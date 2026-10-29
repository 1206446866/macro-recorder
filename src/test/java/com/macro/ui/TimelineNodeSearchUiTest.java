package com.macro.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.flow.*;
import com.macro.model.MacroAction;
import com.macro.ui.theme.ThemeManager;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** 实际组件、局部快捷键及离屏绘制，不显示窗口、不触碰系统剪贴板或发送输入。 */
class TimelineNodeSearchUiTest {
    @Test void buttonOpensLiveSearchAndNavigationWrapsWithoutChangingSelectionOrHistory() throws Exception { edt(() -> {
        try (Fixture f = new Fixture(8)) {
            f.select(0, 4); String before = f.json(); List<MacroNode> selected = f.panel.getSelectedNodes();
            assertFalse(f.search.isVisible()); button(f.panel, "查找节点").doClick(); assertTrue(f.search.isVisible());
            f.query.setText("键盘"); assertEquals(List.of("n0", "n1"), f.search.matches()); assertEquals("n0", f.search.currentId());
            assertEquals("1 / 2", f.count.getText()); f.inputKey(KeyEvent.VK_ENTER, 0); assertEquals("n1", f.search.currentId());
            f.key("findNextTimelineNode"); assertEquals("n0", f.search.currentId());
            f.inputKey(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK); assertEquals("n1", f.search.currentId());
            f.key("findPreviousTimelineNode"); assertEquals("n0", f.search.currentId());
            assertEquals(selected, f.panel.getSelectedNodes()); assertEquals(before, f.json());
            assertEquals(0, f.changes.get()); assertFalse(f.panel.canUndo());
        }
    }); }

    @Test void noResultsAndEmptyQueriesShowStatusWithoutLeavingStaleHighlight() throws Exception { edt(() -> {
        try (Fixture f = new Fixture(5)) {
            f.open("#2"); assertEquals("n1", f.search.currentId());
            f.query.setText("不存在的节点"); assertTrue(f.search.matches().isEmpty()); assertNull(f.search.currentId());
            assertEquals("无匹配", f.count.getText()); assertFalse(button(f.search, "上一项").isEnabled()); assertFalse(button(f.search, "下一项").isEnabled());
            f.key("findNextTimelineNode"); assertNull(f.search.currentId());
            f.query.setText("　"); assertEquals("待输入", f.count.getText());
            f.panel.setFlow(MacroFlowFactory.fromActions(List.of())); f.open("1");
            assertEquals("无匹配", f.count.getText()); assertEquals(0, f.changes.get());
        }
    }); }

    @Test void readOnlyAndPlaybackProgressDoNotBlockFindingOrModifyProgress() throws Exception { edt(() -> {
        try (Fixture f = new Fixture(7)) {
            f.select(0, 4); f.panel.setEditingEnabled(false); f.panel.setEditPermission(() -> false);
            f.panel.applyPlaybackProgress(new PlaybackProgressBuffer.Batch(2, false, java.util.Set.of("n0"), "n1", false, false));
            f.open("图像"); assertEquals("n2", f.search.currentId());
            assertEquals(java.util.Set.of("n0"), f.panel.getCompletedNodeIds()); assertEquals("n1", f.panel.getCurrentPlaybackNodeId());
            assertEquals(List.of("n0", "n4"), f.selected()); assertEquals(0, f.changes.get()); assertFalse(f.panel.canUndo());
        }
    }); }

    @Test void escapeCloseAndReopenKeepQueryAndSelectionButNewMacroResetsSearch() throws Exception { edt(() -> {
        try (Fixture f = new Fixture(7)) {
            f.select(1, 4); f.open("键盘"); f.key("findNextTimelineNode");
            f.inputKey(KeyEvent.VK_ESCAPE, 0); assertFalse(f.search.isVisible()); assertNull(f.search.currentId());
            assertEquals(List.of("n1", "n4"), f.selected());
            f.key("findTimelineNode"); assertEquals("键盘", f.query.getText()); assertEquals("n1", f.search.currentId());
            f.key("clearTimelineSelection"); assertFalse(f.search.isVisible()); assertEquals(List.of("n1", "n4"), f.selected());
            f.key("findNextTimelineNode"); assertTrue(f.search.isVisible());
            f.panel.setActions(List.of(MacroAction.mouseMove(3, 4, 0))); assertFalse(f.search.isVisible()); assertEquals("", f.query.getText());
            assertFalse(f.panel.canUndo());
        }
    }); }

    @Test void insertMoveDeleteAndUndoRefreshMatchesUsingNewDisplayPositions() throws Exception { edt(() -> {
        try (Fixture f = new Fixture(8)) {
            f.open("图像"); String before = f.json();
            assertEquals(List.of("n2"), f.search.matches());
            assertTrue(f.panel.moveNode(f.node(2), 6)); assertEquals("n2", f.search.currentId());
            f.query.setText("#7"); assertEquals("n2", f.search.currentId());
            assertTrue(f.panel.undo()); assertEquals(before, f.json()); assertEquals("n6", f.search.currentId());
            f.query.setText("图像"); assertTrue(f.panel.deleteNodes(List.of(f.node(2)))); assertNull(f.search.currentId());
            assertTrue(f.panel.undo()); assertEquals("n2", f.search.currentId());
            MacroNode image = new MacroNode(null, NodeType.IMAGE_CHECK, null, new ImageCheckConfig("second.png", .9), new ArrayList<>());
            assertTrue(f.panel.insertActionBefore(f.node(2), image));
            assertEquals(List.of(image.getId(), "n2"), f.search.matches()); assertEquals("n2", f.search.currentId());
            assertTrue(f.panel.undo()); assertEquals(List.of("n2"), f.search.matches());
        }
    }); }

    @Test void editingTemplateNameRefreshesTextResultsWithoutChangingJumpIdentity() throws Exception { edt(() -> {
        try (Fixture f = new Fixture(8)) {
            MacroNode image = f.node(2); image.getImageCheckConfig().setSuccessBehavior(ImageCheckBehavior.JUMP);
            MacroFlowFactory.branchFor(image, BranchCondition.FOUND).setTargetId("n6");
            MacroFlowFactory.branchFor(image, BranchCondition.FOUND).setDelay(45);
            f.panel.refreshTimeline(); f.open("login"); assertEquals("n2", f.search.currentId());
            image.getImageCheckConfig().setImagePath("other.png"); f.panel.refreshTimeline(); assertNull(f.search.currentId());
            f.query.setText("other.png"); assertEquals("n2", f.search.currentId());
            assertEquals("n6", MacroFlowFactory.branchFor(image, BranchCondition.FOUND).getTargetId());
            assertFalse(f.panel.canUndo()); assertEquals(0, f.changes.get());
        }
    }); }

    @Test void searchFieldKeepsTextShortcutsAndPanelBindingsHaveLocalScope() throws Exception { edt(() -> {
        try (Fixture f = new Fixture(8)) {
            f.select(0, 4); f.open("键盘"); String before = f.json();
            KeyboardFocusManager old = KeyboardFocusManager.getCurrentKeyboardFocusManager();
            try {
                KeyboardFocusManager.setCurrentKeyboardFocusManager(new DefaultKeyboardFocusManager() {
                    @Override public Component getFocusOwner() { return f.query; }
                });
                for (String name : List.of("selectAllTimelineNodes", "copyTimelineNode", "pasteTimelineNode", "deleteSelectedTimelineAction", "undoTimelineEdit")) f.key(name);
                assertEquals(List.of("n0", "n4"), f.selected()); assertEquals(before, f.json()); assertFalse(f.panel.canPasteNode());
            } finally { KeyboardFocusManager.setCurrentKeyboardFocusManager(old); }
            for (KeyStroke stroke : List.of(KeyStroke.getKeyStroke(KeyEvent.VK_F, KeyEvent.CTRL_DOWN_MASK), KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0),
                    KeyStroke.getKeyStroke(KeyEvent.VK_F3, KeyEvent.SHIFT_DOWN_MASK))) {
                assertNotNull(f.panel.getInputMap(JComponent.WHEN_FOCUSED).get(stroke));
                assertEquals(f.panel.getInputMap(JComponent.WHEN_FOCUSED).get(stroke), f.panel.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(stroke));
                assertNull(f.panel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(stroke));
            }
        }
    }); }

    @Test void openingFindCancelsOldInlineEditorAndArmedDragWithoutWritingValues() throws Exception { edt(() -> {
        try (Fixture f = new Fixture(8)) {
            Container coordinate = find(f.panel.nodeCard(f.node(4)), "CoordinateEditor");
            var begin = coordinate.getClass().getDeclaredMethod("beginEdit"); begin.setAccessible(true); begin.invoke(coordinate);
            JTextField editor = (JTextField) coordinate.getComponent(0); editor.setText("9999");
            assertNotNull(field(f.panel, "activeEdit")); f.key("findTimelineNode");
            assertNull(field(f.panel, "activeEdit")); assertEquals(4, f.node(4).getAction().getX()); assertFalse(f.panel.canUndo());
            f.search.close(); JComponent card = f.panel.nodeCard(f.node(4));
            card.dispatchEvent(new MouseEvent(card, MouseEvent.MOUSE_PRESSED, 1, InputEvent.BUTTON1_DOWN_MASK, 70, 60, 1, false, MouseEvent.BUTTON1));
            assertNotNull(field(f.drag, "source")); f.key("findTimelineNode");
            assertNull(field(f.drag, "source")); assertEquals(0, f.changes.get());
        }
    }); }

    @Test void locatingOffscreenResultScrollsIntoViewWithoutChangingSelection() throws Exception {
        AtomicReference<Fixture> ref = new AtomicReference<>();
        edt(() -> { Fixture f = new Fixture(35); ref.set(f); f.select(0, 4); f.open("#30"); f.layout(1000); });
        edt(() -> {
            try (Fixture f = ref.get()) {
                assertEquals("n29", f.search.currentId());
                JViewport viewport = f.drag.layer().getView().getViewport();
                assertTrue(viewport.getViewPosition().x > 0);
                assertTrue(viewport.getViewRect().contains(f.panel.nodeCard(f.node(29)).getBounds()), "目标卡片完整进入视口");
                assertEquals(List.of("n0", "n4"), f.selected()); assertEquals(0, f.changes.get());
            }
        });
    }

    @Test void closingOrSwitchingBeforeQueuedScrollPreventsStaleNavigation() throws Exception {
        AtomicReference<Fixture> ref = new AtomicReference<>();
        edt(() -> { Fixture f = new Fixture(35); ref.set(f); f.open("#30"); f.inputKey(KeyEvent.VK_ESCAPE, 0); f.layout(1000); });
        edt(() -> {
            Fixture f = ref.get(); assertEquals(0, f.drag.layer().getView().getViewport().getViewPosition().x);
            f.open("#30"); f.panel.setFlow(MacroFlowFactory.rebuildLinear(List.of(action("replacement", 1)), "new-end")); f.layout(1000);
        });
        edt(() -> { try (Fixture f = ref.get()) { assertFalse(f.search.isVisible()); assertNull(f.search.currentId()); assertEquals(0, f.drag.layer().getView().getViewport().getViewPosition().x); } });
    }

    @Test void goldHighlightCoexistsWithMultiSelectionAndRendersAt150PercentWithoutClipping() throws Exception { edt(() -> {
        try (Fixture f = new Fixture(8)) {
            f.select(0, 4); f.open("#5"); f.layout(1000);
            assertTrue(goldPixels(f.panel.nodeCard(f.node(4))) > 100);
            Path dir = directory(); render(f.panel, dir.resolve("find-number-150.png"));
            f.query.setText("键盘"); render(f.panel, dir.resolve("find-text-150.png"));
            f.query.setText("不存在"); render(f.panel, dir.resolve("find-empty-150.png"));
            for (int width : new int[]{820, 1000}) {
                f.layout(width); assertTrue(f.query.getWidth() > 100);
                for (String label : List.of("上一项", "下一项", "关闭")) {
                    JButton button = button(f.search, label); assertTrue(button.getWidth() >= button.getPreferredSize().width);
                    assertTrue(button.getParent().getWidth() >= button.getX() + button.getWidth());
                }
            }
            f.search.close(); assertEquals(0, goldPixels(f.panel.nodeCard(f.node(4))));
            assertEquals(List.of("n0", "n4"), f.selected());
            System.out.println("节点查找离屏预览：" + dir);
        }
    }); }

    private static final class Fixture implements AutoCloseable {
        final ActionTimelinePanel panel = new ActionTimelinePanel(new Clipboard("node-find-test"));
        final TimelineNodeSearch search; final JTextField query; final JLabel count; final TimelineNodeDrag drag;
        final AtomicInteger changes = new AtomicInteger();
        Fixture(int total) throws Exception {
            List<MacroNode> nodes = new ArrayList<>(); for (int i = 0; i < total; i++) nodes.add(action("n" + i, i));
            if (total > 0) nodes.get(0).setAction(MacroAction.keyPress(65, "A", 0));
            if (total > 1) nodes.get(1).setAction(MacroAction.keyRelease(65, "A", 0));
            if (total > 2) nodes.set(2, new MacroNode("n2", NodeType.IMAGE_CHECK, null, new ImageCheckConfig("login_中文.png", .9), new ArrayList<>()));
            panel.setFlow(MacroFlowFactory.rebuildLinear(nodes, "end")); panel.setOnFlowChanged(changes::incrementAndGet);
            search = (TimelineNodeSearch) field(panel, "nodeSearch"); query = (JTextField) field(search, "query"); count = (JLabel) field(search, "count");
            drag = (TimelineNodeDrag) field(panel, "nodeDrag"); layout(1000);
        }
        MacroNode node(int i) { return panel.getFlow().getNodeMap().get("n" + i); }
        void open(String text) { key("findTimelineNode"); query.setText(text); }
        void key(String name) { panel.getActionMap().get(name).actionPerformed(new ActionEvent(panel, 0, name)); }
        void inputKey(int key, int modifiers) { Object name = query.getInputMap().get(KeyStroke.getKeyStroke(key, modifiers)); query.getActionMap().get(name).actionPerformed(new ActionEvent(query, 0, name.toString())); }
        void select(int... indexes) { for (int i : indexes) panel.selectWithModifiers(node(i), true, false, false); }
        List<String> selected() { return panel.getSelectedNodes().stream().map(MacroNode::getId).toList(); }
        String json() throws Exception { return new ObjectMapper().writeValueAsString(panel.getFlow()); }
        void layout(int width) { panel.setSize(width, 700); TimelineJumpLayoutTest.layout(panel); }
        @Override public void close() { search.close(); drag.cancel(true); }
    }
    private static MacroNode action(String id, int coordinate) { return new MacroNode(id, NodeType.ACTION, MacroAction.mouseMove(coordinate, 20, 50), new ArrayList<>()); }
    private static Object field(Object target, String name) throws Exception { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
    private static JButton button(Container root, String text) {
        for (Component c : root.getComponents()) { if (c instanceof JButton b && b.getText().equals(text)) return b;
            if (c instanceof Container child) { JButton b = button(child, text); if (b != null) return b; } }
        return null;
    }
    private static Container find(Container root, String name) {
        if (root.getClass().getSimpleName().equals(name)) return root;
        for (Component c : root.getComponents()) if (c instanceof Container child) { Container found = find(child, name); if (found != null) return found; }
        return null;
    }
    private static int goldPixels(JComponent card) {
        BufferedImage image = new BufferedImage(card.getWidth(), card.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics(); try { card.printAll(g); } finally { g.dispose(); }
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            if (x > 4 && x < image.getWidth() - 5 && y > 4 && y < image.getHeight() - 5) continue;
            Color color = new Color(image.getRGB(x, y)); if (color.getRed() > 200 && color.getGreen() > 140 && color.getBlue() < 130) count++;
        }
        return count;
    }
    private static Path directory() throws Exception { Path root = Path.of(System.getProperty("macro.test.sandboxRoot", "target/test-sandboxes")).toAbsolutePath(); Files.createDirectories(root); return Files.createTempDirectory(root, "timeline-node-find-"); }
    private static void render(JComponent component, Path path) throws Exception {
        TimelineJumpLayoutTest.layout(component);
        BufferedImage image = new BufferedImage(component.getWidth() * 3 / 2, component.getHeight() * 3 / 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics(); try { g.setColor(ThemeManager.getPalette().background()); g.fillRect(0, 0, image.getWidth(), image.getHeight()); g.scale(1.5, 1.5); component.printAll(g); } finally { g.dispose(); }
        ImageIO.write(image, "png", path.toFile());
    }
    private interface Checked { void run() throws Exception; }
    private static void edt(Checked body) throws Exception { SwingUtilities.invokeAndWait(() -> { try { body.run(); } catch (Exception ex) { throw new AssertionError(ex); } }); }
}
