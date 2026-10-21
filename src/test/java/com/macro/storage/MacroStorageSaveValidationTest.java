package com.macro.storage;

import com.macro.flow.*;
import com.macro.model.MacroAction;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** 只使用独立的工作区临时目录，不读写个人宏和模板，不构造 Robot。 */
class MacroStorageSaveValidationTest {
    private final MacroStorage storage = new MacroStorage();

    @Test
    void deletedSuccessOrFailureTargetCannotOverwriteValidFileOrLeaveTemporaryFiles() throws Exception {
        for (BranchCondition condition : List.of(BranchCondition.FOUND, BranchCondition.NOT_FOUND)) {
            Path directory = directory();
            Path target = directory.resolve("existing.mr");
            MacroFlow flow = jumpFlow(condition);
            save(flow, target);
            byte[] original = Files.readAllBytes(target);
            MacroFlow originalFlow = storage.load(target.toString()).getFlow();
            MacroFlow broken = deleteTarget(flow, condition);

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> save(broken, target));
            assertTrue(exception.getMessage().contains("无效分支"));
            assertTrue(exception.getMessage().contains("image"));
            assertArrayEquals(original, Files.readAllBytes(target), "无效流程不能覆盖原有有效 MR");
            assertEquals(originalFlow, storage.load(target.toString()).getFlow(), "原文件仍应能重新加载");
            assertEquals(Set.of("existing.mr"), names(directory), "不能产生或遗留临时文件");
        }
    }

    @Test
    void invalidFlowIsRejectedBeforeCreatingDirectoriesOrTemporaryFiles() throws Exception {
        Path directory = directory();
        Path target = directory.resolve("not-created/sub/invalid.mr");
        MacroFlow broken = deleteTarget(jumpFlow(BranchCondition.FOUND), BranchCondition.FOUND);
        assertThrows(IllegalArgumentException.class, () -> save(broken, target));
        assertFalse(Files.exists(target.getParent().getParent()), "结构校验必须先于目录及临时文件创建");
        assertTrue(names(directory).isEmpty());
    }

    @Test
    void forwardJumpCanStillSaveAndReload() throws Exception {
        roundTrip(jumpFlow(BranchCondition.FOUND), directory().resolve("forward.mr"));
    }

    @Test
    void backwardJumpCanStillSaveAndReload() throws Exception {
        roundTrip(jumpFlow(BranchCondition.NOT_FOUND), directory().resolve("backward.mr"));
    }

    @Test
    void nonEmptyMissingTemplatePathStillSavesAndReloadsUnchanged() throws Exception {
        Path directory = directory();
        for (String reference : List.of("not-present.png", directory.resolve("not-present.png").toString())) {
            MacroFlow flow = jumpFlow(BranchCondition.FOUND);
            image(flow).getImageCheckConfig().setImagePath(reference);
            assertFalse(Files.exists(directory.resolve("not-present.png")));
            Path target = directory.resolve(reference.equals("not-present.png") ? "relative.mr" : "absolute.mr");
            roundTrip(flow, target);
            assertEquals(reference, image(storage.load(target.toString()).getFlow()).getImageCheckConfig().getImagePath());
            assertFalse(Files.exists(directory.resolve("not-present.png")));
        }
    }

    @Test
    void nullFlowAndLegacyActionOnlyOverloadRemainCompatible() throws Exception {
        Path directory = directory();
        List<MacroAction> actions = List.of(MacroAction.mouseMove(10, 20, 0));
        Path legacy = directory.resolve("legacy.mr"), explicitNull = directory.resolve("null-flow.mr");
        storage.save(actions, null, legacy.toString());
        storage.save(actions, null, null, explicitNull.toString());
        for (Path target : List.of(legacy, explicitNull)) {
            MacroFile loaded = storage.load(target.toString());
            assertNull(loaded.getFlow());
            assertEquals(actions, loaded.getActions());
        }
        assertEquals(Set.of("legacy.mr", "null-flow.mr"), names(directory));
    }

    private void roundTrip(MacroFlow flow, Path target) throws Exception {
        save(flow, target);
        MacroFile loaded = storage.load(target.toString());
        assertEquals(flow, loaded.getFlow());
        assertEquals(MacroFlowFactory.extractActions(flow), loaded.getActions());
        assertFalse(names(target.getParent()).stream().anyMatch(name -> name.endsWith(".tmp")), "保存结束不能遗留临时文件");
    }

    private void save(MacroFlow flow, Path target) throws Exception {
        storage.save(MacroFlowFactory.extractActions(flow), null, flow, target.toString());
    }

    private static MacroFlow jumpFlow(BranchCondition condition) {
        ImageCheckConfig config = new ImageCheckConfig("not-present.png", .9);
        if (condition == BranchCondition.FOUND) config.setSuccessBehavior(ImageCheckBehavior.JUMP);
        else config.setFailureBehavior(ImageCheckBehavior.JUMP);
        MacroNode image = new MacroNode("image", NodeType.IMAGE_CHECK, null, config, new ArrayList<>(List.of(
                new MacroBranch(condition, condition == BranchCondition.FOUND ? "last" : "first", 0))));
        return MacroFlowFactory.rebuildLinear(List.of(action("first"), image, action("middle"), action("last")), "end");
    }

    private static MacroFlow deleteTarget(MacroFlow flow, BranchCondition condition) {
        String target = MacroFlowFactory.branchFor(image(flow), condition).getTargetId();
        // 与时间线删除一致：重建默认主链，但保留用户指定的跳转引用。
        List<MacroNode> remaining = MacroFlowFactory.timelineNodes(flow);
        remaining.removeIf(node -> node.getId().equals(target));
        return MacroFlowFactory.rebuildLinear(remaining, "end");
    }

    private static MacroNode image(MacroFlow flow) { return flow.getNodeMap().get("image"); }
    private static MacroNode action(String id) {
        return new MacroNode(id, NodeType.ACTION, MacroAction.mouseMove(10, 20, 0), new ArrayList<>());
    }
    private static Path directory() throws Exception {
        Path root = Files.createDirectories(Path.of(System.getProperty("macro.test.sandboxRoot", "target/test-sandboxes")));
        return Files.createTempDirectory(root, "storage-save-validation-");
    }
    private static Set<String> names(Path directory) throws Exception {
        try (var entries = Files.list(directory)) {
            return entries.map(path -> path.getFileName().toString()).collect(Collectors.toSet());
        }
    }
}
