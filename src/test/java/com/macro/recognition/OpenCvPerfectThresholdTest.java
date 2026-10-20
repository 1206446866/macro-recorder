package com.macro.recognition;

import org.opencv.core.CvType;
import org.opencv.core.Mat;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Random;

/** 纯内存图像及受控浮点误差回归；不截图、不访问用户素材、不发送输入。 */
public final class OpenCvPerfectThresholdTest {
    private static final OpenCvTemplateMatcher MATCHER = new OpenCvTemplateMatcher();

    public static void main(String[] args) throws Exception {
        MATCHER.ensureLoaded();
        exactCopies();
        controlledRounding();
        differentPixelsAndThresholds();
        transparentMasks();
        resultSelection();
        System.out.println("通过：真实 OpenCV 的15组精确复制、受控非零/零/负舍入误差、单像素差异拒绝、普通阈值、透明掩码、最佳位置及截断");
    }

    private static void exactCopies() {
        for (int size : new int[]{8, 31, 64, 127, 256}) {
            for (int seed = 0; seed < 3; seed++) {
                BufferedImage template = noise(size, seed);
                BufferedImage source = image(size + 20, size + 20);
                copy(template, source, 10, 10);
                var result = MATCHER.findAll(source, template, 1.0, 100);
                check(result.matches().size() == 1, "精确复制仍被100%阈值拒绝：" + size + "/" + seed);
                perfect(result, new Rectangle(10, 10, size, size));
            }
        }
    }

    private static void controlledRounding() throws Exception {
        BufferedImage template = noise(31, 0);
        BufferedImage same = image(31, 31);
        copy(template, same, 0, 0);
        for (float error : new float[]{0f, 6f, -1f}) {
            perfect(withError(same, template, 1.0, error), new Rectangle(0, 0, 31, 31));
        }
        // RGB 仅差一个单位；即便 OpenCV 因舍入返回零，也不能冒充精确满分。
        BufferedImage changed = image(31, 31);
        copy(template, changed, 0, 0);
        changed.setRGB(0, 0, changed.getRGB(0, 0) ^ 1);
        for (float error : new float[]{0f, 6f, -1f}) {
            var result = withError(changed, template, 1.0, error);
            check(result.matches().isEmpty() && !result.bestCandidate().matched()
                    && result.bestCandidate().similarity() < 1.0, "近似但不同的像素被放宽到100%");
        }
        // 非满分阈值仍使用原来的 OpenCV 分数，不进行原像素重评分。
        var ordinary = withError(same, template, 0.99, 6f);
        double expected = 1.0 - Math.sqrt(6.0 / (31 * 31 * 3.0 * 255 * 255));
        check(ordinary.bestCandidate().matched()
                && ordinary.bestCandidate().similarity() == expected, "普通阈值评分发生变化");
    }

    private static void differentPixelsAndThresholds() {
        BufferedImage black = image(1, 1);
        BufferedImage source = image(1, 1);
        source.setRGB(0, 0, 0x333333);
        check(MATCHER.findAll(source, black, 0.79, 1).matches().size() == 1,
                "正常阈值应接受的候选被拒绝");
        check(MATCHER.findAll(source, black, 0.81, 1).matches().isEmpty(), "正常阈值被整体放宽");
        for (double threshold : new double[]{0.9, 0.99, 1.0}) {
            source.setRGB(0, 0, 0xFFFFFF);
            check(MATCHER.findAll(source, black, threshold, 1).matches().isEmpty(),
                    "明显不同图像通过阈值：" + threshold);
        }
        source.setRGB(0, 0, 0);
        perfect(MATCHER.findAll(source, black, 1.0, 1), new Rectangle(0, 0, 1, 1));
        source.setRGB(0, 0, 1);
        check(MATCHER.findAll(source, black, 1.0, 1).matches().isEmpty(), "小图单通道差异被忽略");
    }

    private static void transparentMasks() {
        for (int alpha : new int[]{220, 127}) {
            BufferedImage template = new BufferedImage(9, 9, BufferedImage.TYPE_INT_ARGB);
            BufferedImage colors = noise(9, 12);
            BufferedImage source = image(9, 9);
            for (int y = 0; y < 9; y++) {
                for (int x = 0; x < 9; x++) {
                    boolean visible = x > 1 && x < 7 && y > 1 && y < 7;
                    int ignoredAlpha = alpha == 220 ? 219 : 0;
                    int color = colors.getRGB(x, y) & 0xFFFFFF;
                    template.setRGB(x, y, ((visible ? alpha : ignoredAlpha) << 24) | color);
                    source.setRGB(x, y, visible ? color : color ^ 0xFFFFFF);
                }
            }
            perfect(MATCHER.findAll(source, template, 1.0, 1), new Rectangle(0, 0, 9, 9));
            source.setRGB(4, 4, source.getRGB(4, 4) ^ 1);
            check(MATCHER.findAll(source, template, 1.0, 1).matches().isEmpty(),
                    "掩码内单像素差异被忽略：alpha=" + alpha);
        }
        BufferedImage transparent = new BufferedImage(3, 3, BufferedImage.TYPE_INT_ARGB);
        var empty = MATCHER.findAll(image(3, 3), transparent, 1.0, 1);
        check(empty.matches().isEmpty() && empty.bestCandidate() == null, "全透明模板语义改变");
    }

    private static void resultSelection() {
        BufferedImage template = noise(8, 4);
        BufferedImage source = image(70, 20);
        copy(template, source, 1, 1);
        source.setRGB(1, 1, source.getRGB(1, 1) ^ 1);
        copy(template, source, 30, 1);
        copy(template, source, 50, 1);
        var all = MATCHER.findAll(source, template, 1.0, 10);
        check(all.matches().stream().map(OpenCvTemplateMatcher.MatchResult::pixelBounds).toList()
                .equals(List.of(new Rectangle(30, 1, 8, 8), new Rectangle(50, 1, 8, 8))),
                "满分位置、同分顺序或非精确候选过滤不正确");
        check(!all.truncated(), "未满结果上限却标记截断");
        var limited = MATCHER.findAll(source, template, 1.0, 1);
        perfect(limited, new Rectangle(30, 1, 8, 8));
        check(limited.matches().size() == 1 && limited.truncated(), "结果上限或截断语义改变");
        var ordinary = MATCHER.findAll(source, template, 0.99, 10);
        check(ordinary.matches().size() == 3 && ordinary.bestCandidate().matched(), "普通阈值候选选择改变");
        for (int i = 1; i < ordinary.matches().size(); i++) {
            check(ordinary.matches().get(i - 1).similarity() >= ordinary.matches().get(i).similarity(),
                    "原有按相似度排序行为改变");
        }
    }

    /** 注入单个 CV_32F 结果，确保不同原生优化路径下也能确定性覆盖浮点舍入边界。 */
    private static OpenCvTemplateMatcher.SearchResult withError(BufferedImage source,
            BufferedImage template, double threshold, float error) throws Exception {
        var conversion = OpenCvTemplateMatcher.class.getDeclaredMethod("toTemplateData", BufferedImage.class);
        conversion.setAccessible(true);
        Object data = conversion.invoke(MATCHER, template);
        var imageAccessor = data.getClass().getDeclaredMethod("image");
        var maskAccessor = data.getClass().getDeclaredMethod("mask");
        imageAccessor.setAccessible(true);
        maskAccessor.setAccessible(true);
        Mat templateMat = (Mat) imageAccessor.invoke(data);
        Mat maskMat = (Mat) maskAccessor.invoke(data);
        Mat scores = new Mat(1, 1, CvType.CV_32FC1);
        try {
            scores.put(0, 0, new float[]{error});
            var collection = OpenCvTemplateMatcher.class.getDeclaredMethod("collectResults", Mat.class,
                    BufferedImage.class, BufferedImage.class, conversion.getReturnType(), double.class, int.class);
            collection.setAccessible(true);
            return (OpenCvTemplateMatcher.SearchResult) collection.invoke(
                    MATCHER, scores, source, template, data, threshold, 10);
        } finally {
            scores.release();
            maskMat.release();
            templateMat.release();
        }
    }

    private static BufferedImage image(int width, int height) {
        return new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    }

    private static BufferedImage noise(int size, int seed) {
        BufferedImage image = image(size, size);
        Random random = new Random(seed);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) image.setRGB(x, y, random.nextInt(0x1000000));
        }
        return image;
    }

    private static void copy(BufferedImage template, BufferedImage source, int x, int y) {
        source.setRGB(x, y, template.getWidth(), template.getHeight(),
                template.getRGB(0, 0, template.getWidth(), template.getHeight(), null, 0, template.getWidth()),
                0, template.getWidth());
    }

    private static void perfect(OpenCvTemplateMatcher.SearchResult result, Rectangle expected) {
        check(!result.matches().isEmpty() && result.bestCandidate().matched()
                && result.bestCandidate().similarity() == 1.0
                && result.bestCandidate().pixelBounds().equals(expected), "未返回精确满分或正确位置：" + result);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
