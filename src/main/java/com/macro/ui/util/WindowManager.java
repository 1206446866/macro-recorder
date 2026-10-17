package com.macro.ui.util;

import javax.swing.*;
import java.awt.*;

/** 统一处理 Swing 主窗口的最小化与恢复。 */
public final class WindowManager {

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

    /** 恢复最小化窗口，同时保留最大化等其他扩展状态。 */
    public static void restore(JFrame frame) {
        if (frame == null || !isMinimized(frame)) {
            return;
        }
        frame.setExtendedState(frame.getExtendedState() & ~Frame.ICONIFIED);
        frame.toFront();
        frame.requestFocus();
    }
}
