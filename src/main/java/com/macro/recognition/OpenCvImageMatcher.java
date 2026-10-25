package com.macro.recognition;

import com.macro.flow.FlowContext;
import com.macro.flow.ImageCheckConfig;
import com.macro.flow.ImageMatchResult;
import com.macro.flow.ImageMatcher;
import com.macro.flow.MacroNode;
import com.macro.util.ImageTemplatePathUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/**
 * 将图像检查节点接入现有 OpenCV 屏幕识别服务，供播放器执行成功或失败分支。
 * 负责模板路径解析、读取缓存和匹配结果转换，不选择分支，也不发送键鼠输入。
 */
public final class OpenCvImageMatcher implements ImageMatcher {

    /** 复用已有的屏幕采集、DPI 换算和 OpenCV 模板匹配服务。 */
    private final ScreenRecognitionService recognitionService =
            new ScreenRecognitionService();

    /** 为相对模板路径提供当前 MR 文件所在目录。 */
    private final Supplier<File> baseDirectorySupplier;

    /** 最近一次解码的模板路径。 */
    private Path cachedPath;

    /** 最近一次模板文件修改时间，用于判断缓存是否失效。 */
    private long cachedLastModified;

    /** 缓存的模板像素，避免闭环重试时重复读取 PNG。 */
    private BufferedImage cachedTemplate;

    /**
     * 创建正式播放使用的 OpenCV 图像匹配器。
     *
     * @param baseDirectorySupplier 相对模板路径的基准目录提供者
     */
    public OpenCvImageMatcher(Supplier<File> baseDirectorySupplier) {
        this.baseDirectorySupplier = baseDirectorySupplier;
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
     * 截取所有显示器并匹配当前模板，返回相似度最高的命中及其屏幕逻辑坐标。
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
        ImageCheckConfig config = node.getImageCheckConfig();
        validateConfig(node, config);
        Path templatePath = resolvePath(config.getImagePath());
        String requestId = ImageRecognitionDiagnostics.nextRequestId("PLAYBACK");
        String input = "Request: " + requestId + "\nNodeId: " + node.getId()
                + "\nConfiguredTemplatePath: " + config.getImagePath()
                + "\nResolvedTemplatePath: " + templatePath
                + "\nThreshold: " + ImageRecognitionDiagnostics.similarity(config.getThreshold())
                + "\nEngine: OPEN_CV";
        ImageRecognitionDiagnostics.log("PLAYBACK", "MATCH_START", input);
        try {
            BufferedImage template = loadTemplate(templatePath);
            String foreground = ImageRecognitionDiagnostics.foregroundWindow();
            List<ScreenRecognitionService.ScreenSnapshot> snapshots =
                    recognitionService.captureScreens();
            String capturedAt = java.time.Instant.now().toString();
            String capture = "Request: " + requestId + "\nNodeId: " + node.getId()
                    + "\nCapturedAt: " + capturedAt
                    + "\nForegroundBeforeCapture: " + foreground
                    + "\nTemplatePixels: " + template.getWidth() + "x" + template.getHeight()
                    + "\nTemplateFileLastModified: " + templatePath.toFile().lastModified()
                    + "\n" + ImageRecognitionDiagnostics.describeScreens(snapshots);
            ImageRecognitionDiagnostics.log("PLAYBACK", "CAPTURE", capture);
            ScreenRecognitionService.RecognitionReport report =
                    recognitionService.recognize(
                            snapshots,
                            template,
                            config.getThreshold()
                    );

            ImageMatchResult result;
            if (!report.matches().isEmpty()) {
                ScreenRecognitionService.RecognitionResult best =
                        report.matches().get(0);
                result = ImageMatchResult.found(
                        best.similarity(),
                        best.logicalBounds()
                );
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
                    + "\nElapsedMillis: " + report.elapsedMillis();
            ImageRecognitionDiagnostics.log("PLAYBACK", "MATCH_RESULT", outcome);
            ImageRecognitionDiagnostics.saveCapture("PLAYBACK", requestId, snapshots, template,
                    input + "\n" + capture + "\n" + outcome);
            return result;
        } catch (Exception exception) {
            ImageRecognitionDiagnostics.log("PLAYBACK", "MATCH_ERROR",
                    input + "\nException: " + exception);
            throw exception;
        }
    }

    /** 检查配置、非空模板路径及 0～1 的有限阈值；此处不访问模板文件。 */
    private void validateConfig(MacroNode node, ImageCheckConfig config) {
        if (config == null) {
            throw new IllegalArgumentException(
                    "图像识别节点缺少配置：" + node.getId()
            );
        }
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
     * 读取模板像素；路径与文件修改时间均未变化时复用上次结果，减少重试时的重复解码。
     *
     * @throws IOException 文件不存在或无法解码为图片，失败时不覆盖原有缓存
     */
    private synchronized BufferedImage loadTemplate(Path path) throws IOException {
        File file = path.toFile();
        if (!file.isFile()) {
            throw new IOException("模板图片不存在：" + path);
        }
        long lastModified = file.lastModified();
        if (path.equals(cachedPath)
                && lastModified == cachedLastModified
                && cachedTemplate != null) {
            return cachedTemplate;
        }

        BufferedImage image = ImageIO.read(file);
        if (image == null) {
            throw new IOException("无法读取模板 PNG：" + path);
        }
        cachedPath = path;
        cachedLastModified = lastModified;
        cachedTemplate = image;
        return image;
    }
}
