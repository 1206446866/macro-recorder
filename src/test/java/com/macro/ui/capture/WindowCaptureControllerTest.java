package com.macro.ui.capture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.*;
import javax.swing.*;
import java.awt.*;
import java.awt.image.*;
import java.io.IOException;
import java.lang.reflect.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** 合成捕获和窗口服务替身；不激活目标应用、不读取桌面、不显示选区。 */
@EnabledOnOs(OS.WINDOWS) @Timeout(10)
class WindowCaptureControllerTest {
    private static final CaptureWindowService.Target TARGET = new CaptureWindowService.Target(7, 8, "fixture", "窗口", "fixture.exe");

    @Test void activationFailureRestoresAllVisibleApplicationWindows() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            JFrame owner = frame(); JDialog child = new JDialog(owner); child.setAutoRequestFocus(false);
            child.setBounds(-12500,-12500,50,50); child.setVisible(true);
            AtomicInteger failed = new AtomicInteger();
            CaptureWindowService service = new CaptureWindowService() {
                @Override public void activate(Target target) throws IOException {
                    assertFalse(owner.isVisible()); assertFalse(child.isVisible()); throw new IOException("前置失败");
                }
            };
            ScreenCaptureController controller = new ScreenCaptureController(owner,(image,bounds)->fail(),()->fail(),
                    error->{assertTrue(owner.isVisible()); assertTrue(child.isVisible()); failed.incrementAndGet();}, false,
                    (device,bounds)->{fail("前置失败不应截图"); return null;}, service);
            try { controller.startCapture(TARGET); assertEquals(1,failed.get()); assertFalse((boolean)field(controller,"capturing")); }
            finally { controller.cancelCapture(); child.dispose(); owner.dispose(); }
        });
    }

    @Test void snapshotUsesTargetScreenAndCarriesRestrictedClientBounds() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        AtomicReference<JFrame> owner = new AtomicReference<>(); AtomicReference<ScreenCaptureController> reference = new AtomicReference<>();
        AtomicInteger activated=new AtomicInteger(), captured=new AtomicInteger();
        try {
            SwingUtilities.invokeAndWait(()->{
                JFrame frame=frame(); owner.set(frame);
                ScreenCaptureController controller=new ScreenCaptureController(frame,(image,bounds)->fail(),()->{},error->fail(error), false,
                        (device,bounds)->{captured.incrementAndGet(); return new BaseMultiResolutionImage(new BufferedImage(bounds.width*2,bounds.height*2,BufferedImage.TYPE_INT_RGB));},
                        fake(activated,false));
                reference.set(controller); controller.startCapture(TARGET);
                ((Timer)field(controller,"captureTimer")).stop();
                try {
                    Method capture=ScreenCaptureController.class.getDeclaredMethod("captureTarget",CaptureWindowService.Target.class); capture.setAccessible(true);
                    Object snapshot=capture.invoke(controller,TARGET);
                    Method limit=snapshot.getClass().getDeclaredMethod("selectableBounds"); limit.setAccessible(true);
                    Rectangle screen=frame.getGraphicsConfiguration().getBounds();
                    assertEquals(new Rectangle(screen.x+20,screen.y+30,160,100),limit.invoke(snapshot));
                    assertFalse(frame.isVisible()); assertEquals(1,activated.get()); assertEquals(1,captured.get());
                } catch(ReflectiveOperationException error){ throw new AssertionError(error); }
                controller.cancelCapture(); assertTrue(frame.isVisible());
            });
        } finally { SwingUtilities.invokeAndWait(()->{if(reference.get()!=null)reference.get().cancelCapture(); if(owner.get()!=null)owner.get().dispose();}); }
    }

    @Test void changedClientAfterCaptureRejectsFrozenImageAndRestoresOwner() throws Exception {
        runFailure(false);
    }
    @Test void unavailableTargetNeverFallsBackToDesktopCapture() throws Exception {
        runFailure(true);
    }
    private static void runFailure(boolean unavailable) throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        AtomicReference<JFrame> owner=new AtomicReference<>(); AtomicReference<ScreenCaptureController> control=new AtomicReference<>();
        CountDownLatch failed=new CountDownLatch(1); AtomicInteger captures=new AtomicInteger(),reads=new AtomicInteger();
        AtomicReference<Throwable> failure=new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(()->{
                JFrame frame=frame(); owner.set(frame); CaptureWindowService base=fake(new AtomicInteger(),false);
                CaptureWindowService service=new CaptureWindowService(){
                    @Override public void activate(Target target) { }
                    @Override public Rectangle monitorBounds(Rectangle logical) { return baseMonitor(logical); }
                    @Override public Rectangle clientBounds(Target target)throws IOException {
                        if(unavailable)throw new IOException("窗口已关闭");
                        Rectangle bounds=base.clientBounds(target); if(reads.incrementAndGet()>1)bounds.translate(4,0); return bounds;
                    }
                };
                ScreenCaptureController controller=new ScreenCaptureController(frame,(image,bounds)->failure.set(new AssertionError("不能交付失效图像")),()->{},
                        error->{failure.set(error); assertTrue(frame.isVisible()); failed.countDown();},false,
                        (device,bounds)->{captures.incrementAndGet(); return new BaseMultiResolutionImage(new BufferedImage(bounds.width*2,bounds.height*2,BufferedImage.TYPE_INT_RGB));},service);
                control.set(controller); controller.startCapture(TARGET);
            });
            assertTrue(failed.await(4,TimeUnit.SECONDS)); assertInstanceOf(IOException.class,failure.get());
            assertEquals(unavailable?0:1,captures.get());
        } finally {SwingUtilities.invokeAndWait(()->{if(control.get()!=null)control.get().cancelCapture();if(owner.get()!=null)owner.get().dispose();});}
    }

    @Test void cancellationPreventsLateCaptureFromShowingOverlay() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),exited=new CountDownLatch(1);
        AtomicReference<JFrame> owner=new AtomicReference<>(); AtomicReference<ScreenCaptureController> control=new AtomicReference<>();
        AtomicInteger callbacks=new AtomicInteger();
        try {
            SwingUtilities.invokeAndWait(()->{
                JFrame frame=frame();owner.set(frame);
                ScreenCaptureController controller=new ScreenCaptureController(frame,(image,bounds)->callbacks.incrementAndGet(),callbacks::incrementAndGet,error->fail(error),false,
                        (device,bounds)->{entered.countDown();try{while(!release.await(20,TimeUnit.MILLISECONDS)){} }catch(InterruptedException ignored){}
                            finally{exited.countDown();}return new BaseMultiResolutionImage(new BufferedImage(60,40,BufferedImage.TYPE_INT_RGB));},fake(new AtomicInteger(),false));
                control.set(controller);controller.startCapture(TARGET);
            });
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(()->{control.get().cancelCapture();assertTrue(owner.get().isVisible());assertEquals(1,callbacks.get());});
            release.countDown();assertTrue(exited.await(3,TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(()->{assertNull(field(control.get(),"overlay"));assertEquals(1,callbacks.get());});
        }finally{release.countDown();SwingUtilities.invokeAndWait(()->{if(control.get()!=null)control.get().cancelCapture();if(owner.get()!=null)owner.get().dispose();});}
    }

    private static CaptureWindowService fake(AtomicInteger activation,boolean unused){
        return new CaptureWindowService(){
            @Override public void activate(Target target){assertEquals(TARGET,target);activation.incrementAndGet();}
            @Override public Rectangle clientBounds(Target target){
                Rectangle screen=GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getBounds();
                return new Rectangle(screen.x+40,screen.y+60,320,200);
            }
            @Override public Rectangle monitorBounds(Rectangle logical){return baseMonitor(logical);}
        };
    }
    private static Rectangle baseMonitor(Rectangle logical){return new Rectangle(logical.x,logical.y,logical.width*2,logical.height*2);}
    private static JFrame frame(){JFrame frame=new JFrame();frame.setAutoRequestFocus(false);frame.setBounds(-12000,-12000,300,120);frame.setVisible(true);return frame;}
    private static Object field(Object target,String name){try{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}catch(ReflectiveOperationException error){throw new AssertionError(error);}}
}
