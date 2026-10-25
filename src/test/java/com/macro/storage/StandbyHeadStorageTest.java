package com.macro.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.flow.*;
import com.macro.model.MacroAction;
import com.macro.util.ImageTemplatePathUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StandbyHeadStorageTest {
    private Path temporary;

    @BeforeEach void createWorkspaceSandbox() throws Exception {
        Path root = Path.of(System.getProperty("macro.test.sandboxRoot", "target/test-sandboxes"))
                .toAbsolutePath().normalize();
        Files.createDirectories(root);
        temporary = Files.createTempDirectory(root, "standby-head-storage-");
    }

    @Test void enabledWaitRoundTripsWithoutChangingActionIdsOrDelays() throws Exception {
        MacroStorage storage = new MacroStorage();
        MacroFlow flow = flow();
        var ids = List.copyOf(flow.getNodeMap().keySet());
        for (long delay : new long[]{0, 1_000, 1_234, Long.MAX_VALUE}) {
            flow.setHeadWaitMillis(delay);
            Path file = temporary.resolve("head-" + delay + ".mr");
            storage.save(MacroFlowFactory.extractActions(flow), null, flow, file.toString());
            MacroFlow loaded = storage.load(file.toString()).getFlow();
            assertEquals(delay, loaded.getHeadWaitMillis());
            assertEquals(ids, List.copyOf(loaded.getNodeMap().keySet()));
            assertEquals(50, MacroFlowFactory.timelineNodes(loaded).get(1).getAction().getDelay());
        }
    }

    @Test void absentWaitAndLegacyActionOnlyFilesKeepOriginalBehavior() throws Exception {
        MacroStorage storage = new MacroStorage();
        MacroFlow flow = flow();
        Path file = temporary.resolve("old-flow.mr");
        storage.save(MacroFlowFactory.extractActions(flow), null, flow, file.toString());
        assertFalse(new ObjectMapper().readTree(file.toFile()).get("flow").has("headWaitMillis"));
        assertNull(storage.load(file.toString()).getFlow().getHeadWaitMillis());
        Path legacy = temporary.resolve("old-actions.mr");
        storage.save(MacroFlowFactory.extractActions(flow), null, legacy.toString());
        assertNull(storage.load(legacy.toString()).getFlow());
        assertEquals(2, storage.load(legacy.toString()).getActions().size());
    }

    @Test void invalidWaitIsRejectedBeforeFileOrDirectoryChanges() throws Exception {
        MacroStorage storage = new MacroStorage();
        MacroFlow flow = flow();
        Path file = temporary.resolve("original.mr");
        storage.save(MacroFlowFactory.extractActions(flow), null, flow, file.toString());
        byte[] original = Files.readAllBytes(file);
        flow.setHeadWaitMillis(-1L);
        assertThrows(IllegalArgumentException.class,
                () -> storage.save(MacroFlowFactory.extractActions(flow), null, flow, file.toString()));
        assertArrayEquals(original, Files.readAllBytes(file));
        Path absent = temporary.resolve("not-created");
        assertThrows(IllegalArgumentException.class, () -> storage.save(
                MacroFlowFactory.extractActions(flow), null, flow, absent.resolve("bad.mr").toString()));
        assertFalse(Files.exists(absent));
        try (var files = Files.list(temporary)) { assertEquals(List.of(file), files.toList()); }
        var root = new ObjectMapper().readTree(file.toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.get("flow")).put("headWaitMillis", -1);
        new ObjectMapper().writeValue(file.toFile(), root);
        assertThrows(IllegalArgumentException.class, () -> storage.load(file.toString()));
    }

    @Test void templateRebaseAndGraphRebuildKeepWaitAndImageJumpTarget() throws Exception {
        MacroFlow flow = flow();
        MacroNode image = new MacroNode("image", NodeType.IMAGE_CHECK, null,
                new ImageCheckConfig("missing.png", .9), new ArrayList<>());
        List<MacroNode> nodes = MacroFlowFactory.timelineNodes(flow);
        nodes.add(0, image);
        flow = MacroFlowFactory.rebuildLinear(nodes, "end", 1_234L);
        image.getImageCheckConfig().setSuccessBehavior(ImageCheckBehavior.JUMP);
        MacroFlowFactory.branchFor(image, BranchCondition.FOUND).setTargetId(nodes.get(2).getId());
        MacroFlow rebased = ImageTemplatePathUtil.forSave(flow, temporary.resolve("old").toFile(),
                temporary.resolve("new/macro.mr").toFile());
        assertEquals(1_234L, rebased.getHeadWaitMillis());
        assertEquals(nodes.get(2).getId(), MacroFlowFactory.branchFor(
                rebased.getNodeMap().get("image"), BranchCondition.FOUND).getTargetId());
        Path file = temporary.resolve("rebased.mr");
        new MacroStorage().save(MacroFlowFactory.extractActions(rebased), null, rebased, file.toString());
        assertEquals(1_234L, new MacroStorage().load(file.toString()).getFlow().getHeadWaitMillis());
    }

    private static MacroFlow flow() {
        return MacroFlowFactory.fromActions(List.of(MacroAction.mouseMove(1, 2, 0), MacroAction.mouseMove(3, 4, 50)));
    }
}
