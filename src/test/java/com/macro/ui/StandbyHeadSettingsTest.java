package com.macro.ui;

import com.macro.config.AppConfig;
import com.macro.flow.MacroFlow;

import javax.swing.*;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

/** 在隔离应用副本验证设置、主窗口与录制收尾；不显示窗口、不注册 Hook、不发送输入。 */
public final class StandbyHeadSettingsTest {
    public static void main(String[] args) {
        int exit = 0;
        try {
            Path classes = Path.of(AppConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            check(classes.toString().contains("test-sandboxes"), "必须隔离个人配置");
            SwingUtilities.invokeAndWait(() -> {
                try { verify(); } catch (Exception failure) { throw new AssertionError(failure); }
            });
            System.out.println("通过：默认关闭、配置重读与失败回滚、设置保存/取消、时间线同步、新录制默认及录制收尾保留待机");
        } catch (Throwable failure) {
            failure.printStackTrace();
            exit = 1;
        }
        System.exit(exit);
    }

    private static void verify() throws Exception {
        AppConfig config = new AppConfig();
        check(!config.isStandbyHeadEnabled(), "新配置默认值必须关闭");
        check(config.updateStandbyHeadEnabled(true), "开关保存失败");
        check(new AppConfig().isStandbyHeadEnabled(), "配置没有持久化");
        Field fileField = AppConfig.class.getDeclaredField("configFile");
        fileField.setAccessible(true);
        File actual = (File) fileField.get(config);
        byte[] original = Files.readAllBytes(actual.toPath());
        fileField.set(config, actual.getParentFile());
        try {
            check(!config.updateStandbyHeadEnabled(false), "配置失败被报告为成功");
            check(config.isStandbyHeadEnabled(), "失败后未恢复开关");
            check(java.util.Arrays.equals(original, Files.readAllBytes(actual.toPath())), "保存失败改写原文件");
            check(!config.updateSettings(config.getDefaultSaveDirectory(), config.getDefaultScreenCaptureDirectory(),
                    false, true, 20, config.getRecordingStartBehavior(), config.getTimelineInsertPosition(),
                    false, true, false), "设置窗口保存失败被报告为成功");
            check(config.isStandbyHeadEnabled(), "批量保存失败未恢复开关");
        } finally {
            fileField.set(config, actual);
        }

        SettingsDialog cancelled = new SettingsDialog(null, config);
        try {
            JCheckBox option = (JCheckBox) get(cancelled, "standbyHeadCheckBox");
            check(option.isSelected() && !option.isOpaque() && option.getIcon() != null, "开关状态/深色样式未加载");
            option.doClick();
        } finally { cancelled.dispose(); }
        check(!cancelled.isSettingsSaved() && new AppConfig().isStandbyHeadEnabled(), "取消设置修改了配置");

        SettingsDialog saved = new SettingsDialog(null, config);
        try {
            saved.setStandbyHeadState(false, true);
            check(!((JCheckBox) get(saved, "standbyHeadCheckBox")).isSelected(), "没有显示当前宏状态");
            ((JCheckBox) get(saved, "standbyHeadCheckBox")).doClick();
            invoke(saved, "saveSettings");
            check(saved.isSettingsSaved() && new AppConfig().isStandbyHeadEnabled(), "设置保存未落盘");
        } finally { saved.dispose(); }

        SettingsDialog locked = new SettingsDialog(null, config);
        try {
            locked.setStandbyHeadState(false, false);
            invoke(locked, "saveSettings");
            check(!((JCheckBox) get(locked, "standbyHeadCheckBox")).isEnabled(), "忙碌状态仍可编辑");
            check(new AppConfig().isStandbyHeadEnabled(), "禁用控件覆盖了默认设置");
        } finally { locked.dispose(); }

        MacroFrame frame = new MacroFrame();
        try {
            ActionTimelinePanel panel = (ActionTimelinePanel) get(frame, "actionTimelinePanel");
            check(panel.getFlow().getHeadWaitMillis() == 1_000L, "主窗口未应用默认头部待机");
            panel.getFlow().setHeadWaitMillis(1_250L);
            invoke(frame, "initializeNewRecordingState");
            check(panel.getFlow().getHeadWaitMillis() == 1_000L, "新录制没有使用固定默认时长");
            invoke(frame, "updateRecordingStoppedState");
            check(panel.getFlow().getHeadWaitMillis() == 1_000L, "录制停止后丢失待机");
            check(panel.getFlow() == get(frame, "macroFlow"), "主窗口与时间线数据不同步");
            StandbyHeadTimelineTest.checkbox(panel).doClick();
            check(panel.getFlow().getHeadWaitMillis() == null
                    && !new AppConfig().isStandbyHeadEnabled(), "快捷开关未同步主窗口/配置");
            MacroFlow old = panel.getFlow();
            Field recording = MacroFrame.class.getDeclaredField("recording");
            recording.setAccessible(true);
            recording.setBoolean(frame, true);
            panel.setFlow(old);
            check(!StandbyHeadTimelineTest.checkbox(panel).isEnabled(), "录制期间开关未锁定");
            check(!panel.setStandbyHeadEnabled(true), "录制期间仍可修改头部待机");
            recording.setBoolean(frame, false);
        } finally { frame.dispose(); }
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void invoke(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(target);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
