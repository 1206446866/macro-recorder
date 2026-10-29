package com.macro.ui;

import com.macro.flow.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@EnabledOnOs(OS.WINDOWS)
class ImageClickEditorTest {
    @Test void jumpStaysLastAndClickModeTracksOnlySuccessfulClickBehavior() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            ActionEditorDialog dialog = ActionEditorDialog.edit(null, node("synthetic.png"), n -> fail("不得保存"), ignored -> { });
            try {
                JComboBox<?> success = named(dialog, "successBehavior"), failure = named(dialog, "failureBehavior"), mode = named(dialog, "imageClickMode");
                assertEquals(ImageCheckBehavior.JUMP, success.getItemAt(success.getItemCount() - 1));
                assertEquals(ImageCheckBehavior.JUMP, failure.getItemAt(failure.getItemCount() - 1));
                assertEquals(ImageCheckBehavior.values().length, success.getItemCount());
                assertEquals(success.getItemCount(), failure.getItemCount());
                for (int i = 0; i < success.getItemCount(); i++) assertEquals(success.getItemAt(i), failure.getItemAt(i));
                JTextField x = coordinate(dialog, "failureClickX"), y = coordinate(dialog, "failureClickY");
                assertFalse(x.isEnabled()); assertFalse(y.isEnabled());
                assertFalse(x.isVisible()); assertFalse(x.getParent().isVisible());
                assertFalse(y.isVisible()); assertFalse(mode.isVisible());
                failure.setSelectedItem(ImageCheckBehavior.CLICK_EXECUTE);
                assertTrue(x.isEnabled()); assertTrue(y.isEnabled());
                assertTrue(x.isVisible()); assertTrue(x.getParent().isVisible());
                assertTrue(y.isVisible());
                x.setText("0"); y.setText("-240");
                assertFalse(mode.isEnabled());
                assertEquals(ImageClickMode.CENTER, mode.getSelectedItem());
                success.setSelectedItem(ImageCheckBehavior.CLICK_EXECUTE);
                assertTrue(mode.isEnabled());
                assertTrue(mode.isVisible());
                mode.setSelectedItem(ImageClickMode.RANDOM);
                success.setSelectedItem(ImageCheckBehavior.CLICK_TERMINATE);
                assertTrue(mode.isEnabled());
                failure.setSelectedItem(ImageCheckBehavior.JUMP);
                assertFalse(x.isEnabled()); assertFalse(y.isEnabled());
                assertFalse(x.isVisible()); assertFalse(y.isVisible());
                assertEquals("0", x.getText()); assertEquals("-240", y.getText());
                assertTrue(ImageJumpEditorTest.target(dialog, "failureTarget").isEnabled());
                assertTrue(mode.isEnabled());
                success.setSelectedItem(ImageCheckBehavior.EXECUTE);
                assertFalse(mode.isEnabled());
                assertFalse(mode.isVisible());
                success.setSelectedItem(ImageCheckBehavior.CLICK_EXECUTE);
                assertEquals(ImageClickMode.RANDOM, mode.getSelectedItem());
                assertFalse(ImageJumpEditorTest.target(dialog, "successTarget").isEnabled());
            } finally { dialog.dispose(); }
        });
    }

    @Test void saveAndEditPreserveClickSettingsAndCancelLeavesOriginalUntouched() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        Path template = template();
        SwingUtilities.invokeAndWait(() -> {
            AtomicReference<MacroNode> saved = new AtomicReference<>();
            ActionEditorDialog create = ActionEditorDialog.create(null, saved::set, (parent, options) -> Optional.of(template.toFile()));
            try {
                JComboBox<?> type = ImageJumpEditorTest.components(create).stream().filter(JComboBox.class::isInstance)
                        .map(c -> (JComboBox<?>) c).findFirst().orElseThrow();
                for (int i = 0; i < type.getItemCount(); i++) if (type.getItemAt(i).toString().equals("IMAGE_CHECK")) type.setSelectedIndex(i);
                named(create, "successBehavior").setSelectedItem(ImageCheckBehavior.CLICK_TERMINATE);
                named(create, "imageClickMode").setSelectedItem(ImageClickMode.RANDOM);
                named(create, "failureBehavior").setSelectedItem(ImageCheckBehavior.CLICK_EXECUTE);
                coordinate(create, "failureClickX").setText("0");
                coordinate(create, "failureClickY").setText("-240");
                ImageJumpEditorTest.button(create, "选择模板 PNG").doClick();
                ImageJumpEditorTest.button(create, "插入").doClick();
                assertEquals(ImageCheckBehavior.CLICK_TERMINATE, saved.get().getImageCheckConfig().getSuccessBehavior());
                assertEquals(ImageClickMode.RANDOM, saved.get().getImageCheckConfig().getClickMode());
                assertEquals(ImageCheckBehavior.CLICK_EXECUTE, saved.get().getImageCheckConfig().getFailureBehavior());
                assertEquals(0, saved.get().getImageCheckConfig().getFailureClickX());
                assertEquals(-240, saved.get().getImageCheckConfig().getFailureClickY());
                saved.get().setId("image");
            } finally { create.dispose(); }
            MacroNode original = saved.get();
            ActionEditorDialog edit = ActionEditorDialog.edit(null, original, saved::set, ignored -> { });
            try {
                assertEquals(ImageCheckBehavior.CLICK_TERMINATE, named(edit, "successBehavior").getSelectedItem());
                assertEquals(ImageClickMode.RANDOM, named(edit, "imageClickMode").getSelectedItem());
                assertTrue(named(edit, "imageClickMode").isEnabled());
                assertEquals("0", coordinate(edit, "failureClickX").getText());
                assertEquals("-240", coordinate(edit, "failureClickY").getText());
                assertTrue(coordinate(edit, "failureClickX").isEnabled());
                named(edit, "failureBehavior").setSelectedItem(ImageCheckBehavior.CLICK_TERMINATE);
                coordinate(edit, "failureClickX").setText("999");
                named(edit, "successBehavior").setSelectedItem(ImageCheckBehavior.CLICK_EXECUTE);
                named(edit, "imageClickMode").setSelectedItem(ImageClickMode.CENTER);
            } finally { edit.dispose(); }
            assertSame(original, saved.get());
            assertEquals(ImageCheckBehavior.CLICK_TERMINATE, original.getImageCheckConfig().getSuccessBehavior());
            assertEquals(ImageClickMode.RANDOM, original.getImageCheckConfig().getClickMode());
            assertEquals(ImageCheckBehavior.CLICK_EXECUTE, original.getImageCheckConfig().getFailureBehavior());
            assertEquals(0, original.getImageCheckConfig().getFailureClickX());
        });
    }

    @Test void clickControlsFitEditorAndTimelineUsesContinuationAndTerminationRoutes() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        Path template = template();
        SwingUtilities.invokeAndWait(() -> {
            MacroNode image = node(template.toString());
            image.getImageCheckConfig().setSuccessBehavior(ImageCheckBehavior.CLICK_EXECUTE);
            ActionEditorDialog dialog = ActionEditorDialog.edit(null, image, ignored -> { }, ignored -> { });
            try {
                named(dialog, "failureBehavior").setSelectedItem(ImageCheckBehavior.CLICK_TERMINATE);
                coordinate(dialog, "failureClickX").setText("1230");
                coordinate(dialog, "failureClickY").setText("646");
                ImageJumpEditorTest.layout(dialog);
                for (String name : List.of("imageClickMode", "successTarget", "failureTarget", "scaleMatchingEnabled", "failureClickX", "failureClickY")) {
                    Component component = ImageJumpEditorTest.components(dialog).stream().filter(c -> name.equals(c.getName())).findFirst().orElseThrow();
                    if (!component.isVisible()) continue;
                    Rectangle bounds = SwingUtilities.convertRectangle(component.getParent(), component.getBounds(), dialog.getContentPane());
                    assertTrue(bounds.width > 0 && bounds.height > 0 && bounds.y >= 0 && bounds.y + bounds.height <= dialog.getContentPane().getHeight(), name + "被裁切");
                }
                if (Boolean.getBoolean("macro.image.click.preview")) {
                    Path output = Path.of(System.getProperty("macro.image.click.previewDirectory"));
                    Files.createDirectories(output);
                    JComponent content = (JComponent) dialog.getContentPane();
                    BufferedImage preview = new BufferedImage((int) Math.ceil(content.getWidth() * 1.5),
                            (int) Math.ceil(content.getHeight() * 1.5), BufferedImage.TYPE_INT_ARGB);
                    Graphics2D graphics = preview.createGraphics();
                    try { graphics.scale(1.5, 1.5); content.printAll(graphics); } finally { graphics.dispose(); }
                    ImageIO.write(preview, "png", output.resolve("click-editor-150.png").toFile());
                }
                var method = ActionTimelinePanel.class.getDeclaredMethod("behaviorConditions", MacroNode.class, ImageCheckBehavior.class);
                method.setAccessible(true);
                assertEquals("成功 / 未找到", method.invoke(null, image, ImageCheckBehavior.EXECUTE));
                image.getImageCheckConfig().setSuccessBehavior(ImageCheckBehavior.CLICK_TERMINATE);
                assertEquals("识别成功", method.invoke(null, image, ImageCheckBehavior.TERMINATE));
                MacroFlow flow = MacroFlowFactory.rebuildLinear(List.of(image, ImageJumpEditorTest.action("next")), "end");
                assertEquals("end", MacroFlowFactory.branchFor(image, BranchCondition.FOUND).getTargetId());
                image.getImageCheckConfig().setSuccessBehavior(ImageCheckBehavior.CLICK_EXECUTE);
                MacroFlowFactory.rebuildLinear(MacroFlowFactory.timelineNodes(flow), "end");
                assertEquals("next", MacroFlowFactory.branchFor(image, BranchCondition.FOUND).getTargetId());
            } catch (Exception exception) { throw new AssertionError(exception); }
            finally { dialog.dispose(); }
        });
    }

    @Test void failureCoordinatesRequireBothSignedIntegersAndInactiveFieldsDoNotBlockSaving() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        Path template = template();
        SwingUtilities.invokeAndWait(() -> {
            ActionEditorDialog dialog = ActionEditorDialog.edit(null, node(template.toString()), ignored -> {}, null);
            try {
                named(dialog, "failureBehavior").setSelectedItem(ImageCheckBehavior.CLICK_EXECUTE);
                JTextField x = coordinate(dialog, "failureClickX"), y = coordinate(dialog, "failureClickY");
                Field panelField = ActionEditorDialog.class.getDeclaredField("currentPropertyPanel"); panelField.setAccessible(true);
                Object panel = panelField.get(dialog);
                Method create = panel.getClass().getDeclaredMethod("createImageNode", long.class); create.setAccessible(true);
                for (String[] values : List.of(new String[]{"", "0"}, new String[]{"0", ""},
                        new String[]{"-", "0"}, new String[]{"2147483648", "0"}, new String[]{"0", "-2147483649"})) {
                    x.setText(values[0]); y.setText(values[1]);
                    InvocationTargetException error = assertThrows(InvocationTargetException.class, () -> create.invoke(panel, 100L));
                    assertInstanceOf(IllegalArgumentException.class, error.getCause());
                }
                x.setText("0"); y.setText("-2147483648");
                MacroNode saved = (MacroNode) create.invoke(panel, 100L);
                assertEquals(0, saved.getImageCheckConfig().getFailureClickX());
                assertEquals(Integer.MIN_VALUE, saved.getImageCheckConfig().getFailureClickY());
                named(dialog, "failureBehavior").setSelectedItem(ImageCheckBehavior.EXECUTE);
                x.setText("2147483648");
                saved = (MacroNode) create.invoke(panel, 100L);
                assertNull(saved.getImageCheckConfig().getFailureClickX());
                assertEquals(Integer.MIN_VALUE, saved.getImageCheckConfig().getFailureClickY());
                assertFalse(x.isEnabled()); assertFalse(y.isEnabled());
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            finally { dialog.dispose(); }
        });
    }

    private static JTextField coordinate(Container root, String name) {
        return (JTextField) ImageJumpEditorTest.components(root).stream().filter(c -> name.equals(c.getName())).findFirst().orElseThrow();
    }

    private static JComboBox<?> named(Container root, String name) {
        return (JComboBox<?>) ImageJumpEditorTest.components(root).stream().filter(c -> name.equals(c.getName())).findFirst().orElseThrow();
    }

    private static MacroNode node(String reference) {
        ImageCheckConfig config = new ImageCheckConfig(reference, .9);
        config.setFailureBehavior(ImageCheckBehavior.EXECUTE);
        return new MacroNode("image", NodeType.IMAGE_CHECK, null, config, new ArrayList<>());
    }

    private static Path template() throws Exception {
        Path root = Files.createDirectories(Path.of(System.getProperty("macro.test.sandboxRoot", "target/test-sandboxes")));
        Path path = Files.createTempDirectory(root, "click-editor-").resolve("template.png");
        ImageIO.write(new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB), "png", path.toFile());
        return path;
    }
}
