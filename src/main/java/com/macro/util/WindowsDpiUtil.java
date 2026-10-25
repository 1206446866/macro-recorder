package com.macro.util;


import com.sun.jna.Library;
import com.sun.jna.Native;


/** 查询 Windows 系统 DPI，并将其转换为相对 96 DPI 的缩放比例。 */
public class WindowsDpiUtil {


    /** 声明系统 DPI 查询所需的原生接口。 */
    private interface User32 extends Library {


        User32 INSTANCE = Native.load("user32", User32.class);


        /** 返回当前系统 DPI 数值，供逻辑尺寸与系统缩放换算使用。 */
        int GetDpiForSystem();

    }


    /**
     * 获取当前系统DPI缩放比例
     * <p>
     * 96 DPI = 100%
     * 144 DPI = 150%
     */
    public static double getScale() {


        int dpi = User32.INSTANCE.GetDpiForSystem();
        return dpi / 96.0;

    }

}
