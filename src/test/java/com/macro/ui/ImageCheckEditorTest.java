package com.macro.ui;

import com.macro.flow.*;
import com.macro.model.MacroAction;
import com.macro.ui.theme.ThemeManager;
import com.macro.ui.filedialog.FileDialogOptions;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** 通过现有编辑器控件验证插入、编辑和时间线刷新，并输出九种行为组合的绘制结果。 */
public class ImageCheckEditorTest {
    private static List<Component> children(Container parent) {
        List<Component> result = new ArrayList<>();
        for (Component child : parent.getComponents()) {
            result.add(child);
            if (child instanceof Container nested) result.addAll(children(nested));
        }
        return result;
    }

    private static JButton button(Container root, String text) {
        return (JButton) children(root).stream().filter(c -> c instanceof JButton b
                && text.equals(b.getText())).findFirst().orElseThrow();
    }

    private static void layout(Container parent) {
        parent.doLayout();
        for (Component child : parent.getComponents()) {
            if (child instanceof Container nested) layout(nested);
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("image-editor-test-");
        Path template = directory.resolve("template.png");
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", template.toFile());
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    AtomicReference<MacroNode> inserted = new AtomicReference<>();
                    AtomicReference<Boolean> cancelled = new AtomicReference<>(true);
                    ActionEditorDialog create = ActionEditorDialog.create(null, inserted::set, (parent, options) -> {
                        check(options.mode() == FileDialogOptions.Mode.OPEN_FILE, "模板选择用途不正确");
                        check(options.fileTypes().get(0).extensions().contains("png"), "模板 PNG 过滤器丢失");
                        return cancelled.get() ? java.util.Optional.empty() : java.util.Optional.of(template.toFile());
                    });
                    JComboBox<?> type = (JComboBox<?>) children(create).stream()
                            .filter(c -> c instanceof JComboBox<?>).findFirst().orElseThrow();
                    for (int i = 0; i < type.getItemCount(); i++) {
                        if (type.getItemAt(i).toString().equals("IMAGE_CHECK")) type.setSelectedIndex(i);
                    }
                    // 通过同一选择入口验证取消与确认；原生面板交互由单独的集成测试覆盖。
                    button(create, "选择模板 PNG").doClick();
                    check(inserted.get() == null, "取消文件选择时提交了节点");
                    cancelled.set(false);
                    button(create, "选择模板 PNG").doClick();
                    button(create, "插入").doClick();
                    check(inserted.get() != null, "插入未提交节点");
                    check(inserted.get().getImageCheckConfig().getSuccessBehavior() == ImageCheckBehavior.EXECUTE,
                            "新节点成功默认值不正确");

                    BufferedImage overview = new BufferedImage(2460, 930, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = overview.createGraphics();
                    g.setColor(ThemeManager.getPalette().card()); g.fillRect(0, 0, 2460, 930);
                    int combination = 0;
                    for (ImageCheckBehavior success : List.of(ImageCheckBehavior.RETRY, ImageCheckBehavior.EXECUTE, ImageCheckBehavior.TERMINATE)) {
                        for (ImageCheckBehavior failure : List.of(ImageCheckBehavior.RETRY, ImageCheckBehavior.EXECUTE, ImageCheckBehavior.TERMINATE)) {
                            ActionTimelinePanel timeline = new ActionTimelinePanel();
                            timeline.setActions(List.of(MacroAction.mouseMove(10, 20, 100),
                                    MacroAction.mouseMove(30, 40, 200)));
                            MacroNode anchor = MacroFlowFactory.timelineNodes(timeline.getFlow()).get(1);
                            check(timeline.insertActionBefore(anchor, inserted.get()), "插入时间线失败");
                            MacroNode old = inserted.get();
                            AtomicReference<MacroNode> edited = new AtomicReference<>();
                            ActionEditorDialog editor = ActionEditorDialog.edit(null, old, edited::set, b -> {});
                            List<JComboBox<?>> choices = new ArrayList<>();
                            for (Component c : children(editor)) {
                                if (c instanceof JComboBox<?> combo && combo.getItemAt(0) instanceof ImageCheckBehavior) choices.add(combo);
                            }
                            check(choices.size() == 2, "缺少独立的行为菜单");
                            choices.get(0).setSelectedItem(success);
                            choices.get(1).setSelectedItem(failure);
                            if (combination == 0) {
                                layout(editor);
                                Container content = editor.getContentPane();
                                BufferedImage image = new BufferedImage(content.getWidth(), content.getHeight(), BufferedImage.TYPE_INT_RGB);
                                Graphics2D eg = image.createGraphics(); content.printAll(eg); eg.dispose();
                                ImageIO.write(image, "png", Path.of("target/image-editor-behavior.png").toFile());
                            }
                            button(editor, "保存").doClick();
                            check(timeline.replaceAction(old, edited.get()), "编辑未替换节点");
                            MacroNode node = edited.get();
                            check(node.getImageCheckConfig().getSuccessBehavior() == success
                                    && node.getImageCheckConfig().getFailureBehavior() == failure, "编辑值未提交");
                            check(MacroFlowFactory.findRetryDelay(node) == 1000, "编辑丢失延迟");
                            timeline.setSize(820, 530); layout(timeline);
                            var field = ActionTimelinePanel.class.getDeclaredField("timelinePanel");
                            field.setAccessible(true);
                            JPanel track = (JPanel) field.get(timeline);
                            track.setSize(track.getPreferredSize().width, 266); layout(track);
                            int x = combination % 3 * 820, y = combination / 3 * 310;
                            g.setColor(ThemeManager.getPalette().textPrimary());
                            g.drawString("成功：" + success + "    失败：" + failure, x + 30, y + 20);
                            Graphics2D tile = (Graphics2D)g.create(x, y + 30, 820, 280);
                            track.printAll(tile); tile.dispose();
                            check(timeline.deleteAction(anchor, false), "删除后继失败");
                            for (MacroBranch branch : node.getChildren()) {
                                check(timeline.getFlow().getNodeMap().containsKey(branch.getTargetId()), "删除留下悬空分支");
                            }
                            combination++;
                        }
                    }
                    g.dispose();
                    ImageIO.write(overview, "png", Path.of("target/image-behavior-timelines.png").toFile());
                } catch (Exception exception) { throw new RuntimeException(exception); }
                finally { for (Window w : Window.getWindows()) w.dispose(); }
            });
        } finally { Files.deleteIfExists(template); Files.deleteIfExists(directory); }
        System.out.println("通过：编辑器插入、九种行为编辑、时间线分支刷新及删除后继；布局预览已输出到 target");
    }
}
