package com.macro.ui;

import com.macro.flow.ImageCheckConfig;
import com.macro.flow.MacroNode;
import com.macro.flow.NodeType;
import com.macro.ui.dialog.ImageRecognitionFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** 隐藏窗口内验证编辑和预览参数，不截图、不注册监听、不读写个人设置。 */
@EnabledOnOs(OS.WINDOWS)
class ScaleMatchingEditorTest {
    @Test
    void newImageActionDefaultsOffAndSavedEditRestoresTheFlag() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        Path template = template();
        AtomicReference<MacroNode> saved = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            ActionEditorDialog dialog = ActionEditorDialog.create(null, saved::set,
                    (parent, options) -> Optional.of(template.toFile()));
            try {
                JComboBox<?> type = ImageJumpEditorTest.components(dialog).stream().filter(JComboBox.class::isInstance)
                        .map(c -> (JComboBox<?>) c).findFirst().orElseThrow();
                for (int index = 0; index < type.getItemCount(); index++) {
                    if (type.getItemAt(index).toString().equals("IMAGE_CHECK")) type.setSelectedIndex(index);
                }
                assertFalse(option(dialog).isSelected());
                option(dialog).doClick();
                ImageJumpEditorTest.button(dialog, "选择模板 PNG").doClick();
                ImageJumpEditorTest.button(dialog, "插入").doClick();
                assertTrue(saved.get().getImageCheckConfig().isScaleMatchingEnabled());
            } finally { dialog.dispose(); }
            saved.get().setId("image");
            ActionEditorDialog edit = ActionEditorDialog.edit(null, saved.get(), saved::set, ignored -> {});
            try {
                assertTrue(option(edit).isSelected());
                ImageJumpEditorTest.layout(edit);
                for (Component component : ImageJumpEditorTest.components(edit)) {
                    if (component.isVisible() && (component == option(edit) || "failureTarget".equals(component.getName()))) {
                        Rectangle bounds = SwingUtilities.convertRectangle(component.getParent(), component.getBounds(), edit.getContentPane());
                        assertTrue(bounds.height > 0 && bounds.y >= 0 && bounds.y + bounds.height <= edit.getContentPane().getHeight(),
                                "新增开关或目标行被裁切");
                    }
                }
                option(edit).doClick();
                ImageJumpEditorTest.button(edit, "保存").doClick();
                assertFalse(saved.get().getImageCheckConfig().isScaleMatchingEnabled());
            } finally { edit.dispose(); }
        });
    }

    @Test
    void recognitionPreviewUsesUnsavedOptionWithoutChangingTheNode() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        Path template = template();
        ImageCheckConfig config = new ImageCheckConfig(template.toString(), .93);
        MacroNode node = new MacroNode("image", NodeType.IMAGE_CHECK, null, config, new ArrayList<>());
        SwingUtilities.invokeAndWait(() -> {
            ActionEditorDialog edit = ActionEditorDialog.edit(null, node, ignored -> fail("不能自动保存"), null);
            try {
                Object panel = field(edit, "currentPropertyPanel");
                Method prepare = panel.getClass().getDeclaredMethod("prepareRecognitionTest");
                prepare.setAccessible(true);
                for (boolean enabled : new boolean[]{true, false}) {
                    option(edit).setSelected(enabled);
                    ImageRecognitionFrame preview = (ImageRecognitionFrame) prepare.invoke(panel);
                    assertEquals(enabled, field(preview, "scaleMatchingEnabled"));
                    assertFalse((boolean) field(preview, "recognizing"));
                    preview.dispose();
                }
                assertFalse(config.isScaleMatchingEnabled());
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            } finally { edit.dispose(); }
        });
    }

    private static JCheckBox option(Container parent) {
        return (JCheckBox) ImageJumpEditorTest.components(parent).stream()
                .filter(c -> "scaleMatchingEnabled".equals(c.getName())).findFirst().orElseThrow();
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Path template() throws Exception {
        Path root = Files.createDirectories(Path.of(System.getProperty("macro.test.sandboxRoot", "target/test-sandboxes")));
        Path file = Files.createTempDirectory(root, "scale-editor-").resolve("template.png");
        ImageIO.write(new BufferedImage(12, 12, BufferedImage.TYPE_INT_RGB), "png", file.toFile());
        return file;
    }
}
