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
    /** 组件样式通过静态入口复用，无需创建样式工具实例。 */
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
        button.setForeground(theme.textOnAccent());
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
        textField.setDisabledTextColor(theme.textMuted());
        textField.setCaretColor(theme.textPrimary());
        textField.setBackground(theme.targetCard());
        textField.setSelectionColor(theme.accent());
        textField.setSelectedTextColor(theme.textOnAccent());
        textField.setOpaque(true);
        updateTextFieldBorder(textField, theme, false);
        textField.addFocusListener(new FocusAdapter() {
            /** 字段取得焦点时更新主题边框，突出当前可编辑组件。 */
            @Override
            public void focusGained(FocusEvent event) {
                updateTextFieldBorder(textField, theme, true);
            }

            /** 字段失去焦点时恢复常规主题边框。 */
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
        comboBox.addPropertyChangeListener("enabled", event ->
                updateComboBoxBorder(comboBox, theme, comboBox.hasFocus()));
        comboBox.addFocusListener(new FocusAdapter() {
            /** 字段取得焦点时更新主题边框，突出当前可编辑组件。 */
            @Override
            public void focusGained(FocusEvent event) {
                updateComboBoxBorder(comboBox, theme, true);
            }

            /** 字段失去焦点时恢复常规主题边框。 */
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

    /** 为互斥单选项复用当前主题的边框、强调色和悬停反馈。 */
    public static void styleRadioButton(JRadioButton radioButton) {
        if (radioButton == null) {
            return;
        }
        ThemePalette theme = ThemeManager.getPalette();
        Icon icon = new AccentRadioButtonIcon(theme);
        radioButton.setForeground(theme.textPrimary());
        radioButton.setOpaque(false);
        radioButton.setContentAreaFilled(false);
        radioButton.setFocusPainted(false);
        radioButton.setBorderPainted(false);
        radioButton.setRolloverEnabled(true);
        radioButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        radioButton.setIcon(icon);
        radioButton.setSelectedIcon(icon);
        radioButton.setDisabledIcon(icon);
        radioButton.setDisabledSelectedIcon(icon);
        radioButton.setIconTextGap(8);
        radioButton.setBorder(new EmptyBorder(2, 0, 2, 0));
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
            textField.setSelectedTextColor(theme.textOnAccent());
            textField.setHorizontalAlignment(SwingConstants.RIGHT);
            textField.setFocusLostBehavior(JFormattedTextField.PERSIST);
            textField.setBorder(new EmptyBorder(6, 10, 6, 8));
            textField.addFocusListener(new FocusAdapter() {
                /** 字段取得焦点时更新主题边框，突出当前可编辑组件。 */
                @Override
                public void focusGained(FocusEvent event) {
                    updateSpinnerBorder(spinner, theme, true);
                }

                /** 字段失去焦点时恢复常规主题边框。 */
                @Override
                public void focusLost(FocusEvent event) {
                    updateSpinnerBorder(spinner, theme, false);
                }
            });
        }
    }

    /** 按焦点和启用状态更新文本框边框，保持原有内容内边距。 */
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

    /** 按焦点及禁用状态绘制下拉框边框，使禁用控件视觉弱化。 */
    private static void updateComboBoxBorder(
            JComboBox<?> comboBox,
            ThemePalette theme,
            boolean focused
    ) {
        comboBox.setBorder(BorderFactory.createLineBorder(
                !comboBox.isEnabled() ? theme.controlBackground()
                        : focused ? theme.accent() : theme.border(),
                1,
                true
        ));
    }

    /** 按焦点和启用状态同步数字微调框边框。 */
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

    /** 安装深色滚动条绘制并设置指定尺寸，保持滚动条背景透明。 */
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

        /** 保存当前调色板，供下拉框内容和箭头共同使用。 */
        private DarkComboBoxUI(ThemePalette theme) {
            this.theme = theme;
        }

        /** 创建与组件启用状态一致的主题方向按钮，并保留原有交互监听入口。 */
        @Override
        protected JButton createArrowButton() {
            JButton button = new JButton(new DirectionalArrowIcon(
                    theme,
                    SwingConstants.SOUTH
            ));
            button.setName("ComboBox.arrowButton");
            // 箭头自身按 enabled 状态使用主题色，不交给 LAF 生成灰度图标。
            button.setDisabledIcon(button.getIcon());
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

        /** 创建使用相同调色板和滚动区域样式的下拉列表。 */
        @Override
        protected ComboPopup createPopup() {
            return new DarkComboPopup(comboBox, theme);
        }

        /** 绘制下拉框当前值，禁用时显式使用深色背景和灰色文字，避免外观默认值覆盖。 */
        @Override
        public void paintCurrentValue(Graphics graphics, Rectangle bounds, boolean hasFocus) {
            if (comboBox.isEnabled()) {
                super.paintCurrentValue(graphics, bounds, hasFocus);
                return;
            }

            // BasicComboBoxUI 会在调用渲染器后覆盖禁用颜色；这些 LAF 默认值可能为白色。
            Component value = comboBox.getRenderer().getListCellRendererComponent(
                    listBox, comboBox.getSelectedItem(), -1, false, false);
            value.setFont(comboBox.getFont());
            value.setForeground(theme.textMuted());
            value.setBackground(theme.targetCard());
            Rectangle area = new Rectangle(bounds);
            if (padding != null) {
                area.x += padding.left;
                area.y += padding.top;
                area.width -= padding.left + padding.right;
                area.height -= padding.top + padding.bottom;
            }
            currentValuePane.paintComponent(graphics, value, comboBox,
                    area.x, area.y, area.width, area.height, value instanceof JPanel);
        }

        /** 用主题背景填充下拉框当前值区域，不依赖系统外观的禁用底色。 */
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

        /** 为下拉列表及其滚动区域安装同一套深色背景、文字和选中颜色。 */
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

        /** 保存下拉箭头按钮绘制使用的调色板。 */
        private ComboArrowButtonUI(ThemePalette theme) {
            this.theme = theme;
        }

        /** 绘制下拉箭头按钮的状态背景；箭头自身继续按组件启用状态选择主题颜色。 */
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
            graphics.setColor(button.isEnabled() ? theme.border() : theme.controlBackground());
            graphics.drawLine(0, 0, 0, component.getHeight());
            super.paint(graphics, component);
        }
    }

    /** 深色数值微调框的箭头按钮布局。 */
    private static class DarkSpinnerUI extends BasicSpinnerUI {

        private final ThemePalette theme;

        /** 保存数字微调框背景和上下方向按钮使用的调色板。 */
        private DarkSpinnerUI(ThemePalette theme) {
            this.theme = theme;
        }

        /** 创建微调框递增按钮，并安装 Swing 约定的递增监听。 */
        @Override
        protected Component createNextButton() {
            JButton button = createArrowButton(SwingConstants.NORTH, true);
            button.setName("Spinner.nextButton");
            installNextButtonListeners(button);
            return button;
        }

        /** 创建微调框递减按钮，并安装 Swing 约定的递减监听。 */
        @Override
        protected Component createPreviousButton() {
            JButton button = createArrowButton(SwingConstants.SOUTH, false);
            button.setName("Spinner.previousButton");
            installPreviousButtonListeners(button);
            return button;
        }

        /** 创建与组件启用状态一致的主题方向按钮，并保留原有交互监听入口。 */
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

        /** 保存调色板及按钮所在的上下半区，供边界和交互反馈绘制使用。 */
        private SpinnerArrowButtonUI(ThemePalette theme, boolean upperButton) {
            this.theme = theme;
            this.upperButton = upperButton;
        }

        /** 绘制微调框上下箭头按钮，禁用或按下时使用对应的弱化或反馈颜色。 */
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

    /** 深色主题单选圆点，禁用时保留可辨认的状态。 */
    private record AccentRadioButtonIcon(ThemePalette theme) implements Icon {
        private static final int SIZE = 18;

        /** 依据组件启用及选择状态绘制主题图标，不依赖系统生成的灰度禁用图片。 */
        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            AbstractButton button = (AbstractButton) component;
            boolean enabled = button.isEnabled();
            boolean emphasized = enabled && (button.isSelected() || button.hasFocus()
                    || button.getModel().isRollover());
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.translate(x, y);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(theme.targetCard());
            g2.fillOval(1, 1, SIZE - 2, SIZE - 2);
            g2.setColor(emphasized ? theme.accent() : theme.border());
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawOval(1, 1, SIZE - 3, SIZE - 3);
            if (button.isSelected()) {
                g2.setColor(enabled ? theme.accent() : theme.textMuted());
                g2.fillOval(5, 5, 8, 8);
            }
            g2.dispose();
        }

        /** 返回图标使用的固定逻辑宽度。 */
        @Override
        public int getIconWidth() {
            return SIZE;
        }

        /** 返回图标使用的固定逻辑高度。 */
        @Override
        public int getIconHeight() {
            return SIZE;
        }
    }

    /** 深色主题复选框图标。 */
    private record AccentCheckBoxIcon(ThemePalette theme) implements Icon {

        private static final int SIZE = 18;

        /** 依据组件启用及选择状态绘制主题图标，不依赖系统生成的灰度禁用图片。 */
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
                g2.setColor(theme.textOnAccent());
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

        /** 返回图标使用的固定逻辑宽度。 */
        @Override
        public int getIconWidth() {
            return SIZE;
        }

        /** 返回图标使用的固定逻辑高度。 */
        @Override
        public int getIconHeight() {
            return SIZE;
        }
    }

    /** 深色扁平滚动条。 */
    private static class DarkScrollBarUI extends BasicScrollBarUI {

        private final ThemePalette theme;

        /** 保存滚动条轨道、滑块和方向按钮使用的调色板。 */
        private DarkScrollBarUI(ThemePalette theme) {
            this.theme = theme;
        }

        /** 把滚动条轨道及滑块颜色统一设置为当前深色调色板。 */
        @Override
        protected void configureScrollBarColors() {
            trackColor = theme.background();
            thumbColor = theme.border();
        }

        /** 创建滚动条减小方向按钮，沿用指定方向的箭头。 */
        @Override
        protected JButton createDecreaseButton(int orientation) {
            return createScrollButton(orientation);
        }

        /** 创建滚动条增大方向按钮，沿用指定方向的箭头。 */
        @Override
        protected JButton createIncreaseButton(int orientation) {
            return createScrollButton(orientation);
        }

        /** 建立透明且不抢焦点的滚动按钮，安装统一的深色箭头绘制。 */
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

        /** 绘制滚动条轨道背景，不改变滑块位置或滚动范围。 */
        @Override
        protected void paintTrack(Graphics graphics, JComponent component, Rectangle bounds) {
            graphics.setColor(theme.background());
            graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
        }

        /** 按滚动条状态绘制圆角滑块，使用独立图形副本保留后续绘制环境。 */
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

        /** 保存滚动条端部方向按钮使用的调色板。 */
        private ScrollArrowButtonUI(ThemePalette theme) {
            this.theme = theme;
        }

        /** 绘制滚动条端部方向按钮的状态背景，并保留主题箭头绘制。 */
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

        /** 保存按钮使用的调色板和常规、悬停背景颜色。 */
        private RoundedButtonUI(
                ThemePalette theme,
                Color background,
                Color hoverBackground
        ) {
            this.theme = theme;
            this.background = background;
            this.hoverBackground = hoverBackground;
        }

        /** 根据组件启用、悬停和按下状态绘制主题按钮背景，再沿用按钮文字及图标绘制。 */
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

        /** 依据组件启用及选择状态绘制主题图标，不依赖系统生成的灰度禁用图片。 */
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

        /** 按指定方向绘制箭头线段，供滚动条、下拉框和微调框共用。 */
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

        /** 返回图标使用的固定逻辑宽度。 */
        @Override
        public int getIconWidth() {
            return WIDTH;
        }

        /** 返回图标使用的固定逻辑高度。 */
        @Override
        public int getIconHeight() {
            return HEIGHT;
        }
    }
}
