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

/** 采集所有显示器并在屏幕图像中执行模板识别。 */
public final class ScreenRecognitionService {

    private static final int MAX_RECOGNITION_RESULT_COUNT = 100;

    private final OpenCvTemplateMatcher openCvTemplateMatcher =
            new OpenCvTemplateMatcher();

    /** 采集当前所有显示器的最高分辨率画面。 */
    public List<ScreenSnapshot> captureScreens() throws AWTException {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("当前环境不支持屏幕图像识别");
        }

        GraphicsDevice[] devices = GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .getScreenDevices();
        List<ScreenSnapshot> snapshots = new ArrayList<>(devices.length);
        for (int index = 0; index < devices.length; index++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("图像识别已取消");
            }
            GraphicsDevice device = devices[index];
            GraphicsConfiguration configuration = device.getDefaultConfiguration();
            Rectangle logicalBounds = configuration.getBounds();
            MultiResolutionImage capture = new Robot(device)
                    .createMultiResolutionScreenCapture(logicalBounds);
            BufferedImage image = copyHighestResolutionVariant(capture);
            snapshots.add(new ScreenSnapshot(
                    image,
                    new Rectangle(logicalBounds),
                    "显示器 " + (index + 1)
            ));
        }
        return snapshots;
    }

    /** 在已采集的所有屏幕中查找相互独立的匹配位置。 */
    public RecognitionReport recognize(
            List<ScreenSnapshot> snapshots,
            BufferedImage template,
            double threshold
    ) {
        long initializationMillis = openCvTemplateMatcher.ensureLoaded();
        if (snapshots == null || snapshots.isEmpty()) {
            return RecognitionReport.empty(
                    initializationMillis
            );
        }
        long heapBefore = usedHeapBytes();
        List<RecognitionResult> matches = new ArrayList<>();
        RecognitionResult bestCandidate = null;
        boolean truncated = false;
        long estimatedWorkingMemoryBytes = 0L;
        ProcessMemoryMonitor memoryMonitor = ProcessMemoryMonitor.start();
        long startedAt = System.nanoTime();
        long elapsedMillis;

        try {
            for (ScreenSnapshot snapshot : snapshots) {
                if (Thread.currentThread().isInterrupted()) {
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
                                remainingResultCount
                        );
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
            elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
        } finally {
            memoryMonitor.close();
        }

        matches.sort(Comparator.comparingDouble(
                RecognitionResult::similarity
        ).reversed());
        long heapDeltaBytes = Math.max(0L, usedHeapBytes() - heapBefore);
        return new RecognitionReport(
                matches,
                bestCandidate,
                truncated,
                elapsedMillis,
                initializationMillis,
                heapDeltaBytes,
                memoryMonitor.peakIncreaseBytes(),
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

    /** 取得当前 JVM 已使用的堆字节数，用于识别前后内存增量统计。 */
    private long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
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
        return new Rectangle(
                screenBounds.x + (int) Math.round(pixelBounds.x / scaleX),
                screenBounds.y + (int) Math.round(pixelBounds.y / scaleY),
                Math.max(1, (int) Math.round(pixelBounds.width / scaleX)),
                Math.max(1, (int) Math.round(pixelBounds.height / scaleY))
        );
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
