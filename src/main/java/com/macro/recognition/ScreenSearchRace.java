package com.macro.recognition;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** 局扫失败后竞速扩大搜索和全扫；任务总数有界，命中即可返回，原生调用自行收尾。 */
final class ScreenSearchRace {
    private static final Semaphore SLOTS = new Semaphore(2);
    private static final AtomicInteger THREAD_IDS = new AtomicInteger();
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(2, 2, 10, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2), task -> {
                Thread thread = new Thread(task, "opencv-search-race-" + THREAD_IDS.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
    static { WORKERS.allowCoreThreadTimeOut(true); }

    record Outcome(String stage, ScreenRecognitionService.RecognitionReport report) {
        boolean found() { return !report.matches().isEmpty(); }
    }
    private record Completed(int index, Outcome outcome, Throwable failure) { }

    @FunctionalInterface interface Search {
        Outcome run(BooleanSupplier cancelled) throws Exception;
    }

    /** 后台尚有前次原生任务时返回 null，调用方保持串行回退，不排队积压。 */
    static Outcome trySearch(Search expanded, Search global, BooleanSupplier stop, RecognitionSession session)
            throws Exception {
        checkCancelled(stop);
        if (!SLOTS.tryAcquire(2)) return null;
        AtomicBoolean cancelled = new AtomicBoolean();
        BooleanSupplier token = () -> cancelled.get() || stop.getAsBoolean();
        CompletionService<Completed> completion = new ExecutorCompletionService<>(WORKERS);
        Search[] searches = {expanded, global};
        Outcome[] results = new Outcome[2];
        Throwable[] failures = new Throwable[2];
        int submitted = 0;
        try {
            for (int index = 0; index < 2; index++) {
                int taskIndex = index;
                RecognitionSession.Lease lease = session.retain();
                try {
                    completion.submit(() -> {
                        try (lease) {
                            checkCancelled(token);
                            return new Completed(taskIndex, searches[taskIndex].run(token), null);
                        } catch (Throwable error) {
                            return new Completed(taskIndex, null, error);
                        } finally {
                            SLOTS.release();
                        }
                    });
                    submitted++;
                } catch (RuntimeException | Error error) {
                    lease.close();
                    throw error;
                }
            }
            for (int received = 0; received < 2;) {
                checkCancelled(stop);
                Future<Completed> ready;
                try {
                    ready = completion.poll(10, TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("图像识别已取消");
                }
                if (ready == null) continue;
                Completed value = ready.get();
                received++;
                failures[value.index()] = value.failure();
                if (value.outcome() == null) continue;
                results[value.index()] = value.outcome();
                if (value.outcome().found()) return value.outcome();
            }
            checkCancelled(stop);
            for (Throwable failure : failures) {
                if (failure instanceof Exception exception) throw exception;
                if (failure instanceof Error error) throw error;
            }
            return results[1]; // 两边都未命中时，保留全扫的最佳候选与未命中结论。
        } finally {
            cancelled.set(true);
            // 不取消尚未启动的 Future，确保每个任务都会进入 finally 释放配额和采样引用。
            SLOTS.release(2 - submitted);
        }
    }

    private static void checkCancelled(BooleanSupplier stop) {
        if (Thread.currentThread().isInterrupted() || stop.getAsBoolean()) {
            throw new CancellationException("图像识别已取消");
        }
    }
}
