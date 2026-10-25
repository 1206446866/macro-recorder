package com.macro.util;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Structure;


/** 通过 Windows 原生接口读取当前鼠标的屏幕坐标，不移动鼠标或发送输入。 */
public class WindowsMouseUtil {


    /** 声明读取系统指针位置所需的原生接口。 */
    private interface User32 extends Library {


        User32 INSTANCE = Native.load("user32", User32.class);


        /** 将当前系统指针的屏幕坐标写入给定结构。 */
        boolean GetCursorPos(POINT point);

    }


    /** 承接 Windows 返回的 X、Y 屏幕坐标，字段顺序与原生 POINT 结构一致。 */
    public static class POINT extends Structure {


        public int x;

        public int y;


        /** 按 Windows POINT 布局依次排列 X 和 Y，保证 JNA 正确读写原生内存。 */
        @Override
        protected java.util.List<String> getFieldOrder() {

            return java.util.List.of("x", "y");
        }
    }


    /** 创建坐标缓冲区并查询当前鼠标位置，不改变指针所在位置。 */
    public static POINT getCursorPosition() {


        POINT point = new POINT();


        User32.INSTANCE.GetCursorPos(point);


        return point;

    }

}