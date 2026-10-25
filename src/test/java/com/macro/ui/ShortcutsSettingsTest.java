package com.macro.ui;

import com.macro.config.AppConfig;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 隔离配置中的隐藏设置窗口检查；不注册全局按键，不读取剪贴板，不发送输入。 */
public final class ShortcutsSettingsTest {
    public static void main(String[] args) {
        int exit = 0;
        try {
            Path classes = Path.of(AppConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            check(classes.toString().contains("test-sandboxes"), "必须隔离个人配置");
            SwingUtilities.invokeAndWait(() -> {
                try { verify(); } catch (Exception failure) { throw new AssertionError(failure); }
            });
            System.out.println("通过：独立快捷键页、现有按键与生效场景、只读、窄宽布局、切页滚动高度及取消不写配置");
        } catch (Throwable failure) {
            failure.printStackTrace();
            exit = 1;
        }
        System.exit(exit);
    }

    private static void verify() throws Exception {
        AppConfig config = new AppConfig();
        check(config.updateStandbyHeadEnabled(true), "隔离配置准备失败");
        Field fileField = AppConfig.class.getDeclaredField("configFile");
        fileField.setAccessible(true);
        Path file = ((File) fileField.get(config)).toPath();
        byte[] before = Files.readAllBytes(file);
        SettingsDialog dialog = new SettingsDialog(null, config);
        try {
            List<Component> components = descendants(dialog.getContentPane());
            JPanel pages = (JPanel) components.stream().filter(c -> c instanceof JPanel panel
                            && panel.getLayout() instanceof CardLayout)
                    .findFirst().orElseThrow();
            check(pages.getComponentCount() == 6, "既有设置页面丢失或新增分类未单独成页");
            Dimension recordingSize = pages.getPreferredSize();
            JButton navigation = button(components, "快捷键");
            navigation.doClick();
            JPanel shortcuts = (JPanel) Arrays.stream(pages.getComponents())
                    .filter(Component::isVisible).findFirst().orElseThrow();
            check(shortcuts.getClass().getSimpleName().equals("ShortcutsSettingsPanel"), "导航未切到快捷键页");
            check(Boolean.TRUE.equals(navigation.getClientProperty("settings.selected")), "导航选中态未同步");
            List<Component> shortcutComponents = descendants(shortcuts);
            List<String> text = shortcutComponents.stream().filter(JLabel.class::isInstance)
                    .map(c -> ((JLabel) c).getText()).toList();
            for (String expected : List.of("F10", "F11", "F12", "Delete", "Enter", "Esc", "Alt + F4", "Ctrl + V",
                    "录制时截图", "暂停 / 继续播放", "停止录制 / 播放", "删除选中操作", "保存当前输入", "取消当前输入",
                    "确认截图", "取消截图", "粘贴图片", "保存设置 / 确认提示", "关闭设置，不保存修改",
                    "关闭图像识别窗口", "关闭提示窗口", "图像采集页可用，文字输入框除外")) {
                check(text.contains(expected), "缺少现有快捷键或场景：" + expected);
            }
            check(text.stream().filter("F11"::equals).count() == 1, "F11 不应被列为录制快捷键");
            check(shortcutComponents.stream().noneMatch(c -> c instanceof AbstractButton
                    || c instanceof JTextComponent || c instanceof JComboBox<?> || c instanceof JSpinner),
                    "快捷键页含有可编辑或可改绑控件");
            check(!shortcuts.isOpaque(), "快捷键页未复用深色背景");
            check(pages.getPreferredSize().equals(shortcuts.getPreferredSize()), "滚动高度没有匹配快捷键页");
            check(pages.getPreferredSize().height > recordingSize.height, "没有覆盖长页面滚动");
            JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, pages);
            for (int width : new int[]{820, 920}) {
                dialog.setSize(width, 640);
                layoutTree(dialog);
                check(scroll.getHorizontalScrollBarPolicy() == JScrollPane.HORIZONTAL_SCROLLBAR_NEVER,
                        "不应出现水平滚动条");
                check(scroll.getVerticalScrollBar().isVisible(), "长列表不能滚动");
                for (Component component : shortcutComponents) {
                    if (component instanceof JLabel label) {
                        check(label.getWidth() >= label.getPreferredSize().width,
                                "宽度 " + width + " 裁切了快捷键说明：" + label.getText()
                                        + "，实际=" + label.getBounds() + "，期望=" + label.getPreferredSize()
                                        + "，父级=" + label.getParent().getBounds()
                                        + "，页面=" + shortcuts.getBounds());
                    }
                }
                // 实际 Swing 离屏绘制，验证绘制入口；不代表真实 Windows DPI 验收。
                BufferedImage image = new BufferedImage(width, 640, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics();
                try { dialog.getRootPane().printAll(graphics); } finally { graphics.dispose(); }
            }
            scroll.getViewport().setViewPosition(new Point(0, 200));
            button(components, "录制").doClick();
            check(pages.getPreferredSize().equals(recordingSize), "长快捷键页改变了录制页滚动高度");
            check(scroll.getViewport().getViewPosition().y == 0, "切页没有回到顶部");
            for (String name : List.of("播放", "文件", "界面", "快捷键")) button(components, name).doClick();
            check(Arrays.equals(before, Files.readAllBytes(file)), "浏览快捷键改写了配置文件");
            button(components, "取消").doClick();
            check(!dialog.isSettingsSaved() && Arrays.equals(before, Files.readAllBytes(file)),
                    "关闭只读页面保存了配置");
        } finally {
            dialog.dispose();
        }
    }

    private static JButton button(List<Component> components, String text) {
        return (JButton) components.stream().filter(c -> c instanceof JButton button
                && text.equals(button.getText())).findFirst().orElseThrow();
    }

    private static List<Component> descendants(Container parent) {
        List<Component> result = new ArrayList<>();
        for (Component component : parent.getComponents()) {
            result.add(component);
            if (component instanceof Container container) result.addAll(descendants(container));
        }
        return result;
    }

    private static void layoutTree(Container container) {
        container.doLayout();
        for (Component component : container.getComponents()) {
            if (component instanceof Container child) layoutTree(child);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
