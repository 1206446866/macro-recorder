package com.macro.recognition;

import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

/** 使用真实 OpenCV 和合成屏幕；不采集桌面、不访问个人图片、不发送输入。 */
class ScreenRecognitionServiceTest {
    private final ScreenRecognitionService service = new ScreenRecognitionService();

    @Test
    void exactMatchesAcrossDisplaysKeepScaledAndNegativeCoordinates() {
        BufferedImage template = image(6, 6);
        Random random = new Random(17);
        for (int y = 0; y < 6; y++) for (int x = 0; x < 6; x++) {
            template.setRGB(x, y, random.nextInt(0x1000000));
        }
        BufferedImage screen = image(120, 60);
        for (int y = 0; y < 6; y++) for (int x = 0; x < 6; x++) {
            screen.setRGB(x + 9, y + 6, template.getRGB(x, y));
        }
        var report = service.recognize(List.of(
                new ScreenRecognitionService.ScreenSnapshot(screen, new Rectangle(-100, 10, 80, 40), "左屏"),
                new ScreenRecognitionService.ScreenSnapshot(screen, new Rectangle(100, 20, 120, 60), "右屏")
        ), template, 1.0);
        assertEquals(2, report.matches().size());
        assertEquals(List.of(new Rectangle(-94, 14, 4, 4), new Rectangle(109, 26, 6, 6)),
                report.matches().stream().map(ScreenRecognitionService.RecognitionResult::logicalBounds).toList());
        assertTrue(report.matches().stream().allMatch(r -> r.matched() && r.similarity() == 1.0));
        assertEquals(new Rectangle(9, 6, 6, 6), report.bestCandidate().pixelBounds());
        assertFalse(report.truncated());
        assertTrue(report.estimatedWorkingMemoryBytes() > 0);
    }

    @Test
    void thresholdAndEmptyResultsKeepTheirMeaning() {
        BufferedImage template = image(1, 1);
        BufferedImage screen = image(1, 1);
        screen.setRGB(0, 0, 0x333333);
        var snapshots = List.of(new ScreenRecognitionService.ScreenSnapshot(screen, new Rectangle(0, 0, 1, 1), "屏幕"));
        assertEquals(1, service.recognize(snapshots, template, .79).matches().size());
        var rejected = service.recognize(snapshots, template, .81);
        assertTrue(rejected.matches().isEmpty());
        assertNotNull(rejected.bestCandidate());
        assertFalse(rejected.bestCandidate().matched());
        for (var empty : List.of(service.recognize(List.of(), template, .9),
                service.recognize(null, template, .9))) {
            assertTrue(empty.matches().isEmpty());
            assertNull(empty.bestCandidate());
            assertFalse(empty.truncated());
        }
    }

    @Test
    void totalResultsRemainCappedAcrossDisplays() {
        BufferedImage template = image(1, 1);
        template.setRGB(0, 0, 0x123456);
        List<ScreenRecognitionService.ScreenSnapshot> snapshots = new ArrayList<>();
        for (int i = 0; i < 101; i++) snapshots.add(new ScreenRecognitionService.ScreenSnapshot(
                template, new Rectangle(i, 0, 1, 1), "屏幕 " + i));
        var report = service.recognize(snapshots, template, 1.0);
        assertEquals(100, report.matches().size());
        assertTrue(report.truncated());
    }

    @Test
    void cancellationStillStopsRecognition() {
        BufferedImage image = image(1, 1);
        new OpenCvTemplateMatcher().ensureLoaded();
        try {
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class, () -> service.recognize(List.of(
                    new ScreenRecognitionService.ScreenSnapshot(image, new Rectangle(0, 0, 1, 1), "屏幕")), image, .9));
        } finally {
            Thread.interrupted();
        }
    }

    private static BufferedImage image(int width, int height) {
        return new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    }
}
