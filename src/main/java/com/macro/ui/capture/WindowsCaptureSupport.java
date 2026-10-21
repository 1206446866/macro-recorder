package com.macro.ui.capture;

import com.sun.jna.*;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.win32.StdCallLibrary;
import java.awt.Window;

/** 只在一次录制截图会话内遮蔽窗口，避免隐藏/显示过渡进入捕获画面。 */
final class WindowsCaptureSupport {
    private static final int DWMWA_CLOAK = 13;
    private static final int DWMWA_CLOAKED = 14;
    private WindowsCaptureSupport() { }

    static Cloak cloak(Window window) {
        if (!Platform.isWindows() || !window.isDisplayable()) return Cloak.UNAVAILABLE;
        try (Memory value = new Memory(4)) {
            HWND hwnd = new HWND(Native.getComponentPointer(window));
            if (Dwm.INSTANCE.DwmGetWindowAttribute(hwnd, DWMWA_CLOAKED, value, 4) < 0) return Cloak.UNAVAILABLE;
            int original = value.getInt(0) & 1; // 只保存应用遮蔽位，不改变 shell/owner 遮蔽状态。
            value.setInt(0, 1);
            if (Dwm.INSTANCE.DwmSetWindowAttribute(hwnd, DWMWA_CLOAK, value, 4) < 0) return Cloak.UNAVAILABLE;
            return new Cloak(hwnd, original);
        } catch (RuntimeException | LinkageError failure) {
            return Cloak.UNAVAILABLE;
        }
    }

    /** 等待本进程已排队的 DWM 更新，不能代表整个桌面会话已全部完成合成。 */
    static boolean flush() {
        if (!Platform.isWindows()) return false;
        try { return Dwm.INSTANCE.DwmFlush() >= 0; }
        catch (RuntimeException | LinkageError failure) { return false; }
    }

    static final class Cloak implements AutoCloseable {
        static final Cloak UNAVAILABLE = new Cloak(null, 0);
        private HWND hwnd;
        private final int original;
        private Cloak(HWND hwnd, int original) { this.hwnd = hwnd; this.original = original; }
        boolean available() { return hwnd != null; }

        @Override public void close() {
            HWND saved = hwnd;
            if (saved == null) return;
            hwnd = null;
            try (Memory value = new Memory(4)) {
                if (!User32.INSTANCE.IsWindow(saved)) return;
                value.setInt(0, original);
                if (Dwm.INSTANCE.DwmSetWindowAttribute(saved, DWMWA_CLOAK, value, 4) < 0) {
                    System.err.println("截图结束后恢复窗口遮蔽状态失败");
                }
            } catch (RuntimeException | LinkageError failure) {
                System.err.println("截图结束后恢复窗口遮蔽状态失败：" + failure.getMessage());
            }
        }
    }

    private interface Dwm extends StdCallLibrary {
        Dwm INSTANCE = Native.load("dwmapi", Dwm.class);
        int DwmGetWindowAttribute(HWND hwnd, int attribute, Pointer value, int size);
        int DwmSetWindowAttribute(HWND hwnd, int attribute, Pointer value, int size);
        int DwmFlush();
    }
}
