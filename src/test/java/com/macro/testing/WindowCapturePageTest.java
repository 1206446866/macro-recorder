package com.macro.testing;

import com.macro.config.AppConfig;
import com.macro.storage.ImageCaptureStorage;
import com.macro.ui.capture.CaptureWindowService;
import com.macro.ui.page.ImageCapturePanel;
import com.formdev.flatlaf.FlatDarkLaf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** 沿用隔离 JVM，AppConfig 只写本次新建应用目录，不接触项目个人配置。 */
@EnabledOnOs(OS.WINDOWS)
class WindowCapturePageTest {
    @Test void scopeCallbacksDefaultsAnd150PercentLayout()throws Exception{
        assumeFalse(GraphicsEnvironment.isHeadless());LegacyMainRunner.run(Probe.class,false);
    }
    public static class Probe {
        public static void main(String[] args)throws Exception{
            FlatDarkLaf.setup();
            SwingUtilities.invokeAndWait(()->{
                AppConfig config=new AppConfig();AtomicReference<CaptureWindowService.Target> requested=new AtomicReference<>();AtomicInteger requests=new AtomicInteger();
                ImageCapturePanel panel=new ImageCapturePanel(()->{},()->{},target->{requested.set(target);requests.incrementAndGet();},config,new ImageCaptureStorage());
                try{
                    assertTrue(button(panel,"captureButton").isEnabled());assertFalse(button(panel,"useScreenButton").isEnabled());assertFalse(button(panel,"ocrButton").isEnabled());
                    button(panel,"captureButton").doClick();assertNull(requested.get());
                    CaptureWindowService.Target target=new CaptureWindowService.Target(7,8,"fixture","指定截图窗口 — 长标题布局测试","fixture.exe");
                    Method set=ImageCapturePanel.class.getDeclaredMethod("setCaptureTarget",CaptureWindowService.Target.class);set.setAccessible(true);set.invoke(panel,target);
                    button(panel,"captureButton").doClick();assertEquals(target,requested.get());assertTrue(button(panel,"useScreenButton").isEnabled());
                    panel.setCapturedImage(new BufferedImage(80,50,BufferedImage.TYPE_INT_RGB));assertTrue(button(panel,"ocrButton").isEnabled());
                    panel.setSize(900,680);layout(panel);
                    assertTrue(button(panel,"captureButton").getWidth()>90);assertTrue(button(panel,"chooseWindowButton").getWidth()>70);
                    BufferedImage preview=new BufferedImage(1350,1020,BufferedImage.TYPE_INT_RGB);Graphics2D graphics=preview.createGraphics();graphics.scale(1.5,1.5);panel.paint(graphics);graphics.dispose();
                    javax.imageio.ImageIO.write(preview,"png",config.getDefaultScreenCaptureDirectory().toPath().getParent().resolve("capture-page-150.png").toFile());
                    button(panel,"useScreenButton").doClick();button(panel,"captureButton").doClick();assertNull(requested.get());assertEquals(3,requests.get());
                    assertFalse(button(panel,"useScreenButton").isEnabled());
                    assertFalse(Files.readString(config.getDefaultScreenCaptureDirectory().toPath().getParent().resolve("config.properties")).contains("fixture"));
                }catch(Exception error){throw new AssertionError(error);}
            });
        }
        private static JButton button(Object target,String name){try{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return(JButton)field.get(target);}catch(ReflectiveOperationException error){throw new AssertionError(error);}}
        private static void layout(Container root){root.doLayout();for(Component child:root.getComponents())if(child instanceof Container container)layout(container);}
    }
}
