package com.macro.ui;

import com.macro.constant.UiConstants;
import com.macro.ui.theme.ThemeManager;
import com.macro.ui.theme.ThemePalette;
import com.macro.ui.util.ComponentStyleUtil;
import com.macro.ui.util.NumericInputUtil;
import com.macro.ui.window.WindowTitleBarSupport;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.function.Consumer;

/** 批量编辑只修改勾选字段，混合选择不会把普通延迟与图像重试间隔混为一项。 */
final class TimelineBatchEditDialog extends JDialog {
    record Values(Long actionDelay, Long retryDelay) { }
    private final WindowTitleBarSupport titleBarSupport;
    private final JCheckBox actionEnabled = new JCheckBox();
    private final JCheckBox retryEnabled = new JCheckBox();
    private final JTextField actionDelay = new JTextField("100");
    private final JTextField retryDelay = new JTextField("1000");
    private final JLabel error = new JLabel(" ");

    TimelineBatchEditDialog(Window owner, int total, int actions, int retries, Consumer<Values> apply) {
        super(owner, "批量编辑", ModalityType.APPLICATION_MODAL);
        ThemePalette theme = ThemeManager.getPalette();
        JPanel root = new JPanel(new BorderLayout(0, 18));
        root.setBackground(theme.background());
        root.setBorder(new EmptyBorder(22, 24, 20, 24));
        JLabel title = new JLabel("编辑 " + total + " 个节点");
        title.setFont(new Font(UiConstants.DEFAULT_FONT_FAMILY, Font.BOLD, 21));
        title.setForeground(theme.textPrimary());
        JPanel header = new JPanel(new GridLayout(0, 1, 0, 8)); header.setOpaque(false);
        header.add(title);
        JLabel hint = new JLabel("仅修改勾选的项目，整批操作可一次撤销。");
        hint.setForeground(theme.textSecondary()); header.add(hint);
        root.add(header, BorderLayout.NORTH);
        JPanel fields = new JPanel(new GridLayout(0, 1, 0, 14)); fields.setOpaque(false);
        fields.add(fieldRow(actionEnabled, actionDelay, "普通动作延迟", actions));
        fields.add(fieldRow(retryEnabled, retryDelay, "图像重试间隔", retries));
        JLabel note = new JLabel("图像项仅影响有“重试”行为的节点。");
        note.setForeground(theme.textMuted()); fields.add(note);
        root.add(fields, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(0, 10)); footer.setOpaque(false);
        error.setForeground(theme.danger()); footer.add(error, BorderLayout.NORTH);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0)); buttons.setOpaque(false);
        JButton cancel = new JButton("取消"); ComponentStyleUtil.styleSecondaryButton(cancel);
        JButton confirm = new JButton("应用到所选节点"); ComponentStyleUtil.stylePrimaryButton(confirm);
        cancel.addActionListener(event -> dispose());
        confirm.addActionListener(event -> {
            try { Values values = readValues(); apply.accept(values); dispose(); }
            catch (IllegalArgumentException invalid) { error.setText(invalid.getMessage()); }
        });
        buttons.add(cancel); buttons.add(confirm); footer.add(buttons, BorderLayout.SOUTH);
        root.add(footer, BorderLayout.SOUTH); setContentPane(root);
        titleBarSupport = new WindowTitleBarSupport(this);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE); setResizable(false);
        getRootPane().setDefaultButton(confirm);
        getRootPane().registerKeyboardAction(event -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack(); setSize(Math.max(470, getWidth()), getHeight()); setLocationRelativeTo(owner);
    }

    private JPanel fieldRow(JCheckBox enabled, JTextField field, String name, int count) {
        JPanel row = new JPanel(new BorderLayout(12, 0)); row.setOpaque(false);
        enabled.setText(name + "（" + count + " 项）");
        ComponentStyleUtil.styleCheckBox(enabled); enabled.setEnabled(count > 0);
        field.setColumns(10); ComponentStyleUtil.styleTextField(field);
        NumericInputUtil.installNonNegativeLong(field, name); field.setEnabled(false);
        enabled.addActionListener(event -> field.setEnabled(enabled.isSelected()));
        JPanel input = new JPanel(new BorderLayout(8, 0)); input.setOpaque(false); input.add(field, BorderLayout.CENTER);
        JLabel unit = new JLabel("ms"); unit.setForeground(ThemeManager.getPalette().textSecondary()); input.add(unit, BorderLayout.EAST);
        row.add(enabled, BorderLayout.CENTER); row.add(input, BorderLayout.EAST);
        return row;
    }

    Values readValues() {
        if (!actionEnabled.isSelected() && !retryEnabled.isSelected()) throw new IllegalArgumentException("请先勾选要修改的项目");
        return new Values(actionEnabled.isSelected() ? NumericInputUtil.readLong(actionDelay) : null,
                retryEnabled.isSelected() ? NumericInputUtil.readLong(retryDelay) : null);
    }

    @Override public void addNotify() { super.addNotify(); if (titleBarSupport != null) titleBarSupport.activate(); }
    @Override public void removeNotify() { try { if (titleBarSupport != null) titleBarSupport.deactivate(); } finally { super.removeNotify(); } }
}
