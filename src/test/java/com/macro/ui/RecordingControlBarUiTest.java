package com.macro.ui;

import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.macro.config.AppConfig;
import com.macro.flow.NodeType;
import com.macro.recorder.MacroRecorder;
import com.macro.ui.capture.ScreenCaptureController;
import com.macro.ui.window.WindowsRecordingTitleBar;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 在真实 Swing 窗口上测试布局和回调；按钮由程序触发，不冒充真实鼠标验收。 */
public final class RecordingControlBarUiTest {
    private static MacroFrame frame;
    private static JFrame extra;
    private static MacroRecorder recorder;
    private static long originalWindowProcedure;
    private static final int WM_NCHITTEST = 0x0084;
    private static final int WM_SYSCOMMAND = 0x0112;
    private static final int SC_RESTORE = 0xF120;
    private static final int SC_CLOSE = 0xF060;

    public static void main(String[] args) throws Exception {
        int exitCode = 0;
        try { runTests(); }
        catch (Throwable failure) { failure.printStackTrace(); exitCode = 1; }
        // 独立界面测试与应用默认关闭行为一致，不把原生/AWT 后台线程留在测试终端。
        System.exit(exitCode);
    }

    private static void runTests() throws Exception {
        try {
            edt(() -> {
                frame = new MacroFrame();
                frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                frame.setTitle("录制控制栏自动验证");
                frame.setVisible(true);
                extra = new JFrame("独立图像窗口恢复验证");
                extra.setSize(300, 120);
                extra.setLocation(20, 200);
                extra.setVisible(true);
            });
            edt(() -> { }); // 等待构造函数中已有的初始 pack。
            AtomicReference<Rectangle> fullBounds = new AtomicReference<>();
            AtomicReference<Dimension> fullMinimum = new AtomicReference<>();
            edt(() -> {
                recorder = (MacroRecorder) field(frame, "recorder");
                AppConfig config = (AppConfig) field(frame, "appConfig");
                Properties properties = (Properties) field(config, "properties");
                properties.setProperty("record.startBehavior", "CONTROL_BAR");
                properties.setProperty("record.mouseMove", "false");
                fullBounds.set(frame.getBounds());
                fullMinimum.set(frame.getMinimumSize());
                originalWindowProcedure = User32.INSTANCE.GetWindowLongPtr(window(), -4).longValue();
                beginWithoutHook();
                Method seed = MacroRecorder.class.getDeclaredMethod("recordAction", com.macro.model.MacroAction.class);
                seed.setAccessible(true);
                seed.invoke(recorder, com.macro.model.MacroAction.keyPress(java.awt.event.KeyEvent.VK_A, "A", 0));
                seed.invoke(recorder, com.macro.model.MacroAction.keyRelease(java.awt.event.KeyEvent.VK_A, "A", 0));
                invoke(frame, "applyRecordingStartBehavior");
            });
            await(() -> nativeCaption().isActive()
                    && frame.getHeight() < 150, "原生录制标题栏布局未就绪");
            edt(() -> {
                invoke(frame, "refreshRecordingControlBar");
                check(!frame.isUndecorated() && frame.getHeight() < 150 && frame.getWidth() < 800, "紧凑尺寸或原生装饰不正确");
                check((frame.getExtendedState() & Frame.ICONIFIED) == 0, "控制栏模式不应主动最小化");
                check(frame.getContentPane() == field(frame, "recordingControlBar"), "没有在同一主窗口切换内容");
                String[] names = {"recordingBarCaptureButton", "recordingBarPauseButton", "recordingBarStopButton"};
                int previous = -1;
                for (String name : names) {
                    JButton button = (JButton) field(frame, name);
                    check(button.getX() > previous && button.getWidth() >= button.getPreferredSize().width,
                            "按钮顺序错误或被裁切：" + name);
                    check(button.getIcon() != null && button.getIcon().getIconWidth() == 16,
                            "录制按钮没有复用项目的轻量图标：" + name);
                    previous = button.getX();
                }
                check(field(button("recordingBarPauseButton").getIcon(), "type").toString().equals("PAUSE"),
                        "正在录制时应显示暂停图标");
            });
            captureCaptionForInspection("recording-native-caption.png");
            verifyCaptionRepaintStability();
            edt(RecordingControlBarUiTest::verifyNativeCaptionContract);
            edt(() -> frame.setExtendedState(Frame.MAXIMIZED_BOTH));
            await(() -> (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH,
                    "原生最大化系统命令没有生效");
            edt(() -> {
                invoke(frame, "refreshRecordingControlBar");
                verifyNativeCaptionContract();
            });
            captureCaptionForInspection("recording-native-caption-maximized.png");
            edt(() -> systemCommand(SC_RESTORE));
            await(() -> (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) == 0 && frame.getHeight() < 150,
                    "原生还原系统命令没有恢复紧凑尺寸");
            captureCaptionForInspection("recording-native-caption-restored.png");
            edt(() -> {
                button("recordingBarPauseButton").doClick();
                check(recorder.getRecordingStatus().paused() && button("recordingBarPauseButton").getText().equals("继续"), "暂停联动错误");
                check(field(button("recordingBarPauseButton").getIcon(), "type").toString().equals("PLAY"),
                        "暂停后继续按钮没有切换为播放图标");
            });
            captureCaptionForInspection("recording-native-caption-paused.png");
            edt(() -> {
                button("recordingBarCaptureButton").doClick();
            });
            await(() -> captureOverlay() != null, "截图选区未打开");
            edt(() -> {
                check(!frame.isVisible() && !extra.isVisible(), "截图前没有隐藏所有可见应用窗口");
                controller().cancelCapture();
                invoke(frame, "refreshRecordingControlBar");
                check(frame.isVisible() && extra.isVisible(), "取消后未恢复全部窗口");
                check(recorder.getRecordingStatus().paused(), "取消截图丢失暂停状态");
                button("recordingBarCaptureButton").doClick();
            });
            await(() -> captureOverlay() != null, "第二次截图未打开");
            edt(() -> {
                // 用已确认图像回调测试保存和恢复；不模拟用户实际框选。
                Object overlay = captureOverlay();
                @SuppressWarnings("unchecked") Consumer<BufferedImage> confirmed =
                        (Consumer<BufferedImage>) field(overlay, "onConfirmed");
                confirmed.accept(new BufferedImage(12, 12, BufferedImage.TYPE_INT_ARGB));
            });
            await(() -> !recorder.getRecordingStatus().capturing(), "图像保存未完成");
            edt(() -> {
                check(recorder.getRecordingStatus().paused(), "成功截图后错误继续录制");
                check(recorder.getRecordedNodes().stream().anyMatch(node -> node.getType() == NodeType.IMAGE_CHECK), "没有生成现有图像节点");
                button("recordingBarPauseButton").doClick();
                check(!recorder.getRecordingStatus().paused(), "继续失败");
                check(field(button("recordingBarPauseButton").getIcon(), "type").toString().equals("PAUSE"),
                        "恢复录制后没有恢复暂停图标");
                button("recordingBarMinimizeButton").doClick();
            });
            await(() -> (frame.getExtendedState() & Frame.ICONIFIED) != 0, "最小化不是任务栏最小化");
            edt(() -> { systemCommand(SC_RESTORE); frame.setVisible(true); });
            await(() -> (frame.getExtendedState() & Frame.ICONIFIED) == 0, "窗口无法恢复");
            captureCaptionForInspection("recording-native-caption-after-capture.png");
            edt(() -> {
                check(frame.getContentPane() == field(frame, "recordingControlBar") && recorder.getRecordingStatus().recording(), "恢复后状态错误");
                button("recordingBarStopButton").doClick();
            });
            edt(() -> {
                check(!recorder.getRecordingStatus().recording(), "终止没有停止录制");
                check(frame.getContentPane() == field(frame, "pagePanel"), "停止后没有恢复完整页面");
                check(frame.getBounds().equals(fullBounds.get()) && frame.getMinimumSize().equals(fullMinimum.get())
                        && frame.isResizable(), "完整窗口尺寸、位置或缩放能力未恢复");
                check(!nativeCaption().isActive() && User32.INSTANCE.GetWindowLongPtr(window(), -4).longValue()
                        == originalWindowProcedure, "停止后没有撤销标题栏扩展或恢复原生消息链");
                check(recorder.getActions().size() == 2 && !((com.macro.flow.MacroFlow) field(frame, "macroFlow")).getNodeMap().isEmpty(), "停止丢失已有动作");
                beginWithoutHook();
                invoke(frame, "applyRecordingStartBehavior");
                button("recordingBarCaptureButton").doClick();
            });
            await(() -> captureOverlay() != null, "停止测试的截图未打开");
            edt(() -> recorder.nativeKeyPressed(key(NativeKeyEvent.NATIVE_KEY_PRESSED, NativeKeyEvent.VC_F12)));
            edt(() -> {
                check(frame.isVisible() && frame.getContentPane() == field(frame, "pagePanel"), "截图中 F12 未恢复主窗口");
                check(captureOverlay() == null && !recorder.getRecordingStatus().recording(), "F12 未清理选区或录制状态");
                frame.setExtendedState(Frame.MAXIMIZED_BOTH);
            });
            await(() -> (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH, "最大化状态未生效");
            edt(() -> { beginWithoutHook(); invoke(frame, "applyRecordingStartBehavior"); });
            await(() -> frame.getHeight() < 150, "最大化窗口不能切换成紧凑条");
            edt(() -> button("recordingBarStopButton").doClick());
            edt(() -> {
                check((frame.getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH, "停止后原最大化状态丢失");
                frame.setExtendedState(Frame.NORMAL);
                ((Properties) field(field(frame, "appConfig"), "properties")).setProperty("record.startBehavior", "MINIMIZE");
                beginWithoutHook();
                invoke(frame, "applyRecordingStartBehavior");
            });
            await(() -> (frame.getExtendedState() & Frame.ICONIFIED) != 0, "开始录制最小化配置失效");
            edt(() -> {
                check(frame.getContentPane() == field(frame, "pagePanel") && !(boolean) field(frame, "compactRecording"),
                        "最小化模式不应同时切换控制栏");
                check(!(boolean) field(frame, "recordingControlRegionEnabled"), "完整主窗不应作为整个录制过滤区域");
            });
            edt(() -> button("recordingBarStopButton").doClick());
            edt(() -> check(!recorder.getRecordingStatus().recording()
                    && frame.getContentPane() == field(frame, "pagePanel"), "自动最小化后停止未恢复完整窗口"));
            await(() -> (frame.getExtendedState() & Frame.ICONIFIED) == 0, "停止后未恢复主动最小化的窗口");
            edt(() -> {
                Properties properties = (Properties) field(field(frame, "appConfig"), "properties");
                properties.setProperty("record.startBehavior", "NONE");
                properties.setProperty("minimizeOnPlayStart", "true");
                Rectangle bounds = frame.getBounds();
                int state = frame.getExtendedState();
                beginWithoutHook();
                invoke(frame, "applyRecordingStartBehavior");
                check(frame.getBounds().equals(bounds) && frame.getExtendedState() == state,
                        "无模式改变了录制前的窗口尺寸或状态，或受到了播放配置影响");
                check(frame.getContentPane() == field(frame, "pagePanel") && frame.isResizable(), "无模式改变了完整主窗口");
                check(!(boolean) field(frame, "recordingControlRegionEnabled"), "无模式不应过滤整个主窗口");
                invoke(frame, "stopRecording");
            });
            edt(() -> check(!recorder.getRecordingStatus().recording()
                    && !(boolean) field(frame, "recordingControlRegionEnabled"), "无模式停止后遗留录制或过滤状态"));
            edt(() -> frame.setExtendedState(Frame.MAXIMIZED_BOTH));
            await(() -> (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH,
                    "最小化模式验证前最大化未生效");
            edt(() -> {
                ((Properties) field(field(frame, "appConfig"), "properties")).setProperty("record.startBehavior", "MINIMIZE");
                beginWithoutHook();
                invoke(frame, "applyRecordingStartBehavior");
            });
            await(() -> (frame.getExtendedState() & Frame.ICONIFIED) != 0, "最大化窗口无法按设置最小化");
            edt(() -> invoke(frame, "stopRecording"));
            edt(() -> check((frame.getExtendedState() & Frame.ICONIFIED) == 0
                            && (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH,
                    "录制停止未保留最小化之前的最大化状态"));
            verifyNativeCloseCommand();
            System.out.println("通过：三种录制启动行为、混合标题栏布局和命中、自绘最小化及窗口样式、程序化最大化/还原、自绘最小化、关闭消息、暂停截图成功/取消、全部窗口显隐、停止/F12 和消息链恢复及动作保留；未模拟真实鼠标交互");
        } finally {
            // 先让既有停止回调执行，再销毁窗口，避免测试清理后回调重新恢复主窗口。
            edt(() -> { if (recorder != null) recorder.stop(); });
            edt(() -> {
                if (frame != null) ((Timer) field(frame, "recordingBarTimer")).stop();
                for (Window window : Window.getWindows()) window.dispose();
            });
        }
    }

    private static void beginWithoutHook() throws Exception {
        Field state = declared(MacroRecorder.class, "state");
        @SuppressWarnings({"unchecked", "rawtypes"}) Object recording = Enum.valueOf((Class) state.getType(), "RECORDING");
        state.set(recorder, recording);
        declared(MacroFrame.class, "recording").setBoolean(frame, true);
        declared(MacroFrame.class, "recordingControlHwnd").set(frame, new WinDef.HWND(Native.getComponentPointer(frame)));
        declared(MacroFrame.class, "recordingControlRegionEnabled").setBoolean(frame, true);
        declared(MacroRecorder.class, "lastTime").setLong(recorder, System.currentTimeMillis());
        ((com.macro.recorder.RecordingControlInputFilter) field(recorder, "controlInputFilter")).reset();
        recorder.refreshControlRegion();
    }
    private static NativeKeyEvent key(int type, int code) {
        return new NativeKeyEvent(type, 0, code, code, NativeKeyEvent.CHAR_UNDEFINED);
    }
    /** 使用物理坐标发送命中查询，不移动鼠标，也不伪装成人工操作验收。 */
    private static void verifyNativeCaptionContract() throws Exception {
        WindowsRecordingTitleBar caption = nativeCaption();
        check(caption.isActive(), "混合标题栏没有启用");
        int styles = User32.INSTANCE.GetWindowLong(window(), -16);
        check((styles & 0x00C00000) == 0x00C00000 && (styles & 0x00040000) != 0
                        && (styles & 0x000B0000) == 0,
                "DWM 边框丢失或原生系统按钮样式残留");
        WinDef.RECT outer = new WinDef.RECT();
        check(User32.INSTANCE.GetWindowRect(window(), outer), "无法获取测试窗口物理位置");
        WinDef.POINT client = new WinDef.POINT();
        check(NativeUser.INSTANCE.ClientToScreen(window(), client), "无法取得客户区原点");
        double scale = NativeUser.INSTANCE.GetDpiForWindow(window()) / 96.0;
        for (String name : new String[]{"recordingBarMinimizeButton", "recordingBarCaptureButton",
                "recordingBarPauseButton", "recordingBarStopButton"}) {
            JButton control = button(name);
            Rectangle logical = SwingUtilities.convertRectangle(control.getParent(), control.getBounds(), frame.getRootPane());
            check(hitTest(client.x + (int) Math.round(logical.getCenterX() * scale),
                    client.y + (int) Math.round(logical.getCenterY() * scale)) == 1,
                    "自绘按钮没有按客户区处理点击：" + name);
        }
        JComponent row = (JComponent) field(frame, "recordingCaptionRow");
        Rectangle rowBounds = SwingUtilities.convertRectangle(row.getParent(), row.getBounds(), frame.getRootPane());
        int dragX = client.x + (int) Math.round((rowBounds.x + 16) * scale);
        int dragY = client.y + (int) Math.round((rowBounds.y + rowBounds.height / 2.0) * scale);
        check(hitTest(dragX, dragY) == 2, "标题文字区域没有保留原生拖动命中");
        if ((frame.getExtendedState() & Frame.MAXIMIZED_BOTH) == 0) {
            check(hitTest(outer.left + 1, (outer.top + outer.bottom) / 2) == 10,
                    "左侧原生缩放边框命中丢失");
            check(hitTest((outer.left + outer.right) / 2, outer.top + 1) == 2,
                    "顶部应保留拖动，不能启动纵向缩放");
        }
    }
    private static int hitTest(int screenX, int screenY) {
        long packed = (screenX & 0xFFFFL) | ((screenY & 0xFFFFL) << 16);
        return User32.INSTANCE.SendMessage(window(), WM_NCHITTEST, new WinDef.WPARAM(0),
                new WinDef.LPARAM(packed)).intValue();
    }
    private static void systemCommand(int command) {
        User32.INSTANCE.SendMessage(window(), WM_SYSCOMMAND, new WinDef.WPARAM(command), new WinDef.LPARAM(0));
    }
    private static WinDef.HWND window() { return new WinDef.HWND(Native.getComponentPointer(frame)); }
    private static WindowsRecordingTitleBar nativeCaption() {
        try { return (WindowsRecordingTitleBar) field(frame, "nativeRecordingTitleBar"); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    /** 独立窗口验证系统关闭命令和销毁回调，不影响主窗口中的录制回归。 */
    private static void verifyNativeCloseCommand() throws Exception {
        AtomicReference<JFrame> closingFrame = new AtomicReference<>();
        AtomicReference<WindowsRecordingTitleBar> closingCaption = new AtomicReference<>();
        try {
            edt(() -> {
                JFrame fixture = new JFrame("原生关闭消息验证");
                closingFrame.set(fixture);
                fixture.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                JPanel captionRow = new JPanel(new BorderLayout());
                captionRow.add(new JLabel("关闭验证"), BorderLayout.CENTER);
                JButton fixtureButton = new JButton("测试");
                captionRow.add(fixtureButton, BorderLayout.EAST);
                JPanel content = new JPanel(new BorderLayout());
                content.add(captionRow, BorderLayout.NORTH);
                fixture.setContentPane(content);
                fixture.setSize(560, 150);
                fixture.setVisible(true);
                WindowsRecordingTitleBar caption = new WindowsRecordingTitleBar(fixture, captionRow,
                        java.util.List.of(fixtureButton));
                closingCaption.set(caption);
                caption.activate();
            });
            await(() -> closingCaption.get().isActive(), "关闭验证标题栏未启用");
            edt(() -> User32.INSTANCE.SendMessage(new WinDef.HWND(Native.getComponentPointer(closingFrame.get())),
                    WM_SYSCOMMAND, new WinDef.WPARAM(SC_CLOSE), new WinDef.LPARAM(0)));
            await(() -> !closingFrame.get().isDisplayable() && !closingCaption.get().isActive(),
                    "系统关闭命令没有销毁窗口或清理标题栏活动状态");
        } finally {
            edt(() -> {
                if (closingFrame.get() != null && closingFrame.get().isDisplayable()) {
                    if (closingCaption.get() != null) closingCaption.get().deactivate();
                    closingFrame.get().dispose();
                }
            });
        }
    }
    /** 重复绘制父行、更新子组件及改变宽度，验证自绘最小化符号保持可见。 */
    private static void verifyCaptionRepaintStability() throws Exception {
        AtomicReference<Dimension> originalSize = new AtomicReference<>();
        edt(() -> originalSize.set(frame.getSize()));
        for (int iteration = 0; iteration < 6; iteration++) {
            int extraWidth = iteration % 2 == 0 ? 48 : 0;
            edt(() -> {
                frame.setSize(originalSize.get().width + extraWidth, originalSize.get().height);
                JComponent row = (JComponent) field(frame, "recordingCaptionRow");
                row.revalidate();
                row.repaint();
                button("recordingBarPauseButton").repaint();
                invoke(frame, "refreshRecordingControlBar");
            });
            Thread.sleep(60);
        }
        edt(() -> {
            extra.toFront();
            frame.toFront();
            invoke(frame, "refreshRecordingControlBar");
            verifyNativeCaptionContract();
        });
        captureCaptionForInspection("recording-native-caption-repainted.png");
    }
    /** 保存真实窗口画面，并检查自绘最小化按钮命中区确实存在对比符号，避免把透明空白当成成功。 */
    private static void captureCaptionForInspection(String imageName) throws Exception {
        AtomicReference<Boolean> wasTop = new AtomicReference<>();
        edt(() -> {
            wasTop.set(frame.isAlwaysOnTop());
            frame.setAlwaysOnTop(true);
            frame.toFront();
        });
        try {
            Robot robot = new Robot(frame.getGraphicsConfiguration().getDevice());
            robot.delay(250);
            AtomicReference<CaptionObservation> observation = new AtomicReference<>();
            edt(() -> {
                nativeCaption().refresh();
                Rectangle windowBounds = new Rectangle(frame.getLocationOnScreen(), frame.getSize());
                Rectangle captureBounds = windowBounds.intersection(frame.getGraphicsConfiguration().getBounds());
                WinDef.RECT outer = new WinDef.RECT();
                check(User32.INSTANCE.GetWindowRect(window(), outer), "无法取得截图窗口的物理位置");
                int dpi = NativeUser.INSTANCE.GetDpiForWindow(window());
                double scale = dpi / 96.0;
                JButton minimize = button("recordingBarMinimizeButton");
                Rectangle logical = SwingUtilities.convertRectangle(minimize.getParent(), minimize.getBounds(), frame.getRootPane());
                WinDef.POINT client = new WinDef.POINT();
                check(NativeUser.INSTANCE.ClientToScreen(window(), client), "无法取得客户区原点");
                Rectangle[] buttonRanges = {new Rectangle(
                        client.x + (int) Math.round(logical.x * scale),
                        client.y + (int) Math.round(logical.y * scale),
                        (int) Math.round(logical.width * scale), (int) Math.round(logical.height * scale))};
                // 最大化窗口可能超出屏幕边界，只截取显示器内的部分，并保留相应的物理坐标原点。
                Rectangle physicalCapture = new Rectangle(
                        outer.left + (int) Math.round((captureBounds.x - windowBounds.x) * scale),
                        outer.top + (int) Math.round((captureBounds.y - windowBounds.y) * scale),
                        (int) Math.round(captureBounds.width * scale),
                        (int) Math.round(captureBounds.height * scale));
                observation.set(new CaptionObservation(captureBounds, physicalCapture, dpi, buttonRanges));
            });
            CaptionObservation captured = observation.get();
            BufferedImage image = robot.createScreenCapture(captured.logicalCapture());
            Path output = Path.of("target", imageName);
            Files.createDirectories(output.toAbsolutePath().getParent());
            check(ImageIO.write(image, "png", output.toFile()), "标题栏诊断截图未保存");
            // 先落盘再断言；如果出现空白自绘最小化按钮，失败画面仍可供定位。
            verifySystemButtonGlyphs(image, captured, output);
        } finally {
            edt(() -> frame.setAlwaysOnTop(wasTop.get()));
        }
    }
    /** 排除命中区外沿，再按亮度及相对背景的对比度统计系统符号像素。 */
    private static void verifySystemButtonGlyphs(BufferedImage image, CaptionObservation captured, Path output) {
        Rectangle physicalCapture = captured.physicalCapture();
        double imageScaleX = image.getWidth() / (double) physicalCapture.width;
        double imageScaleY = image.getHeight() / (double) physicalCapture.height;
        int margin = Math.max(4, (int) Math.ceil(4 * captured.dpi() / 96.0));
        int[] hitCodes = {1};
        int[] brightCounts = new int[hitCodes.length];
        for (int index = 0; index < hitCodes.length; index++) {
            Rectangle range = captured.buttonRanges()[index];
            check(range.width > margin * 2 && range.height > margin * 2,
                    "自绘最小化按钮命中区不足以核验符号：" + hitCodes[index] + "，区域=" + range + "，截图=" + output);
            int left = (int) Math.ceil((range.x + margin - physicalCapture.x) * imageScaleX);
            int right = (int) Math.floor((range.x + range.width - margin - physicalCapture.x) * imageScaleX);
            int top = (int) Math.ceil((range.y + margin - physicalCapture.y) * imageScaleY);
            int bottom = (int) Math.floor((range.y + range.height - margin - physicalCapture.y) * imageScaleY);
            Rectangle imageRegion = new Rectangle(left, top, Math.max(0, right - left), Math.max(0, bottom - top))
                    .intersection(new Rectangle(0, 0, image.getWidth(), image.getHeight()));
            check(!imageRegion.isEmpty(), "自绘最小化按钮符号区域未落在截图内：" + range + "，截图=" + output);
            int[] luminanceHistogram = new int[256];
            for (int y = imageRegion.y; y < imageRegion.y + imageRegion.height; y++) {
                for (int x = imageRegion.x; x < imageRegion.x + imageRegion.width; x++) {
                    luminanceHistogram[luminance(image.getRGB(x, y))]++;
                }
            }
            int middleCount = imageRegion.width * imageRegion.height / 2;
            int background = 0;
            int cumulative = 0;
            while (background < 255 && cumulative + luminanceHistogram[background] <= middleCount) {
                cumulative += luminanceHistogram[background++];
            }
            // 系统可能使用灰色符号；按相对背景的对比度判断，避免把可见的最小化横线误判为空白。
            for (int value = 0; value < 256; value++) {
                if (Math.abs(value - background) >= 26) {
                    brightCounts[index] += luminanceHistogram[value];
                }
            }
            check(brightCounts[index] >= 3,
                    "自绘最小化按钮命中存在但符号不可见：HT=" + hitCodes[index] + "，对比符号像素=" + brightCounts[index]
                            + "，DPI=" + captured.dpi() + "，物理按钮区域=" + range + "，图像检查区=" + imageRegion
                            + "，背景亮度=" + background + "，截图=" + output.toAbsolutePath());
        }
        System.out.println("自绘最小化按钮可见性：" + output.getFileName() + "，DPI=" + captured.dpi()
                + "，最小化对比像素=" + java.util.Arrays.toString(brightCounts));
    }
    private static int luminance(int rgb) {
        return (299 * ((rgb >>> 16) & 0xFF) + 587 * ((rgb >>> 8) & 0xFF) + 114 * (rgb & 0xFF)) / 1000;
    }
    private record CaptionObservation(Rectangle logicalCapture, Rectangle physicalCapture, int dpi,
                                      Rectangle[] buttonRanges) { }
    private interface NativeUser extends StdCallLibrary {
        NativeUser INSTANCE = Native.load("user32", NativeUser.class, W32APIOptions.DEFAULT_OPTIONS);
        int GetDpiForWindow(WinDef.HWND window);
        boolean ClientToScreen(WinDef.HWND window, WinDef.POINT point);
    }
    private static JButton button(String name) throws Exception { return (JButton) field(frame, name); }
    private static ScreenCaptureController controller() throws Exception { return (ScreenCaptureController) field(frame, "recordingScreenCaptureController"); }
    private static Object captureOverlay() {
        try { return field(controller(), "overlay"); } catch (Exception exception) { throw new RuntimeException(exception); }
    }
    private static Field declared(Class<?> type, String name) throws Exception { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
    private static Object field(Object object, String name) throws Exception { return declared(object.getClass(), name).get(object); }
    private static void invoke(Object object, String name) throws Exception { Method method = object.getClass().getDeclaredMethod(name); method.setAccessible(true); method.invoke(object); }
    private interface Step { void run() throws Exception; }
    private static void edt(Step step) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> { try { step.run(); } catch (Throwable failure) { error.set(failure); } });
        if (error.get() != null) throw new AssertionError("界面验证失败", error.get());
    }
    private static void await(BooleanSupplier condition, String message) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            AtomicReference<Boolean> done = new AtomicReference<>();
            edt(() -> done.set(condition.getAsBoolean()));
            if (done.get()) return;
            Thread.sleep(50);
        }
        throw new AssertionError(message);
    }
    private static void check(boolean result, String message) { if (!result) throw new AssertionError(message); }
}
