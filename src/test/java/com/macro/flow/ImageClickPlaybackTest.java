package com.macro.flow;

import com.macro.model.MacroAction;
import com.macro.player.MacroPlayer;
import com.macro.player.PlayerState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.awt.*;
import java.awt.event.InputEvent;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** 只用计数 Robot 和合成识别结果，不移动真实鼠标或发送输入。 */
@EnabledOnOs(OS.WINDOWS)
@Timeout(10)
class ImageClickPlaybackTest {
    private static final Rectangle BOUNDS = new Rectangle(-150, 30, 32, 18);
    private static final ImageMatchResult FOUND = ImageMatchResult.found(1, BOUNDS);

    @Test void centerClickCompletesBeforeFollowingActionAndKeepsItsDelay() throws Exception {
        Fixture fixture = new Fixture();
        fixture.player.play(flow(ImageCheckBehavior.CLICK_EXECUTE, ImageClickMode.CENTER, 50), 1, (n, c) -> FOUND);
        assertEquals(List.of("move:-134,39", "press", "release", "key:65"), fixture.robot.events);
        assertTrue(fixture.robot.keyAt - fixture.robot.releasedAt >= TimeUnit.MILLISECONDS.toNanos(45));
        assertEquals(PlayerState.IDLE, fixture.player.getState());
    }

    @Test void clickTerminateEndsEntireInfiniteLoopAfterOneClick() throws Exception {
        Fixture fixture = new Fixture();
        fixture.player.play(flow(ImageCheckBehavior.CLICK_TERMINATE, ImageClickMode.CENTER, 0), -1, (n, c) -> FOUND);
        assertEquals(List.of("move:-134,39", "press", "release"), fixture.robot.events);
        assertEquals(PlayerState.IDLE, fixture.player.getState());
    }

    @Test void randomClicksUseOnlyTheCurrentLogicalHitBounds() throws Exception {
        Fixture fixture = new Fixture();
        fixture.player.play(flow(ImageCheckBehavior.CLICK_EXECUTE, ImageClickMode.RANDOM, 0), 12, (n, c) -> FOUND);
        assertEquals(12, fixture.robot.points.size());
        assertTrue(fixture.robot.points.stream().allMatch(BOUNDS::contains));
        assertEquals(12, fixture.robot.events.stream().filter("press"::equals).count());
    }

    @Test void notFoundNeverClicksAnEarlierNodesSuccessfulBounds() throws Exception {
        Fixture fixture = new Fixture();
        MacroFlow flow = flow(ImageCheckBehavior.CLICK_EXECUTE, ImageClickMode.CENTER, 0);
        var previous = image("previous", ImageCheckBehavior.EXECUTE, ImageClickMode.CENTER);
        flow = MacroFlowFactory.rebuildLinear(List.of(previous, flow.getNodeMap().get("image"), flow.getNodeMap().get("next")), "end");
        fixture.player.play(flow, 1, (node, context) -> node.getId().equals("previous") ? FOUND : ImageMatchResult.notFound());
        assertEquals(List.of("key:65"), fixture.robot.events);
    }

    @Test void retryDoesNotClickUntilTheCurrentImageIsFound() throws Exception {
        Fixture fixture = new Fixture();
        MacroFlow flow = flow(ImageCheckBehavior.CLICK_EXECUTE, ImageClickMode.CENTER, 0);
        var config = flow.getNodeMap().get("image").getImageCheckConfig();
        config.setFailureBehavior(ImageCheckBehavior.RETRY);
        config.setRetryDelay(0L);
        MacroFlowFactory.rebuildLinear(MacroFlowFactory.timelineNodes(flow), "end");
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        fixture.player.play(flow, 1, (n, c) -> attempts.incrementAndGet() < 3 ? ImageMatchResult.notFound() : FOUND);
        assertEquals(3, attempts.get());
        assertEquals(List.of("move:-134,39", "press", "release", "key:65"), fixture.robot.events);
    }

    @Test void stopAfterMatchingOrAfterMovingDoesNotPressMouse() throws Exception {
        Fixture first = new Fixture();
        first.player.play(flow(ImageCheckBehavior.CLICK_EXECUTE, ImageClickMode.CENTER, 0), 1,
                (n, c) -> { first.player.stop(); return FOUND; });
        assertTrue(first.robot.events.isEmpty());
        Fixture second = new Fixture();
        second.robot.onMove = second.player::stop;
        second.player.play(flow(ImageCheckBehavior.CLICK_EXECUTE, ImageClickMode.CENTER, 0), 1, (n, c) -> FOUND);
        assertEquals(List.of("move:-134,39"), second.robot.events);
    }

    @Test void stoppingDuringPressStillReleasesAndSkipsNextAction() throws Exception {
        Fixture fixture = new Fixture();
        fixture.robot.onPress = fixture.player::stop;
        fixture.player.play(flow(ImageCheckBehavior.CLICK_EXECUTE, ImageClickMode.CENTER, 0), 1, (n, c) -> FOUND);
        assertEquals(List.of("move:-134,39", "press", "release"), fixture.robot.events);
        assertFalse(fixture.robot.pressed);
    }

    @Test void pauseAfterMatchingWaitsBeforeAnyClick() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch paused = new CountDownLatch(1);
        fixture.player.setOnPauseReached(paused::countDown);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> task = worker.submit(() -> {
                try { fixture.player.play(flow(ImageCheckBehavior.CLICK_EXECUTE, ImageClickMode.CENTER, 0), 1,
                        (n, c) -> { fixture.player.pause(); return FOUND; }); }
                catch (Exception exception) { throw new CompletionException(exception); }
            });
            assertTrue(paused.await(3, TimeUnit.SECONDS));
            assertTrue(fixture.robot.events.isEmpty());
            fixture.player.resume();
            task.get(3, TimeUnit.SECONDS);
            assertEquals(List.of("move:-134,39", "press", "release", "key:65"), fixture.robot.events);
        } finally {
            fixture.player.stop(); worker.shutdownNow();
            assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test void pressFailureAndReleaseFailureStillCleanUpHeldMouse() throws Exception {
        for (boolean failPress : new boolean[]{true, false}) {
            Fixture fixture = new Fixture();
            if (failPress) fixture.robot.onPress = () -> { throw new IllegalStateException("模拟按下失败"); };
            else fixture.robot.failFirstRelease = true;
            assertThrows(IllegalStateException.class, () -> fixture.player.play(
                    flow(ImageCheckBehavior.CLICK_EXECUTE, ImageClickMode.CENTER, 0), 1, (n, c) -> FOUND));
            assertFalse(fixture.robot.pressed);
            assertEquals(PlayerState.IDLE, fixture.player.getState());
            assertFalse(fixture.robot.events.contains("key:65"));
        }
    }

    @Test void invalidFailureClickAndMissingContinuationAreRejectedBeforeInputsOrMatching() throws Exception {
        Fixture fixture = new Fixture();
        MacroFlow flow = flow(ImageCheckBehavior.CLICK_EXECUTE, ImageClickMode.CENTER, 0);
        flow.getNodeMap().get("image").getImageCheckConfig().setFailureBehavior(ImageCheckBehavior.CLICK_TERMINATE);
        assertThrows(IllegalArgumentException.class, () -> fixture.player.play(flow, 1, (n, c) -> { fail("不得识别"); return FOUND; }));
        flow.getNodeMap().get("image").getImageCheckConfig().setFailureBehavior(ImageCheckBehavior.EXECUTE);
        flow.getNodeMap().get("image").getChildren().removeIf(b -> b.getCondition() == BranchCondition.FOUND);
        assertThrows(IllegalArgumentException.class, () -> fixture.player.play(flow, 1, (n, c) -> { fail("不得识别"); return FOUND; }));
        assertTrue(fixture.robot.events.isEmpty());
    }

    @Test void invalidMatchedBoundsFailWithoutSendingInput() throws Exception {
        Fixture fixture = new Fixture();
        assertThrows(IllegalArgumentException.class, () -> fixture.player.play(
                flow(ImageCheckBehavior.CLICK_EXECUTE, ImageClickMode.CENTER, 0), 1,
                (n, c) -> ImageMatchResult.found(new Rectangle(0, 0, 0, 10))));
        assertTrue(fixture.robot.events.isEmpty());
    }

    @Test void failureClicksConfiguredScreenPointBeforeContinuingAndKeepsFollowingDelay() throws Exception {
        Fixture fixture = new Fixture();
        fixture.player.play(failureFlow(ImageCheckBehavior.CLICK_EXECUTE, 50), 1, (n, c) -> ImageMatchResult.notFound());
        assertEquals(List.of("move:0,-240", "press", "release", "key:65"), fixture.robot.events);
        assertTrue(fixture.robot.keyAt - fixture.robot.releasedAt >= TimeUnit.MILLISECONDS.toNanos(45));
        assertEquals(PlayerState.IDLE, fixture.player.getState());
    }

    @Test void failureClickTerminateStopsEntireInfinitePlaybackAfterOneClick() throws Exception {
        Fixture fixture = new Fixture();
        fixture.player.play(failureFlow(ImageCheckBehavior.CLICK_TERMINATE, 0), -1, (n, c) -> ImageMatchResult.notFound());
        assertEquals(List.of("move:0,-240", "press", "release"), fixture.robot.events);
        assertFalse(fixture.robot.pressed);
        assertEquals(PlayerState.IDLE, fixture.player.getState());
    }

    @Test void failureUsesIndependentPointWhileSuccessStillUsesCurrentImageBounds() throws Exception {
        for (boolean found : new boolean[]{true, false}) {
            Fixture fixture = new Fixture();
            MacroFlow flow = failureFlow(ImageCheckBehavior.CLICK_EXECUTE, 0);
            MacroNode previous = image("previous", ImageCheckBehavior.EXECUTE, ImageClickMode.CENTER);
            flow = MacroFlowFactory.rebuildLinear(List.of(previous, flow.getNodeMap().get("image"), flow.getNodeMap().get("next")), "end");
            fixture.player.play(flow, 1, (node, context) -> node == previous || found ? FOUND : ImageMatchResult.notFound());
            assertEquals(List.of(found ? "move:-134,39" : "move:0,-240", "press", "release", "key:65"), fixture.robot.events);
        }
    }

    @Test void missingFailureCoordinateOrContinuationIsRejectedBeforeAnyEarlierInput() throws Exception {
        for (int kind = 0; kind < 3; kind++) {
            Fixture fixture = new Fixture();
            MacroFlow flow = failureFlow(ImageCheckBehavior.CLICK_EXECUTE, 0);
            MacroNode image = flow.getNodeMap().get("image");
            MacroNode earlier = new MacroNode("earlier", NodeType.ACTION, MacroAction.keyRelease(66, "B", 0), new ArrayList<>());
            flow = MacroFlowFactory.rebuildLinear(List.of(earlier, image, flow.getNodeMap().get("next")), "end");
            if (kind == 0) image.getImageCheckConfig().setFailureClickX(null);
            else if (kind == 1) image.getImageCheckConfig().setFailureClickY(null);
            else image.getChildren().removeIf(branch -> branch.getCondition() == BranchCondition.NOT_FOUND);
            MacroFlow invalid = flow;
            assertThrows(IllegalArgumentException.class, () -> fixture.player.play(invalid, 1,
                    (node, context) -> { fail("预检失败不能开始识别"); return FOUND; }));
            assertTrue(fixture.robot.events.isEmpty());
            assertEquals(PlayerState.IDLE, fixture.player.getState());
        }
    }

    @Test void failureClickStopsBeforePressAndReleasesIfStoppedDuringPress() throws Exception {
        for (int phase = 0; phase < 3; phase++) {
            Fixture fixture = new Fixture();
            if (phase == 1) fixture.robot.onMove = fixture.player::stop;
            if (phase == 2) fixture.robot.onPress = fixture.player::stop;
            int stopPhase = phase;
            fixture.player.play(failureFlow(ImageCheckBehavior.CLICK_EXECUTE, 0), 1, (node, context) -> {
                if (stopPhase == 0) fixture.player.stop();
                return ImageMatchResult.notFound();
            });
            assertEquals(phase == 0 ? List.of() : phase == 1 ? List.of("move:0,-240")
                    : List.of("move:0,-240", "press", "release"), fixture.robot.events);
            assertFalse(fixture.robot.pressed);
        }
    }

    @Test void pausedFailureClickWaitsAndFailedNativePressStillReleases() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch paused = new CountDownLatch(1);
        fixture.player.setOnPauseReached(paused::countDown);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> task = worker.submit(() -> {
                try { fixture.player.play(failureFlow(ImageCheckBehavior.CLICK_EXECUTE, 0), 1,
                        (node, context) -> { fixture.player.pause(); return ImageMatchResult.notFound(); }); }
                catch (Exception error) { throw new CompletionException(error); }
            });
            assertTrue(paused.await(3, TimeUnit.SECONDS));
            assertTrue(fixture.robot.events.isEmpty());
            fixture.player.resume();
            task.get(3, TimeUnit.SECONDS);
            assertEquals(List.of("move:0,-240", "press", "release", "key:65"), fixture.robot.events);
        } finally {
            fixture.player.stop(); worker.shutdownNow();
            assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS));
        }
        for (boolean failPress : new boolean[]{true, false}) {
            Fixture broken = new Fixture();
            if (failPress) broken.robot.onPress = () -> { throw new IllegalStateException("模拟失败点击异常"); };
            else broken.robot.failFirstRelease = true;
            assertThrows(IllegalStateException.class, () -> broken.player.play(failureFlow(ImageCheckBehavior.CLICK_EXECUTE, 0), 1,
                    (node, context) -> ImageMatchResult.notFound()));
            assertFalse(broken.robot.pressed);
            assertFalse(broken.robot.events.contains("key:65"));
        }
    }

    private static MacroFlow failureFlow(ImageCheckBehavior behavior, long delay) {
        MacroFlow flow = flow(ImageCheckBehavior.CLICK_EXECUTE, ImageClickMode.CENTER, delay);
        ImageCheckConfig config = flow.getNodeMap().get("image").getImageCheckConfig();
        config.setFailureBehavior(behavior);
        config.setFailureClickX(0); config.setFailureClickY(-240);
        return MacroFlowFactory.rebuildLinear(MacroFlowFactory.timelineNodes(flow), "end");
    }

    private static MacroFlow flow(ImageCheckBehavior behavior, ImageClickMode mode, long delay) {
        return MacroFlowFactory.rebuildLinear(List.of(image("image", behavior, mode), new MacroNode("next", NodeType.ACTION,
                MacroAction.keyRelease(65, "A", delay), new ArrayList<>())), "end");
    }

    private static MacroNode image(String id, ImageCheckBehavior behavior, ImageClickMode mode) {
        ImageCheckConfig config = new ImageCheckConfig("synthetic.png", .9);
        config.setSuccessBehavior(behavior); config.setClickMode(mode);
        config.setFailureBehavior(ImageCheckBehavior.EXECUTE);
        return new MacroNode(id, NodeType.IMAGE_CHECK, null, config, new ArrayList<>());
    }

    private static final class Fixture {
        private final MacroPlayer player;
        private final CountingRobot robot;
        private Fixture() throws Exception {
            assumeFalse(GraphicsEnvironment.isHeadless(), "计数 Robot 构造需要桌面环境");
            player = new MacroPlayer(); robot = new CountingRobot();
            Field field = MacroPlayer.class.getDeclaredField("robot"); field.setAccessible(true); field.set(player, robot);
        }
    }

    private static final class CountingRobot extends Robot {
        private final List<String> events = new ArrayList<>();
        private final List<Point> points = new ArrayList<>();
        private Runnable onMove = () -> { }, onPress = () -> { };
        private boolean pressed, failFirstRelease;
        private long releasedAt, keyAt;
        private CountingRobot() throws AWTException { }
        @Override public void mouseMove(int x, int y) { points.add(new Point(x, y)); events.add("move:" + x + "," + y); onMove.run(); }
        @Override public void mousePress(int mask) { assertEquals(InputEvent.BUTTON1_DOWN_MASK, mask); events.add("press"); pressed = true; onPress.run(); }
        @Override public void mouseRelease(int mask) {
            assertEquals(InputEvent.BUTTON1_DOWN_MASK, mask); events.add("release");
            if (failFirstRelease) { failFirstRelease = false; throw new IllegalStateException("模拟释放失败"); }
            pressed = false; releasedAt = System.nanoTime();
        }
        @Override public void keyRelease(int key) { keyAt = System.nanoTime(); events.add("key:" + key); }
        @Override public void keyPress(int key) { fail("unexpected key press"); }
    }
}
