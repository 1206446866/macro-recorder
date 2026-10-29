package com.macro.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.flow.*;
import com.macro.model.MacroAction;
import com.macro.player.MacroPlayer;
import com.macro.player.PlayerState;
import com.macro.storage.MacroStorage;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 组件事件、MR 往返和离屏渲染检查，不发送真实键鼠输入。 */
class TimelineNodeDragTest {
    @Test void reorderedFlowPlaybackJumpsToOriginalTargetAndSkipsItsOldPosition() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        AtomicReference<MacroFlow> reordered = new AtomicReference<>();
        AtomicReference<MacroFlow> restored = new AtomicReference<>();
        edt(() -> {
            try (Fixture f = new Fixture(7)) {
                for (int i = 0; i < 7; i++) f.nodes.get(i).setAction(MacroAction.keyRelease(65 + i, "test", 0));
                f.image(2, ImageCheckBehavior.JUMP, ImageCheckBehavior.TERMINATE, "n4", null);
                f.reload();
                assertTrue(f.panel.moveNode(f.nodes.get(4), 6));
                reordered.set(f.panel.getFlow());
                assertTrue(f.panel.undo());
                restored.set(f.panel.getFlow());
            }
        });
        MacroPlayer player = new MacroPlayer();
        RecordingRobot robot = new RecordingRobot();
        Field input = MacroPlayer.class.getDeclaredField("robot");
        input.setAccessible(true); input.set(player, robot);
        player.play(reordered.get(), 1, (node, context) -> ImageMatchResult.found(new Rectangle(1, 2, 3, 4)));
        assertEquals(List.of(65, 66, 69), robot.keys);
        assertEquals(PlayerState.IDLE, player.getState());
        robot.keys.clear();
        player.play(restored.get(), 1, (node, context) -> ImageMatchResult.found(new Rectangle(1, 2, 3, 4)));
        assertEquals(List.of(65, 66, 69, 70, 71), robot.keys, "撤销后恢复原始顺序与跳转后的默认后继");
        assertEquals(PlayerState.IDLE, player.getState());
    }

    @Test void movingTargetFromFiveToSevenKeepsAllIncomingIdsAndRenumbersLines() throws Exception {
        edt(() -> {
            try (Fixture f = new Fixture(7)) {
                MacroNode image = f.image(2, ImageCheckBehavior.JUMP, ImageCheckBehavior.JUMP, "n4", "n4");
                MacroNode other = f.image(0, ImageCheckBehavior.JUMP, ImageCheckBehavior.EXECUTE, "n4", null);
                f.reload();
                MacroNode target = f.nodes.get(4);
                assertTrue(f.panel.moveNode(target, 6));
                assertEquals(List.of("n0", "n1", "n2", "n3", "n5", "n6", "n4"), f.ids());
                for (MacroNode node : List.of(image, other)) assertEquals("n4", MacroFlowFactory.branchFor(node, BranchCondition.FOUND).getTargetId());
                assertEquals("n4", MacroFlowFactory.branchFor(image, BranchCondition.NOT_FOUND).getTargetId());
                f.layout();
                var routes = TimelineJumpLayoutTest.routes(f.track);
                assertEquals(3, routes.size());
                assertTrue(routes.stream().allMatch(route -> route.edge().targetId().equals("n4") && route.edge().label().endsWith("07")));
                TimelineJumpLayoutTest.assertTargets(f.track, routes);
                assertSame(target, f.panel.getSelectedMacroNode());
                assertEquals(1, f.changes.get());
            }
        });
    }

    @Test void movingImagePreservesJumpRetryClickParametersAndDefaultSuccessor() throws Exception {
        edt(() -> {
            try (Fixture f = new Fixture(5)) {
                MacroNode image = f.image(1, ImageCheckBehavior.JUMP, ImageCheckBehavior.RETRY, "n4", null);
                image.getImageCheckConfig().setRetryDelay(321L);
                image.getImageCheckConfig().setScaleMatchingEnabled(true);
                image.getImageCheckConfig().setClickMode(ImageClickMode.RANDOM);
                f.reload();
                assertTrue(f.panel.moveNode(image, 3));
                assertEquals("n4", MacroFlowFactory.branchFor(image, BranchCondition.FOUND).getTargetId());
                assertEquals(image.getId(), MacroFlowFactory.branchFor(image, BranchCondition.NOT_FOUND).getTargetId());
                assertEquals(321L, MacroFlowFactory.branchFor(image, BranchCondition.NOT_FOUND).getDelay());
                assertTrue(image.getImageCheckConfig().isScaleMatchingEnabled());
                assertEquals(ImageClickMode.RANDOM, image.getImageCheckConfig().getClickMode());
                image.getImageCheckConfig().setSuccessBehavior(ImageCheckBehavior.CLICK_EXECUTE);
                image.getImageCheckConfig().setFailureBehavior(ImageCheckBehavior.CLICK_TERMINATE);
                assertTrue(f.panel.moveNode(image, 0));
                assertEquals("n0", MacroFlowFactory.branchFor(image, BranchCondition.FOUND).getTargetId());
                assertEquals("end", MacroFlowFactory.branchFor(image, BranchCondition.NOT_FOUND).getTargetId());
                assertEquals("n4", MacroFlowFactory.branchFor(f.nodes.get(3), BranchCondition.ALWAYS).getTargetId());
            }
        });
    }

    @Test void headEndDelaysAndIdentitySurviveFirstAndLastMovesAndMrRoundTrip() throws Exception {
        edt(() -> {
            try (Fixture f = new Fixture(7)) {
                f.image(2, ImageCheckBehavior.JUMP, ImageCheckBehavior.EXECUTE, "n4", null);
                f.reload();
                f.panel.getFlow().setHeadWaitMillis(765L);
                f.panel.refreshTimeline();
                MacroNode target = f.nodes.get(4);
                assertTrue(f.panel.moveNode(target, 6));
                assertTrue(f.panel.moveNode(f.nodes.get(6), 0));
                assertSame(f.nodes.get(6), MacroFlowFactory.timelineNodes(f.panel.getFlow()).get(0));
                assertEquals("n6", f.panel.getFlow().getStartId());
                assertEquals(765L, f.panel.getFlow().getHeadWaitMillis());
                assertEquals(40L, target.getAction().getDelay());
                assertEquals("end", MacroFlowFactory.branchFor(target, BranchCondition.ALWAYS).getTargetId());
                Path path = directory().resolve("moved.mr");
                MacroStorage storage = new MacroStorage();
                storage.save(f.panel.getActions(), null, f.panel.getFlow(), path.toString());
                MacroFlow loaded = storage.load(path.toString()).getFlow();
                assertEquals(f.panel.getFlow(), loaded);
                assertEquals("n4", MacroFlowFactory.branchFor(loaded.getNodeMap().get("n2"), BranchCondition.FOUND).getTargetId());
                assertEquals("07", TimelineNodeNumbers.labels(MacroFlowFactory.timelineNodes(loaded)).get("n4"));
            }
        });
    }

    @Test void noOpInvalidAndReadOnlyMovesNeverNotifyOrMutate() throws Exception {
        edt(() -> {
            try (Fixture f = new Fixture(3)) {
                String original = f.json();
                assertFalse(f.panel.moveNode(f.nodes.get(0), 0));
                assertFalse(f.panel.moveNode(f.nodes.get(0), -1));
                assertFalse(f.panel.moveNode(f.nodes.get(0), 3));
                assertFalse(f.panel.moveNode(action("stale", 0), 1));
                assertFalse(f.panel.moveNode(null, 1));
                f.panel.setEditingEnabled(false);
                assertFalse(f.panel.moveNode(f.nodes.get(0), 2));
                f.panel.setEditingEnabled(true);
                f.panel.setEditPermission(() -> false);
                assertFalse(f.panel.moveNode(f.nodes.get(0), 2));
                assertEquals(original, f.json());
                assertEquals(0, f.changes.get());
            }
        });
    }

    @Test void dragPreviewsWithoutChangingFlowThenCommitsOnceOnRelease() throws Exception {
        edt(() -> {
            try (Fixture f = new Fixture(4)) {
                MacroFlow original = f.panel.getFlow();
                String json = f.json();
                MacroNode source = f.nodes.get(0);
                f.press(source);
                Point destination = f.point(f.nodes.get(2));
                f.dragTo(destination);
                assertTrue(f.drag.isDragging(source));
                assertEquals(2, f.drag.targetIndex());
                assertEquals(List.of("n1", "n2", "n0", "n3"), f.viewIds());
                assertSame(original, f.panel.getFlow());
                assertEquals(json, f.json());
                assertEquals(0, f.changes.get());
                f.release(destination);
                assertFalse(f.drag.isDragging(source));
                assertEquals(List.of("n1", "n2", "n0", "n3"), f.ids());
                assertEquals(f.ids(), f.viewIds());
                assertEquals(1, f.changes.get());
                assertTrue(f.drag.suppressesClick());
                assertClean(f.drag);
            }
        });
    }

    @Test void smallMotionIsSelectionAndDoubleClickStillOpensEditor() throws Exception {
        edt(() -> {
            try (Fixture f = new Fixture(3)) {
                AtomicInteger edits = new AtomicInteger();
                f.panel.setOnActionDoubleClicked(node -> edits.incrementAndGet());
                f.press(f.nodes.get(0));
                Point at = f.point(f.nodes.get(0));
                at.translate(3, 2);
                f.dragTo(at);
                f.release(at);
                assertSame(f.nodes.get(0), f.panel.getSelectedMacroNode());
                assertEquals(0, f.changes.get());
                assertFalse(f.drag.suppressesClick());
                JComponent card = f.card(f.nodes.get(0));
                card.dispatchEvent(new MouseEvent(card, MouseEvent.MOUSE_CLICKED, 10, 0, 50, 50, 2, false, MouseEvent.BUTTON1));
                assertEquals(1, edits.get());
                assertClean(f.drag);
            }
        });
    }

    @Test void actualComponentDispatchContinuesAfterPreviewReparentsThePressedCard() throws Exception {
        edt(() -> {
            try (Fixture f = new Fixture(4)) {
                AtomicInteger edits = new AtomicInteger();
                f.panel.setOnActionDoubleClicked(node -> edits.incrementAndGet());
                JComponent pressed = f.card(f.nodes.get(0));
                f.press(f.nodes.get(0));
                Point destination = f.point(f.nodes.get(2));
                Point local = SwingUtilities.convertPoint(f.drag.layer(), destination, pressed);
                pressed.dispatchEvent(new MouseEvent(pressed, MouseEvent.MOUSE_DRAGGED, 2,
                        MouseEvent.BUTTON1_DOWN_MASK, local.x, local.y, 0, false, MouseEvent.NOBUTTON));
                assertTrue(f.drag.isDragging(f.nodes.get(0)));
                assertEquals(2, f.drag.targetIndex());
                assertSame(f.track, pressed.getParent());
                local = SwingUtilities.convertPoint(f.drag.layer(), destination, pressed);
                pressed.dispatchEvent(new MouseEvent(pressed, MouseEvent.MOUSE_RELEASED, 3,
                        0, local.x, local.y, 1, false, MouseEvent.BUTTON1));
                assertEquals(List.of("n1", "n2", "n0", "n3"), f.ids());
                pressed.dispatchEvent(new MouseEvent(pressed, MouseEvent.MOUSE_CLICKED, 4,
                        0, local.x, local.y, 2, false, MouseEvent.BUTTON1));
                assertEquals(0, edits.get(), "完成拖动后产生的点击不能打开旧卡片编辑器");
                assertEquals(1, f.changes.get());
                assertClean(f.drag);
                f.press(f.nodes.get(0));
                f.release(f.point(f.nodes.get(0)));
                JComponent current = f.card(f.nodes.get(0));
                current.dispatchEvent(new MouseEvent(current, MouseEvent.MOUSE_CLICKED, 5,
                        0, 50, 50, 2, false, MouseEvent.BUTTON1));
                assertEquals(1, edits.get(), "下一次独立点击应恢复正常编辑");
            }
        });
    }

    @Test void draggingFromChildLabelWorksButCoordinateEditingDoesNotArmDrag() throws Exception {
        edt(() -> {
            try (Fixture f = new Fixture(3)) {
                JComponent card = f.card(f.nodes.get(0));
                JLabel title = Arrays.stream(card.getComponents()).filter(c -> c instanceof JLabel).map(c -> (JLabel) c).findFirst().orElseThrow();
                title.dispatchEvent(new MouseEvent(title, MouseEvent.MOUSE_PRESSED, 1, MouseEvent.BUTTON1_DOWN_MASK, 5, 5, 1, false, MouseEvent.BUTTON1));
                assertNotNull(field(f.drag, "source"));
                f.drag.cancel(true);
                Container coordinate = find(card, "CoordinateEditor");
                assertNotNull(coordinate);
                coordinate.dispatchEvent(new MouseEvent(coordinate, MouseEvent.MOUSE_PRESSED, 1, MouseEvent.BUTTON1_DOWN_MASK, 5, 5, 1, false, MouseEvent.BUTTON1));
                assertNull(field(f.drag, "source"));
                coordinate.dispatchEvent(new MouseEvent(coordinate, MouseEvent.MOUSE_CLICKED, 1, 0, 5, 5, 1, false, MouseEvent.BUTTON1));
                assertNotNull(find(coordinate, "JTextField"));
                assertEquals(0, f.changes.get());
            }
        });
    }

    @Test void escapeAndOutsideReleaseRestoreOriginalLayoutAndRemoveListeners() throws Exception {
        edt(() -> {
            for (boolean escape : new boolean[]{true, false}) try (Fixture f = new Fixture(4)) {
                List<String> original = f.ids();
                f.press(f.nodes.get(0));
                f.dragTo(f.point(f.nodes.get(2)));
                if (escape) {
                    KeyEvent event = new KeyEvent(f.panel, KeyEvent.KEY_PRESSED, 1, 0, KeyEvent.VK_ESCAPE, (char) 27);
                    f.event(event);
                    assertTrue(event.isConsumed());
                } else f.release(new Point(5, -25));
                assertEquals(original, f.ids());
                assertEquals(original, f.viewIds());
                assertEquals(0, f.changes.get());
                assertClean(f.drag);
            }
        });
    }

    @Test void dragCancelsOnPlaybackLockPermissionLossRebuildReplacementAndRemoval() throws Exception {
        edt(() -> {
            for (int kind = 0; kind < 6; kind++) try (Fixture f = new Fixture(4)) {
                String original = f.json();
                f.press(f.nodes.get(0));
                f.dragTo(f.point(f.nodes.get(2)));
                switch (kind) {
                    case 0 -> f.panel.setEditingEnabled(false);
                    case 1 -> f.panel.setEditPermission(() -> false);
                    case 2 -> f.panel.refreshTimeline();
                    case 3 -> f.panel.setFlow(MacroFlowFactory.fromActions(List.of(MacroAction.mouseMove(0, 0, 0))));
                    case 4 -> f.panel.removeNotify();
                    case 5 -> {
                        AtomicReference<Boolean> allowed = new AtomicReference<>(true);
                        f.panel.setEditPermission(allowed::get);
                        allowed.set(false); // 覆盖状态变化通知尚未到达的边界。
                        f.release(f.point(f.nodes.get(3)));
                    }
                }
                if (kind != 3) { assertEquals(original, f.json()); assertEquals(f.ids(), f.viewIds()); }
                else assertEquals(1, f.ids().size());
                assertEquals(0, f.changes.get());
                assertClean(f.drag);
            }
        });
    }

    @Test void losingWindowActivationCancelsWithoutShowingOrActivatingATestWindow() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        edt(() -> {
            try (Fixture f = new Fixture(4)) {
                JFrame host = new JFrame();
                try {
                    host.setContentPane(f.panel);
                    f.layout();
                    f.press(f.nodes.get(0));
                    f.dragTo(f.point(f.nodes.get(2)));
                    f.event(new WindowEvent(host, WindowEvent.WINDOW_DEACTIVATED));
                    assertEquals(List.of("n0", "n1", "n2", "n3"), f.ids());
                    assertEquals(f.ids(), f.viewIds());
                    assertEquals(0, f.changes.get());
                    assertFalse(host.isVisible());
                    assertClean(f.drag);
                } finally { host.dispose(); }
            }
        });
    }

    @Test void draggingOverSameSlotDoesNotOscillateOrCommitAndHeadStaysFixed() throws Exception {
        edt(() -> {
            try (Fixture f = new Fixture(4)) {
                f.panel.setStandbyHeadEnabled(true);
                f.changes.set(0);
                f.layout();
                Component head = f.track.getComponent(0);
                assertEquals("standbyHead", head.getName());
                head.dispatchEvent(new MouseEvent(head, MouseEvent.MOUSE_PRESSED, 1,
                        MouseEvent.BUTTON1_DOWN_MASK, 40, 40, 1, false, MouseEvent.BUTTON1));
                assertNull(field(f.drag, "source"), "待机头不能开始拖动");
                f.press(f.nodes.get(0));
                Point destination = f.point(f.nodes.get(2));
                for (int i = 0; i < 12; i++) f.dragTo(destination);
                assertEquals(2, f.drag.targetIndex());
                assertSame(head, f.track.getComponent(0));
                f.dragTo(f.pointInOriginalSlot(0));
                f.release(f.pointInOriginalSlot(0));
                assertEquals(List.of("n0", "n1", "n2", "n3"), f.ids());
                assertEquals(0, f.changes.get());
                assertEquals(MacroFlow.DEFAULT_HEAD_WAIT_MILLIS, f.panel.getFlow().getHeadWaitMillis());
            }
        });
    }

    @Test void edgeScrollingAdvancesToOffscreenNodesAndStopsOnCancel() throws Exception {
        edt(() -> {
            try (Fixture f = new Fixture(14)) {
                f.press(f.nodes.get(0));
                Rectangle viewport = f.viewport();
                f.dragTo(new Point(viewport.x + viewport.width - 3, viewport.y + 85));
                Method tick = TimelineNodeDrag.class.getDeclaredMethod("scrollAtEdge");
                tick.setAccessible(true);
                JScrollPane scroll = (JScrollPane) f.drag.layer().getView();
                int before = scroll.getHorizontalScrollBar().getValue();
                for (int i = 0; i < 200; i++) tick.invoke(f.drag);
                assertTrue(scroll.getHorizontalScrollBar().getValue() > before);
                assertEquals(13, f.drag.targetIndex());
                f.dragTo(new Point(viewport.x + 3, viewport.y + 85));
                for (int i = 0; i < 200; i++) tick.invoke(f.drag);
                assertEquals(before, scroll.getHorizontalScrollBar().getValue());
                assertEquals(0, f.drag.targetIndex());
                assertEquals(14, f.ids().size());
                assertEquals(0, f.changes.get());
                f.drag.cancel(true);
                assertClean(f.drag);
            }
        });
    }

    @Test void semiTransparentGhostAndPlaceholderRenderAt150PercentWithoutWritingFlow() throws Exception {
        edt(() -> {
            try (Fixture f = new Fixture(5)) {
                f.image(1, ImageCheckBehavior.JUMP, ImageCheckBehavior.RETRY, "n3", null);
                f.reload();
                Path output = directory();
                f.render(output.resolve("timeline-before-150.png"));
                f.press(f.nodes.get(3));
                Point destination = f.point(f.nodes.get(1));
                destination.translate(30, -20);
                f.dragTo(destination);
                BufferedImage ghost = (BufferedImage) field(f.drag, "ghost");
                assertNotNull(ghost);
                assertTrue(ghost.getWidth() >= 158 && ghost.getHeight() >= 176);
                assertEquals(0, ghost.getRGB(0, 0) >>> 24);
                f.render(output.resolve("timeline-drag-150.png"));
                var previewRoute = TimelineJumpLayoutTest.routes(f.track).get(0);
                assertFalse(previewRoute.forward());
                assertTrue(previewRoute.edge().label().contains("回跳 04"), "预览方向文字应与临时连线方向一致，编号仍为原节点编号");
                assertEquals(List.of("n0", "n1", "n2", "n3", "n4"), f.ids());
                f.release(destination);
                f.layout();
                f.render(output.resolve("timeline-after-150.png"));
                assertEquals("n3", MacroFlowFactory.branchFor(f.nodes.get(1), BranchCondition.FOUND).getTargetId());
                System.out.println("时间线拖动离屏图：" + output);
            }
        });
    }

    private static void assertClean(TimelineNodeDrag drag) throws Exception {
        assertNull(field(drag, "source"));
        assertNull(field(drag, "ghost"));
        assertFalse(((Timer) field(drag, "scrollTimer")).isRunning());
        AWTEventListener listener = (AWTEventListener) field(drag, "listener");
        assertTrue(Arrays.stream(Toolkit.getDefaultToolkit().getAWTEventListeners()).noneMatch(entry ->
                entry == listener || entry instanceof java.awt.event.AWTEventListenerProxy proxy && proxy.getListener() == listener));
    }

    private static MacroNode action(String id, long delay) {
        return new MacroNode(id, NodeType.ACTION, MacroAction.mouseMove(12, 34, delay), new ArrayList<>());
    }
    private static Path directory() throws Exception {
        Path root = Path.of(System.getProperty("macro.test.sandboxRoot", "target/test-sandboxes")).toAbsolutePath();
        Files.createDirectories(root);
        return Files.createTempDirectory(root, "timeline-drag-");
    }
    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static Container find(Container root, String name) {
        if (root.getClass().getSimpleName().equals(name)) return root;
        for (Component child : root.getComponents()) if (child instanceof Container nested) {
            Container match = find(nested, name); if (match != null) return match;
        }
        return null;
    }
    private interface Checked { void run() throws Exception; }
    private static void edt(Checked body) throws Exception {
        SwingUtilities.invokeAndWait(() -> { try { body.run(); } catch (Exception error) { throw new AssertionError(error); } });
    }

    private static final class Fixture implements AutoCloseable {
        final ActionTimelinePanel panel = new ActionTimelinePanel();
        final List<MacroNode> nodes = new ArrayList<>();
        final TimelineNodeDrag drag;
        final JPanel track;
        final AtomicInteger changes = new AtomicInteger();
        Fixture(int count) throws Exception {
            for (int i = 0; i < count; i++) nodes.add(action("n" + i, i * 10L));
            drag = (TimelineNodeDrag) field(panel, "nodeDrag");
            track = TimelineJumpLayoutTest.track(panel);
            panel.setOnFlowChanged(changes::incrementAndGet);
            reload();
        }
        void reload() { panel.setFlow(MacroFlowFactory.rebuildLinear(nodes, "end")); layout(); }
        MacroNode image(int index, ImageCheckBehavior success, ImageCheckBehavior failure, String found, String missed) {
            ImageCheckConfig config = new ImageCheckConfig("synthetic.png", .9);
            config.setSuccessBehavior(success); config.setFailureBehavior(failure);
            MacroNode node = new MacroNode("n" + index, NodeType.IMAGE_CHECK, null, config, new ArrayList<>(List.of(
                    new MacroBranch(BranchCondition.FOUND, found, 0), new MacroBranch(BranchCondition.NOT_FOUND, missed, 0))));
            nodes.set(index, node); return node;
        }
        void layout() { panel.setSize(1020, 700); TimelineJumpLayoutTest.layout(panel); }
        @SuppressWarnings("unchecked") JComponent card(MacroNode node) throws Exception {
            return ((java.util.Map<String, JComponent>) field(panel, "nodePanels")).get(node.getId());
        }
        Point point(MacroNode node) throws Exception {
            JComponent card = card(node);
            return SwingUtilities.convertPoint(card, card.getWidth() / 2, card.getHeight() / 2, drag.layer());
        }
        Point pointInOriginalSlot(int index) throws Exception {
            int[] centers = (int[]) field(drag, "centers");
            return SwingUtilities.convertPoint(track, centers[index], card(nodes.get(0)).getY() + 88, drag.layer());
        }
        Rectangle viewport() {
            JViewport viewport = ((JScrollPane) drag.layer().getView()).getViewport();
            return SwingUtilities.convertRectangle(viewport.getParent(), viewport.getBounds(), drag.layer());
        }
        void press(MacroNode node) throws Exception {
            JComponent card = card(node);
            card.dispatchEvent(new MouseEvent(card, MouseEvent.MOUSE_PRESSED, 1, MouseEvent.BUTTON1_DOWN_MASK,
                    card.getWidth() / 2, card.getHeight() / 2, 1, false, MouseEvent.BUTTON1));
        }
        void dragTo(Point point) throws Exception {
            event(new MouseEvent(drag.layer(), MouseEvent.MOUSE_DRAGGED, 2, MouseEvent.BUTTON1_DOWN_MASK,
                    point.x, point.y, 0, false, MouseEvent.NOBUTTON));
        }
        void release(Point point) throws Exception {
            event(new MouseEvent(drag.layer(), MouseEvent.MOUSE_RELEASED, 3, 0,
                    point.x, point.y, 1, false, MouseEvent.BUTTON1));
        }
        void event(AWTEvent event) throws Exception {
            Method handle = TimelineNodeDrag.class.getDeclaredMethod("handleEvent", AWTEvent.class);
            handle.setAccessible(true); handle.invoke(drag, event);
        }
        List<String> ids() { return MacroFlowFactory.timelineNodes(panel.getFlow()).stream().map(MacroNode::getId).toList(); }
        List<String> viewIds() throws Exception {
            List<String> result = new ArrayList<>();
            for (Component child : track.getComponents()) if (child.getClass().getSimpleName().equals("NodePanel"))
                result.add(((MacroNode) field(child, "macroNode")).getId());
            return result;
        }
        String json() throws Exception { return new ObjectMapper().writeValueAsString(panel.getFlow()); }
        void render(Path path) throws Exception {
            layout();
            BufferedImage image = new BufferedImage(panel.getWidth() * 3 / 2, panel.getHeight() * 3 / 2, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try { graphics.setColor(new Color(0x101218)); graphics.fillRect(0, 0, image.getWidth(), image.getHeight()); graphics.scale(1.5, 1.5); panel.paint(graphics); }
            finally { graphics.dispose(); }
            ImageIO.write(image, "png", path.toFile());
        }
        @Override public void close() { drag.cancel(true); panel.removeNotify(); }
    }

    private static final class RecordingRobot extends Robot {
        final List<Integer> keys = new ArrayList<>();
        RecordingRobot() throws AWTException { }
        @Override public void keyRelease(int code) { keys.add(code); }
        @Override public void keyPress(int code) { fail("不能发送真实按键"); }
        @Override public void mouseMove(int x, int y) { fail("不能移动真实鼠标"); }
        @Override public void mousePress(int mask) { fail("不能发送真实点击"); }
        @Override public void mouseRelease(int mask) { fail("不能发送真实点击"); }
        @Override public void mouseWheel(int amount) { fail("不能发送真实滚轮"); }
    }
}
