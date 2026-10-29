package com.macro.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.flow.ImageCheckConfig;
import com.macro.flow.ImageMatchResult;
import com.macro.flow.MacroFlow;
import com.macro.flow.MacroFlowFactory;
import com.macro.flow.MacroNode;
import com.macro.flow.NodeType;
import com.macro.model.MacroAction;
import com.macro.player.MacroPlayer;
import com.macro.player.PlayerState;

import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.FocusEvent;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** 组件级保护检查使用合成回调，不操作剪贴板、原生文件面板或真实键鼠。 */
public final class TimelinePlaybackProtectionTest {
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                blockedMutationsAndReadOnlyView();
                staleInlineEditors();
                staleDialogAndConfirmationCallbacks();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
        System.out.println("通过：播放/暂停/停止中保护、只读查看、旧行内编辑器及跨播放代次回调");
    }

    private static void blockedMutationsAndReadOnlyView() throws Exception {
        Fixture fixture = new Fixture();
        AtomicInteger requests = new AtomicInteger();
        fixture.panel.setOnActionEditRequested(node -> requests.incrementAndGet());
        fixture.panel.setOnActionInsertRequested(node -> requests.incrementAndGet());
        String before = json(fixture.flow);
        for (PlayerState state : List.of(PlayerState.PLAYING, PlayerState.PAUSED, PlayerState.STOPPING)) {
            // 不切换本地开关，确认写入点会直接检查状态，覆盖 EDT 通知尚未到达的情况。
            fixture.state.set(state);
            check(!fixture.panel.insertActionBefore(fixture.first, action("before")), "前插未拦截");
            check(!fixture.panel.insertActionAfter(fixture.first, action("after")), "后插未拦截");
            check(!fixture.panel.insertAction(null, action("default"), null), "默认插入未拦截");
            check(!fixture.panel.replaceAction(fixture.first, action("replacement")), "替换未拦截");
            check(!fixture.panel.deleteAction(0, true), "保留延迟删除未拦截");
            check(!fixture.panel.deleteAction(fixture.first, false), "节点删除未拦截");
            invoke(fixture.panel, "selectMacroNode", new Class<?>[]{MacroNode.class}, fixture.first);
            fixture.panel.getActionMap().get("deleteSelectedTimelineAction")
                    .actionPerformed(new ActionEvent(fixture.panel, 0, "Delete"));
            Container coordinate = Objects.requireNonNull(coordinate(fixture.panel), "缺少坐标编辑组件");
            invoke(coordinate, "beginEdit", new Class<?>[0]);
            check(text(coordinate) == null, "只读坐标仍创建编辑器");
            invoke(fixture.panel, "beginRetryDelayEdit", new Class<?>[]{MacroNode.class}, fixture.image);
            check(text(track(fixture.panel)) == null, "只读重试仍创建编辑器");
            JPopupMenu menu = (JPopupMenu) invoke(fixture.panel, "createContextMenu",
                    new Class<?>[]{MacroNode.class}, fixture.first);
            for (Component entry : menu.getComponents()) {
                JMenuItem item = (JMenuItem) entry;
                if ("复制节点".equals(item.getText())) {
                    check(item.isEnabled(), "只读复制被禁用");
                    continue;
                }
                check(!item.isEnabled(), "修改菜单没有禁用");
                for (var listener : item.getActionListeners()) {
                    listener.actionPerformed(new ActionEvent(item, 0, "绕过禁用状态"));
                }
            }
            fixture.panel.refreshTimeline();
            fixture.panel.setFlow(fixture.flow);
            check(fixture.panel.getFlow() == fixture.flow && fixture.panel.isEnabled(), "查看能力被禁用");
            check(json(fixture.flow).equals(before) && requests.get() == 0, "只读操作修改了运行图或打开编辑入口");
        }
        fixture.state.set(PlayerState.IDLE);
        check(fixture.panel.insertActionAfter(fixture.first, action("after-stop")), "结束后不能插入");
        check(fixture.panel.replaceAction(fixture.first, action("after-stop-edit")), "结束后不能替换");
        check(fixture.panel.deleteAction(0, false), "结束后不能删除");
    }

    private static void staleInlineEditors() throws Exception {
        for (int kind = 0; kind < 3; kind++) {
            Fixture fixture = new Fixture();
            String before = json(fixture.flow);
            JTextField editor;
            if (kind == 0) {
                Container coordinate = Objects.requireNonNull(coordinate(fixture.panel), "缺少坐标编辑组件");
                invoke(coordinate, "beginEdit", new Class<?>[0]);
                editor = text(coordinate);
            } else if (kind == 1) {
                JPanel connector = new JPanel();
                JLabel label = new JLabel("40 ms");
                JPanel arrow = new JPanel();
                connector.add(label);
                connector.add(arrow);
                invoke(fixture.panel, "beginDelayEdit",
                        new Class<?>[]{JPanel.class, JLabel.class, JComponent.class, MacroAction.class},
                        connector, label, arrow, fixture.first.getAction());
                editor = text(connector);
            } else {
                invoke(fixture.panel, "beginRetryDelayEdit", new Class<?>[]{MacroNode.class}, fixture.image);
                editor = text(track(fixture.panel));
            }
            check(editor != null, "空闲时不能开始行内编辑");
            editor.setText("77");
            fixture.state.set(PlayerState.PAUSED);
            editor.postActionEvent();
            blur(editor);
            check(json(fixture.flow).equals(before), "暂停时旧编辑器仍提交");
            fixture.state.set(PlayerState.IDLE);
            fixture.panel.setEditingEnabled(false);
            fixture.panel.setEditingEnabled(true);
            editor.setText("88");
            editor.postActionEvent();
            blur(editor);
            check(json(fixture.flow).equals(before), "结束后旧失焦/回车又写入模型");
        }
        Fixture fixture = new Fixture();
        Container coordinate = Objects.requireNonNull(coordinate(fixture.panel), "缺少坐标编辑组件");
        invoke(coordinate, "beginEdit", new Class<?>[0]);
        JTextField editor = Objects.requireNonNull(text(coordinate), "坐标编辑器未打开");
        editor.setText("999");
        fixture.panel.setEditingEnabled(false);
        fixture.panel.setEditingEnabled(true);
        editor.postActionEvent();
        check(fixture.first.getAction().getX() == 12, "启动前取消的编辑器仍写入模型");
    }

    private static void staleDialogAndConfirmationCallbacks() {
        Fixture fixture = new Fixture();
        AtomicInteger writes = new AtomicInteger();
        Consumer<MacroNode> save = fixture.panel.guardEditCallback(node -> writes.incrementAndGet());
        Consumer<Boolean> confirmation = fixture.panel.guardEditCallback(confirmed -> writes.incrementAndGet());
        fixture.panel.setEditingEnabled(false);
        fixture.state.set(PlayerState.PAUSED);
        save.accept(action("during-pause"));
        confirmation.accept(true);
        fixture.state.set(PlayerState.IDLE);
        fixture.panel.setEditingEnabled(true);
        save.accept(action("after-stop"));
        confirmation.accept(true);
        check(writes.get() == 0, "旧弹窗或确认回调跨播放任务写入");
        fixture.panel.guardEditCallback((MacroNode node) -> writes.incrementAndGet()).accept(action("fresh"));
        check(writes.get() == 1, "结束后新的编辑回调没有恢复");
    }

    /** 实际播放器检查只执行两个图像节点，匹配器为模拟实现，完全没有输入动作。 */
    public static final class Desktop {
        public static void main(String[] args) throws Exception {
            MacroPlayer player = new MacroPlayer();
            MacroNode first = image("first-image"), second = image("second-image");
            MacroFlow flow = MacroFlowFactory.rebuildLinear(List.of(first, second), "end");
            String before = json(flow);
            ActionTimelinePanel panel = new ActionTimelinePanel();
            AtomicInteger dialogWrites = new AtomicInteger();
            AtomicReference<ActionEditorDialog> dialog = new AtomicReference<>();
            AtomicReference<Consumer<MacroNode>> save = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                panel.setFlow(flow);
                panel.setEditPermission(() -> player.getState() == PlayerState.IDLE);
                save.set(panel.guardEditCallback(node -> {
                    dialogWrites.incrementAndGet();
                    panel.insertActionAfter(first, node);
                }));
                // 创建真正的编辑器但不显示窗口，验证其原有保存路径不能绕过回调保护。
                dialog.set(ActionEditorDialog.create(null, save.get()));
            });
            player.setOnStateChange(state -> SwingUtilities.invokeLater(() ->
                    panel.setEditingEnabled(player.getState() == PlayerState.IDLE)));
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            var executor = Executors.newSingleThreadExecutor();
            try {
                var task = executor.submit(() -> player.play(flow, 1, (node, context) -> {
                    if (node == first) {
                        entered.countDown();
                        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("模拟匹配等待超时");
                    }
                    return ImageMatchResult.found(new Rectangle(1, 2, 3, 4));
                }));
                check(entered.await(5, TimeUnit.SECONDS), "实际播放未开始");
                SwingUtilities.invokeAndWait(() -> {
                    check(player.getState() == PlayerState.PLAYING, "未处于实际播放状态");
                    check(!panel.insertActionAfter(first, image("blocked-playing")), "实际播放中允许插入");
                });
                player.pause();
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        check(player.getState() == PlayerState.PAUSED, "未处于实际暂停状态");
                        check(!panel.insertActionAfter(first, image("blocked-paused")), "实际暂停中允许插入");
                        check(!panel.deleteAction(second, false), "实际暂停中允许删除");
                        invoke(dialog.get(), "saveAction", new Class<?>[0]);
                        check(dialogWrites.get() == 0 && json(flow).equals(before), "已有弹窗修改了实际运行流程");
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                });
                release.countDown();
                player.resume();
                task.get(5, TimeUnit.SECONDS);
                SwingUtilities.invokeAndWait(() -> {
                    check(player.getState() == PlayerState.IDLE, "播放未正常结束");
                    save.get().accept(action("stale-save"));
                    check(dialogWrites.get() == 0, "结束后旧编辑器回调又生效");
                    check(panel.insertActionAfter(first, image("fresh-image")), "实际播放结束后未恢复编辑");
                });
            } finally {
                player.stop();
                release.countDown();
                executor.shutdownNow();
                SwingUtilities.invokeAndWait(() -> dialog.get().dispose());
            }
            System.out.println("通过：实际播放器播放/暂停保护、已有编辑器保存、恢复播放无非法节点及结束后解锁");
        }
    }

    private static final class Fixture {
        final AtomicReference<PlayerState> state = new AtomicReference<>(PlayerState.IDLE);
        final MacroNode first = action("first");
        final MacroNode image = image("image");
        final MacroFlow flow = MacroFlowFactory.rebuildLinear(List.of(first, image, action("last")), "end");
        final ActionTimelinePanel panel = new ActionTimelinePanel();
        Fixture() {
            panel.setFlow(flow);
            panel.setEditPermission(() -> state.get() == PlayerState.IDLE);
        }
    }

    private static MacroNode action(String id) {
        return new MacroNode(id, NodeType.ACTION, MacroAction.mouseMove(12, 34, 40), new ArrayList<>());
    }

    private static MacroNode image(String id) {
        ImageCheckConfig config = new ImageCheckConfig("unused-template.png", 0.9);
        config.setRetryDelay(1000L);
        return new MacroNode(id, NodeType.IMAGE_CHECK, null, config, new ArrayList<>());
    }

    private static String json(MacroFlow flow) throws Exception {
        return new ObjectMapper().writeValueAsString(flow);
    }

    private static Object invoke(Object owner, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = owner.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(owner, args);
    }

    private static Container track(ActionTimelinePanel panel) throws Exception {
        var field = ActionTimelinePanel.class.getDeclaredField("timelinePanel");
        field.setAccessible(true);
        return (Container) field.get(panel);
    }

    private static Container coordinate(Container root) {
        for (Component component : root.getComponents()) {
            if (component.getClass().getSimpleName().equals("CoordinateEditor")) return (Container) component;
            if (component instanceof Container child) {
                Container found = coordinate(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JTextField text(Container root) {
        for (Component component : root.getComponents()) if (component instanceof JTextField field) return field;
        return null;
    }

    private static void blur(JTextField editor) {
        for (var listener : editor.getFocusListeners()) listener.focusLost(new FocusEvent(editor, FocusEvent.FOCUS_LOST));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
