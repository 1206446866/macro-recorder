package com.macro.player;

import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.macro.config.AppConfig;
import com.macro.flow.ImageCheckBehavior;
import com.macro.flow.ImageCheckConfig;
import com.macro.flow.ImageMatchResult;
import com.macro.flow.MacroFlowFactory;
import com.macro.flow.MacroNode;
import com.macro.flow.NodeType;
import com.macro.model.ActionType;
import com.macro.recorder.MacroRecorder;

import java.awt.AWTException;
import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 合成全局按键事件检查 F11 去重；不注册 Hook、不显示窗口、不发送真实输入。 */
public final class StopKeyListenerTest {
    public static void main(String[] args) throws Exception {
        Path classes = Path.of(AppConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!classes.getFileName().toString().equals("classes")
                || !classes.getParent().getFileName().toString().startsWith("StopKeyListenerTest-")) {
            throw new AssertionError("请通过隔离测试入口运行，不能使用个人应用目录");
        }
        repeatedPressAndRelease();
        heldKeySurvivesButtonStateChanges();
        releaseOutsidePlayback();
        newListenerStartsReleased();
        stopWhilePlaying();
        recordingKeepsOrdinaryF11();
        System.out.println("通过：F11 单次/重复按下、释放后重按、按钮切换期间保持去重、空闲释放、监听器重建、播放/暂停中 F12，以及录制普通 F11/暂停/继续");
    }

    private static void repeatedPressAndRelease() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.start();
            int before = fixture.states.size();
            fixture.press(NativeKeyEvent.VC_F11);
            check(fixture.player.getState() == PlayerState.PAUSED, "首次 F11 未暂停");
            for (int i = 0; i < 8; i++) fixture.press(NativeKeyEvent.VC_F11);
            check(fixture.player.getState() == PlayerState.PAUSED && fixture.states.size() == before + 1,
                    "重复按下反复切换暂停状态");
            fixture.release(NativeKeyEvent.VC_F10);
            fixture.press(NativeKeyEvent.VC_F11);
            check(fixture.states.size() == before + 1, "其他按键释放错误清除了 F11 标记");
            fixture.release(NativeKeyEvent.VC_F11); // 暂停时也必须处理释放。
            fixture.press(NativeKeyEvent.VC_F11);
            check(fixture.player.getState() == PlayerState.PLAYING && fixture.states.size() == before + 2,
                    "释放后重按未恢复播放");
            fixture.press(NativeKeyEvent.VC_F11);
            check(fixture.states.size() == before + 2, "恢复后仍响应自动重复");
            fixture.release(NativeKeyEvent.VC_F11);
            fixture.release(NativeKeyEvent.VC_F11);
            fixture.press(NativeKeyEvent.VC_F11);
            check(fixture.player.getState() == PlayerState.PAUSED && fixture.states.size() == before + 3,
                    "再次完整按键周期不能暂停");
            fixture.stopWithF12(); // F11 尚未释放，仍必须能停止。
            int stopped = fixture.states.size();
            fixture.press(NativeKeyEvent.VC_F12);
            check(fixture.states.size() == stopped, "空闲时重复 F12 改变状态");
        }
    }

    private static void heldKeySurvivesButtonStateChanges() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.start();
            fixture.press(NativeKeyEvent.VC_F11);
            fixture.player.resume(); // 等价于通过现有按钮恢复播放，不能重新武装仍按住的 F11。
            int before = fixture.states.size();
            fixture.press(NativeKeyEvent.VC_F11);
            fixture.press(NativeKeyEvent.VC_F11);
            check(fixture.player.getState() == PlayerState.PLAYING && fixture.states.size() == before,
                    "按钮改变状态后自动重复再次暂停");
            fixture.release(NativeKeyEvent.VC_F11);
            fixture.press(NativeKeyEvent.VC_F11);
            check(fixture.player.getState() == PlayerState.PAUSED, "释放后按下状态未恢复");
            fixture.stopWithF12();
        }
    }

    private static void releaseOutsidePlayback() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.press(NativeKeyEvent.VC_F11);
            check(fixture.player.getState() == PlayerState.IDLE, "空闲时 F11 启动了播放");
            fixture.start();
            fixture.press(NativeKeyEvent.VC_F11);
            check(fixture.player.getState() == PlayerState.PLAYING, "跨空闲/播放的持续按下触发第二次切换");
            fixture.release(NativeKeyEvent.VC_F11);
            fixture.press(NativeKeyEvent.VC_F11);
            check(fixture.player.getState() == PlayerState.PAUSED, "跨状态释放未清除标记");
            fixture.stopWithF12();
            fixture.release(NativeKeyEvent.VC_F11); // 停止后的迟到释放也必须生效。
            fixture.start();
            fixture.press(NativeKeyEvent.VC_F11);
            check(fixture.player.getState() == PlayerState.PAUSED, "空闲释放后去重状态永久卡住");
            fixture.stopWithF12();
        }
    }

    private static void newListenerStartsReleased() throws Exception {
        try (Fixture old = new Fixture()) {
            old.start();
            old.press(NativeKeyEvent.VC_F11);
            old.stopWithF12(); // 模拟结束监听时未再收到 F11 release。
        }
        try (Fixture next = new Fixture()) {
            next.start();
            next.press(NativeKeyEvent.VC_F11);
            check(next.player.getState() == PlayerState.PAUSED, "新监听器继承了上次按下状态");
            next.stopWithF12();
        }
    }

    private static void stopWhilePlaying() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.start();
            fixture.stopWithF12();
        }
    }

    private static void recordingKeepsOrdinaryF11() throws Exception {
        MacroRecorder recorder = new MacroRecorder(new AppConfig());
        var state = MacroRecorder.class.getDeclaredField("state");
        state.setAccessible(true);
        state.set(recorder, Arrays.stream(state.getType().getEnumConstants())
                .filter(value -> value.toString().equals("RECORDING")).findFirst().orElseThrow());
        var lastTime = MacroRecorder.class.getDeclaredField("lastTime");
        lastTime.setAccessible(true);
        lastTime.setLong(recorder, System.currentTimeMillis());
        try {
            recorder.nativeKeyPressed(key(NativeKeyEvent.NATIVE_KEY_PRESSED, NativeKeyEvent.VC_F11));
            recorder.nativeKeyPressed(key(NativeKeyEvent.NATIVE_KEY_PRESSED, NativeKeyEvent.VC_F11));
            recorder.nativeKeyReleased(key(NativeKeyEvent.NATIVE_KEY_RELEASED, NativeKeyEvent.VC_F11));
            var actions = recorder.getActions();
            check(actions.size() == 3 && actions.stream().allMatch(action -> action.getKeyCode() == KeyEvent.VK_F11),
                    "普通录制 F11 或自动重复被意外过滤");
            check(actions.get(0).getType() == ActionType.KEY_PRESS
                    && actions.get(1).getType() == ActionType.KEY_PRESS
                    && actions.get(2).getType() == ActionType.KEY_RELEASE, "录制按下/释放语义改变");
            check(!recorder.getRecordingStatus().paused() && recorder.pause(), "F11 错误切换了录制暂停");
            int before = recorder.getActions().size();
            recorder.nativeKeyPressed(key(NativeKeyEvent.NATIVE_KEY_PRESSED, NativeKeyEvent.VC_F11));
            recorder.nativeKeyReleased(key(NativeKeyEvent.NATIVE_KEY_RELEASED, NativeKeyEvent.VC_F11));
            check(recorder.getRecordingStatus().paused() && recorder.getActions().size() == before,
                    "暂停录制时 F11 错误恢复或写入动作");
            check(recorder.resume(), "录制不能继续");
            recorder.nativeKeyPressed(key(NativeKeyEvent.NATIVE_KEY_PRESSED, NativeKeyEvent.VC_F11));
            recorder.nativeKeyReleased(key(NativeKeyEvent.NATIVE_KEY_RELEASED, NativeKeyEvent.VC_F11));
            check(!recorder.getRecordingStatus().paused() && recorder.getActions().size() == before + 2,
                    "继续录制后普通 F11 不再正常录入");
            recorder.nativeKeyPressed(key(NativeKeyEvent.NATIVE_KEY_PRESSED, NativeKeyEvent.VC_F12));
            check(!recorder.getRecordingStatus().recording(), "录制 F12 停止失效");
        } finally {
            recorder.stop();
        }
    }

    private static NativeKeyEvent key(int type, int code) {
        return new NativeKeyEvent(type, 0, code, code, NativeKeyEvent.CHAR_UNDEFINED);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** 实际播放器执行仅含图像重试的流程，模拟匹配；计数 Robot 防止意外真实输入。 */
    private static final class Fixture implements AutoCloseable {
        final MacroPlayer player = new MacroPlayer();
        final CountingRobot robot = new CountingRobot();
        final StopKeyListener listener = new StopKeyListener(player);
        final List<PlayerState> states = new CopyOnWriteArrayList<>();
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> task;

        Fixture() throws Exception {
            var field = MacroPlayer.class.getDeclaredField("robot");
            field.setAccessible(true);
            field.set(player, robot);
            check(field.get(player) == robot, "计数 Robot 未注入，拒绝播放");
            player.setOnStateChange(states::add);
        }

        void start() throws Exception {
            CountDownLatch matched = new CountDownLatch(1);
            ImageCheckConfig config = new ImageCheckConfig("unused-template.png", 0.9);
            config.setSuccessBehavior(ImageCheckBehavior.RETRY);
            config.setFailureBehavior(ImageCheckBehavior.RETRY);
            config.setRetryDelay(10_000L);
            var flow = MacroFlowFactory.rebuildLinear(List.of(
                    new MacroNode("image", NodeType.IMAGE_CHECK, null, config, new ArrayList<>())), "end");
            task = executor.submit(() -> player.play(flow, 1, (node, context) -> {
                matched.countDown();
                return ImageMatchResult.notFound();
            }));
            check(matched.await(2, TimeUnit.SECONDS) && player.getState() == PlayerState.PLAYING,
                    "实际播放未启动");
        }

        void press(int code) { listener.nativeKeyPressed(key(NativeKeyEvent.NATIVE_KEY_PRESSED, code)); }
        void release(int code) { listener.nativeKeyReleased(key(NativeKeyEvent.NATIVE_KEY_RELEASED, code)); }

        void stopWithF12() throws Exception {
            press(NativeKeyEvent.VC_F12);
            task.get(2, TimeUnit.SECONDS);
            check(player.getState() == PlayerState.IDLE && robot.inputs.get() == 0,
                    "F12 未结束整个播放或意外执行真实输入");
        }

        @Override public void close() throws InterruptedException {
            player.stop();
            executor.shutdownNow();
            check(executor.awaitTermination(2, TimeUnit.SECONDS), "测试播放线程未结束");
            check(robot.inputs.get() == 0, "测试意外调用输入动作");
        }
    }

    private static final class CountingRobot extends Robot {
        final AtomicInteger inputs = new AtomicInteger();
        CountingRobot() throws AWTException { }
        @Override public void keyPress(int key) { inputs.incrementAndGet(); }
        @Override public void keyRelease(int key) { inputs.incrementAndGet(); }
        @Override public void mouseMove(int x, int y) { inputs.incrementAndGet(); }
        @Override public void mousePress(int buttons) { inputs.incrementAndGet(); }
        @Override public void mouseRelease(int buttons) { inputs.incrementAndGet(); }
        @Override public void mouseWheel(int amount) { inputs.incrementAndGet(); }
    }
}
