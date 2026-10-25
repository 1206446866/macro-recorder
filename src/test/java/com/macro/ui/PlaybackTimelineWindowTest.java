package com.macro.ui;

import com.macro.config.AppConfig;
import com.macro.flow.MacroFlowFactory;
import com.macro.model.MacroAction;
import com.macro.player.MacroPlayer;
import com.macro.player.PlaybackProgress;
import com.macro.player.PlayerState;
import com.macro.ui.util.WindowManager;

import javax.swing.*;
import java.awt.Frame;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static com.macro.player.PlaybackProgress.Phase.*;

/** 隔离配置、隐藏窗口、模拟窗口状态/目标激活；不注册 Hook、不发送输入、不抢真实前台。 */
public final class PlaybackTimelineWindowTest {
    public static void main(String[] args) {
        ProbeFrame[] holder = new ProbeFrame[1];
        int exit = 0;
        try {
            Path classes = Path.of(AppConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            check(classes.toString().contains("test-sandboxes"), "必须隔离个人配置");
            SwingUtilities.invokeAndWait(() -> {
                try {
                    ProbeFrame frame = holder[0] = new ProbeFrame();
                    AppConfig config = (AppConfig) get(frame, "appConfig");
                    config.setMinimizeOnPlayStart(true);
                    config.setRestoreWindowAfterPlayStop(true);
                    ActionTimelinePanel timeline = timeline(frame);
                    timeline.setActions(List.of(MacroAction.mouseMove(0, 0, 0), MacroAction.mouseMove(1, 1, 100)));
                    invoke(frame, "syncActionsFromTimeline");
                    invoke(frame, "prepareWindowForPlayback");
                    state(player(frame), PlayerState.PLAYING);
                    var nodes = MacroFlowFactory.timelineNodes(timeline.getFlow());
                    invoke(frame, "receivePlaybackProgress", PlaybackProgress.class, new PlaybackProgress(1, null, LOOP_STARTED));
                    invoke(frame, "receivePlaybackProgress", PlaybackProgress.class, new PlaybackProgress(1, nodes.get(0).getId(), NODE_COMPLETED));
                    invoke(frame, "receivePlaybackProgress", PlaybackProgress.class, new PlaybackProgress(1, nodes.get(1).getId(), NODE_STARTED));
                } catch (Exception failure) { throw new AssertionError(failure); }
            });
            // EDT 之外的快捷键必须立即暂停，再由 EDT 展示窗口。
            invoke(holder[0], "handlePlaybackPauseHotkey");
            check(player(holder[0]).getState() == PlayerState.PAUSED, "F11 暂停被延后到 EDT");
            SwingUtilities.invokeAndWait(() -> {
                try {
                    ProbeFrame frame = holder[0];
                    check(frame.simulatedState == Frame.ICONIFIED, "只有暂停信号时不能抢先恢复窗口");
                    invoke(frame, "showPausedPlaybackTimeline"); // 模拟工作线程确认安全暂停点。
                    ActionTimelinePanel timeline = timeline(frame);
                    check(frame.simulatedState == Frame.NORMAL && frame.frontCalls > 0, "暂停未恢复并请求前置");
                    check(timeline.getParent().isVisible(), "暂停没有切换时间线");
                    check(!timeline.isEditingAllowed(), "暂停允许修改流程");
                    var nodes = MacroFlowFactory.timelineNodes(timeline.getFlow());
                    check(timeline.getCompletedNodeIds().equals(Set.of(nodes.get(0).getId())), "暂停时已完成标记丢失");
                    invoke(frame, "showHomePage");
                    invoke(frame, "showActionTimeline");
                    check(timeline.getCompletedNodeIds().size() == 1, "页面切换清除了进度");
                    JButton resume = (JButton) get(frame, "timelinePauseButton");
                    check(resume.isVisible() && resume.isEnabled() && resume.getText().equals("继续"), "时间线缺少继续按钮");
                    resume.doClick();
                    check(frame.prepareCalls == 1 && player(frame).getState() == PlayerState.PLAYING,
                            "未在目标准备完成之后继续");
                    check(frame.simulatedState == Frame.ICONIFIED, "继续未按配置重新最小化");
                    int fronts = frame.frontCalls;
                    invoke(frame, "updatePlaybackState", PlayerState.class, PlayerState.PAUSED);
                    invoke(frame, "showPausedPlaybackTimeline");
                    check(frame.frontCalls == fronts, "迟到的暂停事件再次抢前台");
                    invoke(frame, "resumePlayback");
                    check(frame.prepareCalls == 1, "迟到的继续请求再次准备窗口");
                    state(player(frame), PlayerState.PAUSED);
                    invoke(frame, "updatePlaybackState", PlayerState.class, PlayerState.PAUSED);
                    invoke(frame, "showPausedPlaybackTimeline");
                    frame.allowResume = false;
                    resume.doClick();
                    check(player(frame).getState() == PlayerState.PAUSED, "目标准备失败仍然继续");
                    frame.allowResume = true;
                    ((AppConfig) get(frame, "appConfig")).setMinimizeOnPlayStart(false);
                    resume.doClick();
                    check(frame.simulatedState == Frame.NORMAL, "关闭自动最小化后仍然最小化");
                    ((JButton) get(frame, "timelineStopButton")).doClick();
                    check(player(frame).getState() == PlayerState.STOPPING, "时间线停止按钮未共用停止入口");
                    state(player(frame), PlayerState.IDLE); // 此夹具无播放工作线程，仅模拟其完成通知。
                    invoke(frame, "receivePlaybackProgress", PlaybackProgress.class, new PlaybackProgress(1, null, FINISHED));
                    invoke(frame, "restoreWindowAfterPlayback");
                    invoke(frame, "restorePlaybackFinishedState", Exception.class, null);
                    check(timeline.isEditingAllowed(), "结束后未恢复编辑权限");
                    check(timeline.getCompletedNodeIds().size() == 1 && timeline.getCurrentPlaybackNodeId() == null,
                            "停止应保留完成结果并清除当前标记");
                    check(!resume.isVisible() && !frame.isAlwaysOnTop(), "结束后按钮或置顶状态错误");
                } catch (Exception failure) { throw new AssertionError(failure); }
            });
            System.out.println("通过：F11 立即暂停、恢复窗口与只读时间线、页面切换保留进度、继续前准备目标、失败保持暂停、最小化配置、迟到通知和停止收尾");
        } catch (Throwable failure) {
            failure.printStackTrace();
            exit = 1;
        } finally {
            if (holder[0] != null) {
                try { SwingUtilities.invokeAndWait(holder[0]::dispose); } catch (Exception failure) { exit = 1; }
            }
        }
        System.exit(exit);
    }

    private static MacroPlayer player(MacroFrame frame) throws Exception { return (MacroPlayer) get(frame, "player"); }
    private static ActionTimelinePanel timeline(MacroFrame frame) throws Exception { return (ActionTimelinePanel) get(frame, "actionTimelinePanel"); }
    private static Object get(Object target, String name) throws Exception {
        Field field = MacroFrame.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
    private static void state(MacroPlayer player, PlayerState state) throws Exception {
        Field field = MacroPlayer.class.getDeclaredField("state"); field.setAccessible(true); field.set(player, state);
    }
    private static void invoke(Object target, String name) throws Exception {
        Method method = MacroFrame.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(target);
    }
    private static void invoke(Object target, String name, Class<?> type, Object value) throws Exception {
        Method method = MacroFrame.class.getDeclaredMethod(name, type); method.setAccessible(true); method.invoke(target, value);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static final class ProbeFrame extends MacroFrame {
        int simulatedState;
        int frontCalls;
        int prepareCalls;
        boolean allowResume = true;
        @Override public int getExtendedState() { return simulatedState; }
        @Override public void setExtendedState(int state) { simulatedState = state; }
        @Override public void toFront() { frontCalls++; }
        @Override public void requestFocus() { }
        @Override boolean prepareWindowForPlaybackResume() {
            try {
                check(player(this).getState() == PlayerState.PAUSED, "准备目标时播放器已开始发送输入");
                prepareCalls++;
                if (!allowResume) return false;
                if (((AppConfig) get(this, "appConfig")).isMinimizeOnPlayStart()) WindowManager.minimize(this);
                return true;
            } catch (Exception failure) { throw new AssertionError(failure); }
        }
    }
}
