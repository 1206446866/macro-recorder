package com.macro.ui.window;

import org.junit.jupiter.api.Test;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecordingBarGeometryTest {
    private static final Dimension MINIMUM = new Dimension(640, 112);

    @Test
    void restoresNegativeMonitorPositionsAndIgnoresStoredHeight() {
        List<Rectangle> screens = List.of(new Rectangle(0, 0, 1920, 1040), new Rectangle(-1600, -200, 1600, 860));
        assertEquals(new Rectangle(-1500, -100, 800, 112),
                RecordingBarGeometry.restore(new Rectangle(-1500, -100, 800, 999), MINIMUM, screens));
        assertEquals(new Rectangle(-650, 548, 650, 112),
                RecordingBarGeometry.restore(new Rectangle(-650, 620, 650, 1), MINIMUM, screens));
    }

    @Test
    void clampsRemovedMonitorChangedResolutionAndExtremeValues() {
        Rectangle screen = new Rectangle(40, 30, 1240, 660);
        for (Rectangle requested : List.of(new Rectangle(4000, 2500, 1000, 900),
                new Rectangle(Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, 1),
                new Rectangle(1200, -500, 100, 0))) {
            Rectangle actual = RecordingBarGeometry.restore(requested, MINIMUM, List.of(screen));
            assertTrue(screen.contains(actual));
            assertTrue(actual.width >= MINIMUM.width);
            assertEquals(MINIMUM.height, actual.height);
        }
        assertEquals(new Rectangle(40, 30, 1240, 112), RecordingBarGeometry.restore(
                new Rectangle(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, 0), MINIMUM, List.of(screen)));
    }

    @Test
    void selectsNearestScreenWhenOldPositionHasNoIntersection() {
        List<Rectangle> screens = List.of(new Rectangle(0, 0, 1280, 720), new Rectangle(-1920, 0, 1920, 1040));
        assertEquals(new Rectangle(-1920, 100, 700, 112),
                RecordingBarGeometry.restore(new Rectangle(-4000, 100, 700, 0), MINIMUM, screens));
        // 极小屏幕无法同时满足完整可见和最小控件宽度，仍把标题区放回工作区起点。
        assertEquals(new Rectangle(0, 0, 640, 112), RecordingBarGeometry.restore(
                new Rectangle(900, 900, 800, 0), MINIMUM, List.of(new Rectangle(0, 0, 400, 80))));
    }

    @Test
    void nativeSizingKeepsHeightAndAnchorsOppositeEdgeAt100And150Percent() {
        for (double scale : new double[]{1, 1.5}) {
            Dimension limits = new Dimension((int) (640 * scale), (int) (112 * scale));
            Rectangle proposed = new Rectangle(-100, -200, 300, 500);
            for (int edge = 1; edge <= 8; edge++) {
                Rectangle actual = RecordingBarGeometry.constrainSizing(proposed, edge, limits);
                assertEquals(limits.height, actual.height);
                assertEquals(limits.width, actual.width);
                if (edge == 1 || edge == 4 || edge == 7) assertEquals(proposed.getMaxX(), actual.getMaxX());
                else assertEquals(proposed.x, actual.x);
                if (edge == 3 || edge == 4 || edge == 5) assertEquals(proposed.getMaxY(), actual.getMaxY());
                else assertEquals(proposed.y, actual.y);
            }
        }
        assertEquals(10, RecordingBarGeometry.horizontalHit(13));
        assertEquals(10, RecordingBarGeometry.horizontalHit(16));
        assertEquals(11, RecordingBarGeometry.horizontalHit(14));
        assertEquals(11, RecordingBarGeometry.horizontalHit(17));
        assertEquals(1, RecordingBarGeometry.horizontalHit(12));
        assertEquals(1, RecordingBarGeometry.horizontalHit(15));
        assertEquals(2, RecordingBarGeometry.horizontalHit(2));
    }
}
