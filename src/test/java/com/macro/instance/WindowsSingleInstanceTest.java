package com.macro.instance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 真实 Windows 互斥量及消息窗口、独立 JVM；不创建应用窗口、不读配置、不发送输入。 */
@EnabledOnOs(OS.WINDOWS)
class WindowsSingleInstanceTest {
    @TempDir Path temporary;

    @Test
    void duplicateProcessNotifiesOwnerWithoutAcquiring() throws Exception {
        String identity = identity();
        CountDownLatch notified = new CountDownLatch(1);
        try (WindowsSingleInstance owner = WindowsSingleInstance.acquire(identity, notified::countDown)) {
            assertNotNull(owner);
            Process duplicate = launch(identity, "duplicate", temporary.resolve("duplicate.log"));
            try {
                assertTrue(duplicate.waitFor(10, TimeUnit.SECONDS));
                assertEquals(0, duplicate.exitValue(), log("duplicate.log"));
                assertTrue(notified.await(3, TimeUnit.SECONDS), "跨进程通知未送达");
                assertTrue(log("duplicate.log").contains("SECONDARY"));
            } finally {
                terminate(duplicate);
            }
        }
    }

    @Test
    void closeIsIdempotentAndAllowsNewOwner() throws Exception {
        String identity = identity();
        WindowsSingleInstance first = WindowsSingleInstance.acquire(identity, () -> {});
        assertNotNull(first);
        first.close();
        first.close();
        try (WindowsSingleInstance next = WindowsSingleInstance.acquire(identity, () -> {})) {
            assertNotNull(next, "关闭后锁或窗口类未释放");
        }
    }

    @Test
    void callbackFailureDoesNotReleaseProtectionOrStopNotifications() throws Exception {
        String identity = identity();
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch recovered = new CountDownLatch(1);
        try (WindowsSingleInstance owner = WindowsSingleInstance.acquire(identity, () -> {
            if (requests.incrementAndGet() == 1) throw new IllegalStateException("预期的测试回调异常");
            recovered.countDown();
        })) {
            assertNotNull(owner);
            assertNull(WindowsSingleInstance.acquire(identity, () -> fail("重复实例不能成为主实例")));
            assertNull(WindowsSingleInstance.acquire(identity, () -> fail("回调异常导致锁丢失")));
            assertTrue(recovered.await(3, TimeUnit.SECONDS));
            assertEquals(2, requests.get());
        }
        try (WindowsSingleInstance next = WindowsSingleInstance.acquire(identity, () -> {})) {
            assertNotNull(next, "重复启动检查残留的错误码不能误判新实例");
        }
    }

    @Test
    void crashedProcessLeavesNoStaleLock() throws Exception {
        String identity = identity();
        Path output = temporary.resolve("owner.log");
        Process owner = launch(identity, "owner", output);
        try {
            awaitText(output, "PRIMARY");
            owner.destroyForcibly();
            assertTrue(owner.waitFor(10, TimeUnit.SECONDS));
            try (WindowsSingleInstance replacement = WindowsSingleInstance.acquire(identity, () -> {})) {
                assertNotNull(replacement, "异常退出后仍被残留锁阻止");
            }
        } finally {
            terminate(owner);
        }
    }

    @Test
    void simultaneousProcessesElectOneOwnerAndDeliverActivation() throws Exception {
        String identity = identity();
        Path firstOutput = temporary.resolve("first.log");
        Path secondOutput = temporary.resolve("second.log");
        Process first = launch(identity, "race", firstOutput);
        Process second = launch(identity, "race", secondOutput);
        try {
            awaitText(firstOutput, "WAITING");
            awaitText(secondOutput, "WAITING");
            first.getOutputStream().write(1);
            first.getOutputStream().flush();
            second.getOutputStream().write(1);
            second.getOutputStream().flush();
            awaitEitherSecondary(firstOutput, secondOutput);
            String firstLog = Files.readString(firstOutput);
            Process primary = firstLog.contains("SECONDARY") ? second : first;
            Process secondary = primary == first ? second : first;
            Path primaryOutput = primary == first ? firstOutput : secondOutput;
            assertTrue(secondary.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, secondary.exitValue());
            awaitText(primaryOutput, "ACTIVATED");
            assertTrue(primary.isAlive());
            primary.getOutputStream().write(1);
            primary.getOutputStream().flush();
            assertTrue(primary.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, primary.exitValue(), Files.readString(primaryOutput));
            try (WindowsSingleInstance replacement = WindowsSingleInstance.acquire(identity, () -> {})) {
                assertNotNull(replacement, "正常退出后应能重新启动");
            }
        } finally {
            terminate(first);
            terminate(second);
        }
    }

    private Process launch(String identity, String mode, Path output) throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        return new ProcessBuilder(java.toString(), "-Dfile.encoding=UTF-8", "-cp", classpath,
                Probe.class.getName(), identity, mode)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
    }

    private static String identity() {
        return UUID.randomUUID().toString();
    }

    private String log(String name) throws Exception {
        return Files.readString(temporary.resolve(name));
    }

    private static void awaitText(Path file, String text) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            if (Files.exists(file) && Files.readString(file).contains(text)) return;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        fail("未收到 " + text + "：" + (Files.exists(file) ? Files.readString(file) : file));
    }

    private static void awaitEitherSecondary(Path first, Path second) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            if (Files.readString(first).contains("SECONDARY") || Files.readString(second).contains("SECONDARY")) return;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        fail("并发启动未产生唯一主实例：" + Files.readString(first) + Files.readString(second));
    }

    private static void terminate(Process process) throws Exception {
        if (process.isAlive()) {
            process.destroyForcibly();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "测试进程未能退出");
        }
    }

    /** 子进程仅建立原生单实例保护，不启动 MacroApplication 或 Swing。 */
    public static final class Probe {
        public static void main(String[] args) throws Exception {
            if (args[1].equals("race")) {
                System.out.println("WAITING");
                System.in.read();
            }
            try (WindowsSingleInstance owner = WindowsSingleInstance.acquire(args[0],
                    () -> System.out.println("ACTIVATED"))) {
                System.out.println(owner == null ? "SECONDARY" : "PRIMARY");
                if (args[1].equals("duplicate") && owner != null) throw new AssertionError("重复启动获得了锁");
                if (owner != null) System.in.read();
            }
        }
    }
}
