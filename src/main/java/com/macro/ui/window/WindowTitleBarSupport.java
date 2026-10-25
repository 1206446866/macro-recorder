package com.macro.ui.window;

import javax.swing.*;
import java.awt.Window;

/** 为应用弹窗复用主页标题栏，统一安装与卸载原生消息适配器。 */
public final class WindowTitleBarSupport {
    private final MainTitleBar titleBar;
    private final WindowsRecordingTitleBar nativeTitleBar;

    /** 在业务内容之外安装标题栏，保留窗口原有模态、尺寸和关闭策略。 */
    public WindowTitleBarSupport(Window window) {
        if (window instanceof JFrame frame) {
            titleBar = new MainTitleBar(frame);
            frame.setJMenuBar(titleBar);
            nativeTitleBar = WindowsRecordingTitleBar.forMainWindow(frame, titleBar, titleBar.getWindowButtons());
        } else if (window instanceof JDialog dialog) {
            titleBar = new MainTitleBar(dialog);
            dialog.setJMenuBar(titleBar);
            nativeTitleBar = WindowsRecordingTitleBar.forDialog(dialog, titleBar, titleBar.getWindowButtons());
        } else {
            throw new IllegalArgumentException("标题栏仅用于应用窗口及对话框");
        }
    }

    /** 创建 HWND 后安装；原生扩展失败时保留系统标题操作，避免显示两套按钮。 */
    public void activate() {
        titleBar.setVisible(true);
        try {
            nativeTitleBar.activate();
        } catch (RuntimeException | LinkageError failure) {
            titleBar.setVisible(false);
            System.getLogger(WindowTitleBarSupport.class.getName()).log(System.Logger.Level.WARNING,
                    "无法启用弹窗自绘标题栏，保留系统窗口操作", failure);
        }
    }

    /** 先恢复原 AWT 窗口过程，再由窗口销毁其原生句柄。 */
    public void deactivate() {
        if (nativeTitleBar.isActive()) nativeTitleBar.deactivate();
    }
}
