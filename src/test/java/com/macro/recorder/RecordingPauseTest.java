package com.macro.recorder;

import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.mouse.NativeMouseEvent;
import com.macro.config.AppConfig;
import com.macro.flow.ImageCheckConfig;
import com.macro.flow.NodeType;
import com.macro.model.ActionType;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 直接调用原生事件回调验证暂停/截图状态，不注册钩子，不向桌面发送输入。 */
public final class RecordingPauseTest {
    public static void main(String[] args) throws Exception {
        AppConfig config = new AppConfig();
        var propertiesField = AppConfig.class.getDeclaredField("properties");
        propertiesField.setAccessible(true);
        ((Properties) propertiesField.get(config)).setProperty("record.mouseMove", "false");
        MacroRecorder recorder = new MacroRecorder(config);
        var state = MacroRecorder.class.getDeclaredField("state");
        state.setAccessible(true);
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object recording = Enum.valueOf((Class) state.getType(), "RECORDING");
        state.set(recorder, recording);
        var lastTime = MacroRecorder.class.getDeclaredField("lastTime");
        lastTime.setAccessible(true);
        lastTime.setLong(recorder, System.currentTimeMillis());
        AtomicReference<MacroRecorder.ImageCaptureSession> session = new AtomicReference<>();
        recorder.setOnImageCaptureRequested(session::set);
        recorder.nativeKeyPressed(key(NativeKeyEvent.NATIVE_KEY_PRESSED, NativeKeyEvent.VC_A));
        recorder.nativeMousePressed(new NativeMouseEvent(NativeMouseEvent.NATIVE_MOUSE_PRESSED,
                0, 600, 600, 1, NativeMouseEvent.BUTTON1));
        check(recorder.pause(), "暂停失败");
        check(recorder.getActions().size() == 4, "暂停没有配对释放已有键鼠");
        check(recorder.getActions().stream().anyMatch(action -> action.getType() == ActionType.KEY_RELEASE), "缺少键盘释放");
        check(recorder.getActions().stream().anyMatch(action -> action.getType() == ActionType.MOUSE_RELEASE), "缺少鼠标释放");
        int before = recorder.getActions().size();
        recorder.nativeKeyReleased(key(NativeKeyEvent.NATIVE_KEY_RELEASED, NativeKeyEvent.VC_A));
        recorder.nativeKeyPressed(key(NativeKeyEvent.NATIVE_KEY_PRESSED, NativeKeyEvent.VC_B));
        Thread.sleep(150);
        check(recorder.requestImageCapture(), "暂停时无法截图");
        check(recorder.getRecordingStatus().paused() && recorder.getRecordingStatus().capturing(), "截图前暂停状态丢失");
        Thread.sleep(150);
        check(recorder.completeImageCapture(session.get().id(), new ImageCheckConfig("fixture.png", 0.9)), "图像节点提交失败");
        check(recorder.getRecordingStatus().paused(), "暂停截图后错误继续录制");
        check(recorder.getActions().size() == before, "暂停或截图输入污染动作");
        check(recorder.getRecordedNodes().stream().filter(node -> node.getType() == NodeType.IMAGE_CHECK).count() == 1,
                "图像节点未进入现有流程");
        check(recorder.resume(), "继续失败");
        // 暂停期间按下的 B 不能以孤立释放进入宏。
        recorder.nativeKeyReleased(key(NativeKeyEvent.NATIVE_KEY_RELEASED, NativeKeyEvent.VC_B));
        check(recorder.getActions().size() == before, "恢复后出现暂停期间按键的孤立释放");
        recorder.nativeKeyPressed(key(NativeKeyEvent.NATIVE_KEY_PRESSED, NativeKeyEvent.VC_C));
        check(recorder.getActions().get(before).getDelay() < 200, "暂停和截图耗时未排除");
        recorder.nativeKeyReleased(key(NativeKeyEvent.NATIVE_KEY_RELEASED, NativeKeyEvent.VC_C));
        check(recorder.requestImageCapture(), "正常录制时无法截图");
        check(recorder.cancelImageCapture(session.get().id()), "截图取消失败");
        check(!recorder.getRecordingStatus().paused() && !recorder.getRecordingStatus().capturing(), "取消后未恢复录制");
        check(recorder.pause() && recorder.requestImageCapture(), "第二次暂停截图失败");
        check(recorder.cancelImageCapture(session.get().id()) && recorder.getRecordingStatus().paused(), "取消后暂停状态丢失");
        check(recorder.requestImageCapture(), "暂停截图无法再次进入");
        long stale = session.get().id();
        AtomicInteger stops = new AtomicInteger();
        recorder.setOnStop(stops::incrementAndGet);
        recorder.nativeKeyPressed(key(NativeKeyEvent.NATIVE_KEY_PRESSED, NativeKeyEvent.VC_F12));
        recorder.stop();
        check(stops.get() == 1 && !recorder.getRecordingStatus().recording(), "F12 没有统一停止或重复回调");
        check(!recorder.cancelImageCapture(stale), "停止后旧截图回调重新开启录制");
        check(!recorder.getActions().isEmpty(), "停止清空了动作");
        MacroRecorder controls = new MacroRecorder(config);
        state.set(controls, recording);
        lastTime.setLong(controls, System.currentTimeMillis());
        controls.setControlRegionSupplier(() -> new RecordingControlInputFilter.Region(new java.awt.Rectangle(100, 100, 500, 100), true));
        controls.nativeMousePressed(new NativeMouseEvent(NativeMouseEvent.NATIVE_MOUSE_PRESSED, 0, 200, 150, 1, NativeMouseEvent.BUTTON1));
        controls.nativeKeyPressed(key(NativeKeyEvent.NATIVE_KEY_PRESSED, NativeKeyEvent.VC_SPACE));
        controls.setControlRegionSupplier(() -> RecordingControlInputFilter.Region.EMPTY);
        Thread.sleep(30);
        controls.nativeMouseReleased(new NativeMouseEvent(NativeMouseEvent.NATIVE_MOUSE_RELEASED, 0, 800, 500, 1, NativeMouseEvent.BUTTON1));
        controls.nativeKeyReleased(key(NativeKeyEvent.NATIVE_KEY_RELEASED, NativeKeyEvent.VC_SPACE));
        check(controls.getActions().isEmpty(), "控制栏按下/隐藏后释放被写入宏");
        controls.nativeMousePressed(new NativeMouseEvent(NativeMouseEvent.NATIVE_MOUSE_PRESSED, 0, 800, 500, 1, NativeMouseEvent.BUTTON1));
        controls.nativeMouseReleased(new NativeMouseEvent(NativeMouseEvent.NATIVE_MOUSE_RELEASED, 0, 800, 500, 1, NativeMouseEvent.BUTTON1));
        controls.nativeKeyPressed(key(NativeKeyEvent.NATIVE_KEY_PRESSED, NativeKeyEvent.VC_D));
        controls.nativeKeyReleased(key(NativeKeyEvent.NATIVE_KEY_RELEASED, NativeKeyEvent.VC_D));
        check(controls.getActions().size() == 4, "控制栏之外的普通键鼠未正常录入");
        controls.stop();
        System.out.println("通过：暂停/继续、键鼠配对、截图成功/取消保持暂停、时间补偿、F12 及过期回调");
    }
    private static NativeKeyEvent key(int type, int code) {
        return new NativeKeyEvent(type, 0, code, code, NativeKeyEvent.CHAR_UNDEFINED);
    }
    private static void check(boolean result, String message) { if (!result) throw new AssertionError(message); }
}
