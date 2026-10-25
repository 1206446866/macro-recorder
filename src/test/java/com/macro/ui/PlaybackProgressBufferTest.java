package com.macro.ui;

import com.macro.player.PlaybackProgress;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.macro.player.PlaybackProgress.Phase.*;
import static org.junit.jupiter.api.Assertions.*;

class PlaybackProgressBufferTest {
    @Test void fastRepeatedNodesUseOneScheduledTaskAndKeepEveryCompletedIdentity() {
        PlaybackProgressBuffer buffer = new PlaybackProgressBuffer();
        assertTrue(buffer.offer(new PlaybackProgress(1, null, LOOP_STARTED)));
        for (int i = 0; i < 10_000; i++) {
            assertFalse(buffer.offer(new PlaybackProgress(1, "a", NODE_STARTED)));
            assertFalse(buffer.offer(new PlaybackProgress(1, "a", NODE_COMPLETED)));
        }
        buffer.offer(new PlaybackProgress(1, "b", NODE_STARTED));
        buffer.offer(new PlaybackProgress(1, "b", NODE_COMPLETED));
        buffer.offer(new PlaybackProgress(1, "c", NODE_STARTED));
        var batch = buffer.drain();
        assertNotNull(batch);
        assertTrue(batch.reset());
        assertEquals(Set.of("a", "b"), batch.completed());
        assertEquals("c", batch.current());
        assertNull(buffer.drain());
        assertTrue(buffer.offer(new PlaybackProgress(1, null, FINISHED)));
        var finished = buffer.drain();
        assertNotNull(finished);
        assertNull(finished.current());
    }

    @Test void nextLoopDiscardsOldCompletionAndFinishRetainsThisLoopsResult() {
        PlaybackProgressBuffer buffer = new PlaybackProgressBuffer();
        buffer.offer(new PlaybackProgress(1, null, LOOP_STARTED));
        buffer.offer(new PlaybackProgress(1, "a", NODE_COMPLETED));
        buffer.offer(new PlaybackProgress(2, null, LOOP_STARTED));
        buffer.offer(new PlaybackProgress(2, null, HEAD_WAIT_STARTED));
        buffer.offer(new PlaybackProgress(2, null, HEAD_WAIT_COMPLETED));
        buffer.offer(new PlaybackProgress(2, "b", NODE_STARTED));
        buffer.offer(new PlaybackProgress(2, "b", NODE_COMPLETED));
        buffer.offer(new PlaybackProgress(2, null, FINISHED));
        var batch = buffer.drain();
        assertNotNull(batch);
        assertEquals(2, batch.loop());
        assertTrue(batch.reset());
        assertEquals(Set.of("b"), batch.completed());
        assertNull(batch.current());
        assertTrue(batch.headCompleted());
        assertFalse(batch.headCurrent());
    }

    @Test void clearCanReuseAlreadyQueuedFlushWithoutShowingOldProgress() {
        PlaybackProgressBuffer buffer = new PlaybackProgressBuffer();
        assertTrue(buffer.offer(new PlaybackProgress(1, null, LOOP_STARTED)));
        buffer.offer(new PlaybackProgress(1, "old", NODE_COMPLETED));
        buffer.clear();
        assertFalse(buffer.offer(new PlaybackProgress(1, null, LOOP_STARTED)));
        buffer.offer(new PlaybackProgress(1, "new", NODE_STARTED));
        var batch = buffer.drain();
        assertNotNull(batch);
        assertTrue(batch.reset());
        assertTrue(batch.completed().isEmpty());
        assertEquals("new", batch.current());
        assertTrue(buffer.offer(new PlaybackProgress(1, "new", NODE_COMPLETED)));
    }
}
