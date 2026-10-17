package com.macro.ui.theme;

import java.awt.Color;

/**
 * 应用级颜色配置。后续增加主题或用户自定义颜色时，可通过替换调色板扩展，
 * 避免将颜色散落在各个窗口和组件中。
 */
public record ThemePalette(
        Color background,
        Color card,
        Color targetCard,
        Color controlBackground,
        Color textPrimary,
        Color textSecondary,
        Color textMuted,
        Color border,
        Color accent,
        Color accentHover,
        Color success,
        Color danger,
        Color dangerHover
) {
}
