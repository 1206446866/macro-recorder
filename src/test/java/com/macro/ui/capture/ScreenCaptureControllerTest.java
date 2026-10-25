package com.macro.ui.capture;

import com.sun.jna.*;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.win32.StdCallLibrary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import javax.swing.*;
import java.awt.*;
import java.awt.image.*;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** 只用合成图像；窗口位于屏幕外，不捕获桌面，不发送输入事件。 */
@EnabledOnOs(OS.WINDOWS)
class ScreenCaptureControllerTest {
    @Test void recordingCaptureSkipsTimerAndCancelRestoresAllWindowState() throws Exception { runLifecycle(false); }
    @Test void simulatedCompletionRestoresStateAndIgnoresOldWorker() throws Exception { runLifecycle(true); }

    private void runLifecycle(boolean complete) throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        AtomicReference<JFrame> owner = new AtomicReference<>();
        AtomicReference<ScreenCaptureController> control = new AtomicReference<>();
        AtomicInteger callbacks = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean workerWasEdt = new AtomicBoolean(true);
        try {
            SwingUtilities.invokeAndWait(() -> {
                JFrame frame = new JFrame("截图状态自动测试"); owner.set(frame);
                frame.setAutoRequestFocus(false); frame.setAlwaysOnTop(true);
                frame.setBounds(-12000, -12000, 300, 120); frame.setVisible(true);
                ScreenCaptureController controller = new ScreenCaptureController(frame,
                        image -> callbacks.incrementAndGet(), callbacks::incrementAndGet,
                        Assertions::fail, true, (device, bounds) -> {
                            workerWasEdt.set(SwingUtilities.isEventDispatchThread()); entered.countDown();
                            assertTrue(release.await(3, TimeUnit.SECONDS), "测试未及时释放截图任务");
                            return new BaseMultiResolutionImage(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB));
                        });
                control.set(controller); controller.startCapture();
                assertFalse(frame.isVisible());
                assertNull(field(controller, "captureTimer"), "录制截图不应再等待150ms计时器");
                assertEquals(1, cloak(frame) & 1);
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertFalse(workerWasEdt.get());
            SwingUtilities.invokeAndWait(() -> {
                ScreenCaptureController controller = control.get();
                if (complete) {
                    try {
                        var method = ScreenCaptureController.class.getDeclaredMethod("completeCapture", long.class, BufferedImage.class);
                        method.setAccessible(true);
                        method.invoke(controller, field(controller, "activeSessionId"), new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB));
                    } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
                } else controller.cancelCapture();
                controller.cancelCapture();
                assertTrue(owner.get().isVisible()); assertTrue(owner.get().isAlwaysOnTop());
                assertEquals(new Rectangle(-12000, -12000, 300, 120), owner.get().getBounds());
                assertEquals(0, cloak(owner.get()) & 1);
                assertEquals(1, callbacks.get());
                assertFalse((Boolean) field(controller, "capturing"));
            });
            release.countDown();
            SwingUtilities.invokeAndWait(() -> assertEquals(1, callbacks.get()));
        } finally {
            release.countDown();
            SwingUtilities.invokeAndWait(() -> { if (control.get() != null) control.get().cancelCapture(); if (owner.get() != null) owner.get().dispose(); });
        }
    }

    @Test void captureFailureRestoresWindowAndCallsFailureOnEdt() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        CountDownLatch failed = new CountDownLatch(1);
        AtomicReference<JFrame> owner = new AtomicReference<>();
        AtomicReference<ScreenCaptureController> control = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                JFrame frame = new JFrame(); owner.set(frame);
                frame.setAutoRequestFocus(false); frame.setBounds(-12000, -12000, 300, 100); frame.setVisible(true);
                ScreenCaptureController controller = new ScreenCaptureController(frame, image -> fail(), () -> fail(),
                        error -> { assertTrue(SwingUtilities.isEventDispatchThread()); assertTrue(frame.isVisible());
                            assertEquals(0, cloak(frame) & 1); failed.countDown(); }, true,
                        (device, bounds) -> { throw new IllegalStateException("模拟采集失败"); });
                control.set(controller); controller.startCapture();
            });
            assertTrue(failed.await(3, TimeUnit.SECONDS));
        } finally {
            SwingUtilities.invokeAndWait(() -> { if (control.get() != null) control.get().cancelCapture(); if (owner.get() != null) owner.get().dispose(); });
        }
    }

    @Test void existingCloakIsPreservedAndOrdinaryCaptureStillUsesOriginalTimer() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame(); frame.pack();
            try {
                try (WindowsCaptureSupport.Cloak first = WindowsCaptureSupport.cloak(frame)) {
                    assertTrue(first.available());
                    try (WindowsCaptureSupport.Cloak second = WindowsCaptureSupport.cloak(frame)) { assertTrue(second.available()); }
                    assertEquals(1, cloak(frame) & 1);
                }
                assertEquals(0, cloak(frame) & 1);
                ScreenCaptureController normal = new ScreenCaptureController(frame, image -> fail(), () -> { }, Assertions::fail,
                        false, (device, bounds) -> { throw new AssertionError("取消后不应采集"); });
                normal.startCapture();
                assertNotNull(field(normal, "captureTimer"));
                normal.cancelCapture();
            } finally { frame.dispose(); }
        });
    }

    @Test void frozenOverlayDoesNotInheritOwnersTemporaryCloak() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        AtomicReference<JFrame> owner = new AtomicReference<>();
        AtomicReference<ScreenCaptureController> control = new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            SwingUtilities.invokeAndWait(() -> {
                JFrame frame = new JFrame(); owner.set(frame);
                frame.setAutoRequestFocus(false); frame.setBounds(-12000, -12000, 300, 120); frame.setVisible(true);
                ScreenCaptureController controller = new ScreenCaptureController(frame, image -> { }, () -> { }, Assertions::fail, true,
                        (device, bounds) -> { entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS), "测试未及时释放截图任务");
                            return new BaseMultiResolutionImage(new BufferedImage(60, 40, BufferedImage.TYPE_INT_RGB)); });
                control.set(controller); controller.startCapture();
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                ScreenCaptureController controller = control.get();
                try {
                    Class<?> type = Class.forName(ScreenCaptureController.class.getName()+"$ScreenSnapshot");
                    var constructor = type.getDeclaredConstructor(BufferedImage.class, Rectangle.class, GraphicsConfiguration.class);
                    constructor.setAccessible(true);
                    Object snapshot = constructor.newInstance(new BufferedImage(60, 40, BufferedImage.TYPE_INT_RGB),
                            new Rectangle(-12000, -12000, 60, 40), owner.get().getGraphicsConfiguration());
                    var show = ScreenCaptureController.class.getDeclaredMethod("showSelectionOverlay", long.class, type);
                    show.setAccessible(true); show.invoke(controller, field(controller, "activeSessionId"), snapshot);
                    Window overlay = (Window) field(controller, "overlay");
                    assertFalse(owner.get().isVisible());
                    assertEquals(0, cloak(owner.get()) & 1, "创建框选层前必须解除所属窗口的临时遮蔽");
                    assertTrue(overlay.isShowing());
                    assertEquals(0, cloak(overlay) & 5, "框选层继承了遮蔽状态");
                    controller.cancelCapture();
                    assertFalse(overlay.isDisplayable()); assertTrue(owner.get().isVisible());
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            });
        } finally {
            release.countDown();
            SwingUtilities.invokeAndWait(() -> { if (control.get()!=null) control.get().cancelCapture(); if (owner.get()!=null) owner.get().dispose(); });
        }
    }

    private static Object field(Object object, String name) {
        try { Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static int cloak(Window frame) {
        try (Memory memory = new Memory(4)) {
            assertEquals(0, Dwm.INSTANCE.DwmGetWindowAttribute(new HWND(Native.getComponentPointer(frame)), 14, memory, 4));
            return memory.getInt(0);
        }
    }
    private interface Dwm extends StdCallLibrary {
        Dwm INSTANCE = Native.load("dwmapi", Dwm.class);
        int DwmGetWindowAttribute(HWND hwnd, int attribute, Pointer value, int size);
    }
}
