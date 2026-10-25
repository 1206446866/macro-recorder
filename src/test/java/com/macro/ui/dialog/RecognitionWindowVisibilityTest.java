package com.macro.ui.dialog;

import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** 用虚拟可见状态模拟主窗口、所属编辑器及其他程序窗口；不实际显示、截图或抢焦点。 */
class RecognitionWindowVisibilityTest {
    @Test
    void allVisibleWindowsAreCollectedBeforeOwnersHideChildren() throws Exception {
        onEdt(() -> {
            VirtualFrame main = new VirtualFrame(true);
            VirtualDialog editor = new VirtualDialog(main);
            main.child = editor;
            VirtualFrame other = new VirtualFrame(true);
            VirtualFrame hidden = new VirtualFrame(false);
            ImageRecognitionFrame recognition = new ImageRecognitionFrame(null, () -> null);
            try {
                main.state = Frame.MAXIMIZED_BOTH;
                invoke(recognition, "hideApplicationWindows");
                assertFalse(main.isVisible());
                assertFalse(editor.isVisible());
                assertFalse(other.isVisible());
                assertFalse(hidden.isVisible());
                main.state = Frame.NORMAL;
                invoke(recognition, "restoreApplicationWindows");
                assertTrue(main.isVisible());
                assertTrue(editor.isVisible(), "主窗口隐藏后自动隐藏的编辑器也必须恢复");
                assertTrue(other.isVisible());
                assertFalse(hidden.isVisible(), "原来隐藏的窗口不应被显示");
                assertEquals(Frame.MAXIMIZED_BOTH, main.state);
                int shows = main.showCount;
                invoke(recognition, "restoreApplicationWindows");
                assertEquals(shows, main.showCount, "重复恢复不应再次显示窗口");
            } finally { recognition.dispose(); editor.dispose(); main.dispose(); other.dispose(); hidden.dispose(); }
        });
    }

    @Test
    void disposedEditorsStayClosedWhenCaptureIsCancelled() throws Exception {
        onEdt(() -> {
            VirtualFrame main = new VirtualFrame(true);
            VirtualDialog editor = new VirtualDialog(main);
            ImageRecognitionFrame recognition = new ImageRecognitionFrame(null, () -> null);
            try {
                invoke(recognition, "hideApplicationWindows");
                editor.dispose();
                recognition.dispose();
                assertTrue(main.isVisible(), "关闭识别窗口没有恢复主窗口");
                assertFalse(editor.isVisible(), "已销毁的旧编辑器被重新打开");
                invoke(recognition, "runRecognitionWorker");
                assertNull(get(recognition, "recognitionWorker"), "迟到的延时事件启动了已关闭窗口的识别");
                assertFalse(recognition.isDisplayable());
            } finally { recognition.dispose(); editor.dispose(); main.dispose(); }
        });
    }

    private static void onEdt(Check check) throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "隐藏窗口构造需要桌面环境");
        SwingUtilities.invokeAndWait(() -> {
            try { check.run(); } catch (Exception failure) { throw new AssertionError(failure); }
        });
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void invoke(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(target);
    }

    private static final class VirtualFrame extends JFrame {
        boolean visible;
        boolean alive = true;
        int state;
        int showCount;
        VirtualDialog child;
        VirtualFrame(boolean visible) {
            super.addNotify(); // 仅建立隐藏原生句柄，供 DWM 辅助方法调用。
            this.visible = visible;
        }
        @Override public boolean isVisible() { return visible; }
        @Override public boolean isDisplayable() { return alive; }
        @Override public void setVisible(boolean visible) {
            this.visible = visible;
            if (visible) showCount++;
            else if (child != null) child.visible = false;
        }
        @Override public int getExtendedState() { return state; }
        @Override public void setExtendedState(int state) { this.state = state; }
        @Override public void dispose() { alive = false; visible = false; super.dispose(); }
    }

    private static final class VirtualDialog extends JDialog {
        boolean visible = true;
        boolean alive = true;
        VirtualDialog(Frame owner) {
            super(owner, false);
            super.addNotify();
        }
        @Override public boolean isVisible() { return visible; }
        @Override public boolean isDisplayable() { return alive; }
        @Override public void setVisible(boolean visible) { this.visible = visible; }
        @Override public void dispose() { alive = false; visible = false; super.dispose(); }
    }

    @FunctionalInterface private interface Check { void run() throws Exception; }
}
