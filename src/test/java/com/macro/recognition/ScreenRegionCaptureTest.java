package com.macro.recognition;

import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** 合成多屏捕获，验证真正传给截图源的区域以及 DPI/负坐标转换。 */
class ScreenRegionCaptureTest {
    @Test void clipsToDisplaysAndCapturesOnlyIntersectingAreas() throws Exception {
        var left = geometry("left", new Rectangle(-400, -20, 400, 240), 1.5);
        var right = geometry("right", new Rectangle(0, 0, 600, 400), 1);
        List<Rectangle> requests = new ArrayList<>();
        var service = new ScreenRecognitionService(() -> List.of(left, right), (screen, bounds) -> {
            requests.add(new Rectangle(bounds));
            return pixels(bounds, screen.scaleX());
        });
        var snapshots = service.captureScreens(service.screenGeometry(), new Rectangle(-40, 10, 100, 60), () -> false);
        assertEquals(List.of(new Rectangle(-40, 10, 40, 60), new Rectangle(0, 10, 60, 60)), requests);
        assertEquals(requests, snapshots.stream().map(ScreenRecognitionService.ScreenSnapshot::logicalBounds).toList());
        assertEquals(60, snapshots.get(0).image().getWidth());
        assertEquals(90, snapshots.get(0).image().getHeight());
        requests.clear();
        service.captureScreens(service.screenGeometry(), new Rectangle(590, 390, 100, 100), () -> false);
        assertEquals(List.of(new Rectangle(590, 390, 10, 10)), requests);
        requests.clear();
        assertTrue(service.captureScreens(service.screenGeometry(), new Rectangle(900, 900, 10, 10), () -> false).isEmpty());
        assertTrue(requests.isEmpty());
    }

    @Test void preservesPixelToLogicalMappingInsideNegativeDpiCrop() throws Exception {
        var left = geometry("left", new Rectangle(-400, 0, 400, 240), 1.5);
        BufferedImage template = AdaptiveImageSearchTest.pattern(12, 12);
        var service = new ScreenRecognitionService(() -> List.of(left), (screen, bounds) -> {
            BufferedImage pixels = new BufferedImage(bounds.width * 3 / 2, bounds.height * 3 / 2, BufferedImage.TYPE_INT_RGB);
            AdaptiveImageSearchTest.paste(pixels, template, 9, 6);
            return new BaseMultiResolutionImage(pixels);
        });
        var snapshots = service.captureScreens(service.screenGeometry(), new Rectangle(-100, 20, 80, 60), () -> false);
        var result = service.recognize(snapshots, template, 1).matches().get(0);
        assertEquals(new Rectangle(-94, 24, 8, 8), result.logicalBounds());
        assertEquals(new Rectangle(9, 6, 12, 12), result.pixelBounds());
    }

    @Test void checksStopBeforeAndAfterEachCapture() {
        var first = geometry("first", new Rectangle(0, 0, 100, 100), 1);
        var second = geometry("second", new Rectangle(100, 0, 100, 100), 1);
        AtomicBoolean stopped = new AtomicBoolean();
        List<Rectangle> requests = new ArrayList<>();
        var service = new ScreenRecognitionService(() -> List.of(first, second), (screen, bounds) -> {
            requests.add(bounds);
            stopped.set(true);
            return pixels(bounds, 1);
        });
        assertThrows(CancellationException.class, () -> service.captureScreens(service.screenGeometry(), null, () -> true));
        assertTrue(requests.isEmpty());
        assertThrows(CancellationException.class, () -> service.captureScreens(service.screenGeometry(), null, stopped::get));
        assertEquals(1, requests.size(), "停止后不能继续捕获第二个屏幕");
    }

    @Test void geometryDoesNotExposeMutableCoordinatesAndFullEntryStillCapturesAll() throws Exception {
        Rectangle original = new Rectangle(0, 0, 120, 80);
        var screen = geometry("screen", original, 1);
        original.x = 20;
        screen.bounds().x = 30;
        assertEquals(new Rectangle(0, 0, 120, 80), screen.bounds());
        List<Rectangle> requests = new ArrayList<>();
        var service = new ScreenRecognitionService(() -> List.of(screen), (display, bounds) -> {
            requests.add(bounds);
            return pixels(bounds, 1);
        });
        assertEquals(1, service.captureScreens().size());
        assertEquals(List.of(new Rectangle(0, 0, 120, 80)), requests);
    }

    static ScreenRecognitionService.ScreenGeometry geometry(String id, Rectangle bounds, double scale) {
        return new ScreenRecognitionService.ScreenGeometry(id, bounds, scale, scale, id);
    }

    private static BaseMultiResolutionImage pixels(Rectangle bounds, double scale) {
        return new BaseMultiResolutionImage(new BufferedImage((int) Math.round(bounds.width * scale),
                (int) Math.round(bounds.height * scale), BufferedImage.TYPE_INT_RGB));
    }
}
