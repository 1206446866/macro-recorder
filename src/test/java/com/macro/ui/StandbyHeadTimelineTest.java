package com.macro.ui;

import com.macro.config.TimelineInsertPosition;
import com.macro.flow.*;
import com.macro.model.MacroAction;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.FocusEvent;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StandbyHeadTimelineTest {
    @Test void toggleAddsOnlyReadonlyHeadAndKeepsNumberingAndBranches() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ActionTimelinePanel panel = panel();
            var nodes = MacroFlowFactory.timelineNodes(panel.getFlow());
            MacroNode image = nodes.get(1);
            image.getImageCheckConfig().setSuccessBehavior(ImageCheckBehavior.JUMP);
            MacroFlowFactory.branchFor(image, BranchCondition.FOUND).setTargetId(nodes.get(2).getId());
            String target = MacroFlowFactory.branchFor(image, BranchCondition.FOUND).getTargetId();
            var ids = List.copyOf(panel.getFlow().getNodeMap().keySet());
            var card = realCard(panel);
            Dimension size = card.getPreferredSize();
            List<Rectangle> details = layoutDetails(card);
            checkbox(panel).doClick();
            assertEquals(1_000L, panel.getFlow().getHeadWaitMillis());
            assertNotNull(named(panel, "standbyHead"));
            assertEquals(ids, List.copyOf(panel.getFlow().getNodeMap().keySet()));
            assertEquals(target, MacroFlowFactory.branchFor(image, BranchCondition.FOUND).getTargetId());
            assertEquals("01", TimelineNodeNumbers.labels(nodes).get(nodes.get(0).getId()));
            assertEquals(size, realCard(panel).getPreferredSize());
            assertEquals(details, layoutDetails(realCard(panel)));
            assertFalse(contains(named(panel, "standbyHead"), JTextField.class));
            assertEquals(3, MacroFlowFactory.timelineNodes(panel.getFlow()).size());
            checkbox(panel).doClick();
            assertNull(panel.getFlow().getHeadWaitMillis());
            assertNull(named(panel, "standbyHead"));
        });
    }

    @Test void onlyConnectorEditsDelayAndPendingEditCannotSurviveToggle() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ActionTimelinePanel panel = panel();
            panel.setStandbyHeadEnabled(true);
            JTextField editor = editHead(panel);
            editor.setText("1250");
            editor.postActionEvent();
            assertEquals(1_250L, panel.getFlow().getHeadWaitMillis());
            assertEquals("1250 ms", headDelay(panel).getText());
            assertTrue(panel.setStandbyHeadEnabled(true));
            assertEquals(1_250L, panel.getFlow().getHeadWaitMillis(), "保存其他设置不能重置自定时长");
            editor = editHead(panel);
            editor.setText("9223372036854775808");
            // 非法值走失焦校验；回车会按产品设计弹出模态警告，不应让自动测试等待用户确认。
            for (var listener : editor.getFocusListeners()) listener.focusLost(new FocusEvent(editor, FocusEvent.FOCUS_LOST));
            assertEquals(1_250L, panel.getFlow().getHeadWaitMillis());
            assertNotNull(editor.getParent());
            assertNotNull(editor.getToolTipText());
            panel.setStandbyHeadEnabled(false);
            editor.setText("3000");
            editor.postActionEvent();
            assertNull(panel.getFlow().getHeadWaitMillis(), "旧编辑回调不得重新启用待机");
        });
    }

    @Test void insertDeleteAndReloadPreserveCustomWaitAndExistingActionDelays() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ActionTimelinePanel panel = panel();
            panel.setStandbyHeadEnabled(true);
            panel.getFlow().setHeadWaitMillis(432L);
            var original = MacroFlowFactory.timelineNodes(panel.getFlow());
            MacroNode added = new MacroNode("new", NodeType.ACTION, MacroAction.mouseMove(3, 4, 20), new ArrayList<>());
            assertTrue(panel.insertAction(original.get(0), added, TimelineInsertPosition.BEFORE));
            assertEquals(432L, panel.getFlow().getHeadWaitMillis());
            assertTrue(panel.deleteAction(added, false));
            assertEquals(432L, panel.getFlow().getHeadWaitMillis());
            assertEquals(original, MacroFlowFactory.timelineNodes(panel.getFlow()));
            assertEquals(50, original.get(2).getAction().getDelay());
            panel.setFlow(panel.getFlow());
            assertTrue(checkbox(panel).isSelected());
            assertEquals("432 ms", headDelay(panel).getText());
            panel.setActions(List.of(MacroAction.mouseMove(0, 0, 7)));
            assertFalse(checkbox(panel).isSelected());
            assertNull(named(panel, "standbyHead"));
            assertEquals(7, panel.getActions().get(0).getDelay());
        });
    }

    @Test void playbackLockAndFailedConfigWriteCannotChangeHead() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ActionTimelinePanel panel = panel();
            panel.setOnStandbyHeadToggle(enabled -> false);
            checkbox(panel).doClick();
            assertFalse(checkbox(panel).isSelected());
            assertNull(panel.getFlow().getHeadWaitMillis());
            panel.setStandbyHeadEnabled(true);
            JTextField editor = editHead(panel);
            editor.setText("500");
            panel.setEditingEnabled(false);
            assertFalse(checkbox(panel).isEnabled());
            assertFalse(panel.setStandbyHeadEnabled(false));
            editor.postActionEvent();
            assertEquals(1_000L, panel.getFlow().getHeadWaitMillis());
            panel.setEditingEnabled(true);
            assertTrue(checkbox(panel).isEnabled());
        });
    }

    @Test void headDoesNotCountAsActionAndEmptyTimelineStaysEmpty() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ActionTimelinePanel panel = new ActionTimelinePanel();
            assertTrue(panel.setStandbyHeadEnabled(true));
            assertTrue(panel.getActions().isEmpty());
            assertTrue(MacroFlowFactory.timelineNodes(panel.getFlow()).isEmpty());
            assertNotNull(named(panel, "standbyHead"));
            assertNull(named(panel, "standbyHeadConnector"));
            assertEquals("0 个", ((JLabel) field(panel, "actionCountValue")).getText());
            assertEquals("1.00 秒", ((JLabel) field(panel, "totalDurationValue")).getText());
        });
    }

    private static ActionTimelinePanel panel() {
        MacroNode first = new MacroNode("a", NodeType.ACTION, MacroAction.mouseMove(0, 0, 0), new ArrayList<>());
        MacroNode image = new MacroNode("image", NodeType.IMAGE_CHECK, null,
                new ImageCheckConfig("missing.png", .9), new ArrayList<>());
        MacroNode last = new MacroNode("b", NodeType.ACTION, MacroAction.mouseMove(1, 1, 50), new ArrayList<>());
        ActionTimelinePanel panel = new ActionTimelinePanel();
        panel.setFlow(MacroFlowFactory.rebuildLinear(List.of(first, image, last), "end"));
        return panel;
    }

    static JTextField editHead(ActionTimelinePanel panel) {
        JLabel label = headDelay(panel);
        Container connector = label.getParent();
        label.dispatchEvent(new MouseEvent(label, MouseEvent.MOUSE_CLICKED, 0, 0, 2, 2, 1, false, MouseEvent.BUTTON1));
        for (Component child : connector.getComponents()) if (child instanceof JTextField editor) return editor;
        throw new AssertionError("待机连线没有进入编辑");
    }

    private static JLabel headDelay(ActionTimelinePanel panel) {
        return assertInstanceOf(JLabel.class, named(panel, "standbyHeadDelay"));
    }

    static JCheckBox checkbox(ActionTimelinePanel panel) {
        return (JCheckBox) field(panel, "standbyHeadCheckBox");
    }

    static Component named(Component root, String name) {
        if (name.equals(root.getName())) return root;
        if (root instanceof Container container) for (Component child : container.getComponents()) {
            Component found = named(child, name);
            if (found != null) return found;
        }
        return null;
    }

    private static Container realCard(ActionTimelinePanel panel) {
        JPanel track = (JPanel) field(panel, "timelinePanel");
        for (Component child : track.getComponents()) {
            if (child.getClass().getSimpleName().equals("NodePanel")) return (Container) child;
        }
        throw new AssertionError("没有操作卡");
    }

    private static List<Rectangle> layoutDetails(Container card) {
        card.setSize(card.getPreferredSize());
        card.doLayout();
        return java.util.Arrays.stream(card.getComponents()).map(Component::getBounds).toList();
    }

    static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static boolean contains(Component root, Class<?> type) {
        if (type.isInstance(root)) return true;
        if (root instanceof Container container) for (Component child : container.getComponents()) {
            if (contains(child, type)) return true;
        }
        return false;
    }
}
