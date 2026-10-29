package com.macro.ui.capture;

import com.sun.jna.*;
import com.sun.jna.platform.win32.*;
import com.sun.jna.platform.win32.WinDef.*;
import com.sun.jna.win32.StdCallLibrary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import javax.swing.*;
import java.awt.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** 只查询屏幕参数和不显示的自建窗口；不枚举个人窗口，不激活任何窗口。 */
@EnabledOnOs(OS.WINDOWS)
class CaptureWindowServiceTest {
    @Test void physicalClientQueryRestoresThreadDpiOnSuccessAndFailure() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame(); frame.setUndecorated(true); frame.setBounds(-12000,-12000,300,160); frame.addNotify();
            Pointer before = Dpi.INSTANCE.GetThreadDpiAwarenessContext();
            try {
                HWND handle = new HWND(Native.getComponentPointer(frame));
                Rectangle client = CaptureWindowService.readPhysicalClientBounds(handle);
                assertEquals(Math.round(300*frame.getGraphicsConfiguration().getDefaultTransform().getScaleX()), client.width);
                assertEquals(Math.round(160*frame.getGraphicsConfiguration().getDefaultTransform().getScaleY()), client.height);
                assertEquals(before,Dpi.INSTANCE.GetThreadDpiAwarenessContext());
                assertThrows(java.io.IOException.class,()->CaptureWindowService.readPhysicalClientBounds(new HWND(Pointer.createConstant(1))));
                assertEquals(before,Dpi.INSTANCE.GetThreadDpiAwarenessContext());
            } catch(Exception error) { throw new AssertionError(error); }
            finally { frame.dispose(); }
        });
    }
    @Test void monitorMappingKeepsNativeOriginAndPhysicalDimensionsWithoutChangingDpi() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        Pointer before = Dpi.INSTANCE.GetThreadDpiAwarenessContext();
        for(GraphicsDevice device:GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            Rectangle logical=device.getDefaultConfiguration().getBounds();
            Rectangle physical=new CaptureWindowService().monitorBounds(logical);
            assertEquals(logical.x,physical.x); assertEquals(logical.y,physical.y);
            assertEquals(device.getDisplayMode().getWidth(),physical.width); assertEquals(device.getDisplayMode().getHeight(),physical.height);
        }
        assertEquals(before,Dpi.INSTANCE.GetThreadDpiAwarenessContext());
    }
    @Test void invalidOrOwnProcessTargetIsRejectedBeforeActivation() throws Exception {
        CaptureWindowService service=new CaptureWindowService();
        assertThrows(java.io.IOException.class,()->service.activate(new CaptureWindowService.Target(0,0,"","","")));
        assertThrows(java.io.IOException.class,()->service.clientBounds(new CaptureWindowService.Target(1,1,"","","")));
    }
    interface Dpi extends StdCallLibrary {
        Dpi INSTANCE=Native.load("user32",Dpi.class);
        Pointer GetThreadDpiAwarenessContext();
    }
}
