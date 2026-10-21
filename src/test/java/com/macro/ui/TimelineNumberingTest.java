package com.macro.ui;

import com.macro.flow.*;
import com.macro.model.MacroAction;
import com.macro.model.MouseButton;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TimelineNumberingTest {
    @Test void numbersSkipInternalEndAndGrowPastTwoDigitsWithoutChangingIds() {
        List<MacroNode> nodes = new ArrayList<>();
        nodes.add(new MacroNode("end", NodeType.END, null, new ArrayList<>()));
        for (int i = 0; i < 101; i++) nodes.add(TimelineJumpLayoutTest.action("id-" + i));
        var numbers = TimelineNodeNumbers.labels(nodes);
        assertEquals(101, numbers.size());
        assertEquals("01", numbers.get("id-0"));
        assertEquals("99", numbers.get("id-98"));
        assertEquals("100", numbers.get("id-99"));
        assertEquals("101", numbers.get("id-100"));
        assertEquals("id-0", nodes.get(1).getId());
    }

    @Test void paintedBadgeDoesNotChangeCardChildrenGeometryOrTimelineSpacing() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                ImageCheckConfig config = new ImageCheckConfig("synthetic.png", .9);
                List<MacroNode> nodes = List.of(TimelineJumpLayoutTest.action("move"),
                        new MacroNode("down", NodeType.ACTION, MacroAction.mousePress(1, 2, MouseButton.LEFT, 0), new ArrayList<>()),
                        new MacroNode("key", NodeType.ACTION, MacroAction.keyPress(65, "A", 0), new ArrayList<>()),
                        new MacroNode("image", NodeType.IMAGE_CHECK, null, config, new ArrayList<>()));
                ActionTimelinePanel timeline = new ActionTimelinePanel();
                timeline.setFlow(MacroFlowFactory.rebuildLinear(nodes, "end"));
                JPanel track = TimelineJumpLayoutTest.track(timeline);
                TimelineJumpLayoutTest.layoutTrack(track);
                Map<Component, Geometry> expected = geometry(track);
                for (JPanel card : cards(track)) {
                    assertEquals(new Dimension(158, 176), card.getSize());
                    assertEquals(new Dimension(158, 176), card.getPreferredSize());
                    assertEquals(new Dimension(158, 176), card.getMinimumSize());
                    assertEquals(new Dimension(158, 176), card.getMaximumSize());
                    Field number = field(card, "displayNumber");
                    String text = (String) number.get(card);
                    number.set(card, "");
                    BufferedImage plain = paint(card);
                    number.set(card, text);
                    BufferedImage numbered = paint(card);
                    assertNotEquals(plain.getRGB(10, 15), numbered.getRGB(10, 15), "徽标必须实际绘制");
                    field(card, "selected").setBoolean(card, true);
                    paint(card);
                    TimelineJumpLayoutTest.layoutTrack(track);
                    assertEquals(expected, geometry(track), "徽标与选中状态不能改变任何子组件、连接器或轨道尺寸");
                }
            } catch (Exception e) { throw new AssertionError(e); }
        });
    }

    @Test void insertDeleteAndReorderRenumberBadgesAndLabelsButKeepBranchIds() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                MacroNode first = TimelineJumpLayoutTest.action("first"), last = TimelineJumpLayoutTest.action("last");
                ImageCheckConfig config = new ImageCheckConfig("synthetic.png", .9);
                config.setSuccessBehavior(ImageCheckBehavior.JUMP);
                config.setFailureBehavior(ImageCheckBehavior.JUMP);
                MacroNode image = new MacroNode("image", NodeType.IMAGE_CHECK, null, config, new ArrayList<>(List.of(
                        new MacroBranch(BranchCondition.FOUND, "last", 0), new MacroBranch(BranchCondition.NOT_FOUND, "first", 0))));
                ActionTimelinePanel timeline = new ActionTimelinePanel();
                timeline.setFlow(MacroFlowFactory.rebuildLinear(List.of(first, image, last), "end"));
                JPanel track = TimelineJumpLayoutTest.track(timeline);
                assertNumbers(track, List.of("01", "02", "03"));
                MacroNode inserted = TimelineJumpLayoutTest.action("temporary");
                assertTrue(timeline.insertActionBefore(first, inserted));
                assertNumbers(track, List.of("01", "02", "03", "04"));
                TimelineJumpLayoutTest.layoutTrack(track);
                assertTrue(TimelineJumpLayoutTest.routes(track).get(0).edge().label().endsWith("04"));
                assertTrue(timeline.deleteAction(inserted, false));
                assertNumbers(track, List.of("01", "02", "03"));
                timeline.setFlow(MacroFlowFactory.rebuildLinear(List.of(last, image, first), "end"));
                assertNumbers(track, List.of("01", "02", "03"));
                TimelineJumpLayoutTest.layoutTrack(track);
                var routes = TimelineJumpLayoutTest.routes(track);
                assertEquals("last", routes.get(0).edge().targetId());
                assertTrue(routes.get(0).edge().label().endsWith("01"));
                assertTrue(routes.get(1).edge().label().endsWith("03"));
                assertEquals("last", MacroFlowFactory.branchFor(image, BranchCondition.FOUND).getTargetId());
                assertEquals("first", MacroFlowFactory.branchFor(image, BranchCondition.NOT_FOUND).getTargetId());
                assertEquals("image", image.getId());
            } catch (Exception e) { throw new AssertionError(e); }
        });
    }

    private static List<JPanel> cards(JPanel track) {
        return java.util.Arrays.stream(track.getComponents()).filter(c -> c.getClass().getSimpleName().equals("NodePanel"))
                .map(c -> (JPanel) c).toList();
    }
    private static Field field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void assertNumbers(JPanel track, List<String> expected) throws Exception {
        List<String> actual = new ArrayList<>();
        for (JPanel card : cards(track)) actual.add((String) field(card, "displayNumber").get(card));
        assertEquals(expected, actual);
    }
    private record Geometry(Rectangle bounds, Dimension preferred, Dimension minimum, Dimension maximum) { }
    private static Map<Component, Geometry> geometry(Component component) {
        Map<Component, Geometry> result = new LinkedHashMap<>();
        result.put(component, new Geometry(component.getBounds(), component.getPreferredSize(), component.getMinimumSize(), component.getMaximumSize()));
        if (component instanceof Container container) for (Component child : container.getComponents()) result.putAll(geometry(child));
        return result;
    }
    private static BufferedImage paint(JPanel card) {
        BufferedImage image = new BufferedImage(card.getWidth(), card.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try { card.paint(graphics); } finally { graphics.dispose(); }
        return image;
    }
}
