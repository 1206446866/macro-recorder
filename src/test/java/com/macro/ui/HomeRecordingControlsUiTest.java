package com.macro.ui;

import com.github.kwhat.jnativehook.NativeInputEvent;
import com.github.kwhat.jnativehook.mouse.NativeMouseEvent;
import com.macro.config.AppConfig;
import com.macro.flow.MacroFlow;
import com.macro.flow.MacroFlowFactory;
import com.macro.model.MacroAction;
import com.macro.recorder.MacroRecorder;
import com.macro.recorder.RecordingControlInputFilter;
import com.macro.ui.filedialog.FileDialogService;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import javax.swing.JButton;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 在真实 Swing 布局上程序化验证主页输入过滤，不注册钩子或向桌面发送鼠标输入。 */
public final class HomeRecordingControlsUiTest {
    private static MacroFrame frame;
    private static MacroRecorder recorder;
    private static RecordingControlInputFilter filter;
    private static final AtomicInteger fileDialogCalls = new AtomicInteger();

    public static void main(String[] args) {
        int exitCode = 0;
        try {
            verifyIsolatedConfiguration();
            runTests();
        } catch (Throwable failure) {
            failure.printStackTrace();
            exitCode = 1;
        } finally {
            try {
                // 先执行既有停止回调，再销毁窗口，防止回调重新显示已清理的测试窗口。
                edt(() -> { if (recorder != null) recorder.stop(); });
                edt(() -> {
                    if (frame != null) {
                        ((Timer) field(frame, "recordingBarTimer")).stop();
                        frame.dispose();
                    }
                });
            } catch (Exception failure) {
                failure.printStackTrace();
                exitCode = 1;
            }
        }
        System.exit(exitCode);
    }

    /** 测试必须加载隔离目录中的生产类，禁止写入项目根目录的个人配置。 */
    private static void verifyIsolatedConfiguration() throws Exception {
        Path expected = Path.of("target", "recording-control-tests", "classes").toAbsolutePath().normalize();
        Path source = Path.of(AppConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
        check(source.equals(expected), "请将生产类复制至 target/recording-control-tests/classes 后运行本测试");
        check(new AppConfig().getApplicationDirectory().toPath().toAbsolutePath().normalize().equals(expected),
                "测试配置未隔离，已拒绝创建主窗口");
    }

    private static void runTests() throws Exception {
        edt(() -> {
            frame = new MacroFrame();
            frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            frame.setTitle("主页录制控件程序化验证");
            recorder = (MacroRecorder) field(frame, "recorder");
            filter = (RecordingControlInputFilter) field(recorder, "controlInputFilter");
            Properties properties = (Properties) field(field(frame, "appConfig"), "properties");
            properties.setProperty("record.startBehavior", "NONE");
            properties.setProperty("record.mouseMove", "false");
            FileDialogService cancelledDialog = (parent, options) -> {
                fileDialogCalls.incrementAndGet();
                return Optional.empty();
            };
            declared(MacroFrame.class, "fileDialogs").set(frame, cancelledDialog);
            frame.setVisible(true);
        });
        edt(() -> { }); // 等待主窗口构造函数中已有的初始 pack。
        edt(() -> {
            frame.setLocation(30, 30);
            invoke(frame, "showRecordButton");
            frame.validate();
        });
        settleLayout();
        edt(() -> verifyHomeRegions("recordButton", "selectWindowButton"));
        verifyMovedAndResizedRegions();
        verifyRecordingAndSaveAsLifecycle();
        System.out.println("通过：主页四个录制控件实际物理区域、区域间隙、窗口移动和改宽后的区域历史、启动/停止排队输入、合法外部操作、另存为实际状态防护及停止/失败恢复、暂停动态图标；未发送真实鼠标输入");
    }

    /** 使用独立 Win32 查询及组件坐标换算核验 NativeHook 供应器的实际物理像素区域。 */
    private static void verifyHomeRegions(String... visibleButtons) throws Exception {
        invoke(frame, "refreshRecordingControlBounds");
        RecordingControlInputFilter.Region region = currentRegion();
        check(region.areas().size() == visibleButtons.length, "主页过滤区域数量不正确：" + region.areas());
        check(!region.keyboardFocused(), "主页过滤扩大到了整个主窗口的键盘输入");
        List<Rectangle> expected = new ArrayList<>();
        for (String name : visibleButtons) {
            JButton control = button(name);
            check(control.isShowing(), "应显示的录制控件不可见：" + name);
            Rectangle physical = physicalVisibleBounds(control);
            expected.add(physical);
            check(region.areas().contains(physical), "控件物理区域与实际布局不一致：" + name + "，应为" + physical + "，实际" + region.areas());
            check(filter.containsCurrentPoint(physical.getCenterX(), physical.getCenterY()), "录制控件中心没有纳入过滤：" + name);
        }
        Rectangle first = expected.get(0);
        Rectangle second = expected.get(1);
        check(first.getMaxX() < second.x, "测试布局没有提供可验证的按钮间隙");
        int gapX = (int) Math.round((first.getMaxX() + second.x) / 2.0);
        int gapY = (int) first.getCenterY();
        check(!filter.containsCurrentPoint(gapX, gapY), "多个录制按钮的外接矩形错误屏蔽了中间区域");
        check(!filter.ignoreMouseMotion(mouse(NativeMouseEvent.NATIVE_MOUSE_MOVED, gapX, gapY, now())),
                "录制按钮间隙的正常鼠标移动被过滤");
        System.out.println("区域核验：DPI=" + Coordinates.INSTANCE.GetDpiForWindow(window()) + "，控件=" + List.of(visibleButtons) + "，物理区域=" + expected);
    }

    private static void verifyMovedAndResizedRegions() throws Exception {
        AtomicReference<Rectangle> oldControl = new AtomicReference<>();
        AtomicReference<Long> oldTime = new AtomicReference<>();
        edt(() -> {
            filter.reset();
            oldControl.set(physicalVisibleBounds(button("recordButton")));
            oldTime.set(now());
            frame.setLocation(frame.getX() + 120, frame.getY() + 70);
        });
        settleLayout();
        edt(() -> {
            verifyHomeRegions("recordButton", "selectWindowButton");
            Rectangle old = oldControl.get();
            int oldX = old.x + 2;
            int oldY = old.y + 2;
            check(!currentRegion().areas().stream().anyMatch(area -> area.contains(oldX, oldY)), "移动距离不足以验证旧按钮位置");
            check(filter.ignoreMouseMotion(mouse(NativeMouseEvent.NATIVE_MOUSE_MOVED, oldX, oldY, oldTime.get())),
                    "窗口移动后，延迟派发的旧按钮事件没有按历史区域过滤");
            check(!filter.ignoreMouseMotion(mouse(NativeMouseEvent.NATIVE_MOUSE_MOVED, oldX, oldY, now())),
                    "窗口移动后仍错误过滤旧按钮位置");
            Dimension size = frame.getSize();
            frame.setSize(size.width + 80, size.height);
        });
        settleLayout();
        edt(() -> verifyHomeRegions("recordButton", "selectWindowButton"));
    }

    private static void verifyRecordingAndSaveAsLifecycle() throws Exception {
        edt(() -> {
            seedCurrentMacro();
            invoke(frame, "refreshActionState");
            check(button("saveAsButton").isEnabled(), "含动作的空闲宏未启用另存为");
            long queuedAt = now();
            Rectangle start = physicalVisibleBounds(button("recordButton"));
            Rectangle select = physicalVisibleBounds(button("selectWindowButton"));
            beginWithoutHook();
            // 启动回调已经运行后再派发启动点击，覆盖 NativeHook 与 EDT 的队列顺序差异。
            recorder.nativeMousePressed(mouse(NativeMouseEvent.NATIVE_MOUSE_PRESSED, centerX(start), centerY(start), queuedAt));
            recorder.nativeMouseReleased(mouse(NativeMouseEvent.NATIVE_MOUSE_RELEASED, centerX(start), centerY(start), now()));
            simulateClick(select);
            check(recorder.getActions().isEmpty(), "启动或指定窗口按钮点击进入了宏动作");
            invoke(frame, "refreshSaveAsState");
            check(!button("saveAsButton").isEnabled(), "实际录制期间另存为未禁用");
            invoke(frame, "saveMacroAs");
            button("saveAsButton").doClick();
            check(fileDialogCalls.get() == 0, "录制中调用了另存为文件面板");

            // 即使 UI 标志或按钮可用性滞后，操作入口也必须以录制器真实状态为准。
            declared(MacroFrame.class, "recording").setBoolean(frame, false);
            button("saveAsButton").setEnabled(true);
            button("saveAsButton").doClick();
            invoke(frame, "saveMacroAs");
            invoke(frame, "refreshSaveAsState");
            check(fileDialogCalls.get() == 0 && !button("saveAsButton").isEnabled(), "过期 UI 状态绕过了录制中的另存为限制");
            declared(MacroFrame.class, "recording").setBoolean(frame, true);

            invoke(frame, "showRecordStopButton");
            frame.validate();
        });
        settleLayout();
        edt(() -> {
            verifyHomeRegions("stopRecordButton", "recordingImageButton");
            simulateClick(physicalVisibleBounds(button("stopRecordButton")));
            simulateClick(physicalVisibleBounds(button("recordingImageButton")));
            check(recorder.getActions().isEmpty(), "主页停止或图像操作按钮点击进入了宏动作");
            verifyRecordingIcons();

            Rectangle outside = new Rectangle(5, 5, 1, 1);
            check(!filter.containsCurrentPoint(5, 5), "测试外部坐标与控件区域重叠");
            simulateClick(outside);
            check(recorder.getActions().size() == 2, "录制控件之外的合法点击没有正常写入");
            // 外部按下后在操作控件上松开，必须保留已记录按下的配对释放。
            recorder.nativeMousePressed(mouse(NativeMouseEvent.NATIVE_MOUSE_PRESSED, 5, 5, now()));
            Rectangle stop = physicalVisibleBounds(button("stopRecordButton"));
            recorder.nativeMouseReleased(mouse(NativeMouseEvent.NATIVE_MOUSE_RELEASED, centerX(stop), centerY(stop), now()));
            check(recorder.getActions().size() == 4, "跨控件区域松开导致普通点击的释放丢失");
            recorder.nativeMousePressed(mouse(NativeMouseEvent.NATIVE_MOUSE_PRESSED, centerX(stop), centerY(stop), now()));
            invoke(frame, "stopRecording");
            recorder.nativeMouseReleased(mouse(NativeMouseEvent.NATIVE_MOUSE_RELEASED, centerX(stop), centerY(stop), now()));
            check(recorder.getActions().size() == 4, "终止按钮按下或停止后的释放污染了宏");
        });
        edt(() -> { }); // 执行 recorder.stop() 排队的既有统一 UI 收尾。
        edt(() -> {
            check(!recorder.getRecordingStatus().recording() && button("saveAsButton").isEnabled(), "停止后含动作宏的另存为未恢复");
            check(MacroFlowFactory.extractActions((MacroFlow) field(frame, "macroFlow")).size() == 4, "停止回调丢失有效动作");
            invoke(frame, "saveMacroAs");
            button("saveAsButton").doClick();
            check(fileDialogCalls.get() == 2, "停止后另存为入口未恢复，或重复触发了文件面板");

            beginWithoutHook();
            invoke(frame, "initializeNewRecordingState");
            invoke(frame, "showRecordStopButton");
            invoke(frame, "refreshSaveAsState");
            recorder.stop();
        });
        edt(() -> { });
        edt(() -> {
            check(button("saveAsButton").isEnabled(), "停止后未恢复另存为原有的按钮状态");
            invoke(frame, "saveMacroAs");
            button("saveAsButton").doClick();
            check(fileDialogCalls.get() == 2, "空动作宏仍打开了另存为文件面板");
            seedCurrentMacro();
            Object beforeFailure = field(frame, "macroFlow");
            declared(MacroFrame.class, "recording").setBoolean(frame, true);
            button("saveAsButton").setEnabled(false);
            invoke(frame, "restoreRecordingStartFailureState");
            check(button("saveAsButton").isEnabled() && field(frame, "macroFlow") == beforeFailure,
                    "启动失败恢复没有保留原宏或恢复另存为");
            check(button("recordButton").isShowing() && button("selectWindowButton").isShowing(), "停止或启动失败后没有恢复主页录制入口");
        });
    }

    /** 仅驱动已有状态刷新，不进入 DWM 模式或改动窗口装饰。 */
    private static void verifyRecordingIcons() throws Exception {
        check(iconType(button("recordingBarCaptureButton")).equals("IMAGE_CAPTURE"), "截图图标不正确");
        check(iconType(button("recordingBarStopButton")).equals("STOP"), "终止图标不正确");
        declared(MacroFrame.class, "compactRecording").setBoolean(frame, true);
        try {
            invoke(frame, "refreshRecordingControlBar");
            check(iconType(button("recordingBarPauseButton")).equals("PAUSE") && button("recordingBarPauseButton").getText().equals("暂停"), "录制中的暂停图标与文字不同步");
            check(recorder.pause(), "程序化暂停失败");
            invoke(frame, "refreshRecordingControlBar");
            check(iconType(button("recordingBarPauseButton")).equals("PLAY") && button("recordingBarPauseButton").getText().equals("继续"), "暂停后的继续图标与文字不同步");
            check(recorder.resume(), "程序化继续失败");
            invoke(frame, "refreshRecordingControlBar");
            check(iconType(button("recordingBarPauseButton")).equals("PAUSE"), "继续录制后未恢复暂停图标");
        } finally {
            declared(MacroFrame.class, "compactRecording").setBoolean(frame, false);
        }
    }

    private static void beginWithoutHook() throws Exception {
        Field state = declared(MacroRecorder.class, "state");
        for (Object constant : state.getType().getEnumConstants()) {
            if (((Enum<?>) constant).name().equals("RECORDING")) state.set(recorder, constant);
        }
        ((List<?>) field(recorder, "actions")).clear();
        ((List<?>) field(recorder, "recordedNodes")).clear();
        declared(MacroRecorder.class, "lastTime").setLong(recorder, System.currentTimeMillis());
        declared(MacroFrame.class, "recording").setBoolean(frame, true);
        declared(MacroFrame.class, "recordingControlRegionEnabled").setBoolean(frame, false);
        invoke(frame, "refreshRecordingControlBounds");
        filter.reset();
    }

    private static void seedCurrentMacro() throws Exception {
        List<MacroAction> actions = new ArrayList<>(List.of(MacroAction.keyPress(KeyEvent.VK_A, "A", 0)));
        MacroFlow flow = MacroFlowFactory.fromActions(actions);
        declared(MacroFrame.class, "actions").set(frame, actions);
        declared(MacroFrame.class, "macroFlow").set(frame, flow);
        ((ActionTimelinePanel) field(frame, "actionTimelinePanel")).setFlow(flow);
    }

    private static Rectangle physicalVisibleBounds(JButton control) {
        Rectangle visible = control.getVisibleRect();
        Point logical = SwingUtilities.convertPoint(control, visible.x, visible.y, frame.getRootPane());
        WinDef.POINT clientOrigin = new WinDef.POINT();
        check(Coordinates.INSTANCE.ClientToScreen(window(), clientOrigin), "无法获取客户区物理原点");
        double scale = Coordinates.INSTANCE.GetDpiForWindow(window()) / 96.0;
        int left = clientOrigin.x + (int) Math.round(logical.x * scale);
        int top = clientOrigin.y + (int) Math.round(logical.y * scale);
        int right = clientOrigin.x + (int) Math.round((logical.x + visible.width) * scale);
        int bottom = clientOrigin.y + (int) Math.round((logical.y + visible.height) * scale);
        return new Rectangle(left, top, right - left, bottom - top);
    }

    private static RecordingControlInputFilter.Region currentRegion() throws Exception {
        return (RecordingControlInputFilter.Region) invoke(frame, "getRecordingControlRegion");
    }

    private static void simulateClick(Rectangle region) throws Exception {
        recorder.nativeMousePressed(mouse(NativeMouseEvent.NATIVE_MOUSE_PRESSED, centerX(region), centerY(region), now()));
        recorder.nativeMouseReleased(mouse(NativeMouseEvent.NATIVE_MOUSE_RELEASED, centerX(region), centerY(region), now()));
    }

    private static NativeMouseEvent mouse(int type, int x, int y, long when) throws Exception {
        NativeMouseEvent event = new NativeMouseEvent(type, 0, x, y, 1, NativeMouseEvent.BUTTON1);
        declared(NativeInputEvent.class, "when").setLong(event, when & 0xFFFFFFFFL);
        return event;
    }

    private static long now() { return Kernel32.INSTANCE.GetTickCount64(); }
    private static int centerX(Rectangle area) { return (int) area.getCenterX(); }
    private static int centerY(Rectangle area) { return (int) area.getCenterY(); }
    private static WinDef.HWND window() { return new WinDef.HWND(Native.getComponentPointer(frame)); }
    private static JButton button(String name) throws Exception { return (JButton) field(frame, name); }
    private static String iconType(JButton button) throws Exception {
        check(button.getIcon() != null, "录制按钮缺少图标：" + button.getText());
        check(button.getIcon().getIconWidth() > 0 && button.getIconTextGap() > 0, "录制按钮图标尺寸或文字间距不正确");
        return ((Enum<?>) field(button.getIcon(), "type")).name();
    }

    private static void settleLayout() throws Exception {
        edt(() -> { frame.validate(); invoke(frame, "refreshRecordingControlBounds"); });
        Thread.sleep(60);
        edt(() -> { frame.validate(); invoke(frame, "refreshRecordingControlBounds"); });
    }

    private static Field declared(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    private static Object field(Object target, String name) throws Exception { return declared(target.getClass(), name).get(target); }
    private static Object invoke(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }
    private static void edt(CheckedRunnable action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try { action.run(); }
            catch (Throwable exception) { failure.set(exception); }
        });
        if (failure.get() instanceof Exception exception) throw exception;
        if (failure.get() instanceof Error error) throw error;
    }
    private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
    @FunctionalInterface private interface CheckedRunnable { void run() throws Exception; }
    private interface Coordinates extends StdCallLibrary {
        Coordinates INSTANCE = Native.load("user32", Coordinates.class, W32APIOptions.DEFAULT_OPTIONS);
        boolean ClientToScreen(WinDef.HWND window, WinDef.POINT point);
        int GetDpiForWindow(WinDef.HWND window);
    }
}
