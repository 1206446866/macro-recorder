package com.macro.util;

import com.sun.jna.Library;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinUser;

/** 集中声明窗口查找、信息读取和前台激活使用的 Windows 原生接口。 */
public interface User32Api extends Library {

    /**
     * 根据屏幕坐标获取窗口句柄。
     *
     * @param point 屏幕坐标
     * @return 窗口句柄
     */
    WinDef.HWND WindowFromPoint(WinDef.POINT.ByValue point);

    /**
     * 获取窗口标题。
     *
     * @param hWnd 窗口句柄
     * @param lpString 接收窗口标题的缓冲区
     * @param nMaxCount 缓冲区最大字符数
     * @return 实际复制的字符数
     */
    int GetWindowTextW(WinDef.HWND hWnd, char[] lpString, int nMaxCount);

    /**
     * 获取窗口类名。
     *
     * @param hWnd 窗口句柄
     * @param lpClassName 接收类名的缓冲区
     * @param nMaxCount 缓冲区最大字符数
     * @return 实际复制的字符数
     */
    int GetClassNameW(WinDef.HWND hWnd, char[] lpClassName, int nMaxCount);

    /**
     * 获取窗口所属进程 ID。
     *
     * @param hWnd 窗口句柄
     * @param processId 接收进程 ID
     * @return 线程 ID
     */
    int GetWindowThreadProcessId(WinDef.HWND hWnd, int[] processId);

    /**
     * 枚举所有顶层窗口。
     *
     * @param callback 窗口枚举回调
     * @param data 用户数据
     * @return 是否枚举成功
     */
    boolean EnumWindows(WinUser.WNDENUMPROC callback, Pointer data);

    /**
     * 判断窗口是否可见。
     *
     * @param hWnd 窗口句柄
     * @return 是否可见
     */
    boolean IsWindowVisible(WinDef.HWND hWnd);

    /**
     * 获取窗口在屏幕中的矩形范围。
     *
     * @param hWnd 窗口句柄
     * @param rect 接收窗口矩形
     * @return 是否获取成功
     */
    boolean GetWindowRect(WinDef.HWND hWnd, RECT rect);

    /**
     * 判断窗口是否最小化。
     *
     * @param hWnd 窗口句柄
     * @return 是否最小化
     */
    boolean IsIconic(WinDef.HWND hWnd);

    /**
     * 显示或恢复窗口。
     *
     * @param hWnd 窗口句柄
     * @param command 显示命令
     * @return 操作前窗口是否可见
     */
    boolean ShowWindow(WinDef.HWND hWnd, int command);

    /**
     * 将窗口设置为前台窗口。
     *
     * @param hWnd 窗口句柄
     * @return 是否设置成功
     */
    boolean SetForegroundWindow(WinDef.HWND hWnd);

    /**
     * 获取当前前台窗口。
     *
     * @return 当前前台窗口句柄
     */
    WinDef.HWND GetForegroundWindow();

    /**
     * 获取指定窗口的祖先窗口。
     *
     * @param hWnd 窗口句柄
     * @param gaFlags 祖先窗口类型
     * @return 祖先窗口句柄
     */
    HWND GetAncestor(HWND hWnd, int gaFlags);
}
