package com.macro.player;

import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.macro.flow.*;
import com.macro.model.MacroAction;

import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.macro.player.PlaybackProgress.Phase.*;

/** 实际流程、计数 Robot 和模拟匹配器；不注册全局监听、不截屏、不发送真实输入。 */
public final class PlaybackProgressTest {
    public static void main(String[] args) throws Exception {
        jumpsAndLoopsReportActualOrder();
        retryAndTerminationReportOnlyExecutedNodes();
        cancellationAndFailureDoNotCompletePendingNodes();
        imageCompletionRemainsVisibleWhilePaused();
        pauseAcknowledgementWaitsForInputCompletion();
        pauseAcknowledgementCanResumeWithoutLosingWakeup();
        routedHotkeyKeepsRepeatGuardAndImmediateStop();
        observerFailureDoesNotChangeExecution();
        System.out.println("通过：实际跳转/回跳/重试与每轮顺序、终止、停止/异常未完成节点、识图期间暂停、快捷键分流去重及观察者异常隔离");
    }

    private static void jumpsAndLoopsReportActualOrder() throws Exception {
        try (Fixture fixture = new Fixture()) {
            MacroNode a = action("a"), skipped = action("skip"), b = action("b"), image = image();
            image.getImageCheckConfig().setFailureBehavior(ImageCheckBehavior.JUMP);
            MacroFlow flow = MacroFlowFactory.rebuildLinear(List.of(a, image, skipped, b), "end", 1L);
            MacroFlowFactory.branchFor(image, BranchCondition.NOT_FOUND).setTargetId("b");
            fixture.player.play(flow, 2, (node, context) -> ImageMatchResult.notFound());
            check(fixture.completed().equals(List.of("a", "image", "b", "a", "image", "b")), "前跳或每轮进度与执行不一致");
            check(fixture.events.stream().filter(e -> e.phase() == LOOP_STARTED).count() == 2, "每轮没有重置通知");
            check(fixture.events.stream().filter(e -> e.phase() == HEAD_WAIT_COMPLETED).count() == 2, "待机完成没有上报");
            check(fixture.events.get(fixture.events.size() - 1).phase() == FINISHED, "缺少最终收尾通知");
            fixture.events.clear();
            image.getImageCheckConfig().setSuccessBehavior(ImageCheckBehavior.JUMP);
            image.getImageCheckConfig().setFailureBehavior(ImageCheckBehavior.EXECUTE);
            MacroFlowFactory.branchFor(image, BranchCondition.FOUND).setTargetId("a");
            MacroFlowFactory.branchFor(image, BranchCondition.NOT_FOUND).setTargetId("b");
            AtomicInteger matches = new AtomicInteger();
            fixture.player.play(flow, 1, (node, context) -> matches.incrementAndGet() == 1
                    ? ImageMatchResult.found(1, new Rectangle(0, 0, 1, 1)) : ImageMatchResult.notFound());
            check(fixture.completed().equals(List.of("a", "image", "a", "image", "b")), "回跳进度被时间线顺序替代");
            check(fixture.events.stream().filter(e -> e.phase() == HEAD_WAIT_STARTED).count() == 1, "回跳重复触发头部待机");
        }
    }

    private static void retryAndTerminationReportOnlyExecutedNodes() throws Exception {
        try (Fixture fixture = new Fixture()) {
            MacroNode image = image();
            image.getImageCheckConfig().setRetryDelay(1L);
            MacroFlow flow = MacroFlowFactory.rebuildLinear(List.of(image, action("after")), "end");
            AtomicInteger matches = new AtomicInteger();
            fixture.player.play(flow, 1, (node, context) -> matches.incrementAndGet() < 3
                    ? ImageMatchResult.notFound() : ImageMatchResult.found(1, new Rectangle(0, 0, 1, 1)));
            check(fixture.completed().equals(List.of("image", "image", "image", "after")), "重试没有按实际尝试上报");
            fixture.events.clear();
            image.getImageCheckConfig().setFailureBehavior(ImageCheckBehavior.TERMINATE);
            fixture.player.play(flow, 3, (node, context) -> ImageMatchResult.notFound());
            check(fixture.completed().equals(List.of("image")), "终止后错误标记后续或下一轮");
        }
    }

    private static void cancellationAndFailureDoNotCompletePendingNodes() throws Exception {
        try (Fixture fixture = new Fixture()) {
            MacroNode pending = action("pending");
            pending.getAction().setDelay(Long.MAX_VALUE);
            CountDownLatch started = new CountDownLatch(1);
            fixture.player.setOnProgress(event -> {
                fixture.events.add(event);
                if (event.phase() == NODE_STARTED) started.countDown();
            });
            Future<?> task = fixture.executor.submit(() -> {
                fixture.player.play(MacroFlowFactory.rebuildLinear(List.of(pending), "end"), 1);
                return null;
            });
            check(started.await(2, TimeUnit.SECONDS), "未进入当前节点");
            fixture.player.stop();
            task.get(2, TimeUnit.SECONDS);
            check(fixture.completed().isEmpty() && fixture.robot.inputs.get() == 0, "取消的等待节点被标记完成或发送输入");
            fixture.events.clear();
            try {
                fixture.player.play(MacroFlowFactory.rebuildLinear(List.of(image(), action("after")), "end"), 1,
                        (node, context) -> { throw new IOException("模拟识别异常"); });
                throw new AssertionError("识别异常被吞掉");
            } catch (IOException expected) {
                check(fixture.completed().isEmpty() && fixture.player.getState() == PlayerState.IDLE, "失败节点被完成标记");
                check(fixture.events.get(fixture.events.size() - 1).phase() == FINISHED, "异常未上报收尾");
            }
        }
    }

    private static void imageCompletionRemainsVisibleWhilePaused() throws Exception {
        try (Fixture fixture = new Fixture()) {
            MacroNode image = image();
            image.getImageCheckConfig().setFailureBehavior(ImageCheckBehavior.EXECUTE);
            CountDownLatch completed = new CountDownLatch(1);
            CountDownLatch acknowledged = new CountDownLatch(1);
            fixture.player.setOnPauseReached(acknowledged::countDown);
            fixture.player.setOnProgress(event -> {
                fixture.events.add(event);
                if (event.phase() == NODE_COMPLETED) completed.countDown();
            });
            Future<?> task = fixture.executor.submit(() -> fixture.player.play(
                    MacroFlowFactory.rebuildLinear(List.of(image, action("after")), "end"), 1,
                    (node, context) -> { fixture.player.pause(); return ImageMatchResult.notFound(); }));
            check(completed.await(2, TimeUnit.SECONDS), "识图已经结束却未上报完成");
            check(acknowledged.await(2, TimeUnit.SECONDS), "识图结束后没有确认安全暂停点");
            check(fixture.player.getState() == PlayerState.PAUSED && fixture.robot.inputs.get() == 0,
                    "暂停时误执行后续动作");
            fixture.player.resume();
            task.get(2, TimeUnit.SECONDS);
            check(fixture.completed().equals(List.of("image", "after")), "继续后进度不一致");
        }
    }

    private static void pauseAcknowledgementWaitsForInputCompletion() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CountDownLatch inputStarted = new CountDownLatch(1), releaseInput = new CountDownLatch(1);
            CountDownLatch acknowledged = new CountDownLatch(1);
            AtomicInteger moves = new AtomicInteger();
            fixture.robot.onMove = () -> {
                if (moves.incrementAndGet() != 1) return;
                inputStarted.countDown();
                try { check(releaseInput.await(2, TimeUnit.SECONDS), "模拟输入未释放"); }
                catch (InterruptedException failure) { throw new IllegalStateException(failure); }
            };
            fixture.player.setOnPauseReached(acknowledged::countDown);
            Future<?> task = fixture.executor.submit(() -> {
                fixture.player.play(MacroFlowFactory.rebuildLinear(List.of(action("a"), action("b")), "end"), 1);
                return null;
            });
            check(inputStarted.await(2, TimeUnit.SECONDS), "没有进入模拟输入");
            fixture.player.pause();
            check(acknowledged.getCount() == 1, "尚未完成输入就允许前置查看窗口");
            releaseInput.countDown();
            check(acknowledged.await(2, TimeUnit.SECONDS), "输入完成后没有确认暂停");
            check(fixture.completed().equals(List.of("a")) && fixture.robot.inputs.get() == 1,
                    "暂停确认与真实输入边界不一致");
            fixture.player.resume();
            task.get(2, TimeUnit.SECONDS);
        }
    }

    private static void pauseAcknowledgementCanResumeWithoutLosingWakeup() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.player.setOnPauseReached(fixture.player::resume);
            // 回调中的再次 PLAYING 通知不重复暂停。
            java.util.concurrent.atomic.AtomicBoolean first = new java.util.concurrent.atomic.AtomicBoolean(true);
            fixture.player.setOnStateChange(state -> {
                if (state == PlayerState.PLAYING && first.getAndSet(false)) fixture.player.pause();
            });
            Future<?> task = fixture.executor.submit(() -> {
                fixture.player.play(MacroFlowFactory.rebuildLinear(List.of(action("a")), "end"), 1);
                return null;
            });
            task.get(2, TimeUnit.SECONDS);
            check(fixture.robot.inputs.get() == 1 && fixture.player.getState() == PlayerState.IDLE,
                    "回调继续造成唤醒丢失");
        }
    }

    private static void routedHotkeyKeepsRepeatGuardAndImmediateStop() throws Exception {
        try (Fixture fixture = new Fixture()) {
            AtomicInteger requests = new AtomicInteger();
            StopKeyListener listener = new StopKeyListener(fixture.player, requests::incrementAndGet);
            for (int i = 0; i < 10; i++) listener.nativeKeyPressed(key(NativeKeyEvent.NATIVE_KEY_PRESSED, NativeKeyEvent.VC_F11));
            check(requests.get() == 1, "F11 回调失去按住去重");
            listener.nativeKeyReleased(key(NativeKeyEvent.NATIVE_KEY_RELEASED, NativeKeyEvent.VC_F11));
            listener.nativeKeyPressed(key(NativeKeyEvent.NATIVE_KEY_PRESSED, NativeKeyEvent.VC_F11));
            check(requests.get() == 2, "释放后不能再次请求");
            MacroFlow flow = MacroFlowFactory.fromActions(List.of(MacroAction.mouseMove(0, 0, Long.MAX_VALUE)));
            CountDownLatch started = new CountDownLatch(1);
            fixture.player.setOnStateChange(state -> { if (state == PlayerState.PLAYING) started.countDown(); });
            Future<?> task = fixture.executor.submit(() -> { fixture.player.play(flow, 1); return null; });
            check(started.await(2, TimeUnit.SECONDS), "未进入播放");
            listener.nativeKeyPressed(key(NativeKeyEvent.NATIVE_KEY_PRESSED, NativeKeyEvent.VC_F12));
            task.get(2, TimeUnit.SECONDS);
            check(fixture.robot.inputs.get() == 0 && requests.get() == 2, "F12 被回调分流或继续发送输入");
        }
    }

    private static void observerFailureDoesNotChangeExecution() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.player.setOnProgress(event -> { throw new IllegalStateException("预期的进度回调异常"); });
            fixture.player.play(MacroFlowFactory.rebuildLinear(List.of(action("a"), action("b")), "end"), 1);
            check(fixture.robot.inputs.get() == 2 && fixture.player.getState() == PlayerState.IDLE,
                    "进度观察者改变了实际执行或清理");
        }
    }

    private static NativeKeyEvent key(int id, int code) {
        return new NativeKeyEvent(id, 0, 0, code, NativeKeyEvent.CHAR_UNDEFINED);
    }
    private static MacroNode action(String id) {
        return new MacroNode(id, NodeType.ACTION, MacroAction.mouseMove(0, 0, 0), new ArrayList<>());
    }
    private static MacroNode image() {
        return new MacroNode("image", NodeType.IMAGE_CHECK, null, new ImageCheckConfig("unused.png", .9), new ArrayList<>());
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static final class Fixture implements AutoCloseable {
        final MacroPlayer player = new MacroPlayer();
        final CountingRobot robot = new CountingRobot();
        final List<PlaybackProgress> events = new CopyOnWriteArrayList<>();
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        Fixture() throws Exception {
            var field = MacroPlayer.class.getDeclaredField("robot");
            field.setAccessible(true);
            field.set(player, robot);
            check(field.get(player) == robot, "计数 Robot 未注入，拒绝播放");
            player.setOnProgress(events::add);
        }
        List<String> completed() {
            return events.stream().filter(event -> event.phase() == NODE_COMPLETED).map(PlaybackProgress::nodeId).toList();
        }
        @Override public void close() throws Exception {
            player.stop();
            executor.shutdownNow();
            check(executor.awaitTermination(2, TimeUnit.SECONDS), "测试线程没有退出");
        }
    }
    private static final class CountingRobot extends Robot {
        final AtomicInteger inputs = new AtomicInteger();
        Runnable onMove;
        CountingRobot() throws AWTException { }
        @Override public void mouseMove(int x, int y) { inputs.incrementAndGet(); if (onMove != null) onMove.run(); }
        @Override public void mousePress(int mask) { inputs.incrementAndGet(); }
        @Override public void mouseRelease(int mask) { inputs.incrementAndGet(); }
        @Override public void mouseWheel(int amount) { inputs.incrementAndGet(); }
        @Override public void keyPress(int key) { inputs.incrementAndGet(); }
        @Override public void keyRelease(int key) { inputs.incrementAndGet(); }
    }
}
