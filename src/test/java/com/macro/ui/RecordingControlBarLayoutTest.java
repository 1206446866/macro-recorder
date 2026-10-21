package com.macro.ui;

import com.macro.model.TargetWindow;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;

import static org.junit.jupiter.api.Assertions.*;

class RecordingControlBarLayoutTest {
    @Test
    void targetInformationHandlesGlobalMissingAndLiteralTitles() {
        assertEquals("全局录制 · 未指定目标窗口", RecordingControlBar.targetText(null));
        assertEquals("进程名暂不可用 · 窗口标题暂不可用", RecordingControlBar.targetText(TargetWindow.create()));
        assertEquals("进程名暂不可用 · 窗口标题暂不可用",
                RecordingControlBar.targetText(TargetWindow.create().setProcessName(" ").setWindowTitle("\n")));
        assertEquals("WeChat.exe · 聊天 窗口", RecordingControlBar.targetText(
                TargetWindow.create().setProcessName("WeChat.exe").setWindowTitle("聊天\n窗口")));
        assertEquals("app.exe · <html>literal", RecordingControlBar.targetText(
                TargetWindow.create().setProcessName("app.exe").setWindowTitle("<html>literal")));
    }

    @Test
    void twoRowsKeepButtonsFixedAndClipLongTargetAtDifferentFontSizes() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (int fontSize : new int[]{12, 18}) {
                JLabel name = new JLabel("Macro Recorder"), status = new JLabel("正在录制"),
                        count = new JLabel("操作数：128"), target = new JLabel();
                JButton min = new JButton("−"), capture = new JButton("截图"),
                        pause = new JButton("暂停"), stop = new JButton("终止");
                for (JComponent c : new JComponent[]{name, status, count, target, min, capture, pause, stop}) {
                    c.setFont(new Font(Font.DIALOG, Font.PLAIN, fontSize));
                }
                RecordingControlBar bar = new RecordingControlBar(name, status, count, target, min, capture, pause, stop);
                target.setText("app.exe · 窗口");
                Dimension preferred = bar.getPreferredSize();
                for (int width : new int[]{preferred.width, preferred.width + 200}) {
                    bar.setSize(width, preferred.height);
                    layout(bar);
                    Rectangle minBounds = bounds(min, bar), actions = bounds(capture, bar);
                    assertTrue(target.getHeight() >= target.getFontMetrics(target.getFont()).getHeight(),
                            "初始空标签在数据刷新后裁切了目标文字高度");
                    assertTrue(minBounds.y + minBounds.height <= actions.y, "最小化和操作按钮应分属两行");
                    assertTrue(bounds(name, bar).x < bounds(status, bar).x);
                    assertTrue(bounds(status, bar).x < bounds(count, bar).x);
                    assertTrue(bounds(count, bar).getMaxX() <= minBounds.x);
                    assertTrue(bounds(stop, bar).getMaxX() <= width - bar.getInsets().right);
                    assertEquals(minBounds.getMaxX(), bounds(stop, bar).getMaxX());
                    target.setText("<html>app.exe · " + "超长窗口标题".repeat(100));
                    status.setText("正在截图");
                    pause.setText("继续");
                    layout(bar);
                    assertEquals(actions, bounds(capture, bar), "长标题或状态变化移动了操作组");
                    assertEquals(minBounds, bounds(min, bar));
                    assertEquals(preferred, bar.getPreferredSize(), "长标题改变了窗口首选尺寸");
                    assertTrue(bounds(target, bar).getMaxX() < actions.x);
                    assertEquals(Boolean.TRUE, target.getClientProperty("html.disable"));
                    Rectangle view = new Rectangle(0, 0, target.getWidth(), target.getHeight());
                    String clipped = SwingUtilities.layoutCompoundLabel(target, target.getFontMetrics(target.getFont()),
                            target.getText(), null, target.getVerticalAlignment(), target.getHorizontalAlignment(),
                            target.getVerticalTextPosition(), target.getHorizontalTextPosition(), view,
                            new Rectangle(), new Rectangle(), target.getIconTextGap());
                    assertTrue(clipped.endsWith("..."), "实际 JLabel 文本布局没有省略长标题");
                }
            }
        });
    }

    private static Rectangle bounds(Component component, Container ancestor) {
        return SwingUtilities.convertRectangle(component.getParent(), component.getBounds(), ancestor);
    }

    private static void layout(Container container) {
        container.doLayout();
        for (Component c : container.getComponents()) if (c instanceof Container child) layout(child);
    }
}
