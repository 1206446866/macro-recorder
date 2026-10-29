package com.macro.recorder;

import com.github.kwhat.jnativehook.mouse.NativeMouseEvent;
import com.sun.jna.platform.win32.WinUser;
import org.junit.jupiter.api.Test;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

/** 状态与发送接口均为替身，不查询或改变真实鼠标状态。 */
class CaptureMouseReleaseTest {
    @Test void noHeldButtonsOrOnlyRecentPressBitsSendNothing() {
        for (int state : new int[]{0, 1}) {
            List<Integer> guarded = new ArrayList<>(); AtomicInteger sent = new AtomicInteger();
            new CaptureMouseRelease(key -> state, flags -> { sent.incrementAndGet(); return 1; }).releasePressedButtons(guarded::add);
            assertTrue(guarded.isEmpty()); assertEquals(0, sent.get());
        }
    }

    @Test void eachPhysicalButtonUsesTheMatchingUpFlag() {
        int[] keys = {1, 2, 4}, buttons = {NativeMouseEvent.BUTTON1, NativeMouseEvent.BUTTON2, NativeMouseEvent.BUTTON3}, flags = {4, 16, 64};
        for (int i = 0; i < keys.length; i++) {
            int index = i; List<Integer> guarded = new ArrayList<>(), sent = new ArrayList<>();
            new CaptureMouseRelease(key -> key == keys[index] ? (short) 0x8001 : 0, value -> { sent.add(value); return 1; }).releasePressedButtons(guarded::add);
            assertEquals(List.of(buttons[index]), guarded); assertEquals(List.of(flags[index]), sent);
        }
    }

    @Test void guardsAllHeldButtonsBeforeOneCombinedRelease() {
        List<Integer> queries = new ArrayList<>(), guarded = new ArrayList<>(); AtomicInteger sends = new AtomicInteger();
        new CaptureMouseRelease(key -> { queries.add(key); return 0x8000; }, flags -> {
            assertEquals(List.of(NativeMouseEvent.BUTTON1, NativeMouseEvent.BUTTON2, NativeMouseEvent.BUTTON3), guarded);
            assertEquals(0x54, flags); sends.incrementAndGet(); return 1;
        }).releasePressedButtons(guarded::add);
        assertEquals(List.of(1, 2, 4), queries); assertEquals(1, sends.get());
    }

    @Test void nativeStructureHasOnlyUpsAndNoMovementWheelOrKeyboard() {
        WinUser.INPUT[] inputs = CaptureMouseRelease.encodeMouseUp(0x54);
        assertEquals(1, inputs.length); WinUser.INPUT input = inputs[0]; input.write(); input.read();
        assertEquals(WinUser.INPUT.INPUT_MOUSE, input.type.intValue()); assertEquals(0x54, input.input.mi.dwFlags.intValue());
        assertEquals(0, input.input.mi.dx.intValue()); assertEquals(0, input.input.mi.dy.intValue());
        assertEquals(0, input.input.mi.mouseData.intValue()); assertEquals(0, input.input.mi.time.intValue()); assertEquals(0, input.input.mi.dwExtraInfo.longValue());
        for (int invalid : new int[]{0, 1, 2, 8, 32, 0x100, 0x8000})
            assertThrows(IllegalArgumentException.class, () -> CaptureMouseRelease.encodeMouseUp(invalid));
    }

    @Test void failedSendReportsFailureAfterArmingFilters() {
        for (int result : new int[]{0, 2}) {
            List<Integer> guarded = new ArrayList<>();
            IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                    new CaptureMouseRelease(key -> key == 1 ? 0x8000 : 0, flags -> result).releasePressedButtons(guarded::add));
            assertTrue(failure.getMessage().contains("取消本次截图")); assertEquals(List.of(NativeMouseEvent.BUTTON1), guarded);
        }
    }

    @Test void failedGuardNeverSendsInput() {
        AtomicInteger sent = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> new CaptureMouseRelease(key -> 0x8000, flags -> { sent.incrementAndGet(); return 1; })
                .releasePressedButtons(button -> { throw new IllegalStateException("guard"); }));
        assertEquals(0, sent.get());
    }

    @Test void repeatedUpsWaitForFreshPressWithoutBlockingMovement() {
        AtomicLong clock = new AtomicLong(100); RecordingControlInputFilter filter = new RecordingControlInputFilter(clock::get); filter.reset();
        clock.set(110); filter.beginSuspension(); filter.suppressMouseUntilNextPress(NativeMouseEvent.BUTTON1);
        assertTrue(filter.ignoreMouseRelease(mouse(NativeMouseEvent.NATIVE_MOUSE_RELEASED, 115), false));
        clock.set(200); filter.endSuspension();
        assertTrue(filter.ignoreMouseRelease(mouse(NativeMouseEvent.NATIVE_MOUSE_RELEASED, 210), false));
        assertFalse(filter.ignoreMouseMotion(mouse(NativeMouseEvent.NATIVE_MOUSE_MOVED, 211)));
        assertFalse(filter.ignoreMousePress(mouse(NativeMouseEvent.NATIVE_MOUSE_PRESSED, 220)));
        assertFalse(filter.ignoreMouseRelease(mouse(NativeMouseEvent.NATIVE_MOUSE_RELEASED, 230), true));
    }

    @Test void delayedCapturePressDoesNotClearGuardAfterResume() {
        AtomicLong clock = new AtomicLong(100); RecordingControlInputFilter filter = new RecordingControlInputFilter(clock::get); filter.reset();
        clock.set(110); filter.beginSuspension(); filter.suppressMouseUntilNextPress(NativeMouseEvent.BUTTON1);
        clock.set(200); filter.endSuspension();
        assertTrue(filter.ignoreMousePress(mouse(NativeMouseEvent.NATIVE_MOUSE_PRESSED, 150)));
        assertTrue(filter.ignoreMouseRelease(mouse(NativeMouseEvent.NATIVE_MOUSE_RELEASED, 210), false));
        assertTrue(filter.ignoreMouseRelease(mouse(NativeMouseEvent.NATIVE_MOUSE_RELEASED, 220), false));
        assertFalse(filter.ignoreMousePress(mouse(NativeMouseEvent.NATIVE_MOUSE_PRESSED, 230)));
        assertFalse(filter.ignoreMouseRelease(mouse(NativeMouseEvent.NATIVE_MOUSE_RELEASED, 240), true));
    }

    @Test void controlPressDoesNotRearmButOutsidePressDoes() {
        AtomicLong clock = new AtomicLong(100); RecordingControlInputFilter filter = new RecordingControlInputFilter(clock::get);
        filter.setRegionSupplier(() -> new RecordingControlInputFilter.Region(new Rectangle(0, 0, 100, 100), false)); filter.reset();
        filter.suppressMouseUntilNextPress(NativeMouseEvent.BUTTON1);
        assertTrue(filter.ignoreMousePress(new NativeMouseEvent(NativeMouseEvent.NATIVE_MOUSE_PRESSED, 0, 5, 5, 1, NativeMouseEvent.BUTTON1)));
        assertTrue(filter.ignoreMouseRelease(mouse(NativeMouseEvent.NATIVE_MOUSE_RELEASED, 110), false));
        assertTrue(filter.ignoreMouseRelease(mouse(NativeMouseEvent.NATIVE_MOUSE_RELEASED, 120), false));
        assertFalse(filter.ignoreMousePress(mouse(NativeMouseEvent.NATIVE_MOUSE_PRESSED, 130)));
        assertFalse(filter.ignoreMouseRelease(mouse(NativeMouseEvent.NATIVE_MOUSE_RELEASED, 140), true));
    }

    @Test void resetClearsOldCaptureGuards() {
        AtomicLong clock = new AtomicLong(100); RecordingControlInputFilter filter = new RecordingControlInputFilter(clock::get); filter.reset();
        filter.suppressMouseUntilNextPress(NativeMouseEvent.BUTTON1); clock.set(200); filter.reset();
        assertFalse(filter.ignoreMouseRelease(mouse(NativeMouseEvent.NATIVE_MOUSE_RELEASED, 210), false));
        assertFalse(filter.ignoreMousePress(mouse(NativeMouseEvent.NATIVE_MOUSE_PRESSED, 220)));
        assertFalse(filter.ignoreMouseRelease(mouse(NativeMouseEvent.NATIVE_MOUSE_RELEASED, 230), true));
    }

    private static NativeMouseEvent mouse(int type, long when) {
        NativeMouseEvent event = new NativeMouseEvent(type, 0, 500, 500, 1, NativeMouseEvent.BUTTON1);
        try { var field = com.github.kwhat.jnativehook.NativeInputEvent.class.getDeclaredField("when"); field.setAccessible(true); field.setLong(event, when); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        return event;
    }
}
