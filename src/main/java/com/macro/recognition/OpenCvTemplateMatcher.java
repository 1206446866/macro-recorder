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
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** 使用 OpenCV 原生模板匹配实现多位置识别。 */
public final class OpenCvTemplateMatcher {

    private static final int OPAQUE_ALPHA_THRESHOLD = 220;
    private static final double OVERLAP_MERGE_RATIO = 0.40;
    /** 仅限定 100% 阈值下原像素复核的候选范围，不作为接受匹配的容差。 */
    private static final double PERFECT_MATCH_RECHECK_ERROR_RATIO = 1e-6;
    /** 小图保持串行，避免线程启动成本；大图最多同时执行两次原生匹配。 */
    private static final long PARALLEL_SCALE_MIN_PIXELS = 256L * 256;
    private static final Object LOAD_LOCK = new Object();
    private static final AtomicBoolean PRELOAD_STARTED = new AtomicBoolean();
    private static volatile boolean loaded;
    private final TemplateScaleVariants scaleVariants = new TemplateScaleVariants();

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
        return findAll(source, template, threshold, maxResults, () -> false);
    }

    /** 原尺寸匹配的可取消入口，保持已有评分和候选合并规则。 */
    SearchResult findAll(BufferedImage source, BufferedImage template, double threshold,
                         int maxResults, BooleanSupplier stopRequested) {
        validateArguments(source, template, threshold, maxResults);
        checkCancelled(stopRequested);
        ensureLoaded();

        scaleVariants.prepare(template);
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        int templateWidth = template.getWidth();
        int templateHeight = template.getHeight();
        if (templateWidth <= 0 || templateHeight <= 0
                || templateWidth > sourceWidth
                || templateHeight > sourceHeight) {
            return SearchResult.empty();
        }

        Mat sourceMat = toBgrMat(source);
        try {
            return matchInSource(sourceMat, source, template, threshold, maxResults, stopRequested);
        } finally {
            release(sourceMat);
        }
    }

    /** 用同一份屏幕 Mat 搜索最多十个粗查、四个细查比例，原尺寸由上层先行完成。 */
    SearchResult findAllScaled(BufferedImage source, BufferedImage template, double threshold,
                               int maxResults, MatchResult originalBest, BooleanSupplier stopRequested) {
        return findAllScaled(source, template, threshold, maxResults, originalBest, stopRequested, true);
    }

    /** 扩大范围竞速任务保持比例串行；全局任务保留原有最多双比例并行。 */
    SearchResult findAllScaled(BufferedImage source, BufferedImage template, double threshold,
                               int maxResults, MatchResult originalBest, BooleanSupplier stopRequested,
                               boolean allowParallelScales) {
        return findAllScaled(source, template, threshold, maxResults, originalBest, stopRequested,
                allowParallelScales, TemplateScaleRange.DEFAULT);
    }

    SearchResult findAllScaled(BufferedImage source, BufferedImage template, double threshold,
                               int maxResults, MatchResult originalBest, BooleanSupplier stopRequested,
                               boolean allowParallelScales, TemplateScaleRange range) {
        validateArguments(source, template, threshold, maxResults);
        checkCancelled(stopRequested);
        ensureLoaded();
        Set<Long> visited = new HashSet<>();
        visited.add(TemplateScaleVariants.sizeKey(template.getWidth(), template.getHeight()));
        List<MatchResult> matches = new ArrayList<>();
        MatchResult best = originalBest;
        double bestScale = 1.0;
        boolean truncated = false;
        Mat sourceMat = null;
        try {
            for (int pass = 0; pass < 2; pass++) {
                double[] scales = pass == 0 ? range.coarseScales() : TemplateScaleVariants.fineScales(bestScale);
                List<Double> selectedScales = new ArrayList<>();
                for (double scale : scales) {
                    checkCancelled(stopRequested);
                    int width = Math.max(1, (int) Math.round(template.getWidth() * scale));
                    int height = Math.max(1, (int) Math.round(template.getHeight() * scale));
                    if (scale < range.minimum() || scale > range.maximum()
                            || width > source.getWidth() || height > source.getHeight()
                            || !visited.add(TemplateScaleVariants.sizeKey(width, height))) continue;
                    selectedScales.add(scale);
                }
                boolean parallel = allowParallelScales && (long) source.getWidth() * source.getHeight() >= PARALLEL_SCALE_MIN_PIXELS
                        && Runtime.getRuntime().availableProcessors() > 1;
                for (int index = 0; index < selectedScales.size();) {
                    checkCancelled(stopRequested);
                    if (sourceMat == null) sourceMat = toBgrMat(source);
                    int batchSize = parallel ? Math.min(2, selectedScales.size() - index) : 1;
                    BufferedImage first = scaledTemplate(template, selectedScales.get(index));
                    SearchResult[] results = batchSize == 2
                            ? matchPair(sourceMat, source, first, scaledTemplate(template, selectedScales.get(index + 1)),
                                    threshold, maxResults, stopRequested)
                            : new SearchResult[]{matchInSource(sourceMat, source, first,
                                    threshold, maxResults, stopRequested)};
                    for (int offset = 0; offset < results.length; offset++) {
                        SearchResult result = results[offset];
                        truncated |= result.truncated();
                        for (MatchResult match : result.matches()) {
                            truncated |= addOrMergeMatch(matches, match, maxResults);
                        }
                        MatchResult candidate = result.bestCandidate();
                        if (candidate != null && (best == null || candidate.similarity() > best.similarity())) {
                            best = candidate;
                            bestScale = selectedScales.get(index + offset);
                        }
                    }
                    index += batchSize;
                }
            }
            checkCancelled(stopRequested);
            if (best != null && best.matched()) retainBestCandidate(matches, best, maxResults);
            matches.sort(Comparator.comparingDouble(MatchResult::similarity).reversed());
            return new SearchResult(matches, best, truncated);
        } finally {
            release(sourceMat);
        }
    }

    private BufferedImage scaledTemplate(BufferedImage template, double scale) {
        return scaleVariants.image(template,
                Math.max(1, (int) Math.round(template.getWidth() * scale)),
                Math.max(1, (int) Math.round(template.getHeight() * scale)));
    }

    /** 返回缩放模板缓存的像素占用，供已有识别报告估算内存。 */
    long scaleCacheBytes() {
        return scaleVariants.cachedBytes();
    }

    /** 复用屏幕矩阵，单次匹配仅分配模板、掩码和误差矩阵并及时释放。 */
    private SearchResult matchInSource(Mat sourceMat, BufferedImage source, BufferedImage template,
                                       double threshold, int maxResults, BooleanSupplier stopRequested) {
        checkCancelled(stopRequested);
        try (NativeMatch match = computeErrors(sourceMat, template)) {
            return collectMatch(match, source, template, threshold, maxResults, stopRequested);
        }
    }

    /** 只并行原生计算；候选收集、停止回调及结果合并仍按原比例顺序执行。 */
    private SearchResult[] matchPair(Mat sourceMat, BufferedImage source,
                                     BufferedImage first, BufferedImage second, double threshold,
                                     int maxResults, BooleanSupplier stopRequested) {
        NativeMatch[] nativeMatches = new NativeMatch[2];
        Throwable[] failure = new Throwable[1];
        Thread worker = new Thread(() -> {
            try {
                nativeMatches[1] = computeErrors(sourceMat, second);
            } catch (RuntimeException | Error exception) {
                failure[0] = exception;
            }
        }, "opencv-scale-match");
        worker.setDaemon(true);
        try {
            worker.start();
            nativeMatches[0] = computeErrors(sourceMat, first);
            joinWorker(worker);
            checkCancelled(stopRequested);
            if (failure[0] instanceof RuntimeException exception) throw exception;
            if (failure[0] instanceof Error error) throw error;
            return new SearchResult[]{
                    collectMatch(nativeMatches[0], source, first, threshold, maxResults, stopRequested),
                    collectMatch(nativeMatches[1], source, second, threshold, maxResults, stopRequested)
            };
        } finally {
            // 即使调用线程取消/抛错，也必须等原生调用退出后才能释放共享屏幕矩阵。
            joinWorker(worker);
            for (NativeMatch match : nativeMatches) if (match != null) match.close();
        }
    }

    /** 保留中断标志，确保当前批次的原生调用结束及资源释放，不启动后续批次。 */
    private void joinWorker(Thread worker) {
        boolean interrupted = false;
        while (worker.isAlive()) {
            try {
                worker.join();
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private SearchResult collectMatch(NativeMatch match, BufferedImage source, BufferedImage template,
                                      double threshold, int maxResults, BooleanSupplier stopRequested) {
        checkCancelled(stopRequested);
        return match.templateData().opaquePixelCount() == 0 ? SearchResult.empty()
                : collectResults(match.errors(), source, template, match.templateData(),
                        threshold, maxResults, stopRequested);
    }

    /** 返回自有模板/结果矩阵，屏幕矩阵只读共享；失败时仍释放全部已创建资源。 */
    private NativeMatch computeErrors(Mat sourceMat, BufferedImage template) {
        Mat templateMat = null;
        Mat maskMat = null;
        Mat resultMat = new Mat();
        boolean retained = false;
        try {
            TemplateData templateData = toTemplateData(template);
            templateMat = templateData.image();
            maskMat = templateData.mask();
            if (templateData.opaquePixelCount() == 0) {
                // 全透明模板不需要原生搜索。
            } else if (templateData.maskRequired()) {
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

            NativeMatch match = new NativeMatch(templateData, resultMat);
            retained = true;
            return match;
        } finally {
            if (!retained) {
                release(resultMat);
                release(maskMat);
                release(templateMat);
            }
        }
    }

    private record NativeMatch(TemplateData templateData, Mat errors) implements AutoCloseable {
        @Override
        public void close() {
            errors.release();
            templateData.mask().release();
            templateData.image().release();
        }
    }

    /** 在 Java 阶段及每轮原生匹配之间响应线程中断或播放器停止。 */
    private static void checkCancelled(BooleanSupplier stopRequested) {
        if (Thread.currentThread().isInterrupted() || stopRequested.getAsBoolean()) {
            throw new CancellationException("图像识别已取消");
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

    /** 保守估算两比例批次的缓冲区；不含由服务另行计入的保留模板缓存。 */
    long estimateScaleWorkingMemoryBytes(BufferedImage source, BufferedImage template) {
        return estimateScaleWorkingMemoryBytes(source, template, TemplateScaleRange.DEFAULT);
    }

    long estimateScaleWorkingMemoryBytes(BufferedImage source, BufferedImage template, TemplateScaleRange range) {
        long sourcePixels = (long) source.getWidth() * source.getHeight();
        long maxTemplatePixels = (long) Math.min(source.getWidth(),
                Math.max(1, (int) Math.round(template.getWidth() * range.maximum())))
                * Math.min(source.getHeight(),
                Math.max(1, (int) Math.round(template.getHeight() * range.maximum())));
        // 屏幕转换/矩阵、两份原生误差、一份 Java 误差，以及两份模板转换/矩阵/图像。
        return sourcePixels * (7L + 3L + 2L * Float.BYTES + Float.BYTES)
                + maxTemplatePixels * (2L * (8L + 4L + Integer.BYTES));
    }

    /** 将误差矩阵转换为相似度候选，合并重叠位置并限制结果数量；满分阈值仍按原像素复核。 */
    private SearchResult collectResults(
            Mat resultMat,
            BufferedImage source,
            BufferedImage template,
            TemplateData templateData,
            double threshold,
            int maxResults,
            BooleanSupplier stopRequested
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
            if ((index & 0xFFFF) == 0) checkCancelled(stopRequested);
            double error = errors[index];
            if (!Double.isFinite(error)) {
                continue;
            }

            boolean requiresExactRecheck = threshold == 1.0
                    && error <= maximumSquaredError * PERFECT_MATCH_RECHECK_ERROR_RATIO;
            // 非命中且无法改善最佳候选的位置无需计算坐标、开平方或创建候选。
            // 满分复核必须先保留：原生误差的大小不能代替原像素误差。
            if (!requiresExactRecheck && error >= bestError
                    && (truncated || error > maximumAcceptedError)) {
                continue;
            }

            int x = index % columns;
            int y = index / columns;
            if (requiresExactRecheck) {
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

    /** 按两个区域中较小区域的面积计算重叠比例，无交集或无有效面积时返回零。 */
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

    /** 把 Java 图像转换为 OpenCV 的三通道 BGR 矩阵，返回的原生矩阵由调用方释放。 */
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

    /** 构建模板矩阵及透明掩码，记录参与匹配的像素数和透明度判定阈值。 */
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

    /** 判断图像是否含有达到常规不透明阈值的像素，供透明模板选择掩码规则。 */
    private boolean containsOpaquePixel(int[] pixels) {
        for (int pixel : pixels) {
            if ((pixel >>> 24) >= OPAQUE_ALPHA_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    /** 拒绝空图像、越界阈值和非正结果上限，避免将非法参数传给 OpenCV。 */
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

    /** 释放非空 OpenCV 矩阵的原生内存，允许资源尚未创建的清理路径共用此入口。 */
    private void release(Mat mat) {
        if (mat != null) {
            mat.release();
        }
    }

    /** 把单调时钟的起始时间换算为已经经过的毫秒数。 */
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

        /** 创建独立保存区域坐标的候选结果，避免外部矩形修改影响匹配记录。 */
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

        /** 将命中列表保存为不可变快照，空列表引用统一按无命中处理。 */
        public SearchResult {
            matches = matches == null ? List.of() : List.copyOf(matches);
        }

        /** 创建既无命中也无最佳候选的结果，用于没有有效搜索区域的情况。 */
        private static SearchResult empty() {
            return new SearchResult(List.of(), null, false);
        }
    }
}
