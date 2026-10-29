package com.macro.recognition;

import com.macro.flow.FlowContext;
import com.macro.flow.ImageCheckConfig;
import com.macro.flow.ImageMatchResult;
import com.macro.flow.ImageMatcher;
import com.macro.flow.MacroNode;
import com.macro.util.ImageTemplatePathUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.Rectangle;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 将图像检查节点接入现有 OpenCV 屏幕识别服务，供播放器执行成功或失败分支。
 * 负责模板路径解析、读取缓存和匹配结果转换，不选择分支，也不发送键鼠输入。
 */
public final class OpenCvImageMatcher implements ImageMatcher {

    /** 复用已有的屏幕采集、DPI 换算和 OpenCV 模板匹配服务。 */
    private final ScreenRecognitionService recognitionService;
    private final ImageSearchLocationCache locations = new ImageSearchLocationCache();

    /** 为相对模板路径提供当前 MR 文件所在目录。 */
    private final Supplier<File> baseDirectorySupplier;

    /** 最近一次解码模板的路径、修改时间与文件大小。 */
    private ImageSearchLocationCache.TemplateStamp cachedStamp;

    /** 缓存的模板像素，避免闭环重试时重复读取 PNG。 */
    private BufferedImage cachedTemplate;

    /**
     * 创建正式播放使用的 OpenCV 图像匹配器。
     *
     * @param baseDirectorySupplier 相对模板路径的基准目录提供者
     */
    public OpenCvImageMatcher(Supplier<File> baseDirectorySupplier) {
        this(baseDirectorySupplier, new ScreenRecognitionService());
    }

    OpenCvImageMatcher(Supplier<File> baseDirectorySupplier, ScreenRecognitionService recognitionService) {
        this.baseDirectorySupplier = baseDirectorySupplier;
        this.recognitionService = recognitionService;
    }

    /** 成功载入另一份 MR 或开始新录制时调用；只清空提示，不采集桌面。 */
    public void clearLocationCache() {
        locations.clear();
        recognitionService.clearWindowBindings();
    }

    @Override public void beginPlayback() { recognitionService.clearWindowBindings(); }

    /** 录制截图保存成功后记住初始位置；定位提示失败不影响录制结果。 */
    public void rememberCapture(Path template, Rectangle bounds) {
        if (bounds == null || bounds.isEmpty()) return;
        try {
            locations.rememberCapture(ImageSearchLocationCache.TemplateStamp.read(template), bounds,
                    recognitionService.screenGeometry());
        } catch (IOException | RuntimeException exception) {
            ImageRecognitionDiagnostics.log("PLAYBACK", "LOCATION_HINT_SKIPPED", exception.toString());
        }
    }

    /**
     * 播放前检查节点配置和模板文件，避免执行输入动作后才发现模板不可用。
     *
     * @throws IllegalArgumentException 配置缺失、路径为空或格式非法、阈值超出有效范围
     * @throws IOException 模板路径不存在或不是普通文件；此检查不解码图片
     */
    @Override
    public void validate(MacroNode node) throws IOException {
        validateConfig(node, node.getImageCheckConfig());
        ImageTemplatePathUtil.requireTemplate(node,
                baseDirectorySupplier == null ? null : baseDirectorySupplier.get());
    }

    /**
     * 优先局扫，失败后竞速扩大局扫和全扫，返回获胜阶段中相似度最高的位置。
     * 未命中时仍保留最佳候选的相似度；采集或匹配异常继续交由播放器处理。
     *
     * @param node 当前 IMAGE_CHECK 节点
     * @param context 本轮流程上下文，当前匹配实现不读取或修改其中的状态
     * @return 包含最佳相似度及逻辑坐标区域的匹配结果
     * @throws Exception 截屏、模板读取或 OpenCV 匹配失败
     */
    @Override
    public ImageMatchResult match(
            MacroNode node,
            FlowContext context
    ) throws Exception {
        return match(node, context, () -> false);
    }

    /** 执行到节点才采集屏幕；各阶段重新截图，同一阶段各比例复用截图。 */
    @Override
    public ImageMatchResult match(MacroNode node, FlowContext context,
                                  BooleanSupplier stopRequested) throws Exception {
        ImageCheckConfig config = node.getImageCheckConfig();
        validateConfig(node, config);
        Path templatePath = resolvePath(config.getImagePath());
        String requestId = ImageRecognitionDiagnostics.nextRequestId("PLAYBACK");
        String input = "Request: " + requestId + "\nNodeId: " + node.getId()
                + "\nConfiguredTemplatePath: " + config.getImagePath()
                + "\nResolvedTemplatePath: " + templatePath
                + "\nThreshold: " + ImageRecognitionDiagnostics.similarity(config.getThreshold())
                + "\nScaleMatchingEnabled: " + config.isScaleMatchingEnabled()
                + "\nSearchScope: " + config.getSearchScope().name()
                + "\nEngine: OPEN_CV";
        ImageRecognitionDiagnostics.log("PLAYBACK", "MATCH_START", input);
        try {
            checkCancelled(stopRequested);
            long startedAt = System.nanoTime();
            var stamp = ImageSearchLocationCache.TemplateStamp.read(templatePath);
            BufferedImage template = loadTemplate(stamp);
            var scope = recognitionService.captureScope(config, stopRequested);
            var screens = scope.screens();
            var key = new ImageSearchLocationCache.SearchKey(node.getId(), stamp,
                    config.getThreshold(), config.isScaleMatchingEnabled(), scope.cacheKey());
            Rectangle previous = locations.find(key, screens);
            try (RecognitionSession session = recognitionService.beginRecognition()) {
                ScreenSearchRace.Outcome searchResult = search(scope, previous, template, config,
                        stopRequested, requestId, input, session);
                var report = searchResult.report();
                String stage = searchResult.stage();
                checkCancelled(stopRequested);
                scope.verify(stopRequested);

                ImageMatchResult result;
                if (!report.matches().isEmpty()) {
                    ScreenRecognitionService.RecognitionResult best =
                            report.matches().get(0);
                    result = ImageMatchResult.found(
                            best.similarity(),
                            best.logicalBounds()
                    );
                    locations.remember(key, best.logicalBounds(), screens);
                } else {
                    double bestSimilarity = report.bestCandidate() == null
                            ? 0.0
                            : report.bestCandidate().similarity();
                    result = ImageMatchResult.notFound(bestSimilarity);
                }
                String outcome = "Request: " + requestId + "\nNodeId: " + node.getId()
                        + "\nFound: " + result.isFound()
                        + "\nBestCandidateSimilarity: " + (report.bestCandidate() == null
                                ? "<无候选>" : ImageRecognitionDiagnostics.similarity(report.bestCandidate().similarity()))
                        + "\nMatchCount: " + report.matches().size()
                        + "\nMatchBounds: " + result.getMatchBounds()
                        + "\nSearchStage: " + stage
                        + "\nElapsedMillis: " + report.elapsedMillis()
                        + "\nTotalElapsedMillis: " + (System.nanoTime() - startedAt) / 1_000_000L;
                ImageRecognitionDiagnostics.log("PLAYBACK", "MATCH_RESULT", outcome);
                return result;
            }
        } catch (CancellationException cancelled) {
            ImageRecognitionDiagnostics.log("PLAYBACK", "MATCH_CANCELLED", "Request: " + requestId);
            throw cancelled;
        } catch (Exception exception) {
            ImageRecognitionDiagnostics.log("PLAYBACK", "MATCH_ERROR",
                    input + "\nException: " + exception);
            throw exception;
        }
    }

    private ScreenSearchRace.Outcome search(ScreenRecognitionService.CaptureScope scope,
            Rectangle previous, BufferedImage template, ImageCheckConfig config, BooleanSupplier stop,
            String requestId, String input, RecognitionSession session) throws Exception {
        var screens = scope.screens();
        if (previous != null) {
            List<Rectangle> fullBounds = captureBounds(screens, null);
            Rectangle local = ImageSearchLocationCache.searchBounds(previous, false);
            List<Rectangle> localBounds = captureBounds(screens, local);
            boolean localScanned = !localBounds.isEmpty() && !localBounds.equals(fullBounds);
            if (localScanned) {
                var result = searchStage(scope, local, template, config, stop, requestId, input, "LOCAL", session, true);
                if (result.found()) return result;
            }
            Rectangle expanded = ImageSearchLocationCache.searchBounds(previous, true);
            List<Rectangle> expandedBounds = captureBounds(screens, expanded);
            if (!expandedBounds.isEmpty() && !expandedBounds.equals(fullBounds)
                    && !(localScanned && expandedBounds.equals(localBounds))) {
                var winner = ScreenSearchRace.trySearch(
                        token -> searchStage(scope, expanded, template, config, token, requestId, input,
                                "EXPANDED", session, false),
                        token -> searchStage(scope, null, template, config, token, requestId, input,
                                "GLOBAL", session, true), stop, session);
                if (winner != null) return winner;
                // 前次原生任务尚未退出时保持串行，不等待配额或积压后台工作。
                var result = searchStage(scope, expanded, template, config, stop, requestId, input,
                        "EXPANDED", session, true);
                if (result.found()) return result;
            }
        }
        return searchStage(scope, null, template, config, stop, requestId, input, "GLOBAL", session, true);
    }

    private static List<Rectangle> captureBounds(List<ScreenRecognitionService.ScreenGeometry> screens,
                                               Rectangle region) {
        return screens.stream().map(screen -> region == null ? screen.bounds() : screen.bounds().intersection(region))
                .filter(bounds -> !bounds.isEmpty()).toList();
    }

    private ScreenSearchRace.Outcome searchStage(
            ScreenRecognitionService.CaptureScope scope, Rectangle region, BufferedImage template,
            ImageCheckConfig config, BooleanSupplier stopRequested, String requestId, String input, String stage,
            RecognitionSession session, boolean allowParallelScales
    ) throws Exception {
        checkCancelled(stopRequested);
        String foreground = ImageRecognitionDiagnostics.foregroundWindow();
        List<ScreenRecognitionService.ScreenSnapshot> snapshots =
                scope.captureScreens(region, stopRequested);
        String capture = "Request: " + requestId + "\nSearchStage: " + stage
                + "\nSearchRegion: " + (region == null ? "<所有显示器>" : region)
                + "\nCapturedAt: " + java.time.Instant.now()
                + "\nForegroundBeforeCapture: " + foreground
                + "\nTemplatePixels: " + template.getWidth() + "x" + template.getHeight()
                + "\n" + ImageRecognitionDiagnostics.describeScreens(snapshots);
        ImageRecognitionDiagnostics.log("PLAYBACK", "CAPTURE", capture);
        var report = recognitionService.recognize(snapshots, template, config.getThreshold(),
                config.isScaleMatchingEnabled(), stopRequested, session, allowParallelScales);
        scope.verify(stopRequested);
        ImageRecognitionDiagnostics.saveCapture("PLAYBACK", requestId + "-" + stage, snapshots, template,
                input + "\n" + capture + "\nMatchCount: " + report.matches().size()
                        + "\nElapsedMillis: " + report.elapsedMillis());
        return new ScreenSearchRace.Outcome(stage, report);
    }

    private static void checkCancelled(BooleanSupplier stopRequested) {
        if (Thread.currentThread().isInterrupted() || stopRequested.getAsBoolean()) {
            throw new CancellationException("图像识别已取消");
        }
    }

    /** 检查配置、非空模板路径及 0～1 的有限阈值；此处不访问模板文件。 */
    private void validateConfig(MacroNode node, ImageCheckConfig config) {
        if (config == null) {
            throw new IllegalArgumentException(
                    "图像识别节点缺少配置：" + node.getId()
            );
        }
        config.validateSearchScope();
        if (config.getImagePath() == null
                || config.getImagePath().isBlank()) {
            throw new IllegalArgumentException(
                    "图像识别节点缺少模板路径：" + node.getId()
            );
        }
        if (!Double.isFinite(config.getThreshold())
                || config.getThreshold() < 0.0
                || config.getThreshold() > 1.0) {
            throw new IllegalArgumentException(
                    "图像识别节点匹配阈值必须位于 0 到 1 之间："
                            + node.getId()
            );
        }
    }

    /** 按当前 MR 目录和公共路径规则解析模板，绝对路径仍沿用原文件位置。 */
    private Path resolvePath(String imagePath) {
        return ImageTemplatePathUtil.resolve(imagePath,
                baseDirectorySupplier == null ? null : baseDirectorySupplier.get());
    }

    /**
     * 读取模板像素；路径、文件修改时间和大小均未变化时复用上次结果，减少重试时的重复解码。
     *
     * @throws IOException 文件不存在或无法解码为图片，失败时不覆盖原有缓存
     */
    private synchronized BufferedImage loadTemplate(ImageSearchLocationCache.TemplateStamp stamp) throws IOException {
        if (stamp.equals(cachedStamp) && cachedTemplate != null) {
            return cachedTemplate;
        }

        BufferedImage image = ImageIO.read(stamp.path().toFile());
        if (image == null) {
            throw new IOException("无法读取模板 PNG：" + stamp.path());
        }
        cachedStamp = stamp;
        cachedTemplate = image;
        return image;
    }
}
