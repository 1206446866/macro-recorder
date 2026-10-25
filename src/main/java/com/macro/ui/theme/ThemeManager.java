package com.macro.ui.theme;

import lombok.Getter;

/**
 * 主题管理入口。当前使用默认深色主题；后续可在设置中加载用户调色板。
 * 颜色方案在创建窗口前确定，因此切换主题时应重新应用 UI 并刷新窗口。
 */
public final class ThemeManager {

    @Getter
    private static ThemePalette palette = DarkTheme.createPalette();

    /** 主题状态通过统一入口共享，无需创建管理器实例。 */
    private ThemeManager() {
    }

    /**
     * 设置应用调色板，为后续主题选择和自定义颜色预留入口。
     *
     * @param newPalette 新调色板
     */
    public static void setPalette(ThemePalette newPalette) {
        if (newPalette == null) {
            throw new IllegalArgumentException("主题调色板不能为空");
        }
        palette = newPalette;
    }
}
