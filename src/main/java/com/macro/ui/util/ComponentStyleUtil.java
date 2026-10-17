package com.macro.ui.util;

import com.macro.ui.theme.ThemeManager;
import com.macro.ui.theme.ThemePalette;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;

import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicComboPopup;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.plaf.basic.BasicSpinnerUI;
import javax.swing.plaf.basic.ComboPopup;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;

/** Swing 公共组件样式工具。 */
public final class ComponentStyleUtil {

    private static final int SCROLL_BAR_SIZE = 12;
    private ComponentStyleUtil() {
    }

    /**
     * 为滚动区域应用当前主题的深色滚动条样式。
     *
     * <p>此方法只调整外观，不改变各页面设置的滚动增量和滚动策略。</p>
     */
    public static void styleScrollPane(JScrollPane scrollPane) {
        if (scrollPane == null) {
            return;
        }

        ThemePalette theme = ThemeManager.getPalette();
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setBackground(theme.background());

        styleScrollBar(
                scrollPane.getVerticalScrollBar(),
                theme,
                new Dimension(SCROLL_BAR_SIZE, 0)
        );
        styleScrollBar(
                scrollPane.getHorizontalScrollBar(),
                theme,
                new Dimension(0, SCROLL_BAR_SIZE)
        );
    }

    /** 为主要操作按钮应用当前主题的强调色圆角样式。 */
    public static void stylePrimaryButton(JButton button) {
        if (button == null) {
            return;
        }
        ThemePalette theme = ThemeManager.getPalette();
        styleButton(button, theme, theme.accent(), theme.accentHover());
    }

    /** 为普通操作按钮应用当前主题的深色圆角样式。 */
    public static void styleSecondaryButton(JButton button) {
        if (button == null) {
            return;
        }
        ThemePalette theme = ThemeManager.getPalette();
        styleButton(button, theme, theme.controlBackground(), theme.card());
    }

    /** 配置圆角按钮的公共外观。 */
    private static void styleButton(
            JButton button,
            ThemePalette theme,
            Color background,
            Color hoverBackground
    ) {
        button.setForeground(theme.textPrimary());
        button.setBackground(background);
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setBorder(new EmptyBorder(9, 14, 9, 14));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setIconTextGap(8);
        button.setRolloverEnabled(true);
        button.setUI(new RoundedButtonUI(theme, background, hoverBackground));
    }

    /** 为单行文本输入框应用深色主题及聚焦边框。 */
    public static void styleTextField(JTextField textField) {
        if (textField == null) {
            return;
        }

        ThemePalette theme = ThemeManager.getPalette();
        textField.setForeground(theme.textPrimary());
        textField.setCaretColor(theme.textPrimary());
        textField.setBackground(theme.targetCard());
        textField.setSelectionColor(theme.accent());
        textField.setSelectedTextColor(Color.WHITE);
        textField.setOpaque(true);
        updateTextFieldBorder(textField, theme, false);
        textField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent event) {
                updateTextFieldBorder(textField, theme, true);
            }

            @Override
            public void focusLost(FocusEvent event) {
                updateTextFieldBorder(textField, theme, false);
            }
        });
    }

    /** 为下拉框应用深色主题、扁平箭头及聚焦边框。 */
    public static void styleComboBox(JComboBox<?> comboBox) {
        if (comboBox == null) {
            return;
        }

        ThemePalette theme = ThemeManager.getPalette();
        comboBox.setForeground(theme.textPrimary());
        comboBox.setBackground(theme.targetCard());
        comboBox.setOpaque(true);
        comboBox.setUI(new DarkComboBoxUI(theme));
        updateComboBoxBorder(comboBox, theme, false);
        comboBox.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent event) {
                updateComboBoxBorder(comboBox, theme, true);
            }

            @Override
            public void focusLost(FocusEvent event) {
                updateComboBoxBorder(comboBox, theme, false);
            }
        });
    }

    /** 为复选框应用强调色勾选标记和深色边框。 */
    public static void styleCheckBox(JCheckBox checkBox) {
        if (checkBox == null) {
            return;
        }

        ThemePalette theme = ThemeManager.getPalette();
        Icon icon = new AccentCheckBoxIcon(theme);
        checkBox.setForeground(theme.textPrimary());
        checkBox.setOpaque(false);
        checkBox.setFocusPainted(false);
        checkBox.setBorderPainted(false);
        checkBox.setRolloverEnabled(true);
        checkBox.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        checkBox.setIcon(icon);
        checkBox.setSelectedIcon(icon);
        checkBox.setDisabledIcon(icon);
        checkBox.setDisabledSelectedIcon(icon);
        checkBox.setIconTextGap(10);
        checkBox.setBorder(new EmptyBorder(2, 0, 2, 0));
    }

    /** 为数值微调框应用深色输入区、聚焦边框和扁平箭头按钮。 */
    public static void styleSpinner(JSpinner spinner) {
        if (spinner == null) {
            return;
        }

        ThemePalette theme = ThemeManager.getPalette();
        spinner.setUI(new DarkSpinnerUI(theme));
        spinner.setForeground(theme.textPrimary());
        spinner.setBackground(theme.targetCard());
        spinner.setOpaque(true);
        updateSpinnerBorder(spinner, theme, false);

        if (spinner.getEditor() instanceof JSpinner.NumberEditor numberEditor) {
            numberEditor.getFormat().setGroupingUsed(false);
        }

        if (spinner.getEditor() instanceof JSpinner.DefaultEditor defaultEditor) {
            JFormattedTextField textField = defaultEditor.getTextField();
            textField.setFont(spinner.getFont());
            textField.setForeground(theme.textPrimary());
            textField.setDisabledTextColor(theme.textMuted());
            textField.setCaretColor(theme.textPrimary());
            textField.setBackground(theme.targetCard());
            textField.setSelectionColor(theme.accent());
            textField.setSelectedTextColor(Color.WHITE);
            textField.setHorizontalAlignment(SwingConstants.RIGHT);
            textField.setFocusLostBehavior(JFormattedTextField.PERSIST);
            textField.setBorder(new EmptyBorder(6, 10, 6, 8));
            textField.addFocusListener(new FocusAdapter() {
                @Override
                public void focusGained(FocusEvent event) {
                    updateSpinnerBorder(spinner, theme, true);
                }

                @Override
                public void focusLost(FocusEvent event) {
                    updateSpinnerBorder(spinner, theme, false);
                }
            });
        }
    }

    private static void updateTextFieldBorder(
            JTextField textField,
            ThemePalette theme,
            boolean focused
    ) {
        textField.setBorder(new CompoundBorder(
                BorderFactory.createLineBorder(
                        focused ? theme.accent() : theme.border(),
                        1,
                        true
                ),
                new EmptyBorder(7, 9, 7, 9)
        ));
    }

    private static void updateComboBoxBorder(
            JComboBox<?> comboBox,
            ThemePalette theme,
            boolean focused
    ) {
        comboBox.setBorder(BorderFactory.createLineBorder(
                focused ? theme.accent() : theme.border(),
                1,
                true
        ));
    }

    private static void updateSpinnerBorder(
            JSpinner spinner,
            ThemePalette theme,
            boolean focused
    ) {
        spinner.setBorder(BorderFactory.createLineBorder(
                focused ? theme.accent() : theme.border(),
                1,
                true
        ));
    }

    private static void styleScrollBar(
            JScrollBar scrollBar,
            ThemePalette theme,
            Dimension preferredSize
    ) {
        if (scrollBar == null) {
            return;
        }
        scrollBar.setUI(new DarkScrollBarUI(theme));
        scrollBar.setBackground(theme.background());
        scrollBar.setOpaque(false);
        scrollBar.setPreferredSize(preferredSize);
    }

    /** 深色扁平下拉框。 */
    private static class DarkComboBoxUI extends BasicComboBoxUI {

        private final ThemePalette theme;

        private DarkComboBoxUI(ThemePalette theme) {
            this.theme = theme;
        }

        @Override
        protected JButton createArrowButton() {
            JButton button = new JButton(new DirectionalArrowIcon(
                    theme,
                    SwingConstants.SOUTH
            ));
            button.setName("ComboBox.arrowButton");
            button.setUI(new ComboArrowButtonUI(theme));
            button.setOpaque(false);
            button.setContentAreaFilled(false);
            button.setBorderPainted(false);
            button.setFocusPainted(false);
            button.setFocusable(false);
            button.setRolloverEnabled(true);
            button.setPreferredSize(new Dimension(30, 0));
            return button;
        }

        @Override
        protected ComboPopup createPopup() {
            return new DarkComboPopup(comboBox, theme);
        }

        @Override
        public void paintCurrentValueBackground(
                Graphics graphics,
                Rectangle bounds,
                boolean hasFocus
        ) {
            graphics.setColor(theme.targetCard());
            graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
        }
    }

    /** 带深色滚动区域的下拉菜单。 */
    private static class DarkComboPopup extends BasicComboPopup {

        private DarkComboPopup(JComboBox<Object> comboBox, ThemePalette theme) {
            super(comboBox);
            JList<Object> list = getList();
            list.setBackground(theme.targetCard());
            list.setForeground(theme.textPrimary());
            list.setSelectionBackground(theme.controlBackground());
            list.setSelectionForeground(theme.textPrimary());
            list.setBorder(new EmptyBorder(4, 4, 4, 4));
            scroller.setBackground(theme.targetCard());
            scroller.getViewport().setBackground(theme.targetCard());
            styleScrollPane(scroller);
            setBackground(theme.targetCard());
            setBorder(BorderFactory.createLineBorder(theme.border(), 1, true));
        }
    }

    /** 下拉框箭头按钮的扁平绘制。 */
    private static class ComboArrowButtonUI extends DarkButtonUI {

        private final ThemePalette theme;

        private ComboArrowButtonUI(ThemePalette theme) {
            this.theme = theme;
        }

        @Override
        public void paint(Graphics graphics, JComponent component) {
            AbstractButton button = (AbstractButton) component;
            Color fill = theme.controlBackground();
            if (!button.isEnabled()) {
                fill = theme.targetCard();
            } else if (button.getModel().isPressed()) {
                fill = theme.border();
            } else if (button.getModel().isRollover()) {
                fill = theme.card();
            }

            graphics.setColor(fill);
            graphics.fillRect(0, 0, component.getWidth(), component.getHeight());
            graphics.setColor(theme.border());
            graphics.drawLine(0, 0, 0, component.getHeight());
            super.paint(graphics, component);
        }
    }

    /** 深色数值微调框的箭头按钮布局。 */
    private static class DarkSpinnerUI extends BasicSpinnerUI {

        private final ThemePalette theme;

        private DarkSpinnerUI(ThemePalette theme) {
            this.theme = theme;
        }

        @Override
        protected Component createNextButton() {
            JButton button = createArrowButton(SwingConstants.NORTH, true);
            button.setName("Spinner.nextButton");
            installNextButtonListeners(button);
            return button;
        }

        @Override
        protected Component createPreviousButton() {
            JButton button = createArrowButton(SwingConstants.SOUTH, false);
            button.setName("Spinner.previousButton");
            installPreviousButtonListeners(button);
            return button;
        }

        private JButton createArrowButton(int direction, boolean upperButton) {
            JButton button = new JButton(new DirectionalArrowIcon(theme, direction));
            button.setUI(new SpinnerArrowButtonUI(theme, upperButton));
            button.setForeground(theme.textSecondary());
            button.setBackground(theme.controlBackground());
            button.setOpaque(false);
            button.setContentAreaFilled(false);
            button.setBorderPainted(false);
            button.setFocusPainted(false);
            button.setFocusable(false);
            button.setRolloverEnabled(true);
            button.setPreferredSize(new Dimension(24, 16));
            return button;
        }
    }

    /** 数值微调框箭头按钮的扁平绘制。 */
    private static class SpinnerArrowButtonUI extends DarkButtonUI {

        private final ThemePalette theme;
        private final boolean upperButton;

        private SpinnerArrowButtonUI(ThemePalette theme, boolean upperButton) {
            this.theme = theme;
            this.upperButton = upperButton;
        }

        @Override
        public void paint(Graphics graphics, JComponent component) {
            AbstractButton button = (AbstractButton) component;
            Color fill = theme.controlBackground();
            if (!button.isEnabled()) {
                fill = theme.targetCard();
            } else if (button.getModel().isPressed()) {
                fill = theme.border();
            } else if (button.getModel().isRollover()) {
                fill = theme.card();
            }

            graphics.setColor(fill);
            graphics.fillRect(0, 0, component.getWidth(), component.getHeight());
            graphics.setColor(theme.border());
            graphics.drawLine(0, 0, 0, component.getHeight());
            if (upperButton) {
                graphics.drawLine(
                        0,
                        component.getHeight() - 1,
                        component.getWidth(),
                        component.getHeight() - 1
                );
            }
            super.paint(graphics, component);
        }
    }

    /** 深色主题复选框图标。 */
    private record AccentCheckBoxIcon(ThemePalette theme) implements Icon {

        private static final int SIZE = 18;

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            AbstractButton button = (AbstractButton) component;
            ButtonModel model = button.getModel();
            boolean rollover = model.isRollover() && button.isEnabled();

            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.translate(x, y);
            g2.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
            );

            if (button.isSelected()) {
                g2.setColor(rollover ? theme.accentHover() : theme.accent());
                g2.fillRoundRect(0, 0, SIZE, SIZE, 5, 5);
                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(
                        2f,
                        BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND
                ));
                g2.drawLine(4, 9, 7, 12);
                g2.drawLine(7, 12, 14, 5);
            } else {
                g2.setColor(rollover
                        ? theme.controlBackground()
                        : theme.targetCard());
                g2.fillRoundRect(0, 0, SIZE, SIZE, 5, 5);
                g2.setColor(button.hasFocus() ? theme.accent() : theme.border());
                g2.drawRoundRect(0, 0, SIZE - 1, SIZE - 1, 5, 5);
            }

            if (!button.isEnabled()) {
                g2.setColor(new Color(
                        theme.background().getRed(),
                        theme.background().getGreen(),
                        theme.background().getBlue(),
                        110
                ));
                g2.fillRoundRect(0, 0, SIZE, SIZE, 5, 5);
            }
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }
    }

    /** 深色扁平滚动条。 */
    private static class DarkScrollBarUI extends BasicScrollBarUI {

        private final ThemePalette theme;

        private DarkScrollBarUI(ThemePalette theme) {
            this.theme = theme;
        }

        @Override
        protected void configureScrollBarColors() {
            trackColor = theme.background();
            thumbColor = theme.border();
        }

        @Override
        protected JButton createDecreaseButton(int orientation) {
            return createScrollButton(orientation);
        }

        @Override
        protected JButton createIncreaseButton(int orientation) {
            return createScrollButton(orientation);
        }

        private JButton createScrollButton(int orientation) {
            JButton button = new JButton(new DirectionalArrowIcon(theme, orientation));
            button.setUI(new ScrollArrowButtonUI(theme));
            button.setForeground(theme.textSecondary());
            button.setOpaque(false);
            button.setContentAreaFilled(false);
            button.setBorderPainted(false);
            button.setFocusPainted(false);
            button.setFocusable(false);
            button.setRolloverEnabled(true);
            Dimension size = new Dimension(SCROLL_BAR_SIZE, SCROLL_BAR_SIZE);
            button.setPreferredSize(size);
            button.setMinimumSize(size);
            button.setMaximumSize(size);
            return button;
        }

        @Override
        protected void paintTrack(Graphics graphics, JComponent component, Rectangle bounds) {
            graphics.setColor(theme.background());
            graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
        }

        @Override
        protected void paintThumb(Graphics graphics, JComponent component, Rectangle bounds) {
            if (!component.isEnabled() || bounds.isEmpty()) {
                return;
            }

            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(isThumbRollover() ? theme.textMuted() : theme.border());
            int inset = 2;
            g2.fillRoundRect(
                    bounds.x + inset,
                    bounds.y + inset,
                    Math.max(1, bounds.width - inset * 2),
                    Math.max(1, bounds.height - inset * 2),
                    7,
                    7
            );
            g2.dispose();
        }
    }

    /** 滚动条箭头按钮的扁平绘制。 */
    private static class ScrollArrowButtonUI extends DarkButtonUI {

        private final ThemePalette theme;

        private ScrollArrowButtonUI(ThemePalette theme) {
            this.theme = theme;
        }

        @Override
        public void paint(Graphics graphics, JComponent component) {
            AbstractButton button = (AbstractButton) component;
            Color fill = button.getModel().isRollover()
                    ? theme.controlBackground()
                    : theme.background();
            if (button.getModel().isPressed()) {
                fill = theme.border();
            }

            graphics.setColor(fill);
            graphics.fillRect(0, 0, component.getWidth(), component.getHeight());
            super.paint(graphics, component);
        }
    }

    /** 使用主题颜色绘制的公共圆角按钮。 */
    private static class RoundedButtonUI extends DarkButtonUI {

        private final ThemePalette theme;
        private final Color background;
        private final Color hoverBackground;

        private RoundedButtonUI(
                ThemePalette theme,
                Color background,
                Color hoverBackground
        ) {
            this.theme = theme;
            this.background = background;
            this.hoverBackground = hoverBackground;
        }

        @Override
        public void paint(Graphics graphics, JComponent component) {
            AbstractButton button = (AbstractButton) component;
            Color fill = background;
            if (!button.isEnabled()) {
                fill = theme.targetCard();
            } else if (button.getModel().isPressed()) {
                fill = theme.border();
            } else if (button.getModel().isRollover()) {
                fill = hoverBackground;
            }

            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
            );
            int arc = Math.min(14, Math.max(8, component.getHeight() - 2));
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, component.getWidth(), component.getHeight(), arc, arc);
            g2.setColor(theme.border());
            g2.drawRoundRect(
                    0,
                    0,
                    component.getWidth() - 1,
                    component.getHeight() - 1,
                    arc,
                    arc
            );
            g2.dispose();
            super.paint(graphics, component);
        }
    }

    /** 可随悬停状态变色的方向箭头。 */
    private record DirectionalArrowIcon(ThemePalette theme, int direction) implements Icon {

        private static final int WIDTH = 8;
        private static final int HEIGHT = 5;

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.translate(x, y);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            Color arrowColor = theme.textSecondary();
            if (component instanceof AbstractButton button) {
                if (!button.isEnabled()) {
                    arrowColor = theme.textMuted();
                } else if (button.getModel().isRollover()) {
                    arrowColor = theme.accentHover();
                }
            }
            g2.setColor(arrowColor);
            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND));
            paintArrow(g2);
            g2.dispose();
        }

        private void paintArrow(Graphics2D g2) {
            switch (direction) {
                case SwingConstants.NORTH -> {
                    g2.drawLine(1, HEIGHT - 1, WIDTH / 2, 1);
                    g2.drawLine(WIDTH / 2, 1, WIDTH - 1, HEIGHT - 1);
                }
                case SwingConstants.WEST -> {
                    g2.drawLine(WIDTH - 2, 0, 2, HEIGHT / 2);
                    g2.drawLine(2, HEIGHT / 2, WIDTH - 2, HEIGHT);
                }
                case SwingConstants.EAST -> {
                    g2.drawLine(1, 0, WIDTH - 3, HEIGHT / 2);
                    g2.drawLine(WIDTH - 3, HEIGHT / 2, 1, HEIGHT);
                }
                default -> {
                    g2.drawLine(1, 1, WIDTH / 2, HEIGHT - 1);
                    g2.drawLine(WIDTH / 2, HEIGHT - 1, WIDTH - 1, 1);
                }
            }
        }

        @Override
        public int getIconWidth() {
            return WIDTH;
        }

        @Override
        public int getIconHeight() {
            return HEIGHT;
        }
    }
}
