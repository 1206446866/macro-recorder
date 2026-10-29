package com.macro.recognition;

import java.awt.AWTException;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.awt.image.MultiResolutionImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import com.macro.flow.ImageCheckConfig;
import com.macro.flow.ImageSearchScope;
import com.macro.ui.capture.CaptureWindowService;
import java.io.IOException;

/** 采集所有显示器并在屏幕图像中执行模板识别。 */
public final class ScreenRecognitionService {

    private static final int MAX_RECOGNITION_RESULT_COUNT = 100;

    private final OpenCvTemplateMatcher openCvTemplateMatcher =
            new OpenCvTemplateMatcher();
    private final Supplier<List<ScreenGeometry>> geometrySource;
    private final CaptureSource captureSource;
    private final Supplier<RecognitionSession> sessionSource;
    private final WindowRecognitionScope windowScopes;

    public ScreenRecognitionService() {
        this(ScreenRecognitionService::desktopGeometry, ScreenRecognitionService::captureDesktop);
    }

    /** 注入合成屏幕以检查局部捕获，不接触真实桌面。 */
    ScreenRecognitionService(Supplier<List<ScreenGeometry>> geometrySource, CaptureSource captureSource) {
        this(geometrySource, captureSource, RecognitionSession::new);
    }

    ScreenRecognitionService(Supplier<List<ScreenGeometry>> geometrySource, CaptureSource captureSource,
                             Supplier<RecognitionSession> sessionSource) {
        this(geometrySource, captureSource, sessionSource, new CaptureWindowService());
    }

    ScreenRecognitionService(Supplier<List<ScreenGeometry>> geometrySource, CaptureSource captureSource,
                             Supplier<RecognitionSession> sessionSource, CaptureWindowService windows) {
        this.geometrySource = geometrySource;
        this.captureSource = captureSource;
        this.sessionSource = sessionSource;
        windowScopes = new WindowRecognitionScope(windows);
    }

    public void clearWindowBindings() { windowScopes.clear(); }

    /** 仅在识别请求到达时解析窗口，载入配置和播放预检不枚举窗口或截图。 */
    public CaptureScope captureScope(ImageCheckConfig config, BooleanSupplier stop) throws IOException {
        checkCaptureCancelled(stop);
        config.validateSearchScope();
        List<ScreenGeometry> desktop = screenGeometry();
        return new CaptureScope(desktop, config.getSearchScope() == ImageSearchScope.WINDOW
                ? windowScopes.resolve(config.getSearchWindow(), desktop) : null);
    }

    public final class CaptureScope {
        private final List<ScreenGeometry> desktop;
        private final WindowRecognitionScope.Area window;
        private CaptureScope(List<ScreenGeometry> desktop, WindowRecognitionScope.Area window) { this.desktop = desktop; this.window = window; }
        List<ScreenGeometry> screens() { return window == null ? desktop : window.screens(); }
        String cacheKey() { return window == null ? "DESKTOP" : window.key(); }
        public void verify(BooleanSupplier stop) throws IOException {
            checkCaptureCancelled(stop);
            if (window != null) {
                if (!desktop.equals(screenGeometry())) throw new IOException("显示器布局或 DPI 已变化，请重新识别");
                window.verify();
            }
        }
        public List<ScreenSnapshot> captureScreens(Rectangle region, BooleanSupplier stop) throws IOException, AWTException {
            verify(stop);
            List<ScreenSnapshot> snapshots = window == null
                    ? ScreenRecognitionService.this.captureScreens(screens(), region, stop)
                    : ScreenRecognitionService.this.captureScreens(window.captureRegions(region), null, stop);
            verify(stop);
            return snapshots;
        }
    }

    RecognitionSession beginRecognition() { return sessionSource.get(); }

    /** 仅读取显示器位置与 DPI，不截屏；位置缓存据此失效。 */
    List<ScreenGeometry> screenGeometry() {
        return List.copyOf(geometrySource.get());
    }

    /** 采集当前所有显示器的最高分辨率画面。 */
    public List<ScreenSnapshot> captureScreens() throws AWTException {
        return captureScreens(screenGeometry(), null, () -> false);
    }

    /** region 为空时全屏捕获，否则只捕获与选区相交的显示器部分。 */
    List<ScreenSnapshot> captureScreens(List<ScreenGeometry> screens, Rectangle region,
                                      BooleanSupplier stopRequested) throws AWTException {
        List<ScreenSnapshot> snapshots = new ArrayList<>(screens.size());
        for (ScreenGeometry screen : screens) {
            checkCaptureCancelled(stopRequested);
            Rectangle bounds = screen.bounds();
            if (region != null) bounds = bounds.intersection(region);
            if (bounds.isEmpty()) continue;
            MultiResolutionImage capture = captureSource.capture(screen, new Rectangle(bounds));
            checkCaptureCancelled(stopRequested);
            BufferedImage image = copyHighestResolutionVariant(capture);
            snapshots.add(new ScreenSnapshot(image, bounds, screen.displayName()));
        }
        checkCaptureCancelled(stopRequested);
        return snapshots;
    }

    private static void checkCaptureCancelled(BooleanSupplier stopRequested) {
        if (Thread.currentThread().isInterrupted() || stopRequested.getAsBoolean()) {
            throw new CancellationException("图像识别已取消");
        }
    }

    private static List<ScreenGeometry> desktopGeometry() {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("当前环境不支持屏幕图像识别");
        }
        GraphicsDevice[] devices = GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .getScreenDevices();
        List<ScreenGeometry> screens = new ArrayList<>(devices.length);
        for (int index = 0; index < devices.length; index++) {
            GraphicsDevice device = devices[index];
            GraphicsConfiguration configuration = device.getDefaultConfiguration();
            screens.add(new ScreenGeometry(device.getIDstring(), configuration.getBounds(),
                    configuration.getDefaultTransform().getScaleX(),
                    configuration.getDefaultTransform().getScaleY(), "显示器 " + (index + 1)));
        }
        return screens;
    }

    private static MultiResolutionImage captureDesktop(ScreenGeometry screen, Rectangle bounds) throws AWTException {
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            if (device.getIDstring().equals(screen.id())) {
                return new Robot(device).createMultiResolutionScreenCapture(bounds);
            }
        }
        throw new AWTException("显示器配置已变化，请重新识别");
    }

    record ScreenGeometry(String id, Rectangle bounds, double scaleX, double scaleY, String displayName) {
        ScreenGeometry { bounds = new Rectangle(bounds); }
        @Override public Rectangle bounds() { return new Rectangle(bounds); }
    }

    @FunctionalInterface
    interface CaptureSource {
        MultiResolutionImage capture(ScreenGeometry screen, Rectangle bounds) throws AWTException;
    }

    /** 在已采集的所有屏幕中查找相互独立的匹配位置。 */
    public RecognitionReport recognize(
            List<ScreenSnapshot> snapshots,
            BufferedImage template,
            double threshold
    ) {
        return recognize(snapshots, template, threshold, false);
    }

    /** 按动作开关匹配已有快照，原尺寸命中时不启动缩放搜索。 */
    public RecognitionReport recognize(List<ScreenSnapshot> snapshots, BufferedImage template,
                                        double threshold, boolean scaleMatchingEnabled) {
        return recognize(snapshots, template, threshold, scaleMatchingEnabled, () -> false);
    }

    /** 测试窗口和播放器共用的可取消入口，一次快照依次用于所有匹配比例。 */
    public RecognitionReport recognize(List<ScreenSnapshot> snapshots, BufferedImage template,
                                        double threshold, boolean scaleMatchingEnabled, BooleanSupplier stopRequested) {
        return recognize(snapshots, template, threshold, scaleMatchingEnabled, stopRequested, TemplateScaleRange.DEFAULT);
    }

    public RecognitionReport recognize(List<ScreenSnapshot> snapshots, BufferedImage template,
                                        double threshold, boolean scaleMatchingEnabled, BooleanSupplier stopRequested,
                                        TemplateScaleRange range) {
        try (RecognitionSession session = beginRecognition()) {
            return recognize(snapshots, template, threshold, scaleMatchingEnabled, stopRequested, session, true, range);
        }
    }

    RecognitionReport recognize(List<ScreenSnapshot> snapshots, BufferedImage template,
                                double threshold, boolean scaleMatchingEnabled, BooleanSupplier stopRequested,
                                RecognitionSession session, boolean allowParallelScales) {
        return recognize(snapshots, template, threshold, scaleMatchingEnabled, stopRequested, session,
                allowParallelScales, TemplateScaleRange.DEFAULT);
    }

    private RecognitionReport recognize(List<ScreenSnapshot> snapshots, BufferedImage template,
                                double threshold, boolean scaleMatchingEnabled, BooleanSupplier stopRequested,
                                RecognitionSession session, boolean allowParallelScales, TemplateScaleRange range) {
        java.util.Objects.requireNonNull(range);
        if (Thread.currentThread().isInterrupted() || stopRequested.getAsBoolean()) {
            throw new CancellationException("图像识别已取消");
        }
        long initializationMillis = openCvTemplateMatcher.ensureLoaded();
        if (snapshots == null || snapshots.isEmpty()) {
            return RecognitionReport.empty(
                    initializationMillis
            );
        }
        List<RecognitionResult> matches = new ArrayList<>();
        RecognitionResult bestCandidate = null;
        List<OpenCvTemplateMatcher.MatchResult> originalCandidates = new ArrayList<>();
        boolean truncated = false;
        long estimatedWorkingMemoryBytes = 0L;
        long startedAt = System.nanoTime();
        long elapsedMillis;

        {
            for (ScreenSnapshot snapshot : snapshots) {
                if (Thread.currentThread().isInterrupted() || stopRequested.getAsBoolean()) {
                    throw new CancellationException("图像识别已取消");
                }
                int remainingResultCount = Math.max(
                        1,
                        MAX_RECOGNITION_RESULT_COUNT - matches.size()
                );
                OpenCvTemplateMatcher.SearchResult searchResult =
                        openCvTemplateMatcher.findAll(
                                snapshot.image(),
                                template,
                                threshold,
                                remainingResultCount,
                                stopRequested
                        );
                originalCandidates.add(searchResult.bestCandidate());
                truncated |= searchResult.truncated();
                for (OpenCvTemplateMatcher.MatchResult match
                        : searchResult.matches()) {
                    if (matches.size() >= MAX_RECOGNITION_RESULT_COUNT) {
                        truncated = true;
                        break;
                    }
                    matches.add(toRecognitionResult(snapshot, match));
                }
                RecognitionResult current = toRecognitionResult(
                        snapshot,
                        searchResult.bestCandidate()
                );
                if (current != null && (bestCandidate == null
                        || current.similarity() > bestCandidate.similarity())) {
                    bestCandidate = current;
                }
                estimatedWorkingMemoryBytes = Math.max(
                        estimatedWorkingMemoryBytes,
                        openCvTemplateMatcher.estimateWorkingMemoryBytes(
                                snapshot.image(),
                                template
                        )
                );
            }
            if (scaleMatchingEnabled && matches.isEmpty()) {
                for (int index = 0; index < snapshots.size(); index++) {
                    ScreenSnapshot snapshot = snapshots.get(index);
                    OpenCvTemplateMatcher.SearchResult scaled = openCvTemplateMatcher.findAllScaled(
                            snapshot.image(), template, threshold,
                            Math.max(1, MAX_RECOGNITION_RESULT_COUNT - matches.size()),
                            originalCandidates.get(index), stopRequested, allowParallelScales, range);
                    truncated |= scaled.truncated();
                    for (OpenCvTemplateMatcher.MatchResult match : scaled.matches()) {
                        if (matches.size() >= MAX_RECOGNITION_RESULT_COUNT) {
                            truncated = true;
                            break;
                        }
                        matches.add(toRecognitionResult(snapshot, match));
                    }
                    RecognitionResult candidate = toRecognitionResult(snapshot, scaled.bestCandidate());
                    if (candidate != null && (bestCandidate == null || candidate.similarity() > bestCandidate.similarity())) {
                        bestCandidate = candidate;
                    }
                    estimatedWorkingMemoryBytes = Math.max(estimatedWorkingMemoryBytes,
                            openCvTemplateMatcher.estimateScaleWorkingMemoryBytes(snapshot.image(), template, range));
                }
                estimatedWorkingMemoryBytes += openCvTemplateMatcher.scaleCacheBytes();
            }
            elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
        }

        matches.sort(Comparator.comparingDouble(
                RecognitionResult::similarity
        ).reversed());
        return new RecognitionReport(
                matches,
                bestCandidate,
                truncated,
                elapsedMillis,
                initializationMillis,
                session.heapDeltaBytes(),
                session.peakIncreaseBytes(),
                estimatedWorkingMemoryBytes
        );
    }

    /** 将 OpenCV 像素匹配结果转换为全局逻辑坐标结果。 */
    private RecognitionResult toRecognitionResult(
            ScreenSnapshot snapshot,
            OpenCvTemplateMatcher.MatchResult match
    ) {
        if (match == null) {
            return null;
        }
        Rectangle pixelBounds = match.pixelBounds();
        return new RecognitionResult(
                match.matched(),
                match.similarity(),
                toLogicalBounds(snapshot, pixelBounds),
                new Rectangle(pixelBounds),
                snapshot.image(),
                snapshot.displayName()
        );
    }

    /** 将截图像素坐标换算为 Swing/Robot 使用的屏幕逻辑坐标。 */
    private Rectangle toLogicalBounds(
            ScreenSnapshot snapshot,
            Rectangle pixelBounds
    ) {
        Rectangle screenBounds = snapshot.logicalBounds();
        double scaleX = snapshot.image().getWidth()
                / (double) Math.max(1, screenBounds.width);
        double scaleY = snapshot.image().getHeight()
                / (double) Math.max(1, screenBounds.height);
        int x = Math.max(0, Math.min(screenBounds.width - 1, (int)Math.round(pixelBounds.x / scaleX)));
        int y = Math.max(0, Math.min(screenBounds.height - 1, (int)Math.round(pixelBounds.y / scaleY)));
        // 原点和宽高分别舍入时，靠右/下的结果可能多出一个逻辑像素；点击框必须留在截图范围内。
        return new Rectangle(screenBounds.x + x, screenBounds.y + y,
                Math.max(1, Math.min(screenBounds.width - x, (int)Math.round(pixelBounds.width / scaleX))),
                Math.max(1, Math.min(screenBounds.height - y, (int)Math.round(pixelBounds.height / scaleY))));
    }

    /** 复制多分辨率截图中像素数量最多的版本。 */
    private BufferedImage copyHighestResolutionVariant(MultiResolutionImage capture) {
        Image highestResolution = capture.getResolutionVariants().stream()
                .filter(image -> image.getWidth(null) > 0 && image.getHeight(null) > 0)
                .max(Comparator.comparingLong(image ->
                        (long) image.getWidth(null) * image.getHeight(null)))
                .orElseThrow(() -> new IllegalStateException("未获取到屏幕图像"));

        BufferedImage copied = new BufferedImage(
                highestResolution.getWidth(null),
                highestResolution.getHeight(null),
                BufferedImage.TYPE_INT_ARGB
        );
        Graphics2D graphics = copied.createGraphics();
        graphics.drawImage(highestResolution, 0, 0, null);
        graphics.dispose();
        return copied;
    }

    /** 单个显示器的截图及其逻辑坐标范围。 */
    public record ScreenSnapshot(
            BufferedImage image,
            Rectangle logicalBounds,
            String displayName
    ) {
    }

    /** 单个识别位置及其所在屏幕图像。 */
    public record RecognitionResult(
            boolean matched,
            double similarity,
            Rectangle logicalBounds,
            Rectangle pixelBounds,
            BufferedImage screenImage,
            String displayName
    ) {
    }

    /** 跨显示器识别报告，包含全部命中和未达阈值时的最佳候选。 */
    public record RecognitionReport(
            List<RecognitionResult> matches,
            RecognitionResult bestCandidate,
            boolean truncated,
            long elapsedMillis,
            long initializationMillis,
            long heapDeltaBytes,
            long peakWorkingSetIncreaseBytes,
            long estimatedWorkingMemoryBytes
    ) {

        /** 复制命中列表形成报告快照，空列表引用按没有命中处理。 */
        public RecognitionReport {
            matches = matches == null ? List.of() : List.copyOf(matches);
        }

        /** 生成空屏幕列表对应的报告，保留初始化耗时，并标记工作集统计不可用。 */
        private static RecognitionReport empty(
                long initializationMillis
        ) {
            return new RecognitionReport(
                    List.of(),
                    null,
                    false,
                    0L,
                    initializationMillis,
                    0L,
                    -1L,
                    0L
            );
        }
    }
}
