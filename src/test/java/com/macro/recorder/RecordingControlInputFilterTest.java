package com.macro.recorder;

import com.github.kwhat.jnativehook.NativeInputEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.mouse.NativeMouseEvent;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** 使用原生事件模型测试延迟派发、物理像素区域和成对过滤，不操作真实鼠标。 */
public final class RecordingControlInputFilterTest {
    public static void main(String[] args) throws Exception {
        AtomicLong clock = new AtomicLong(100);
        AtomicReference<RecordingControlInputFilter.Region> region = new AtomicReference<>(
                new RecordingControlInputFilter.Region(new Rectangle(1500, 150, 750, 105), true));
        RecordingControlInputFilter filter = new RecordingControlInputFilter(clock::get);
        filter.setRegionSupplier(region::get);
        filter.reset();
        clock.set(110);
        check(filter.ignoreMousePress(mouse(NativeMouseEvent.NATIVE_MOUSE_PRESSED, 1600, 180, 110)), "控制栏按下被录入");
        clock.set(120);
        region.set(RecordingControlInputFilter.Region.EMPTY);
        filter.refreshRegion();
        check(filter.ignoreMouseMotion(mouse(NativeMouseEvent.NATIVE_MOUSE_MOVED, 800, 800, 125)), "标题栏拖动泄漏");
        check(filter.ignoreMouseRelease(mouse(NativeMouseEvent.NATIVE_MOUSE_RELEASED, 800, 800, 125), false), "隐藏后释放泄漏");
        check(!filter.ignoreMousePress(mouse(NativeMouseEvent.NATIVE_MOUSE_PRESSED, 1600, 180, 125)), "最小化后的原位置错误屏蔽");
        check(!filter.ignoreMouseRelease(mouse(NativeMouseEvent.NATIVE_MOUSE_RELEASED, 1600, 180, 126), true), "外部释放丢失");
        // UI 已隐藏后才派发原生按下，仍按事件发生时的控制栏矩形判断。
        check(filter.ignoreMousePress(mouse(NativeMouseEvent.NATIVE_MOUSE_PRESSED, 1600, 180, 115)), "队列中的旧按钮按下泄漏");
        check(filter.ignoreMouseRelease(mouse(NativeMouseEvent.NATIVE_MOUSE_RELEASED, 1600, 180, 119), false), "旧按钮释放泄漏");
        check(filter.ignoreMousePress(mouse(NativeMouseEvent.NATIVE_MOUSE_PRESSED, 1600, 180, 120)), "显隐和点击同一计时边界时泄漏");
        check(filter.ignoreMouseRelease(mouse(NativeMouseEvent.NATIVE_MOUSE_RELEASED, 1600, 180, 120), false), "边界释放泄漏");
        clock.set(130);
        region.set(new RecordingControlInputFilter.Region(new Rectangle(1500, 150, 750, 105), true));
        filter.refreshRegion();
        check(!filter.ignoreMousePress(mouse(NativeMouseEvent.NATIVE_MOUSE_PRESSED, 500, 500, 131)), "外部正常按下被过滤");
        check(!filter.ignoreMouseRelease(mouse(NativeMouseEvent.NATIVE_MOUSE_RELEASED, 1600, 180, 132), true), "跨栏释放没有配对");
        check(filter.ignoreKeyPress(key(NativeKeyEvent.NATIVE_KEY_PRESSED, NativeKeyEvent.VC_SPACE, 135), false), "控制栏空格被录入");
        clock.set(140);
        region.set(RecordingControlInputFilter.Region.EMPTY);
        filter.refreshRegion();
        check(filter.ignoreKeyRelease(key(NativeKeyEvent.NATIVE_KEY_RELEASED, NativeKeyEvent.VC_SPACE, 145), false), "跨焦点释放泄漏");
        check(!filter.ignoreKeyPress(key(NativeKeyEvent.NATIVE_KEY_PRESSED, NativeKeyEvent.VC_A, 145), false), "外部键盘被过滤");
        clock.set(150);
        filter.beginSuspension();
        clock.set(200);
        filter.endSuspension();
        clock.set(250);
        check(filter.ignoreMousePress(mouse(NativeMouseEvent.NATIVE_MOUSE_PRESSED, 400, 400, 175)), "恢复后派发的暂停输入泄漏");
        check(filter.ignoreMouseRelease(mouse(NativeMouseEvent.NATIVE_MOUSE_RELEASED, 400, 400, 230), false), "暂停期间按下的释放泄漏");
        check(!filter.ignoreMousePress(mouse(NativeMouseEvent.NATIVE_MOUSE_PRESSED, 400, 400, 240)), "恢复后正常输入丢失");
        check(filter.ignoreMouseMotion(mouse(NativeMouseEvent.NATIVE_MOUSE_MOVED, 400, 400, 90)), "开始前积压事件被录入");
        // Windows 的事件毫秒计数在约 49.7 天后回绕，过滤仍应与同一时间轴对齐。
        clock.set((1L << 32) + 100);
        filter.reset();
        clock.set((1L << 32) + 120);
        filter.beginSuspension();
        clock.set((1L << 32) + 150);
        filter.endSuspension();
        check(filter.ignoreMousePress(mouse(NativeMouseEvent.NATIVE_MOUSE_PRESSED, 400, 400, 130)), "计时回绕后暂停过滤失效");
        verifySeparatedHomeControls();
        verifyStartAndStopDispatchOrder();
        verifyRegionCopies();
        System.out.println("通过：控制栏/主页分离按钮、间隙、移动历史、启动停止时序、物理像素、成对释放、暂停队列及计时回绕");
    }

    /** 模拟 150% 缩放后的四个独立按钮，不能把按钮之间的主页区域一起排除。 */
    private static void verifySeparatedHomeControls() throws Exception {
        AtomicLong clock = new AtomicLong(300);
        List<Rectangle> areas = List.of(
                new Rectangle(150, 900, 210, 60),
                new Rectangle(390, 900, 210, 60),
                new Rectangle(630, 900, 210, 60),
                new Rectangle(870, 900, 210, 60));
        AtomicReference<RecordingControlInputFilter.Region> region = new AtomicReference<>(
                new RecordingControlInputFilter.Region(areas, false));
        RecordingControlInputFilter filter = new RecordingControlInputFilter(clock::get);
        filter.setRegionSupplier(region::get);
        filter.reset();
        for (Rectangle area : areas) {
            check(filter.ignoreMousePress(mouse(NativeMouseEvent.NATIVE_MOUSE_PRESSED, area.x + 20, area.y + 20, 300)),
                    "主页录制操作按钮按下泄漏");
            check(filter.ignoreMouseRelease(mouse(NativeMouseEvent.NATIVE_MOUSE_RELEASED, area.x + 20, area.y + 20, 300), false),
                    "主页录制操作按钮释放泄漏");
        }
        check(!filter.ignoreMousePress(mouse(NativeMouseEvent.NATIVE_MOUSE_PRESSED, 375, 930, 300)),
                "按钮之间的合法主页操作被过滤");
        check(!filter.ignoreMouseRelease(mouse(NativeMouseEvent.NATIVE_MOUSE_RELEASED, 375, 930, 300), true),
                "主页按钮间隙的释放丢失");
        check(!filter.containsCurrentPoint(375, 930), "截图桥接错误使用了多个按钮的外接矩形");
        check(region.get().bounds().contains(375, 930), "兼容外接矩形没有覆盖所有按钮");
        check(!filter.ignoreKeyPress(key(NativeKeyEvent.NATIVE_KEY_PRESSED, NativeKeyEvent.VC_A, 300), false),
                "主页普通键盘输入被扩大过滤");
        check(!filter.ignoreKeyRelease(key(NativeKeyEvent.NATIVE_KEY_RELEASED, NativeKeyEvent.VC_A, 300), true),
                "主页普通键盘释放被扩大过滤");

        clock.set(320);
        List<Rectangle> moved = areas.stream().map(area -> new Rectangle(area.x + 1200, area.y - 300, area.width, area.height)).toList();
        region.set(new RecordingControlInputFilter.Region(moved, false));
        filter.refreshRegion();
        clock.set(330);
        check(filter.ignoreMousePress(mouse(NativeMouseEvent.NATIVE_MOUSE_PRESSED, 170, 920, 310)),
                "窗口移动后积压的旧位置按下泄漏");
        check(filter.ignoreMouseRelease(mouse(NativeMouseEvent.NATIVE_MOUSE_RELEASED, 170, 920, 315), false),
                "窗口移动后积压的旧位置释放泄漏");
        check(!filter.ignoreMouseMotion(mouse(NativeMouseEvent.NATIVE_MOUSE_MOVED, 170, 920, 330)),
                "窗口移动后旧按钮位置仍被过滤");
        check(filter.ignoreMouseMotion(mouse(NativeMouseEvent.NATIVE_MOUSE_MOVED, 1370, 620, 330)),
                "窗口移动后新的物理按钮位置没有过滤");
        check(!filter.containsCurrentPoint(1575, 630), "移动后的按钮间隙被错误过滤");
    }

    /** 按钮回调可能先于排队的原生释放运行，启动与隐藏同毫秒时仍须保留按钮边界。 */
    private static void verifyStartAndStopDispatchOrder() throws Exception {
        AtomicLong clock = new AtomicLong(500);
        RecordingControlInputFilter.Region controls = new RecordingControlInputFilter.Region(
                List.of(new Rectangle(150, 900, 210, 60), new Rectangle(630, 900, 210, 60)), false);
        AtomicReference<RecordingControlInputFilter.Region> region = new AtomicReference<>(controls);
        RecordingControlInputFilter filter = new RecordingControlInputFilter(clock::get);
        filter.setRegionSupplier(region::get);
        region.set(RecordingControlInputFilter.Region.EMPTY);
        filter.reset();
        check(filter.ignoreMouseRelease(mouse(NativeMouseEvent.NATIVE_MOUSE_RELEASED, 170, 920, 500), false),
                "启动并最小化同毫秒时，延迟派发的启动按钮释放泄漏");
        check(filter.ignoreMousePress(mouse(NativeMouseEvent.NATIVE_MOUSE_PRESSED, 170, 920, 499)),
                "启动前排队的按钮按下被录入");
        clock.set(510);
        check(filter.ignoreMouseRelease(mouse(NativeMouseEvent.NATIVE_MOUSE_RELEASED, 500, 500, 501), false),
                "启动按钮按下在启动前、释放在启动后时没有成对过滤");
        check(!filter.ignoreMousePress(mouse(NativeMouseEvent.NATIVE_MOUSE_PRESSED, 500, 500, 510)),
                "启动后外部的正常鼠标输入被误伤");
        check(!filter.ignoreMouseRelease(mouse(NativeMouseEvent.NATIVE_MOUSE_RELEASED, 500, 500, 510), true),
                "启动后外部鼠标释放丢失");

        clock.set(600);
        filter.reset();
        check(!filter.ignoreMouseMotion(mouse(NativeMouseEvent.NATIVE_MOUSE_MOVED, 170, 920, 600)),
                "新录制错误保留了上一会话的历史按钮边界");
        clock.set(650);
        region.set(controls);
        filter.refreshRegion();
        clock.set(660);
        region.set(RecordingControlInputFilter.Region.EMPTY);
        filter.refreshRegion();
        check(filter.ignoreMousePress(mouse(NativeMouseEvent.NATIVE_MOUSE_PRESSED, 650, 920, 655)),
                "停止按钮回调先运行后，排队的原生按下泄漏");
        check(filter.ignoreMouseRelease(mouse(NativeMouseEvent.NATIVE_MOUSE_RELEASED, 500, 500, 665), false),
                "停止按钮的释放没有成对过滤");
    }

    /** 矩形列表及其元素都不能从外部改变，否则事件历史会被事后改写。 */
    private static void verifyRegionCopies() throws Exception {
        AtomicLong clock = new AtomicLong(700);
        Rectangle original = new Rectangle(150, 900, 210, 60);
        List<Rectangle> originals = new ArrayList<>(List.of(original));
        RecordingControlInputFilter.Region region = new RecordingControlInputFilter.Region(originals, false);
        original.setBounds(0, 0, 10, 10);
        originals.clear();
        region.areas().get(0).setBounds(0, 0, 10, 10);
        region.bounds().setBounds(0, 0, 10, 10);
        RecordingControlInputFilter filter = new RecordingControlInputFilter(clock::get);
        filter.setRegionSupplier(() -> region);
        filter.reset();
        check(filter.containsCurrentPoint(170, 920), "外部修改矩形破坏了过滤快照");
        check(!filter.containsCurrentPoint(5, 5), "外部修改矩形扩大了过滤区域");
        check(filter.ignoreMousePress(mouse(NativeMouseEvent.NATIVE_MOUSE_PRESSED, 170, 920, 700)),
                "防御复制后原有按钮区域没有过滤");
    }

    private static NativeMouseEvent mouse(int type, int x, int y, long when) throws Exception {
        return stamp(new NativeMouseEvent(type, 0, x, y, 1, NativeMouseEvent.BUTTON1), when);
    }
    private static NativeKeyEvent key(int type, int code, long when) throws Exception {
        return stamp(new NativeKeyEvent(type, 0, code, code, NativeKeyEvent.CHAR_UNDEFINED), when);
    }
    private static <T extends NativeInputEvent> T stamp(T event, long when) throws Exception {
        var field = NativeInputEvent.class.getDeclaredField("when");
        field.setAccessible(true);
        field.setLong(event, when);
        return event;
    }
    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }
}
