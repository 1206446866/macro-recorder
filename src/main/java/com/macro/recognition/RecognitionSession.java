package com.macro.recognition;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 一次识别共用一份内存采样；返回结果后仍在收尾的原生任务持有自己的引用。 */
final class RecognitionSession implements AutoCloseable {
    private final ProcessMemoryMonitor memory = ProcessMemoryMonitor.start();
    private final long heapBefore = usedHeap();
    private final AtomicInteger references = new AtomicInteger(1);
    private final AtomicBoolean ownerClosed = new AtomicBoolean();

    Lease retain() {
        int count;
        do {
            count = references.get();
            if (count <= 0) throw new IllegalStateException("识别采样已关闭");
        } while (!references.compareAndSet(count, count + 1));
        return new Lease();
    }

    long heapDeltaBytes() { return Math.max(0, usedHeap() - heapBefore); }
    long peakIncreaseBytes() { return memory.peakIncreaseBytes(); }

    @Override public void close() {
        if (ownerClosed.compareAndSet(false, true)) release();
    }

    private void release() {
        if (references.decrementAndGet() == 0) memory.close();
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    final class Lease implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();
        @Override public void close() {
            if (closed.compareAndSet(false, true)) release();
        }
    }
}
