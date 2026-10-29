package com.macro.config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/** 配置安全回归只运行在 TEST-01 新建的应用目录，不读写个人配置。 */
public final class AppConfigSafetyTest {
    public static final class Write {
        public static void main(String[] args) throws Exception {
            Path directory = isolatedDirectory("Write");
            Path file = directory.resolve("config.properties");
            AppConfig initial = new AppConfig();
            check(Files.isRegularFile(file), "首次启动没有创建配置文件");
            Path oldTemporary = directory.resolve("config-user-kept.tmp");
            Files.writeString(oldTemporary, "此前已经存在的文件", StandardCharsets.UTF_8);
            Set<String> entries = entries(directory);
            properties(initial).setProperty("custom.unknown", "中文=保留值");
            File macros = directory.resolve("宏文件 目录").toFile();
            File images = directory.resolve("图片 目录").toFile();
            check(initial.updateSettings(macros, images, false, false, 37,
                    RecordingStartBehavior.CONTROL_BAR, TimelineInsertPosition.AFTER, true, false),
                    "正常保存被误报为失败");
            AppConfig loaded = new AppConfig();
            check(loaded.getDefaultSaveDirectory().equals(macros)
                            && loaded.getDefaultScreenCaptureDirectory().equals(images)
                            && loaded.getRecordingStartBehavior() == RecordingStartBehavior.CONTROL_BAR
                            && loaded.getTimelineInsertPosition() == TimelineInsertPosition.AFTER
                            && loaded.getMouseMoveInterval() == 37 && !loaded.isRecordMouseMove()
                            && !loaded.isLoadLastMacroOnStartup() && loaded.isMinimizeOnPlayStart()
                            && !loaded.isRestoreWindowAfterPlayStop(), "保存重载改变了合法配置");
            check(properties(loaded).equals(properties(initial)), "未知配置项或 UTF-8 内容丢失");
            check(entries(directory).equals(entries), "正常保存遗留临时文件或删除已有文件");

            partialWriteFailure(directory);
            replacementFailure(directory, new IOException("模拟替换失败"), "保存配置文件失败");
            replacementFailure(directory,
                    new AtomicMoveNotSupportedException("source", "target", "模拟不支持原子替换"),
                    "不支持原子替换");
            check(Files.readString(oldTemporary, StandardCharsets.UTF_8).equals("此前已经存在的文件"),
                    "误删或覆盖此前已存在的临时文件");
            System.out.println("通过：首次创建、原子保存重载、部分写入/替换失败、原子移动不支持、失败反馈及已有文件保全");
        }
    }

    private static void partialWriteFailure(Path directory) throws Exception {
        AppConfig config = new AppConfig();
        Properties broken = new Properties() {
            @Override public void store(Writer writer, String comments) throws IOException {
                writer.write("# partial-write\n");
                writer.flush();
                throw new IOException("模拟写入部分内容后失败");
            }
        };
        broken.putAll(properties(config));
        var field = AppConfig.class.getDeclaredField("properties");
        field.setAccessible(true);
        field.set(config, broken);
        failedSavePreserves(directory, config, "保存配置文件失败");
    }

    private static void replacementFailure(Path directory, IOException failure, String diagnostic) throws Exception {
        FailingReplacementConfig config = new FailingReplacementConfig(failure);
        failedSavePreserves(directory, config, diagnostic);
        check(config.attempts == 1, "替换失败后再次尝试写入或退回非原子路径");
        check(!Files.exists(config.temporaryPath), "替换失败遗留本次临时文件");
    }

    private static void failedSavePreserves(Path directory, AppConfig config, String diagnostic) throws Exception {
        Path file = directory.resolve("config.properties");
        byte[] before = Files.readAllBytes(file);
        var modified = Files.getLastModifiedTime(file);
        Set<String> entries = entries(directory);
        entries.add("log");
        Properties memory = new Properties();
        memory.putAll(properties(config));
        Captured<Boolean> result = capture(() -> config.updateSettings(null, null, true, true, 90,
                RecordingStartBehavior.MINIMIZE, TimelineInsertPosition.BEFORE, false, true));
        check(!result.value(), "保存失败却报告成功");
        check(result.error().contains(diagnostic), "保存失败没有明确诊断：" + result.error());
        check(Arrays.equals(before, Files.readAllBytes(file))
                        && modified.equals(Files.getLastModifiedTime(file)), "失败破坏或替换了原配置");
        check(properties(config).equals(memory), "保存失败未恢复原有内存设置");
        check(entries(directory).equals(entries), "失败清理误删已有文件或遗留新临时文件");
        try (var reports = Files.list(directory.resolve("log"))) {
            check(reports.anyMatch(path -> {
                try { return Files.readString(path).contains(diagnostic); }
                catch (IOException failure) { throw new AssertionError(failure); }
            }), "配置失败未保留详细错误报告");
        }
        System.out.print(result.error());
    }

    /** 只在临时配置已经完整写入后注入失败，验证正式文件在此之前未被触碰。 */
    private static final class FailingReplacementConfig extends AppConfig {
        private final IOException failure;
        private int attempts;
        private Path temporaryPath;

        private FailingReplacementConfig(IOException failure) {
            // 测试前已有正式配置，父构造器不会进入首次保存路径。
            this.failure = failure;
        }

        @Override void replaceConfigFile(Path temporaryPath, Path targetPath) throws IOException {
            this.temporaryPath = temporaryPath;
            attempts++;
            check(temporaryPath.getParent().equals(targetPath.getParent()), "临时文件不在正式配置同目录");
            Properties complete = new Properties();
            try (var reader = Files.newBufferedReader(temporaryPath, StandardCharsets.UTF_8)) {
                complete.load(reader);
            }
            check(complete.getProperty("record.mouseMoveInterval").equals("90"), "替换发生在写入完成之前");
            throw failure;
        }
    }

    public static final class Parse {
        public static void main(String[] args) throws Exception {
            Path directory = isolatedDirectory("Parse");
            Path file = directory.resolve("config.properties");
            String slash = "\\";
            String valid = "record.startBehavior=CONTROL_BAR\ntimeline.defaultInsertPosition=AFTER\n"
                    + "record.minimizeWindow=false\nrecord.mouseMove=false\nrecord.mouseMoveInterval=37\n"
                    + "lastMacroFile=目录" + slash + slash + "脚本.mr\n"
                    + "unicode=" + slash + "u4F60" + slash + "u597D\n"
                    + "ordinary=left" + slash + "qright\n"
                    + "continued=first" + slash + "\n  second\n";
            Files.writeString(file, valid, StandardCharsets.UTF_8);
            var modified = Files.getLastModifiedTime(file);
            Captured<AppConfig> good = capture(AppConfig::new);
            check(good.error().isBlank(), "合法配置被误报为格式错误");
            check(good.value().getRecordingStartBehavior() == RecordingStartBehavior.CONTROL_BAR
                            && good.value().getTimelineInsertPosition() == TimelineInsertPosition.AFTER
                            && !good.value().isMinimizeOnRecording() && !good.value().isRecordMouseMove()
                            && good.value().getMouseMoveInterval() == 37
                            && good.value().getLastMacroFile().equals("目录\\脚本.mr")
                            && properties(good.value()).getProperty("unicode").equals("你好")
                            && properties(good.value()).getProperty("ordinary").equals("leftqright")
                            && properties(good.value()).getProperty("continued").equals("firstsecond"),
                    "UTF-8、合法转义、续行或既有配置解析改变");
            unchanged(file, valid, modified);

            for (String malformed : new String[]{slash + "uZZZZ", slash + "u12", slash + "uu0041"}) {
                String content = "record.startBehavior=CONTROL_BAR\nminimizeOnPlayStart=true\n"
                        + "lastMacroFile=missing.mr\nrecord.mouseMoveInterval=37\nbad=" + malformed + "\n";
                Files.writeString(file, content, StandardCharsets.UTF_8);
                modified = Files.getLastModifiedTime(file);
                Captured<AppConfig> bad = capture(AppConfig::new);
                check(bad.error().contains("配置文件格式错误") && !bad.error().contains("读取配置文件失败"),
                        "格式错误没有与读取错误区分");
                defaults(bad.value());
                check(properties(bad.value()).isEmpty(), "解析失败后残留了前面读取的部分值");
                unchanged(file, content, modified);
                System.out.print(bad.error());
            }

            String legacy = "record.minimizeWindow=false\nrecord.mouseMove=false\n";
            Files.writeString(file, legacy, StandardCharsets.UTF_8);
            AppConfig old = new AppConfig();
            check(!old.isMinimizeOnRecording() && !old.isRecordMouseMove()
                            && old.getRecordingStartBehavior() == RecordingStartBehavior.MINIMIZE
                            && old.getTimelineInsertPosition() == TimelineInsertPosition.BEFORE,
                    "缺字段的旧配置兼容行为改变");
            Files.writeString(file, "", StandardCharsets.UTF_8);
            modified = Files.getLastModifiedTime(file);
            defaults(new AppConfig());
            unchanged(file, "", modified);
            readFailure(directory);
            System.out.println("通过：合法 UTF-8/转义/续行、非法 Unicode 默认回退、部分值隔离、旧配置/空配置及读取失败保全");
        }
    }

    private static void readFailure(Path directory) throws Exception {
        AppConfig config = new AppConfig();
        Path unreadable = Files.createDirectory(directory.resolve("directory-not-readable-as-file"));
        Path marker = unreadable.resolve("keep.txt");
        Files.writeString(marker, "保留");
        var field = AppConfig.class.getDeclaredField("configFile");
        field.setAccessible(true);
        field.set(config, unreadable.toFile());
        var load = AppConfig.class.getDeclaredMethod("load");
        load.setAccessible(true);
        Captured<Object> result = capture(() -> load.invoke(config));
        check(result.error().contains("读取配置文件失败") && !result.error().contains("配置文件格式错误"),
                "I/O 错误被错误归为格式错误");
        defaults(config);
        check(Files.readString(marker).equals("保留")
                        && Files.size(directory.resolve("config.properties")) == 0,
                "读取失败修改了既有文件");
        System.out.print(result.error());
    }

    private static void defaults(AppConfig config) {
        check(config.getRecordingStartBehavior() == RecordingStartBehavior.MINIMIZE
                        && config.getTimelineInsertPosition() == TimelineInsertPosition.BEFORE
                        && config.isRecordMouseMove() && config.getMouseMoveInterval() == 20
                        && config.isLoadLastMacroOnStartup() && config.getLastMacroFile() == null
                        && !config.isMinimizeOnPlayStart() && config.isRestoreWindowAfterPlayStop(),
                "默认值改变或采用了不完整的配置");
    }

    private static Path isolatedDirectory(String testName) throws Exception {
        Path directory = Path.of(AppConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        check(directory.getFileName().toString().equals("classes")
                        && directory.getParent().getFileName().toString().startsWith(testName + "-"),
                "必须通过隔离入口运行，拒绝访问个人配置");
        return directory;
    }

    private static void unchanged(Path file, String content, java.nio.file.attribute.FileTime modified) throws IOException {
        check(Files.readString(file, StandardCharsets.UTF_8).equals(content)
                        && Files.getLastModifiedTime(file).equals(modified), "加载意外改写了原配置");
    }

    private static Properties properties(AppConfig config) throws Exception {
        var field = AppConfig.class.getDeclaredField("properties");
        field.setAccessible(true);
        return (Properties) field.get(config);
    }

    private static Set<String> entries(Path directory) throws IOException {
        try (var paths = Files.list(directory)) {
            return paths.map(path -> path.getFileName().toString()).collect(Collectors.toSet());
        }
    }

    private static <T> Captured<T> capture(CheckedCall<T> call) throws Exception {
        PrintStream previous = System.err;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream errors = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setErr(errors);
            return new Captured<>(call.run(), output.toString(StandardCharsets.UTF_8));
        } finally {
            System.setErr(previous);
        }
    }

    private interface CheckedCall<T> { T run() throws Exception; }
    private record Captured<T>(T value, String error) { }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
