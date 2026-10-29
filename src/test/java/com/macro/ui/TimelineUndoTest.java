package com.macro.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.flow.*;
import com.macro.model.MacroAction;
import com.macro.model.TargetWindow;
import com.macro.player.PlayerState;
import com.macro.storage.MacroStorage;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** 合成流程、组件事件与独立 MR；不显示窗口、截图或发送真实输入。 */
class TimelineUndoTest {
    @Test void deletingJumpTargetRestoresNodeDelayBranchesAndSavedMacro() throws Exception { edt(() -> {
        ActionTimelinePanel panel = panelWithImage();
        MacroNode target = node(panel, "c");
        invoke(panel, "selectMacroNode", new Class<?>[]{MacroNode.class}, target);
        String before = json(panel);
        assertTrue(panel.deleteAction(target, true));
        assertEquals(70, node(panel, "d").getAction().getDelay());
        assertFalse(panel.getFlow().getNodeMap().containsKey("c"));
        AtomicInteger changes = new AtomicInteger(); panel.setOnFlowChanged(changes::incrementAndGet);
        assertTrue(panel.undo());
        assertEquals(before, json(panel));
        assertEquals("c", panel.getSelectedMacroNode().getId());
        assertEquals("c", MacroFlowFactory.branchFor(node(panel, "image"), BranchCondition.FOUND).getTargetId());
        assertEquals(1, changes.get()); assertFalse(panel.canUndo());
        Path root = Files.createDirectories(Path.of(System.getProperty("macro.test.sandboxRoot", "target/test-sandboxes")));
        Path file = Files.createTempDirectory(root, "timeline-undo-").resolve("restored.mr");
        MacroStorage storage = new MacroStorage();
        storage.save(panel.getActions(), null, panel.getFlow(), file.toString());
        assertEquals(panel.getFlow(), storage.load(file.toString()).getFlow());
    }); }

    @Test void movingTargetBackRestoresOriginalNumbersDefaultEdgesAndEndIdentity() throws Exception { edt(() -> {
        ActionTimelinePanel panel = panelWithImage(); String before = json(panel);
        assertTrue(panel.moveNode(node(panel, "c"), 0));
        assertEquals("c", panel.getFlow().getStartId());
        assertTrue(panel.undo()); assertEquals(before, json(panel));
        assertEquals("03", TimelineNodeNumbers.labels(MacroFlowFactory.timelineNodes(panel.getFlow())).get("c"));
        panel.setSize(1100, 700); TimelineJumpLayoutTest.layout(panel);
        var track = TimelineJumpLayoutTest.track(panel);
        TimelineJumpLayoutTest.assertTargets(track, TimelineJumpLayoutTest.routes(track));
    }); }

    @Test void insertReplaceAndNewEditsAfterUndoUseIndependentSteps() throws Exception { edt(() -> {
        ActionTimelinePanel panel = plain(); String initial = json(panel);
        MacroNode inserted = action("new", 5);
        assertTrue(panel.insertActionAfter(node(panel, "a"), inserted)); String afterInsert = json(panel);
        assertTrue(panel.replaceAction(node(panel, "b"), action("replacement", 999)));
        assertTrue(panel.undo()); assertEquals(afterInsert, json(panel));
        assertTrue(panel.undo()); assertEquals(initial, json(panel)); assertFalse(panel.undo());
        assertTrue(panel.deleteAction(node(panel, "a"), false));
        assertTrue(panel.undo()); assertEquals(initial, json(panel)); assertFalse(panel.canUndo());
    }); }

    @Test void imageConfigurationSnapshotDoesNotShareMutableValues() throws Exception { edt(() -> {
        ActionTimelinePanel panel = panelWithImage();
        var config = node(panel, "image").getImageCheckConfig();
        config.setScaleMatchingEnabled(true); config.setClickMode(ImageClickMode.RANDOM);
        config.setFailureBehavior(ImageCheckBehavior.CLICK_TERMINATE);
        config.setFailureClickX(-123); config.setFailureClickY(0);
        config.setSearchScope(ImageSearchScope.WINDOW);
        config.setSearchWindow(TargetWindow.create().setProcessName("fixture.exe").setWindowClass("Fixture").setWindowTitle("测试窗口"));
        panel.setFlow(MacroFlowFactory.rebuildLinear(MacroFlowFactory.timelineNodes(panel.getFlow()), "end", 567L));
        String before = json(panel);
        MacroNode replacement = new MacroNode("unused", NodeType.IMAGE_CHECK, null,
                new ImageCheckConfig("other.png", .5), new ArrayList<>());
        assertTrue(panel.replaceAction(node(panel, "image"), replacement));
        config.setThreshold(.1); config.setFailureClickX(999);
        assertTrue(panel.undo()); assertEquals(before, json(panel));
        assertNotSame(config, node(panel, "image").getImageCheckConfig());
    }); }

    @Test void coordinateAndActionDelayEachUndoOnceWhileInvalidOrCancelledTextDoesNotRecord() throws Exception { edt(() -> {
        ActionTimelinePanel panel = plain();
        Container coordinate = coordinate(panel);
        invoke(coordinate, "beginEdit", new Class<?>[0]);
        JTextField input = text(coordinate); input.setText("-123"); input.postActionEvent();
        assertEquals(-123, node(panel, "a").getAction().getX());
        editDelay(panel, node(panel, "a").getAction(), "99");
        assertTrue(panel.undo()); assertEquals(10, node(panel, "a").getAction().getDelay());
        assertEquals(-123, node(panel, "a").getAction().getX());
        assertTrue(panel.undo()); assertEquals(12, node(panel, "a").getAction().getX());
        editDelay(panel, node(panel, "a").getAction(), "10"); assertFalse(panel.canUndo());
        coordinate = coordinate(panel); invoke(coordinate, "beginEdit", new Class<?>[0]);
        input = text(coordinate); input.setText(""); blur(input); assertFalse(panel.canUndo());
        invoke(panel, "cancelInlineEdit", new Class<?>[0]); assertFalse(panel.canUndo());
        editDelay(panel, node(panel, "a").getAction(), "0"); assertTrue(panel.canUndo());
    }); }

    @Test void retryAndHeadDelayRestoreTheirActualPlaybackValues() throws Exception { edt(() -> {
        ActionTimelinePanel panel = panelWithImage(); String before = json(panel);
        invoke(panel, "beginRetryDelayEdit", new Class<?>[]{MacroNode.class}, node(panel, "image"));
        JTextField input = text(TimelineJumpLayoutTest.track(panel)); input.setText("0"); input.postActionEvent();
        assertEquals(0L, node(panel, "image").getImageCheckConfig().getRetryDelay());
        assertTrue(panel.undo()); assertEquals(before, json(panel));
        panel.setStandbyHeadEnabled(true); panel.clearUndoHistory();
        JLabel delay = descendants(panel).stream().filter(JLabel.class::isInstance).map(JLabel.class::cast)
                .filter(c -> "standbyHeadDelay".equals(c.getName())).findFirst().orElseThrow();
        delay.dispatchEvent(new MouseEvent(delay, MouseEvent.MOUSE_CLICKED, 1, 0, 2, 2, 1, false, MouseEvent.BUTTON1));
        input = text(TimelineJumpLayoutTest.track(panel)); input.setText("0"); input.postActionEvent();
        assertEquals(0L, panel.getFlow().getHeadWaitMillis());
        assertTrue(panel.undo()); assertEquals(MacroFlow.DEFAULT_HEAD_WAIT_MILLIS, panel.getFlow().getHeadWaitMillis());
        assertFalse(panel.canUndo());
    }); }

    @Test void undoHeadToggleKeepsHistoryIfPreferenceUpdateIsRejected() throws Exception { edt(() -> {
        ActionTimelinePanel panel = plain(); assertTrue(panel.setStandbyHeadEnabled(true));
        panel.setOnStandbyHeadToggle(enabled -> false);
        assertFalse(panel.undo()); assertNotNull(panel.getFlow().getHeadWaitMillis()); assertTrue(panel.canUndo());
        AtomicReference<Boolean> persisted = new AtomicReference<>();
        panel.setOnStandbyHeadToggle(enabled -> { persisted.set(enabled); return true; });
        assertTrue(panel.undo()); assertEquals(false, persisted.get()); assertNull(panel.getFlow().getHeadWaitMillis());
    }); }

    @Test void playingPausedAndStoppingBlockUndoWithoutConsumingHistory() throws Exception { edt(() -> {
        ActionTimelinePanel panel = plain(); panel.deleteAction(node(panel, "a"), false);
        String edited = json(panel); AtomicReference<PlayerState> state = new AtomicReference<>(PlayerState.IDLE);
        panel.setEditPermission(() -> state.get() == PlayerState.IDLE);
        for (PlayerState busy : List.of(PlayerState.PLAYING, PlayerState.PAUSED, PlayerState.STOPPING)) {
            state.set(busy); assertFalse(panel.canUndo()); assertFalse(panel.undo()); assertEquals(edited, json(panel));
        }
        state.set(PlayerState.IDLE); panel.setEditingEnabled(false); assertFalse(panel.undo());
        panel.setEditingEnabled(true); assertTrue(panel.undo()); assertEquals(3, panel.getActions().size());
    }); }

    @Test void staleDialogAndInlineCallbacksCannotWriteAfterUndo() throws Exception { edt(() -> {
        ActionTimelinePanel panel = plain();
        panel.moveNode(node(panel, "a"), 2);
        AtomicInteger stale = new AtomicInteger(); Consumer<Boolean> callback = panel.guardEditCallback(ignored -> stale.incrementAndGet());
        Container coordinate = coordinate(panel); invoke(coordinate, "beginEdit", new Class<?>[0]);
        JTextField input = text(coordinate); input.setText("987");
        assertTrue(panel.undo()); String restored = json(panel);
        input.postActionEvent(); blur(input); callback.accept(true);
        assertEquals(0, stale.get()); assertEquals(restored, json(panel));
    }); }

    @Test void noOpAndRejectedMutationsNeverCreateUndoSteps() throws Exception { edt(() -> {
        ActionTimelinePanel panel = plain();
        assertFalse(panel.moveNode(node(panel, "a"), 0)); assertFalse(panel.deleteAction(-1, false));
        assertFalse(panel.insertActionBefore(action("stale", 1), action("new", 1)));
        assertFalse(panel.replaceAction(action("stale", 1), action("new", 1)));
        panel.refreshTimeline(); assertFalse(panel.canUndo());
        assertFalse(panel.undo());
    }); }

    @Test void loadingAnotherFlowClearsHistoryButRefreshingSameFlowDoesNot() throws Exception { edt(() -> {
        ActionTimelinePanel panel = plain(); panel.deleteAction(0, false);
        panel.setFlow(panel.getFlow()); panel.refreshTimeline(); assertTrue(panel.canUndo());
        panel.setFlow(MacroFlowFactory.fromActions(List.of())); assertFalse(panel.canUndo()); assertFalse(panel.undo());
        assertTrue(panel.insertAction(null, action("fresh", 1), null)); assertTrue(panel.canUndo());
        panel.clearUndoHistory(); assertFalse(panel.undo());
    }); }

    @Test void ctrlZBindingLeavesTextFieldsAloneAndDoesNotAddCtrlY() throws Exception { edt(() -> {
        ActionTimelinePanel panel = plain(); panel.deleteAction(0, false);
        var key = KeyStroke.getKeyStroke(KeyEvent.VK_Z, KeyEvent.CTRL_DOWN_MASK);
        Object binding = panel.getInputMap(JComponent.WHEN_FOCUSED).get(key);
        assertEquals(binding, panel.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(key));
        var undo = panel.getActionMap().get(binding);
        KeyboardFocusManager previous = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        AtomicReference<Component> focus = new AtomicReference<>(new JTextField());
        try {
            KeyboardFocusManager.setCurrentKeyboardFocusManager(new DefaultKeyboardFocusManager() {
                @Override public Component getFocusOwner() { return focus.get(); }
            });
            undo.actionPerformed(new ActionEvent(panel, 0, "Ctrl+Z")); assertEquals(2, panel.getActions().size());
            focus.set(panel); undo.actionPerformed(new ActionEvent(panel, 0, "Ctrl+Z")); assertEquals(3, panel.getActions().size());
        } finally { KeyboardFocusManager.setCurrentKeyboardFocusManager(previous); }
        assertNull(panel.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(KeyEvent.VK_Y, KeyEvent.CTRL_DOWN_MASK)));
    }); }

    @Test void emptyTimelineContextMenuCanUndoDeletionAndOldMenuBecomesInvalid() throws Exception { edt(() -> {
        ActionTimelinePanel panel = new ActionTimelinePanel(new java.awt.datatransfer.Clipboard("undo-test")); panel.setActions(List.of(MacroAction.mouseMove(1, 2, 0)));
        panel.deleteAction(0, false);
        JPopupMenu menu = (JPopupMenu) invoke(panel, "createContextMenu", new Class<?>[]{MacroNode.class}, (Object) null);
        JMenuItem undo = (JMenuItem) menu.getComponent(menu.getComponentCount() - 1);
        assertEquals("撤销", undo.getText()); assertTrue(undo.isEnabled()); undo.doClick(); assertEquals(1, panel.getActions().size());
        panel.deleteAction(0, false);
        for (var listener : undo.getActionListeners()) listener.actionPerformed(new ActionEvent(undo, 0, "stale"));
        assertEquals(0, panel.getActions().size()); assertTrue(panel.canUndo());
    }); }

    private static ActionTimelinePanel plain() {
        ActionTimelinePanel panel = new ActionTimelinePanel();
        panel.setFlow(MacroFlowFactory.rebuildLinear(List.of(action("a", 10), action("b", 20), action("c", 30)), "end"));
        return panel;
    }
    private static ActionTimelinePanel panelWithImage() {
        ActionTimelinePanel panel = new ActionTimelinePanel();
        ImageCheckConfig config = new ImageCheckConfig("target/undo-synthetic-template.png", .91);
        config.setSuccessBehavior(ImageCheckBehavior.JUMP); config.setRetryDelay(250L);
        MacroNode image = new MacroNode("image", NodeType.IMAGE_CHECK, null, config,
                new ArrayList<>(List.of(new MacroBranch(BranchCondition.FOUND, "c", 17))));
        panel.setFlow(MacroFlowFactory.rebuildLinear(List.of(action("a", 10), image, action("c", 30), action("d", 40)), "end"));
        return panel;
    }
    private static MacroNode action(String id, long delay) {
        return new MacroNode(id, NodeType.ACTION, MacroAction.mouseMove(12, 34, delay), new ArrayList<>());
    }
    private static MacroNode node(ActionTimelinePanel panel, String id) { return panel.getFlow().getNodeMap().get(id); }
    private static String json(ActionTimelinePanel panel) throws Exception { return new ObjectMapper().writeValueAsString(panel.getFlow()); }
    private static Container coordinate(Container parent) {
        return descendants(parent).stream().filter(c -> c.getClass().getSimpleName().equals("CoordinateEditor"))
                .map(Container.class::cast).findFirst().orElseThrow();
    }
    private static JTextField text(Container parent) {
        return descendants(parent).stream().filter(JTextField.class::isInstance).map(JTextField.class::cast).findFirst().orElseThrow();
    }
    private static List<Component> descendants(Container parent) {
        List<Component> all = new ArrayList<>();
        for (Component c : parent.getComponents()) { all.add(c); if (c instanceof Container nested) all.addAll(descendants(nested)); }
        return all;
    }
    private static void editDelay(ActionTimelinePanel panel, MacroAction action, String value) throws Exception {
        JPanel connector = new JPanel(); JLabel label = new JLabel("delay"); JPanel arrow = new JPanel();
        connector.add(label); connector.add(arrow);
        invoke(panel, "beginDelayEdit", new Class<?>[]{JPanel.class, JLabel.class, JComponent.class, MacroAction.class}, connector, label, arrow, action);
        JTextField input = text(connector); input.setText(value); input.postActionEvent();
    }
    private static void blur(JTextField input) { for (var listener : input.getFocusListeners()) listener.focusLost(new FocusEvent(input, FocusEvent.FOCUS_LOST)); }
    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = target.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, args);
    }
    private static void edt(Checked task) throws Exception {
        SwingUtilities.invokeAndWait(() -> { try { task.run(); } catch (Exception e) { throw new AssertionError(e); } });
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
