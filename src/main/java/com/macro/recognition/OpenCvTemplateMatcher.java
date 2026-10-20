package com.macro.recognition;

import nu.pattern.OpenCV;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** 使用 OpenCV 原生模板匹配实现多位置识别。 */
public final class OpenCvTemplateMatcher {

    private static final int OPAQUE_ALPHA_THRESHOLD = 220;
    private static final double OVERLAP_MERGE_RATIO = 0.40;
    /** 仅限定 100% 阈值下原像素复核的候选范围，不作为接受匹配的容差。 */
    private static final double PERFECT_MATCH_RECHECK_ERROR_RATIO = 1e-6;
    private static final Object LOAD_LOCK = new Object();
    private static final AtomicBoolean PRELOAD_STARTED = new AtomicBoolean();
    private static volatile boolean loaded;

    /** 在后台提前加载并预热 OpenCV，避免阻塞应用启动界面。 */
    public static void preloadInBackground() {
        if (!PRELOAD_STARTED.compareAndSet(false, true)) {
            return;
        }
        Thread preloadThread = new Thread(() -> {
            try {
                new OpenCvTemplateMatcher().ensureLoaded();
            } catch (RuntimeException | LinkageError ignored) {
                // 正式识别时会再次尝试，并通过现有错误弹窗反馈失败原因。
            }
        }, "opencv-preload");
        preloadThread.setDaemon(true);
        preloadThread.setPriority(Math.max(
                Thread.MIN_PRIORITY,
                Thread.NORM_PRIORITY - 1
        ));
        preloadThread.start();
    }

    /** 确保本机 OpenCV 运行库已加载并完成预热，返回首次初始化耗时。 */
    public long ensureLoaded() {
        if (loaded) {
            return 0L;
        }
        synchronized (LOAD_LOCK) {
            if (loaded) {
                return 0L;
            }
            long startedAt = System.nanoTime();
            OpenCV.loadLocally();
            warmUpTemplateMatching();
            loaded = true;
            return elapsedMillis(startedAt);
        }
    }

    /** 用极小矩阵预热有遮罩和无遮罩两条模板匹配路径。 */
    private void warmUpTemplateMatching() {
        Mat source = Mat.zeros(4, 4, CvType.CV_8UC3);
        Mat template = Mat.zeros(2, 2, CvType.CV_8UC3);
        Mat mask = Mat.ones(2, 2, CvType.CV_8UC1);
        Mat result = new Mat();
        try {
            Imgproc.matchTemplate(
                    source,
                    template,
                    result,
                    Imgproc.TM_SQDIFF
            );
            Imgproc.matchTemplate(
                    source,
                    template,
                    result,
                    Imgproc.TM_SQDIFF,
                    mask
            );
        } finally {
            release(result);
            release(mask);
            release(template);
            release(source);
        }
    }

    /** 在源图像中返回多个独立匹配位置。 */
    public SearchResult findAll(
            BufferedImage source,
            BufferedImage template,
            double threshold,
            int maxResults
    ) {
        validateArguments(source, template, threshold, maxResults);
        ensureLoaded();

        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        int templateWidth = template.getWidth();
        int templateHeight = template.getHeight();
        if (templateWidth <= 0 || templateHeight <= 0
                || templateWidth > sourceWidth
                || templateHeight > sourceHeight) {
            return SearchResult.empty();
        }

        Mat sourceMat = null;
        Mat templateMat = null;
        Mat maskMat = null;
        Mat resultMat = new Mat();
        try {
            sourceMat = toBgrMat(source);
            TemplateData templateData = toTemplateData(template);
            templateMat = templateData.image();
            maskMat = templateData.mask();
            if (templateData.opaquePixelCount() == 0) {
                return SearchResult.empty();
            }

            if (templateData.maskRequired()) {
                Imgproc.matchTemplate(
                        sourceMat,
                        templateMat,
                        resultMat,
                        Imgproc.TM_SQDIFF,
                        maskMat
                );
            } else {
                Imgproc.matchTemplate(
                        sourceMat,
                        templateMat,
                        resultMat,
                        Imgproc.TM_SQDIFF
                );
            }

            return collectResults(
                    resultMat,
                    source,
                    template,
                    templateData,
                    threshold,
                    maxResults
            );
        } finally {
            release(resultMat);
            release(maskMat);
            release(templateMat);
            release(sourceMat);
        }
    }

    /** 估算匹配期间 Java 数组与 OpenCV Mat 的峰值总占用。 */
    public long estimateWorkingMemoryBytes(
            BufferedImage source,
            BufferedImage template
    ) {
        long sourcePixels = (long) source.getWidth() * source.getHeight();
        long templatePixels = (long) template.getWidth() * template.getHeight();
        long resultPixels = (long) Math.max(
                0,
                source.getWidth() - template.getWidth() + 1
        ) * Math.max(0, source.getHeight() - template.getHeight() + 1);

        long conversionHeap = sourcePixels * 7L + templatePixels * 8L;
        long nativeMatrices = sourcePixels * 3L
                + templatePixels * 4L
                + resultPixels * Float.BYTES;
        long resultHeap = resultPixels * Float.BYTES;
        return conversionHeap + nativeMatrices + resultHeap;
    }

    private SearchResult collectResults(
            Mat resultMat,
            BufferedImage source,
            BufferedImage template,
            TemplateData templateData,
            double threshold,
            int maxResults
    ) {
        int templateWidth = template.getWidth();
        int templateHeight = template.getHeight();
        int rows = resultMat.rows();
        int columns = resultMat.cols();
        int resultCount = Math.multiplyExact(rows, columns);
        float[] errors = new float[resultCount];
        resultMat.get(0, 0, errors);

        double maximumSquaredError = templateData.opaquePixelCount()
                * 3.0
                * 255.0
                * 255.0;
        double maximumAcceptedError = Math.pow(1.0 - threshold, 2.0)
                * maximumSquaredError;
        List<MatchResult> matches = new ArrayList<>();
        MatchResult bestCandidate = null;
        double bestError = Double.POSITIVE_INFINITY;
        boolean truncated = false;

        for (int index = 0; index < errors.length; index++) {
            if ((index & 0xFFFF) == 0
                    && Thread.currentThread().isInterrupted()) {
                throw new CancellationException("图像识别已取消");
            }
            double error = errors[index];
            if (!Double.isFinite(error)) {
                continue;
            }

            int x = index % columns;
            int y = index / columns;
            if (threshold == 1.0
                    && error <= maximumSquaredError * PERFECT_MATCH_RECHECK_ERROR_RATIO) {
                // 已有精确满分且结果已截断时，剩余候选无法改善最佳结果。
                if (truncated && bestError == 0.0) continue;
                error = exactSquaredError(source, template, x, y, templateData.alphaThreshold());
            }
            double similarity = toSimilarity(error, maximumSquaredError);
            if (error < bestError) {
                bestError = error;
                bestCandidate = MatchResult.candidate(
                        error <= maximumAcceptedError,
                        similarity,
                        new Rectangle(x, y, templateWidth, templateHeight)
                );
            }

            if (!truncated && error <= maximumAcceptedError) {
                MatchResult candidate = MatchResult.candidate(
                        true,
                        similarity,
                        new Rectangle(x, y, templateWidth, templateHeight)
                );
                truncated = addOrMergeMatch(matches, candidate, maxResults);
            }
        }

        if (bestCandidate != null && bestCandidate.matched()) {
            retainBestCandidate(matches, bestCandidate, maxResults);
        }
        matches.sort(Comparator.comparingDouble(
                MatchResult::similarity
        ).reversed());
        return new SearchResult(matches, bestCandidate, truncated);
    }

    /** 用与 OpenCV 转换相同的 RGB 和透明掩码复核满分候选，整数累加不会发生浮点舍入。 */
    private long exactSquaredError(BufferedImage source, BufferedImage template,
            int sourceX, int sourceY, int alphaThreshold) {
        long squaredError = 0L;
        for (int y = 0; y < template.getHeight(); y++) {
            for (int x = 0; x < template.getWidth(); x++) {
                int expected = template.getRGB(x, y);
                if ((expected >>> 24) < alphaThreshold) continue;
                int actual = source.getRGB(sourceX + x, sourceY + y);
                int red = (actual >> 16 & 0xFF) - (expected >> 16 & 0xFF);
                int green = (actual >> 8 & 0xFF) - (expected >> 8 & 0xFF);
                int blue = (actual & 0xFF) - (expected & 0xFF);
                squaredError += red * red + green * green + blue * blue;
            }
        }
        return squaredError;
    }

    /** 将平方差转换为接近现有 Java 匹配器语义的 0 到 1 相似度。 */
    private double toSimilarity(double squaredError, double maximumSquaredError) {
        if (maximumSquaredError <= 0.0) {
            return 0.0;
        }
        double normalizedRootMeanSquare = Math.sqrt(
                Math.max(0.0, squaredError) / maximumSquaredError
        );
        return Math.max(0.0, Math.min(1.0, 1.0 - normalizedRootMeanSquare));
    }

    /** 将最佳位置保留在截断后的结果集合中。 */
    private void retainBestCandidate(
            List<MatchResult> matches,
            MatchResult bestCandidate,
            int maxResults
    ) {
        for (int index = 0; index < matches.size(); index++) {
            MatchResult existing = matches.get(index);
            if (overlapRatio(existing.pixelBounds(), bestCandidate.pixelBounds())
                    >= OVERLAP_MERGE_RATIO) {
                if (bestCandidate.similarity() > existing.similarity()) {
                    matches.set(index, bestCandidate);
                }
                return;
            }
        }
        if (matches.size() < maxResults) {
            matches.add(bestCandidate);
            return;
        }
        if (!matches.isEmpty()) {
            int weakestIndex = 0;
            for (int index = 1; index < matches.size(); index++) {
                if (matches.get(index).similarity()
                        < matches.get(weakestIndex).similarity()) {
                    weakestIndex = index;
                }
            }
            matches.set(weakestIndex, bestCandidate);
        }
    }

    /** 合并同一目标附近的重复框，并在达到上限时停止扩充集合。 */
    private boolean addOrMergeMatch(
            List<MatchResult> matches,
            MatchResult candidate,
            int maxResults
    ) {
        for (int index = 0; index < matches.size(); index++) {
            MatchResult existing = matches.get(index);
            if (overlapRatio(existing.pixelBounds(), candidate.pixelBounds())
                    >= OVERLAP_MERGE_RATIO) {
                if (candidate.similarity() > existing.similarity()) {
                    matches.set(index, candidate);
                }
                return false;
            }
        }
        if (matches.size() < maxResults) {
            matches.add(candidate);
            return false;
        }
        return true;
    }

    private double overlapRatio(Rectangle first, Rectangle second) {
        Rectangle intersection = first.intersection(second);
        if (intersection.isEmpty()) {
            return 0.0;
        }
        double intersectionArea = intersection.getWidth() * intersection.getHeight();
        double smallerArea = Math.min(
                first.getWidth() * first.getHeight(),
                second.getWidth() * second.getHeight()
        );
        return smallerArea <= 0.0 ? 0.0 : intersectionArea / smallerArea;
    }

    private Mat toBgrMat(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] argb = image.getRGB(0, 0, width, height, null, 0, width);
        byte[] bgr = new byte[Math.multiplyExact(argb.length, 3)];
        for (int index = 0; index < argb.length; index++) {
            int pixel = argb[index];
            int offset = index * 3;
            bgr[offset] = (byte) (pixel & 0xFF);
            bgr[offset + 1] = (byte) (pixel >> 8 & 0xFF);
            bgr[offset + 2] = (byte) (pixel >> 16 & 0xFF);
        }

        Mat mat = new Mat(height, width, CvType.CV_8UC3);
        mat.put(0, 0, bgr);
        return mat;
    }

    private TemplateData toTemplateData(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] argb = image.getRGB(0, 0, width, height, null, 0, width);
        int alphaThreshold = containsOpaquePixel(argb)
                ? OPAQUE_ALPHA_THRESHOLD
                : 1;
        byte[] bgr = new byte[Math.multiplyExact(argb.length, 3)];
        byte[] mask = new byte[argb.length];
        int opaquePixelCount = 0;

        for (int index = 0; index < argb.length; index++) {
            int pixel = argb[index];
            int offset = index * 3;
            bgr[offset] = (byte) (pixel & 0xFF);
            bgr[offset + 1] = (byte) (pixel >> 8 & 0xFF);
            bgr[offset + 2] = (byte) (pixel >> 16 & 0xFF);
            if ((pixel >>> 24) >= alphaThreshold) {
                mask[index] = (byte) 0xFF;
                opaquePixelCount++;
            }
        }

        Mat imageMat = new Mat(height, width, CvType.CV_8UC3);
        imageMat.put(0, 0, bgr);
        Mat maskMat = new Mat(height, width, CvType.CV_8UC1);
        maskMat.put(0, 0, mask);
        return new TemplateData(
                imageMat,
                maskMat,
                opaquePixelCount,
                opaquePixelCount < argb.length,
                alphaThreshold
        );
    }

    private boolean containsOpaquePixel(int[] pixels) {
        for (int pixel : pixels) {
            if ((pixel >>> 24) >= OPAQUE_ALPHA_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    private void validateArguments(
            BufferedImage source,
            BufferedImage template,
            double threshold,
            int maxResults
    ) {
        if (source == null || template == null) {
            throw new IllegalArgumentException("源图像和模板图像不能为空");
        }
        if (threshold < 0.0 || threshold > 1.0) {
            throw new IllegalArgumentException("匹配阈值必须位于 0 到 1 之间");
        }
        if (maxResults <= 0) {
            throw new IllegalArgumentException("最大结果数量必须大于零");
        }
    }

    private void release(Mat mat) {
        if (mat != null) {
            mat.release();
        }
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private record TemplateData(
            Mat image,
            Mat mask,
            int opaquePixelCount,
            boolean maskRequired,
            int alphaThreshold
    ) {
    }

    /** 单个 OpenCV 匹配位置。 */
    public record MatchResult(
            boolean matched,
            double similarity,
            Rectangle pixelBounds
    ) {

        private static MatchResult candidate(
                boolean matched,
                double similarity,
                Rectangle bounds
        ) {
            return new MatchResult(matched, similarity, new Rectangle(bounds));
        }
    }

    /** OpenCV 多结果扫描输出。 */
    public record SearchResult(
            List<MatchResult> matches,
            MatchResult bestCandidate,
            boolean truncated
    ) {

        public SearchResult {
            matches = matches == null ? List.of() : List.copyOf(matches);
        }

        private static SearchResult empty() {
            return new SearchResult(List.of(), null, false);
        }
    }
}
