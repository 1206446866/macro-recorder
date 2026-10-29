package com.macro.ui.capture;

import com.sun.jna.*;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.win32.StdCallLibrary;
import java.awt.Window;

/**
 * 为录制截图和识别测试提供临时的 Windows 窗口遮蔽，减少隐藏动画进入采集画面的机会。
 * 调用方负责配合窗口可见状态管理，并在采集结束或取消时关闭遮蔽记录，恢复原来的应用遮蔽位。
 */
public final class WindowsCaptureSupport {
    private static final int DWMWA_CLOAK = 13;
    private static final int DWMWA_CLOAKED = 14;
    /** 原生采集辅助工具无需实例化。 */
    private WindowsCaptureSupport() { }

    /**
     * 保存窗口原来的应用遮蔽位，并立即请求 DWM 遮蔽该窗口。
     *
     * @return 用于恢复状态的记录；不支持或调用失败时返回可安全关闭的空记录
     */
    public static Cloak cloak(Window window) {
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

    /**
     * 等待本进程已排队的 DWM 更新，应在采集后台线程调用，避免阻塞 Swing 事件线程。
     *
     * @return 原生同步调用是否成功；成功也不代表整个桌面会话已经完成合成
     */
    public static boolean flush() {
        if (!Platform.isWindows()) return false;
        try { return Dwm.INSTANCE.DwmFlush() >= 0; }
        catch (RuntimeException | LinkageError failure) { return false; }
    }

    /** 保存一次临时遮蔽的恢复信息；关闭后不再持有窗口引用。 */
    public static final class Cloak implements AutoCloseable {
        static final Cloak UNAVAILABLE = new Cloak(null, 0);
        private HWND hwnd;
        private final int original;
        /** 记录目标窗口及其原始应用遮蔽位；空窗口表示此次未能建立遮蔽。 */
        private Cloak(HWND hwnd, int original) { this.hwnd = hwnd; this.original = original; }
        /** 判断是否仍持有遮蔽恢复信息，不额外查询窗口是否已被销毁。 */
        boolean available() { return hwnd != null; }

        /** 恢复原来的应用遮蔽位，可重复调用；已销毁的窗口直接跳过，原生失败只记录错误。 */
        @Override public void close() {
            HWND saved = hwnd;
            if (saved == null) return;
            hwnd = null;
            try (Memory value = new Memory(4)) {
                if (!User32.INSTANCE.IsWindow(saved)) return;
                value.setInt(0, original);
                int result = Dwm.INSTANCE.DwmSetWindowAttribute(saved, DWMWA_CLOAK, value, 4);
                if (result < 0) {
                    com.macro.diagnostics.ErrorReporter.report("截图结束后恢复窗口遮蔽状态失败，HRESULT=0x"
                            + Integer.toHexString(result), null);
                }
            } catch (RuntimeException | LinkageError failure) {
                com.macro.diagnostics.ErrorReporter.report("截图结束后恢复窗口遮蔽状态失败", failure);
            }
        }
    }

    /** 声明临时遮蔽和采集同步使用的 DWM 原生接口。 */
    private interface Dwm extends StdCallLibrary {
        Dwm INSTANCE = Native.load("dwmapi", Dwm.class);
        /** 读取窗口指定属性到调用方提供的缓冲区。 */
        int DwmGetWindowAttribute(HWND hwnd, int attribute, Pointer value, int size);
        /** 使用给定缓冲区更新窗口指定属性。 */
        int DwmSetWindowAttribute(HWND hwnd, int attribute, Pointer value, int size);
        /** 等待当前进程已经排队的合成更新，返回原生调用结果。 */
        int DwmFlush();
    }
}
