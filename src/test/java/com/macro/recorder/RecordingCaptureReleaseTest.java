package com.macro.recorder;

import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.mouse.NativeMouseEvent;
import com.macro.config.AppConfig;
import com.macro.flow.ImageCheckConfig;
import com.macro.flow.NodeType;
import com.macro.model.ActionType;
import com.macro.util.WindowsMouseUtil;
import java.awt.Rectangle;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

/** 隔离配置、模拟状态与发送；不注册 Hook、不截图、不改变真实键鼠输入。 */
public final class RecordingCaptureReleaseTest {
    public static void main(String[] args) throws Exception {
        Path classes = Path.of(AppConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        check(classes.getParent().getFileName().toString().startsWith("RecordingCaptureReleaseTest-"), "必须使用隔离配置目录");
        releasePrecedesCaptureAndKeepsPairing();
        cancellationDoesNotRepressOrDuplicateRelease();
        pausedCaptureReleasesUnrecordedButtonAndStaysPaused();
        ignoredControlPressStillGetsReleased();
        allButtonsAndKeyboardKeepTheirOwnRules();
        staleSessionsAndRepeatedPrepareNeverResend();
        failedReleaseCanCancelAndRetryWithoutCreatingImage();
        ordinaryPauseAndMissingCallbackDoNotSendInput();
        System.out.println("通过：F10/按钮截图前释放、配对/重复释放、暂停/取消、控件按下、三键、会话过期及失败恢复");
    }

    private static void releasePrecedesCaptureAndKeepsPairing() throws Exception {
        Fixture f = new Fixture(); f.press(NativeMouseEvent.BUTTON1); f.down(1);
        f.recorder.nativeKeyPressed(key(NativeKeyEvent.VC_F10));
        check(f.recorder.getRecordingStatus().capturing(), "F10 没有进入采集状态");
        check(f.recorder.getActions().size() == 2, "请求截图前没有闭合鼠标按下");
        f.onSend = flags -> {
            check(f.recorder.getRecordingStatus().capturing(), "发送释放前未挂起录制");
            f.release(NativeMouseEvent.BUTTON1);
            check(f.recorder.getActions().size() == 2, "自动释放重复录入宏");
        };
        check(f.prepare(), "准备失败"); f.order.add("capture");
        check(f.order.equals(List.of("release", "capture")) && f.sent.equals(List.of(4)), "释放未先于截图");
        check(f.recorder.completeImageCapture(f.id(), new ImageCheckConfig("fixture.png", .9)), "提交失败");
        check(f.recorder.getRecordedNodes().stream().map(node -> node.getType()).toList().equals(List.of(NodeType.ACTION, NodeType.ACTION, NodeType.IMAGE_CHECK)), "释放节点不在识图前");
        var actions = f.recorder.getActions();
        check(actions.get(0).getType() == ActionType.MOUSE_PRESS && actions.get(1).getType() == ActionType.MOUSE_RELEASE, "鼠标动作不配对");
        check(actions.get(1).getX() == 100 && actions.get(1).getY() == 200, "配对释放坐标改变");
        f.release(NativeMouseEvent.BUTTON1); f.release(NativeMouseEvent.BUTTON1); check(f.recorder.getActions().size() == 2, "随后真实/重复释放污染宏");
        f.press(NativeMouseEvent.BUTTON1); f.release(NativeMouseEvent.BUTTON1); check(f.recorder.getActions().size() == 4, "新的点击未恢复");
    }

    private static void cancellationDoesNotRepressOrDuplicateRelease() throws Exception {
        Fixture f = new Fixture(); f.press(NativeMouseEvent.BUTTON2); f.down(2); f.request(); check(f.prepare(), "准备失败");
        check(f.recorder.cancelImageCapture(f.id()), "取消失败");
        f.release(NativeMouseEvent.BUTTON2); f.release(NativeMouseEvent.BUTTON2);
        check(f.recorder.getActions().size() == 2 && f.sent.equals(List.of(16)), "取消后重复释放或重新按下");
        check(f.recorder.getRecordedNodes().stream().noneMatch(node -> node.getType() == NodeType.IMAGE_CHECK), "取消增加图像节点");
        check(!f.recorder.getRecordingStatus().paused() && !f.recorder.getRecordingStatus().capturing(), "取消后未恢复录制");
        f.press(NativeMouseEvent.BUTTON2); f.release(NativeMouseEvent.BUTTON2); check(f.recorder.getActions().size() == 4, "取消后新点击失效");
    }

    private static void pausedCaptureReleasesUnrecordedButtonAndStaysPaused() throws Exception {
        Fixture f = new Fixture(); check(f.recorder.pause(), "暂停失败"); f.down(2); f.request();
        check(f.prepare() && f.sent.equals(List.of(16)), "暂停期间未录入的按下未实际松开");
        check(f.recorder.completeImageCapture(f.id(), new ImageCheckConfig("paused.png", .9)), "暂停截图完成失败");
        check(f.recorder.getRecordingStatus().paused() && f.recorder.getActions().isEmpty(), "暂停状态丢失或出现无来源释放");
        check(f.recorder.resume(), "恢复失败"); f.release(NativeMouseEvent.BUTTON2); check(f.recorder.getActions().isEmpty(), "恢复后出现无来源释放");
        f.press(NativeMouseEvent.BUTTON2); f.release(NativeMouseEvent.BUTTON2); check(f.recorder.getActions().size() == 2, "恢复后新点击失效");
    }

    private static void ignoredControlPressStillGetsReleased() throws Exception {
        Fixture f = new Fixture(); f.recorder.setControlRegionSupplier(() -> new RecordingControlInputFilter.Region(new Rectangle(50, 100, 150, 150), false));
        f.press(NativeMouseEvent.BUTTON1); check(f.recorder.getActions().isEmpty(), "控件按下进入宏"); f.down(1); f.request();
        check(f.prepare() && f.sent.equals(List.of(4)), "被忽略的控件按下未实际释放");
        f.recorder.cancelImageCapture(f.id()); f.release(NativeMouseEvent.BUTTON1); check(f.recorder.getActions().isEmpty(), "控件自动释放进入宏");
        f.recorder.setControlRegionSupplier(() -> RecordingControlInputFilter.Region.EMPTY);
        f.recorder.nativeMousePressed(new NativeMouseEvent(NativeMouseEvent.NATIVE_MOUSE_PRESSED, 0, 700, 500, 1, NativeMouseEvent.BUTTON1));
        f.release(NativeMouseEvent.BUTTON1); check(f.recorder.getActions().size() == 2, "控件外新点击被屏蔽");
    }

    private static void allButtonsAndKeyboardKeepTheirOwnRules() throws Exception {
        Fixture f = new Fixture(); for (int button : new int[]{NativeMouseEvent.BUTTON1, NativeMouseEvent.BUTTON2, NativeMouseEvent.BUTTON3}) f.press(button);
        f.recorder.nativeKeyPressed(key(NativeKeyEvent.VC_A)); f.down(1, 2, 4); f.request();
        check(f.recorder.getActions().size() == 8, "没有闭合原有全部键鼠动作");
        check(f.prepare() && f.sent.equals(List.of(0x54)), "三键未一次释放");
        check(f.queries.equals(List.of(1, 2, 4)), "查询了键盘或侧键"); f.recorder.cancelImageCapture(f.id());
        for (int button : new int[]{NativeMouseEvent.BUTTON1, NativeMouseEvent.BUTTON2, NativeMouseEvent.BUTTON3}) f.release(button);
        check(f.recorder.getActions().size() == 8, "三键尾部释放重复录入");
    }

    private static void staleSessionsAndRepeatedPrepareNeverResend() throws Exception {
        Fixture f = new Fixture(); f.down(1); f.request(); long id = f.id();
        check(!f.recorder.prepareImageCapture(id + 10) && f.queries.isEmpty(), "错误会话发送输入");
        check(f.prepare() && f.prepare() && f.sent.size() == 1 && f.queries.size() == 3, "重复准备重复查询/发送");
        f.recorder.cancelImageCapture(id); check(!f.recorder.prepareImageCapture(id), "结束会话仍发送输入");
        f.request(); long next = f.id(); check(!f.recorder.prepareImageCapture(id), "旧会话穿透新会话");
        f.recorder.stop(); check(!f.recorder.prepareImageCapture(next) && f.sent.size() == 1, "停止后仍发送输入");
    }

    private static void failedReleaseCanCancelAndRetryWithoutCreatingImage() throws Exception {
        Fixture f = new Fixture(); f.press(NativeMouseEvent.BUTTON1); f.down(1); f.result = 0; f.request(); boolean failed = false;
        try { f.prepare(); } catch (IllegalStateException expected) { failed = true; }
        check(failed, "释放失败仍允许截图"); f.recorder.cancelImageCapture(f.id()); f.release(NativeMouseEvent.BUTTON1);
        check(!f.recorder.getRecordingStatus().capturing() && f.recorder.getActions().size() == 2, "失败取消未恢复或重复录入释放");
        check(f.recorder.getRecordedNodes().stream().noneMatch(node -> node.getType() == NodeType.IMAGE_CHECK), "失败增加图像节点");
        f.result = 1; f.request(); check(f.prepare(), "失败后无法再截图"); f.recorder.cancelImageCapture(f.id());
    }

    private static void ordinaryPauseAndMissingCallbackDoNotSendInput() throws Exception {
        Fixture f = new Fixture(); f.press(NativeMouseEvent.BUTTON1); f.down(1); f.recorder.pause();
        check(f.queries.isEmpty() && f.sent.isEmpty(), "普通暂停改变真实输入"); f.recorder.resume();
        f.recorder.setOnImageCaptureRequested(null); check(!f.recorder.requestImageCapture() && f.queries.isEmpty() && f.sent.isEmpty(), "无截图回调时发送输入");
    }

    private static final class Fixture {
        final Map<Integer, Integer> states = new HashMap<>(); final List<Integer> queries = new ArrayList<>(), sent = new ArrayList<>();
        final List<String> order = new ArrayList<>(); final AtomicReference<MacroRecorder.ImageCaptureSession> session = new AtomicReference<>();
        final MacroRecorder recorder; int result = 1; IntConsumer onSend = flags -> {};
        Fixture() throws Exception {
            AppConfig config = new AppConfig(); config.setRecordMouseMove(false);
            CaptureMouseRelease release = new CaptureMouseRelease(key -> { queries.add(key); return states.getOrDefault(key, 0); }, flags -> {
                sent.add(flags); order.add("release"); onSend.accept(flags); return result;
            });
            recorder = new MacroRecorder(config, 1.0, () -> { WindowsMouseUtil.POINT point = new WindowsMouseUtil.POINT(); point.x = 600; point.y = 400; return point; }, (x, y) -> null, release);
            var state = MacroRecorder.class.getDeclaredField("state"); state.setAccessible(true);
            state.set(recorder, Arrays.stream(state.getType().getEnumConstants()).filter(value -> value.toString().equals("RECORDING")).findFirst().orElseThrow());
            var time = MacroRecorder.class.getDeclaredField("lastTime"); time.setAccessible(true); time.setLong(recorder, System.currentTimeMillis());
            recorder.setOnImageCaptureRequested(session::set);
        }
        long id() { return session.get().id(); }
        void request() { check(recorder.requestImageCapture(), "请求截图失败"); }
        boolean prepare() { return recorder.prepareImageCapture(id()); }
        void down(int... keys) { for (int key : keys) states.put(key, 0x8000); }
        void press(int button) { recorder.nativeMousePressed(new NativeMouseEvent(NativeMouseEvent.NATIVE_MOUSE_PRESSED, 0, 100, 200, 1, button)); }
        void release(int button) { recorder.nativeMouseReleased(new NativeMouseEvent(NativeMouseEvent.NATIVE_MOUSE_RELEASED, 0, 700, 500, 1, button)); }
    }
    private static NativeKeyEvent key(int code) { return new NativeKeyEvent(NativeKeyEvent.NATIVE_KEY_PRESSED, 0, code, code, NativeKeyEvent.CHAR_UNDEFINED); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
