package com.macro.flow;

import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.macro.model.MacroAction;
import com.macro.player.PlayerState;
import com.macro.player.StopKeyListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** 计数 Robot、可控匹配器和合成 F12，不向桌面发送输入。 */
@EnabledOnOs(OS.WINDOWS)
@Timeout(10)
class ScaleMatchingPlaybackTest {
    @Test
    void f12CancelsMatchingNormallyWithoutExecutingTheNextAction() throws Exception {
        var fixture = new ImageJumpPlaybackTest.Fixture();
        CountDownLatch entered = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            ImageMatcher matcher = new ImageMatcher() {
                @Override public ImageMatchResult match(MacroNode node, FlowContext context) {
                    throw new AssertionError("播放器应传入停止请求");
                }
                @Override public ImageMatchResult match(MacroNode node, FlowContext context, BooleanSupplier stopped) {
                    entered.countDown();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    while (!stopped.getAsBoolean() && System.nanoTime() < deadline) Thread.onSpinWait();
                    assertTrue(stopped.getAsBoolean());
                    throw new CancellationException("模拟两轮缩放之间取消");
                }
            };
            Future<FlowContext> task = worker.submit(() -> fixture.player.play(flow(), 1, matcher));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            new StopKeyListener(fixture.player).nativeKeyPressed(new NativeKeyEvent(NativeKeyEvent.NATIVE_KEY_PRESSED,
                    0, NativeKeyEvent.VC_F12, NativeKeyEvent.VC_F12, NativeKeyEvent.CHAR_UNDEFINED));
            FlowContext context = task.get(3, TimeUnit.SECONDS);
            assertNull(context.getLastImageMatch());
            assertTrue(fixture.robot.keys.isEmpty());
            assertEquals(PlayerState.IDLE, fixture.player.getState());
        } finally {
            fixture.player.stop();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void unexpectedCancellationStillPropagatesAsAFailure() throws Exception {
        var fixture = new ImageJumpPlaybackTest.Fixture();
        assertThrows(CancellationException.class, () -> fixture.player.play(flow(), 1,
                (node, context) -> { throw new CancellationException("非停止原因"); }));
        assertEquals(PlayerState.IDLE, fixture.player.getState());
        assertTrue(fixture.robot.keys.isEmpty());
    }

    @Test
    void recognitionTimeDoesNotConsumeTheFollowingActionDelay() throws Exception {
        var fixture = new ImageJumpPlaybackTest.Fixture();
        AtomicLong recognizedAt = new AtomicLong();
        fixture.player.play(flow(), 1, (node, context) -> {
            assertTrue(node.getImageCheckConfig().isScaleMatchingEnabled());
            Thread.sleep(60);
            recognizedAt.set(System.nanoTime());
            return ImageJumpPlaybackTest.FOUND;
        });
        assertTrue(System.nanoTime() - recognizedAt.get() >= TimeUnit.MILLISECONDS.toNanos(50));
        assertEquals(List.of(67), fixture.robot.keys);
    }

    private static MacroFlow flow() {
        ImageCheckConfig config = new ImageCheckConfig("unused.png", .9);
        config.setScaleMatchingEnabled(true);
        MacroNode image = new MacroNode("image", NodeType.IMAGE_CHECK, null, config, new ArrayList<>());
        MacroNode action = new MacroNode("next", NodeType.ACTION, MacroAction.keyRelease(67, "C", 50), new ArrayList<>());
        return MacroFlowFactory.rebuildLinear(List.of(image, action), "end");
    }
}
