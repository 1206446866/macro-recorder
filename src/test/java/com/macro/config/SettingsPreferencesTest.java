package com.macro.config;

import com.macro.ui.SettingsDialog;

import javax.swing.JCheckBox;
import javax.swing.JRadioButton;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** 在隔离的应用目录验证配置落盘及设置控件，不接触用户运行配置。 */
public final class SettingsPreferencesTest {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(AppConfig.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI()).toAbsolutePath().normalize();
        if (!directory.endsWith(Path.of("settings-preferences-tests", "classes"))) {
            throw new IllegalStateException("请从 target/settings-preferences-tests/classes 的生产类副本运行此测试。");
        }
        AppConfig config = new AppConfig();
        Properties properties = properties(config);
        properties.clear();
        properties.setProperty("record.minimizeWindow", "false");
        check(config.getRecordingStartBehavior() == RecordingStartBehavior.MINIMIZE,
                "缺少新配置时受到旧布尔值影响");
        check(config.getTimelineInsertPosition() == TimelineInsertPosition.BEFORE,
                "默认插入方向改变了原有前插语义");
        check(!config.isMinimizeOnPlayStart() && config.isRestoreWindowAfterPlayStop(),
                "播放设置默认值发生变化");
        properties.setProperty("record.startBehavior", "unknown");
        properties.setProperty("timeline.defaultInsertPosition", "unknown");
        check(config.getRecordingStartBehavior() == RecordingStartBehavior.MINIMIZE
                        && config.getTimelineInsertPosition() == TimelineInsertPosition.BEFORE,
                "非法配置没有回到稳定默认值");

        File save = Files.createDirectories(directory.resolve("test-save")).toFile();
        File capture = Files.createDirectories(directory.resolve("test-capture")).toFile();
        for (RecordingStartBehavior behavior : RecordingStartBehavior.values()) {
            for (TimelineInsertPosition position : TimelineInsertPosition.values()) {
                check(config.updateSettings(save, capture, false, true, 23,
                        behavior, position, true, false), "配置保存失败");
                AppConfig loaded = new AppConfig();
                check(loaded.getRecordingStartBehavior() == behavior
                                && loaded.getTimelineInsertPosition() == position,
                        "配置重读后选择不一致");
                check(loaded.isMinimizeOnPlayStart() && !loaded.isRestoreWindowAfterPlayStop(),
                        "录制设置覆盖了独立播放配置");
                check(loaded.getMouseMoveInterval() == 23 && !loaded.isLoadLastMacroOnStartup(),
                        "原有配置未一起保存");
            }
        }

        Properties before = new Properties();
        before.putAll(properties);
        Field configFile = AppConfig.class.getDeclaredField("configFile");
        configFile.setAccessible(true);
        File actualFile = (File) configFile.get(config);
        configFile.set(config, directory.toFile());
        try {
            check(!config.updateSettings(null, null, true, false, 90,
                    RecordingStartBehavior.MINIMIZE, TimelineInsertPosition.BEFORE,
                    false, true), "目录被错误当作可写配置文件");
            check(properties.equals(before), "保存失败没有完整恢复内存配置");
        } finally {
            configFile.set(config, actualFile);
        }
        SwingUtilities.invokeAndWait(() -> {
            try {
                testSettingsDialog(new AppConfig());
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        });
        System.out.println("通过：新旧配置默认、非法值、六种选择落盘重读、播放配置独立、保存失败回滚、设置互斥恢复及鼠标项左对齐。");
    }

    /** 仅用程序切换控件验证数据链，不将其作为真实鼠标或 DPI 验收。 */
    private static void testSettingsDialog(AppConfig config) throws Exception {
        SettingsDialog dialog = new SettingsDialog(null, config);
        try {
            JRadioButton minimize = radio(dialog, "recordingMinimizeButton");
            JRadioButton controlBar = radio(dialog, "recordingControlBarButton");
            JRadioButton unchanged = radio(dialog, "recordingUnchangedButton");
            JRadioButton before = radio(dialog, "insertBeforeButton");
            JRadioButton after = radio(dialog, "insertAfterButton");
            check(unchanged.isSelected() && after.isSelected(), "设置打开后未恢复已保存选项");
            minimize.doClick();
            check(minimize.isSelected() && !controlBar.isSelected() && !unchanged.isSelected(),
                    "录制选项没有互斥");
            controlBar.doClick();
            before.doClick();
            check(controlBar.isSelected() && !minimize.isSelected() && !unchanged.isSelected()
                            && before.isSelected() && !after.isSelected(), "单选切换状态错误");
            check(controlBar.getIcon() != null && !controlBar.isOpaque(), "单选按钮未接入主题样式");

            JCheckBox mouse = (JCheckBox) field(dialog, "recordMouseMoveCheckBox");
            Container card = mouse.getParent();
            for (Component component : card.getComponents()) {
                if (component instanceof Container container && container.getComponentCount() > 0) {
                    check(component.getAlignmentX() == Component.LEFT_ALIGNMENT,
                            "鼠标采样行仍采用居中对齐");
                    // 按设置窗口最小宽度扣除侧栏、页面边距及卡片边距，检查采样行不会折行或裁切。
                    int availableWidth = dialog.getMinimumSize().width - 210 - 68 - 44;
                    check(component.getPreferredSize().width <= availableWidth,
                            "鼠标采样行超出设置窗口最小宽度");
                    container.setSize(availableWidth, component.getPreferredSize().height);
                    container.doLayout();
                    for (Component child : container.getComponents()) {
                        check(child.getX() >= 0 && child.getY() >= 0
                                        && child.getX() + child.getWidth() <= container.getWidth()
                                        && child.getY() + child.getHeight() <= container.getHeight(),
                                "窄窗口下鼠标采样控件发生折行或裁切");
                    }
                }
            }
            Method save = SettingsDialog.class.getDeclaredMethod("saveSettings");
            save.setAccessible(true);
            save.invoke(dialog);
            AppConfig loaded = new AppConfig();
            check(loaded.getRecordingStartBehavior() == RecordingStartBehavior.CONTROL_BAR
                            && loaded.getTimelineInsertPosition() == TimelineInsertPosition.BEFORE,
                    "设置保存没有写入选中的新选项");
            SettingsDialog reopened = new SettingsDialog(null, loaded);
            try {
                check(radio(reopened, "recordingControlBarButton").isSelected()
                                && radio(reopened, "insertBeforeButton").isSelected(),
                        "设置重新打开后选择丢失");
                radio(reopened, "recordingUnchangedButton").doClick();
                reopened.dispose();
                check(new AppConfig().getRecordingStartBehavior() == RecordingStartBehavior.CONTROL_BAR,
                        "取消设置仍写入了选择");
            } finally {
                reopened.dispose();
            }
        } finally {
            dialog.dispose();
        }
    }

    private static JRadioButton radio(SettingsDialog dialog, String name) throws Exception {
        return (JRadioButton) field(dialog, name);
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Properties properties(AppConfig config) throws Exception {
        return (Properties) field(config, "properties");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
