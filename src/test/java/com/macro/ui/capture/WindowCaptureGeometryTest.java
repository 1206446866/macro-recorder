package com.macro.ui.capture;

import org.junit.jupiter.api.Test;
import java.awt.Rectangle;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class WindowCaptureGeometryTest {
    @Test void maps150PercentClientAreaAndRoundsInward() {
        assertEquals(new Rectangle(101, 81, 199, 119), WindowCaptureGeometry.selectableBounds(
                new Rectangle(151, 121, 300, 180), new Rectangle(0, 0, 1920, 1080), new Rectangle(0, 0, 1280, 720)));
    }
    @Test void mapsNegativeMonitorOriginAndClipsOffscreenClient() {
        Rectangle physical = new Rectangle(-1920, -150, 1920, 1080), logical = new Rectangle(-1920, -150, 1280, 720);
        assertEquals(new Rectangle(-1920, -150, 200, 200), WindowCaptureGeometry.selectableBounds(
                new Rectangle(-2000, -200, 380, 350), physical, logical));
        assertTrue(WindowCaptureGeometry.selectableBounds(new Rectangle(2000, 2000, 20, 20), physical, logical).isEmpty());
    }
    @Test void conversionNeverExpandsBeyondVisiblePhysicalClient() {
        Random random = new Random(5881);
        for (int sample = 0; sample < 1000; sample++) {
            Rectangle physical = new Rectangle(-2560, 200, 2560, 1440), logical = new Rectangle(-2560, 200, 1707, 960);
            Rectangle client = new Rectangle(random.nextInt(3100)-2800, random.nextInt(1800), 2+random.nextInt(2000), 2+random.nextInt(1000));
            Rectangle result = WindowCaptureGeometry.selectableBounds(client, physical, logical);
            if (result.isEmpty()) continue;
            Rectangle visible = client.intersection(physical);
            double sx = physical.width/(double)logical.width, sy = physical.height/(double)logical.height;
            assertTrue(physical.x + (result.x-logical.x)*sx >= visible.x - 1e-8);
            assertTrue(physical.y + (result.y-logical.y)*sy >= visible.y - 1e-8);
            assertTrue(physical.x + (result.x+result.width-logical.x)*sx <= visible.x+visible.width + 1e-8);
            assertTrue(physical.y + (result.y+result.height-logical.y)*sy <= visible.y+visible.height + 1e-8);
        }
        assertThrows(IllegalArgumentException.class, () -> WindowCaptureGeometry.selectableBounds(new Rectangle(), new Rectangle(), new Rectangle()));
    }
}
