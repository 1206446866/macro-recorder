package com.macro.testing;

import com.macro.config.AppConfig;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** 隔离运行已审查的旧式测试，避免应用目录解析、原生状态或后台线程影响其他检查。 */
final class LegacyMainRunner {
    private static final long TIMEOUT_SECONDS = 60;

    private LegacyMainRunner() {
    }

    /** 生产类副本、工作目录、临时文件及日志都属于本次新建的测试目录。 */
    static Path run(Class<?> testClass, boolean headless) throws Exception {
        Path productionClasses = Path.of(AppConfig.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toRealPath();
        Path root = Path.of(System.getProperty("macro.test.sandboxRoot", "target/test-sandboxes"))
                .toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path directory = Files.createTempDirectory(root, testClass.getSimpleName() + "-");
        Path classes = directory.resolve("classes");
        copyProductionClasses(productionClasses, classes);
        Files.createDirectories(directory.resolve("target"));
        Path temporaryFiles = Files.createDirectory(directory.resolve("tmp"));
        Path log = directory.resolve("output.log");

        String javaName = File.separatorChar == '\\' ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", javaName);
        Process process = new ProcessBuilder(
                java.toString(), "-ea", "-Dfile.encoding=UTF-8",
                "-Djava.awt.headless=" + headless,
                "-Djava.io.tmpdir=" + temporaryFiles,
                "-cp", isolatedClasspath(productionClasses, classes),
                Launcher.class.getName(), testClass.getName())
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("旧式测试超时：" + testClass.getName() + "，日志：" + log);
            }
            if (process.exitValue() != 0) {
                throw new AssertionError("旧式测试失败：" + testClass.getName()
                        + "，退出码：" + process.exitValue() + "，日志：" + log
                        + System.lineSeparator() + logTail(log));
            }
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
        System.out.println("独立检查通过：" + testClass.getName() + "，日志：" + log);
        return directory;
    }

    /** 仅换掉生产类目录；保留 Maven 解析出的测试类、资源及全部依赖的绝对路径。 */
    private static String isolatedClasspath(Path productionClasses, Path isolatedClasses) throws IOException {
        List<String> entries = new ArrayList<>();
        entries.add(isolatedClasses.toString());
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        for (String entry : classpath.split(Pattern.quote(File.pathSeparator))) {
            if (entry.isBlank()) {
                continue;
            }
            Path path = Path.of(entry).toAbsolutePath().normalize();
            if (!path.toRealPath().equals(productionClasses)) {
                entries.add(path.toString());
            }
        }
        return String.join(File.pathSeparator, entries);
    }

    /** AppConfig 依据类来源确定应用目录，仅修改工作目录无法隔离个人配置。 */
    private static void copyProductionClasses(Path source, Path destination) throws IOException {
        if (!Files.isDirectory(source)) {
            throw new IOException("自动测试需要编译后的生产类目录：" + source);
        }
        try (var files = Files.walk(source)) {
            for (Path file : files.toList()) {
                Path relative = source.relativize(file);
                // 不继承构建目录中可能残留的运行配置，使用隔离目录内的新配置。
                if (relative.equals(Path.of("config.properties"))) {
                    continue;
                }
                Path target = destination.resolve(relative);
                if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(file, target);
                }
            }
        }
    }

    /** 失败信息只读取日志末尾，完整输出仍保存在隔离目录中。 */
    private static String logTail(Path log) throws IOException {
        try (var channel = Files.newByteChannel(log)) {
            channel.position(Math.max(0, channel.size() - 16_384));
            var buffer = java.nio.ByteBuffer.allocate(16_384);
            channel.read(buffer);
            buffer.flip();
            return StandardCharsets.UTF_8.decode(buffer).toString();
        }
    }

    /** 子进程在调用旧入口前验证类来源；校验失败时不得构造 AppConfig。 */
    public static final class Launcher {
        public static void main(String[] args) throws Throwable {
            Path expected = Path.of("classes").toRealPath();
            Path actual = Path.of(AppConfig.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toRealPath();
            if (!expected.equals(actual)) {
                throw new AssertionError("生产类未隔离，拒绝运行：" + actual);
            }
            try {
                Class.forName(args[0]).getMethod("main", String[].class)
                        .invoke(null, (Object) new String[0]);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }
    }
}
