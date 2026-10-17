package com.macro.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.player.MacroPlayer;
import com.macro.player.PlayerState;
import com.macro.storage.MacroStorage;
import com.macro.model.MacroAction;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.AWTException;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 独立运行的图像行为回归测试：不执行真实截图，也不向桌面发送键鼠事件。 */
public class ImageCheckBehaviorTest {
    private static final ImageMatchResult FOUND = ImageMatchResult.found(new Rectangle(10, 20, 30, 40));

    public static void main(String[] args) throws Exception {
        testCompatibility();
        for (ImageCheckBehavior success : ImageCheckBehavior.values()) {
            for (ImageCheckBehavior failure : ImageCheckBehavior.values()) {
                testCombination(success, failure, true);
                testCombination(success, failure, false);
                testPersistence(success, failure);
            }
        }
        testRetryControls();
        testInputCleanup();
        System.out.println("通过：九种配置的两类结果、整任务终止、重试控制、输入释放、旧文件兼容及保存重载");
    }

    private static MacroNode image(String id, ImageCheckBehavior success, ImageCheckBehavior failure) {
        ImageCheckConfig config = new ImageCheckConfig("template.png", 0.9);
        config.setSuccessBehavior(success);
        config.setFailureBehavior(failure);
        config.setRetryDelay(5L);
        return new MacroNode(id, NodeType.IMAGE_CHECK, null, config, new ArrayList<>());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void testCompatibility() throws Exception {
        ImageCheckConfig old = new ObjectMapper().readValue(
                "{\"imagePath\":\"template.png\",\"threshold\":0.9}", ImageCheckConfig.class);
        check(old.getSuccessBehavior() == ImageCheckBehavior.EXECUTE, "旧成功行为不兼容");
        check(old.getFailureBehavior() == ImageCheckBehavior.RETRY, "旧失败行为不兼容");
        MacroNode node = new MacroNode("old", NodeType.IMAGE_CHECK, null, old,
                new ArrayList<>(List.of(new MacroBranch(BranchCondition.NOT_FOUND, "old", 734))));
        MacroFlowFactory.rebuildLinear(List.of(node), "end");
        check(MacroFlowFactory.findRetryDelay(node) == 734, "旧重试延迟丢失");
        old.setFailureBehavior(ImageCheckBehavior.EXECUTE);
        MacroFlowFactory.rebuildLinear(List.of(node), "end");
        check(MacroFlowFactory.findRetryDelay(node) == 734, "关闭重试丢失延迟");
    }

    private static void testCombination(ImageCheckBehavior success, ImageCheckBehavior failure,
            boolean found) throws Exception {
        MacroNode first = image("first", success, failure);
        MacroNode next = image("next", ImageCheckBehavior.EXECUTE, ImageCheckBehavior.EXECUTE);
        MacroFlow flow = MacroFlowFactory.rebuildLinear(List.of(first, next), "end");
        ImageCheckBehavior behavior = found ? success : failure;
        MacroPlayer player = new MacroPlayer();
        AtomicInteger checks = new AtomicInteger();
        AtomicInteger nextChecks = new AtomicInteger();
        int loops = behavior == ImageCheckBehavior.TERMINATE ? -1 : 3;
        long started = System.nanoTime();
        FlowContext context = player.play(flow, loops, (node, state) -> {
            if (node.getId().equals("next")) {
                nextChecks.incrementAndGet();
                return FOUND;
            }
            int count = checks.incrementAndGet();
            if (behavior == ImageCheckBehavior.RETRY && count == 3) player.stop();
            if (count > 10) throw new AssertionError("终止未退出整个任务");
            return found ? FOUND : ImageMatchResult.notFound();
        });
        check(player.getState() == PlayerState.IDLE, "未恢复空闲状态");
        check(context.getLastImageMatch() != null, "匹配上下文丢失");
        if (behavior == ImageCheckBehavior.EXECUTE) {
            check(checks.get() == 3 && nextChecks.get() == 3, "执行未进入后继或破坏轮次");
        } else if (behavior == ImageCheckBehavior.TERMINATE) {
            check(checks.get() == 1 && nextChecks.get() == 0, "终止继续执行后继或新轮次");
        } else {
            check(checks.get() == 3 && nextChecks.get() == 0, "重试未回到自身");
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 8, "重试没有等待");
        }
    }

    private static void testPersistence(ImageCheckBehavior success, ImageCheckBehavior failure) throws Exception {
        var file = Files.createTempFile("image-behavior-", ".mr");
        try {
            MacroNode node = image("image", success, failure);
            MacroFlow flow = MacroFlowFactory.rebuildLinear(List.of(node), "end");
            MacroStorage storage = new MacroStorage();
            storage.save(List.of(), null, flow, file.toString());
            MacroNode loaded = storage.load(file.toString()).getFlow().getNodeMap().get("image");
            check(loaded.getImageCheckConfig().getSuccessBehavior() == success, "成功行为未保存");
            check(loaded.getImageCheckConfig().getFailureBehavior() == failure, "失败行为未保存");
            check(MacroFlowFactory.findRetryDelay(loaded) == 5, "延迟未保存");
            check(!Files.readString(file).contains("Base64"), "模板不应内嵌");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static void testRetryControls() throws Exception {
        MacroNode node = image("retry", ImageCheckBehavior.RETRY, ImageCheckBehavior.RETRY);
        node.getImageCheckConfig().setRetryDelay(120L);
        MacroFlow flow = MacroFlowFactory.rebuildLinear(List.of(node), "end");
        MacroPlayer player = new MacroPlayer();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch resumed = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> task = executor.submit(() -> {
                try {
                    player.play(flow, -1, (n, c) -> {
                        if (calls.incrementAndGet() == 1) { player.pause(); paused.countDown(); }
                        else resumed.countDown();
                        return FOUND;
                    });
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            check(paused.await(2, TimeUnit.SECONDS), "重试未启动");
            Thread.sleep(180);
            check(calls.get() == 1, "暂停时仍在重试");
            player.resume();
            check(resumed.await(2, TimeUnit.SECONDS), "继续后未重试");
            player.pause();
            player.stop();
            task.get(2, TimeUnit.SECONDS);
            check(player.getState() == PlayerState.IDLE, "暂停中停止没有收尾");
        } finally {
            player.stop();
            executor.shutdownNow();
        }
    }

    /** 用计数 Robot 验证普通动作与终止/异常清理，避免影响用户桌面。 */
    private static void testInputCleanup() throws Exception {
        for (boolean fail : new boolean[]{false, true}) {
            MacroPlayer player = new MacroPlayer();
            CountingRobot robot = new CountingRobot();
            var field = MacroPlayer.class.getDeclaredField("robot");
            field.setAccessible(true);
            field.set(player, robot);
            MacroNode key = new MacroNode("key", NodeType.ACTION,
                    MacroAction.keyPress(KeyEvent.VK_A, "A", 0), new ArrayList<>());
            MacroNode stop = image("stop", ImageCheckBehavior.TERMINATE, ImageCheckBehavior.TERMINATE);
            MacroFlow flow = MacroFlowFactory.rebuildLinear(List.of(key, stop), "end");
            try {
                player.play(flow, 4, (n, c) -> {
                    if (fail) throw new IllegalStateException("模拟匹配失败");
                    return FOUND;
                });
                check(!fail, "异常被吞掉");
            } catch (IllegalStateException e) {
                check(fail, "意外播放异常");
            }
            check(robot.pressed == 1 && robot.released == 1, "未释放已按下输入或启动多余轮次");
            check(player.getState() == PlayerState.IDLE, "终止或异常未恢复状态");
        }
    }

    private static class CountingRobot extends Robot {
        int pressed;
        int released;
        CountingRobot() throws AWTException { }
        @Override public void keyPress(int key) { pressed++; }
        @Override public void keyRelease(int key) { released++; }
    }
}
