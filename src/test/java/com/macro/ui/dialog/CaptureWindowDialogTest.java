package com.macro.ui.dialog;

import com.macro.ui.capture.CaptureWindowService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** 仅假窗口列表与离屏布局；不枚举或前置用户窗口。 */
@EnabledOnOs(OS.WINDOWS)
class CaptureWindowDialogTest {
    private static final CaptureWindowService.Target TARGET=new CaptureWindowService.Target(7,8,"fixture","测试窗口 — 中文标题","fixture.exe");
    @Test void selectionLoadsOffEdtAndOnlyExplicitConfirmAccepts()throws Exception{
        assumeFalse(GraphicsEnvironment.isHeadless());
        AtomicReference<CaptureWindowDialog> reference=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->reference.set(new CaptureWindowDialog(null,new CaptureWindowService(){
            @Override public List<Target> listWindows(){assertFalse(SwingUtilities.isEventDispatchThread());return List.of(TARGET);}
            @Override public void activate(Target target){fail("选择窗口不能激活目标");}
        })));
        CaptureWindowDialog dialog=reference.get();
        try{awaitIdle(dialog);SwingUtilities.invokeAndWait(()->{
            assertTrue(dialog.getSelection().isEmpty());assertFalse(button(dialog,"confirm").isEnabled());
            list(dialog).setSelectedIndex(0);assertTrue(button(dialog,"confirm").isEnabled());render(dialog);
            button(dialog,"confirm").doClick();assertEquals(TARGET,dialog.getSelection().orElseThrow());
        });}finally{SwingUtilities.invokeAndWait(dialog::dispose);}
    }
    @Test void failedRefreshClearsOldChoiceAndCanRecover()throws Exception{
        assumeFalse(GraphicsEnvironment.isHeadless());AtomicInteger attempts=new AtomicInteger();
        AtomicReference<CaptureWindowDialog> reference=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->reference.set(new CaptureWindowDialog(null,new CaptureWindowService(){
            @Override public List<Target> listWindows()throws java.io.IOException{
                if(attempts.incrementAndGet()==2)throw new java.io.IOException("合成失败");return List.of(TARGET);
            }
        })));
        CaptureWindowDialog dialog=reference.get();
        try{awaitIdle(dialog);SwingUtilities.invokeAndWait(()->{list(dialog).setSelectedIndex(0);button(dialog,"refresh").doClick();});awaitIdle(dialog);
            SwingUtilities.invokeAndWait(()->{assertEquals(0,list(dialog).getModel().getSize());assertFalse(button(dialog,"confirm").isEnabled());button(dialog,"refresh").doClick();});
            awaitIdle(dialog);SwingUtilities.invokeAndWait(()->assertEquals(1,list(dialog).getModel().getSize()));
        }finally{SwingUtilities.invokeAndWait(dialog::dispose);}
    }
    @Test void closingDiscardsLateListAndKeepsSelectionEmpty()throws Exception{
        assumeFalse(GraphicsEnvironment.isHeadless());CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicReference<CaptureWindowDialog> reference=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->reference.set(new CaptureWindowDialog(null,new CaptureWindowService(){
            @Override public List<Target> listWindows(){entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException ignored){}return List.of(TARGET);}
        })));
        CaptureWindowDialog dialog=reference.get();
        try{assertTrue(entered.await(3,TimeUnit.SECONDS));SwingUtilities.invokeAndWait(dialog::dispose);release.countDown();awaitIdle(dialog);
            SwingUtilities.invokeAndWait(()->{assertTrue(dialog.getSelection().isEmpty());assertEquals(0,list(dialog).getModel().getSize());});
        }finally{release.countDown();SwingUtilities.invokeAndWait(dialog::dispose);}
    }
    private static void render(CaptureWindowDialog dialog){
        JRootPane root=dialog.getRootPane();root.setSize(700,460);layout(root);
        assertTrue(list(dialog).getWidth()>400);assertTrue(button(dialog,"confirm").getWidth()>80);
        BufferedImage image=new BufferedImage(1050,690,BufferedImage.TYPE_INT_RGB);Graphics2D graphics=image.createGraphics();graphics.scale(1.5,1.5);root.paint(graphics);graphics.dispose();
        try{Path parent=Path.of(System.getProperty("macro.test.sandboxRoot","target/test-sandboxes")).toAbsolutePath();Files.createDirectories(parent);
            javax.imageio.ImageIO.write(image,"png",Files.createTempDirectory(parent,"window-picker-").resolve("picker-150.png").toFile());
        }catch(Exception error){throw new AssertionError(error);}
    }
    private static void layout(Container root){root.doLayout();for(Component child:root.getComponents())if(child instanceof Container container)layout(container);}
    private static JButton button(CaptureWindowDialog dialog,String name){return(JButton)field(dialog,name);}
    private static JList<?> list(CaptureWindowDialog dialog){return(JList<?>)field(dialog,"windows");}
    private static Object field(Object target,String name){try{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}catch(ReflectiveOperationException error){throw new AssertionError(error);}}
    private static void awaitIdle(CaptureWindowDialog dialog)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);AtomicBoolean idle=new AtomicBoolean();
        do{SwingUtilities.invokeAndWait(()->idle.set(field(dialog,"worker")==null));if(idle.get())return;Thread.sleep(10);}while(System.nanoTime()<deadline);fail("列表读取未完成");
    }
}
