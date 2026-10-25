package com.macro.ui.util;

import com.macro.util.WindowsWindowUtil;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.platform.win32.WinDef.HWND;

import javax.swing.*;
import java.awt.*;

/** 统一处理 Swing 主窗口的最小化与恢复。 */
public final class WindowManager {

    /** 窗口最小化与恢复通过静态入口管理，无需创建实例。 */
    private WindowManager() {
    }

    /** 判断窗口当前是否已最小化。 */
    public static boolean isMinimized(JFrame frame) {
        return frame != null
                && (frame.getExtendedState() & Frame.ICONIFIED) == Frame.ICONIFIED;
    }

    /** 最小化窗口，同时保留窗口原有的其他扩展状态。 */
    public static void minimize(JFrame frame) {
        if (frame == null || isMinimized(frame)) {
            return;
        }
        frame.setExtendedState(frame.getExtendedState() | Frame.ICONIFIED);
    }

    /** 在最大化与还原之间切换，交由 AWT/Windows 保留窗口正常位置和任务栏工作区。 */
    public static void toggleMaximize(JFrame frame) {
        if (frame == null || !frame.isResizable()) return;
        int state = frame.getExtendedState() & ~Frame.ICONIFIED;
        frame.setExtendedState((state & Frame.MAXIMIZED_BOTH) != 0
                ? state & ~Frame.MAXIMIZED_BOTH : state | Frame.MAXIMIZED_BOTH);
    }

    /** 恢复并请求前置，同时保留最大化等其他扩展状态。 */
    public static void restore(JFrame frame) {
        if (frame == null) {
            return;
        }
        if (isMinimized(frame)) {
            frame.setExtendedState(frame.getExtendedState() & ~Frame.ICONIFIED);
        }
        frame.toFront();
        frame.requestFocus();
    }

    /** 恢复后额外请求 Windows 激活真实窗口，不修改永久置顶状态。 */
    public static void restoreAndActivate(JFrame frame) {
        restore(frame);
        if (frame != null && frame.isShowing() && Platform.isWindows()) {
            // AWT 的扩展状态与原生最小化状态可能尚未同步，由现有激活逻辑补做恢复。
            WindowsWindowUtil.activateWindow(new HWND(Native.getComponentPointer(frame)));
        }
    }
}
