package com.macro.ui;

import com.macro.ui.window.WindowsRecordingTitleBar;
import com.sun.jna.Native;
import com.sun.jna.CallbackReference;
import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.*;
import com.sun.jna.platform.win32.WinUser;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.macro.ui.theme.ThemeManager;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import com.macro.config.AppConfig;

/** 屏幕外窗口的合成原生消息检查；不等同于真实鼠标拖边及 GPU 桌面绘制验收。 */
public final class RecordingBarNativeResizeTest {
    private static JFrame frame;
    private static WindowsRecordingTitleBar caption;
    private static RecordingControlBar bar;
    private static JButton minimize, capture, pause, stop;
    private static HWND hwnd;
    private static Pointer awtProc, observerProc;
    private static final AtomicInteger sizingCalls = new AtomicInteger();
    private static final AtomicInteger resizedEvents = new AtomicInteger();
    private static final AtomicReference<Rectangle> forwardedBounds = new AtomicReference<>();
    // 强引用保留到恢复窗口过程后；观察层继续调用原 AWT 过程，不使用 DefWindowProc。
    private static final WinUser.WindowProc observer = (window, message, wParam, lParam) -> {
        if (message == 0x0214) {
            Pointer rect = new Pointer(lParam.longValue());
            forwardedBounds.set(new Rectangle(rect.getInt(0), rect.getInt(4),
                    rect.getInt(8) - rect.getInt(0), rect.getInt(12) - rect.getInt(4)));
            sizingCalls.incrementAndGet();
        }
        return User32.INSTANCE.CallWindowProc(awtProc, window, message, wParam, lParam);
    };

    public static void main(String[] args) {
        int result = 0;
        try {
            Toolkit.getDefaultToolkit().setDynamicLayout(true);
            System.out.println("dynamicLayout=" + Toolkit.getDefaultToolkit().isDynamicLayoutActive());
            check(Toolkit.getDefaultToolkit().isDynamicLayoutActive(), "本检查需要开启原生动态布局");
            check(Path.of(AppConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toString().contains("test-sandboxes"), "需要隔离配置副本");
            SwingUtilities.invokeAndWait(() -> {
                frame = new MacroFrame();
                frame.setAutoRequestFocus(false);
                frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                frame.pack();
                // 观察层直接接到原 AWT 链；主页自绘层按正式切换流程先卸载。
                ((WindowsRecordingTitleBar) field("nativeMainTitleBar")).deactivate();
                hwnd = new HWND(Native.getComponentPointer(frame));
                awtProc = Pointer.createConstant(User32.INSTANCE.GetWindowLongPtr(hwnd, -4).longValue());
                observerProc = CallbackReference.getFunctionPointer(observer);
                check(awtProc != null && Pointer.nativeValue(awtProc) != 0, "没有取得原 AWT 过程");
                Pointer previous = User32.INSTANCE.SetWindowLongPtr(hwnd, -4, observerProc);
                check(awtProc.equals(previous), "观察层没有正确接入原 AWT 消息链");
                frame.addComponentListener(new ComponentAdapter() {
                    @Override public void componentResized(ComponentEvent event) { resizedEvents.incrementAndGet(); }
                });
            });
            SwingUtilities.invokeAndWait(() -> {
                bar = (RecordingControlBar) field("recordingControlBar");
                minimize = (JButton) field("recordingBarMinimizeButton");
                capture = (JButton) field("recordingBarCaptureButton");
                pause = (JButton) field("recordingBarPauseButton");
                stop = (JButton) field("recordingBarStopButton");
                invoke("enterCompactRecordingMode");
                caption = (WindowsRecordingTitleBar) field("nativeRecordingTitleBar");
                frame.setLocation(-12000, -12000);
                frame.setVisible(true);
            });
            Thread.sleep(200);
            RECT original = new RECT();
            User32.INSTANCE.GetWindowRect(hwnd, original);
            int minimumWidth, fixedHeight;
            try (Memory limits = new Memory(40)) {
                limits.clear();
                User32.INSTANCE.SendMessage(hwnd, 0x0024, new WPARAM(0), new LPARAM(Pointer.nativeValue(limits)));
                minimumWidth = limits.getInt(24);
                fixedHeight = limits.getInt(28);
                check(minimumWidth > 0 && fixedHeight > 0 && fixedHeight == limits.getInt(36),
                        "没有取得固定的原生拖动高度");
            }
            User32.INSTANCE.SendMessage(hwnd, 0x0231, new WPARAM(0), new LPARAM(0)); // WM_ENTERSIZEMOVE
            // 单独发送 WM_SIZING，检查原 AWT 过程确实产生动态布局所需的尺寸事件。
            int beforeEvents = resizedEvents.get();
            sendSizing(original.left, original.top, minimumWidth, fixedHeight, 2);
            SwingUtilities.invokeAndWait(() -> { });
            check(resizedEvents.get() > beforeEvents, "WM_SIZING 未进入原 AWT 动态布局通知");
            int[] widths = {minimumWidth, minimumWidth + 150, minimumWidth + 450, minimumWidth};
            for (int index = 0; index < widths.length; index++) {
                int width = widths[index];
                int edge = index % 2 == 0 ? 1 : 2;
                Rectangle constrained = sendSizing(original.left, original.top, width, fixedHeight, edge);
                // 从非 EDT 发起原生尺寸变更；不通过 JFrame.setSize，也不手动 validate。
                check(User32.INSTANCE.SetWindowPos(hwnd, null, constrained.x, constrained.y, constrained.width,
                        constrained.height, 0x0004 | 0x0010), "原生改宽失败");
                Thread.sleep(200);
                SwingUtilities.invokeAndWait(() -> {
                    verify(width);
                    RECT actual = new RECT();
                    check(User32.INSTANCE.GetWindowRect(hwnd, actual)
                            && actual.bottom - actual.top == fixedHeight, "改宽改变了原生窗口固定高度");
                });
            }
            for (int edge = 1; edge <= 8; edge++) {
                Rectangle constrained = sendSizing(original.left, original.top, 10, fixedHeight + 150, edge);
                check(constrained.width >= minimumWidth && constrained.height == fixedHeight,
                        "转发后丢失最小宽度/固定高度约束");
                check(edge == 1 || edge == 4 || edge == 7
                        ? constrained.x + constrained.width == original.left + 10 : constrained.x == original.left,
                        "最小宽度约束改变了相反侧边缘");
            }
            checkDeferredLayoutRecovery(minimumWidth + 450, fixedHeight);
            System.out.println("通过：动态布局开启，WM_SIZING 约束后单次转发到原 AWT 链，尺寸事件及双行布局同步；非真实鼠标验收");
        } catch (Throwable failure) {
            failure.printStackTrace();
            result = 1;
        } finally {
            if (hwnd != null) User32.INSTANCE.SendMessage(hwnd, 0x0232, new WPARAM(0), new LPARAM(0));
            try {
                SwingUtilities.invokeAndWait(() -> {
                    if (caption != null) {
                        caption.deactivate();
                        check(User32.INSTANCE.GetWindowLongPtr(hwnd, -4).longValue() == Pointer.nativeValue(observerProc),
                                "退出紧凑模式没有恢复安装前的窗口过程");
                    }
                    if (observerProc != null && hwnd != null && User32.INSTANCE.IsWindow(hwnd)) {
                        Pointer previous = User32.INSTANCE.SetWindowLongPtr(hwnd, -4, awtProc);
                        check(observerProc.equals(previous), "观察层卸载时消息链异常");
                        check(User32.INSTANCE.GetWindowLongPtr(hwnd, -4).longValue() == Pointer.nativeValue(awtProc),
                                "没有恢复原 AWT 窗口过程");
                    }
                    if (frame != null) {
                        ((Timer) field("recordingBarTimer")).stop();
                        frame.dispose();
                    }
                });
            } catch (Exception failure) { failure.printStackTrace(); result = 1; }
        }
        System.exit(result);
    }

    private static Rectangle sendSizing(int x, int y, int width, int height, int edge) {
        try (Memory rect = new Memory(16)) {
            rect.setInt(0, x); rect.setInt(4, y);
            rect.setInt(8, x + width); rect.setInt(12, y + height);
            int before = sizingCalls.get();
            LRESULT handled = User32.INSTANCE.SendMessage(hwnd, 0x0214, new WPARAM(edge),
                    new LPARAM(Pointer.nativeValue(rect)));
            check(handled.longValue() == 1, "WM_SIZING 已处理矩形时应返回 TRUE");
            check(sizingCalls.get() == before + 1, "WM_SIZING 没有单次转发到原窗口过程");
            Rectangle constrained = new Rectangle(rect.getInt(0), rect.getInt(4),
                    rect.getInt(8) - rect.getInt(0), rect.getInt(12) - rect.getInt(4));
            check(constrained.equals(forwardedBounds.get()), "原 AWT 过程收到的不是约束后的矩形");
            return constrained;
        }
    }

    /** 复现原生刷新时根面板仍保留旧尺寸的分支；不由测试主动 validate 主窗口。 */
    private static void checkDeferredLayoutRecovery(int width, int height) throws Exception {
        check(User32.INSTANCE.SetWindowPos(hwnd, null, 0, 0, width, height, 0x0002 | 0x0004 | 0x0010), "准备改宽失败");
        Thread.sleep(200);
        SwingUtilities.invokeAndWait(() -> {
            frame.getRootPane().setSize(bar.getPreferredSize().width, frame.getRootPane().getHeight());
            frame.getRootPane().validate(); // 保持根面板在旧宽度有效，模拟尺寸事件未完成布局的状态。
            check(frame.getRootPane().getWidth() < frame.getWidth() - frame.getInsets().left - frame.getInsets().right,
                    "未建立旧尺寸根面板夹具");
            try (com.sun.jna.Memory position = new com.sun.jna.Memory(Native.POINTER_SIZE * 2L + 24)) {
                position.clear();
                position.setPointer(0, hwnd.getPointer());
                position.setInt(Native.POINTER_SIZE * 2L + 16, 0x0001 | 0x0002 | 0x0004 | 0x0010);
                User32.INSTANCE.SendMessage(hwnd, 0x0047, new WPARAM(0), new LPARAM(com.sun.jna.Pointer.nativeValue(position)));
            }
            // 与计时刷新/合并消息共用生产入口，在 EDT 返回前必须完成整个容器树布局。
            caption.refresh();
            verify(width);
        });
        Thread.sleep(200);
        SwingUtilities.invokeAndWait(() -> verify(width));
    }

    private static void verify(int requestedPhysicalWidth) {
        RECT outer = new RECT(), client = new RECT();
        User32.INSTANCE.GetWindowRect(hwnd, outer);
        User32.INSTANCE.GetClientRect(hwnd, client);
        System.out.println("requested=" + requestedPhysicalWidth + ", native=" + outer.toRectangle()
                + ", client=" + client.toRectangle() + ", frame=" + frame.getBounds()
                + ", root=" + frame.getRootPane().getBounds() + ", layered=" + frame.getLayeredPane().getBounds()
                + ", bar=" + bar.getBounds() + ", valid=" + frame.isValid() + "/" + bar.isValid());
        check(outer.right - outer.left == requestedPhysicalWidth, "原生宽度没有改变");
        Insets insets = frame.getInsets();
        int contentWidth = frame.getWidth() - insets.left - insets.right;
        check(frame.getRootPane().getWidth() == contentWidth, "根面板没有跟随原生改宽");
        check(bar.getWidth() == contentWidth, "内容区没有铺满客户区");
        double scale = frame.getGraphicsConfiguration().getDefaultTransform().getScaleX();
        check(Math.abs(bar.getWidth() * scale - (client.right - client.left)) <= 2,
                "Swing 内容宽度与原生客户区不一致");
        Rectangle min = SwingUtilities.convertRectangle(minimize.getParent(), minimize.getBounds(), bar);
        Rectangle last = SwingUtilities.convertRectangle(stop.getParent(), stop.getBounds(), bar);
        check(min.x + min.width == bar.getWidth() - bar.getInsets().right, "最小化按钮没有贴近窗口右侧");
        check(last.x + last.width == bar.getWidth() - bar.getInsets().right, "录制操作按钮没有贴近窗口右侧");
        BufferedImage painting = new BufferedImage(bar.getWidth(), bar.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = painting.createGraphics();
        try { bar.paint(graphics); } finally { graphics.dispose(); }
        check(painting.getRGB(bar.getWidth() - 3, bar.getHeight() - 3) == bar.getBackground().getRGB(),
                "新增宽度区域没有绘制内容背景");
        Component details = bar.getComponent(1);
        check(painting.getRGB(details.getX() + details.getWidth() - 1, details.getY())
                == ThemeManager.getPalette().controlBackground().getRGB(), "分隔线没有延伸到内容区右侧");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static Object field(String name) {
        try { Field f = MacroFrame.class.getDeclaredField(name); f.setAccessible(true); return f.get(frame); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static void invoke(String name) {
        try { Method m = MacroFrame.class.getDeclaredMethod(name); m.setAccessible(true); m.invoke(frame); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
}
