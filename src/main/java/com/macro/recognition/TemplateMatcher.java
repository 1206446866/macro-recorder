package com.macro.recognition;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.CancellationException;

/** 使用原始像素尺寸在图像中查找一个或多个模板位置。 */
public final class TemplateMatcher {

    private static final int MAX_ANCHOR_COUNT = 25;
    private static final int MAX_MIDDLE_SAMPLE_COUNT = 256;
    private static final int MAX_DETAIL_SAMPLE_COUNT = 4096;
    private static final int MAX_FALLBACK_CANDIDATE_COUNT = 48;
    private static final int OPAQUE_ALPHA_THRESHOLD = 220;
    private static final double OVERLAP_MERGE_RATIO = 0.40;

    /** 查找相似度最高的单个候选。 */
    public MatchResult findBest(
            BufferedImage source,
            BufferedImage template,
            double threshold
    ) {
        return findAll(source, template, threshold, 1).bestCandidate();
    }

    /**
     * 在源图像中查找多个互不重复的模板位置。
     *
     * @param source     待搜索图像
     * @param template   模板图像
     * @param threshold  匹配阈值，范围为 0 到 1
     * @param maxResults 最多保留的独立结果数量
     * @return 匹配集合和最佳候选
     */
    public SearchResult findAll(
            BufferedImage source,
            BufferedImage template,
            double threshold,
            int maxResults
    ) {
        validateArguments(source, template, threshold, maxResults);

        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        int templateWidth = template.getWidth();
        int templateHeight = template.getHeight();
        if (templateWidth <= 0 || templateHeight <= 0
                || templateWidth > sourceWidth
                || templateHeight > sourceHeight) {
            return SearchResult.empty();
        }

        int[] sourcePixels = source.getRGB(
                0,
                0,
                sourceWidth,
                sourceHeight,
                null,
                0,
                sourceWidth
        );
        int[] templatePixels = template.getRGB(
                0,
                0,
                templateWidth,
                templateHeight,
                null,
                0,
                templateWidth
        );

        int alphaThreshold = containsOpaquePixel(templatePixels)
                ? OPAQUE_ALPHA_THRESHOLD
                : 1;
        List<SamplePoint> anchors = selectAnchors(
                templatePixels,
                templateWidth,
                templateHeight,
                alphaThreshold
        );
        if (anchors.isEmpty()) {
            return SearchResult.empty();
        }
        List<SamplePoint> middleSamples = createSamples(
                templatePixels,
                templateWidth,
                templateHeight,
                alphaThreshold,
                anchors,
                MAX_MIDDLE_SAMPLE_COUNT
        );
        List<SamplePoint> detailSamples = createSamples(
                templatePixels,
                templateWidth,
                templateHeight,
                alphaThreshold,
                anchors,
                MAX_DETAIL_SAMPLE_COUNT
        );

        PriorityQueue<Candidate> fallbackCandidates = new PriorityQueue<>(
                Comparator.comparingLong(Candidate::anchorError).reversed()
        );
        List<MatchResult> matches = new ArrayList<>();
        MatchResult bestCandidate = null;
        boolean truncated = false;
        int maxX = sourceWidth - templateWidth;
        int maxY = sourceHeight - templateHeight;
        long anchorErrorLimit = anchorErrorLimit(anchors.size(), threshold);
        double middleThreshold = Math.max(
                0.0,
                threshold - Math.max(0.05, (1.0 - threshold) * 0.5)
        );

        for (int y = 0; y <= maxY; y++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("图像识别已取消");
            }
            for (int x = 0; x <= maxX; x++) {
                long fallbackLimit = fallbackCandidates.size()
                        < MAX_FALLBACK_CANDIDATE_COUNT
                        ? Long.MAX_VALUE
                        : fallbackCandidates.peek().anchorError();
                long calculationLimit = Math.max(anchorErrorLimit, fallbackLimit);
                long anchorError = calculateColorError(
                        sourcePixels,
                        sourceWidth,
                        x,
                        y,
                        anchors,
                        calculationLimit
                );
                updateFallbackCandidates(
                        fallbackCandidates,
                        x,
                        y,
                        anchorError
                );
                if (anchorError > anchorErrorLimit) {
                    continue;
                }

                double middleSimilarity = calculateSimilarity(
                        sourcePixels,
                        sourceWidth,
                        x,
                        y,
                        middleSamples
                );
                if (middleSimilarity < middleThreshold) {
                    continue;
                }

                double detailSimilarity = calculateSimilarity(
                        sourcePixels,
                        sourceWidth,
                        x,
                        y,
                        detailSamples
                );
                MatchResult candidate = MatchResult.candidate(
                        detailSimilarity >= threshold,
                        detailSimilarity,
                        new Rectangle(x, y, templateWidth, templateHeight)
                );
                if (bestCandidate == null
                        || candidate.similarity() > bestCandidate.similarity()) {
                    bestCandidate = candidate;
                }
                if (candidate.matched()) {
                    truncated |= addOrMergeMatch(matches, candidate, maxResults);
                }
            }
        }

        for (Candidate candidate : fallbackCandidates) {
            double similarity = calculateSimilarity(
                    sourcePixels,
                    sourceWidth,
                    candidate.x(),
                    candidate.y(),
                    detailSamples
            );
            if (bestCandidate == null || similarity > bestCandidate.similarity()) {
                bestCandidate = MatchResult.candidate(
                        similarity >= threshold,
                        similarity,
                        new Rectangle(
                                candidate.x(),
                                candidate.y(),
                                templateWidth,
                                templateHeight
                        )
                );
            }
        }

        matches.sort(Comparator.comparingDouble(MatchResult::similarity).reversed());
        return new SearchResult(matches, bestCandidate, truncated);
    }

    /** 估算当前 Java 匹配器主要像素数组的峰值占用。 */
    public long estimateWorkingMemoryBytes(
            BufferedImage source,
            BufferedImage template
    ) {
        if (source == null || template == null) {
            return 0L;
        }
        long sourcePixels = (long) source.getWidth() * source.getHeight();
        long templatePixels = (long) template.getWidth() * template.getHeight();
        return (sourcePixels + templatePixels) * Integer.BYTES;
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

    /** 从模板不同区域选择对位置变化敏感的像素作为快速筛选锚点。 */
    private List<SamplePoint> selectAnchors(
            int[] pixels,
            int width,
            int height,
            int alphaThreshold
    ) {
        int gridSize = (int) Math.ceil(Math.sqrt(MAX_ANCHOR_COUNT));
        List<ScoredSample> selected = new ArrayList<>(MAX_ANCHOR_COUNT);

        for (int gridY = 0; gridY < gridSize; gridY++) {
            int startY = gridY * height / gridSize;
            int endY = Math.max(startY + 1, (gridY + 1) * height / gridSize);
            for (int gridX = 0; gridX < gridSize; gridX++) {
                int startX = gridX * width / gridSize;
                int endX = Math.max(startX + 1, (gridX + 1) * width / gridSize);
                ScoredSample best = null;

                for (int y = startY; y < Math.min(endY, height); y++) {
                    for (int x = startX; x < Math.min(endX, width); x++) {
                        int rgb = pixels[y * width + x];
                        if (alpha(rgb) < alphaThreshold) {
                            continue;
                        }
                        int score = contrastScore(pixels, width, height, x, y);
                        if (best == null || score > best.score()) {
                            best = new ScoredSample(x, y, rgb, score);
                        }
                    }
                }
                if (best != null) {
                    selected.add(best);
                }
            }
        }

        selected.sort(Comparator.comparingInt(ScoredSample::score).reversed());
        return selected.stream()
                .limit(MAX_ANCHOR_COUNT)
                .map(sample -> new SamplePoint(
                        sample.x(),
                        sample.y(),
                        sample.rgb()
                ))
                .toList();
    }

    /** 创建覆盖模板主体的分层评分采样点。 */
    private List<SamplePoint> createSamples(
            int[] pixels,
            int width,
            int height,
            int alphaThreshold,
            List<SamplePoint> anchors,
            int maximumSampleCount
    ) {
        int opaqueCount = 0;
        for (int pixel : pixels) {
            if (alpha(pixel) >= alphaThreshold) {
                opaqueCount++;
            }
        }

        int step = Math.max(
                1,
                (int) Math.ceil(Math.sqrt(
                        opaqueCount / (double) maximumSampleCount
                ))
        );
        List<SamplePoint> samples = new ArrayList<>();
        Set<Integer> offsets = new HashSet<>();

        for (SamplePoint anchor : anchors) {
            int offset = anchor.y() * width + anchor.x();
            if (offsets.add(offset)) {
                samples.add(anchor);
            }
        }
        for (int y = 0; y < height; y += step) {
            for (int x = 0; x < width; x += step) {
                int offset = y * width + x;
                int rgb = pixels[offset];
                if (alpha(rgb) >= alphaThreshold && offsets.add(offset)) {
                    samples.add(new SamplePoint(x, y, rgb));
                }
            }
        }
        return samples;
    }

    /** 用松于最终阈值的锚点门槛减少完整评分次数。 */
    private long anchorErrorLimit(int anchorCount, double threshold) {
        double toleratedDifference = Math.min(
                1.0,
                (1.0 - threshold) * 2.5 + 0.02
        );
        return Math.round(
                anchorCount * 3.0 * 255.0 * toleratedDifference
        );
    }

    /** 保留少量锚点评分最好的位置，用于没有达标结果时展示最佳候选。 */
    private void updateFallbackCandidates(
            PriorityQueue<Candidate> candidates,
            int x,
            int y,
            long anchorError
    ) {
        if (candidates.size() < MAX_FALLBACK_CANDIDATE_COUNT) {
            candidates.offer(new Candidate(x, y, anchorError));
            return;
        }
        if (anchorError < candidates.peek().anchorError()) {
            candidates.poll();
            candidates.offer(new Candidate(x, y, anchorError));
        }
    }

    /** 合并同一目标附近的重复框，并对结果数量设置安全上限。 */
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

        int weakestIndex = 0;
        for (int index = 1; index < matches.size(); index++) {
            if (matches.get(index).similarity()
                    < matches.get(weakestIndex).similarity()) {
                weakestIndex = index;
            }
        }
        if (candidate.similarity() > matches.get(weakestIndex).similarity()) {
            matches.set(weakestIndex, candidate);
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

    /** 计算采样点颜色差；超过上限时提前结束。 */
    private long calculateColorError(
            int[] sourcePixels,
            int sourceWidth,
            int candidateX,
            int candidateY,
            List<SamplePoint> samples,
            long errorLimit
    ) {
        long error = 0L;
        for (SamplePoint sample : samples) {
            int sourceRgb = sourcePixels[
                    (candidateY + sample.y()) * sourceWidth
                            + candidateX
                            + sample.x()
            ];
            error += colorDifference(sourceRgb, sample.rgb());
            if (error > errorLimit) {
                return error;
            }
        }
        return error;
    }

    /** 根据采样点计算 0 到 1 的颜色相似度。 */
    private double calculateSimilarity(
            int[] sourcePixels,
            int sourceWidth,
            int candidateX,
            int candidateY,
            List<SamplePoint> samples
    ) {
        long error = calculateColorError(
                sourcePixels,
                sourceWidth,
                candidateX,
                candidateY,
                samples,
                Long.MAX_VALUE
        );
        double maximumError = samples.size() * 3.0 * 255.0;
        return maximumError <= 0.0
                ? 0.0
                : Math.max(0.0, 1.0 - error / maximumError);
    }

    private int contrastScore(int[] pixels, int width, int height, int x, int y) {
        int center = pixels[y * width + x];
        int score = 0;
        if (x > 0) score += colorDifference(center, pixels[y * width + x - 1]);
        if (x + 1 < width) score += colorDifference(center, pixels[y * width + x + 1]);
        if (y > 0) score += colorDifference(center, pixels[(y - 1) * width + x]);
        if (y + 1 < height) score += colorDifference(center, pixels[(y + 1) * width + x]);
        return score;
    }

    private boolean containsOpaquePixel(int[] pixels) {
        for (int pixel : pixels) {
            if (alpha(pixel) >= OPAQUE_ALPHA_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    private int colorDifference(int first, int second) {
        return Math.abs(red(first) - red(second))
                + Math.abs(green(first) - green(second))
                + Math.abs(blue(first) - blue(second));
    }

    private int alpha(int rgb) {
        return rgb >>> 24;
    }

    private int red(int rgb) {
        return rgb >> 16 & 0xFF;
    }

    private int green(int rgb) {
        return rgb >> 8 & 0xFF;
    }

    private int blue(int rgb) {
        return rgb & 0xFF;
    }

    private record SamplePoint(int x, int y, int rgb) {
    }

    private record ScoredSample(int x, int y, int rgb, int score) {
    }

    private record Candidate(int x, int y, long anchorError) {
    }

    /** 单个位置的模板评分。 */
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

    /** 多结果扫描输出，同时保留未达阈值时使用的最佳候选。 */
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
