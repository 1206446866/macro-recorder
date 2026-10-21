package com.macro.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;

/** 仅由隔离入口运行，实际保存/重载及模拟替换失败不接触个人配置。 */
public final class RecordingBarPlacementTest {
    public static void main(String[] args) throws Exception {
        Path classes = Path.of(AppConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        check(classes.getParent().getFileName().toString().startsWith("RecordingBarPlacementTest-"), "需要隔离应用目录");
        AppConfig config = new AppConfig();
        check(config.getRecordingBarPlacement() == null, "首次使用不应有几何配置");
        Properties properties = properties(config);
        properties.setProperty("home.window.width", "999");
        properties.setProperty("unknown.personal", "保留");
        AppConfig.RecordingBarPlacement saved = new AppConfig.RecordingBarPlacement(-1500, -100, 850);
        check(config.updateRecordingBarPlacement(saved), "保存失败");
        AppConfig loaded = new AppConfig();
        check(saved.equals(loaded.getRecordingBarPlacement()), "位置和宽度没有持久化");
        check(properties(loaded).getProperty("home.window.width").equals("999")
                && properties(loaded).getProperty("unknown.personal").equals("保留"), "其他配置被覆盖");
        check(!properties(loaded).containsKey("record.controlBar.height"), "不应保存高度");
        Path file = classes.resolve("config.properties");
        byte[] before = Files.readAllBytes(file);
        var modified = Files.getLastModifiedTime(file);
        check(loaded.updateRecordingBarPlacement(saved), "相同配置应成功且无需重复写入");
        check(Arrays.equals(before, Files.readAllBytes(file)) && Files.getLastModifiedTime(file).equals(modified), "相同配置重复写盘");
        for (String invalid : new String[]{"", "abc", "2147483648", "0", "-10"}) {
            properties(loaded).setProperty("record.controlBar.width", invalid);
            check(loaded.getRecordingBarPlacement() == null, "非法宽度未回退：" + invalid);
        }
        properties(loaded).remove("record.controlBar.x");
        check(loaded.getRecordingBarPlacement() == null, "不完整配置未回退");
        FailingConfig failed = new FailingConfig();
        Properties oldMemory = new Properties();
        oldMemory.putAll(properties(failed));
        check(!failed.updateRecordingBarPlacement(new AppConfig.RecordingBarPlacement(20, 30, 700)), "替换失败误报成功");
        check(oldMemory.equals(properties(failed)) && saved.equals(failed.getRecordingBarPlacement()), "保存失败未回滚内存");
        check(Arrays.equals(before, Files.readAllBytes(file)) && Files.getLastModifiedTime(file).equals(modified), "保存失败修改了原文件");
        System.out.println("通过：独立三项配置、重载、负坐标、非法值回退、无高度、同值不写盘、原子替换失败回滚及其他配置保全");
    }

    private static final class FailingConfig extends AppConfig {
        @Override void replaceConfigFile(Path temporary, Path target) throws IOException {
            throw new IOException("模拟操作栏配置替换失败");
        }
    }

    private static Properties properties(AppConfig config) throws Exception {
        var field = AppConfig.class.getDeclaredField("properties");
        field.setAccessible(true);
        return (Properties) field.get(config);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
