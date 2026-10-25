package com.macro.ui;

import com.macro.config.AppConfig;
import com.macro.constant.ApplicationInfo;
import com.macro.ui.theme.ThemeManager;

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

/** 在隔离配置中检查关于页；窗口不显示，不发送键鼠输入。 */
public final class AboutSettingsTest {
    /** 验证类来源后检查隐藏窗口，失败通过独立 JVM 退出码传回 Maven。 */
    public static void main(String[] args) {
        int exit = 0;
        try {
            Path classes = Path.of(AppConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            check(classes.toString().contains("test-sandboxes"), "必须隔离个人配置");
            SwingUtilities.invokeAndWait(() -> {
                try { verify(); } catch (Exception failure) { throw new AssertionError(failure); }
            });
            System.out.println("通过：关于页导航、软件常量、只读、窄宽布局及浏览取消不写配置");
        } catch (Throwable failure) {
            System.getLogger(AboutSettingsTest.class.getName()).log(
                    System.Logger.Level.ERROR, "关于页检查失败", failure);
            exit = 1;
        }
        System.exit(exit);
    }

    /** 检查独立页面、现有主题及切页布局，确认浏览和关闭不保存配置。 */
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
                    && panel.getLayout() instanceof CardLayout).findFirst().orElseThrow();
            check(pages.getComponentCount() == 6, "关于没有独立成页或原有分类丢失");
            Dimension recordingSize = pages.getPreferredSize();
            JButton navigation = button(components, "关于");
            navigation.doClick();
            JPanel about = (JPanel) Arrays.stream(pages.getComponents())
                    .filter(Component::isVisible).findFirst().orElseThrow();
            check(about.getClass().getSimpleName().equals("AboutSettingsPanel"), "关于导航切换错误");
            check(Boolean.TRUE.equals(navigation.getClientProperty("settings.selected")), "关于选中态未更新");
            check(!about.isOpaque(), "关于页没有沿用现有主题背景");
            List<Component> aboutComponents = descendants(about);
            List<JLabel> labels = aboutComponents.stream().filter(JLabel.class::isInstance)
                    .map(JLabel.class::cast).toList();
            List<String> texts = labels.stream().map(JLabel::getText).toList();
            check(texts.contains(ApplicationInfo.APP_NAME), "软件名称没有复用应用常量");
            check(texts.contains("版本 " + ApplicationInfo.APP_VERSION), "版本没有复用应用常量");
            check(texts.contains("键盘与鼠标宏录制工具") && texts.contains("适用平台：Windows"),
                    "缺少软件用途或适用平台");
            check(aboutComponents.stream().noneMatch(c -> c instanceof AbstractButton
                    || c instanceof JTextComponent || c instanceof JComboBox<?> || c instanceof JSpinner),
                    "关于页不应提供可修改控件");
            for (JLabel label : labels) {
                check(label.getForeground().equals(ThemeManager.getPalette().textPrimary())
                        || label.getForeground().equals(ThemeManager.getPalette().textSecondary()),
                        "软件信息文字没有使用现有主题");
            }
            for (int width : new int[]{820, 920}) {
                dialog.setSize(width, 640);
                layoutTree(dialog);
                for (JLabel label : labels) {
                    check(label.getWidth() >= label.getPreferredSize().width,
                            "宽度 " + width + " 裁切了关于文字：" + label.getText()
                                    + "，实际=" + label.getBounds() + "，期望=" + label.getPreferredSize()
                                    + "，父级=" + label.getParent().getBounds() + "，页面=" + about.getBounds());
                    Rectangle bounds = SwingUtilities.convertRectangle(label.getParent(), label.getBounds(), about);
                    check(about.getBounds().width >= bounds.x + bounds.width
                            && about.getBounds().height >= bounds.y + bounds.height, "关于内容越界");
                }
                Rectangle bounds = SwingUtilities.convertRectangle(navigation.getParent(), navigation.getBounds(),
                        dialog.getContentPane());
                check(bounds.y + bounds.height <= dialog.getContentPane().getHeight(), "新增侧栏入口被裁切");
                BufferedImage image = new BufferedImage(width, 640, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics();
                try { dialog.getRootPane().printAll(graphics); } finally { graphics.dispose(); }
            }
            Dimension aboutSize = about.getPreferredSize();
            button(components, "快捷键").doClick();
            button(components, "关于").doClick();
            check(pages.getPreferredSize().equals(aboutSize), "关于页错误沿用了长页面高度");
            button(components, "录制").doClick();
            check(pages.getPreferredSize().equals(recordingSize), "关于页影响了录制页布局");
            navigation.doClick();
            check(Arrays.equals(before, Files.readAllBytes(file)), "查看关于改写了配置文件");
            button(components, "取消").doClick();
            check(!dialog.isSettingsSaved() && Arrays.equals(before, Files.readAllBytes(file)),
                    "关闭关于页面意外保存了配置");
        } finally {
            dialog.dispose();
        }
    }

    /** 从设置窗口查找指定文字的导航或底部按钮。 */
    private static JButton button(List<Component> components, String text) {
        return (JButton) components.stream().filter(c -> c instanceof JButton button
                && text.equals(button.getText())).findFirst().orElseThrow();
    }

    /** 收集子组件，供页面只读与文字检查使用。 */
    private static List<Component> descendants(Container parent) {
        List<Component> result = new ArrayList<>();
        for (Component component : parent.getComponents()) {
            result.add(component);
            if (component instanceof Container container) result.addAll(descendants(container));
        }
        return result;
    }

    /** 对隐藏窗口执行真实布局，不显示到桌面。 */
    private static void layoutTree(Container container) {
        container.doLayout();
        for (Component component : container.getComponents()) {
            if (component instanceof Container child) layoutTree(child);
        }
    }

    /** 将隔离子 JVM 中的检查失败转换为明确异常。 */
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
