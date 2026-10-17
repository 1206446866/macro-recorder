package com.macro.util;


import com.sun.jna.Library;
import com.sun.jna.Native;


public class WindowsDpiUtil {


    private interface User32 extends Library {


        User32 INSTANCE = Native.load("user32", User32.class);


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
