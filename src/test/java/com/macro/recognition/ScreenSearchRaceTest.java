package com.macro.recognition;

import org.junit.jupiter.api.*;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 闩锁模拟不能立即取消的原生调用，不采集桌面，不依赖机器运算快慢。 */
@Timeout(10)
class ScreenSearchRaceTest {
    @BeforeEach void waitForPreviousWork() throws Exception { AdaptiveImageSearchTest.awaitRaceCleanup(); }
    @AfterEach void verifyCleanup() throws Exception { AdaptiveImageSearchTest.awaitRaceCleanup(); }

    @Test void expandedHitReturnsBeforeGlobalCleanupAndBusyPoolNeverQueuesAnotherRace() throws Exception {
        CountDownLatch globalEntered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean loserSawStop = new AtomicBoolean(), loserReturned = new AtomicBoolean();
        RecognitionSession session = new RecognitionSession();
        try {
            var winner = ScreenSearchRace.trySearch(token -> {
                assertTrue(globalEntered.await(3, TimeUnit.SECONDS)); return outcome("EXPANDED", true, 1);
            }, token -> {
                globalEntered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS));
                loserSawStop.set(token.getAsBoolean()); loserReturned.set(true);
                return outcome("GLOBAL", true, 1);
            }, () -> false, session);
            assertEquals("EXPANDED", winner.stage());
            assertFalse(loserReturned.get(), "不能为了等待原生调用收尾而拖住已命中的结果");
            session.close();
            assertEquals(1, references(session), "后台任务仍持有采样引用");
            try (RecognitionSession next = new RecognitionSession()) {
                assertNull(ScreenSearchRace.trySearch(token -> { fail("不得排队"); return null; },
                        token -> { fail("不得排队"); return null; }, () -> false, next));
            }
        } finally { release.countDown(); session.close(); }
        AdaptiveImageSearchTest.awaitRaceCleanup();
        assertTrue(loserSawStop.get()); assertEquals(0, references(session));
    }

    @Test void globalHitCanWinWithoutWaitingForExpandedNativeCall() throws Exception {
        CountDownLatch expandedEntered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean loserSawStop = new AtomicBoolean();
        try (RecognitionSession session = new RecognitionSession()) {
            try {
                var winner = ScreenSearchRace.trySearch(token -> {
                    expandedEntered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS));
                    loserSawStop.set(token.getAsBoolean()); return outcome("EXPANDED", false, .4);
                }, token -> {
                    assertTrue(expandedEntered.await(3, TimeUnit.SECONDS)); return outcome("GLOBAL", true, 1);
                }, () -> false, session);
                assertEquals("GLOBAL", winner.stage());
            } finally { release.countDown(); }
        }
        AdaptiveImageSearchTest.awaitRaceCleanup(); assertTrue(loserSawStop.get());
    }

    @Test void earlyMissDoesNotCancelOtherSearchAndBothMissKeepGlobalCandidate() throws Exception {
        CountDownLatch globalEntered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try (RecognitionSession session = new RecognitionSession()) {
            try {
                Future<ScreenSearchRace.Outcome> pending = caller.submit(() -> ScreenSearchRace.trySearch(
                        token -> outcome("EXPANDED", false, .8), token -> {
                            globalEntered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS));
                            assertFalse(token.getAsBoolean()); return outcome("GLOBAL", false, .4);
                        }, () -> false, session));
                assertTrue(globalEntered.await(3, TimeUnit.SECONDS));
                assertFalse(pending.isDone()); release.countDown();
                var result = pending.get(3, TimeUnit.SECONDS);
                assertEquals("GLOBAL", result.stage()); assertEquals(.4, result.report().bestCandidate().similarity());
            } finally { release.countDown(); caller.shutdownNow(); }
        }
    }

    @Test void oneFailureDoesNotHideOtherHitButPropagatesWhenNothingMatches() throws Exception {
        try (RecognitionSession session = new RecognitionSession()) {
            assertTrue(ScreenSearchRace.trySearch(token -> { throw new IOException("模拟扩大搜索失败"); },
                    token -> outcome("GLOBAL", true, 1), () -> false, session).found());
        }
        AdaptiveImageSearchTest.awaitRaceCleanup();
        try (RecognitionSession session = new RecognitionSession()) {
            IOException failure = assertThrows(IOException.class, () -> ScreenSearchRace.trySearch(
                    token -> { throw new IOException("模拟扩大搜索失败"); },
                    token -> outcome("GLOBAL", false, .2), () -> false, session));
            assertEquals("模拟扩大搜索失败", failure.getMessage());
        }
    }

    @Test void cancellationSignalsBothTasksAndReturnsWithoutWaitingForNativeCalls() throws Exception {
        CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean(); AtomicInteger sawStop = new AtomicInteger();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try (RecognitionSession session = new RecognitionSession()) {
            ScreenSearchRace.Search blocked = token -> {
                entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS));
                if (token.getAsBoolean()) sawStop.incrementAndGet(); return outcome("GLOBAL", false, .1);
            };
            try {
                Future<?> pending = caller.submit(() -> ScreenSearchRace.trySearch(blocked, blocked, stop::get, session));
                assertTrue(entered.await(3, TimeUnit.SECONDS)); stop.set(true);
                assertInstanceOf(CancellationException.class, assertThrows(ExecutionException.class,
                        () -> pending.get(3, TimeUnit.SECONDS)).getCause());
                assertEquals(1L, release.getCount(), "返回时模拟原生调用仍未释放");
            } finally { release.countDown(); caller.shutdownNow(); }
        }
        AdaptiveImageSearchTest.awaitRaceCleanup(); assertEquals(2, sawStop.get());
    }

    @Test void interruptWhileWaitingPreservesCallerFlag() throws Exception {
        CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
        AtomicReference<Thread> callerThread = new AtomicReference<>();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try (RecognitionSession session = new RecognitionSession()) {
            ScreenSearchRace.Search blocked = token -> {
                entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); return outcome("GLOBAL", false, 0);
            };
            try {
                Future<Boolean> pending = caller.submit(() -> {
                    callerThread.set(Thread.currentThread());
                    assertThrows(CancellationException.class, () -> ScreenSearchRace.trySearch(blocked, blocked, () -> false, session));
                    return Thread.currentThread().isInterrupted();
                });
                assertTrue(entered.await(3, TimeUnit.SECONDS)); callerThread.get().interrupt();
                assertTrue(pending.get(3, TimeUnit.SECONDS));
            } finally { release.countDown(); caller.shutdownNow(); }
        }
    }

    private static int references(RecognitionSession session) throws Exception {
        Field field = RecognitionSession.class.getDeclaredField("references"); field.setAccessible(true);
        return ((AtomicInteger) field.get(session)).get();
    }

    static ScreenSearchRace.Outcome outcome(String stage, boolean found, double similarity) {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        var result = new ScreenRecognitionService.RecognitionResult(found, similarity,
                new Rectangle(0, 0, 1, 1), new Rectangle(0, 0, 1, 1), image, "模拟屏幕");
        return new ScreenSearchRace.Outcome(stage, new ScreenRecognitionService.RecognitionReport(
                found ? List.of(result) : List.of(), result, false, 0, 0, 0, 0, 0));
    }
}
