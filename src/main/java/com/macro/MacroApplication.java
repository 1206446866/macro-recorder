package com.macro;


import com.macro.instance.WindowsSingleInstance;
import com.macro.recognition.OpenCvTemplateMatcher;
import com.macro.ui.MacroFrame;
import com.macro.ui.dialog.AppMessageDialog;
import com.macro.ui.util.WindowManager;

import javax.swing.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;


/** 应用启动入口，先完成单实例检查，再初始化主题、识别库和 Swing 主窗口。 */
public class MacroApplication {

    /** 申请单实例资格并在事件线程创建主窗口；重复启动只唤起已有实例，启动失败时统一提示。 */
    public static void main(String[] args) {
        AppMessageDialog.installUncaughtExceptionHandler();
        AtomicReference<MacroFrame> mainWindow = new AtomicReference<>();
        WindowsSingleInstance instance;
        try {
            // 在 OpenCV、主窗口及全局监听初始化之前拒绝重复启动。
            instance = WindowsSingleInstance.acquire(() -> SwingUtilities.invokeLater(() -> {
                MacroFrame frame = mainWindow.get();
                if (frame != null) WindowManager.restoreAndActivate(frame);
            }));
        } catch (IOException | RuntimeException | LinkageError failure) {
            showStartupFailure(failure);
            return;
        }
        if (instance == null) return;

        try {
            Runtime.getRuntime().addShutdownHook(new Thread(instance::close, "single-instance-cleanup"));
            OpenCvTemplateMatcher.preloadInBackground();
            SwingUtilities.invokeLater(() -> {
                try {
                    MacroFrame frame = new MacroFrame();
                    mainWindow.set(frame);
                    frame.setVisible(true);
                } catch (RuntimeException | LinkageError failure) {
                    instance.close();
                    showStartupFailure(failure);
                }
            });
        } catch (RuntimeException | LinkageError failure) {
            instance.close();
            showStartupFailure(failure);
        }
    }

    /** 将启动异常转交 Swing 事件线程显示，避免在启动线程直接操作界面。 */
    private static void showStartupFailure(Throwable failure) {
        SwingUtilities.invokeLater(() -> AppMessageDialog.showError(null,
                "无法启动 Macro Recorder：" + failure.getMessage(), failure));
    }
}
