package com.macro.recognition;

import com.macro.flow.*;
import com.macro.player.MacroPlayer;
import com.macro.player.PlaybackProgress;
import com.macro.storage.MacroStorage;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** 真实 OpenCV、合成截图源、独立临时模板；不采集桌面或发送真实输入。 */
class AdaptiveImageSearchTest {
    @Test void firstUseIsGlobalThenSmallMovementUsesOnlyLocalCapture() throws Exception {
        Fixture f = new Fixture();
        f.place(200, 100);
        assertEquals(new Rectangle(200, 100, 20, 16), f.match().getMatchBounds());
        assertEquals(List.of(f.full()), f.requests());
        f.resetRequests();
        f.place(215, 108);
        assertEquals(new Rectangle(215, 108, 20, 16), f.match().getMatchBounds());
        assertEquals(List.of(new Rectangle(168, 68, 84, 80)), f.requests());
        f.resetRequests();
        assertTrue(f.match().isFound());
        assertEquals(List.of(new Rectangle(183, 76, 84, 80)), f.requests(), "下一次以实际命中框为中心");
    }

    @Test void moderateMovementExpandsOnceAndFarMovementFallsBackToGlobal() throws Exception {
        Fixture f = new Fixture();
        f.place(200, 120); f.match(); f.resetRequests();
        f.place(270, 150);
        assertEquals(new Rectangle(270, 150, 20, 16), f.match().getMatchBounds());
        assertEquals(new Rectangle(168, 88, 84, 80), f.requests().get(0));
        assertTrue(f.requests().contains(new Rectangle(104, 24, 212, 208)) || f.requests().contains(f.full()));
        assertTrue(f.requests().size() >= 2 && f.requests().size() <= 3);
        f.resetRequests();
        f.place(650, 300);
        assertEquals(new Rectangle(650, 300, 20, 16), f.match().getMatchBounds());
        assertTrue(f.requests().size() >= 2 && f.requests().size() <= 3);
        assertTrue(f.requests().contains(f.full()));
        f.resetRequests();
        assertTrue(f.match().isFound());
        assertEquals(List.of(new Rectangle(618, 268, 84, 80)), f.requests());
    }

    @Test void expandedCaptureRefreshesTheOriginalAreaInsteadOfExcludingIt() throws Exception {
        Fixture f = new Fixture();
        f.matcher.rememberCapture(f.templatePath, new Rectangle(200, 120, 20, 16));
        assertTrue(f.requests().isEmpty(), "登记录制坐标不能截图");
        f.desktop.beforeCapture = () -> {
            if (f.desktop.captures.size() == 1) f.place(200, 120);
        };
        assertTrue(f.match().isFound());
        assertTrue(f.requests().size() >= 2 && f.requests().size() <= 3, "目标在后续截图中才出现于原局扫区域");
        assertTrue(f.requests().stream().skip(1).anyMatch(bounds -> bounds.contains(new Rectangle(200, 120, 20, 16))));
    }

    @Test void noHitReturnsNoStaleBoundsAndKeepsHintForLaterRetry() throws Exception {
        Fixture f = new Fixture();
        f.place(200, 120); f.match(); f.resetRequests(); f.clearFrame();
        ImageMatchResult miss = f.match();
        assertFalse(miss.isFound());
        assertNull(miss.getMatchBounds());
        assertEquals(3, f.requests().size());
        f.resetRequests(); f.place(200, 120);
        assertTrue(f.match().isFound());
        assertEquals(1, f.requests().size());
        assertNotEquals(f.full(), f.requests().get(0));
    }

    @Test void nodesHaveIndependentLocationsAndLocalHitTakesPriorityOverGlobalDuplicate() throws Exception {
        Fixture f = new Fixture();
        f.place(200, 120); f.match();
        MacroNode other = node("other", f.templatePath);
        f.resetRequests(); f.place(600, 250);
        assertTrue(f.matcher.match(other, new FlowContext()).isFound());
        assertEquals(List.of(f.full()), f.requests());
        paste(f.desktop.frames.get("main"), f.template, 200, 120);
        f.resetRequests();
        assertEquals(new Rectangle(200, 120, 20, 16), f.match().getMatchBounds());
        assertEquals(1, f.requests().size());
        f.resetRequests();
        assertEquals(new Rectangle(600, 250, 20, 16), f.matcher.match(other, new FlowContext()).getMatchBounds());
        assertEquals(1, f.requests().size());
    }

    @Test void geometryDpiAndTemplateMetadataChangesInvalidateHints() throws Exception {
        Fixture f = new Fixture();
        f.place(200, 120); f.match(); f.resetRequests();
        f.desktop.screens = List.of(ScreenRegionCaptureTest.geometry("main", f.full(), 1.5));
        f.desktop.frames.put("main", new BufferedImage(1200, 600, BufferedImage.TYPE_INT_RGB));
        paste(f.desktop.frames.get("main"), f.template, 300, 180);
        assertEquals(new Rectangle(200, 120, 13, 11), f.match().getMatchBounds());
        assertEquals(List.of(f.full()), f.requests());
        f.resetRequests();
        f.node.getImageCheckConfig().setThreshold(.99);
        assertTrue(f.match().isFound());
        assertEquals(List.of(f.full()), f.requests());
        f.resetRequests();
        Files.setLastModifiedTime(f.templatePath, FileTime.fromMillis(Files.getLastModifiedTime(f.templatePath).toMillis() + 2000));
        assertTrue(f.match().isFound());
        assertEquals(List.of(f.full()), f.requests());
        f.resetRequests();
        f.desktop.screens = List.of(ScreenRegionCaptureTest.geometry("main", new Rectangle(-800, 0, 800, 400), 1.5));
        assertEquals(new Rectangle(-600, 120, 13, 11), f.match().getMatchBounds());
        assertEquals(List.of(new Rectangle(-800, 0, 800, 400)), f.requests());
    }

    @Test void crossingDisplaysUsesClippedCaptureAndGlobalRelocationCanReachAnotherScreen() throws Exception {
        Fixture f = new Fixture();
        f.desktop.screens = List.of(
                ScreenRegionCaptureTest.geometry("left", new Rectangle(-400, 0, 400, 400), 1.5),
                ScreenRegionCaptureTest.geometry("main", f.full(), 1));
        f.desktop.frames.put("left", new BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB));
        f.place(400, 150); f.match(); f.resetRequests();
        f.clearFrame(); paste(f.desktop.frames.get("left"), f.template, 90, 120);
        assertEquals(new Rectangle(-340, 80, 13, 11), f.match().getMatchBounds());
        assertTrue(f.requests().size() >= 3 && f.requests().size() <= 4);
        assertTrue(f.requests().contains(new Rectangle(-400, 0, 400, 400)));
        assertTrue(f.requests().contains(f.full()), "并行全扫仍应覆盖两块显示器");
        f.resetRequests();
        assertEquals(new Rectangle(-340, 80, 13, 11), f.match().getMatchBounds());
        assertEquals(List.of(new Rectangle(-372, 48, 77, 75)), f.requests());
    }

    @Test void scaleMatchingWorksInsideLocalAreaAndItsConfigChangeForcesNewSearch() throws Exception {
        Fixture f = new Fixture();
        f.node.getImageCheckConfig().setScaleMatchingEnabled(true);
        f.place(200, 120); f.match(); f.resetRequests(); f.clearFrame();
        BufferedImage target = new BufferedImage(30, 24, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(f.template, 0, 0, 30, 24, null); graphics.dispose();
        paste(f.desktop.frames.get("main"), target, 205, 125);
        assertEquals(new Rectangle(205, 125, 30, 24), f.match().getMatchBounds());
        assertEquals(1, f.requests().size());
        f.resetRequests(); f.node.getImageCheckConfig().setScaleMatchingEnabled(false);
        assertFalse(f.match().isFound());
        assertEquals(List.of(f.full()), f.requests());
    }

    @Test void cancellationBeforeOrAfterLocalCaptureDoesNotStartAnotherStage() throws Exception {
        Fixture f = new Fixture();
        f.matcher.rememberCapture(f.templatePath, new Rectangle(200, 120, 20, 16));
        assertThrows(CancellationException.class, () -> f.matcher.match(f.node, new FlowContext(), () -> true));
        assertTrue(f.requests().isEmpty());
        AtomicBoolean stop = new AtomicBoolean();
        f.desktop.beforeCapture = () -> stop.set(true);
        assertThrows(CancellationException.class, () -> f.matcher.match(f.node, new FlowContext(), stop::get));
        assertEquals(1, f.requests().size());
        f.resetRequests();
        try {
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class, f::match);
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(f.requests().isEmpty());
        } finally { Thread.interrupted(); }
    }

    @Test void editingRecordedNodesDoesNotResurrectTheirOriginalCaptureHint() throws Exception {
        Fixture f = new Fixture(); f.place(200, 120);
        f.matcher.rememberCapture(f.templatePath, new Rectangle(200, 120, 20, 16));
        assertTrue(f.match().isFound());
        assertNotEquals(f.full(), f.requests().get(0));
        f.resetRequests(); f.node.getImageCheckConfig().setThreshold(.99);
        assertTrue(f.match().isFound());
        assertEquals(List.of(f.full()), f.requests());
        f.resetRequests();
        Path replacement = f.root.resolve("replacement.png");
        ImageIO.write(f.template, "png", replacement.toFile());
        f.node.getImageCheckConfig().setImagePath(replacement.toString());
        assertTrue(f.match().isFound());
        assertEquals(List.of(f.full()), f.requests());
    }

    @Test void localRangesCoveringTheWholeDisplayDoNotCaptureItRepeatedly() throws Exception {
        Fixture f = new Fixture();
        Rectangle screen = new Rectangle(0, 0, 100, 100);
        f.desktop.screens = List.of(ScreenRegionCaptureTest.geometry("main", screen, 1));
        BufferedImage template = pattern(80, 80);
        ImageIO.write(template, "png", f.templatePath.toFile());
        f.desktop.frames.put("main", new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB));
        paste(f.desktop.frames.get("main"), template, 10, 10);
        f.matcher.rememberCapture(f.templatePath, new Rectangle(10, 10, 80, 80));
        assertTrue(f.match().isFound());
        assertEquals(List.of(screen), f.requests());
    }

    @Test void loadValidationAndCacheResetNeverCaptureOrPersistCoordinates() throws Exception {
        Fixture f = new Fixture();
        MacroStorage storage = new MacroStorage();
        MacroFlow flow = MacroFlowFactory.rebuildLinear(List.of(f.node), "end");
        Path mr = f.root.resolve("before.mr");
        storage.save(List.of(), null, flow, mr.toString());
        f.matcher.rememberCapture(f.templatePath, new Rectangle(200, 120, 20, 16));
        f.matcher.validate(f.node);
        assertTrue(f.requests().isEmpty());
        f.place(200, 120); assertTrue(f.match().isFound());
        assertNotEquals(f.full(), f.requests().get(0), "录制坐标可用于首次局扫");
        Path after = f.root.resolve("after.mr");
        storage.save(List.of(), null, flow, after.toString());
        assertEquals(Files.readString(mr), Files.readString(after), "位置缓存不能进入 MR");
        f.resetRequests();
        MacroNode loaded = storage.load(mr.toString()).getFlow().getNodeMap().get("image");
        f.matcher.clearLocationCache(); f.matcher.validate(loaded);
        assertTrue(f.requests().isEmpty());
        assertTrue(f.matcher.match(loaded, new FlowContext()).isFound());
        assertEquals(List.of(f.full()), f.requests(), "加载后第一次实际执行才全扫");
        f.resetRequests();
        Files.delete(f.templatePath);
        assertThrows(java.io.IOException.class, f::match);
        assertTrue(f.requests().isEmpty(), "图片已缓存也不能忽略文件被移走");
    }

    @Test void playbackPreflightDoesNotScanLaterNodes() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        Fixture f = new Fixture(); f.place(200, 120);
        MacroNode second = node("second", f.templatePath);
        MacroFlow flow = MacroFlowFactory.rebuildLinear(List.of(f.node, second), "end");
        Robot noInput = new Robot() {
            @Override public void mouseMove(int x, int y) { fail("不应发送输入"); }
            @Override public void mousePress(int mask) { fail("不应发送输入"); }
            @Override public void mouseRelease(int mask) { fail("不应发送输入"); }
            @Override public void keyPress(int key) { fail("不应发送输入"); }
            @Override public void keyRelease(int key) { fail("不应发送输入"); }
        };
        MacroPlayer player = new MacroPlayer();
        var robotField = MacroPlayer.class.getDeclaredField("robot");
        robotField.setAccessible(true);
        robotField.set(player, noInput);
        player.setOnProgress(progress -> {
            if (progress.phase() == PlaybackProgress.Phase.NODE_STARTED) {
                assertEquals(progress.nodeId().equals("image") ? 0 : 1, f.requests().size());
            }
        });
        player.play(flow, 1, f.matcher);
        assertEquals(List.of(f.full(), f.full()), f.requests());
    }

    @Test void marginsAreBasedOnHitSizeAndBoundedCacheEvictsOldHints() {
        assertEquals(new Rectangle(68, 68, 84, 80),
                ImageSearchLocationCache.searchBounds(new Rectangle(100, 100, 20, 16), false));
        assertEquals(new Rectangle(-100, -25, 1200, 750),
                ImageSearchLocationCache.searchBounds(new Rectangle(100, 100, 800, 500), false));
        assertEquals(new Rectangle(-500, -275, 2000, 1250),
                ImageSearchLocationCache.searchBounds(new Rectangle(100, 100, 800, 500), true));
        var cache = new ImageSearchLocationCache();
        var stamp = new ImageSearchLocationCache.TemplateStamp(Path.of("synthetic.png"), FileTime.fromMillis(0), 1);
        List<ScreenRecognitionService.ScreenGeometry> screens = List.of(ScreenRegionCaptureTest.geometry("main", new Rectangle(0, 0, 800, 400), 1));
        for (int i = 0; i < 129; i++) cache.remember(new ImageSearchLocationCache.SearchKey("node" + i, stamp, 1, false),
                new Rectangle(i, 0, 20, 16), screens);
        assertNull(cache.find(new ImageSearchLocationCache.SearchKey("node0", stamp, 1, false), screens));
        assertNotNull(cache.find(new ImageSearchLocationCache.SearchKey("node128", stamp, 1, false), screens));
    }

    static MacroNode node(String id, Path path) {
        ImageCheckConfig config = new ImageCheckConfig(path.toString(), 1);
        config.setFailureBehavior(ImageCheckBehavior.TERMINATE);
        return new MacroNode(id, NodeType.IMAGE_CHECK, null, config, new ArrayList<>());
    }

    static BufferedImage pattern(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(138);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) image.setRGB(x, y, random.nextInt(0x1000000));
        return image;
    }

    static void paste(BufferedImage source, BufferedImage template, int x, int y) {
        Graphics2D graphics = source.createGraphics(); graphics.drawImage(template, x, y, null); graphics.dispose();
    }

    static final class Fixture {
        final Path root;
        final Path templatePath;
        final BufferedImage template = pattern(20, 16);
        final Desktop desktop = new Desktop();
        final OpenCvImageMatcher matcher;
        final MacroNode node;
        Fixture() throws Exception {
            this(RecognitionSession::new);
        }
        Fixture(java.util.function.Supplier<RecognitionSession> sessions) throws Exception {
            Path sandbox = Path.of(System.getProperty("macro.test.sandboxRoot", "target/test-sandboxes"));
            Files.createDirectories(sandbox);
            root = Files.createTempDirectory(sandbox, "adaptive-search-");
            templatePath = root.resolve("template.png"); ImageIO.write(template, "png", templatePath.toFile());
            desktop.screens = List.of(ScreenRegionCaptureTest.geometry("main", full(), 1));
            clearFrame();
            matcher = new OpenCvImageMatcher(root::toFile,
                    new ScreenRecognitionService(() -> desktop.screens, desktop::capture, sessions));
            node = node("image", templatePath);
        }
        Rectangle full() { return new Rectangle(0, 0, 800, 400); }
        void clearFrame() { desktop.frames.put("main", new BufferedImage(800, 400, BufferedImage.TYPE_INT_RGB)); }
        void place(int x, int y) { clearFrame(); paste(desktop.frames.get("main"), template, x, y); }
        ImageMatchResult match() throws Exception {
            try { return matcher.match(node, new FlowContext()); }
            finally { awaitRaceCleanup(); }
        }
        List<Rectangle> requests() { return desktop.captures.stream().map(Capture::bounds).toList(); }
        void resetRequests() { desktop.captures.clear(); }
    }

    record Capture(String screen, Rectangle bounds) { }
    static final class Desktop {
        volatile List<ScreenRecognitionService.ScreenGeometry> screens;
        final Map<String, BufferedImage> frames = new ConcurrentHashMap<>();
        final List<Capture> captures = new CopyOnWriteArrayList<>();
        volatile Runnable beforeCapture = () -> { };
        BaseMultiResolutionImage capture(ScreenRecognitionService.ScreenGeometry screen, Rectangle bounds) {
            beforeCapture.run();
            captures.add(new Capture(screen.id(), new Rectangle(bounds)));
            int x = (int) Math.round((bounds.x - screen.bounds().x) * screen.scaleX());
            int y = (int) Math.round((bounds.y - screen.bounds().y) * screen.scaleY());
            int width = (int) Math.round(bounds.width * screen.scaleX());
            int height = (int) Math.round(bounds.height * screen.scaleY());
            return new BaseMultiResolutionImage(frames.get(screen.id()).getSubimage(x, y, width, height));
        }
    }

    /** 测试换帧前等待模拟后台收尾；生产返回路径不等待。 */
    static void awaitRaceCleanup() throws Exception {
        boolean interrupted = Thread.interrupted();
        try {
        var field = ScreenSearchRace.class.getDeclaredField("SLOTS"); field.setAccessible(true);
        Semaphore slots = (Semaphore) field.get(null);
        assertTrue(slots.tryAcquire(2, 5, TimeUnit.SECONDS), "后台搜索未及时释放配额");
        slots.release(2);
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
