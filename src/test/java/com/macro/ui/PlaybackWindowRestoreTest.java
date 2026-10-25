package com.macro.ui;

import com.macro.config.AppConfig;
import com.macro.ui.util.WindowManager;

import javax.swing.SwingUtilities;
import java.awt.Frame;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;

/** 隔离配置并模拟窗口状态；不显示窗口、不注册热键、不发送真实输入。 */
public final class PlaybackWindowRestoreTest {
    public static void main(String[] args) {
        int exit = 0;
        try {
            Path classes = Path.of(AppConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            check(classes.toString().contains("test-sandboxes"), "必须隔离个人配置");
            SwingUtilities.invokeAndWait(PlaybackWindowRestoreTest::verify);
            System.out.println("通过：自动最小化恢复、已恢复窗口前置、最大化状态、配置关闭、原已最小化和重复清理");
        } catch (Throwable failure) {
            failure.printStackTrace();
            exit = 1;
        }
        System.exit(exit);
    }

    private static void verify() {
        ProbeFrame frame = new ProbeFrame();
        try {
            Field field = MacroFrame.class.getDeclaredField("appConfig");
            field.setAccessible(true);
            AppConfig config = (AppConfig) field.get(frame);
            config.setMinimizeOnPlayStart(true);
            config.setRestoreWindowAfterPlayStop(true);
            scenario(frame, Frame.NORMAL, null, Frame.NORMAL, 1);
            // 原回归：窗口已恢复但在目标应用后方时，仍必须请求前置。
            scenario(frame, Frame.NORMAL, Frame.NORMAL, Frame.NORMAL, 1);
            scenario(frame, Frame.MAXIMIZED_BOTH, null, Frame.MAXIMIZED_BOTH, 1);
            scenario(frame, Frame.ICONIFIED, null, Frame.ICONIFIED, 0);
            config.setRestoreWindowAfterPlayStop(false);
            scenario(frame, Frame.NORMAL, null, Frame.ICONIFIED, 0);
            config.setRestoreWindowAfterPlayStop(true);
            config.setMinimizeOnPlayStart(false);
            scenario(frame, Frame.NORMAL, Frame.ICONIFIED, Frame.ICONIFIED, 0);
            WindowManager.restoreAndActivate(null);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        } finally {
            frame.dispose();
        }
    }

    private static void scenario(ProbeFrame frame, int initial, Integer stateBeforeCleanup,
                                 int expectedState, int expectedRequests) throws Exception {
        frame.simulatedState = initial;
        frame.frontCalls = 0;
        frame.focusCalls = 0;
        boolean topmost = frame.isAlwaysOnTop();
        invoke(frame, "prepareWindowForPlayback");
        if (stateBeforeCleanup != null) frame.simulatedState = stateBeforeCleanup;
        invoke(frame, "restoreWindowAfterPlayback");
        check(frame.simulatedState == expectedState, "恢复丢失原状态或违反配置");
        check(frame.frontCalls == expectedRequests && frame.focusCalls == expectedRequests,
                "恢复时前置/焦点请求错误");
        check(frame.isAlwaysOnTop() == topmost, "恢复改变永久置顶状态");
        invoke(frame, "restoreWindowAfterPlayback");
        check(frame.frontCalls == expectedRequests && frame.focusCalls == expectedRequests,
                "重复清理再次抢前台");
    }

    private static void invoke(MacroFrame frame, String name) throws Exception {
        Method method = MacroFrame.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(frame);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class ProbeFrame extends MacroFrame {
        private int simulatedState;
        private int frontCalls;
        private int focusCalls;

        @Override public int getExtendedState() { return simulatedState; }
        @Override public void setExtendedState(int state) { simulatedState = state; }
        @Override public void toFront() { frontCalls++; }
        @Override public void requestFocus() { focusCalls++; }
    }
}
