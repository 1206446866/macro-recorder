package com.macro.ui;

import com.macro.config.AppConfig;
import com.macro.ui.window.MainTitleBar;
import com.macro.ui.window.WindowsRecordingTitleBar;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.*;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Objects;

/** 隔离配置中的真实 HWND 消息检查，不注册钩子、不截图或发送输入。 */
public final class MainTitleBarContractTest {
    /** 验证隔离来源后检查原生命中、尺寸及主页/紧凑切换。 */
    public static void main(String[] args) {
        int exit = 0;
        try {
            Path classes = Path.of(AppConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            check(classes.toString().contains("test-sandboxes"), "必须隔离个人配置");
            SwingUtilities.invokeAndWait(MainTitleBarContractTest::verify);
            System.out.println("通过：主页自绘按钮、原生命中与双向尺寸、模式切换单一消息链及销毁卸载");
        } catch (Throwable failure) {
            System.getLogger(MainTitleBarContractTest.class.getName()).log(System.Logger.Level.ERROR, "主标题栏检查失败", failure);
            exit = 1;
        }
        System.exit(exit);
    }

    /** 只构造隐藏主窗口，通过合成原生消息验证标题栏协议，不代替真实鼠标验收。 */
    private static void verify() {
        MacroFrame frame = new MacroFrame();
        try {
            frame.setAutoRequestFocus(false);
            frame.pack();
            frame.validate();
            WindowsRecordingTitleBar main = (WindowsRecordingTitleBar) field(frame, "nativeMainTitleBar");
            MainTitleBar bar = (MainTitleBar) frame.getJMenuBar();
            main.refresh();
            check(main.isActive() && !frame.isUndecorated(), "主页没有使用保留 DWM 边框的自绘标题栏");
            HWND hwnd = new HWND(Native.getComponentPointer(frame));
            long installed = User32.INSTANCE.GetWindowLongPtr(hwnd, -4).longValue();
            long awt = Pointer.nativeValue((Pointer) field(main, "originalProc"));
            check(installed != awt, "主页窗口过程没有安装");
            int style = User32.INSTANCE.GetWindowLong(hwnd, -16);
            check((style & 0x80000) == 0, "原生系统按钮残留");
            check((style & 0x30000) == 0x30000, "主页丢失原生最小化和最大化能力");
            RECT outer = new RECT();
            check(User32.INSTANCE.GetWindowRect(hwnd, outer), "无法取得窗口边界");
            check(hit(hwnd, (outer.left + outer.right) / 2, outer.top + 1) == 12, "主页顶边不支持纵向缩放");
            check(hit(hwnd, outer.left + 1, outer.top + 1) == 13, "主页左上角未保留双向缩放");
            check(hit(hwnd, outer.right - 2, outer.top + 1) == 14, "主页右上角未保留双向缩放");
            POINT origin = new POINT();
            check(Coordinates.INSTANCE.ClientToScreen(hwnd, origin), "无法取得客户区原点");
            double scale = Coordinates.INSTANCE.GetDpiForWindow(hwnd) / 96.0;
            Rectangle title = SwingUtilities.convertRectangle(bar.getParent(), bar.getBounds(), frame.getRootPane());
            check(hitClient(hwnd, origin, scale, title.x + 35, title.getCenterY()) == 2, "主页标题不可原生拖动");
            for (JButton button : bar.getWindowButtons()) {
                Rectangle bounds = SwingUtilities.convertRectangle(button.getParent(), button.getBounds(), frame.getRootPane());
                check(hitClient(hwnd, origin, scale, bounds.getCenterX(), bounds.getCenterY()) == 1,
                        "窗口按钮误判为拖动区域：" + button.getName());
            }
            try (Memory limits = new Memory(40); Memory sizing = new Memory(16)) {
                limits.clear();
                // Windows 正常分发前会填好系统上限；合成消息也提供同样的输入契约。
                limits.setInt(32, User32.INSTANCE.GetSystemMetrics(59));
                limits.setInt(36, User32.INSTANCE.GetSystemMetrics(60));
                User32.INSTANCE.SendMessage(hwnd, 0x24, new WPARAM(0), new LPARAM(Pointer.nativeValue(limits)));
                check(limits.getInt(36) > limits.getInt(28), "主页被紧凑栏的固定高度限制");
                int height = outer.bottom - outer.top + 90;
                sizing.setInt(0, outer.left); sizing.setInt(4, outer.top);
                sizing.setInt(8, outer.right + 80); sizing.setInt(12, outer.top + height);
                User32.INSTANCE.SendMessage(hwnd, 0x214, new WPARAM(8), new LPARAM(Pointer.nativeValue(sizing)));
                check(sizing.getInt(12) - sizing.getInt(4) == height, "主页 WM_SIZING 锁定了高度");
            }
            Container pages = frame.getContentPane();
            Rectangle bounds = frame.getBounds();
            for (int i = 0; i < 2; i++) {
                invoke(frame, "enterCompactRecordingMode");
                check(frame.getJMenuBar() == null && !main.isActive(), "紧凑模式仍显示主页标题栏");
                WindowsRecordingTitleBar compact = (WindowsRecordingTitleBar) field(frame, "nativeRecordingTitleBar");
                check(compact.isActive(), "紧凑栏没有启用");
                check(Pointer.nativeValue((Pointer) field(compact, "originalProc")) == awt, "两个自绘窗口过程发生叠加");
                invoke(frame, "exitCompactRecordingMode");
                frame.validate(); main.refresh();
                check(frame.getJMenuBar() == bar && frame.getContentPane() == pages && main.isActive(), "主页没有正确恢复");
                check(Objects.equals(bounds, frame.getBounds()), "标题栏切换改变主页位置或尺寸");
                check(User32.INSTANCE.GetWindowLongPtr(hwnd, -4).longValue() == installed, "主页消息过程没有恢复");
                check(User32.INSTANCE.GetWindowLong(hwnd, -16) == style, "主页原生样式未恢复");
                check(new HWND(Native.getComponentPointer(frame)).equals(hwnd), "模式切换重建了窗口句柄");
            }
            frame.dispose();
            check(!main.isActive(), "销毁窗口没有卸载主页标题栏");
        } catch (Exception failure) { throw new AssertionError(failure); }
        finally { frame.dispose(); }
    }

    /** 把客户区逻辑点换算为真实 DPI 的屏幕命中坐标。 */
    private static long hitClient(HWND hwnd, POINT origin, double scale, double x, double y) {
        return hit(hwnd, origin.x + (int) Math.round(x * scale), origin.y + (int) Math.round(y * scale));
    }

    /** 发送合成命中消息，不移动鼠标。 */
    private static long hit(HWND hwnd, int x, int y) {
        return User32.INSTANCE.SendMessage(hwnd, 0x84, new WPARAM(0),
                new LPARAM((x & 0xffffL) | ((y & 0xffffL) << 16))).longValue();
    }

    /** 读取已有窗口和原生适配器的测试状态。 */
    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    /** 触发既有紧凑模式入口，不启动录制和全局钩子。 */
    private static void invoke(Object object, String name) throws Exception {
        Method method = object.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(object);
    }

    /** 为原生契约失败保留明确说明。 */
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private interface Coordinates extends StdCallLibrary {
        Coordinates INSTANCE = Native.load("user32", Coordinates.class, W32APIOptions.DEFAULT_OPTIONS);
        boolean ClientToScreen(HWND window, POINT point);
        int GetDpiForWindow(HWND window);
    }
}
