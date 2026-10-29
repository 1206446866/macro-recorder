package com.macro.recorder;

import com.github.kwhat.jnativehook.mouse.NativeMouseEvent;
import com.sun.jna.platform.win32.BaseTSD.ULONG_PTR;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.LONG;
import com.sun.jna.platform.win32.WinUser;

import java.util.function.IntConsumer;
import java.util.function.IntUnaryOperator;

/** 冻结录制截图前只释放当前按住的左/右/中键；不移动鼠标、不按键或恢复按下。 */
final class CaptureMouseRelease {
    private final IntUnaryOperator keyState;
    private final IntUnaryOperator sendMouseUp;

    CaptureMouseRelease() {
        this(key -> User32.INSTANCE.GetAsyncKeyState(key), CaptureMouseRelease::sendMouseUp);
    }

    /** 注入状态与发送接口，使检查不查询或改变用户的真实鼠标状态。 */
    CaptureMouseRelease(IntUnaryOperator keyState, IntUnaryOperator sendMouseUp) {
        this.keyState = keyState;
        this.sendMouseUp = sendMouseUp;
    }

    void releasePressedButtons(IntConsumer beforeRelease) {
        int flags = 0;
        // GetAsyncKeyState 查询物理鼠标键；发送同侧 UP，不经过 Robot 的主/次键交换。
        if ((keyState.applyAsInt(0x01) & 0x8000) != 0) {
            beforeRelease.accept(NativeMouseEvent.BUTTON1); flags |= 0x0004;
        }
        if ((keyState.applyAsInt(0x02) & 0x8000) != 0) {
            beforeRelease.accept(NativeMouseEvent.BUTTON2); flags |= 0x0010;
        }
        if ((keyState.applyAsInt(0x04) & 0x8000) != 0) {
            beforeRelease.accept(NativeMouseEvent.BUTTON3); flags |= 0x0040;
        }
        if (flags != 0 && sendMouseUp.applyAsInt(flags) != 1)
            throw new IllegalStateException("无法松开鼠标，已取消本次截图。请手动松开鼠标后重试。");
    }

    private static int sendMouseUp(int flags) {
        WinUser.INPUT[] input = encodeMouseUp(flags);
        return User32.INSTANCE.SendInput(new DWORD(input.length), input, input[0].size()).intValue();
    }

    /** 一个纯鼠标 UP 输入，坐标、滚轮数据与额外信息均为零。 */
    static WinUser.INPUT[] encodeMouseUp(int flags) {
        if (flags == 0 || (flags & ~0x0054) != 0) throw new IllegalArgumentException("仅允许左、右、中键释放");
        WinUser.INPUT input = new WinUser.INPUT();
        input.type = new DWORD(WinUser.INPUT.INPUT_MOUSE);
        input.input.setType(WinUser.MOUSEINPUT.class);
        input.input.mi = new WinUser.MOUSEINPUT();
        input.input.mi.dx = new LONG(0); input.input.mi.dy = new LONG(0);
        input.input.mi.mouseData = new DWORD(0); input.input.mi.dwFlags = new DWORD(flags);
        input.input.mi.time = new DWORD(0); input.input.mi.dwExtraInfo = new ULONG_PTR(0);
        return new WinUser.INPUT[]{input};
    }
}
