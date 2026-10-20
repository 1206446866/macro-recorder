package com.macro.ui;

import com.macro.config.TimelineInsertPosition;
import com.macro.flow.BranchCondition;
import com.macro.flow.ImageCheckBehavior;
import com.macro.flow.ImageCheckConfig;
import com.macro.flow.MacroFlowFactory;
import com.macro.flow.MacroNode;
import com.macro.flow.NodeType;
import com.macro.model.ActionType;
import com.macro.model.MacroAction;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** 验证插入边界、行内提交和重试模型同步；鼠标事件为程序构造，不代替真实桌面验收。 */
public final class TimelineEditingTest {
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                testInsertion();
                testInlineEditing();
                testRetryEditing();
                if (!GraphicsEnvironment.isHeadless()) testBlankClickAndHide();
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException(exception);
            }
        });
        System.out.println("通过：插入方向/边界、失焦/回车去重、非法值保护、取消/重建、重试配置及分支同步"
                + (GraphicsEnvironment.isHeadless() ? "；当前无桌面，空白点击/隐藏场景未运行" : "；程序构造点击及隐藏取消通过"));
    }

    private static void testInsertion() {
        ActionTimelinePanel timeline = new ActionTimelinePanel();
        MacroNode first = action(1), second = action(2);
        check(timeline.insertAction(null, first, TimelineInsertPosition.BEFORE), "空时间线插入失败");
        check(timeline.insertAction(first, second, TimelineInsertPosition.AFTER), "末项后插失败");
        MacroNode front = action(0);
        check(timeline.insertAction(first, front, TimelineInsertPosition.BEFORE), "首项前插失败");
        check(order(timeline).equals(List.of(front, first, second)), "前后插入顺序错误");
        MacroNode explicit = action(3);
        check(timeline.insertActionBefore(second, explicit), "显式前插失败");
        check(order(timeline).equals(List.of(front, first, explicit, second)), "显式位置语义改变");
        MacroNode stale = action(100);
        check(!timeline.insertAction(stale, action(4), TimelineInsertPosition.AFTER), "过期锚点发生意外插入");
        check(!timeline.insertActionBefore(null, action(5)), "显式前插错误地接受空锚点");
        check(!timeline.insertActionAfter(stale, action(6)), "显式后插错误地接受过期锚点");
        check(!timeline.insertAction(first, null, TimelineInsertPosition.BEFORE), "空节点被插入");

        MacroNode selectedInsertion = action(7);
        check(timeline.insertAction(null, selectedInsertion, TimelineInsertPosition.AFTER), "当前选中节点后插失败");
        check(order(timeline).indexOf(selectedInsertion) == order(timeline).indexOf(explicit) + 1,
                "空锚点没有复用当前选择");
        timeline.setFlow(timeline.getFlow());
        MacroNode tail = action(8);
        check(timeline.insertAction(null, tail, TimelineInsertPosition.BEFORE)
                && order(timeline).get(order(timeline).size() - 1) == tail, "无选中动作时没有追加到末尾");
    }

    private static void testInlineEditing() throws ReflectiveOperationException {
        CountingAction action = new CountingAction();
        action.setType(ActionType.MOUSE_MOVE);
        action.setX(12);
        action.setY(34);
        action.setDelay(40);
        action.xWrites = 0;
        action.delayWrites = 0;
        ActionTimelinePanel timeline = new ActionTimelinePanel();
        timeline.setActions(List.of(action));
        Container coordinate = coordinate(timeline);
        invoke(coordinate, "beginEdit");
        JTextField field = text(coordinate);
        field.setText("2147483648");
        blur(field);
        check(action.getX() == 12 && field.getParent() == coordinate && field.getToolTipText() != null,
                "越界坐标未受保护或没有错误提示");
        field.setText("-120");
        blur(field);
        field.postActionEvent();
        check(action.getX() == -120 && action.xWrites == 1, "坐标失焦未提交或重复提交");

        invoke(coordinate, "beginEdit");
        field = text(coordinate);
        field.setText("99");
        escape(field);
        blur(field);
        check(action.getX() == -120 && action.xWrites == 1, "取消坐标后仍提交");

        JPanel connector = new JPanel();
        JLabel label = new JLabel("40 ms");
        JPanel arrow = new JPanel();
        connector.add(label);
        connector.add(arrow);
        Method beginDelay = ActionTimelinePanel.class.getDeclaredMethod("beginDelayEdit",
                JPanel.class, JLabel.class, javax.swing.JComponent.class, MacroAction.class);
        beginDelay.setAccessible(true);
        beginDelay.invoke(timeline, connector, label, arrow, action);
        JTextField delay = text(connector);
        delay.setText("");
        blur(delay);
        check(action.getDelay() == 40 && action.delayWrites == 0, "空延迟覆盖了原值");
        delay.setText("9223372036854775808");
        blur(delay);
        check(action.getDelay() == 40 && action.delayWrites == 0, "溢出延迟覆盖了原值");
        delay.setText("0");
        delay.postActionEvent();
        blur(delay);
        check(action.getDelay() == 0 && action.delayWrites == 1, "回车与失焦重复写入延迟");

        invoke(coordinate, "beginEdit");
        field = text(coordinate);
        field.setText("789");
        timeline.refreshTimeline();
        blur(field);
        check(action.getX() == -120 && action.xWrites == 1, "重建后旧编辑器仍修改模型");
    }

    private static void testRetryEditing() throws ReflectiveOperationException {
        for (ImageCheckBehavior success : ImageCheckBehavior.values()) {
            for (ImageCheckBehavior failure : ImageCheckBehavior.values()) {
                if (success != ImageCheckBehavior.RETRY && failure != ImageCheckBehavior.RETRY) continue;
                ImageCheckConfig config = new ImageCheckConfig("template.png", 0.9);
                config.setSuccessBehavior(success);
                config.setFailureBehavior(failure);
                config.setRetryDelay(1000L);
                MacroNode image = new MacroNode("image", NodeType.IMAGE_CHECK, null, config, new ArrayList<>());
                ActionTimelinePanel timeline = new ActionTimelinePanel();
                timeline.setFlow(MacroFlowFactory.rebuildLinear(List.of(action(1), image, action(2)), null));
                JPanel track = (JPanel) field(timeline, "timelinePanel");
                track.setSize(track.getPreferredSize().width, 266);
                layout(track);
                List<Rectangle> before = nodeBounds(track);
                invoke(timeline, "beginRetryDelayEdit", MacroNode.class, image);
                JTextField editor = text(track);
                layout(track);
                check(nodeBounds(track).equals(before), "重试编辑器改变了节点或连接线布局");
                check(editor.getBounds().width >= 68 && editor.getBounds().height == 26,
                        "重试编辑器没有放置在可见回路位置");
                editor.setText("");
                blur(editor);
                check(config.getRetryDelay() == 1000L, "空重试值覆盖配置");
                editor.setText("0");
                blur(editor);
                editor.postActionEvent();
                check(config.getRetryDelay() == 0L && editor.getParent() == null, "失焦未提交重试配置");
                for (var branch : image.getChildren()) {
                    boolean retry = branch.getCondition() == BranchCondition.FOUND
                            ? success == ImageCheckBehavior.RETRY : failure == ImageCheckBehavior.RETRY;
                    check(branch.getDelay() == 0, "实际分支延迟与零重试配置不一致");
                    if (retry) check(branch.getTargetId().equals(image.getId()), "重试编辑改变了闭环目标");
                }
                invoke(timeline, "beginRetryDelayEdit", MacroNode.class, image);
                editor = text(track);
                editor.setText("2750");
                editor.postActionEvent();
                blur(editor);
                check(MacroFlowFactory.findRetryDelay(image) == 2750L, "回车未提交重试值");
                for (var branch : image.getChildren()) {
                    boolean retry = branch.getCondition() == BranchCondition.FOUND
                            ? success == ImageCheckBehavior.RETRY : failure == ImageCheckBehavior.RETRY;
                    check(branch.getDelay() == (retry ? 2750L : 0L), "重试分支未同步或污染非重试分支");
                }
                invoke(timeline, "beginRetryDelayEdit", MacroNode.class, image);
                editor = text(track);
                editor.setText("5000");
                escape(editor);
                blur(editor);
                check(config.getRetryDelay() == 2750L, "Esc 没有取消重试编辑");
            }
        }
    }

    private static void testBlankClickAndHide() throws ReflectiveOperationException {
        ActionTimelinePanel timeline = new ActionTimelinePanel();
        MacroAction action = MacroAction.mouseMove(1, 2, 30);
        timeline.setActions(List.of(action));
        JFrame window = new JFrame("时间线行内编辑测试");
        window.setContentPane(timeline);
        window.setSize(750, 520);
        window.setVisible(true);
        try {
            int initial = Toolkit.getDefaultToolkit().getAWTEventListeners(AWTEvent.MOUSE_EVENT_MASK).length;
            Container coordinate = coordinate(timeline);
            invoke(coordinate, "beginEdit");
            text(coordinate).setText("77");
            JLabel blank = new JLabel("不主动取得焦点的说明文字");
            timeline.add(blank, java.awt.BorderLayout.SOUTH);
            blank.dispatchEvent(new MouseEvent(blank, MouseEvent.MOUSE_PRESSED, 1, 0, 2, 2, 1, false, MouseEvent.BUTTON1));
            check(action.getX() == 77, "点击同窗口不可聚焦组件未提交");
            check(Toolkit.getDefaultToolkit().getAWTEventListeners(AWTEvent.MOUSE_EVENT_MASK).length == initial,
                    "提交后没有释放临时鼠标监听");
            invoke(coordinate, "beginEdit");
            JTextField hidden = text(coordinate);
            hidden.setText("88");
            timeline.setVisible(false);
            blur(hidden);
            check(action.getX() == 77, "隐藏页面后仍提交旧编辑器");
            check(Toolkit.getDefaultToolkit().getAWTEventListeners(AWTEvent.MOUSE_EVENT_MASK).length == initial,
                    "隐藏页面后没有释放临时鼠标监听");
        } finally {
            window.dispose();
        }
    }

    private static MacroNode action(int value) {
        return new MacroNode("action-" + value, NodeType.ACTION,
                MacroAction.mouseMove(value, value, 0), new ArrayList<>());
    }

    private static List<MacroNode> order(ActionTimelinePanel timeline) {
        return MacroFlowFactory.timelineNodes(timeline.getFlow());
    }

    private static Container coordinate(Container root) {
        for (Component component : descendants(root)) {
            if (component.getClass().getSimpleName().equals("CoordinateEditor")) return (Container) component;
        }
        throw new AssertionError("缺少坐标编辑器");
    }

    private static JTextField text(Container root) {
        for (Component component : root.getComponents()) if (component instanceof JTextField text) return text;
        throw new AssertionError("缺少行内输入框");
    }

    private static List<Component> descendants(Container root) {
        List<Component> result = new ArrayList<>();
        for (Component component : root.getComponents()) {
            result.add(component);
            if (component instanceof Container child) result.addAll(descendants(child));
        }
        return result;
    }

    private static List<Rectangle> nodeBounds(Container track) {
        return java.util.Arrays.stream(track.getComponents())
                .filter(component -> component.getClass().getSimpleName().equals("NodePanel"))
                .map(Component::getBounds).toList();
    }

    private static void layout(Container root) {
        root.doLayout();
        for (Component component : root.getComponents()) if (component instanceof Container child) layout(child);
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void invoke(Object owner, String name) throws ReflectiveOperationException {
        Method method = owner.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(owner);
    }

    private static void invoke(Object owner, String name, Class<?> type, Object value) throws ReflectiveOperationException {
        Method method = owner.getClass().getDeclaredMethod(name, type);
        method.setAccessible(true);
        method.invoke(owner, value);
    }

    private static void blur(JTextField field) {
        for (var listener : field.getFocusListeners()) listener.focusLost(new FocusEvent(field, FocusEvent.FOCUS_LOST));
    }

    private static void escape(JTextField field) {
        for (var listener : field.getKeyListeners()) listener.keyPressed(new KeyEvent(field,
                KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED));
    }

    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }

    private static final class CountingAction extends MacroAction {
        private int xWrites;
        private int delayWrites;
        @Override public void setX(int value) { super.setX(value); xWrites++; }
        @Override public void setDelay(long value) { super.setDelay(value); delayWrites++; }
    }
}
