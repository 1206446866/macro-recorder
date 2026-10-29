package com.macro.diagnostics;

import com.macro.constant.ApplicationInfo;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ErrorReportWriterTest {
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-10-28T12:34:56.123Z"), ZoneId.of("Asia/Shanghai"));

    @Test void utf8ReportPreservesVersionTimeThreadNestedAndSuppressedErrors() throws Exception {
        Path root = directory().resolve("中文 应用目录");
        Throwable failure = new IllegalStateException("读取失败", new IOException("底层文件不存在"));
        failure.addSuppressed(new IOException("清理也失败"));
        Path report = writer(root).write("加载宏失败：脚本.mr", failure, Thread.currentThread());
        assertNotNull(report);
        assertEquals(root.resolve("log"), report.getParent());
        assertTrue(report.getFileName().toString().startsWith("error-20261028-203456-123-"));
        String text = Files.readString(report, StandardCharsets.UTF_8);
        for (String expected : new String[]{ApplicationInfo.APP_NAME, ApplicationInfo.APP_VERSION,
                "2026-10-28T20:34:56.123+08:00", Thread.currentThread().getName(), root.toString(),
                "加载宏失败：脚本.mr", "IllegalStateException: 读取失败", "Caused by: java.io.IOException: 底层文件不存在",
                "Suppressed: java.io.IOException: 清理也失败", "ErrorReportWriterTest", "设备样本"})
            assertTrue(text.contains(expected), expected + " 未写入报告");
    }

    @Test void messageOnlyErrorLabelsCallSiteRatherThanInventingAnException() throws Exception {
        String report = Files.readString(writer(directory()).write("目标窗口不存在", null, Thread.currentThread()));
        assertTrue(report.contains("未提供异常对象，以下仅为报告调用位置"));
        assertTrue(report.contains("messageOnlyErrorLabelsCallSite"));
        assertTrue(report.contains("目标窗口不存在"));
    }

    @Test void sameMillisecondConcurrentReportsNeverOverwriteEachOtherOrExistingFiles() throws Exception {
        Path root = directory();
        Path log = Files.createDirectory(root.resolve("log"));
        Path existing = log.resolve("error-20261028-203456-123-kept.log");
        Files.writeString(existing, "保留以前的报告");
        ErrorReportWriter writer = writer(root);
        var pool = Executors.newFixedThreadPool(6);
        ArrayList<Future<Path>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 48; i++) {
                int number = i;
                results.add(pool.submit(() -> writer.write("并发错误-" + number,
                        new IOException("cause-" + number), Thread.currentThread())));
            }
            HashSet<Path> unique = new HashSet<>();
            for (int i = 0; i < results.size(); i++) {
                Path file = results.get(i).get(10, TimeUnit.SECONDS);
                assertNotNull(file); assertTrue(unique.add(file));
                assertTrue(Files.readString(file).contains("错误描述：并发错误-" + i + "\n"));
                assertTrue(Files.readString(file).contains("IOException: cause-" + i));
            }
            try (var paths = Files.list(log)) { assertEquals(49, paths.count()); }
            assertEquals("保留以前的报告", Files.readString(existing));
        } finally { pool.shutdownNow(); }
    }

    @Test void unavailableDeviceLibraryDoesNotLoseOriginalError() throws Exception {
        Path root = directory();
        ErrorReportWriter writer = new ErrorReportWriter(() -> root,
                () -> { throw new UnsatisfiedLinkError("没有设备接口"); }, FIXED);
        String report = Files.readString(writer.write("原始操作失败", new IOException("真实原因"), Thread.currentThread()));
        assertTrue(report.contains("IOException: 真实原因"));
        assertTrue(report.contains("环境信息采集失败：java.lang.UnsatisfiedLinkError: 没有设备接口"));
    }

    @Test void blockedLogDirectoryFallsBackToStderrWithoutReplacingOriginalFailure() throws Exception {
        Path root = directory();
        Path blocker = root.resolve("log");
        Files.writeString(blocker, "保留此文件，不能删除或覆盖");
        String error = captureError(() -> assertNull(writer(root).write("导入失败", new IOException("原始异常"), Thread.currentThread())));
        assertTrue(error.contains("错误报告写入失败")); assertTrue(error.contains("IOException: 原始异常"));
        assertEquals("保留此文件，不能删除或覆盖", Files.readString(blocker));
    }

    @Test void rootResolutionFailureIsContainedAndWriterCanRecoverOnSameThread() throws Exception {
        Path root = directory();
        AtomicBoolean blocked = new AtomicBoolean(true);
        ErrorReportWriter writer = new ErrorReportWriter(() -> {
            if (blocked.getAndSet(false)) throw new SecurityException("目录访问被拒绝");
            return root;
        }, () -> "设备", FIXED);
        String error = captureError(() -> assertNull(writer.write("首次报错", new IOException("原始错误"), Thread.currentThread())));
        assertTrue(error.contains("目录访问被拒绝"));
        assertNotNull(writer.write("后续报错", null, Thread.currentThread()));
    }

    @Test void recursiveCollectorCannotGenerateReportStorm() throws Exception {
        Path root = directory();
        AtomicReference<ErrorReportWriter> reference = new AtomicReference<>();
        ErrorReportWriter writer = new ErrorReportWriter(() -> root, () -> {
            assertNull(reference.get().write("递归", null, Thread.currentThread()));
            return "外层设备信息";
        }, FIXED);
        reference.set(writer);
        assertNotNull(writer.write("外层", new IOException("原因"), Thread.currentThread()));
        try (var files = Files.list(root.resolve("log"))) { assertEquals(1, files.count()); }
    }

    @Test void reportNeverCreatesConfigOrTouchesPersonalData() throws Exception {
        Path root = directory();
        Path config = root.resolve("config.properties");
        Path mr = Files.createDirectories(root.resolve("save")).resolve("个人.mr");
        Path screen = Files.createDirectories(root.resolve("ScreenCapture")).resolve("保留.png");
        for (Path path : new Path[]{config, mr, screen}) Files.writeString(path, "保留内容：" + path.getFileName());
        assertNotNull(writer(root).write("写入报告", new IOException("失败"), Thread.currentThread()));
        for (Path path : new Path[]{config, mr, screen}) assertEquals("保留内容：" + path.getFileName(), Files.readString(path));
        Path empty = directory();
        assertNotNull(writer(empty).write("没有配置也可报告", null, Thread.currentThread()));
        try (var files = Files.list(empty)) { assertEquals(java.util.List.of("log"), files.map(p -> p.getFileName().toString()).toList()); }
    }

    static Path directory() throws IOException {
        Path parent = Path.of(System.getProperty("macro.test.sandboxRoot", "target/test-sandboxes")).toAbsolutePath();
        Files.createDirectories(parent);
        return Files.createTempDirectory(parent, "error-report-");
    }

    static ErrorReportWriter writer(Path root) { return new ErrorReportWriter(() -> root, () -> "设备样本\n", FIXED); }

    static String captureError(Runnable task) throws Exception {
        PrintStream original = System.err;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setErr(capture); task.run();
        } finally { System.setErr(original); }
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
