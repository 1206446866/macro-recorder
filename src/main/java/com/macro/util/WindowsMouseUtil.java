package com.macro.util;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Structure;


public class WindowsMouseUtil {


    private interface User32 extends Library {


        User32 INSTANCE = Native.load("user32", User32.class);


        boolean GetCursorPos(POINT point);

    }


    public static class POINT extends Structure {


        public int x;

        public int y;


        @Override
        protected java.util.List<String> getFieldOrder() {

            return java.util.List.of("x", "y");
        }
    }


    public static POINT getCursorPosition() {


        POINT point = new POINT();


        User32.INSTANCE.GetCursorPos(point);


        return point;

    }

}