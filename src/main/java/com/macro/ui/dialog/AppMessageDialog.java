package com.macro.ui.dialog;

import com.macro.constant.UiConstants;
import com.macro.diagnostics.ErrorReporter;

import com.macro.ui.theme.ThemeManager;
import com.macro.ui.theme.ThemePalette;
import com.macro.ui.util.ComponentStyleUtil;
import com.macro.ui.window.WindowTitleBarSupport;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import com.macro.ui.util.DarkButtonUI;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 应用统一消息弹窗。
 *
 * <p>弹窗样式全部从当前主题读取，业务代码只需使用静态方法显示消息。</p>
 */
public final class AppMessageDialog extends JDialog {
    private final WindowTitleBarSupport titleBarSupport;

    /** 为提示和确认弹窗安装统一标题栏，保留原模态与默认确认按钮。 */
    @Override public void addNotify() {
        super.addNotify();
        if (titleBarSupport != null) titleBarSupport.activate();
    }

    /** 原生窗口销毁前卸载回调，不改变确认结果和取消语义。 */
    @Override public void removeNotify() {
        try { if (titleBarSupport != null) titleBarSupport.deactivate(); }
        finally { super.removeNotify(); }
    }

    private static final Font FONT_NORMAL = new Font(UiConstants.DEFAULT_FONT_FAMILY, Font.PLAIN, 13);
    private static final Font FONT_SMALL = new Font(UiConstants.DEFAULT_FONT_FAMILY, Font.PLAIN, 12);
    private static final Font FONT_BOLD = new Font(UiConstants.DEFAULT_FONT_FAMILY, Font.BOLD, 14);
    private static final Font FONT_TITLE = new Font(UiConstants.DEFAULT_FONT_FAMILY, Font.BOLD, 16);
    private static final int DIALOG_WIDTH = 430;
    private static final int DETAILS_EXPANDED_HEIGHT = 200;
    private static final AtomicBoolean UNCAUGHT_DIALOG_OPEN = new AtomicBoolean();

    private final MessageType messageType;
    private final String dialogTitle;
    private final String message;
    private final Throwable throwable;
    private final boolean confirmation;
    private final ThemePalette theme;
    private boolean confirmed;
    private JPanel detailsContainer;
    private JButton detailsButton;

    /** 按消息类型构建统一深色提示或确认窗口，保存异常详情并安装默认确认与关闭行为。 */
    private AppMessageDialog(
            Window owner,
            MessageType messageType,
            String dialogTitle,
            String message,
            Throwable throwable,
            boolean confirmation
    ) {
        super(owner, normalizeTitle(dialogTitle, messageType), ModalityType.APPLICATION_MODAL);
        this.messageType = messageType;
        this.dialogTitle = normalizeTitle(dialogTitle, messageType);
        this.message = normalizeMessage(message);
        this.throwable = throwable;
        this.confirmation = confirmation;
        this.theme = ThemeManager.getPalette();

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setBackground(theme.background());
        setResizable(false);
        setContentPane(createContent());
        titleBarSupport = new WindowTitleBarSupport(this);
        getRootPane().setDefaultButton(findConfirmButton(getContentPane()));
        registerKeyboardActions();
        pack();
        setMinimumSize(new Dimension(DIALOG_WIDTH, getHeight()));
        setLocationRelativeTo(owner);
    }

    /** 显示错误消息。 */
    public static void showError(Component parent, String message) {
        show(parent, MessageType.ERROR, message, null);
    }

    /** 显示带异常详情的错误消息。 */
    public static void showError(Component parent, String message, Throwable throwable) {
        show(parent, MessageType.ERROR, message, throwable);
    }

    /** 显示警告消息。 */
    public static void showWarning(Component parent, String message) {
        show(parent, MessageType.WARNING, message, null);
    }

    /** 真正失败但使用警告样式的操作同样保留异常报告，普通校验警告不写日志。 */
    public static void showWarning(Component parent, String message, Throwable throwable) {
        show(parent, MessageType.WARNING, message, throwable);
    }

    /** 在启动入口安装；每个异常都记录，连续未捕获异常只保留一个待处理弹窗。 */
    public static void installUncaughtExceptionHandler() {
        ErrorReporter.installUncaughtHandler((message, failure) -> display(null, MessageType.ERROR, message, failure, true));
    }

    /** 显示普通提示消息。 */
    public static void showInfo(Component parent, String message) {
        show(parent, MessageType.INFO, message, null);
    }

    /** 显示主题统一的确认弹窗，并同步返回用户选择。 */
    public static boolean showConfirm(Component parent, String title, String message) {
        if (SwingUtilities.isEventDispatchThread()) {
            return showConfirmOnEventThread(parent, title, message);
        }

        AtomicBoolean result = new AtomicBoolean(false);
        try {
            SwingUtilities.invokeAndWait(() ->
                    result.set(showConfirmOnEventThread(parent, title, message))
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("无法显示确认弹窗", exception.getCause());
        }
        return result.get();
    }

    /** 在界面事件线程中显示弹窗。 */
    private static void show(
            Component parent,
            MessageType messageType,
            String message,
            Throwable throwable
    ) {
        if (messageType == MessageType.ERROR || throwable != null) ErrorReporter.report(message, throwable);
        display(parent, messageType, message, throwable, false);
    }

    private static void display(Component parent, MessageType messageType, String message,
                                Throwable throwable, boolean uncaught) {
        if (GraphicsEnvironment.isHeadless()) return;
        if (uncaught && !UNCAUGHT_DIALOG_OPEN.compareAndSet(false, true)) return;
        Runnable showTask = () -> {
            try {
                Window owner = resolveOwner(parent);
                AppMessageDialog dialog = new AppMessageDialog(
                        owner, messageType, messageType.getTitle(), message, throwable, false);
                dialog.setVisible(true);
            } catch (Throwable displayFailure) {
                ErrorReporter.report("显示消息弹窗失败", displayFailure);
            } finally {
                if (uncaught) UNCAUGHT_DIALOG_OPEN.set(false);
            }
        };

        try {
            if (SwingUtilities.isEventDispatchThread()) showTask.run();
            else SwingUtilities.invokeLater(showTask);
        } catch (Throwable schedulingFailure) {
            if (uncaught) UNCAUGHT_DIALOG_OPEN.set(false);
            ErrorReporter.report("安排消息弹窗失败", schedulingFailure);
        }
    }

    /** 在界面事件线程中显示确认弹窗。 */
    private static boolean showConfirmOnEventThread(
            Component parent,
            String title,
            String message
    ) {
        AppMessageDialog dialog = new AppMessageDialog(
                resolveOwner(parent),
                MessageType.WARNING,
                title,
                message,
                null,
                true
        );
        dialog.setVisible(true);
        return dialog.confirmed;
    }

    /** 查找弹窗所属窗口。 */
    private static Window resolveOwner(Component parent) {
        if (parent instanceof Window window) {
            return window;
        }
        return parent == null ? null : SwingUtilities.getWindowAncestor(parent);
    }

    /** 创建弹窗内容。 */
    private JPanel createContent() {
        JPanel root = new JPanel(new BorderLayout(0, 16));
        root.setBackground(theme.background());
        root.setBorder(new EmptyBorder(20, 22, 18, 22));
        root.setPreferredSize(new Dimension(DIALOG_WIDTH, preferredHeight()));

        root.add(createHeader(), BorderLayout.NORTH);
        root.add(createMessageContent(), BorderLayout.CENTER);
        root.add(createFooter(), BorderLayout.SOUTH);
        return root;
    }

    /** 创建标题栏。 */
    private JPanel createHeader() {
        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setOpaque(false);

        JLabel icon = new JLabel(new MessageIcon(messageType, 24, typeColor()));
        icon.setPreferredSize(new Dimension(38, 38));
        icon.setHorizontalAlignment(SwingConstants.CENTER);
        icon.setOpaque(true);
        icon.setBackground(theme.controlBackground());
        icon.setBorder(new EmptyBorder(7, 7, 7, 7));
        header.add(icon, BorderLayout.WEST);

        JLabel title = new JLabel(dialogTitle);
        title.setFont(FONT_TITLE);
        title.setForeground(theme.textPrimary());
        header.add(title, BorderLayout.CENTER);

        installWindowDragging(header);
        installWindowDragging(title);
        return header;
    }

    /** 创建消息与异常详情区域。 */
    private JPanel createMessageContent() {
        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        JTextArea messageArea = createTextArea(message, FONT_NORMAL, theme.textPrimary());
        messageArea.setAlignmentX(LEFT_ALIGNMENT);
        content.add(messageArea);

        String throwableMessage = throwableMessage();
        if (throwableMessage != null) {
            content.add(Box.createVerticalStrut(12));
            RoundedPanel causeCard = new RoundedPanel(
                    theme.targetCard(),
                    theme.border(),
                    12
            );
            causeCard.setLayout(new BorderLayout());
            causeCard.setBorder(new EmptyBorder(9, 11, 9, 11));
            causeCard.setAlignmentX(LEFT_ALIGNMENT);
            causeCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 58));
            JTextArea cause = createTextArea(
                    throwableMessage,
                    FONT_SMALL,
                    theme.textSecondary()
            );
            causeCard.add(cause, BorderLayout.CENTER);
            content.add(causeCard);
        }

        if (throwable != null) {
            content.add(Box.createVerticalStrut(10));
            detailsContainer = createDetailsContainer();
            detailsContainer.setAlignmentX(LEFT_ALIGNMENT);
            detailsContainer.setVisible(false);
            content.add(detailsContainer);
        }

        return content;
    }

    /** 创建底部操作区。 */
    private JPanel createFooter() {
        JPanel footer = new JPanel(new BorderLayout(10, 0));
        footer.setOpaque(false);

        if (throwable != null) {
            detailsButton = createButton("查看详情", false);
            detailsButton.addActionListener(event -> toggleDetails());
            footer.add(detailsButton, BorderLayout.WEST);
        }

        JButton confirmButton = createButton(confirmation ? "删除" : "确定", true);
        confirmButton.putClientProperty("dialogConfirmButton", Boolean.TRUE);
        confirmButton.addActionListener(event -> {
            confirmed = confirmation;
            dispose();
        });

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        right.setOpaque(false);
        if (confirmation) {
            JButton cancelButton = createButton("取消", false);
            cancelButton.addActionListener(event -> dispose());
            right.add(cancelButton);
        }
        right.add(confirmButton);
        footer.add(right, BorderLayout.EAST);
        return footer;
    }

    /** 创建异常堆栈详情区域。 */
    private JPanel createDetailsContainer() {
        RoundedPanel panel = new RoundedPanel(
                theme.controlBackground(),
                theme.border(),
                12
        );
        panel.setLayout(new BorderLayout());
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));
        panel.setPreferredSize(new Dimension(376, 196));
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 200));

        JTextArea details = createTextArea(
                stackTraceText(throwable),
                new Font(Font.MONOSPACED, Font.PLAIN, 11),
                theme.textSecondary()
        );
        details.setEditable(false);
        details.setFocusable(true);
        details.setLineWrap(false);
        details.setWrapStyleWord(false);
        details.setSelectionColor(theme.accent());
        details.setSelectedTextColor(theme.textOnAccent());
        details.setCaretPosition(0);

        JScrollPane scrollPane = new JScrollPane(
                details,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        );
        scrollPane.setPreferredSize(new Dimension(360, 180));
        scrollPane.setMaximumSize(new Dimension(DIALOG_WIDTH - 60, 180));
        scrollPane.setBorder(null);
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setBackground(theme.controlBackground());
        ComponentStyleUtil.styleScrollPane(scrollPane);
        panel.add(scrollPane, BorderLayout.CENTER);
        return panel;
    }

    /** 展开或收起异常详情。 */
    private void toggleDetails() {
        boolean showDetails = !detailsContainer.isVisible();
        detailsContainer.setVisible(showDetails);
        detailsButton.setText(showDetails ? "收起详情" : "查看详情");
        getContentPane().setPreferredSize(new Dimension(
                DIALOG_WIDTH,
                preferredHeight() + (showDetails ? DETAILS_EXPANDED_HEIGHT : 0)
        ));
        setMinimumSize(new Dimension(DIALOG_WIDTH, 0));
        pack();
        if (!showDetails) {
            setMinimumSize(new Dimension(DIALOG_WIDTH, getHeight()));
        }
        setLocationRelativeTo(getOwner());
    }

    /** 创建只读文本区域。 */
    private JTextArea createTextArea(String text, Font font, Color color) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(false);
        area.setFont(font);
        area.setForeground(color);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setColumns(34);
        area.setBorder(null);
        return area;
    }

    /** 创建圆角操作按钮。 */
    private JButton createButton(String text, boolean primary) {
        Color background = primary ? typeColor() : theme.controlBackground();
        Color hover = primary ? primaryHoverColor() : theme.card();
        JButton button = new JButton(text);
        button.setFont(FONT_BOLD);
        button.setForeground(primary ? theme.textOnAccent() : theme.textPrimary());
        button.setBorder(new EmptyBorder(9, 18, 9, 18));
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        button.setRolloverEnabled(true);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setUI(new RoundedButtonUI(background, hover));
        return button;
    }

    /** 注册回车确认与退出键关闭操作。 */
    private void registerKeyboardActions() {
        JRootPane rootPane = getRootPane();
        rootPane.registerKeyboardAction(
                event -> dispose(),
                KeyStroke.getKeyStroke("ESCAPE"),
                JComponent.WHEN_IN_FOCUSED_WINDOW
        );
    }

    /** 在组件上启用窗口拖动。 */
    private void installWindowDragging(Component component) {
        MouseAdapter adapter = new MouseAdapter() {
            private Point pressedPoint;

            /** 仅记录左键按下时的相对位置，作为拖动提示窗口的起点。 */
            @Override
            public void mousePressed(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event)) pressedPoint = event.getPoint();
            }

            /** 左键释放后清除拖动起点，结束本次窗口移动。 */
            @Override
            public void mouseReleased(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event)) pressedPoint = null;
            }

            /** 保持初始按下偏移移动窗口，未按住左键或没有起点时不处理。 */
            @Override
            public void mouseDragged(MouseEvent event) {
                if (pressedPoint == null || (event.getModifiersEx() & MouseEvent.BUTTON1_DOWN_MASK) == 0) return;
                Point screenPoint = event.getLocationOnScreen();
                setLocation(
                        screenPoint.x - pressedPoint.x,
                        screenPoint.y - pressedPoint.y
                );
            }
        };
        component.addMouseListener(adapter);
        component.addMouseMotionListener(adapter);
    }

    /** 查找标记为确认操作的按钮。 */
    private JButton findConfirmButton(Container container) {
        for (Component component : container.getComponents()) {
            if (component instanceof JButton button
                    && Boolean.TRUE.equals(button.getClientProperty("dialogConfirmButton"))) {
                return button;
            }
            if (component instanceof Container child) {
                JButton result = findConfirmButton(child);
                if (result != null) return result;
            }
        }
        return null;
    }

    /** 获取消息类型对应的主题颜色。 */
    private Color typeColor() {
        if (confirmation) {
            return theme.danger();
        }
        return switch (messageType) {
            case ERROR -> theme.danger();
            case WARNING -> theme.accentHover();
            case INFO -> theme.accent();
        };
    }

    /** 获取主要按钮悬停颜色。 */
    private Color primaryHoverColor() {
        if (confirmation) {
            return theme.dangerHover();
        }
        return switch (messageType) {
            case ERROR -> theme.dangerHover();
            case WARNING, INFO -> theme.accentHover();
        };
    }

    /** 计算折叠状态下的弹窗高度。 */
    private int preferredHeight() {
        int lines = Math.max(1, (message.length() + 29) / 30);
        return Math.min(360, 155 + lines * 19 + (throwable == null ? 0 : 78));
    }

    /** 获取适合展示的异常原因。 */
    private String throwableMessage() {
        if (throwable == null) return null;
        String detail = throwable.getMessage();
        if (detail == null || detail.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return throwable.getClass().getSimpleName() + "：" + detail;
    }

    /** 将异常转换为完整堆栈文本。 */
    private static String stackTraceText(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    /** 规范化空消息。 */
    private static String normalizeMessage(String message) {
        return message == null || message.isBlank() ? "操作未能完成。" : message;
    }

    /** 规范化空标题。 */
    private static String normalizeTitle(String title, MessageType messageType) {
        return title == null || title.isBlank() ? messageType.getTitle() : title;
    }

    /** 通用圆角面板。 */
    private static final class RoundedPanel extends JPanel {
        private final Color fill;
        private final Color stroke;
        private final int radius;

        /** 保存提示卡片的填充色、边框色和圆角半径，背景由组件自行绘制。 */
        private RoundedPanel(Color fill, Color stroke, int radius) {
            this.fill = fill;
            this.stroke = stroke;
            this.radius = radius;
            setOpaque(false);
        }

        /** 绘制提示卡片的圆角背景及边框，再交给 Swing 绘制其内容。 */
        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);
            g2.setColor(stroke);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);
            g2.dispose();
            super.paintComponent(graphics);
        }
    }

    /** 圆角按钮绘制器。 */
    private static final class RoundedButtonUI extends DarkButtonUI {
        private final Color background;
        private final Color hover;

        /** 保存按钮常规和悬停背景，供交互状态绘制使用。 */
        private RoundedButtonUI(Color background, Color hover) {
            this.background = background;
            this.hover = hover;
        }

        /** 根据按钮悬停与按下状态绘制圆角背景，然后沿用按钮文字和图标绘制。 */
        @Override
        public void paint(Graphics graphics, JComponent component) {
            AbstractButton button = (AbstractButton) component;
            Color fill = button.getModel().isRollover() ? hover : background;
            if (button.getModel().isPressed()) fill = fill.darker();

            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, component.getWidth(), component.getHeight(), 12, 12);
            Color highlight = ThemeManager.getPalette().highlight();
            g2.setColor(new Color(highlight.getRed(), highlight.getGreen(), highlight.getBlue(), 18));
            g2.drawRoundRect(0, 0, component.getWidth() - 1, component.getHeight() - 1, 12, 12);
            g2.dispose();
            super.paint(graphics, component);
        }
    }

    /**
     * 消息类型图标。
     */
        private record MessageIcon(MessageType type, int size, Color color) implements Icon {
        /**
         * 绑定消息类型、图标尺寸和颜色，供提示标题区复用。
         */
        private MessageIcon {
        }

            /**
             * 按错误、警告或信息类型绘制对应的提示图形。
             */
            @Override
            public void paintIcon(Component component, Graphics graphics, int x, int y) {
                Graphics2D g2 = (Graphics2D) graphics.create();
                g2.translate(x, y);
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(color);
                g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int middle = size / 2;

                switch (type) {
                    case ERROR -> {
                        g2.drawOval(2, 2, size - 5, size - 5);
                        g2.drawLine(7, 7, size - 8, size - 8);
                        g2.drawLine(size - 8, 7, 7, size - 8);
                    }
                    case WARNING -> {
                        Polygon triangle = new Polygon(
                                new int[]{middle, size - 2, 2},
                                new int[]{2, size - 3, size - 3},
                                3
                        );
                        g2.drawPolygon(triangle);
                        g2.drawLine(middle, 8, middle, 14);
                        g2.fillOval(middle - 1, 17, 3, 3);
                    }
                    case INFO -> {
                        g2.drawOval(2, 2, size - 5, size - 5);
                        g2.fillOval(middle - 1, 6, 3, 3);
                        g2.drawLine(middle, 11, middle, 18);
                    }
                }
                g2.dispose();
            }

            /**
             * 返回图标使用的逻辑宽度。
             */
            @Override
            public int getIconWidth() {
                return size;
            }

            /**
             * 返回图标使用的逻辑高度。
             */
            @Override
            public int getIconHeight() {
                return size;
            }
        }

}
