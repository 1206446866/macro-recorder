package com.macro.storage;

import com.macro.model.MacroAction;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Arrays;

public class MacroStorageFailureTest {

    public static void main(String[] args) throws Exception {
        Path testDir = Files.createTempDirectory("macro-overwrite-test-");
        Path target = testDir.resolve("existing.mr");

        // 模拟已经存在的宏文件，记录原始内容。
        byte[] originalContent = "ORIGINAL-MACRO-CONTENT"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(target, originalContent);

        // 模拟序列化动作列表时发生异常。
        var brokenAction = new MacroAction() {
            @Override
            public int getX() {
                throw new IllegalStateException("模拟动作数据读取失败");
            }
        };
        brokenAction.setType(com.macro.model.ActionType.MOUSE_MOVE);

        MacroStorage storage = new MacroStorage();

        try {
            storage.save(List.of(brokenAction), null, target.toString());
            throw new AssertionError("测试失败：预期保存抛出异常");
        } catch (IllegalStateException e) {
            System.out.println("异常传递测试通过：" + e.getMessage());
        } catch (Exception e) {
            System.out.println("保存异常已传回：" + e.getClass().getSimpleName());
        }

        // 检查原有文件内容是否完整。
        byte[] actualContent = Files.readAllBytes(target);
        if (!Arrays.equals(originalContent, actualContent)) {
            throw new AssertionError("测试失败：原有文件内容发生变化");
        }

        // 检查是否存在残留临时文件。
        try (var files = Files.list(testDir)) {
            boolean tempExists = files.anyMatch(path ->
                    path.getFileName().toString().startsWith("macro-")
                            && path.getFileName().toString().endsWith(".tmp")
            );

            if (tempExists) {
                throw new AssertionError("测试失败：存在残留临时文件");
            }
        }

        System.out.println("测试通过：原有文件内容完整，临时文件已清理");
    }
}
