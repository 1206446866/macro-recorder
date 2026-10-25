package com.macro.ui;

import com.macro.model.TargetWindow;
import com.macro.ui.theme.ThemeManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * 组织紧凑录制操作栏的双行内容，控件状态和按钮回调由主窗口管理。
 * 第一行显示程序与录制状态，第二行显示目标窗口和操作按钮；目标文本只使用按钮之外的剩余宽度。
 */
final class RecordingControlBar extends JPanel {
    /** 使用主窗口已有的标签和按钮组装布局，不创建另一套录制状态或事件回调。 */
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

    /** 返回布局首选尺寸，并为两行控件预留至少 640 个逻辑像素的宽度。 */
    @Override
    public Dimension getPreferredSize() {
        Dimension size = super.getPreferredSize();
        return new Dimension(Math.max(640, size.width), size.height);
    }

    /** 生成单行目标说明；未指定窗口时说明全局录制，字段缺失时使用对应提示。 */
    static String targetText(TargetWindow target) {
        if (target == null) return "全局录制 · 未指定目标窗口";
        return available(target.getProcessName(), "进程名暂不可用") + " · "
                + available(target.getWindowTitle(), "窗口标题暂不可用");
    }

    /** 将空白字段替换为提示，并去除换行，避免窗口标题破坏单行显示。 */
    private static String available(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.replace('\r', ' ').replace('\n', ' ');
    }

    /** 创建使用指定布局的透明容器，让操作栏背景连续铺满。 */
    private static JPanel transparent(LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setOpaque(false);
        return panel;
    }
}
