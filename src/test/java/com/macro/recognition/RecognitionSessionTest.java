package com.macro.recognition;

import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RecognitionSessionTest {
    @Test void threeSearchStagesShareOneSamplerAndReleaseItAfterAllWork() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        AtomicReference<RecognitionSession> sampling = new AtomicReference<>();
        var f = new AdaptiveImageSearchTest.Fixture(() -> {
            starts.incrementAndGet(); var session = new RecognitionSession(); sampling.set(session); return session;
        });
        f.matcher.rememberCapture(f.templatePath, new Rectangle(200, 120, 20, 16));
        assertFalse(f.match().isFound());
        assertEquals(3, f.requests().size());
        assertEquals(1, starts.get(), "局扫及两个回退任务应共用一次采样");
        assertThrows(IllegalStateException.class, () -> sampling.get().retain());
    }

    @Test void localHitStillCreatesJustOneSampler() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        var f = new AdaptiveImageSearchTest.Fixture(() -> { starts.incrementAndGet(); return new RecognitionSession(); });
        f.matcher.rememberCapture(f.templatePath, new Rectangle(200, 120, 20, 16)); f.place(200, 120);
        assertTrue(f.match().isFound()); assertEquals(1, starts.get()); assertEquals(1, f.requests().size());
    }

    @Test void ownerAndLeaseCloseAreIdempotentAndLastLeaseEndsSampling() {
        var session = new RecognitionSession();
        var lease = session.retain(); session.close(); session.close();
        assertTrue(session.heapDeltaBytes() >= 0);
        lease.close(); lease.close();
        assertThrows(IllegalStateException.class, session::retain);
    }
}
