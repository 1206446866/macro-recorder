package com.macro.ui.util;

import com.macro.ui.dialog.AppMessageDialog;

import java.awt.*;

/** 为尚未接入业务逻辑的界面入口提供统一提示。 */
public final class TodoUtil {

    private TodoUtil() {
    }

    /** 显示功能待开发提示。 */
    public static void show(Component parent, String featureName) {
        AppMessageDialog.showInfo(parent, featureName + "功能待开发。");
    }
}
