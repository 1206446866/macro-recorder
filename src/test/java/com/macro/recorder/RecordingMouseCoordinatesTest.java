package com.macro.recorder;

import com.github.kwhat.jnativehook.mouse.NativeMouseEvent;
import com.github.kwhat.jnativehook.mouse.NativeMouseWheelEvent;
import com.macro.config.AppConfig;
import com.macro.model.ActionType;
import com.macro.model.MacroAction;
import com.macro.util.WindowsMouseUtil;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef;

import java.awt.Point;
import java.awt.Rectangle;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 用合成事件与模拟指针验证排队坐标，不注册钩子、不移动鼠标、不创建窗口。 */
public final class RecordingMouseCoordinatesTest {
    public static void main(String[] args) throws Exception {
        // AppConfig 会创建配置，必须先确认 TEST-01 已隔离生产类来源。
        Path classes = Path.of(AppConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!classes.getFileName().toString().equals("classes")
                || !classes.getParent().getFileName().toString().startsWith("RecordingMouseCoordinatesTest-")) {
            throw new AssertionError("请通过隔离测试入口运行，不能使用个人应用目录");
        }
        pressAndReleaseKeepEventPosition();
        movementAndWheelKeepEventPosition();
        targetHitUsesPhysicalEventPosition();
        controlFilteringKeepsPairing();
        movementSamplingIsUnchanged();
        captureStillQueriesCurrentPosition();
        System.out.println("通过：事件 A/当前 B、按下释放、移动滚轮、目标命中、控件配对、采样及截图即时坐标");
    }

    private static void pressAndReleaseKeepEventPosition() throws Exception {
        Fixture fixture = new Fixture(1.5);
        NativeMouseEvent press = mouse(NativeMouseEvent.NATIVE_MOUSE_PRESSED, -301, 451);
        NativeMouseEvent release = mouse(NativeMouseEvent.NATIVE_MOUSE_RELEASED, -299, 454);
        fixture.current.set(new Point(1200, 900));
        fixture.recorder.nativeMousePressed(press);
        fixture.current.set(new Point(1800, 1200));
        fixture.recorder.nativeMouseReleased(release);
        List<MacroAction> actions = fixture.recorder.getActions();
        check(actions.size() == 2, "普通点击动作数量改变");
        position(actions.get(0), ActionType.MOUSE_PRESS, -200, 300);
        position(actions.get(1), ActionType.MOUSE_RELEASE, -199, 302);
        check(fixture.cursorReads.get() == 0, "处理已发生的按下/释放时重新查询了指针");

        Fixture unscaled = new Fixture(1.0);
        unscaled.recorder.nativeMousePressed(mouse(NativeMouseEvent.NATIVE_MOUSE_PRESSED, 246, -357));
        unscaled.recorder.nativeMouseReleased(mouse(NativeMouseEvent.NATIVE_MOUSE_RELEASED, 249, -354));
        position(unscaled.recorder.getActions().get(0), ActionType.MOUSE_PRESS, 246, -357);
        position(unscaled.recorder.getActions().get(1), ActionType.MOUSE_RELEASE, 249, -354);
        check(unscaled.cursorReads.get() == 0, "100% 缩放仍使用当前指针");
    }

    private static void movementAndWheelKeepEventPosition() throws Exception {
        Fixture fixture = new Fixture(1.5);
        NativeMouseEvent move = mouse(NativeMouseEvent.NATIVE_MOUSE_MOVED, -450, 300);
        NativeMouseWheelEvent wheel = wheel(-420, 330);
        fixture.current.set(new Point(1800, 1200));
        fixture.recorder.nativeMouseMoved(move);
        fixture.recorder.nativeMouseWheelMoved(wheel);
        List<MacroAction> actions = fixture.recorder.getActions();
        check(actions.size() == 2, "移动/滚轮动作丢失");
        position(actions.get(0), ActionType.MOUSE_MOVE, -300, 200);
        position(actions.get(1), ActionType.MOUSE_WHEEL, -280, 220);
        check(actions.get(1).getWheelAmount() == -2, "滚轮方向或数量改变");
        check(fixture.cursorReads.get() == 0, "移动/滚轮处理时重新查询了指针");
    }

    private static void targetHitUsesPhysicalEventPosition() throws Exception {
        Fixture fixture = new Fixture(1.5);
        set(fixture.recorder, "targetHwnd", fixture.target);
        fixture.current.set(new Point(1800, 1200));
        fixture.recorder.nativeMousePressed(mouse(NativeMouseEvent.NATIVE_MOUSE_PRESSED, 330, 480));
        fixture.recorder.nativeMouseWheelMoved(wheel(360, 510));
        fixture.recorder.nativeMouseMoved(mouse(NativeMouseEvent.NATIVE_MOUSE_MOVED, 390, 540));
        check(fixture.hits.equals(List.of(new Point(330, 480), new Point(360, 510), new Point(390, 540))),
                "目标命中没有使用事件物理坐标，或提前应用了 DPI 转换");
        position(fixture.recorder.getActions().get(0), ActionType.MOUSE_PRESS, 220, 320);
        position(fixture.recorder.getActions().get(1), ActionType.MOUSE_WHEEL, 240, 340);
        position(fixture.recorder.getActions().get(2), ActionType.MOUSE_MOVE, 260, 360);

        // 已录入的按下允许在目标外释放，配对规则不能因为坐标修复而改变。
        fixture.recorder.nativeMouseReleased(mouse(NativeMouseEvent.NATIVE_MOUSE_RELEASED, 900, 900));
        position(fixture.recorder.getActions().get(3), ActionType.MOUSE_RELEASE, 600, 600);
        check(fixture.hits.size() == 3, "已录入按下的配对释放被重新做目标过滤");

        fixture.current.set(new Point(330, 480));
        fixture.recorder.nativeMousePressed(mouse(NativeMouseEvent.NATIVE_MOUSE_PRESSED, 100, 100));
        fixture.recorder.nativeMouseReleased(mouse(NativeMouseEvent.NATIVE_MOUSE_RELEASED, 100, 100));
        fixture.recorder.nativeMouseMoved(mouse(NativeMouseEvent.NATIVE_MOUSE_MOVED, 100, 100));
        fixture.recorder.nativeMouseWheelMoved(wheel(100, 100));
        check(fixture.recorder.getActions().size() == 4, "当前指针在目标内时错误录入目标外的旧事件");
        check(fixture.hits.size() == 7 && fixture.hits.subList(3, 7).stream()
                .allMatch(point -> point.equals(new Point(100, 100))), "目标外事件没有按自身坐标判断");
        check(fixture.cursorReads.get() == 0, "目标命中查询了当前指针");
    }

    private static void controlFilteringKeepsPairing() throws Exception {
        Fixture fixture = new Fixture(1.5);
        fixture.recorder.setControlRegionSupplier(() -> new RecordingControlInputFilter.Region(
                new Rectangle(1500, 150, 750, 105), false));
        fixture.current.set(new Point(600, 600));
        fixture.recorder.nativeMousePressed(mouse(NativeMouseEvent.NATIVE_MOUSE_PRESSED, 1600, 180));
        fixture.recorder.nativeMouseReleased(mouse(NativeMouseEvent.NATIVE_MOUSE_RELEASED, 600, 600));
        fixture.recorder.nativeMouseMoved(mouse(NativeMouseEvent.NATIVE_MOUSE_MOVED, 1600, 180));
        fixture.recorder.nativeMouseWheelMoved(wheel(1600, 180));
        check(fixture.recorder.getActions().isEmpty(), "控件内事件或其跨区域释放泄漏");

        fixture.current.set(new Point(1600, 180));
        fixture.recorder.nativeMousePressed(mouse(NativeMouseEvent.NATIVE_MOUSE_PRESSED, 600, 600));
        fixture.recorder.nativeMouseReleased(mouse(NativeMouseEvent.NATIVE_MOUSE_RELEASED, 1650, 180));
        List<MacroAction> actions = fixture.recorder.getActions();
        check(actions.size() == 2, "控件外按下及控件内配对释放被误删");
        position(actions.get(0), ActionType.MOUSE_PRESS, 400, 400);
        position(actions.get(1), ActionType.MOUSE_RELEASE, 1100, 120);
        check(fixture.cursorReads.get() == 0, "控件配对使用了不同时间点的坐标");
    }

    private static void movementSamplingIsUnchanged() throws Exception {
        Fixture fixture = new Fixture(1.5);
        fixture.config.setMouseMoveInterval(Integer.MAX_VALUE);
        set(fixture.recorder, "lastMouseMoveTime", System.currentTimeMillis());
        fixture.recorder.nativeMouseMoved(mouse(NativeMouseEvent.NATIVE_MOUSE_MOVED, 300, 450));
        check(fixture.recorder.getActions().isEmpty(), "采样间隔被绕过");
        fixture.config.setMouseMoveInterval(0);
        fixture.recorder.nativeMouseMoved(mouse(NativeMouseEvent.NATIVE_MOUSE_MOVED, 330, 480));
        position(fixture.recorder.getActions().get(0), ActionType.MOUSE_MOVE, 220, 320);
        fixture.config.setRecordMouseMove(false);
        fixture.recorder.nativeMouseMoved(mouse(NativeMouseEvent.NATIVE_MOUSE_MOVED, 360, 510));
        check(fixture.recorder.getActions().size() == 1, "关闭轨迹录制后仍写入移动动作");
    }

    private static void captureStillQueriesCurrentPosition() throws Exception {
        Fixture fixture = new Fixture(1.5);
        fixture.config.setRecordMouseMove(false);
        AtomicReference<MacroRecorder.ImageCaptureSession> session = new AtomicReference<>();
        fixture.recorder.setOnImageCaptureRequested(session::set);
        fixture.current.set(new Point(930, 645));
        check(fixture.recorder.requestImageCapture(), "无法进入模拟截图会话");
        check(session.get().startX() == 620 && session.get().startY() == 430,
                "截图边界不再使用当下位置");
        check(fixture.recorder.cancelImageCapture(session.get().id()), "截图取消失败");
        fixture.recorder.nativeMousePressed(mouse(NativeMouseEvent.NATIVE_MOUSE_PRESSED, 300, 450));
        position(fixture.recorder.getActions().get(0), ActionType.MOUSE_PRESS, 200, 300);
        check(fixture.cursorReads.get() == 1, "截图后的普通事件又查询了当前指针");
    }

    private static NativeMouseEvent mouse(int type, int x, int y) {
        return new NativeMouseEvent(type, 0, x, y, 1, NativeMouseEvent.BUTTON1);
    }

    private static NativeMouseWheelEvent wheel(int x, int y) {
        return new NativeMouseWheelEvent(NativeMouseEvent.NATIVE_MOUSE_WHEEL, 0, x, y, 0,
                NativeMouseWheelEvent.WHEEL_UNIT_SCROLL, 3, -2);
    }

    private static void position(MacroAction action, ActionType type, int x, int y) {
        check(action.getType() == type && action.getX() == x && action.getY() == y,
                "动作坐标错误：" + action.getType() + " (" + action.getX() + ", " + action.getY() + ")");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void set(Object owner, String name, Object value) throws Exception {
        var field = MacroRecorder.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    /** 两种屏幕位置来源完全独立，确保旧实现读取 B 时检查会失败。 */
    private static final class Fixture {
        final AppConfig config = new AppConfig();
        final AtomicReference<Point> current = new AtomicReference<>(new Point(1800, 1200));
        final AtomicInteger cursorReads = new AtomicInteger();
        final List<Point> hits = new ArrayList<>();
        final WinDef.HWND target = new WinDef.HWND(Pointer.createConstant(101));
        final WinDef.HWND outside = new WinDef.HWND(Pointer.createConstant(202));
        final MacroRecorder recorder;

        Fixture(double scale) throws Exception {
            config.setRecordMouseMove(true);
            config.setMouseMoveInterval(0);
            recorder = new MacroRecorder(config, scale, () -> {
                cursorReads.incrementAndGet();
                Point value = current.get();
                WindowsMouseUtil.POINT point = new WindowsMouseUtil.POINT();
                point.x = value.x;
                point.y = value.y;
                return point;
            }, (x, y) -> {
                hits.add(new Point(x, y));
                return new Rectangle(300, 450, 300, 300).contains(x, y) ? target : outside;
            });
            var state = MacroRecorder.class.getDeclaredField("state");
            state.setAccessible(true);
            state.set(recorder, Arrays.stream(state.getType().getEnumConstants())
                    .filter(value -> value.toString().equals("RECORDING")).findFirst().orElseThrow());
            set(recorder, "lastTime", System.currentTimeMillis());
        }
    }
}
