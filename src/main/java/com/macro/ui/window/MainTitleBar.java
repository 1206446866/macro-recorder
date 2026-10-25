package com.macro.ui.window;

import com.macro.constant.UiConstants;
import com.macro.ui.theme.ThemeManager;
import com.macro.ui.theme.ThemePalette;
import com.macro.ui.util.DarkButtonUI;
import com.macro.ui.util.WindowManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowEvent;
import java.util.List;

/** 应用窗口与对话框共用的主题标题和窗口按钮；拖动、边框及缩放由原生适配层接管。 */
public final class MainTitleBar extends JMenuBar {
    private final ThemePalette theme = ThemeManager.getPalette();
    private final JLabel title = new JLabel();
    private final JButton minimize;
    private final JButton maximize;
    private final JButton close;
    private final Window frame;

    /** 使用根面板标题区域承载控件，保留原有业务内容面板和页面切换结构。 */
    public MainTitleBar(JFrame frame) {
        this((Window) frame);
    }

    /** 为对话框复用相同外观，仅提供原有的关闭操作。 */
    public MainTitleBar(JDialog dialog) {
        this((Window) dialog);
    }

    /** 共享标题和按钮绘制，独立窗口保留最小化/最大化，对话框仅显示关闭。 */
    private MainTitleBar(Window frame) {
        this.frame = frame;
        setName("mainWindowTitleBar");
        setLayout(new BorderLayout(12, 0));
        setOpaque(true);
        setBackground(theme.background());
        setFocusable(false);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, theme.border()), new EmptyBorder(0, 12, 0, 0)));
        title.setName("mainWindowTitle");
        title.setText(windowTitle());
        title.setFont(new Font(UiConstants.DEFAULT_FONT_FAMILY, Font.BOLD, 13));
        title.setForeground(theme.textPrimary());
        title.setMinimumSize(new Dimension());
        title.setIconTextGap(8);
        if (!frame.getIconImages().isEmpty()) {
            title.setIcon(new ImageIcon(frame.getIconImages().get(0).getScaledInstance(18, 18, Image.SCALE_SMOOTH)));
        }
        add(title, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new GridLayout(1, 0));
        buttons.setOpaque(false);
        if (frame instanceof JFrame window) {
            minimize = createButton("mainWindowMinimizeButton", "最小化", Control.MINIMIZE,
                    () -> WindowManager.minimize(window));
            maximize = createButton("mainWindowMaximizeButton", "最大化", Control.MAXIMIZE, () -> {
                WindowManager.toggleMaximize(window);
                refreshWindowState();
            });
            buttons.add(minimize);
            buttons.add(maximize);
        } else {
            minimize = null;
            maximize = null;
        }
        close = createButton("mainWindowCloseButton", "关闭", Control.CLOSE,
                () -> frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING)));
        buttons.add(close);
        add(buttons, BorderLayout.EAST);
        frame.addWindowStateListener(event -> refreshWindowState());
        frame.addPropertyChangeListener("resizable", event -> refreshWindowState());
        frame.addPropertyChangeListener("title", event -> title.setText(windowTitle()));
        refreshWindowState();
    }

    /** 返回需要排除原生拖动命中的按钮，避免点击窗口操作时开始拖动。 */
    public List<JButton> getWindowButtons() {
        return minimize == null ? List.of(close) : List.of(minimize, maximize, close);
    }

    /** 从对应窗口类型读取标题，避免为对话框伪造独立窗口状态。 */
    private String windowTitle() {
        return frame instanceof Frame window ? window.getTitle() : ((Dialog) frame).getTitle();
    }

    /** 同步最大化/还原图形及可调整大小状态，不主动改变窗口位置。 */
    private void refreshWindowState() {
        if (!(frame instanceof JFrame window)) return;
        boolean maximized = (window.getExtendedState() & Frame.MAXIMIZED_BOTH) != 0;
        maximize.setEnabled(window.isResizable());
        maximize.setToolTipText(maximized ? "还原" : "最大化");
        maximize.getAccessibleContext().setAccessibleName(maximize.getToolTipText());
        if (!Boolean.valueOf(maximized).equals(maximize.getClientProperty("window.maximized"))) {
            maximize.putClientProperty("window.maximized", maximized);
            Icon icon = new ControlIcon(maximized ? Control.RESTORE : Control.MAXIMIZE, theme);
            maximize.setIcon(icon);
            maximize.setDisabledIcon(icon);
        }
    }

    /** 创建固定宽度的自绘窗口按钮，点击回调沿用既有窗口操作和关闭事件。 */
    private JButton createButton(String name, String tooltip, Control control, Runnable action) {
        JButton button = new JButton();
        button.setName(name);
        button.setToolTipText(tooltip);
        button.getAccessibleContext().setAccessibleName(tooltip);
        button.setPreferredSize(new Dimension(46, 36));
        button.setMinimumSize(button.getPreferredSize());
        button.setFocusable(false);
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder());
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        button.setRolloverEnabled(true);
        button.setForeground(theme.textPrimary());
        Icon icon = new ControlIcon(control, theme);
        button.setIcon(icon);
        button.setDisabledIcon(icon);
        button.setUI(new ControlButtonUI(control == Control.CLOSE, theme));
        button.addActionListener(event -> action.run());
        return button;
    }

    private enum Control { MINIMIZE, MAXIMIZE, RESTORE, CLOSE }

    /** 绘制最小化、最大化/还原和关闭图形，颜色跟随当前主题和按钮状态。 */
    private record ControlIcon(Control control, ThemePalette theme) implements Icon {
        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            AbstractButton button = (AbstractButton) component;
            Graphics2D g2 = (Graphics2D) graphics.create();
            try {
                g2.translate(x, y);
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                boolean closing = control == Control.CLOSE && (button.getModel().isRollover() || button.getModel().isPressed());
                g2.setColor(!button.isEnabled() ? theme.textMuted() : closing ? theme.textOnAccent() : theme.textPrimary());
                g2.setStroke(new BasicStroke(1.25f));
                switch (control) {
                    case MINIMIZE -> g2.drawLine(2, 8, 12, 8);
                    case MAXIMIZE -> g2.drawRect(2, 2, 10, 10);
                    case RESTORE -> {
                        g2.drawLine(5, 2, 12, 2);
                        g2.drawLine(12, 2, 12, 9);
                        g2.drawRect(2, 5, 7, 7);
                    }
                    case CLOSE -> {
                        g2.drawLine(3, 3, 11, 11);
                        g2.drawLine(11, 3, 3, 11);
                    }
                }
            } finally { g2.dispose(); }
        }

        /** 返回窗口图标的逻辑宽度。 */
        @Override public int getIconWidth() { return 14; }
        /** 返回窗口图标的逻辑高度。 */
        @Override public int getIconHeight() { return 14; }
    }

    /** 绘制主题悬停背景，关闭按钮使用现有危险色。 */
    private static final class ControlButtonUI extends DarkButtonUI {
        private final boolean close;
        private final ThemePalette theme;

        /** 绑定按钮用途及当前调色板，不另设颜色体系。 */
        private ControlButtonUI(boolean close, ThemePalette theme) {
            this.close = close;
            this.theme = theme;
        }

        @Override
        public void paint(Graphics graphics, JComponent component) {
            AbstractButton button = (AbstractButton) component;
            if (button.isEnabled() && (button.getModel().isRollover() || button.getModel().isPressed())) {
                graphics.setColor(close ? button.getModel().isPressed() ? theme.danger() : theme.dangerHover()
                        : button.getModel().isPressed() ? theme.border() : theme.controlHover());
                graphics.fillRect(0, 0, component.getWidth(), component.getHeight());
            }
            super.paint(graphics, component);
        }
    }
}
