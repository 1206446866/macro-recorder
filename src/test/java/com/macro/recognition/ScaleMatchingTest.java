package com.macro.recognition;

import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 真实 OpenCV 配合合成图像，不截取桌面或发送输入。 */
class ScaleMatchingTest {
    private final ScreenRecognitionService service = new ScreenRecognitionService();

    @Test
    void optInFindsCommonDpiRatiosAndReturnsActualBounds() {
        BufferedImage template = pattern(36, 30);
        for (double scale : new double[]{2.0 / 3.0, 5.0 / 6.0, 1.2, 1.5}) {
            BufferedImage target = resize(template, scale);
            BufferedImage screen = new BufferedImage(150, 120, BufferedImage.TYPE_INT_RGB);
            paste(screen, target, 39, 24);
            var screens = screens(screen);
            assertTrue(service.recognize(screens, template, 1.0).matches().isEmpty(), "旧入口仍按原尺寸搜索");
            assertTrue(service.recognize(screens, template, 1.0, false).matches().isEmpty());
            var report = service.recognize(screens, template, 1.0, true);
            assertEquals(1, report.matches().size(), "比例 " + scale);
            var match = report.matches().get(0);
            assertEquals(new Rectangle(39, 24, target.getWidth(), target.getHeight()), match.pixelBounds());
            assertEquals(new Rectangle(-74, 36, (int) Math.round(target.getWidth() / 1.5), (int) Math.round(target.getHeight() / 1.5)),
                    match.logicalBounds(), "保留负坐标和 150% DPI 换算");
            assertEquals(1.0, match.similarity());
        }
    }

    @Test
    void nativeSizeAcrossAllDisplaysWinsBeforeAnyScaledSearch() {
        BufferedImage template = pattern(30, 30);
        BufferedImage scaledScreen = new BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB);
        paste(scaledScreen, resize(template, 1.5), 20, 10);
        BufferedImage nativeScreen = new BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB);
        paste(nativeScreen, template, 45, 20);
        var report = service.recognize(List.of(
                new ScreenRecognitionService.ScreenSnapshot(scaledScreen, new Rectangle(0, 0, 100, 80), "缩放屏"),
                new ScreenRecognitionService.ScreenSnapshot(nativeScreen, new Rectangle(100, 0, 100, 80), "原尺寸屏")),
                template, 1.0, true);
        assertEquals(1, report.matches().size());
        assertEquals("原尺寸屏", report.matches().get(0).displayName());
        assertEquals(new Rectangle(145, 20, 30, 30), report.matches().get(0).logicalBounds());
    }

    @Test
    void fineSearchFindsSizeBetweenCoarseRatios() {
        BufferedImage template = pattern(40, 40);
        BufferedImage target = resize(template, 1.15);
        BufferedImage screen = new BufferedImage(512, 256, BufferedImage.TYPE_INT_RGB);
        paste(screen, target, 30, 18);
        var report = service.recognize(screens(screen), template, .98, true);
        assertEquals(new Rectangle(30, 18, 46, 46), report.matches().get(0).pixelBounds());
    }

    @Test
    void templateLargerThanScreenCanStillBeReducedToFit() {
        BufferedImage template = pattern(60, 60);
        BufferedImage screen = new BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB);
        paste(screen, resize(template, 2.0 / 3.0), 5, 5);
        assertTrue(service.recognize(screens(screen), template, 1).matches().isEmpty());
        var report = service.recognize(screens(screen), template, 1, true);
        assertEquals(new Rectangle(5, 5, 40, 40), report.matches().get(0).pixelBounds());
    }

    @Test
    void differentSizedTargetsAreRetainedAndNearbyDuplicateScalesAreMerged() {
        BufferedImage template = pattern(40, 40);
        BufferedImage screen = new BufferedImage(600, 300, BufferedImage.TYPE_INT_RGB);
        paste(screen, resize(template, .8), 10, 12);
        paste(screen, resize(template, 1.25), 115, 20);
        var matches = service.recognize(screens(screen), template, .90, true).matches();
        assertEquals(2, matches.size());
        assertTrue(matches.stream().anyMatch(m -> m.pixelBounds().equals(new Rectangle(10, 12, 32, 32))));
        assertTrue(matches.stream().anyMatch(m -> m.pixelBounds().equals(new Rectangle(115, 20, 50, 50))));
    }

    @Test
    void unrelatedAndTransparentTemplatesDoNotBecomeMatches() {
        BufferedImage template = pattern(30, 30);
        assertTrue(service.recognize(screens(new BufferedImage(80, 70, BufferedImage.TYPE_INT_RGB)),
                template, .99, true).matches().isEmpty());
        BufferedImage transparent = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
        var report = service.recognize(screens(pattern(70, 70)), transparent, .9, true);
        assertTrue(report.matches().isEmpty());
        assertNull(report.bestCandidate());
    }

    @Test
    void cancellationIsCheckedBeforeAndBetweenScaleAttempts() {
        BufferedImage template = pattern(30, 30), screen = pattern(100, 90);
        assertThrows(CancellationException.class,
                () -> service.recognize(screens(screen), template, 1, true, () -> true));
        AtomicInteger checks = new AtomicInteger();
        assertThrows(CancellationException.class, () -> new OpenCvTemplateMatcher().findAllScaled(
                screen, template, 1, 10, null, () -> checks.incrementAndGet() > 7));
        assertEquals(8, checks.get());
    }

    @Test
    void scaleCacheReusesPixelsAndInvalidatesOnTemplateReplacement() {
        TemplateScaleVariants variants = new TemplateScaleVariants();
        BufferedImage template = pattern(20, 20);
        BufferedImage first = variants.image(template, 24, 24);
        assertSame(first, variants.image(template, 24, 24));
        assertEquals(24 * 24 * 4, variants.cachedBytes());
        variants.prepare(pattern(20, 20));
        assertEquals(0, variants.cachedBytes());
        assertNotSame(first, variants.image(template, 24, 24));
    }

    @Test
    void scaleCacheHasABoundedPixelBudget() {
        TemplateScaleVariants variants = new TemplateScaleVariants();
        BufferedImage template = pattern(10, 10);
        for (int width : new int[]{4096, 4097, 4098}) {
            variants.image(template, width, 1024);
            assertTrue(variants.cachedBytes() <= 32L * 1024 * 1024);
        }
    }

    private static List<ScreenRecognitionService.ScreenSnapshot> screens(BufferedImage image) {
        return List.of(new ScreenRecognitionService.ScreenSnapshot(image,
                new Rectangle(-100, 20, image.getWidth() * 2 / 3, image.getHeight() * 2 / 3), "测试屏"));
    }

    static BufferedImage pattern(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(7281);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) image.setRGB(x, y, random.nextInt(0x1000000));
        return image;
    }

    static BufferedImage resize(BufferedImage template, double scale) {
        BufferedImage image = new BufferedImage((int) Math.round(template.getWidth() * scale),
                (int) Math.round(template.getHeight() * scale), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(template, 0, 0, image.getWidth(), image.getHeight(), null);
        } finally { graphics.dispose(); }
        return image;
    }

    static void paste(BufferedImage image, BufferedImage target, int x, int y) {
        Graphics2D graphics = image.createGraphics();
        try { graphics.drawImage(target, x, y, null); } finally { graphics.dispose(); }
    }
}
