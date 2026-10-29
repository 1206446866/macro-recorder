package com.macro.ui.capture;

import com.macro.util.WindowsWindowUtil;
import com.sun.jna.*;
import com.sun.jna.platform.win32.*;
import com.sun.jna.platform.win32.WinDef.*;
import com.sun.jna.win32.StdCallLibrary;

import java.awt.Rectangle;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 仅供用户主动截图使用的窗口枚举、前置及客户区查询，不修改录制/播放目标。 */
public class CaptureWindowService {
    public record Target(long handle, int processId, String windowClass, String title, String processName) {
        @Override public String toString() {
            return title + "  —  " + (processName == null || processName.isBlank() ? "PID " + processId : processName);
        }
    }

    public List<Target> listWindows() throws IOException {
        requireWindows();
        List<Target> targets = new ArrayList<>();
        int ownProcess = Kernel32.INSTANCE.GetCurrentProcessId();
        boolean enumerated = User32.INSTANCE.EnumWindows((window, data) -> {
            if (!User32.INSTANCE.IsWindowVisible(window)) return true;
            int pid = WindowsWindowUtil.getProcessId(window);
            if (pid == 0 || pid == ownProcess || cloaked(window)) return true;
            char[] text = new char[1024]; User32.INSTANCE.GetWindowText(window, text, text.length);
            String title = Native.toString(text);
            if (title == null || title.isBlank()) return true;
            targets.add(new Target(Pointer.nativeValue(window.getPointer()), pid,
                    WindowsWindowUtil.getWindowClass(window), title, WindowsWindowUtil.getProcessName(window)));
            return true;
        }, null);
        if (!enumerated) throw new IOException("无法读取窗口列表，请刷新后重试");
        targets.sort(Comparator.comparing(Target::title, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(targets);
    }

    public void activate(Target target) throws IOException {
        HWND window = validate(target);
        if (Coordinates.INSTANCE.IsIconic(window)) Coordinates.INSTANCE.ShowWindowAsync(window, WinUser.SW_RESTORE);
        // 不模拟按键或改变目标窗口的置顶属性；以稍后实际前台状态为准。
        User32.INSTANCE.SetForegroundWindow(window);
    }

    /** 返回物理像素客户区；临时 DPI 上下文始终在同一线程恢复。 */
    public Rectangle clientBounds(Target target) throws IOException {
        HWND window = validate(target);
        if (!window.equals(User32.INSTANCE.GetForegroundWindow()) || Coordinates.INSTANCE.IsIconic(window)) {
            throw new IOException("未能将指定窗口切到前台，请先手动显示该窗口后重试");
        }
        if (cloaked(window)) throw new IOException("指定窗口当前不可见，请显示窗口后重试");
        return readPhysicalClientBounds(window);
    }

    static Rectangle readPhysicalClientBounds(HWND window) throws IOException {
        Pointer previous = Coordinates.INSTANCE.SetThreadDpiAwarenessContext(Pointer.createConstant(-4L));
        if (previous == null) throw new IOException("无法读取指定窗口的 DPI 坐标");
        try {
            RECT client = new RECT();
            POINT origin = new POINT();
            if (!User32.INSTANCE.GetClientRect(window, client) || !Coordinates.INSTANCE.ClientToScreen(window, origin)) {
                throw new IOException("无法读取指定窗口内容区，请重新选择窗口");
            }
            if (client.right <= 0 || client.bottom <= 0) throw new IOException("指定窗口没有可截图的内容区");
            return new Rectangle(origin.x, origin.y, client.right, client.bottom);
        } finally {
            Coordinates.INSTANCE.SetThreadDpiAwarenessContext(previous);
        }
    }

    /** Java 17 Windows 屏幕原点沿用物理原点，宽高则按各显示器 DPI 缩放。 */
    public Rectangle monitorBounds(Rectangle javaBounds) throws IOException {
        requireWindows();
        Pointer previous = Coordinates.INSTANCE.SetThreadDpiAwarenessContext(Pointer.createConstant(-4L));
        if (previous == null) throw new IOException("无法读取显示器 DPI 坐标");
        try {
            POINT.ByValue point = new POINT.ByValue(); point.x = javaBounds.x + 1; point.y = javaBounds.y + 1;
            var monitor = User32.INSTANCE.MonitorFromPoint(point, WinUser.MONITOR_DEFAULTTONULL);
            WinUser.MONITORINFO info = new WinUser.MONITORINFO();
            if (monitor == null || !User32.INSTANCE.GetMonitorInfo(monitor, info).booleanValue()) throw new IOException("显示器布局已变化，请重新截图");
            return info.rcMonitor.toRectangle();
        } finally { Coordinates.INSTANCE.SetThreadDpiAwarenessContext(previous); }
    }

    private HWND validate(Target target) throws IOException {
        requireWindows();
        if (target == null || target.handle() == 0) throw new IOException("请先指定截图窗口");
        HWND window = new HWND(Pointer.createConstant(target.handle()));
        if (!User32.INSTANCE.IsWindow(window) || !User32.INSTANCE.IsWindowVisible(window)
                || WindowsWindowUtil.getProcessId(window) != target.processId()
                || target.processId() == Kernel32.INSTANCE.GetCurrentProcessId()
                || !java.util.Objects.equals(target.windowClass(), WindowsWindowUtil.getWindowClass(window))) {
            throw new IOException("指定窗口已经关闭或发生变化，请重新选择窗口");
        }
        return window;
    }

    private static boolean cloaked(HWND window) {
        try (Memory value = new Memory(4)) {
            return Dwm.INSTANCE.DwmGetWindowAttribute(window, 14, value, 4) >= 0 && value.getInt(0) != 0;
        }
    }
    private static void requireWindows() throws IOException {
        if (!Platform.isWindows()) throw new IOException("指定窗口截图目前支持 Windows");
    }
    private interface Coordinates extends StdCallLibrary {
        Coordinates INSTANCE = Native.load("user32", Coordinates.class);
        Pointer SetThreadDpiAwarenessContext(Pointer context);
        boolean ClientToScreen(HWND window, POINT point);
        boolean ShowWindowAsync(HWND window, int command);
        boolean IsIconic(HWND window);
    }
    private interface Dwm extends StdCallLibrary {
        Dwm INSTANCE = Native.load("dwmapi", Dwm.class);
        int DwmGetWindowAttribute(HWND window, int attribute, Pointer value, int bytes);
    }
}
