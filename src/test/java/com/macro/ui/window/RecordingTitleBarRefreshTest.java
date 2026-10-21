package com.macro.ui.window;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** 屏幕外独立窗口，不构造 AppConfig、不注册 Hook、不截图或发送输入。 */
@EnabledOnOs(OS.WINDOWS)
class RecordingTitleBarRefreshTest {
    private CountingFrame frame;
    private CountingPanel bar;
    private JButton minimize;
    private WindowsRecordingTitleBar caption;
    private HWND hwnd;

    @BeforeEach
    void prepare() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要 Windows 屏幕外窗口");
        Toolkit.getDefaultToolkit().setDynamicLayout(true);
        assertTrue(Toolkit.getDefaultToolkit().isDynamicLayoutActive(), "必须开启动态布局");
        onEdt(() -> {
            frame = new CountingFrame();
            frame.setAutoRequestFocus(false);
            bar = new CountingPanel();
            bar.setLayout(new BorderLayout());
            bar.setPreferredSize(new Dimension(640, 112));
            JPanel heading = new JPanel(new BorderLayout());
            heading.add(new JLabel("Macro Recorder"), BorderLayout.CENTER);
            minimize = new JButton("−");
            heading.add(minimize, BorderLayout.EAST);
            bar.add(heading, BorderLayout.NORTH);
            bar.add(new JLabel("synthetic.exe · test title"), BorderLayout.CENTER);
            frame.setContentPane(bar);
            frame.pack();
            hwnd = new HWND(Native.getComponentPointer(frame));
            caption = new WindowsRecordingTitleBar(frame, bar, List.of(minimize));
            caption.activate();
            frame.pack();
            frame.setMinimumSize(frame.getSize());
            frame.setLocation(-12000, -12000);
            frame.setVisible(true);
            frame.validate();
            caption.refresh();
        });
        // 仅稳定首次显示消息，不用等待来替代被测刷新。
        Thread.sleep(80);
        drain();
        onEdt(() -> { caption.refresh(); frame.validations = 0; bar.repaintRequests = 0; });
    }

    @AfterEach
    void close() throws Exception {
        onEdt(() -> {
            try { if (caption != null) caption.deactivate(); }
            finally { if (frame != null) frame.dispose(); }
        });
    }

    @Test
    void unchangedRefreshRequestsAndPublicRefreshDoNotForceLayoutOrWholeBarPaint() throws Exception {
        onEdt(() -> {
            for (int i = 0; i < 12; i++) {
                caption.refresh();
                requestUnchangedRefresh();
            }
        });
        drain();
        onEdt(() -> {
            assertEquals(0, frame.validations, "有效且尺寸一致时不应强制布局");
            assertEquals(0, bar.repaintRequests, "重复尺寸消息不应强制整栏重绘");
        });
    }

    @Test
    void repairsStaleValidContainersAndInvalidLayoutAtUnchangedWindowSize() throws Exception {
        onEdt(() -> {
            frame.getRootPane().setSize(frame.getRootPane().getWidth() - 80, frame.getRootPane().getHeight());
            frame.getRootPane().validate();
            assertTrue(frame.getRootPane().isValid());
            frame.validations = 0;
            caption.refresh();
            assertSynchronized();
            assertTrue(frame.validations > 0);

            bar.setSize(bar.getWidth() - 60, bar.getHeight());
            bar.validate(); // 有效但尺寸错误的子容器同样需要恢复。
            caption.refresh();
            assertSynchronized();

            bar.invalidate();
            frame.validations = 0;
            bar.repaintRequests = 0;
            caption.refresh();
            assertTrue(frame.validations > 0, "无改宽时的真实布局失效不能被去重掉");
            assertTrue(bar.repaintRequests > 0);
            assertSynchronized();
        });
    }

    @Test
    void nativeWidthChangeStillUpdatesContainersAndButtonSnapshot() throws Exception {
        RECT bounds = new RECT();
        assertTrue(User32.INSTANCE.GetWindowRect(hwnd, bounds));
        assertTrue(User32.INSTANCE.SetWindowPos(hwnd, null, 0, 0,
                bounds.right - bounds.left + 300, bounds.bottom - bounds.top, 0x0002 | 0x0004 | 0x0010));
        drain();
        onEdt(() -> { assertSynchronized(); assertButtonSnapshot(); });
    }

    @Test
    void exitSizeMoveRepairsLayoutAndAlwaysKeepsFinalPaintAndButtonSnapshot() throws Exception {
        onEdt(() -> {
            frame.getRootPane().setSize(frame.getRootPane().getWidth() - 90, frame.getRootPane().getHeight());
            frame.getRootPane().validate();
            bar.repaintRequests = 0;
            User32.INSTANCE.SendMessage(hwnd, 0x0232, new WPARAM(0), new LPARAM(0));
        });
        drain();
        onEdt(() -> {
            assertSynchronized();
            assertButtonSnapshot();
            assertTrue(bar.repaintRequests > 0, "松手后必须有最终提交");
            frame.validations = 0;
            bar.repaintRequests = 0;
            // 即使没有新尺寸，最终刷新仍保留；但不强制重排有效容器。
            User32.INSTANCE.SendMessage(hwnd, 0x0232, new WPARAM(0), new LPARAM(0));
        });
        drain();
        onEdt(() -> {
            assertEquals(0, frame.validations);
            assertTrue(bar.repaintRequests > 0);
            bar.repaintRequests = 0;
            caption.refresh();
            assertEquals(0, bar.repaintRequests, "最终刷新标记不能污染后续普通刷新");
        });
    }

    private void requestUnchangedRefresh() {
        try {
            // 单独验证自定义调度，不把原 AWT 对合成位置消息的合法布局计入去重断言。
            Method method = WindowsRecordingTitleBar.class.getDeclaredMethod("queueRefresh", boolean.class, boolean.class);
            method.setAccessible(true);
            method.invoke(caption, false, false);
        } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
    }

    private void assertSynchronized() {
        Insets insets = frame.getInsets();
        int width = frame.getWidth() - insets.left - insets.right;
        int height = frame.getHeight() - insets.top - insets.bottom;
        assertEquals(width, frame.getRootPane().getWidth());
        assertEquals(height, frame.getRootPane().getHeight());
        assertEquals(width, frame.getLayeredPane().getWidth());
        assertEquals(width, bar.getWidth());
        assertEquals(height, bar.getHeight());
        assertTrue(bar.isValid());
    }

    private void assertButtonSnapshot() {
        try {
            Field field = WindowsRecordingTitleBar.class.getDeclaredField("layout");
            field.setAccessible(true);
            Object snapshot = field.get(caption);
            Method accessor = snapshot.getClass().getDeclaredMethod("buttons");
            accessor.setAccessible(true);
            Rectangle expected = SwingUtilities.convertRectangle(minimize.getParent(), minimize.getBounds(), frame.getRootPane());
            assertEquals(List.of(expected), accessor.invoke(snapshot), "按钮命中快照必须对应最终布局");
        } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
    }

    private static void onEdt(Runnable action) throws Exception { SwingUtilities.invokeAndWait(action); }
    private static void drain() throws Exception { onEdt(() -> { }); onEdt(() -> { }); }

    private static final class CountingFrame extends JFrame {
        int validations;
        @Override public void validate() { validations++; super.validate(); }
    }

    private static final class CountingPanel extends JPanel {
        int repaintRequests;
        @Override public void repaint() { repaintRequests++; super.repaint(); }
    }
}
