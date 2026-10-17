package com.macro.ui.util;

import com.macro.ui.theme.ThemeManager;

import javax.swing.AbstractButton;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicGraphicsUtils;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;

/** 深色按钮文字绘制，避免默认禁用文字的浮雕重影。 */
public class DarkButtonUI extends BasicButtonUI {

    @Override
    protected void paintText(Graphics graphics, AbstractButton button,
                             Rectangle textRect, String text) {
        if (button.isEnabled()) {
            super.paintText(graphics, button, textRect, text);
            return;
        }

        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(ThemeManager.getPalette().textSecondary());
            BasicGraphicsUtils.drawStringUnderlineCharAt(
                    button, g2, text, button.getDisplayedMnemonicIndex(),
                    textRect.x, textRect.y + g2.getFontMetrics().getAscent());
        } finally {
            g2.dispose();
        }
    }
}
