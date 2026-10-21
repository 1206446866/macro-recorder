package com.macro.ui;

import com.macro.config.AppConfig;
import com.macro.model.TargetWindow;
import com.macro.recorder.MacroRecorder;
import com.macro.ui.window.WindowsRecordingTitleBar;
import com.macro.ui.window.RecordingBarGeometry;
import com.sun.jna.Native;
import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.*;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;

/** 隔离桌面契约：不显示窗口、不注册 Hook、不截图、不发送真实输入。 */
public final class RecordingTitleBarContractTest {
    public static void main(String[] args) {
        int result = 0;
        try {
            Path classes = Path.of(AppConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!classes.toString().contains("test-sandboxes")) throw new AssertionError("必须在隔离副本运行");
            SwingUtilities.invokeAndWait(RecordingTitleBarContractTest::verify);
            System.out.println("通过：双行布局、原生命中与定高水平缩放消息、几何持久化/重新进入恢复、主页尺寸保全、录制/暂停/恢复置顶和原值恢复、重复进入");
        } catch (Throwable failure) {
            failure.printStackTrace();
            result = 1;
        }
        System.exit(result);
    }

    private static void verify() {
        MacroFrame frame = new MacroFrame();
        try {
            frame.pack(); // 创建 HWND，始终保持不可见。
            HWND hwnd = new HWND(Native.getComponentPointer(frame));
            long procedure = User32.INSTANCE.GetWindowLongPtr(hwnd, -4).longValue();
            int style = User32.INSTANCE.GetWindowLong(hwnd, -16);
            TargetWindow target = TargetWindow.create().setProcessName("app.exe").setWindowTitle("长标题".repeat(100));
            set(frame, "targetWindow", target);
            MacroRecorder recorder = (MacroRecorder) get(frame, "recorder");
            AppConfig config = (AppConfig) get(frame, "appConfig");
            for (boolean originallyOnTop : new boolean[]{false, true, false}) {
                Rectangle homeBounds = frame.getBounds();
                Dimension homeMinimum = frame.getMinimumSize();
                AppConfig.RecordingBarPlacement beforePlacement = config.getRecordingBarPlacement();
                frame.setAlwaysOnTop(originallyOnTop);
                boolean focusable = frame.getFocusableWindowState();
                Object recordingState = recordingState(recorder);
                set(recorder, "state", recordingState);
                HWND foreground = User32.INSTANCE.GetForegroundWindow();
                invoke(frame, "enterCompactRecordingMode");
                frame.validate();
                invoke(frame, "refreshRecordingControlBar");
                check(frame.isAlwaysOnTop(), "进入紧凑模式后没有置顶");
                check(new HWND(Native.getComponentPointer(frame)).equals(hwnd), "紧凑模式更换了窗口实例/句柄");
                check(java.util.Objects.equals(foreground, User32.INSTANCE.GetForegroundWindow()),
                        "隐藏窗口进入紧凑模式时抢占了前台");
                invoke(frame, "enterCompactRecordingMode"); // 重入不得覆盖最初保存的置顶值。
                ((JButton) get(frame, "recordingBarPauseButton")).doClick();
                check(recorder.getRecordingStatus().paused() && frame.isAlwaysOnTop(), "暂停时丢失置顶");
                ((JButton) get(frame, "recordingBarPauseButton")).doClick();
                check(!recorder.getRecordingStatus().paused() && frame.isAlwaysOnTop(), "恢复时丢失置顶");
                check(frame.getFocusableWindowState() == focusable, "置顶改变了原有焦点能力");
                WindowsRecordingTitleBar caption = (WindowsRecordingTitleBar) get(frame, "nativeRecordingTitleBar");
                check(caption.isActive(), "标题栏未激活");
                check((User32.INSTANCE.GetWindowLong(hwnd, -16) & 0xB0000) == 0, "原生系统按钮样式残留");
                check(!frame.isUndecorated(), "不应重建无边框窗口");
                JLabel label = (JLabel) get(frame, "recordingBarTarget");
                check(label.getText().equals(RecordingControlBar.targetText(target)), "目标数据未接入");
                check(label.getText().equals(label.getToolTipText()), "完整目标提示丢失");
                check(label.getHeight() >= label.getFontMetrics(label.getFont()).getHeight(), "目标文字高度不足");
                if (beforePlacement == null) check(frame.getWidth() < 800, "长目标撑宽了首次默认窗口");
                else check(frame.getX() == beforePlacement.x() && frame.getY() == beforePlacement.y()
                        && frame.getWidth() == beforePlacement.width(), "重新进入没有恢复操作栏位置和宽度");
                POINT origin = new POINT();
                check(NativeCoordinates.INSTANCE.ClientToScreen(hwnd, origin), "无法取得客户区原点");
                double scale = NativeCoordinates.INSTANCE.GetDpiForWindow(hwnd) / 96.0;
                try (Memory limits = new Memory(40)) {
                    limits.clear();
                    User32.INSTANCE.SendMessage(hwnd, 0x24, new WPARAM(0), new LPARAM(Pointer.nativeValue(limits)));
                    check(limits.getInt(24) >= Math.ceil(frame.getMinimumSize().width * scale)
                            && limits.getInt(28) >= Math.ceil(frame.getMinimumSize().height * scale),
                            "原生最小拖动尺寸没有按 DPI 保护双行布局");
                    check(limits.getInt(28) == limits.getInt(36), "原生最小/最大拖动高度没有锁定");
                    verifyNativeSizing(hwnd, limits.getInt(24), limits.getInt(28));
                }
                RECT outer = new RECT();
                check(User32.INSTANCE.GetWindowRect(hwnd, outer), "无法取得原生矩形");
                check(hitScreen(hwnd, (outer.left + outer.right) / 2, outer.top + 1) == 2, "顶部仍能纵向缩放");
                check(hitScreen(hwnd, outer.left + 1, outer.top + 1) == 10, "左上角未限制为水平缩放");
                check(hitScreen(hwnd, outer.right - 2, outer.top + 1) == 11, "右上角未限制为水平缩放");
                long bottomHit = hitScreen(hwnd, (outer.left + outer.right) / 2, outer.bottom - 2);
                check(bottomHit != 15 && bottomHit != 16 && bottomHit != 17, "底边仍能纵向缩放");
                for (String name : new String[]{"recordingBarMinimizeButton", "recordingBarCaptureButton",
                        "recordingBarPauseButton", "recordingBarStopButton"}) {
                    JButton button = (JButton) get(frame, name);
                    Rectangle bounds = SwingUtilities.convertRectangle(button.getParent(), button.getBounds(), frame.getRootPane());
                    check(hit(hwnd, origin, scale, bounds.getCenterX(), bounds.getCenterY()) == 1, "按钮未按客户区命中：" + name);
                }
                Rectangle textBounds = SwingUtilities.convertRectangle(label.getParent(), label.getBounds(), frame.getRootPane());
                check(hit(hwnd, origin, scale, textBounds.x + 10, textBounds.getCenterY()) == 2, "标题文本区不可拖动");
                Rectangle area = new Rectangle(frame.getGraphicsConfiguration().getBounds());
                Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(frame.getGraphicsConfiguration());
                area.x += insets.left; area.y += insets.top;
                area.width -= insets.left + insets.right; area.height -= insets.top + insets.bottom;
                Rectangle moved = RecordingBarGeometry.restore(new Rectangle(frame.getX() + 12, frame.getY() + 16,
                        frame.getWidth() + 64, 999), frame.getMinimumSize(), java.util.List.of(area));
                frame.setBounds(moved);
                frame.validate();
                check(frame.getHeight() == moved.height, "水平改宽改变了固定高度");
                check(java.util.Objects.equals(new AppConfig().getRecordingBarPlacement(), beforePlacement),
                        "移动或调整宽度时提前写入了配置");
                ((JButton) get(frame, "recordingBarMinimizeButton")).doClick();
                check((frame.getExtendedState() & Frame.ICONIFIED) != 0, "自绘最小化未请求任务栏最小化");
                if (!originallyOnTop) frame.setExtendedState(Frame.NORMAL);
                check(frame.isAlwaysOnTop(), "最小化/恢复时丢失置顶属性");
                invoke(frame, "restoreWindowAfterRecording");
                AppConfig.RecordingBarPlacement expectedPlacement = new AppConfig.RecordingBarPlacement(moved.x, moved.y, moved.width);
                check(expectedPlacement.equals(config.getRecordingBarPlacement())
                        && expectedPlacement.equals(new AppConfig().getRecordingBarPlacement()), "退出后未持久化最后的位置与宽度");
                check(frame.getBounds().equals(homeBounds) && frame.getMinimumSize().equals(homeMinimum), "主页尺寸或位置受紧凑几何设置影响");
                check(frame.isAlwaysOnTop() == originallyOnTop, "退出紧凑模式没有恢复原置顶值");
                invoke(frame, "restoreWindowAfterRecording");
                check(frame.isAlwaysOnTop() == originallyOnTop, "重复收尾改变了原置顶值");
                check(User32.INSTANCE.GetWindowLong(hwnd, -16) == style, "原生窗口样式未完整恢复");
                check(User32.INSTANCE.GetWindowLongPtr(hwnd, -4).longValue() == procedure, "原窗口过程未恢复");
                target = null;
                set(frame, "targetWindow", null);
            }
        } finally {
            invoke(frame, "exitCompactRecordingMode");
            frame.dispose();
        }
    }

    private static void verifyNativeSizing(HWND hwnd, int minimumWidth, int fixedHeight) {
        for (int edge = 1; edge <= 8; edge++) {
            try (Memory rect = new Memory(16)) {
                rect.setInt(0, -100); rect.setInt(4, -200);
                rect.setInt(8, 0); rect.setInt(12, 600);
                User32.INSTANCE.SendMessage(hwnd, 0x214, new WPARAM(edge), new LPARAM(Pointer.nativeValue(rect)));
                check(rect.getInt(12) - rect.getInt(4) == fixedHeight
                        && rect.getInt(8) - rect.getInt(0) >= minimumWidth, "WM_SIZING 没有保护固定高度和最小宽度");
            }
        }
        try (Memory pos = new Memory(Native.POINTER_SIZE * 2 + 24)) {
            pos.clear();
            pos.setPointer(0, hwnd.getPointer());
            int offset = Native.POINTER_SIZE * 2;
            pos.setInt(offset + 8, minimumWidth + 100);
            pos.setInt(offset + 12, fixedHeight + 300);
            User32.INSTANCE.SendMessage(hwnd, 0x46, new WPARAM(0), new LPARAM(Pointer.nativeValue(pos)));
            check(pos.getInt(offset + 8) == minimumWidth + 100 && pos.getInt(offset + 12) == fixedHeight,
                    "WM_WINDOWPOSCHANGING 没有限制高度或阻止了水平改宽");
        }
    }

    private static long hitScreen(HWND hwnd, int x, int y) {
        return User32.INSTANCE.SendMessage(hwnd, 0x84, new WPARAM(0),
                new LPARAM((x & 0xffffL) | ((y & 0xffffL) << 16))).longValue();
    }

    /** 只模拟录制状态，不调用 start 注册全局钩子，也不发送输入事件。 */
    private static Object recordingState(MacroRecorder recorder) {
        try {
            Field state = MacroRecorder.class.getDeclaredField("state");
            return java.util.Arrays.stream(state.getType().getEnumConstants())
                    .filter(value -> value.toString().equals("RECORDING")).findFirst().orElseThrow();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static long hit(HWND hwnd, POINT origin, double scale, double x, double y) {
        int sx = origin.x + (int) Math.round(x * scale), sy = origin.y + (int) Math.round(y * scale);
        return User32.INSTANCE.SendMessage(hwnd, 0x84, new WPARAM(0), new LPARAM((sx & 0xffffL) | ((sy & 0xffffL) << 16))).longValue();
    }

    private static Object get(Object object, String name) {
        try { Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static void set(Object object, String name, Object value) {
        try { Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); f.set(object, value); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static void invoke(Object object, String name) {
        try { Method m = object.getClass().getDeclaredMethod(name); m.setAccessible(true); m.invoke(object); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private interface NativeCoordinates extends StdCallLibrary {
        NativeCoordinates INSTANCE = Native.load("user32", NativeCoordinates.class, W32APIOptions.DEFAULT_OPTIONS);
        boolean ClientToScreen(HWND hwnd, POINT point);
        int GetDpiForWindow(HWND hwnd);
    }
}
