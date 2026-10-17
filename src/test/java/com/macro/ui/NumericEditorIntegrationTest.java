package com.macro.ui;

import com.macro.config.AppConfig;
import com.macro.flow.ImageCheckConfig;
import com.macro.flow.MacroFlowFactory;
import com.macro.flow.MacroNode;
import com.macro.flow.NodeType;
import com.macro.model.MacroAction;
import com.macro.storage.MacroStorage;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

/** 验证真实编辑器、内联编辑及设置提交；不执行播放，也不修改用户配置文件。 */
public final class NumericEditorIntegrationTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory(Path.of("target"), "numeric-editor-test-").toAbsolutePath();
        Path template = directory.resolve("template.png");
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", template.toFile());
        AtomicReference<MacroNode> ordinary = new AtomicReference<>();
        AtomicReference<MacroNode> image = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                AtomicReference<String> unexpectedDialog = new AtomicReference<>();
                javax.swing.Timer dialogGuard = new javax.swing.Timer(100, event -> {
                    for (Window window : Window.getWindows()) {
                        if (window.isShowing() && window instanceof com.macro.ui.dialog.AppMessageDialog) {
                            try {
                                var message = window.getClass().getDeclaredField("message");
                                message.setAccessible(true);
                                unexpectedDialog.set(String.valueOf(message.get(window)));
                            } catch (ReflectiveOperationException failure) {
                                unexpectedDialog.set(failure.toString());
                            }
                            System.err.println("测试遇到非预期提示：" + unexpectedDialog.get());
                            window.dispose();
                        }
                    }
                });
                dialogGuard.start();
                try {
                    testEditor(template, ordinary, image);
                    testTimeline();
                    testSettings(directory);
                    check(unexpectedDialog.get() == null, "出现非预期提示：" + unexpectedDialog.get());
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                } finally {
                    dialogGuard.stop();
                    for (Window window : Window.getWindows()) window.dispose();
                }
            });
            MacroStorage storage = new MacroStorage();
            var flow = MacroFlowFactory.rebuildLinear(List.of(ordinary.get(), image.get()), null);
            Path file = directory.resolve("numeric.mr");
            storage.save(MacroFlowFactory.extractActions(flow), null, flow, file.toString());
            var loaded = storage.load(file.toString());
            check(loaded.getActions().get(0).getWheelAmount() == -3, "滚轮方向在保存重载后改变");
            check(loaded.getActions().get(0).getY() == Integer.MIN_VALUE, "负坐标在保存重载后改变");
            check(loaded.getActions().get(0).getDelay() == 4000L, "延迟单位或数值改变");
            var config = MacroFlowFactory.timelineNodes(loaded.getFlow()).get(1).getImageCheckConfig();
            check(config.getThreshold() == 0.97 && config.getRetryDelay() == 0L, "图像数值存储格式改变");
            storage.save(List.of(ordinary.get().getAction()), null, directory.resolve("ordinary.mr").toString());
            check(storage.load(directory.resolve("ordinary.mr").toString()).getActions().size() == 1,
                    "普通动作 MR 读写回归");
        } finally {
            try (var files = Files.list(directory)) {
                for (Path file : files.toList()) Files.deleteIfExists(file);
            }
            Files.deleteIfExists(directory);
        }
        System.out.println("通过：动作/图像阈值提交、只读按键码、时间线无效输入保护/取消、设置保存及 MR 读写");
    }

    private static void testEditor(Path template, AtomicReference<MacroNode> ordinary,
            AtomicReference<MacroNode> image) {
        MacroAction original = MacroAction.mouseWheel(10, 20, 1, 30);
        MacroNode source = MacroFlowFactory.timelineNodes(MacroFlowFactory.fromActions(List.of(original))).get(0);
        ActionEditorDialog editor = ActionEditorDialog.edit(null, source, ordinary::set, ignored -> { });
        numericField(editor, "X 坐标").setText("-123");
        numericField(editor, "Y 坐标").setText(Integer.toString(Integer.MIN_VALUE));
        numericField(editor, "滚轮量").setText("-3");
        numericField(editor, "延迟（ms）").setText("4000");
        button(editor, "保存").doClick();
        check(ordinary.get().getAction().getX() == -123 && ordinary.get().getAction().getWheelAmount() == -3,
                "动作编辑没有提交新的数值");
        check(original.getX() == 10 && original.getDelay() == 30, "编辑过程中提前污染原动作");

        MacroNode imageSource = new MacroNode("image", NodeType.IMAGE_CHECK, null,
                new ImageCheckConfig(template.toString(), 0.9), new ArrayList<>());
        ActionEditorDialog imageEditor = ActionEditorDialog.edit(null, imageSource, image::set, ignored -> { });
        JSpinner spinner = (JSpinner) children(imageEditor).stream()
                .filter(component -> component instanceof JSpinner).findFirst().orElseThrow();
        JTextField threshold = ((JSpinner.NumberEditor) spinner.getEditor()).getTextField();
        threshold.setText("97");
        check(spinner.getValue().equals(90), "测试没有保留未提交输入");
        numericField(imageEditor, "延迟（ms）").setText("0");
        button(imageEditor, "保存").doClick();
        check(image.get().getImageCheckConfig().getThreshold() == 0.97, "保存错误地使用了旧阈值");
        check(imageSource.getImageCheckConfig().getThreshold() == 0.9, "编辑污染了原图像节点");

        MacroNode keyNode = MacroFlowFactory.timelineNodes(MacroFlowFactory.fromActions(
                List.of(MacroAction.keyPress(KeyEvent.VK_A, "A", 0)))).get(0);
        AtomicReference<MacroNode> keyboard = new AtomicReference<>();
        ActionEditorDialog keyEditor = ActionEditorDialog.edit(null, keyNode, keyboard::set, ignored -> { });
        JTextField keyName = (JTextField) children(keyEditor).stream().filter(component -> component instanceof JTextField text
                && text.isEditable() && text.getText().equals("A")).findFirst().orElseThrow();
        keyName.setText("Tab");
        check(children(keyEditor).stream().anyMatch(component -> component instanceof JTextField text
                && !text.isEditable() && text.getText().equals("9")), "只读按键码或名称同步被影响");
        button(keyEditor, "保存").doClick();
        check(keyboard.get().getAction().getKeyCode() == KeyEvent.VK_TAB, "普通按键名称受到数字限制");
    }

    private static void testTimeline() throws Exception {
        MacroAction action = MacroAction.mouseMove(12, 34, 40);
        ActionTimelinePanel timeline = new ActionTimelinePanel();
        timeline.setActions(List.of(action));
        Container coordinate = (Container) children(timeline).stream()
                .filter(component -> component.getClass().getSimpleName().equals("CoordinateEditor"))
                .findFirst().orElseThrow();
        Method begin = coordinate.getClass().getDeclaredMethod("beginEdit");
        begin.setAccessible(true);
        begin.invoke(coordinate);
        JTextField text = (JTextField) coordinate.getComponent(0);
        text.setText("2147483648");
        blur(text);
        check(action.getX() == 12 && text.getParent() == coordinate, "无效坐标污染动作或被静默丢弃");
        text.setText("-123");
        blur(text);
        check(action.getX() == -123 && coordinate.getComponent(0) instanceof JLabel, "合法负坐标未提交");
        begin.invoke(coordinate);
        text = (JTextField) coordinate.getComponent(0);
        text.setText("99");
        escape(text);
        check(action.getX() == -123 && coordinate.getComponent(0) instanceof JLabel, "Esc 没有取消坐标编辑");

        JPanel connector = new JPanel();
        JLabel label = new JLabel("40 ms");
        JPanel arrow = new JPanel();
        connector.add(label); connector.add(arrow);
        Method delay = ActionTimelinePanel.class.getDeclaredMethod("beginDelayEdit",
                JPanel.class, JLabel.class, JComponent.class, MacroAction.class);
        delay.setAccessible(true);
        delay.invoke(timeline, connector, label, arrow, action);
        JTextField delayText = (JTextField) connector.getComponent(0);
        delayText.setText(""); blur(delayText);
        check(action.getDelay() == 40 && delayText.getParent() == connector, "空延迟被提交或静默丢弃");
        delayText.setText("9223372036854775808"); blur(delayText);
        check(action.getDelay() == 40, "溢出延迟污染动作");
        delayText.setText("0"); delayText.postActionEvent();
        check(action.getDelay() == 0 && connector.getComponent(0) == label, "零延迟未正确提交");
        delay.invoke(timeline, connector, label, arrow, action);
        delayText = (JTextField) connector.getComponent(0);
        delayText.setText("900"); escape(delayText);
        check(action.getDelay() == 0 && connector.getComponent(0) == label, "Esc 没有取消延迟编辑");
    }

    private static void testSettings(Path directory) throws Exception {
        Path configPath = Path.of("config.properties");
        if (!Files.isRegularFile(configPath)) return;
        byte[] before = Files.readAllBytes(configPath);
        AppConfig config = isolatedConfig(directory);
        SettingsDialog cancelled = new SettingsDialog(null, config);
        JTextField interval = numericField(cancelled, "鼠标移动采样间隔（ms）");
        interval.setText(""); blur(interval);
        button(cancelled, "取消").doClick();
        check(!Files.exists(directory.resolve("config.properties")), "取消设置时保存了无效数值");
        SettingsDialog settings = new SettingsDialog(null, config);
        numericField(settings, "鼠标移动采样间隔（ms）").setText("35");
        button(settings, "保存").doClick();
        Properties saved = new Properties();
        try (var input = Files.newBufferedReader(directory.resolve("config.properties"))) { saved.load(input); }
        check("35".equals(saved.getProperty("record.mouseMoveInterval")), "设置保存没有提交最新采样间隔");
        AppConfig reloaded = isolatedConfig(directory);
        Method load = AppConfig.class.getDeclaredMethod("load");
        load.setAccessible(true);
        load.invoke(reloaded);
        check(reloaded.getMouseMoveInterval() == 35, "配置保存后重新读取发生改变");
        check(Arrays.equals(before, Files.readAllBytes(configPath)), "测试修改了用户配置文件");
    }

    /** 只在测试对象中将既有配置读写入口指向临时目录，保持生产 AppConfig 完全不变。 */
    private static AppConfig isolatedConfig(Path directory) throws Exception {
        AppConfig config = new AppConfig();
        for (String name : List.of("applicationDirectory", "defaultSaveDirectory", "defaultScreenCaptureDirectory", "configFile")) {
            var field = AppConfig.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(config, name.equals("configFile") ? directory.resolve("config.properties").toFile() : directory.toFile());
        }
        var properties = AppConfig.class.getDeclaredField("properties");
        properties.setAccessible(true);
        ((Properties) properties.get(config)).clear();
        return config;
    }

    private static List<Component> children(Container root) {
        List<Component> result = new ArrayList<>();
        for (Component component : root.getComponents()) {
            result.add(component);
            if (component instanceof Container container) result.addAll(children(container));
        }
        return result;
    }

    private static JTextField numericField(Container root, String name) {
        return (JTextField) children(root).stream().filter(component -> component instanceof JTextField field
                && name.equals(field.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow();
    }

    private static JButton button(Container root, String name) {
        return (JButton) children(root).stream().filter(component -> component instanceof JButton button
                && name.equals(button.getText())).findFirst().orElseThrow();
    }

    private static void blur(JTextField field) {
        for (var listener : field.getFocusListeners()) listener.focusLost(new FocusEvent(field, FocusEvent.FOCUS_LOST));
    }

    private static void escape(JTextField field) {
        for (var listener : field.getKeyListeners()) listener.keyPressed(new KeyEvent(field,
                KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED));
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
