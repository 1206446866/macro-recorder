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

/** 缩放开关及旧 MR 兼容检查，只使用临时路径和缺失模板引用。 */
class ScaleMatchingStorageTest {
    @Test
    void bothStatesRoundTripAndOldFilesDefaultToDisabled() throws Exception {
        MacroStorage storage = new MacroStorage();
        Path root = directory();
        for (boolean enabled : new boolean[]{false, true}) {
            MacroFlow flow = flow(enabled);
            Path file = root.resolve(enabled + ".mr");
            storage.save(List.of(), null, flow, file.toString());
            assertEquals(flow, storage.load(file.toString()).getFlow());
        }
        Path old = root.resolve("old.mr");
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode data = (ObjectNode) mapper.readTree(root.resolve("true.mr").toFile());
        ((ObjectNode) data.path("flow").path("nodeMap").path("image").path("imageCheckConfig"))
                .remove("scaleMatchingEnabled");
        mapper.writeValue(old.toFile(), data);
        assertFalse(storage.load(old.toString()).getFlow().getNodeMap().get("image")
                .getImageCheckConfig().isScaleMatchingEnabled());
    }

    @Test
    void saveAsRebasingKeepsScaleFlagAndLeavesOriginalNodeUntouched() throws Exception {
        Path root = directory();
        MacroFlow original = flow(true);
        MacroFlow rebased = ImageTemplatePathUtil.forSave(original, root.toFile(),
                root.resolve("other/macro.mr").toFile());
        ImageCheckConfig config = rebased.getNodeMap().get("image").getImageCheckConfig();
        assertTrue(config.isScaleMatchingEnabled());
        assertEquals(root.resolve("missing.png").toString(), config.getImagePath());
        assertEquals("missing.png", original.getNodeMap().get("image").getImageCheckConfig().getImagePath());
        assertEquals(original.getNodeMap().get("image").getChildren(), rebased.getNodeMap().get("image").getChildren());
    }

    private static MacroFlow flow(boolean enabled) {
        ImageCheckConfig config = new ImageCheckConfig("missing.png", .9);
        config.setScaleMatchingEnabled(enabled);
        return MacroFlowFactory.rebuildLinear(List.of(new MacroNode("image", NodeType.IMAGE_CHECK,
                null, config, new ArrayList<>())), "end");
    }

    private static Path directory() throws Exception {
        Path root = Files.createDirectories(Path.of(System.getProperty("macro.test.sandboxRoot", "target/test-sandboxes")));
        return Files.createTempDirectory(root, "scale-storage-");
    }
}
