package com.macro.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.macro.flow.*;
import com.macro.util.ImageTemplatePathUtil;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ImageClickStorageTest {
    @Test void clickBehaviorsAndModesRoundTripAndSurviveSaveAs() throws Exception {
        Path directory = directory();
        MacroStorage storage = new MacroStorage();
        for (var behavior : List.of(ImageCheckBehavior.CLICK_EXECUTE, ImageCheckBehavior.CLICK_TERMINATE)) {
            for (var mode : ImageClickMode.values()) {
                MacroFlow flow = flow(behavior, mode);
                Path file = directory.resolve(behavior.name() + mode.name() + ".mr");
                storage.save(List.of(), null, flow, file.toString());
                assertEquals(flow, storage.load(file.toString()).getFlow());
                var moved = ImageTemplatePathUtil.forSave(flow, directory.toFile(), directory.resolve("other/macro.mr").toFile());
                var config = moved.getNodeMap().get("image").getImageCheckConfig();
                assertEquals(behavior, config.getSuccessBehavior());
                assertEquals(mode, config.getClickMode());
                assertTrue(config.isScaleMatchingEnabled());
                assertEquals("template.png", flow.getNodeMap().get("image").getImageCheckConfig().getImagePath());
                assertEquals(flow.getNodeMap().get("image").getChildren(), moved.getNodeMap().get("image").getChildren());
            }
        }
    }

    @Test void missingOrNullClickModeUsesCenterAndOldBehaviorRemainsUnchanged() throws Exception {
        Path directory = directory();
        MacroStorage storage = new MacroStorage();
        Path current = directory.resolve("current.mr");
        storage.save(List.of(), null, flow(ImageCheckBehavior.EXECUTE, ImageClickMode.RANDOM), current.toString());
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode tree = (ObjectNode) mapper.readTree(current.toFile());
        ObjectNode config = (ObjectNode) tree.path("flow").path("nodeMap").path("image").path("imageCheckConfig");
        config.remove("clickMode");
        Path old = directory.resolve("old.mr");
        mapper.writeValue(old.toFile(), tree);
        var loaded = storage.load(old.toString()).getFlow().getNodeMap().get("image").getImageCheckConfig();
        assertEquals(ImageClickMode.CENTER, loaded.getClickMode());
        assertEquals(ImageCheckBehavior.EXECUTE, loaded.getSuccessBehavior());
        assertNull(loaded.getFailureClickX());
        assertNull(loaded.getFailureClickY());
        config.putNull("clickMode");
        Path nullMode = directory.resolve("null.mr");
        mapper.writeValue(nullMode.toFile(), tree);
        assertEquals(ImageClickMode.CENTER, storage.load(nullMode.toString()).getFlow().getNodeMap().get("image").getImageCheckConfig().getClickMode());
    }

    @Test void failureClickWithoutCoordinatesIsRejectedOnSaveAndLoadWithoutOverwritingOriginal() throws Exception {
        Path directory = directory();
        MacroStorage storage = new MacroStorage();
        MacroFlow flow = flow(ImageCheckBehavior.CLICK_EXECUTE, ImageClickMode.CENTER);
        Path file = directory.resolve("valid.mr");
        storage.save(List.of(), null, flow, file.toString());
        byte[] original = Files.readAllBytes(file);
        flow.getNodeMap().get("image").getImageCheckConfig().setFailureBehavior(ImageCheckBehavior.CLICK_TERMINATE);
        assertThrows(IllegalArgumentException.class, () -> storage.save(List.of(), null, flow, file.toString()));
        assertArrayEquals(original, Files.readAllBytes(file));
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode tree = (ObjectNode) mapper.readTree(original);
        ((ObjectNode) tree.path("flow").path("nodeMap").path("image").path("imageCheckConfig")).put("failureBehavior", "CLICK_EXECUTE");
        Path invalid = directory.resolve("invalid.mr");
        mapper.writeValue(invalid.toFile(), tree);
        assertThrows(IllegalArgumentException.class, () -> storage.load(invalid.toString()));
    }

    @Test void failureBehaviorsAndIndependentCoordinatesRoundTripAndSurviveSaveAs() throws Exception {
        Path directory = directory();
        MacroStorage storage = new MacroStorage();
        for (var behavior : List.of(ImageCheckBehavior.CLICK_EXECUTE, ImageCheckBehavior.CLICK_TERMINATE)) {
            MacroFlow flow = flow(ImageCheckBehavior.CLICK_EXECUTE, ImageClickMode.RANDOM);
            var config = flow.getNodeMap().get("image").getImageCheckConfig();
            config.setFailureBehavior(behavior); config.setFailureClickX(0); config.setFailureClickY(-240);
            flow = MacroFlowFactory.rebuildLinear(MacroFlowFactory.timelineNodes(flow), "end");
            Path file = directory.resolve(behavior + ".mr");
            storage.save(List.of(), null, flow, file.toString());
            assertEquals(flow, storage.load(file.toString()).getFlow());
            var copied = ImageTemplatePathUtil.forSave(flow, directory.toFile(), directory.resolve("elsewhere/macro.mr").toFile())
                    .getNodeMap().get("image").getImageCheckConfig();
            assertEquals(0, copied.getFailureClickX()); assertEquals(-240, copied.getFailureClickY());
            assertEquals(behavior, copied.getFailureBehavior()); assertEquals(ImageClickMode.RANDOM, copied.getClickMode());
            assertNotSame(config, copied);
        }
    }

    @Test void partialCoordinatesCannotOverwriteValidFileAndInactiveCoordinatesArePreserved() throws Exception {
        Path directory = directory(), file = directory.resolve("existing.mr");
        MacroStorage storage = new MacroStorage();
        MacroFlow flow = flow(ImageCheckBehavior.EXECUTE, ImageClickMode.CENTER);
        var config = flow.getNodeMap().get("image").getImageCheckConfig();
        config.setFailureClickX(Integer.MIN_VALUE); config.setFailureClickY(Integer.MAX_VALUE);
        storage.save(List.of(), null, flow, file.toString());
        assertEquals(flow, storage.load(file.toString()).getFlow());
        byte[] original = Files.readAllBytes(file);
        config.setFailureBehavior(ImageCheckBehavior.CLICK_EXECUTE); config.setFailureClickY(null);
        assertThrows(IllegalArgumentException.class, () -> storage.save(List.of(), null, flow, file.toString()));
        assertArrayEquals(original, Files.readAllBytes(file));
        ObjectMapper mapper = new ObjectMapper(); ObjectNode tree = (ObjectNode) mapper.readTree(original);
        ObjectNode data = (ObjectNode) tree.path("flow").path("nodeMap").path("image").path("imageCheckConfig");
        data.put("failureBehavior", "CLICK_EXECUTE"); data.putNull("failureClickX");
        Path invalid = directory.resolve("partial.mr"); mapper.writeValue(invalid.toFile(), tree);
        assertThrows(IllegalArgumentException.class, () -> storage.load(invalid.toString()));
    }

    private static MacroFlow flow(ImageCheckBehavior behavior, ImageClickMode mode) {
        var config = new ImageCheckConfig("template.png", .9);
        config.setSuccessBehavior(behavior);
        config.setClickMode(mode);
        config.setScaleMatchingEnabled(true);
        return MacroFlowFactory.rebuildLinear(List.of(new MacroNode("image", NodeType.IMAGE_CHECK,
                null, config, new ArrayList<>())), "end");
    }

    private static Path directory() throws Exception {
        Path root = Files.createDirectories(Path.of(System.getProperty("macro.test.sandboxRoot", "target/test-sandboxes")));
        return Files.createTempDirectory(root, "click-storage-");
    }
}
