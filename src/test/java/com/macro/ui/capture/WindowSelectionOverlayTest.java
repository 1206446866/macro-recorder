package com.macro.ui.capture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** 仅合成图片与离屏鼠标事件，不显示窗口、不读取或限制真实鼠标。 */
@EnabledOnOs(OS.WINDOWS)
class WindowSelectionOverlayTest {
    @Test void outsideCannotStartAndDragClipsBothDirectionsAtClientEdges() throws Exception {
        run((overlay, canvas, captured, cancelled) -> {
            mouse(canvas, MouseEvent.MOUSE_PRESSED, 5, 5, MouseEvent.BUTTON1);
            mouse(canvas, MouseEvent.MOUSE_DRAGGED, 90, 60, MouseEvent.NOBUTTON);
            mouse(canvas, MouseEvent.MOUSE_RELEASED, 90, 60, MouseEvent.BUTTON1);
            assertNull(field(canvas, "selection"));
            mouse(canvas, MouseEvent.MOUSE_PRESSED, 30, 25, MouseEvent.BUTTON1);
            mouse(canvas, MouseEvent.MOUSE_DRAGGED, 130, 90, MouseEvent.NOBUTTON);
            mouse(canvas, MouseEvent.MOUSE_RELEASED, 130, 90, MouseEvent.BUTTON1);
            assertEquals(new Rectangle(-11970, -11975, 50, 25), overlay.getSelectionBoundsOnScreen());
            mouse(canvas, MouseEvent.MOUSE_PRESSED, 79, 49, MouseEvent.BUTTON1);
            // Start a new region outside the existing selection but inside the permitted client area.
            mouse(canvas, MouseEvent.MOUSE_PRESSED, 21, 16, MouseEvent.BUTTON1);
            mouse(canvas, MouseEvent.MOUSE_RELEASED, -50, -50, MouseEvent.BUTTON1);
            assertNull(overlay.getSelectionBoundsOnScreen(), "1×1 selection cannot be confirmed");
            assertNull(captured.get()); assertEquals(0, cancelled.get());
        });
    }
    @Test void confirmationOnlyCopiesSelectedClientPixelsAt150Percent() throws Exception {
        run((overlay, canvas, captured, cancelled) -> {
            mouse(canvas, MouseEvent.MOUSE_PRESSED, 20, 15, MouseEvent.BUTTON1);
            mouse(canvas, MouseEvent.MOUSE_RELEASED, 100, 100, MouseEvent.BUTTON1);
            assertEquals(new Rectangle(-11980, -11985, 60, 35), overlay.getSelectionBoundsOnScreen());
            overlay.getRootPane().getActionMap().get("confirmCapture").actionPerformed(null);
            assertNotNull(captured.get()); assertEquals(90, captured.get().getWidth()); assertEquals(53, captured.get().getHeight());
            assertEquals(0xFF33AA55, captured.get().getRGB(0,0));
        });
    }
    @Test void outsideClickPreservesSelectionAndEscapeCancelsOnce() throws Exception {
        run((overlay, canvas, captured, cancelled) -> {
            mouse(canvas, MouseEvent.MOUSE_PRESSED, 25, 20, MouseEvent.BUTTON1);
            mouse(canvas, MouseEvent.MOUSE_RELEASED, 45, 40, MouseEvent.BUTTON1);
            Rectangle before = overlay.getSelectionBoundsOnScreen();
            mouse(canvas, MouseEvent.MOUSE_PRESSED, 100, 60, MouseEvent.BUTTON1);
            mouse(canvas, MouseEvent.MOUSE_RELEASED, 115, 70, MouseEvent.BUTTON1);
            assertEquals(before, overlay.getSelectionBoundsOnScreen());
            var cancel = overlay.getRootPane().getActionMap().get("cancelCapture"); cancel.actionPerformed(null); cancel.actionPerformed(null);
            assertEquals(1, cancelled.get()); assertNull(captured.get());
        });
    }
    private static void run(Check check) throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            JFrame owner = new JFrame();
            AtomicReference<BufferedImage> captured = new AtomicReference<>(); AtomicInteger cancelled = new AtomicInteger();
            BufferedImage image = new BufferedImage(180,120,BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics(); graphics.setColor(Color.RED); graphics.fillRect(0,0,180,120);
            graphics.setColor(new Color(0x33AA55)); graphics.fillRect(30,22,90,53); graphics.dispose();
            ScreenSelectionOverlay overlay = new ScreenSelectionOverlay(owner, image, new Rectangle(-12000,-12000,120,80), owner.getGraphicsConfiguration(),
                    captured::set, cancelled::incrementAndGet, error -> fail(error), new Rectangle(-11980,-11985,60,35));
            try {
                JPanel canvas = (JPanel)field(overlay,"canvas"); canvas.setSize(120,80);
                check.run(overlay,canvas,captured,cancelled);
            } finally { overlay.closeOverlay(); owner.dispose(); }
        });
    }
    private static void mouse(Component canvas,int id,int x,int y,int button) {
        canvas.dispatchEvent(new MouseEvent(canvas,id,System.currentTimeMillis(),0,x,y,1,false,button));
    }
    private static Object field(Object target,String name) {
        try { Field field=target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch(ReflectiveOperationException error){ throw new AssertionError(error); }
    }
    @FunctionalInterface private interface Check { void run(ScreenSelectionOverlay overlay,JPanel canvas,AtomicReference<BufferedImage> captured,AtomicInteger cancelled); }
}
