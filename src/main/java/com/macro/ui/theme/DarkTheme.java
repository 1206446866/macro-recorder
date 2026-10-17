package com.macro.ui.theme;

import java.awt.Color;

/** 默认深色主题。 */
public final class DarkTheme {

    private DarkTheme() {
    }

    public static ThemePalette createPalette() {
        return new ThemePalette(
                new Color(18, 20, 24),       // 主背景
                new Color(27, 30, 36),       // 卡片背景
                new Color(22, 25, 31),       // 目标窗口区域
                new Color(32, 35, 42),       // 控件背景
                new Color(235, 237, 241),    // 主要文字
                new Color(178, 184, 196),    // 次要文字
                new Color(125, 132, 146),    // 弱化文字
                new Color(48, 53, 63),       // 边框
                new Color(105, 117, 255),    // 强调色
                new Color(120, 132, 255),    // 强调色悬停
                new Color(75, 190, 125),     // 成功状态
                new Color(215, 82, 92),      // 危险操作
                new Color(230, 94, 104)      // 危险操作悬停
        );
    }
}
