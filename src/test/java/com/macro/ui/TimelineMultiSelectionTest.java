package com.macro.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.flow.*;
import com.macro.model.MacroAction;
import com.macro.storage.MacroStorage;
import com.macro.player.MacroPlayer;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 合成节点、隔离剪贴板及组件事件；不发送真实输入，不改个人配置。 */
class TimelineMultiSelectionTest {
    @Test void controlTogglesAndShiftUsesAnchorWithTimelineOrder() throws Exception { edt(() -> {
        try (Fixture f = new Fixture()) {
            f.click(3, 0); f.click(1, InputEvent.CTRL_DOWN_MASK);
            assertEquals(List.of("n1", "n3"), f.selected());
            f.click(5, InputEvent.SHIFT_DOWN_MASK);
            assertEquals(List.of("n1", "n2", "n3", "n4", "n5"), f.selected());
            f.click(0, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
            assertEquals(List.of("n0", "n1", "n2", "n3", "n4", "n5"), f.selected());
            f.click(5, InputEvent.CTRL_DOWN_MASK);
            assertEquals(List.of("n0", "n1", "n2", "n3", "n4"), f.selected());
            assertFalse(f.panel.canUndo()); assertEquals(0, f.changes.get());
        }
    }); }

    @Test void plainClickCollapsesGroupOnReleaseAndBackgroundClearsSelection() throws Exception { edt(() -> {
        try (Fixture f = new Fixture()) {
            f.select(1, 3); f.press(1, 0); assertEquals(List.of("n1", "n3"), f.selected());
            f.releaseCard(1, 0); assertEquals(List.of("n1"), f.selected());
            f.select(1, 3); f.track.dispatchEvent(mouse(f.track, MouseEvent.MOUSE_PRESSED, 2, 2, 0));
            assertTrue(f.selected().isEmpty()); assertNull(f.panel.getSelectedMacroNode());
        }
    }); }

    @Test void allEscapeRefreshAndNewFlowHavePredictableSelection() throws Exception { edt(() -> {
        try (Fixture f = new Fixture()) {
            f.key("selectAllTimelineNodes"); assertEquals(f.ids(), f.selected());
            f.panel.refreshTimeline(); assertEquals(f.ids(), f.selected());
            KeyboardFocusManager previous = KeyboardFocusManager.getCurrentKeyboardFocusManager();
            AtomicReference<Component> focus = new AtomicReference<>(new JTextArea());
            try {
                KeyboardFocusManager.setCurrentKeyboardFocusManager(new DefaultKeyboardFocusManager() {
                    @Override public Component getFocusOwner() { return focus.get(); }
                });
                f.key("clearTimelineSelection"); f.key("selectAllTimelineNodes"); f.key("deleteSelectedTimelineAction");
                assertEquals(f.ids(), f.selected()); assertEquals(0, f.changes.get());
                focus.set((JButton) field(f.panel, "batchEditButton"));
                f.key("clearTimelineSelection"); assertTrue(f.selected().isEmpty());
                f.key("selectAllTimelineNodes"); assertEquals(f.ids(), f.selected());
            } finally { KeyboardFocusManager.setCurrentKeyboardFocusManager(previous); }
            f.key("clearTimelineSelection"); assertTrue(f.selected().isEmpty());
            f.select(1, 3); f.panel.setFlow(MacroFlowFactory.fromActions(List.of()));
            assertTrue(f.selected().isEmpty()); assertFalse(((JPanel) field(f.panel, "selectionToolbar")).isVisible());
        }
    }); }

    @Test void modifierClickOnCoordinatesSelectsWithoutStartingInlineEditor() throws Exception { edt(() -> {
        try (Fixture f = new Fixture()) {
            f.click(0, 0);
            Container coordinate = find(f.panel.nodeCard(f.node(2)), "CoordinateEditor");
            coordinate.dispatchEvent(mouse(coordinate, MouseEvent.MOUSE_PRESSED, 5, 5, InputEvent.CTRL_DOWN_MASK));
            coordinate.dispatchEvent(mouse(coordinate, MouseEvent.MOUSE_CLICKED, 5, 5, InputEvent.CTRL_DOWN_MASK));
            assertEquals(List.of("n0", "n2"), f.selected());
            assertNull(field(f.panel, "activeEdit")); assertNull(field(f.drag, "source"));
        }
    }); }

    @Test void batchDelayChangesOnlySelectedActionsAndUndoRestoresEntireSelection() throws Exception { edt(() -> {
        try (Fixture f = new Fixture()) {
            f.select(1, 3); String before = f.json();
            assertTrue(f.panel.applyBatchEdits(f.panel.getSelectedNodes(), 123L, null));
            assertEquals(List.of(0L, 123L, 20L, 123L, 40L, 50L), f.delays());
            assertEquals(List.of("n1", "n3"), f.selected()); assertEquals(1, f.changes.get());
            assertTrue(f.panel.undo()); assertEquals(before, f.json());
            assertEquals(List.of("n1", "n3"), f.selected()); assertEquals("n3", f.panel.getSelectedMacroNode().getId());
            assertEquals("n3", field(f.panel, "selectionAnchorId")); assertFalse(f.panel.canUndo());
        }
    }); }

    @Test void mixedBatchSeparatesRetryFromActionDelayAndPreservesImageOptions() throws Exception { edt(() -> {
        try (Fixture f = new Fixture()) {
            f.makeImage(1, true); f.makeImage(3, false); f.select(0, 1, 3);
            MacroNode retry = f.node(1), noRetry = f.node(3);
            noRetry.getImageCheckConfig().setRetryDelay(888L);
            assertTrue(f.panel.applyBatchEdits(f.panel.getSelectedNodes(), Long.MAX_VALUE, 234L));
            assertEquals(Long.MAX_VALUE, f.node(0).getAction().getDelay());
            assertEquals(234L, retry.getImageCheckConfig().getRetryDelay());
            assertEquals(234L, MacroFlowFactory.branchFor(retry, BranchCondition.NOT_FOUND).getDelay());
            assertEquals(888L, noRetry.getImageCheckConfig().getRetryDelay());
            assertEquals(.93, retry.getImageCheckConfig().getThreshold()); assertTrue(retry.getImageCheckConfig().isScaleMatchingEnabled());
            assertEquals("n4", MacroFlowFactory.branchFor(retry, BranchCondition.FOUND).getTargetId());
            assertEquals(1, f.changes.get());
        }
    }); }

    @Test void invalidNoOpReadOnlyAndStaleBatchNeverPartiallyWriteOrRecord() throws Exception { edt(() -> {
        try (Fixture f = new Fixture()) {
            f.select(1, 3); List<MacroNode> targets = f.panel.getSelectedNodes(); String before = f.json();
            assertThrows(IllegalArgumentException.class, () -> f.panel.applyBatchEdits(targets, 2L, -1L));
            assertFalse(f.panel.applyBatchEdits(targets, null, null));
            assertFalse(f.panel.applyBatchEdits(List.of(f.node(1), f.node(1)), 12L, null));
            assertFalse(f.panel.applyBatchEdits(List.of(f.node(1), action("stale", 0)), 12L, null));
            f.panel.setEditingEnabled(false);
            assertFalse(f.panel.applyBatchEdits(targets, 12L, null)); assertFalse(f.panel.deleteNodes(targets)); assertFalse(f.panel.moveNodes(targets, 0));
            f.panel.setEditingEnabled(true); f.panel.setEditPermission(() -> false);
            assertFalse(f.panel.applyBatchEdits(targets, 12L, null));
            assertEquals(before, f.json()); assertFalse(f.panel.canUndo()); assertEquals(0, f.changes.get());
        }
    }); }

    @Test void unchangedValuesDoNotNotifyOrUseUndoStep() throws Exception { edt(() -> {
        try (Fixture f = new Fixture()) {
            assertFalse(f.panel.applyBatchEdits(List.of(f.node(1)), 10L, null));
            f.makeImage(1, true); assertFalse(f.panel.applyBatchEdits(List.of(f.node(1)), null, 321L));
            assertFalse(f.panel.canUndo()); assertEquals(0, f.changes.get());
        }
    }); }

    @Test void nonContiguousMoveRetainsOrderIdsJumpsAndOneUndoStep() throws Exception { edt(() -> {
        try (Fixture f = new Fixture()) {
            f.makeImage(0, true); f.select(1, 4); String before = f.json();
            assertTrue(f.panel.moveNodes(f.panel.getSelectedNodes(), 3));
            assertEquals(List.of("n0", "n2", "n3", "n1", "n4", "n5"), f.ids());
            assertEquals("n4", MacroFlowFactory.branchFor(f.node(0), BranchCondition.FOUND).getTargetId());
            assertEquals(List.of("n1", "n4"), f.selected()); assertEquals(1, f.changes.get());
            Path dir = directory(); Path file = dir.resolve("batch-moved.mr"); MacroStorage storage = new MacroStorage();
            storage.save(f.panel.getActions(), null, f.panel.getFlow(), file.toString());
            assertEquals(f.panel.getFlow(), storage.load(file.toString()).getFlow());
            assertTrue(f.panel.undo()); assertEquals(before, f.json()); assertEquals(List.of("n1", "n4"), f.selected()); assertFalse(f.panel.canUndo());
        }
    }); }

    @Test void batchMoveHandlesFirstLastNoOpAndInvalidIndices() throws Exception { edt(() -> {
        try (Fixture f = new Fixture()) {
            f.select(1, 2); List<MacroNode> group = f.panel.getSelectedNodes();
            assertFalse(f.panel.moveNodes(group, 1)); assertFalse(f.panel.moveNodes(group, -1)); assertFalse(f.panel.moveNodes(group, 5));
            assertTrue(f.panel.moveNodes(group, 0)); assertEquals(List.of("n1", "n2", "n0", "n3", "n4", "n5"), f.ids());
            assertTrue(f.panel.moveNodes(group, 4)); assertEquals(List.of("n0", "n3", "n4", "n5", "n1", "n2"), f.ids());
            assertEquals("end", MacroFlowFactory.branchFor(group.get(1), BranchCondition.ALWAYS).getTargetId());
            assertEquals(2, f.changes.get());
        }
    }); }

    @Test void playerUsesGroupedOrderAndOriginalJumpIdentityThenUndoRestoresPlayback() throws Exception {
        AtomicReference<MacroFlow> moved = new AtomicReference<>(), restored = new AtomicReference<>();
        edt(() -> {
            try (Fixture f = new Fixture()) {
                for (int i = 0; i < 6; i++) f.node(i).setAction(MacroAction.keyRelease(65 + i, "test", 0));
                f.makeImage(0, true); f.select(1, 4);
                assertTrue(f.panel.moveNodes(f.panel.getSelectedNodes(), 4)); moved.set(f.panel.getFlow());
                assertTrue(f.panel.undo()); restored.set(f.panel.getFlow());
            }
        });
        MacroPlayer player = new MacroPlayer(); RecordingRobot robot = new RecordingRobot();
        Field input = MacroPlayer.class.getDeclaredField("robot"); input.setAccessible(true); input.set(player, robot);
        player.play(moved.get(), 1, (node, context) -> ImageMatchResult.found(new Rectangle(1, 2, 3, 4)));
        assertEquals(List.of(69), robot.keys);
        robot.keys.clear();
        player.play(restored.get(), 1, (node, context) -> ImageMatchResult.found(new Rectangle(1, 2, 3, 4)));
        assertEquals(List.of(69, 70), robot.keys);
    }

    @Test void groupDragPreviewsBothCardsThenCommitsOnceAndRendersAt150Percent() throws Exception { edt(() -> {
        try (Fixture f = new Fixture()) {
            f.makeImage(0, true); f.select(1, 4); f.layout(); String before = f.json(); Path dir = directory();
            f.render(dir.resolve("multi-selected-150.png")); f.press(4, 0);
            int[] centers = (int[]) field(f.drag, "centers");
            Point target = SwingUtilities.convertPoint(f.track, centers[5], f.panel.nodeCard(f.node(4)).getY() + 88, f.drag.layer());
            f.dragEvent(MouseEvent.MOUSE_DRAGGED, target);
            assertTrue(f.drag.isDragging(f.node(1))); assertTrue(f.drag.isDragging(f.node(4)));
            assertEquals(before, f.json()); assertEquals(0, f.changes.get()); assertEquals(4, f.drag.targetIndex());
            f.render(dir.resolve("multi-drag-150.png")); f.dragEvent(MouseEvent.MOUSE_RELEASED, target);
            assertEquals(List.of("n0", "n2", "n3", "n5", "n1", "n4"), f.ids());
            assertEquals(List.of("n1", "n4"), f.selected()); assertEquals(1, f.changes.get());
            assertTrue(f.panel.undo()); assertEquals(before, f.json()); assertEquals(List.of("n1", "n4"), f.selected());
            System.out.println("多选离屏预览：" + dir);
        }
    }); }

    @Test void cancellingGroupDragRestoresOrderAndSelectionWithoutUndo() throws Exception { edt(() -> {
        try (Fixture f = new Fixture()) {
            f.select(1, 3); f.layout(); String before = f.json(); f.press(1, 0);
            Point target = SwingUtilities.convertPoint(f.panel.nodeCard(f.node(4)), 78, 88, f.drag.layer());
            f.dragEvent(MouseEvent.MOUSE_DRAGGED, target); assertTrue(f.drag.isActive());
            f.panel.setEditingEnabled(false); assertFalse(f.drag.isActive());
            assertEquals(before, f.json()); assertEquals(List.of("n1", "n3"), f.selected());
            assertEquals(0, f.changes.get()); assertFalse(f.panel.canUndo()); assertNull(field(f.drag, "source"));
        }
    }); }

    @Test void deletingTargetsDoesNotRedirectIncomingJumpAndUndoRestoresEverything() throws Exception { edt(() -> {
        try (Fixture f = new Fixture()) {
            f.makeImage(0, true); f.select(1, 4); String before = f.json();
            assertTrue(f.panel.deleteNodes(f.panel.getSelectedNodes()));
            assertEquals(List.of("n0", "n2", "n3", "n5"), f.ids());
            assertEquals("n4", MacroFlowFactory.branchFor(f.node(0), BranchCondition.FOUND).getTargetId());
            assertTrue(f.selected().isEmpty()); assertEquals(1, f.changes.get());
            assertTrue(f.panel.undo()); assertEquals(before, f.json()); assertEquals(List.of("n1", "n4"), f.selected()); assertFalse(f.panel.canUndo());
        }
    }); }

    @Test void deleteAllKeepsHeadAndEndAndCanUndoOnce() throws Exception { edt(() -> {
        try (Fixture f = new Fixture()) {
            f.panel.getFlow().setHeadWaitMillis(789L); f.key("selectAllTimelineNodes");
            assertTrue(f.panel.deleteNodes(f.panel.getSelectedNodes())); assertTrue(f.ids().isEmpty());
            assertEquals("end", f.panel.getFlow().getStartId()); assertEquals(789L, f.panel.getFlow().getHeadWaitMillis());
            assertTrue(f.panel.undo()); assertEquals(6, f.selected().size());
        }
    }); }

    @Test void batchClipboardRemapsInternalJumpAndKeepsExternalTargetWithOneUndo() throws Exception { edt(() -> {
        try (Fixture f = new Fixture()) {
            f.makeImage(0, true); f.makeImage(2, true);
            MacroFlowFactory.branchFor(f.node(2), BranchCondition.FOUND).setTargetId("n5");
            f.select(0, 2, 4); String before = f.json();
            f.key("copyTimelineNode"); f.key("pasteTimelineNode");
            List<MacroNode> pasted = f.panel.getSelectedNodes(); assertEquals(3, pasted.size());
            assertEquals(List.of("n0", "n1", "n2", "n3", "n4", pasted.get(0).getId(), pasted.get(1).getId(), pasted.get(2).getId(), "n5"), f.ids());
            assertEquals(pasted.get(2).getId(), MacroFlowFactory.branchFor(pasted.get(0), BranchCondition.FOUND).getTargetId());
            assertEquals(pasted.get(0).getId(), MacroFlowFactory.branchFor(pasted.get(0), BranchCondition.NOT_FOUND).getTargetId());
            assertEquals("n5", MacroFlowFactory.branchFor(pasted.get(1), BranchCondition.FOUND).getTargetId());
            pasted.get(2).getAction().setX(999); assertEquals(12, f.node(4).getAction().getX());
            assertTrue(f.panel.undo()); assertEquals(before, f.json()); assertEquals(List.of("n0", "n2", "n4"), f.selected()); assertFalse(f.panel.canUndo());
        }
    }); }

    @Test void groupPasteWithMissingExternalTargetRejectsWholeGroupWithoutHistory() throws Exception { edt(() -> {
        try (Fixture f = new Fixture()) {
            f.makeImage(0, true); f.select(0, 1); f.key("copyTimelineNode");
            f.panel.setFlow(MacroFlowFactory.rebuildLinear(List.of(action("other", 0)), "end"));
            String before = f.json();
            assertThrows(IllegalArgumentException.class, () -> f.panel.pasteNode(null));
            assertEquals(before, f.json()); assertFalse(f.panel.canUndo()); assertEquals(0, f.changes.get());
        }
    }); }

    @Test void copiedGroupCanPasteAfterSourceDeletedAndReadOnlyCopyStillWorks() throws Exception { edt(() -> {
        try (Fixture f = new Fixture()) {
            f.makeImage(0, true); f.select(0, 4); f.panel.setEditingEnabled(false); f.key("copyTimelineNode");
            assertFalse(f.panel.pasteNode(null)); f.panel.setEditingEnabled(true);
            assertTrue(f.panel.deleteNodes(f.panel.getSelectedNodes()));
            assertTrue(f.panel.pasteNode(null)); assertEquals(6, f.ids().size());
            List<MacroNode> pasted = f.panel.getSelectedNodes(); assertEquals(2, pasted.size());
            assertEquals(pasted.get(1).getId(), MacroFlowFactory.branchFor(pasted.get(0), BranchCondition.FOUND).getTargetId());
        }
    }); }

    @Test void oldBulkMenuCannotActOnChangedSelectionAndLocksOnPlaybackBoundary() throws Exception { edt(() -> {
        try (Fixture f = new Fixture()) {
            f.select(1, 3); JPopupMenu menu = menu(f.panel, f.node(1));
            assertEquals("批量编辑", ((JMenuItem) menu.getComponent(0)).getText());
            assertEquals("复制所选", ((JMenuItem) menu.getComponent(2)).getText());
            f.click(2, 0);
            ((JMenuItem) menu.getComponent(0)).doClick(); ((JMenuItem) menu.getComponent(3)).doClick();
            assertEquals(0, f.changes.get()); assertFalse(f.panel.canUndo());
            f.select(1, 3); JPopupMenu previous = menu(f.panel, f.node(1));
            f.panel.setEditingEnabled(false); f.panel.setEditingEnabled(true);
            ((JMenuItem) previous.getComponent(0)).doClick(); ((JMenuItem) previous.getComponent(3)).doClick();
            f.panel.setEditingEnabled(false); JPopupMenu locked = menu(f.panel, f.node(1));
            assertFalse(((JMenuItem) locked.getComponent(0)).isEnabled()); assertFalse(((JMenuItem) locked.getComponent(3)).isEnabled());
            assertTrue(((JMenuItem) locked.getComponent(2)).isEnabled());
        }
    }); }

    @Test void batchDialogRequiresOptInAndValidatesOnlyCheckedFields() throws Exception { edt(() -> {
        TimelineBatchEditDialog dialog = new TimelineBatchEditDialog(null, 4, 2, 1, values -> {});
        try {
            assertThrows(IllegalArgumentException.class, dialog::readValues);
            JCheckBox enabled = (JCheckBox) field(dialog, "actionEnabled"); JTextField delay = (JTextField) field(dialog, "actionDelay");
            enabled.doClick(); delay.setText("0"); assertEquals(new TimelineBatchEditDialog.Values(0L, null), dialog.readValues());
            delay.setText(""); assertThrows(IllegalArgumentException.class, dialog::readValues);
            delay.setText("9223372036854775808"); assertThrows(IllegalArgumentException.class, dialog::readValues);
            delay.setText(Long.toString(Long.MAX_VALUE)); assertEquals(Long.MAX_VALUE, dialog.readValues().actionDelay());
            ((JCheckBox) field(dialog, "retryEnabled")).doClick(); ((JTextField) field(dialog, "retryDelay")).setText("234");
            assertEquals(234L, dialog.readValues().retryDelay());
            delay.setText("100"); ((JTextField) field(dialog, "retryDelay")).setText("1000");
            Path dir = directory(); TimelineJumpLayoutTest.layout(dialog);
            render(dialog.getRootPane(), dir.resolve("batch-edit-150.png"));
            System.out.println("批量编辑离屏预览：" + dir);
        } finally { dialog.dispose(); }
        TimelineBatchEditDialog noRetry = new TimelineBatchEditDialog(null, 2, 2, 0, values -> {});
        try { assertFalse(((JCheckBox) field(noRetry, "retryEnabled")).isEnabled()); } finally { noRetry.dispose(); }
    }); }

    private static final class Fixture implements AutoCloseable {
        final Clipboard clipboard = new Clipboard("multi-selection-test");
        final ActionTimelinePanel panel = new ActionTimelinePanel(clipboard);
        final AtomicInteger changes = new AtomicInteger(); final JPanel track; final TimelineNodeDrag drag;
        Fixture() throws Exception {
            List<MacroNode> nodes = new ArrayList<>(); for (int i = 0; i < 6; i++) nodes.add(action("n" + i, i * 10L));
            panel.setFlow(MacroFlowFactory.rebuildLinear(nodes, "end")); panel.setOnFlowChanged(changes::incrementAndGet);
            track = TimelineJumpLayoutTest.track(panel); drag = (TimelineNodeDrag) field(panel, "nodeDrag"); layout();
        }
        MacroNode node(int i) { return panel.getFlow().getNodeMap().get("n" + i); }
        void makeImage(int i, boolean retry) {
            List<MacroNode> nodes = new ArrayList<>(MacroFlowFactory.timelineNodes(panel.getFlow()));
            ImageCheckConfig config = new ImageCheckConfig("synthetic.png", .93); config.setScaleMatchingEnabled(true); config.setRetryDelay(321L);
            config.setSuccessBehavior(ImageCheckBehavior.JUMP); config.setFailureBehavior(retry ? ImageCheckBehavior.RETRY : ImageCheckBehavior.EXECUTE);
            nodes.set(nodes.indexOf(node(i)), new MacroNode("n" + i, NodeType.IMAGE_CHECK, null, config,
                    new ArrayList<>(List.of(new MacroBranch(BranchCondition.FOUND, "n4", 17)))));
            panel.setFlow(MacroFlowFactory.rebuildLinear(nodes, "end")); layout();
        }
        void select(int... indexes) { panel.clearSelection(); for (int i : indexes) click(i, InputEvent.CTRL_DOWN_MASK); }
        void click(int i, int modifiers) { press(i, modifiers); releaseCard(i, modifiers); }
        void press(int i, int modifiers) { JComponent card = panel.nodeCard(node(i)); card.dispatchEvent(mouse(card, MouseEvent.MOUSE_PRESSED, 78, 60, modifiers)); }
        void releaseCard(int i, int modifiers) { JComponent card = panel.nodeCard(node(i)); card.dispatchEvent(mouse(card, MouseEvent.MOUSE_RELEASED, 78, 60, modifiers)); }
        void key(String name) { panel.getActionMap().get(name).actionPerformed(new ActionEvent(panel, 0, name)); }
        List<String> selected() { return panel.getSelectedNodes().stream().map(MacroNode::getId).toList(); }
        List<String> ids() { return MacroFlowFactory.timelineNodes(panel.getFlow()).stream().map(MacroNode::getId).toList(); }
        List<Long> delays() { return panel.getActions().stream().map(MacroAction::getDelay).toList(); }
        String json() throws Exception { return new ObjectMapper().writeValueAsString(panel.getFlow()); }
        void layout() { panel.setSize(1800, 720); TimelineJumpLayoutTest.layout(panel); }
        void render(Path file) throws Exception { layout(); TimelineMultiSelectionTest.render(panel, file); }
        void dragEvent(int type, Point point) throws Exception {
            Method handle = TimelineNodeDrag.class.getDeclaredMethod("handleEvent", AWTEvent.class); handle.setAccessible(true);
            handle.invoke(drag, new MouseEvent(drag.layer(), type, 1, type == MouseEvent.MOUSE_DRAGGED ? InputEvent.BUTTON1_DOWN_MASK : 0,
                    point.x, point.y, 1, false, type == MouseEvent.MOUSE_DRAGGED ? MouseEvent.NOBUTTON : MouseEvent.BUTTON1));
        }
        @Override public void close() { drag.cancel(true); }
    }
    private static final class RecordingRobot extends Robot {
        final List<Integer> keys = new ArrayList<>();
        RecordingRobot() throws AWTException { super(); }
        @Override public void keyRelease(int code) { keys.add(code); }
        @Override public void keyPress(int code) { throw new AssertionError("不应按下真实按键"); }
        @Override public void mouseMove(int x, int y) { throw new AssertionError("不应移动鼠标"); }
        @Override public void mousePress(int mask) { throw new AssertionError("不应按下鼠标"); }
        @Override public void mouseRelease(int mask) { throw new AssertionError("不应释放鼠标"); }
    }
    private static MacroNode action(String id, long delay) { return new MacroNode(id, NodeType.ACTION, MacroAction.mouseMove(12, 34, delay), new ArrayList<>()); }
    private static MouseEvent mouse(Component c, int type, int x, int y, int modifiers) {
        return new MouseEvent(c, type, 1, modifiers | (type == MouseEvent.MOUSE_PRESSED ? InputEvent.BUTTON1_DOWN_MASK : 0), x, y, 1, false, MouseEvent.BUTTON1);
    }
    private static Object field(Object value, String name) throws Exception { Field f = value.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(value); }
    private static JPopupMenu menu(ActionTimelinePanel panel, MacroNode node) throws Exception {
        Method method = ActionTimelinePanel.class.getDeclaredMethod("createContextMenu", MacroNode.class); method.setAccessible(true); return (JPopupMenu) method.invoke(panel, node);
    }
    private static Container find(Container root, String name) {
        if (root.getClass().getSimpleName().equals(name)) return root;
        for (Component child : root.getComponents()) if (child instanceof Container c) { Container found = find(c, name); if (found != null) return found; }
        return null;
    }
    private static Path directory() throws Exception {
        Path root = Path.of(System.getProperty("macro.test.sandboxRoot", "target/test-sandboxes")).toAbsolutePath(); Files.createDirectories(root);
        return Files.createTempDirectory(root, "timeline-multi-selection-");
    }
    private static void render(JComponent component, Path file) throws Exception {
        BufferedImage image = new BufferedImage((int) (component.getWidth() * 1.5), (int) (component.getHeight() * 1.5), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(com.macro.ui.theme.ThemeManager.getPalette().background()); g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.scale(1.5, 1.5); component.printAll(g);
        } finally { g.dispose(); }
        ImageIO.write(image, "png", file.toFile());
    }
    private interface Checked { void run() throws Exception; }
    private static void edt(Checked body) throws Exception { SwingUtilities.invokeAndWait(() -> { try { body.run(); } catch (Exception ex) { throw new AssertionError(ex); } }); }
}
