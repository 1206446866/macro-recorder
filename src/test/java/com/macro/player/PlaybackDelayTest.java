package com.macro.player;

import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.macro.flow.ImageCheckBehavior;
import com.macro.flow.ImageCheckConfig;
import com.macro.flow.ImageMatchResult;
import com.macro.flow.MacroFlow;
import com.macro.flow.MacroFlowFactory;
import com.macro.flow.MacroNode;
import com.macro.flow.NodeType;
import com.macro.model.MacroAction;

import java.awt.AWTException;
import java.awt.Robot;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** 播放等待边界回归：只使用计数 Robot、模拟匹配和合成快捷键，不注册 Hook。 */
public final class PlaybackDelayTest {
    private static final long SAFE_MILLIS = Long.MAX_VALUE / 1_000_000L;

    public static void main(String[] args) throws Exception {
        testNormalDelays();
        for (long delay : new long[]{SAFE_MILLIS, SAFE_MILLIS + 1L,
                SAFE_MILLIS * 2L + 2L, Long.MAX_VALUE}) {
            for (DelaySource source : DelaySource.values()) {
                testLargeDelay(delay, source);
            }
        }
        testPauseResume();
        testInterruptedWait();
        System.out.println("通过：零/普通延迟、换算边界及三类溢出输入，覆盖旧动作/流程动作/分支/图像重试；暂停保留余量、F12停止及线程中断");
    }

    private static void testNormalDelays() throws Exception {
        for (long delay : new long[]{0L, 35L}) {
            for (DelaySource source : List.of(DelaySource.LEGACY_ACTION, DelaySource.FLOW_ACTION)) {
                try (Fixture fixture = new Fixture()) {
                    fixture.start(delay, source);
                    fixture.task.get(3, TimeUnit.SECONDS);
                    check(fixture.robot.inputs.get() == 1, "正常动作未执行一次");
                    check(fixture.robot.lastInputTime.get() - fixture.started.get()
                            >= TimeUnit.MILLISECONDS.toNanos(delay), "正常延迟提前结束");
                    check(fixture.player.getState() == PlayerState.IDLE, "正常播放未恢复 IDLE");
                }
            }
        }
    }

    private static void testLargeDelay(long delay, DelaySource source) throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.start(delay, source);
            fixture.awaitTimedWait();
            expectPending(fixture.task, 70L, "大延迟提前完成：" + source + "/" + delay);
            fixture.checkNoProgress(source);
            // 极大延迟在暂停状态下也必须可以被现有 F12 路径唤醒。
            if (delay == Long.MAX_VALUE) {
                fixture.player.pause();
                check(fixture.player.getState() == PlayerState.PAUSED, "大延迟不能暂停");
                expectPending(fixture.task, 20L, "暂停中的大延迟提前完成");
            }
            new StopKeyListener(fixture.player).nativeKeyPressed(new NativeKeyEvent(
                    NativeKeyEvent.NATIVE_KEY_PRESSED, 0, NativeKeyEvent.VC_F12,
                    NativeKeyEvent.VC_F12, NativeKeyEvent.CHAR_UNDEFINED));
            fixture.task.get(2, TimeUnit.SECONDS);
            fixture.checkNoProgress(source);
            check(fixture.player.getState() == PlayerState.IDLE, "F12 后未恢复 IDLE");
        }
    }

    private static void testPauseResume() throws Exception {
        long delay = 400L;
        try (Fixture fixture = new Fixture()) {
            fixture.start(delay, DelaySource.FLOW_ACTION);
            fixture.awaitTimedWait();
            fixture.player.pause();
            long beforePause = System.nanoTime() - fixture.started.get();
            check(fixture.player.getState() == PlayerState.PAUSED, "普通延迟未进入暂停");
            expectPending(fixture.task, 500L, "暂停期间仍在消耗延迟");
            check(fixture.robot.inputs.get() == 0, "暂停期间执行了动作");
            long resumed = System.nanoTime();
            fixture.player.resume();
            fixture.task.get(3, TimeUnit.SECONDS);
            long afterResume = fixture.robot.lastInputTime.get() - resumed;
            // 允许测试线程观察状态的少量调度误差，但不能把暂停的 500ms 算入等待。
            check(afterResume >= TimeUnit.MILLISECONDS.toNanos(delay - 20L) - beforePause,
                    "恢复播放丢失剩余延迟");
            check(fixture.robot.inputs.get() == 1 && fixture.player.getState() == PlayerState.IDLE,
                    "恢复后动作次数或状态不正确");
        }
    }

    private static void testInterruptedWait() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.start(Long.MAX_VALUE, DelaySource.IMAGE_RETRY);
            fixture.awaitTimedWait();
            fixture.worker.get().interrupt();
            try {
                fixture.task.get(2, TimeUnit.SECONDS);
                throw new AssertionError("线程中断未传回调用方");
            } catch (ExecutionException expected) {
                check(expected.getCause() instanceof InterruptedException, "中断异常类型改变");
            }
            fixture.checkNoProgress(DelaySource.IMAGE_RETRY);
            check(fixture.player.getState() == PlayerState.IDLE, "中断后未恢复 IDLE");
        }
    }

    private static void expectPending(Future<?> task, long millis, String message) throws Exception {
        try {
            task.get(millis, TimeUnit.MILLISECONDS);
            throw new AssertionError(message);
        } catch (TimeoutException expected) {
            // 只观察短时间内仍在等待，不实际等待数百年。
        }
    }

    private static MacroNode image(String id, ImageCheckBehavior behavior, long delay) {
        ImageCheckConfig config = new ImageCheckConfig("unused-template.png", 0.9);
        config.setSuccessBehavior(behavior);
        config.setFailureBehavior(behavior);
        config.setRetryDelay(delay);
        return new MacroNode(id, NodeType.IMAGE_CHECK, null, config, new ArrayList<>());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private enum DelaySource { LEGACY_ACTION, FLOW_ACTION, BRANCH, IMAGE_RETRY }

    /** 沿用现有回归测试的执行器替换方式，注入确认成功后才允许启动播放。 */
    private static final class Fixture implements AutoCloseable {
        final MacroPlayer player = new MacroPlayer();
        final CountingRobot robot = new CountingRobot();
        final AtomicInteger matches = new AtomicInteger();
        final AtomicLong started = new AtomicLong();
        final AtomicReference<Thread> worker = new AtomicReference<>();
        final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "playback-delay-test");
            worker.set(thread);
            return thread;
        });
        Future<?> task;

        Fixture() throws Exception {
            var field = MacroPlayer.class.getDeclaredField("robot");
            field.setAccessible(true);
            field.set(player, robot);
            check(field.get(player) == robot, "计数 Robot 未注入，拒绝启动播放");
        }

        void start(long delay, DelaySource source) {
            task = executor.submit(() -> {
                started.set(System.nanoTime());
                switch (source) {
                    case LEGACY_ACTION -> player.play(List.of(MacroAction.mouseMove(0, 0, delay)), 1);
                    case FLOW_ACTION -> player.play(MacroFlowFactory.fromActions(
                            List.of(MacroAction.mouseMove(0, 0, delay))), 1);
                    case BRANCH, IMAGE_RETRY -> {
                        MacroNode first = image("first", source == DelaySource.BRANCH
                                ? ImageCheckBehavior.EXECUTE : ImageCheckBehavior.RETRY, delay);
                        MacroFlow flow = MacroFlowFactory.rebuildLinear(List.of(first,
                                image("after", ImageCheckBehavior.TERMINATE, 0L)), "end");
                        if (source == DelaySource.BRANCH) {
                            first.getChildren().forEach(branch -> branch.setDelay(delay));
                        }
                        player.play(flow, 1, (node, context) -> {
                            matches.incrementAndGet();
                            return ImageMatchResult.notFound();
                        });
                    }
                }
                return null;
            });
        }

        void awaitTimedWait() throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
            while (System.nanoTime() < deadline) {
                if (task.isDone()) {
                    task.get();
                    throw new AssertionError("延迟在进入等待前已结束");
                }
                Thread thread = worker.get();
                if (player.getState() == PlayerState.PLAYING && thread != null
                        && thread.getState() == Thread.State.TIMED_WAITING) return;
                Thread.sleep(1L);
            }
            throw new AssertionError("未观察到播放器进入定时等待");
        }

        void checkNoProgress(DelaySource source) {
            check(robot.inputs.get() == 0, "延迟未结束却执行了输入动作");
            int expectedMatches = source == DelaySource.BRANCH || source == DelaySource.IMAGE_RETRY ? 1 : 0;
            check(matches.get() == expectedMatches, "延迟溢出导致提前跳转或重复识别");
        }

        @Override public void close() throws InterruptedException {
            player.stop();
            executor.shutdownNow();
            check(executor.awaitTermination(2L, TimeUnit.SECONDS), "测试播放线程未结束");
        }
    }

    private static final class CountingRobot extends Robot {
        final AtomicInteger inputs = new AtomicInteger();
        final AtomicLong lastInputTime = new AtomicLong();
        CountingRobot() throws AWTException { }
        private void record() { lastInputTime.set(System.nanoTime()); inputs.incrementAndGet(); }
        @Override public void keyPress(int key) { record(); }
        @Override public void keyRelease(int key) { record(); }
        @Override public void mouseMove(int x, int y) { record(); }
        @Override public void mousePress(int buttons) { record(); }
        @Override public void mouseRelease(int buttons) { record(); }
        @Override public void mouseWheel(int amount) { record(); }
    }
}
