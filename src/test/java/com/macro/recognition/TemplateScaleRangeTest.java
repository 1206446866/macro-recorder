package com.macro.recognition;

import org.junit.jupiter.api.Test;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 合成画面验证自定义上下限真正参与匹配，同时保留默认调用语义。 */
class TemplateScaleRangeTest {
    @Test void rejectsInvalidRanges() {
        for (double[] pair : new double[][]{{0, 1.5}, {.009, 1.5}, {1.01, 1.5}, {.5, .99},
                {.5, 4.01}, {Double.NaN, 2}, {.5, Double.POSITIVE_INFINITY}})
            assertThrows(IllegalArgumentException.class, () -> new TemplateScaleRange(pair[0], pair[1]));
        assertDoesNotThrow(() -> new TemplateScaleRange(.01, 4));
        assertDoesNotThrow(() -> new TemplateScaleRange(1, 1));
    }

    @Test void defaultOrderAndCustomEndpointsHaveBoundedCandidates() {
        assertArrayEquals(TemplateScaleVariants.COARSE_SCALES, TemplateScaleRange.DEFAULT.coarseScales());
        for (TemplateScaleRange range : List.of(new TemplateScaleRange(.5, 2),
                new TemplateScaleRange(.98, 1.02), new TemplateScaleRange(.01, 4),
                new TemplateScaleRange(1, 1))) {
            double[] scales = range.coarseScales();
            assertTrue(scales.length <= 10);
            assertTrue(Arrays.stream(scales).allMatch(s -> s >= range.minimum() && s <= range.maximum()));
            if (range.minimum() < 1) assertTrue(Arrays.stream(scales).anyMatch(s -> s == range.minimum()));
            if (range.maximum() > 1) assertTrue(Arrays.stream(scales).anyMatch(s -> s == range.maximum()));
        }
    }

    @Test void customRangeFindsHalfAndDoubleTargetsAndExcludesOutsideRange() {
        var service = new ScreenRecognitionService();
        BufferedImage template = ScaleMatchingTest.pattern(40, 40);
        for (double scale : new double[]{.5, 2}) {
            BufferedImage target = ScaleMatchingTest.resize(template, scale);
            BufferedImage screen = new BufferedImage(180, 150, BufferedImage.TYPE_INT_RGB);
            ScaleMatchingTest.paste(screen, target, 12, 18);
            var snapshots = List.of(new ScreenRecognitionService.ScreenSnapshot(screen,
                    new Rectangle(0, 0, 180, 150), "合成屏幕"));
            assertTrue(service.recognize(snapshots, template, 1, true).matches().isEmpty());
            var report = service.recognize(snapshots, template, 1, true, () -> false,
                    new TemplateScaleRange(.5, 2));
            assertEquals(1, report.matches().size());
            assertEquals(new Rectangle(12, 18, target.getWidth(), target.getHeight()), report.matches().get(0).pixelBounds());
            assertTrue(service.recognize(snapshots, template, 1, true, () -> false,
                    new TemplateScaleRange(.9, 1.1)).matches().isEmpty());
        }
    }

    @Test void customScaleDoesNotOverrideOriginalSizePriority() {
        BufferedImage template = ScaleMatchingTest.pattern(30, 30);
        BufferedImage screen = new BufferedImage(180, 140, BufferedImage.TYPE_INT_RGB);
        ScaleMatchingTest.paste(screen, template, 5, 8);
        ScaleMatchingTest.paste(screen, ScaleMatchingTest.resize(template, 2), 80, 30);
        var report = new ScreenRecognitionService().recognize(List.of(
                new ScreenRecognitionService.ScreenSnapshot(screen, new Rectangle(0, 0, 180, 140), "合成屏幕")),
                template, 1, true, () -> false, new TemplateScaleRange(.5, 2));
        assertEquals(1, report.matches().size());
        assertEquals(new Rectangle(5, 8, 30, 30), report.matches().get(0).pixelBounds());
    }
}
