package com.macro.flow;

import org.junit.jupiter.api.Test;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class ImageClickModeTest {
    @Test void centerUsesActualLogicalBoundsIncludingNegativeCoordinates() {
        Rectangle bounds = new Rectangle(-151, 27, 31, 19);
        assertEquals(new Point(-136, 36), ImageClickMode.CENTER.pointWithin(bounds));
        assertEquals(new Rectangle(-151, 27, 31, 19), bounds);
    }

    @Test void randomPointsStayInsideBoundsAndSinglePixelWorks() {
        Rectangle bounds = new Rectangle(-170, -30, 37, 29);
        for (int i = 0; i < 1000; i++) assertTrue(bounds.contains(ImageClickMode.RANDOM.pointWithin(bounds)));
        assertEquals(new Point(-2, 3), ImageClickMode.RANDOM.pointWithin(new Rectangle(-2, 3, 1, 1)));
    }

    @Test void emptyBoundsCannotProduceClicks() {
        for (ImageClickMode mode : ImageClickMode.values()) {
            assertThrows(IllegalArgumentException.class, () -> mode.pointWithin(null));
            assertThrows(IllegalArgumentException.class, () -> mode.pointWithin(new Rectangle(1, 2, 0, 10)));
            assertThrows(IllegalArgumentException.class, () -> mode.pointWithin(new Rectangle(1, 2, 10, -1)));
        }
    }

    @Test void bothResultsExposeSameBehaviorsWithJumpLast() {
        for (boolean found : new boolean[]{false, true}) {
            var options = ImageCheckBehavior.optionsForResult(found);
            assertEquals(ImageCheckBehavior.JUMP, options[options.length - 1]);
            assertEquals(ImageCheckBehavior.values().length, options.length);
            assertTrue(Arrays.asList(options).contains(ImageCheckBehavior.CLICK_EXECUTE));
            assertTrue(Arrays.asList(options).contains(ImageCheckBehavior.CLICK_TERMINATE));
        }
        assertArrayEquals(ImageCheckBehavior.optionsForResult(true), ImageCheckBehavior.optionsForResult(false));
    }
}
