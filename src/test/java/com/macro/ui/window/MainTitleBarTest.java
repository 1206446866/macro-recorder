package com.macro.ui.window;

import com.macro.constant.ApplicationInfo;
import com.macro.ui.theme.ThemeManager;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** 隐藏窗口中的按钮及布局测试，不注册钩子、不发送真实输入。 */
class MainTitleBarTest {
    /** 对话框沿用主页样式，但关闭按钮只派发本窗口事件，不生成最小化或最大化入口。 */
    @Test
    void dialogsOnlyExposeCloseAndPreserveTheirModality() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            JDialog dialog = new JDialog((Window) null, "设置", Dialog.ModalityType.APPLICATION_MODAL);
            try {
                dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
                AtomicInteger closeEvents = new AtomicInteger();
                dialog.addWindowListener(new WindowAdapter() {
                    @Override public void windowClosing(WindowEvent event) { closeEvents.incrementAndGet(); }
                });
                MainTitleBar bar = new MainTitleBar(dialog);
                assertEquals(1, bar.getWindowButtons().size());
                JButton close = bar.getWindowButtons().get(0);
                assertEquals("mainWindowCloseButton", close.getName());
                assertEquals(new Dimension(46, 36), close.getPreferredSize());
                dialog.setTitle("更新后的设置标题");
                assertEquals(dialog.getTitle(), ((JLabel) bar.getComponent(0)).getText());
                close.doClick();
                assertEquals(1, closeEvents.get());
                assertEquals(Dialog.ModalityType.APPLICATION_MODAL, dialog.getModalityType());
            } finally { dialog.dispose(); }
        });
    }

    @Test
    void controlsPreserveWindowStateAndCloseUsesNormalClosingEvent() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            StateFrame frame = new StateFrame();
            try {
                frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
                AtomicInteger closeEvents = new AtomicInteger();
                frame.addWindowListener(new WindowAdapter() {
                    @Override public void windowClosing(WindowEvent event) { closeEvents.incrementAndGet(); }
                });
                MainTitleBar bar = new MainTitleBar(frame);
                var buttons = bar.getWindowButtons();
                assertEquals(3, buttons.size());
                frame.state = Frame.MAXIMIZED_BOTH;
                buttons.get(0).doClick();
                assertEquals(Frame.MAXIMIZED_BOTH | Frame.ICONIFIED, frame.state);
                buttons.get(1).doClick();
                assertEquals(Frame.NORMAL, frame.state);
                assertEquals("最大化", buttons.get(1).getToolTipText());
                buttons.get(1).doClick();
                assertEquals(Frame.MAXIMIZED_BOTH, frame.state);
                assertEquals("还原", buttons.get(1).getToolTipText());
                frame.setResizable(false);
                assertFalse(buttons.get(1).isEnabled());
                buttons.get(1).doClick();
                assertEquals(Frame.MAXIMIZED_BOTH, frame.state);
                buttons.get(2).doClick();
                assertEquals(1, closeEvents.get(), "自绘关闭必须走既有窗口关闭处理");
                frame.setTitle("新的窗口标题");
                assertEquals("新的窗口标题", ((JLabel) bar.getComponent(0)).getText());
            } finally { frame.dispose(); }
        });
    }

    @Test
    void buttonsStayAtRightEdgeLongTitleDoesNotPushThemOutAndColorsUseTheme() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            StateFrame frame = new StateFrame();
            try {
                frame.setTitle(ApplicationInfo.APP_NAME + " · " + "很长的窗口名称".repeat(40));
                MainTitleBar bar = new MainTitleBar(frame);
                for (int width : new int[]{240, 900}) {
                    bar.setSize(width, bar.getPreferredSize().height);
                    layout(bar);
                    var buttons = bar.getWindowButtons();
                    Rectangle previous = null;
                    for (JButton button : buttons) {
                        Rectangle bounds = SwingUtilities.convertRectangle(button.getParent(), button.getBounds(), bar);
                        assertEquals(46, bounds.width);
                        if (previous != null) assertEquals(previous.x + previous.width, bounds.x);
                        previous = bounds;
                        assertFalse(button.isFocusable(), "窗口按钮不应保留录制输入焦点");
                    }
                    assertNotNull(previous);
                    assertEquals(width, previous.x + previous.width);
                    JLabel title = (JLabel) bar.getComponent(0);
                    assertTrue(title.getX() + title.getWidth() <= width - 138);
                }
                JButton close = bar.getWindowButtons().get(2);
                close.getModel().setRollover(true);
                BufferedImage image = paint(close);
                assertEquals(ThemeManager.getPalette().dangerHover().getRGB(), image.getRGB(3, 3));
                JButton minimize = bar.getWindowButtons().get(0);
                minimize.getModel().setRollover(true);
                assertEquals(ThemeManager.getPalette().controlHover().getRGB(), paint(minimize).getRGB(3, 3));
                frame.setTitle(ApplicationInfo.APP_NAME);
                minimize.getModel().setRollover(false);
                close.getModel().setRollover(false);
                BufferedImage preview = new BufferedImage(bar.getWidth() * 3 / 2, bar.getHeight() * 3 / 2,
                        BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = preview.createGraphics();
                try {
                    graphics.scale(1.5, 1.5);
                    graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    bar.printAll(graphics);
                } finally { graphics.dispose(); }
                Path path = Path.of("target", "main-titlebar-20261025", "preview.png");
                Files.createDirectories(path.getParent());
                assertTrue(ImageIO.write(preview, "png", path.toFile()));
            } catch (Exception failure) { throw new AssertionError(failure); }
            finally { frame.dispose(); }
        });
    }

    /** 同步隐藏组件的布局，无需显示窗口。 */
    private static void layout(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) if (child instanceof Container nested) layout(nested);
    }

    /** 绘制按钮实际交互状态以验证主题色。 */
    private static BufferedImage paint(JComponent component) {
        BufferedImage image = new BufferedImage(component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try { component.paint(graphics); } finally { graphics.dispose(); }
        return image;
    }

    /** 仅记录状态请求，避免测试最大化时干预真实桌面。 */
    private static final class StateFrame extends JFrame {
        private int state;
        @Override public int getExtendedState() { return state; }
        @Override public void setExtendedState(int state) { this.state = state; }
    }
}
