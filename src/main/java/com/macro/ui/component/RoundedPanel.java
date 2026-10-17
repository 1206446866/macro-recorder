package com.macro.ui.component;

import javax.swing.*;
import java.awt.*;

/** 可复用的主题圆角面板。 */
public class RoundedPanel extends JPanel {

    private final Color background;
    private final Color borderColor;
    private final int radius;

    public RoundedPanel(Color background, Color borderColor, int radius) {
        this.background = background;
        this.borderColor = borderColor;
        this.radius = radius;
        setOpaque(false);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g2 = (Graphics2D) graphics.create();
        g2.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON
        );
        g2.setColor(background);
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);
        if (borderColor != null) {
            g2.setColor(borderColor);
            g2.drawRoundRect(
                    0,
                    0,
                    Math.max(0, getWidth() - 1),
                    Math.max(0, getHeight() - 1),
                    radius,
                    radius
            );
        }
        g2.dispose();
        super.paintComponent(graphics);
    }
}
