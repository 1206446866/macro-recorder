package com.macro.flow;

import com.macro.model.MacroAction;
import com.macro.player.MacroPlayer;
import com.macro.player.PlayerState;

import java.awt.AWTException;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** 使用计数 Robot 和模拟匹配器；非法流程必须在任何输入或匹配调用前被拒绝。 */
public final class FlowPreflightValidationTest {
    private static final ImageMatchResult FOUND = ImageMatchResult.found(new Rectangle(1, 2, 3, 4));

    public static void main(String[] args) throws Exception {
        missingActionSuccessor();
        invalidStructureAndReferences();
        missingImageSuccessors();
        exactAndFallbackBranches();
        retryAndLegacyDefaults();
        branchlessRetryAndTermination();
        System.out.println("通过：必需后继、非法入口/节点/引用、精确及后备分支、重试/终止、旧默认值与零输入拒绝");
    }

    private static void missingActionSuccessor() throws Exception {
        MacroFlow missing = actionFlow();
        missing.getNodeMap().get("release").setChildren(new ArrayList<>());
        rejected(missing, "ALWAYS");
        MacroFlow wrongCondition = actionFlow();
        wrongCondition.getNodeMap().get("release").setChildren(List.of(branch(BranchCondition.FOUND, "end")));
        rejected(wrongCondition, "ALWAYS");
    }

    private static void invalidStructureAndReferences() throws Exception {
        List<Consumer<MacroFlow>> corruptions = List.of(
                flow -> flow.setStartId("missing-start"),
                flow -> flow.setStartId(""),
                flow -> flow.getNodeMap().get("release").getChildren().get(0).setTargetId("missing-target"),
                flow -> flow.getNodeMap().get("release").setId("wrong-index"),
                flow -> flow.getNodeMap().get("release").setType(null),
                flow -> flow.getNodeMap().get("release").setChildren(null),
                flow -> flow.getNodeMap().get("release").getChildren().add(null),
                flow -> flow.getNodeMap().get("release").getChildren().get(0).setDelay(-1)
        );
        for (Consumer<MacroFlow> corruption : corruptions) {
            MacroFlow flow = actionFlow();
            corruption.accept(flow);
            rejected(flow, "");
        }
    }

    private static void missingImageSuccessors() throws Exception {
        MacroFlow success = imageFlow(ImageCheckBehavior.EXECUTE, ImageCheckBehavior.TERMINATE);
        success.getNodeMap().get("check").setChildren(List.of(branch(BranchCondition.NOT_FOUND, "after")));
        rejected(success, "FOUND");
        MacroFlow failure = imageFlow(ImageCheckBehavior.RETRY, ImageCheckBehavior.EXECUTE);
        failure.getNodeMap().get("check").setChildren(List.of(branch(BranchCondition.FOUND, "after")));
        rejected(failure, "NOT_FOUND");
        for (BranchCondition only : List.of(BranchCondition.FOUND, BranchCondition.NOT_FOUND)) {
            MacroFlow both = imageFlow(ImageCheckBehavior.EXECUTE, ImageCheckBehavior.EXECUTE);
            both.getNodeMap().get("check").setChildren(List.of(branch(only, "after")));
            rejected(both, only == BranchCondition.FOUND ? "NOT_FOUND" : "FOUND");
        }
    }

    private static void exactAndFallbackBranches() throws Exception {
        Fixture ordinary = new Fixture();
        ordinary.player.play(actionFlow(), 1);
        ordinary.checkInputs();

        MacroFlow successOnly = imageFlow(ImageCheckBehavior.EXECUTE, ImageCheckBehavior.TERMINATE);
        successOnly.getNodeMap().get("check").setChildren(List.of(branch(BranchCondition.FOUND, "after")));
        path(successOnly, true, List.of("check", "after"));
        path(successOnly, false, List.of("check"));
        MacroFlow failureOnly = imageFlow(ImageCheckBehavior.TERMINATE, ImageCheckBehavior.EXECUTE);
        failureOnly.getNodeMap().get("check").setChildren(List.of(branch(BranchCondition.NOT_FOUND, "after")));
        path(failureOnly, false, List.of("check", "after"));
        path(failureOnly, true, List.of("check"));

        MacroFlow fallback = imageFlow(ImageCheckBehavior.EXECUTE, ImageCheckBehavior.EXECUTE);
        fallback.getNodeMap().get("check").setChildren(List.of(branch(BranchCondition.ALWAYS, "after")));
        path(fallback, true, List.of("check", "after"));
        path(fallback, false, List.of("check", "after"));

        // ALWAYS 放在前面，仍然必须保留运行时精确条件优先的语义。
        fallback.getNodeMap().get("check").setChildren(List.of(
                branch(BranchCondition.ALWAYS, "end"), branch(BranchCondition.FOUND, "after")));
        path(fallback, true, List.of("check", "after"));
        path(fallback, false, List.of("check"));
    }

    private static void retryAndLegacyDefaults() throws Exception {
        for (boolean legacy : new boolean[]{false, true}) {
            MacroFlow flow = imageFlow(ImageCheckBehavior.EXECUTE, ImageCheckBehavior.RETRY);
            MacroNode image = flow.getNodeMap().get("check");
            image.getImageCheckConfig().setRetryDelay(0L);
            if (legacy) {
                image.getImageCheckConfig().setSuccessBehavior(null);
                image.getImageCheckConfig().setFailureBehavior(null);
                // 默认未找到行为直接重试，不需要 NOT_FOUND 分支。
                image.setChildren(List.of(branch(BranchCondition.FOUND, "after")));
            } else {
                image.setChildren(List.of(branch(BranchCondition.FOUND, "after"),
                        branch(BranchCondition.NOT_FOUND, "check")));
            }
            Fixture fixture = new Fixture();
            List<String> visits = new ArrayList<>();
            AtomicInteger attempts = new AtomicInteger();
            fixture.player.play(flow, 1, (node, context) -> {
                visits.add(node.getId());
                return node == image && attempts.incrementAndGet() < 3 ? ImageMatchResult.notFound() : FOUND;
            });
            check(visits.equals(List.of("check", "check", "check", "after")), "重试闭环或旧默认行为改变");
            fixture.checkInputs();
        }
    }

    private static void branchlessRetryAndTermination() throws Exception {
        for (boolean found : new boolean[]{true, false}) {
            MacroFlow terminate = imageFlow(ImageCheckBehavior.TERMINATE, ImageCheckBehavior.TERMINATE);
            terminate.getNodeMap().get("check").setChildren(new ArrayList<>());
            Fixture fixture = new Fixture();
            AtomicInteger checks = new AtomicInteger();
            fixture.player.play(terminate, -1, (node, context) -> {
                check(checks.incrementAndGet() == 1, "终止没有结束整个任务");
                return found ? FOUND : ImageMatchResult.notFound();
            });
            fixture.checkInputs();
        }
        MacroFlow retry = imageFlow(ImageCheckBehavior.RETRY, ImageCheckBehavior.RETRY);
        retry.getNodeMap().get("check").setChildren(new ArrayList<>());
        retry.getNodeMap().get("check").getImageCheckConfig().setRetryDelay(0L);
        Fixture fixture = new Fixture();
        AtomicInteger attempts = new AtomicInteger();
        fixture.player.play(retry, -1, (node, context) -> {
            if (attempts.incrementAndGet() == 3) fixture.player.stop();
            check(attempts.get() <= 3, "重试停止失效");
            return FOUND;
        });
        check(attempts.get() == 3, "无普通后继的合法重试被拒绝");
        fixture.checkInputs();
    }

    private static void rejected(MacroFlow flow, String message) throws Exception {
        Fixture fixture = new Fixture();
        AtomicInteger matches = new AtomicInteger(), stateChanges = new AtomicInteger();
        fixture.player.setOnStateChange(state -> stateChanges.incrementAndGet());
        try {
            fixture.player.play(flow, 1, (node, context) -> {
                matches.incrementAndGet();
                return FOUND;
            });
            throw new AssertionError("非法流程未被拒绝");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains(message), "缺少明确的节点或必需结果提示：" + expected.getMessage());
        }
        check(fixture.robot.events.isEmpty(), "非法流程拒绝前调用了输入执行器：" + fixture.robot.events);
        check(matches.get() == 0, "非法流程拒绝前开始图像识别");
        check(stateChanges.get() == 0 && fixture.player.getState() == PlayerState.IDLE,
                "非法流程在拒绝前进入播放状态");
    }

    private static void path(MacroFlow flow, boolean found, List<String> expected) throws Exception {
        Fixture fixture = new Fixture();
        List<String> visits = new ArrayList<>();
        fixture.player.play(flow, 1, (node, context) -> {
            visits.add(node.getId());
            return found ? FOUND : ImageMatchResult.notFound();
        });
        check(visits.equals(expected), "合法精确/后备/终止路径改变：" + visits);
        fixture.checkInputs();
    }

    private static MacroFlow actionFlow() {
        return MacroFlowFactory.rebuildLinear(List.of(press(), new MacroNode("release", NodeType.ACTION,
                MacroAction.keyRelease(KeyEvent.VK_A, "A", 0), new ArrayList<>())), "end");
    }

    private static MacroFlow imageFlow(ImageCheckBehavior success, ImageCheckBehavior failure) {
        ImageCheckConfig config = new ImageCheckConfig("unused-template.png", 0.9);
        config.setSuccessBehavior(success);
        config.setFailureBehavior(failure);
        MacroNode image = new MacroNode("check", NodeType.IMAGE_CHECK, null, config, new ArrayList<>());
        ImageCheckConfig stop = new ImageCheckConfig("unused-template.png", 0.9);
        stop.setSuccessBehavior(ImageCheckBehavior.TERMINATE);
        stop.setFailureBehavior(ImageCheckBehavior.TERMINATE);
        MacroNode after = new MacroNode("after", NodeType.IMAGE_CHECK, null, stop, new ArrayList<>());
        MacroFlow flow = MacroFlowFactory.rebuildLinear(List.of(press(), image, after), "end");
        after.setChildren(new ArrayList<>());
        return flow;
    }

    private static MacroNode press() {
        return new MacroNode("press", NodeType.ACTION,
                MacroAction.keyPress(KeyEvent.VK_A, "A", 0), new ArrayList<>());
    }

    private static MacroBranch branch(BranchCondition condition, String target) {
        return new MacroBranch(condition, target, 0L);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** 沿用既有检查的计数 Robot 注入方式，所有可能发送输入的方法均被覆盖。 */
    private static final class Fixture {
        final MacroPlayer player = new MacroPlayer();
        final CountingRobot robot = new CountingRobot();
        Fixture() throws Exception {
            var field = MacroPlayer.class.getDeclaredField("robot");
            field.setAccessible(true);
            field.set(player, robot);
            check(field.get(player) == robot, "计数执行器未正确注入，拒绝运行");
        }
        void checkInputs() {
            check(robot.events.equals(List.of("按下:65", "释放:65")), "合法动作或输入释放改变：" + robot.events);
            check(player.getState() == PlayerState.IDLE, "合法流程未恢复 IDLE");
        }
    }

    private static final class CountingRobot extends Robot {
        final List<String> events = new ArrayList<>();
        CountingRobot() throws AWTException { }
        @Override public void keyPress(int key) { events.add("按下:" + key); }
        @Override public void keyRelease(int key) { events.add("释放:" + key); }
        @Override public void mouseMove(int x, int y) { events.add("移动"); }
        @Override public void mousePress(int buttons) { events.add("鼠标按下"); }
        @Override public void mouseRelease(int buttons) { events.add("鼠标释放"); }
        @Override public void mouseWheel(int amount) { events.add("滚轮"); }
    }
}
