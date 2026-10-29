package com.macro.recognition;

import com.macro.flow.*;
import com.macro.model.TargetWindow;
import com.macro.ui.capture.CaptureWindowService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 OpenCV + 假窗口与合成桌面；不激活窗口、不截桌面、不发送输入。 */
@Timeout(20)
class WindowRecognitionTest {
    private static final CaptureWindowService.Target ORIGINAL = target(41,"文档 A");
    private static CaptureWindowService.Target target(long handle,String title) { return new CaptureWindowService.Target(handle,8,"FixtureClass",title,"fixture.exe"); }
    private static TargetWindow description() { return TargetWindow.create().setProcessName("fixture.exe").setWindowClass("FixtureClass").setWindowTitle("文档 A"); }

    @Test void firstSearchOnlyCapturesWindowAndIgnoresIdenticalOutsideImage() throws Exception {
        try(Fixture f=new Fixture()) {
            f.draw(20,20,170,140);
            var result=f.match(); assertEquals(new Rectangle(170,140,20,16),result.getMatchBounds());
            assertEquals(List.of(f.windows.client),f.captures); assertEquals(0,f.windows.activations.get());
        }
    }
    @Test void missingInsideNeverSearchesDesktopEvenWhenOutsideTargetMatches() throws Exception {
        try(Fixture f=new Fixture()) {
            f.draw(20,20); assertFalse(f.match().isFound()); assertEquals(List.of(f.windows.client),f.captures);
        }
    }
    @Test void nearlyFullClientTemplateAndScaledTemplateBothMatchWithinWindow() throws Exception {
        try(Fixture f=new Fixture()) {
            f.windows.client=new Rectangle(168,138,24,20);f.draw(170,140);
            assertEquals(new Rectangle(170,140,20,16),f.match().getMatchBounds());
        }
        try(Fixture f=new Fixture()) {
            f.config.setScaleMatchingEnabled(true);f.draw();
            Graphics2D graphics=f.frame.createGraphics();graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(f.template,170,140,24,19,null);graphics.dispose();
            assertEquals(new Rectangle(170,140,24,19),f.match().getMatchBounds());
            assertTrue(f.captures.stream().allMatch(f.windows.client::contains));
        }
    }
    @Test void movingAndResizingWindowUsesNewBoundsAndInvalidatesOldPosition() throws Exception {
        try(Fixture f=new Fixture()) {
            f.draw(170,140); assertTrue(f.match().isFound());
            f.windows.client=new Rectangle(300,200,350,250);f.draw(370,240);f.captures.clear();
            assertEquals(new Rectangle(370,240,20,16),f.match().getMatchBounds());assertEquals(List.of(f.windows.client),f.captures);
            f.windows.client=new Rectangle(300,200,220,140);f.captures.clear();
            assertTrue(f.match().isFound());assertEquals(List.of(f.windows.client),f.captures);assertEquals(1,f.windows.enumerations.get());
        }
    }
    @Test void localAndRacedFallbackRemainInsideCurrentClient() throws Exception {
        try(Fixture f=new Fixture()) {
            f.windows.client=new Rectangle(100,100,500,350);f.draw(150,150);assertTrue(f.match().isFound());
            f.draw(470,350);f.captures.clear();
            assertEquals(new Rectangle(470,350,20,16),f.match().getMatchBounds());
            f.drain();assertTrue(f.captures.size()>=2);assertTrue(f.captures.stream().allMatch(f.windows.client::contains));
        }
    }
    @Test void exactTitleWinsThenBindingSurvivesTitleChangeButNotWindowReplacement() throws Exception {
        try(Fixture f=new Fixture()) {
            f.windows.items=List.of(target(42,"文档 B"),ORIGINAL);f.draw(170,140);assertTrue(f.match().isFound());
            f.windows.items=List.of(target(42,"文档 B"),target(41,"标题已变化"));assertTrue(f.match().isFound());assertEquals(1,f.windows.enumerations.get());
            f.windows.items=List.of(target(42,"文档 B"));f.captures.clear();
            assertThrows(IOException.class,f::match);assertTrue(f.captures.isEmpty());
            f.matcher.beginPlayback();assertTrue(f.match().isFound());assertEquals(2,f.windows.enumerations.get());
        }
    }
    @Test void uniqueProcessAndClassAllowChangedTitleWhileAmbiguousTargetsFailWithoutCapture() throws Exception {
        try(Fixture f=new Fixture()) {
            f.windows.items=List.of(target(41,"新标题"));f.draw(170,140);assertTrue(f.match().isFound());
        }
        try(Fixture f=new Fixture()) {
            f.windows.items=List.of(ORIGINAL,target(42,"文档 A"));
            assertThrows(IOException.class,f::match);assertTrue(f.captures.isEmpty());
            f.windows.items=List.of(target(41,"新标题"),target(42,"另一标题"));
            assertThrows(IOException.class,f::match);assertTrue(f.captures.isEmpty());
        }
    }
    @Test void unavailableOrMissingWindowNeverFallsBackToDesktop() throws Exception {
        try(Fixture f=new Fixture()) {
            f.draw(20,20);f.windows.unavailable=true;assertThrows(IOException.class,f::match);assertTrue(f.captures.isEmpty());
            f.windows.unavailable=false;f.windows.items=List.of();f.matcher.beginPlayback();
            assertThrows(IOException.class,f::match);assertTrue(f.captures.isEmpty());
        }
    }
    @Test void windowMovementDuringCaptureOrNativeResultCollectionRejectsStaleResult() throws Exception {
        try(Fixture f=new Fixture()) {
            f.draw(170,140);f.afterCapture=()->f.windows.client=new Rectangle(110,100,350,250);
            assertThrows(IOException.class,f::match);
        }
        try(Fixture f=new Fixture()) {
            f.draw(170,140);AtomicBoolean moved=new AtomicBoolean();
            assertThrows(IOException.class,()->f.matcher.match(f.node,new FlowContext(),()->{
                if(!moved.get()&&StackWalker.getInstance().walk(frames->frames.anyMatch(frame->frame.getClassName().equals(OpenCvTemplateMatcher.class.getName())&&frame.getMethodName().equals("collectResults")))){
                    moved.set(true);f.windows.client=new Rectangle(110,100,350,250);
                }
                return false;
            }));assertTrue(moved.get());
        }
    }
    @Test void preflightAndCancellationDoNotLocateWindowsOrCaptureAnything() throws Exception {
        try(Fixture f=new Fixture()) {
            f.matcher.validate(f.node);assertEquals(0,f.windows.enumerations.get());assertTrue(f.captures.isEmpty());
            assertThrows(CancellationException.class,()->f.matcher.match(f.node,new FlowContext(),()->true));
            assertEquals(0,f.windows.enumerations.get());
            f.config.setSearchWindow(null);assertThrows(IllegalArgumentException.class,()->f.matcher.validate(f.node));
        }
    }
    @Test void switchingScopeDoesNotReuseOldWindowHintAsDesktopPosition() throws Exception {
        try(Fixture f=new Fixture()) {
            f.draw(170,140);assertTrue(f.match().isFound());f.captures.clear();
            f.config.setSearchScope(ImageSearchScope.DESKTOP);assertTrue(f.match().isFound());assertEquals(List.of(new Rectangle(0,0,800,600)),f.captures);
            f.config.setSearchScope(ImageSearchScope.WINDOW);f.captures.clear();assertTrue(f.match().isFound());assertEquals(List.of(f.windows.client),f.captures);
        }
    }
    @Test void crossMonitorCaptureRespectsEachDpiAndNegativeOrigin() throws Exception {
        Windows windows=new Windows();windows.client=new Rectangle(-300,30,600,450);
        var left=new ScreenRecognitionService.ScreenGeometry("left",new Rectangle(-1200,0,800,600),1.5,1.5,"left");
        var right=new ScreenRecognitionService.ScreenGeometry("right",new Rectangle(0,0,800,600),1,1,"right");
        List<Rectangle> physicalCaptures=new CopyOnWriteArrayList<>();
        CaptureWindowService service=new CaptureWindowService(){
            @Override public List<Target> listWindows(){return List.of(ORIGINAL);}
            @Override public Rectangle clientBounds(Target target){return new Rectangle(windows.client);}
            @Override public Rectangle monitorBounds(Rectangle logical){return logical.x<0?new Rectangle(-1200,0,1200,900):new Rectangle(0,0,800,600);}
        };
        ScreenRecognitionService recognition=new ScreenRecognitionService(()->List.of(left,right),(screen,area)->{
            var original=screen.id().equals("left")?left:right;
            Rectangle pixels=new Rectangle(original.bounds().x+(int)Math.round((area.x-original.bounds().x)*original.scaleX()),
                    (int)Math.round(area.y*original.scaleY()),(int)Math.round(area.width*original.scaleX()),(int)Math.round(area.height*original.scaleY()));
            physicalCaptures.add(pixels);return new BaseMultiResolutionImage(new BufferedImage(pixels.width,pixels.height,BufferedImage.TYPE_INT_RGB));
        },RecognitionSession::new,service);
        ImageCheckConfig config=new ImageCheckConfig();config.setSearchScope(ImageSearchScope.WINDOW);config.setSearchWindow(description());
        assertEquals(2,recognition.captureScope(config,()->false).captureScreens(null,()->false).size());
        assertTrue(physicalCaptures.stream().allMatch(windows.client::contains));
    }
    @Test void robotRoundingCannotCapturePixelsOutsideClientAtAnyLocalOrigin() {
        Random random=new Random(855);
        for(double scale:new double[]{1,1.25,1.5,1.75,2,2.5})for(int sample=0;sample<200;sample++){
            Rectangle monitor=new Rectangle(-1920,100,1920,1080);
            var screen=new ScreenRecognitionService.ScreenGeometry("s",new Rectangle(-1920,100,(int)Math.round(1920/scale),(int)Math.round(1080/scale)),scale,scale,"s");
            Rectangle client=new Rectangle(-1900+random.nextInt(500),120+random.nextInt(200),200+random.nextInt(600),200+random.nextInt(400));
            Rectangle requested=WindowRecognitionScope.logicalBounds(client.intersection(monitor),monitor,screen);
            if(sample%2==0){requested.x++;requested.width--;}
            Rectangle safe=WindowRecognitionScope.safeCaptureBounds(requested,screen,monitor,client);
            if(safe.isEmpty())continue;
            Rectangle pixels=new Rectangle(monitor.x+(int)Math.round((safe.x-screen.bounds().x)*scale),monitor.y+(int)Math.round((safe.y-screen.bounds().y)*scale),
                    (int)Math.round(safe.width*scale),(int)Math.round(safe.height*scale));
            assertTrue(client.contains(pixels),"unsafe "+pixels+" client "+client);
        }
    }
    @Test void dpiRoundingAtCaptureEdgeKeepsReturnedClickBoundsInsideSnapshot() throws Exception {
        ScreenRecognitionService service=new ScreenRecognitionService();
        var convert=ScreenRecognitionService.class.getDeclaredMethod("toLogicalBounds",ScreenRecognitionService.ScreenSnapshot.class,Rectangle.class);
        convert.setAccessible(true);
        Rectangle bounds=new Rectangle(100,100,67,40);
        var snapshot=new ScreenRecognitionService.ScreenSnapshot(new BufferedImage(100,60,BufferedImage.TYPE_INT_RGB),bounds,"fixture");
        Rectangle result=(Rectangle)convert.invoke(service,snapshot,new Rectangle(50,0,50,60));
        assertEquals(new Rectangle(134,100,33,40),result);assertTrue(bounds.contains(result));
        Rectangle tinyBounds=new Rectangle(-300,50,33,20);
        var tinySnapshot=new ScreenRecognitionService.ScreenSnapshot(new BufferedImage(100,60,BufferedImage.TYPE_INT_RGB),tinyBounds,"fixture");
        Rectangle tiny=(Rectangle)convert.invoke(service,tinySnapshot,new Rectangle(99,59,1,1));
        assertTrue(tinyBounds.contains(tiny));assertEquals(1,tiny.width);assertEquals(1,tiny.height);
    }

    private static class Windows extends CaptureWindowService {
        volatile Rectangle client=new Rectangle(100,100,350,250);volatile List<Target> items=List.of(ORIGINAL);volatile boolean unavailable;
        final AtomicInteger enumerations=new AtomicInteger(),activations=new AtomicInteger();
        @Override public List<Target> listWindows(){enumerations.incrementAndGet();return items;}
        @Override public Rectangle clientBounds(Target target)throws IOException{
            if(unavailable||items.stream().noneMatch(item->item.handle()==target.handle()&&item.processId()==target.processId()))throw new IOException("窗口未在前台、已关闭或已最小化");
            return new Rectangle(client);
        }
        @Override public Rectangle monitorBounds(Rectangle logical){return new Rectangle(logical);}
        @Override public void activate(Target target){activations.incrementAndGet();fail("识别不得激活窗口");}
    }
    private static final class Fixture implements AutoCloseable {
        final Windows windows=new Windows();final ImageCheckConfig config;final MacroNode node;final OpenCvImageMatcher matcher;
        final BufferedImage template=new BufferedImage(20,16,BufferedImage.TYPE_INT_RGB);
        volatile BufferedImage frame;volatile Runnable afterCapture=()->{};
        final List<Rectangle> captures=new CopyOnWriteArrayList<>();
        Fixture()throws Exception{
            Path root=Path.of(System.getProperty("macro.test.sandboxRoot","target/test-sandboxes")).toAbsolutePath();Files.createDirectories(root);
            Path directory=Files.createTempDirectory(root,"window-recognition-");Path png=directory.resolve("template.png");Random random=new Random(55);
            for(int y=0;y<16;y++)for(int x=0;x<20;x++)template.setRGB(x,y,random.nextInt(0x1000000));ImageIO.write(template,"png",png.toFile());
            config=new ImageCheckConfig(png.toString(),.99);config.setSearchScope(ImageSearchScope.WINDOW);config.setSearchWindow(description());
            node=new MacroNode("image",NodeType.IMAGE_CHECK,null,config,new ArrayList<>());
            var screen=new ScreenRecognitionService.ScreenGeometry("s",new Rectangle(0,0,800,600),1,1,"s");
            var service=new ScreenRecognitionService(()->List.of(screen),(display,bounds)->{
                captures.add(new Rectangle(bounds));BufferedImage image=frame.getSubimage(bounds.x,bounds.y,bounds.width,bounds.height);afterCapture.run();return new BaseMultiResolutionImage(image);
            },RecognitionSession::new,windows);
            matcher=new OpenCvImageMatcher(directory::toFile,service);draw();
        }
        void draw(int...xy){frame=new BufferedImage(800,600,BufferedImage.TYPE_INT_RGB);Graphics2D graphics=frame.createGraphics();for(int i=0;i<xy.length;i+=2)graphics.drawImage(template,xy[i],xy[i+1],null);graphics.dispose();}
        ImageMatchResult match()throws Exception{return matcher.match(node,new FlowContext());}
        void drain()throws Exception{var field=ScreenSearchRace.class.getDeclaredField("SLOTS");field.setAccessible(true);Semaphore slots=(Semaphore)field.get(null);assertTrue(slots.tryAcquire(2,5,TimeUnit.SECONDS));slots.release(2);}
        @Override public void close()throws Exception{drain();}
    }
}
