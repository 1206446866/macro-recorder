package com.macro.ui;

import com.macro.flow.MacroFlowFactory;
import com.macro.model.MacroAction;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TimelinePlaybackProgressTest {
    @Test void glowAndCurrentBorderPaintWithoutChangingCardsOrTheirLayout() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ActionTimelinePanel panel = panel();
            JPanel track = track(panel);
            TimelineJumpLayoutTest.layoutTrack(track);
            var cards = cards(track);
            List<Rectangle> bounds = cards.stream().map(Component::getBounds).toList();
            List<Dimension> preferred = cards.stream().map(Component::getPreferredSize).toList();
            BufferedImage first = paint(cards.get(0)), current = paint(cards.get(1)), skipped = paint(cards.get(2));
            var nodes = MacroFlowFactory.timelineNodes(panel.getFlow());
            panel.applyPlaybackProgress(new PlaybackProgressBuffer.Batch(1, true,
                    Set.of(nodes.get(0).getId()), nodes.get(1).getId(), false, false));
            assertNotEquals(first.getRGB(10, 65), paint(cards.get(0)).getRGB(10, 65), "完成卡片内部必须实际绘制光晕");
            assertNotEquals(current.getRGB(3, 80), paint(cards.get(1)).getRGB(3, 80), "当前卡片边框必须可见");
            assertEquals(skipped.getRGB(10, 65), paint(cards.get(2)).getRGB(10, 65), "未执行卡片不能点亮");
            assertEquals(cards, cards(track), "进度不能重建卡片");
            assertEquals(bounds, cards.stream().map(Component::getBounds).toList());
            assertEquals(preferred, cards.stream().map(Component::getPreferredSize).toList());
        });
    }

    @Test void sameFlowBrowseRetainsResultNewLoopAndNewMacroClearIt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ActionTimelinePanel panel = panel();
            String first = MacroFlowFactory.timelineNodes(panel.getFlow()).get(0).getId();
            panel.applyPlaybackProgress(new PlaybackProgressBuffer.Batch(1, true, Set.of(first), null, false, false));
            panel.setFlow(panel.getFlow());
            assertEquals(Set.of(first), panel.getCompletedNodeIds());
            panel.applyPlaybackProgress(new PlaybackProgressBuffer.Batch(2, true, Set.of(), first, false, false));
            assertTrue(panel.getCompletedNodeIds().isEmpty());
            assertEquals(2, panel.getPlaybackLoop());
            panel.applyPlaybackProgress(new PlaybackProgressBuffer.Batch(2, false, Set.of(first), null, false, false));
            assertEquals(Set.of(first), panel.getCompletedNodeIds());
            assertNull(panel.getCurrentPlaybackNodeId());
            panel.setActions(List.of(MacroAction.mouseMove(9, 9, 0)));
            assertTrue(panel.getCompletedNodeIds().isEmpty());
            assertEquals(0, panel.getPlaybackLoop());
        });
    }

    @Test void readonlyProgressStillAllowsSelectionButPreventsEdits() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ActionTimelinePanel panel = panel();
            var first = MacroFlowFactory.timelineNodes(panel.getFlow()).get(0);
            panel.setEditingEnabled(false);
            assertTrue(((JLabel) StandbyHeadTimelineTest.field(panel, "timelineEditHint")).getText().contains("锁定"));
            AtomicInteger edits = new AtomicInteger();
            panel.setOnActionDoubleClicked(node -> edits.incrementAndGet());
            JPanel card = cards(track(panel)).get(0);
            card.dispatchEvent(new MouseEvent(card, MouseEvent.MOUSE_PRESSED, 0, 0, 2, 2, 1, false, MouseEvent.BUTTON1));
            card.dispatchEvent(new MouseEvent(card, MouseEvent.MOUSE_CLICKED, 0, 0, 2, 2, 2, false, MouseEvent.BUTTON1));
            assertSame(first, panel.getSelectedMacroNode());
            assertEquals(0, edits.get());
            assertFalse(panel.deleteAction(first, false));
            assertFalse(panel.setStandbyHeadEnabled(true));
        });
    }

    @Test void repeatedIdenticalProgressDoesNotRequestAnotherPaintOrLayout() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ActionTimelinePanel panel = panel();
            String id = MacroFlowFactory.timelineNodes(panel.getFlow()).get(0).getId();
            var batch = new PlaybackProgressBuffer.Batch(1, false, Set.of(id), id, false, false);
            RepaintManager previous = RepaintManager.currentManager(panel);
            AtomicInteger paints = new AtomicInteger();
            AtomicInteger validations = new AtomicInteger();
            RepaintManager manager = new RepaintManager() {
                @Override public void addDirtyRegion(JComponent component, int x, int y, int width, int height) {
                    if (component.getClass().getSimpleName().equals("NodePanel")) paints.incrementAndGet();
                    super.addDirtyRegion(component, x, y, width, height);
                }
                @Override public void addInvalidComponent(JComponent component) {
                    validations.incrementAndGet();
                    super.addInvalidComponent(component);
                }
            };
            RepaintManager.setCurrentManager(manager);
            try {
                panel.applyPlaybackProgress(batch);
                assertTrue(paints.get() > 0);
                paints.set(0);
                panel.applyPlaybackProgress(batch);
                assertEquals(0, paints.get());
                assertEquals(0, validations.get());
            } finally { RepaintManager.setCurrentManager(previous); }
        });
    }

    @Test void standbyHeadCanShowCurrentAndCompletedWithoutBecomingAnAction() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ActionTimelinePanel panel = panel();
            panel.setStandbyHeadEnabled(true);
            JComponent head = assertInstanceOf(JComponent.class,
                    StandbyHeadTimelineTest.named(panel, "standbyHead"));
            head.setSize(head.getPreferredSize());
            BufferedImage plain = paint(head);
            panel.applyPlaybackProgress(new PlaybackProgressBuffer.Batch(1, true, Set.of(), null, true, false));
            assertNotEquals(plain.getRGB(3, 80), paint(head).getRGB(3, 80));
            panel.applyPlaybackProgress(new PlaybackProgressBuffer.Batch(1, false, Set.of(), null, false, true));
            assertNotEquals(plain.getRGB(10, 65), paint(head).getRGB(10, 65));
            assertTrue(panel.getCompletedNodeIds().isEmpty());
            assertEquals(3, panel.getActions().size());
        });
    }

    private static ActionTimelinePanel panel() {
        ActionTimelinePanel panel = new ActionTimelinePanel();
        panel.setActions(List.of(MacroAction.mouseMove(1, 2, 0),
                MacroAction.mouseMove(3, 4, 50), MacroAction.mouseMove(5, 6, 50)));
        return panel;
    }

    private static List<JPanel> cards(JPanel track) {
        return java.util.Arrays.stream(track.getComponents()).filter(component -> component.getClass().getSimpleName().equals("NodePanel"))
                .map(component -> (JPanel) component).toList();
    }

    private static JPanel track(ActionTimelinePanel panel) {
        try { return TimelineJumpLayoutTest.track(panel); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }

    private static BufferedImage paint(JComponent component) {
        BufferedImage image = new BufferedImage(component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        component.paint(graphics);
        graphics.dispose();
        return image;
    }
}
