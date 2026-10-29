package com.macro.recognition;

import org.junit.jupiter.api.Test;
import org.opencv.core.Mat;

import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** 真实 OpenCV 的有界并行、透明掩码、取消及资源收尾检查，仅使用合成图像。 */
class ScaleMatchingParallelTest {
    private final OpenCvTemplateMatcher matcher = new OpenCvTemplateMatcher();

    @Test
    void pairsExactlyMatchSerialScoresOrderMasksAndTruncation() throws Exception {
        for (int alpha : new int[]{255, 220, 127, 0}) {
            BufferedImage first = maskedPattern(32, 24, alpha);
            BufferedImage second = maskedPattern(48, 36, alpha);
            BufferedImage source = new BufferedImage(512, 256, BufferedImage.TYPE_INT_RGB);
            copyColors(source, first, 10, 20);
            copyColors(source, first, 100, 20);
            copyColors(source, second, 300, 120);
            for (double threshold : new double[]{.95, 1.0}) {
                for (int limit : new int[]{1, 10}) {
                    var expectedFirst = matcher.findAll(source, first, threshold, limit);
                    var expectedSecond = matcher.findAll(source, second, threshold, limit);
                    var pair = pair(source, first, second, threshold, limit, () -> false);
                    assertEquals(expectedFirst, pair[0], "first alpha=" + alpha + " threshold=" + threshold);
                    assertEquals(expectedSecond, pair[1], "second alpha=" + alpha + " threshold=" + threshold);
                }
            }
        }
        assertWorkerFinished();
    }

    @Test
    void cancellationJoinsNativeWorkerAndNextSearchStillWorks() throws Exception {
        BufferedImage template = ScaleMatchingTest.pattern(40, 40);
        BufferedImage source = ScaleMatchingTest.pattern(512, 256);
        assertThrows(CancellationException.class,
                () -> pair(source, template, template, .98, 10, () -> true));
        assertWorkerFinished();
        assertNotNull(pair(source, template, template, .98, 10, () -> false)[0].bestCandidate());
    }

    @Test
    void interruptionIsPreservedAfterWorkerCleanup() throws Exception {
        BufferedImage template = ScaleMatchingTest.pattern(40, 40);
        BufferedImage source = ScaleMatchingTest.pattern(512, 256);
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class,
                    () -> pair(source, template, template, .98, 10, () -> false));
            assertTrue(Thread.currentThread().isInterrupted());
            assertWorkerFinished();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void nativeFailureIsPropagatedAfterWorkerCleanup() throws Exception {
        BufferedImage source = ScaleMatchingTest.pattern(100, 80);
        BufferedImage template = ScaleMatchingTest.pattern(20, 20);
        // 一边尺寸越界、另一边合法，覆盖调用线程和工作线程两条异常释放路径。
        BufferedImage invalid = ScaleMatchingTest.pattern(120, 30);
        assertThrows(RuntimeException.class, () -> pair(source, template, invalid, .98, 10, () -> false));
        assertWorkerFinished();
        assertThrows(RuntimeException.class, () -> pair(source, invalid, template, .98, 10, () -> false));
        assertWorkerFinished();
        assertNotNull(pair(source, template, template, .98, 10, () -> false)[1].bestCandidate());
    }

    private OpenCvTemplateMatcher.SearchResult[] pair(BufferedImage source, BufferedImage first,
            BufferedImage second, double threshold, int limit, BooleanSupplier stopRequested) throws Exception {
        matcher.ensureLoaded();
        var conversion = OpenCvTemplateMatcher.class.getDeclaredMethod("toBgrMat", BufferedImage.class);
        conversion.setAccessible(true);
        Mat sourceMat = (Mat) conversion.invoke(matcher, source);
        try {
            var method = OpenCvTemplateMatcher.class.getDeclaredMethod("matchPair", Mat.class,
                    BufferedImage.class, BufferedImage.class, BufferedImage.class,
                    double.class, int.class, BooleanSupplier.class);
            method.setAccessible(true);
            try {
                return (OpenCvTemplateMatcher.SearchResult[]) method.invoke(matcher, sourceMat,
                        source, first, second, threshold, limit, stopRequested);
            } catch (InvocationTargetException exception) {
                if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
                if (exception.getCause() instanceof Error error) throw error;
                throw exception;
            }
        } finally {
            sourceMat.release();
        }
    }

    private static BufferedImage maskedPattern(int width, int height, int alpha) {
        BufferedImage colors = ScaleMatchingTest.pattern(width, height);
        BufferedImage template = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int pixelAlpha = x < 2 || y < 2 ? 0 : alpha;
            template.setRGB(x, y, pixelAlpha << 24 | colors.getRGB(x, y) & 0xFFFFFF);
        }
        return template;
    }

    private static void copyColors(BufferedImage source, BufferedImage template, int x, int y) {
        source.setRGB(x, y, template.getWidth(), template.getHeight(),
                template.getRGB(0, 0, template.getWidth(), template.getHeight(), null, 0, template.getWidth()),
                0, template.getWidth());
    }

    private static void assertWorkerFinished() {
        assertTrue(Thread.getAllStackTraces().keySet().stream()
                .noneMatch(thread -> thread.isAlive() && thread.getName().equals("opencv-scale-match")),
                "识别返回时不应遗留原生匹配工作线程");
    }
}
