package com.macro.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.macro.ui.theme.ThemeManager;
import com.macro.ui.theme.ThemePalette;
import com.macro.ui.util.ComponentStyleUtil;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class DarkComboBoxStyleTest {
    @Test
    void disabledDefaultRendererIgnoresLightLookAndFeelDefaults() throws Exception {
        verifyStyle(false);
    }

    @Test
    void disabledCustomRendererAndFlatLafRestoreNormalStyleWhenEnabled() throws Exception {
        verifyStyle(true);
    }

    private void verifyStyle(boolean customRenderer) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            LookAndFeel original = UIManager.getLookAndFeel();
            Object oldBackground = UIManager.get("ComboBox.disabledBackground");
            Object oldForeground = UIManager.get("ComboBox.disabledForeground");
            try {
                if (customRenderer) UIManager.setLookAndFeel(new FlatDarkLaf());
                // Reproduce stale/light defaults even when the application uses a dark palette.
                UIManager.put("ComboBox.disabledBackground", Color.WHITE);
                UIManager.put("ComboBox.disabledForeground", Color.BLACK);
                ThemePalette theme = ThemeManager.getPalette();
                JComboBox<String> combo = new JComboBox<>(new String[]{"目标节点"});
                ComponentStyleUtil.styleComboBox(combo);
                AtomicReference<Component> rendered = new AtomicReference<>();
                DefaultListCellRenderer defaultRenderer = new DefaultListCellRenderer();
                combo.setRenderer((list, value, index, selected, focus) -> {
                    JLabel label;
                    if (customRenderer) {
                        label = new JLabel(value);
                        label.setOpaque(true);
                    } else {
                        label = (JLabel) defaultRenderer.getListCellRendererComponent(
                                list, value, index, selected, focus);
                    }
                    rendered.set(label);
                    return label;
                });
                combo.setSize(220, 30);
                combo.doLayout();
                JButton arrow = (JButton) java.util.Arrays.stream(combo.getComponents())
                        .filter(c -> "ComboBox.arrowButton".equals(c.getName())).findFirst().orElseThrow();
                for (boolean enabled : new boolean[]{false, true, false, true}) {
                    combo.setEnabled(enabled);
                    BufferedImage image = new BufferedImage(220, 30, BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics = image.createGraphics();
                    try { combo.paint(graphics); } finally { graphics.dispose(); }
                    assertEquals(theme.targetCard().getRGB(), image.getRGB(150, 15), "选中值背景不能被默认白底覆盖");
                    assertEquals(enabled ? theme.textPrimary() : theme.textMuted(), rendered.get().getForeground());
                    assertEquals(theme.targetCard(), rendered.get().getBackground());
                    assertEquals(enabled ? theme.border() : theme.controlBackground(),
                            ((LineBorder) combo.getBorder()).getLineColor());
                    assertEquals(enabled, arrow.isEnabled());
                    assertEquals((enabled ? theme.controlBackground() : theme.targetCard()).getRGB(),
                            image.getRGB(arrow.getX() + 3, 3));
                    if (!enabled) assertSame(arrow.getIcon(), arrow.getDisabledIcon(), "禁用箭头应自行使用主题弱化色");
                }
            } catch (UnsupportedLookAndFeelException exception) {
                throw new AssertionError(exception);
            } finally {
                try { UIManager.setLookAndFeel(original); }
                catch (UnsupportedLookAndFeelException exception) { throw new AssertionError(exception); }
                UIManager.put("ComboBox.disabledBackground", oldBackground);
                UIManager.put("ComboBox.disabledForeground", oldForeground);
            }
        });
    }
}
