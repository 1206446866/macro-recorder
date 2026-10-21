package com.macro.ui;

import com.macro.model.TargetWindow;
import com.macro.ui.theme.ThemeManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/** 紧凑录制的双行布局；右侧控件按首选宽度固定，目标文本只使用剩余空间。 */
final class RecordingControlBar extends JPanel {
    RecordingControlBar(JLabel name, JLabel status, JLabel count, JLabel target,
                        JButton minimize, JButton capture, JButton pause, JButton stop) {
        super(new BorderLayout(0, 10));
        setBackground(ThemeManager.getPalette().background());
        setBorder(new EmptyBorder(8, 12, 10, 12));

        JPanel heading = transparent(new BorderLayout(12, 0));
        JPanel summary = transparent(new FlowLayout(FlowLayout.LEFT, 10, 0));
        summary.add(name);
        summary.add(status);
        summary.add(count);
        heading.add(summary, BorderLayout.CENTER);
        heading.add(minimize, BorderLayout.EAST);

        JPanel details = transparent(new BorderLayout(12, 0));
        details.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, ThemeManager.getPalette().controlBackground()),
                new EmptyBorder(10, 0, 0, 0)));
        JPanel information = transparent(new BorderLayout(0, 3));
        JLabel caption = new JLabel("当前目标窗口");
        caption.setFont(count.getFont());
        caption.setForeground(ThemeManager.getPalette().textSecondary());
        information.add(caption, BorderLayout.NORTH);
        // BasicLabelUI 按实际分配宽度绘制省略号；禁止窗口标题被当作 HTML 解析。
        target.putClientProperty("html.disable", Boolean.TRUE);
        int textHeight = target.getFontMetrics(target.getFont()).getHeight();
        target.setMinimumSize(new Dimension(0, textHeight));
        target.setPreferredSize(new Dimension(180, textHeight));
        information.add(target, BorderLayout.CENTER);
        details.add(information, BorderLayout.CENTER);
        JPanel actions = transparent(new GridLayout(1, 3, 6, 0));
        actions.add(capture);
        actions.add(pause);
        actions.add(stop);
        details.add(actions, BorderLayout.EAST);
        add(heading, BorderLayout.NORTH);
        add(details, BorderLayout.CENTER);
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension size = super.getPreferredSize();
        return new Dimension(Math.max(640, size.width), size.height);
    }

    static String targetText(TargetWindow target) {
        if (target == null) return "全局录制 · 未指定目标窗口";
        return available(target.getProcessName(), "进程名暂不可用") + " · "
                + available(target.getWindowTitle(), "窗口标题暂不可用");
    }

    private static String available(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.replace('\r', ' ').replace('\n', ' ');
    }

    private static JPanel transparent(LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setOpaque(false);
        return panel;
    }
}
