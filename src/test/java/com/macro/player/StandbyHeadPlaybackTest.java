package com.macro.player;

import com.macro.flow.*;
import com.macro.model.MacroAction;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** 计数 Robot 与模拟匹配器，验证真实播放器等待路径；不发送任何真实输入。 */
public final class StandbyHeadPlaybackTest {
    public static void main(String[] args) throws Exception {
        perLoopAndActionDelays();
        pausedHeadRetainsWholeDelay();
        stoppingHeadPreventsAllInputs();
        imageTimeDoesNotConsumeFollowingDelay();
        negativeWaitRejectedBeforePlayback();
        System.out.println("通过：每轮头部等待、动作原延迟、暂停冻结、停止/超大延迟、识图耗时不抵扣后继延迟及负值预检");
    }

    private static void perLoopAndActionDelays() throws Exception {
        try (Fixture fixture = new Fixture()) {
            MacroFlow flow = MacroFlowFactory.fromActions(List.of(
                    MacroAction.mouseMove(1, 2, 15), MacroAction.mouseMove(3, 4, 35)));
            flow.setHeadWaitMillis(70L);
            AtomicLong started = new AtomicLong();
            fixture.player.setOnStateChange(state -> {
                if (state == PlayerState.PLAYING) started.set(System.nanoTime());
            });
            fixture.player.play(flow, 2);
            check(fixture.robot.inputs.size() == 4, "动作次数/顺序错误");
            check(fixture.robot.inputs.get(0) - started.get() >= nanos(85), "头部待机或原首动作延迟未等待");
            check(fixture.robot.inputs.get(1) - fixture.robot.inputs.get(0) >= nanos(35), "原动作间隔改变");
            check(fixture.robot.inputs.get(2) - fixture.robot.inputs.get(1) >= nanos(85), "下一轮没有执行头部等待");
            flow.setHeadWaitMillis(null);
            fixture.robot.inputs.clear();
            fixture.player.play(flow, 1);
            check(fixture.robot.inputs.size() == 2, "关闭待机影响原流程");
            flow.setHeadWaitMillis(0L);
            fixture.robot.inputs.clear();
            fixture.player.play(flow, 1);
            check(fixture.robot.inputs.size() == 2, "零等待未能继续");
        }
    }

    private static void pausedHeadRetainsWholeDelay() throws Exception {
        try (Fixture fixture = new Fixture()) {
            MacroFlow flow = MacroFlowFactory.fromActions(List.of(MacroAction.mouseMove(0, 0, 0)));
            flow.setHeadWaitMillis(100L);
            CountDownLatch paused = new CountDownLatch(1);
            AtomicBoolean pauseOnce = new AtomicBoolean();
            fixture.player.setOnStateChange(state -> {
                if (state == PlayerState.PLAYING && pauseOnce.compareAndSet(false, true)) {
                    fixture.player.pause();
                    paused.countDown();
                }
            });
            Future<?> task = fixture.start(flow, null);
            check(paused.await(2, TimeUnit.SECONDS), "未进入暂停");
            Thread.sleep(140);
            check(fixture.robot.inputs.isEmpty(), "暂停期间执行了操作");
            long resumed = System.nanoTime();
            fixture.player.resume();
            task.get(2, TimeUnit.SECONDS);
            check(fixture.robot.inputs.get(0) - resumed >= nanos(100), "暂停耗时抵扣了待机延迟");
        }
    }

    private static void stoppingHeadPreventsAllInputs() throws Exception {
        try (Fixture fixture = new Fixture()) {
            MacroFlow flow = MacroFlowFactory.fromActions(List.of(MacroAction.mouseMove(0, 0, 0)));
            flow.setHeadWaitMillis(Long.MAX_VALUE);
            Future<?> task = fixture.start(flow, null);
            fixture.awaitWait(task);
            fixture.player.stop();
            task.get(2, TimeUnit.SECONDS);
            check(fixture.robot.inputs.isEmpty() && fixture.player.getState() == PlayerState.IDLE,
                    "待机停止后仍执行动作或未恢复空闲");
        }
    }

    private static void imageTimeDoesNotConsumeFollowingDelay() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ImageCheckConfig config = new ImageCheckConfig("unused.png", .9);
            config.setFailureBehavior(ImageCheckBehavior.EXECUTE);
            MacroNode image = new MacroNode("image", NodeType.IMAGE_CHECK, null, config, new ArrayList<>());
            MacroNode after = new MacroNode("after", NodeType.ACTION, MacroAction.mouseMove(0, 0, 50), new ArrayList<>());
            MacroFlow flow = MacroFlowFactory.rebuildLinear(List.of(image, after), "end", 30L);
            AtomicLong matchFinished = new AtomicLong();
            fixture.player.play(flow, 1, (node, context) -> {
                Thread.sleep(60);
                matchFinished.set(System.nanoTime());
                return ImageMatchResult.notFound();
            });
            check(fixture.robot.inputs.size() == 1
                            && fixture.robot.inputs.get(0) - matchFinished.get() >= nanos(50),
                    "识图损耗抵扣了后继延迟");
        }
    }

    private static void negativeWaitRejectedBeforePlayback() throws Exception {
        try (Fixture fixture = new Fixture()) {
            MacroFlow flow = MacroFlowFactory.fromActions(List.of(MacroAction.mouseMove(0, 0, 0)));
            flow.setHeadWaitMillis(-1L);
            try {
                fixture.player.play(flow, 1);
                throw new AssertionError("非法待机被允许播放");
            } catch (IllegalArgumentException expected) {
                check(fixture.player.getState() == PlayerState.IDLE && fixture.robot.inputs.isEmpty(), "预检之前执行了输入");
            }
        }
    }

    private static long nanos(long millis) { return TimeUnit.MILLISECONDS.toNanos(millis); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static final class Fixture implements AutoCloseable {
        final MacroPlayer player = new MacroPlayer();
        final CountingRobot robot = new CountingRobot();
        final AtomicReference<Thread> worker = new AtomicReference<>();
        final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "standby-head-test");
            worker.set(thread);
            return thread;
        });

        Fixture() throws Exception {
            var field = MacroPlayer.class.getDeclaredField("robot");
            field.setAccessible(true);
            field.set(player, robot);
            check(field.get(player) == robot, "计数 Robot 未注入，拒绝播放");
        }

        Future<?> start(MacroFlow flow, ImageMatcher matcher) {
            return executor.submit(() -> { player.play(flow, 1, matcher); return null; });
        }

        void awaitWait(Future<?> task) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline) {
                if (task.isDone()) { task.get(); throw new AssertionError("待机提前结束"); }
                Thread thread = worker.get();
                if (thread != null && thread.getState() == Thread.State.TIMED_WAITING) return;
                Thread.sleep(1);
            }
            throw new AssertionError("没有进入待机等待");
        }

        @Override public void close() throws Exception {
            player.stop();
            executor.shutdownNow();
            check(executor.awaitTermination(2, TimeUnit.SECONDS), "播放线程未退出");
        }
    }

    private static final class CountingRobot extends Robot {
        final List<Long> inputs = new CopyOnWriteArrayList<>();
        CountingRobot() throws AWTException { }
        private void record() { inputs.add(System.nanoTime()); }
        @Override public void mouseMove(int x, int y) { record(); }
        @Override public void mousePress(int buttons) { record(); }
        @Override public void mouseRelease(int buttons) { record(); }
        @Override public void mouseWheel(int amount) { record(); }
        @Override public void keyPress(int keycode) { record(); }
        @Override public void keyRelease(int keycode) { record(); }
    }
}
