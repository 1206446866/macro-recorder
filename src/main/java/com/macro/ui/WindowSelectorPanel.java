package com.macro.ui;

import com.macro.constant.UiConstants;

import com.macro.model.WindowInfo;
import com.macro.ui.theme.ThemeManager;
import com.macro.ui.theme.ThemePalette;
import lombok.Getter;
import lombok.Setter;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import com.macro.ui.util.DarkButtonUI;
import java.awt.*;

/**
 * 目标窗口选择页面。
 *
 * <p>负责展示当前识别到的窗口信息，不负责窗口识别。</p>
 */
public class WindowSelectorPanel extends JPanel {

    private static final ThemePalette THEME = ThemeManager.getPalette();
    private static final Font FONT_NORMAL = new Font(UiConstants.DEFAULT_FONT_FAMILY, Font.PLAIN, 13);
    private static final Font FONT_SMALL = new Font(UiConstants.DEFAULT_FONT_FAMILY, Font.PLAIN, 12);
    private static final Font FONT_BOLD = new Font(UiConstants.DEFAULT_FONT_FAMILY, Font.BOLD, 13);
    private static final Font FONT_TITLE = new Font(UiConstants.DEFAULT_FONT_FAMILY, Font.BOLD, 27);

    private final JLabel titleValue = new JLabel("未选择");
    private final JLabel processValue = new JLabel("未选择");
    private final JLabel classValue = new JLabel("未选择");
    private final JLabel processIdValue = new JLabel("未选择");
    private final JLabel statusLabel = new JLabel("等待选择窗口");
    private final JLabel statusHintLabel = new JLabel("将鼠标移动到目标窗口，然后单击选中");
    private final JButton reselectButton = new JButton("重新选择");
    private final JButton confirmButton = new JButton("确认选择");
    private SelectionCard infoCard;

    /** 当前选择的窗口。 */
    @Getter
    private WindowInfo selectedWindow;

    /** 返回页面时执行的操作。 */
    @Setter
    private Runnable onBack;

    /** 重新开始选择时执行的操作。 */
    @Setter
    private Runnable onReselect;

    /** 确认选择时执行的操作。 */
    @Setter
    private Runnable onConfirm;

    /** 创建目标窗口选择页，绑定导航和确认按钮，并以未选中状态初始化信息卡。 */
    public WindowSelectorPanel() {
        setLayout(new BorderLayout(0, 18));
        setBackground(THEME.background());
        setBorder(new EmptyBorder(24, 28, 20, 28));
        initLayout();
        initEvents();
        updateWindowInfo(null);
    }

    /** 初始化页面布局。 */
    private void initLayout() {
        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));

        JButton backButton = new JButton("返回");
        styleButton(backButton, false);
        backButton.setIcon(new SelectorIcon(IconType.BACK, 16, THEME.textPrimary()));
        backButton.setAlignmentX(LEFT_ALIGNMENT);
        backButton.addActionListener(event -> {
            if (onBack != null) onBack.run();
        });

        JLabel titleLabel = createLabel("选择录制窗口", FONT_TITLE, THEME.textPrimary());
        JLabel subtitleLabel = createLabel(
                "指定宏录制和播放时使用的目标窗口",
                FONT_NORMAL,
                THEME.textSecondary()
        );
        titleLabel.setAlignmentX(LEFT_ALIGNMENT);
        subtitleLabel.setAlignmentX(LEFT_ALIGNMENT);

        heading.add(backButton);
        heading.add(Box.createVerticalStrut(18));
        heading.add(titleLabel);
        heading.add(Box.createVerticalStrut(6));
        heading.add(subtitleLabel);
        add(heading, BorderLayout.NORTH);

        JPanel content = new JPanel(new GridBagLayout());
        content.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.NORTH;
        content.add(createSelectionGuide(), gbc);

        gbc.gridy++;
        gbc.insets = new Insets(14, 0, 0, 0);
        infoCard = createInfoCard();
        content.add(infoCard, gbc);

        gbc.gridy++;
        gbc.weighty = 1;
        content.add(Box.createVerticalGlue(), gbc);
        add(content, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(12, 0));
        footer.setOpaque(false);
        footer.add(createLabel(
                "选中后可随时在主页面清除或重新指定",
                FONT_SMALL,
                THEME.textMuted()
        ), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        buttons.setOpaque(false);
        styleButton(reselectButton, false);
        reselectButton.setIcon(new SelectorIcon(IconType.RESELECT, 16, THEME.textPrimary()));
        styleButton(confirmButton, true);
        confirmButton.setIcon(new SelectorIcon(IconType.CONFIRM, 16, THEME.textOnAccent()));
        buttons.add(reselectButton);
        buttons.add(confirmButton);
        footer.add(buttons, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);
    }

    /** 创建窗口选择提示卡片。 */
    private JPanel createSelectionGuide() {
        RoundedPanel guide = new RoundedPanel(THEME.targetCard(), THEME.border(), 16);
        guide.setLayout(new BorderLayout(14, 0));
        guide.setBorder(new EmptyBorder(15, 17, 15, 17));

        JPanel iconHolder = new JPanel(new GridBagLayout()) {
            /** 绘制选择提示图标背后的半透明强调圆，保留父面板的后续绘制。 */
            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g2 = (Graphics2D) graphics.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(
                        THEME.accent().getRed(),
                        THEME.accent().getGreen(),
                        THEME.accent().getBlue(),
                        34
                ));
                g2.fillOval(0, 0, getWidth(), getHeight());
                g2.dispose();
                super.paintComponent(graphics);
            }
        };
        iconHolder.setOpaque(false);
        iconHolder.setPreferredSize(new Dimension(44, 44));
        iconHolder.add(new JLabel(new SelectorIcon(IconType.TARGET, 24, THEME.accent())));
        guide.add(iconHolder, BorderLayout.WEST);

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        statusLabel.setFont(FONT_BOLD);
        statusLabel.setForeground(THEME.textPrimary());
        statusHintLabel.setFont(FONT_SMALL);
        statusHintLabel.setForeground(THEME.textSecondary());
        statusLabel.setAlignmentX(LEFT_ALIGNMENT);
        statusHintLabel.setAlignmentX(LEFT_ALIGNMENT);
        text.add(statusLabel);
        text.add(Box.createVerticalStrut(4));
        text.add(statusHintLabel);
        guide.add(text, BorderLayout.CENTER);

        JLabel liveBadge = createLabel("实时识别", FONT_SMALL, THEME.accent());
        liveBadge.setOpaque(true);
        liveBadge.setBackground(THEME.controlBackground());
        liveBadge.setBorder(new EmptyBorder(5, 9, 5, 9));
        guide.add(liveBadge, BorderLayout.EAST);
        return guide;
    }

    /** 创建窗口信息卡片。 */
    private SelectionCard createInfoCard() {
        SelectionCard card = new SelectionCard();
        card.setLayout(new GridBagLayout());
        card.setBorder(new EmptyBorder(17, 18, 17, 18));

        GridBagConstraints headerGbc = new GridBagConstraints();
        headerGbc.gridx = 0;
        headerGbc.gridy = 0;
        headerGbc.gridwidth = 2;
        headerGbc.weightx = 1;
        headerGbc.fill = GridBagConstraints.HORIZONTAL;
        headerGbc.anchor = GridBagConstraints.WEST;
        headerGbc.insets = new Insets(0, 0, 9, 0);

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(createLabel("窗口信息", FONT_BOLD, THEME.textPrimary()), BorderLayout.WEST);
        header.add(createLabel("当前指向", FONT_SMALL, THEME.textMuted()), BorderLayout.EAST);
        card.add(header, headerGbc);

        addInfoRow(card, 1, "窗口标题", titleValue);
        addInfoRow(card, 2, "进程名称", processValue);
        addInfoRow(card, 3, "窗口类名", classValue);
        addInfoRow(card, 4, "进程编号", processIdValue);
        return card;
    }

    /** 添加窗口信息行。 */
    private void addInfoRow(JPanel panel, int row, String labelText, JLabel valueLabel) {
        GridBagConstraints labelGbc = new GridBagConstraints();
        labelGbc.gridx = 0;
        labelGbc.gridy = row;
        labelGbc.weightx = 0;
        labelGbc.anchor = GridBagConstraints.WEST;
        labelGbc.insets = new Insets(7, 0, 7, 20);

        JLabel label = createLabel(labelText, FONT_SMALL, THEME.textMuted());
        label.setPreferredSize(new Dimension(62, 28));
        panel.add(label, labelGbc);

        GridBagConstraints valueGbc = new GridBagConstraints();
        valueGbc.gridx = 1;
        valueGbc.gridy = row;
        valueGbc.weightx = 1;
        valueGbc.fill = GridBagConstraints.HORIZONTAL;
        valueGbc.anchor = GridBagConstraints.WEST;
        valueGbc.insets = new Insets(5, 0, 5, 0);

        valueLabel.setFont(FONT_BOLD);
        valueLabel.setForeground(THEME.textPrimary());
        valueLabel.setOpaque(true);
        valueLabel.setBackground(THEME.controlBackground());
        valueLabel.setBorder(new EmptyBorder(7, 10, 7, 10));
        panel.add(valueLabel, valueGbc);
    }

    /** 初始化页面事件。 */
    private void initEvents() {
        reselectButton.addActionListener(event -> {
            if (onReselect != null) onReselect.run();
        });
        confirmButton.addActionListener(event -> {
            if (onConfirm != null) onConfirm.run();
        });
    }

    /**
     * 更新当前窗口信息。
     *
     * @param windowInfo 窗口信息
     */
    public void updateWindowInfo(WindowInfo windowInfo) {
        selectedWindow = windowInfo;
        boolean selected = windowInfo != null;

        if (!selected) {
            updateValue(titleValue, "未选择");
            updateValue(processValue, "未选择");
            updateValue(classValue, "未选择");
            updateValue(processIdValue, "未选择");
            statusLabel.setText("等待选择窗口");
            statusLabel.setForeground(THEME.textPrimary());
            statusHintLabel.setText("将鼠标移动到目标窗口，然后单击选中");
        } else {
            updateValue(titleValue, emptyToDefault(windowInfo.getWindowTitle()));
            updateValue(processValue, emptyToDefault(windowInfo.getProcessName()));
            updateValue(classValue, emptyToDefault(windowInfo.getWindowClass()));
            updateValue(processIdValue, String.valueOf(windowInfo.getProcessId()));
            statusLabel.setText("已识别目标窗口");
            statusLabel.setForeground(THEME.success());
            statusHintLabel.setText("确认信息无误后即可使用该窗口开始录制");
        }

        confirmButton.setEnabled(selected);
        if (infoCard != null) infoCard.setSelected(selected);
    }

    /** 更新信息标签及其完整内容提示。 */
    private void updateValue(JLabel label, String value) {
        label.setText(value);
        label.setToolTipText(value);
    }

    /** 将空字符串转换为显示文本。 */
    private String emptyToDefault(String value) {
        return value == null || value.isBlank() ? "未获取" : value;
    }

    /** 设置按钮样式。 */
    private void styleButton(JButton button, boolean primary) {
        Color background = primary ? THEME.accent() : THEME.controlBackground();
        Color hover = primary ? THEME.accentHover() : THEME.card();
        button.setFont(FONT_BOLD);
        button.setForeground(primary ? THEME.textOnAccent() : THEME.textPrimary());
        button.setBorder(new EmptyBorder(9, 14, 9, 14));
        button.setIconTextGap(7);
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        button.setRolloverEnabled(true);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setUI(new RoundedButtonUI(background, hover));
    }

    /** 创建文本标签。 */
    private JLabel createLabel(String text, Font font, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(font);
        label.setForeground(color);
        return label;
    }

    /** 根据是否已选中窗口绘制信息卡片。 */
    private static final class SelectionCard extends JPanel {
        private boolean selected;

        /** 初始化由选择状态驱动边框颜色的透明信息卡。 */
        private SelectionCard() {
            setOpaque(false);
        }

        /** 记录是否已有选中目标，并重绘卡片边框以反馈选择状态。 */
        private void setSelected(boolean selected) {
            this.selected = selected;
            repaint();
        }

        /** 根据是否选中窗口绘制常规或强调边框，保持卡片内容布局不变。 */
        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(THEME.card());
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 16, 16);
            g2.setStroke(new BasicStroke(selected ? 1.6f : 1f));
            g2.setColor(selected ? THEME.accent() : THEME.border());
            g2.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 16, 16);
            g2.dispose();
            super.paintComponent(graphics);
        }
    }

    /** 通用圆角面板。 */
    private static final class RoundedPanel extends JPanel {
        private final Color background;
        private final Color border;
        private final int radius;

        /** 保存主题背景、边框和圆角半径，供窗口信息区域统一绘制。 */
        private RoundedPanel(Color background, Color border, int radius) {
            this.background = background;
            this.border = border;
            this.radius = radius;
            setOpaque(false);
        }

        /** 绘制窗口选择页卡片的圆角背景和细边框。 */
        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(background);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);
            g2.setColor(border);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);
            g2.dispose();
            super.paintComponent(graphics);
        }
    }

    /** 绘制圆角按钮。 */
    private static final class RoundedButtonUI extends DarkButtonUI {
        private final Color background;
        private final Color hover;

        /** 保存按钮背景与悬停颜色，供确认和导航操作复用。 */
        private RoundedButtonUI(Color background, Color hover) {
            this.background = background;
            this.hover = hover;
        }

        /** 按禁用、按下或悬停状态绘制按钮反馈，再绘制按钮内容。 */
        @Override
        public void paint(Graphics graphics, JComponent component) {
            AbstractButton button = (AbstractButton) component;
            Color fill = background;
            if (!button.isEnabled()) {
                fill = blend(background, THEME.card(), 0.58);
            } else if (button.getModel().isPressed()) {
                fill = blend(hover, THEME.shadow(), 0.16);
            } else if (button.getModel().isRollover()) {
                fill = hover;
            }

            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, component.getWidth(), component.getHeight(), 12, 12);
            Color highlight = THEME.highlight();
            g2.setColor(new Color(highlight.getRed(), highlight.getGreen(), highlight.getBlue(),
                    button.isEnabled() ? 18 : 8));
            g2.drawRoundRect(0, 0, component.getWidth() - 1, component.getHeight() - 1, 12, 12);
            g2.dispose();
            super.paint(graphics, component);
        }
    }

    private enum IconType { BACK, TARGET, RESELECT, CONFIRM }

    /**
     * 页面按钮使用的轻量矢量图标。
     */
        private record SelectorIcon(IconType type, int size, Color color) implements Icon {
        /**
         * 绑定图标用途、逻辑尺寸和颜色。
         */
        private SelectorIcon {
        }

            /**
             * 按图标用途绘制窗口选择、返回或确认图形。
             */
            @Override
            public void paintIcon(Component component, Graphics graphics, int x, int y) {
                Graphics2D g2 = (Graphics2D) graphics.create();
                g2.translate(x, y);
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(component.isEnabled() ? color : THEME.textMuted());
                g2.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int middle = size / 2;
                switch (type) {
                    case BACK -> {
                        g2.drawLine(3, middle, size - 2, middle);
                        g2.drawLine(3, middle, 7, middle - 4);
                        g2.drawLine(3, middle, 7, middle + 4);
                    }
                    case TARGET -> {
                        g2.drawOval(3, 3, size - 7, size - 7);
                        g2.drawOval(middle - 3, middle - 3, 6, 6);
                        g2.drawLine(middle, 0, middle, 5);
                        g2.drawLine(middle, size - 6, middle, size - 1);
                        g2.drawLine(0, middle, 5, middle);
                        g2.drawLine(size - 6, middle, size - 1, middle);
                    }
                    case RESELECT -> {
                        g2.drawArc(2, 2, size - 5, size - 5, 35, 285);
                        g2.drawLine(size - 3, 3, size - 3, 8);
                        g2.drawLine(size - 3, 3, size - 8, 3);
                    }
                    case CONFIRM -> {
                        g2.drawLine(2, middle, 6, size - 4);
                        g2.drawLine(6, size - 4, size - 2, 3);
                    }
                }
                g2.dispose();
            }

            /**
             * 返回选择页图标的逻辑宽度。
             */
            @Override
            public int getIconWidth() {
                return size;
            }

            /**
             * 返回选择页图标的逻辑高度。
             */
            @Override
            public int getIconHeight() {
                return size;
            }
        }

    /** 混合两种颜色。 */
    private static Color blend(Color first, Color second, double secondRatio) {
        double firstRatio = 1.0 - secondRatio;
        return new Color(
                (int) Math.round(first.getRed() * firstRatio + second.getRed() * secondRatio),
                (int) Math.round(first.getGreen() * firstRatio + second.getGreen() * secondRatio),
                (int) Math.round(first.getBlue() * firstRatio + second.getBlue() * secondRatio)
        );
    }
}
