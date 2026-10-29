package com.macro.diagnostics;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.macro.diagnostics.ErrorReportWriterTest.*;
import static org.junit.jupiter.api.Assertions.*;

class UncaughtErrorHandlerTest {
    @Test void logsBeforeNotificationAndRetainsOriginalThreadAndPreviousHandler() throws Exception {
        Path root = directory();
        AtomicInteger previous = new AtomicInteger(), notified = new AtomicInteger();
        IOException failure = new IOException("后台异常");
        Thread origin = new Thread("识别后台线程");
        UncaughtErrorHandler handler = new UncaughtErrorHandler(writer(root), (message, caught) -> {
            assertSame(failure, caught); assertTrue(message.contains(origin.getName()));
            try (var files = Files.list(root.resolve("log"))) {
                Path report = files.findFirst().orElseThrow();
                assertTrue(Files.readString(report).contains("记录线程：识别后台线程"));
            } catch (IOException exception) { throw new AssertionError(exception); }
            notified.incrementAndGet();
        }, (thread, caught) -> { assertSame(origin, thread); assertSame(failure, caught); previous.incrementAndGet(); });
        handler.uncaughtException(origin, failure);
        assertEquals(1, previous.get()); assertEquals(1, notified.get());
        try (var files = Files.list(root.resolve("log"))) { assertEquals(1, files.count()); }
    }

    @Test void failedWriterAndFailedPreviousHandlerStillNotifyOriginalException() throws Exception {
        Path root = directory(); Files.writeString(root.resolve("log"), "阻止写入");
        IOException original = new IOException("原异常"); AtomicReference<Throwable> notified = new AtomicReference<>();
        UncaughtErrorHandler handler = new UncaughtErrorHandler(writer(root), (message, failure) -> notified.set(failure),
                (thread, failure) -> { throw new IllegalStateException("旧处理器失效"); });
        captureError(() -> assertDoesNotThrow(() -> handler.uncaughtException(Thread.currentThread(), original)));
        assertSame(original, notified.get());
    }

    @Test void notificationFailureIsLoggedOnceWithoutRecursiveNotification() throws Exception {
        Path root = directory(); AtomicInteger calls = new AtomicInteger();
        UncaughtErrorHandler handler = new UncaughtErrorHandler(writer(root), (message, failure) -> {
            calls.incrementAndGet(); throw new IllegalStateException("通知失败");
        }, (thread, failure) -> { });
        handler.uncaughtException(Thread.currentThread(), new IOException("原始异常"));
        assertEquals(1, calls.get());
        try (var files = Files.list(root.resolve("log"))) {
            var reports = files.map(path -> {
                try { return Files.readString(path); } catch (IOException exception) { throw new AssertionError(exception); }
            }).toList();
            assertEquals(2, reports.size());
            assertTrue(reports.stream().anyMatch(text -> text.contains("IOException: 原始异常")));
            assertTrue(reports.stream().anyMatch(text -> text.contains("IllegalStateException: 通知失败")));
        }
    }

    @Test void previousHandlerReentryCannotLoopAndDoesNotBlockLaterReports() throws Exception {
        Path root = directory(); AtomicReference<UncaughtErrorHandler> reference = new AtomicReference<>();
        AtomicInteger notified = new AtomicInteger();
        UncaughtErrorHandler handler = new UncaughtErrorHandler(writer(root), (message, failure) -> notified.incrementAndGet(),
                (thread, failure) -> reference.get().uncaughtException(thread, failure));
        reference.set(handler);
        handler.uncaughtException(Thread.currentThread(), new IOException("first"));
        handler.uncaughtException(Thread.currentThread(), new IOException("second"));
        assertEquals(2, notified.get());
        try (var files = Files.list(root.resolve("log"))) { assertEquals(2, files.count()); }
    }
}
