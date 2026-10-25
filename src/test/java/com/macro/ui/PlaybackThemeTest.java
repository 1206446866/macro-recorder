package com.macro.ui;

import com.macro.constant.UiConstants;
import com.macro.flow.MacroFlowFactory;
import com.macro.model.MacroAction;
import com.macro.ui.theme.ThemeManager;
import com.macro.ui.theme.ThemePalette;
import com.macro.ui.util.ComponentStyleUtil;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 只绘制内存中的真实组件，验证渐变与文字配色，不截图或发送输入。 */
class PlaybackThemeTest {
    @Test
    void textSelectionButtonsAndCheckmarksFollowConfiguredPalette() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ThemePalette original = ThemeManager.getPalette();
            ThemePalette custom = customPalette(original);
            try {
                ThemeManager.setPalette(custom);
                JTextField field = new JTextField("测试文字");
                ComponentStyleUtil.styleTextField(field);
                assertEquals(custom.textOnAccent(), field.getSelectedTextColor());
                assertEquals(custom.accent(), field.getSelectionColor());
                assertEquals(custom.textMuted(), field.getDisabledTextColor());
                JSpinner spinner = new JSpinner(new SpinnerNumberModel(1, 0, 10, 1));
                ComponentStyleUtil.styleSpinner(spinner);
                JTextField editor = ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField();
                assertEquals(custom.textOnAccent(), editor.getSelectedTextColor());
                JButton primary = new JButton("确认");
                ComponentStyleUtil.stylePrimaryButton(primary);
                assertEquals(custom.textOnAccent(), primary.getForeground());
                JButton secondary = new JButton("取消");
                ComponentStyleUtil.styleSecondaryButton(secondary);
                assertEquals(custom.textPrimary(), secondary.getForeground());
                JCheckBox checkBox = new JCheckBox("勾选", true);
                ComponentStyleUtil.styleCheckBox(checkBox);
                Icon icon = checkBox.getIcon();
                BufferedImage image = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = image.createGraphics();
                try { icon.paintIcon(checkBox, graphics, 0, 0); } finally { graphics.dispose(); }
                boolean found = false;
                for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
                    found |= image.getRGB(x, y) == custom.textOnAccent().getRGB();
                }
                assertTrue(found, "勾选图标不能继续使用固定白色");
            } finally {
                ThemeManager.setPalette(original);
            }
        });
    }

    @Test
    void gradientUsesBothThemeEndpointsAndKeepsCurrentBorderAndOuterArea() throws Exception {
        ThemePalette theme = customPalette(ThemeManager.getPalette());
        BufferedImage completed = decorate(theme, true, false);
        Color left = new Color(completed.getRGB(14, 140));
        Color right = new Color(completed.getRGB(144, 35));
        assertTrue(left.getRed() > right.getRed(), "左下应更接近主题起点色");
        assertTrue(right.getGreen() > left.getGreen(), "右上应更接近主题终点色");
        assertEquals(theme.targetCard().getRGB(), completed.getRGB(0, 80), "内光不能溢出卡片");
        assertEquals(theme.targetCard().getRGB(), completed.getRGB(157, 80));
        BufferedImage current = decorate(theme, true, true);
        assertEquals(theme.accent().getRGB(), current.getRGB(3, 80), "当前播放边框必须保持可辨认");
        BufferedImage inactive = decorate(theme, false, false);
        assertEquals(theme.targetCard().getRGB(), inactive.getRGB(14, 140), "未执行不能点亮");
    }

    @Test
    void realCardKeepsChildGeometryAndTextColorsAndProducesPreview() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                ActionTimelinePanel panel = new ActionTimelinePanel();
                panel.setActions(List.of(MacroAction.mouseMove(1230, 646, 0)));
                JPanel track = TimelineJumpLayoutTest.track(panel);
                TimelineJumpLayoutTest.layoutTrack(track);
                JComponent card = (JComponent) java.util.Arrays.stream(track.getComponents())
                        .filter(c -> c.getClass().getSimpleName().equals("NodePanel")).findFirst().orElseThrow();
                List<ComponentState> before = componentStates(card);
                List<BufferedImage> images = new ArrayList<>();
                images.add(paint(card));
                String id = MacroFlowFactory.timelineNodes(panel.getFlow()).get(0).getId();
                panel.applyPlaybackProgress(new PlaybackProgressBuffer.Batch(1, false, Set.of(id), null, false, false));
                images.add(paint(card));
                Color left = new Color(images.get(1).getRGB(10, 65), true);
                Color right = new Color(images.get(1).getRGB(147, 65), true);
                assertTrue(left.getBlue() > left.getGreen(), "默认已播放内光应为蓝色");
                assertTrue(right.getGreen() > left.getGreen(), "默认内光应向右过渡到青色");
                panel.applyPlaybackProgress(new PlaybackProgressBuffer.Batch(1, true, Set.of(), id, false, false));
                images.add(paint(card));
                panel.applyPlaybackProgress(new PlaybackProgressBuffer.Batch(1, false, Set.of(id), id, false, false));
                images.add(paint(card));
                assertEquals(before, componentStates(card), "进度渐变不能移动内容或改写文字配色");
                savePreview(images);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        });
    }

    /** 用反差明显的颜色证明绘制读取调色板，而非写死蓝青或白色。 */
    private static ThemePalette customPalette(ThemePalette base) {
        return new ThemePalette(base.background(), base.card(), base.targetCard(), base.controlBackground(),
                base.textPrimary(), base.textSecondary(), base.textMuted(), base.border(), base.accent(),
                base.accentHover(), base.success(), base.danger(), base.dangerHover(), new Color(248, 229, 93),
                base.controlHover(), base.highlight(), base.shadow(), new Color(245, 45, 65), new Color(45, 235, 120));
    }

    /** 直接使用生产绘制入口，独立验证主题端点与边界。 */
    private static BufferedImage decorate(ThemePalette theme, boolean completed, boolean current) throws Exception {
        BufferedImage image = new BufferedImage(158, 176, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(theme.targetCard());
            graphics.fillRect(0, 0, 158, 176);
            Method paint = ActionTimelinePanel.class.getDeclaredMethod("paintPlaybackDecoration", Graphics.class,
                    int.class, int.class, boolean.class, boolean.class, ThemePalette.class);
            paint.setAccessible(true);
            paint.invoke(null, graphics, 158, 176, completed, current, theme);
        } finally { graphics.dispose(); }
        return image;
    }

    /** 记录真实卡片及后代组件的位置、尺寸、字体和文字颜色。 */
    private static List<ComponentState> componentStates(Component component) {
        List<ComponentState> states = new ArrayList<>();
        states.add(new ComponentState(component, component.getBounds(), component.getPreferredSize(),
                component.getFont(), component.getForeground()));
        if (component instanceof Container container) for (Component child : container.getComponents()) {
            states.addAll(componentStates(child));
        }
        return states;
    }

    /** 绘制单个实际卡片，不创建可见窗口。 */
    private static BufferedImage paint(JComponent card) {
        BufferedImage image = new BufferedImage(card.getWidth(), card.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try { card.paint(graphics); } finally { graphics.dispose(); }
        return image;
    }

    /** 导出实际卡片在四种播放状态下的 150% 离屏预览，供视觉核对。 */
    private static void savePreview(List<BufferedImage> images) throws Exception {
        ThemePalette theme = ThemeManager.getPalette();
        int width = 752, height = 230;
        BufferedImage preview = new BufferedImage(width * 3 / 2, height * 3 / 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = preview.createGraphics();
        try {
            graphics.scale(1.5, 1.5);
            graphics.setColor(theme.background());
            graphics.fillRect(0, 0, width, height);
            graphics.setFont(new Font(UiConstants.DEFAULT_FONT_FAMILY, Font.PLAIN, 13));
            graphics.setColor(theme.textSecondary());
            String[] captions = {"未播放", "已播放 · 蓝青渐变", "当前播放", "已播放且当前"};
            for (int i = 0; i < images.size(); i++) {
                int x = 20 + i * 186;
                graphics.drawString(captions[i], x, 26);
                graphics.drawImage(images.get(i), x, 40, null);
            }
        } finally { graphics.dispose(); }
        Path path = Path.of("target", "playback-gradient-20261025", "preview.png");
        Files.createDirectories(path.getParent());
        assertTrue(ImageIO.write(preview, "png", path.toFile()));
    }

    private record ComponentState(Component component, Rectangle bounds, Dimension preferred, Font font, Color foreground) { }
}
