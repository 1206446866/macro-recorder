package com.macro.ui.capture;

import com.sun.jna.*;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.win32.StdCallLibrary;
import javax.swing.*;
import java.awt.*;
import java.util.Arrays;

/** 有界桌面诊断：仅捕获完全位于自建纯色背景内的小矩形，不保存屏幕图像。 */
public final class CaptureTimingProbe {
    private static JFrame backdrop, foreground;
    private static HWND hwnd;
    private static Rectangle sample;
    private static final Color BACKGROUND = new Color(19, 127, 61);
    public static void main(String[] args) throws Exception {
        int result = 0;
        try {
            SwingUtilities.invokeAndWait(() -> {
                Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getBounds();
                backdrop = new JFrame("Macro Recorder 截图耗时背景");
                backdrop.setUndecorated(true); backdrop.setAutoRequestFocus(false); backdrop.setAlwaysOnTop(true);
                backdrop.getContentPane().setBackground(BACKGROUND);
                backdrop.setBounds(screen.x + 100, screen.y + 100, 640, 360); backdrop.setVisible(true);
                foreground = new JFrame("Macro Recorder 截图耗时前景");
                foreground.setUndecorated(true); foreground.setAutoRequestFocus(false); foreground.setAlwaysOnTop(true);
                foreground.getContentPane().setBackground(Color.MAGENTA);
                foreground.setBounds(screen.x + 150, screen.y + 150, 500, 220); foreground.setVisible(true);
                hwnd = new HWND(Native.getComponentPointer(foreground));
                sample = new Rectangle(screen.x + 170, screen.y + 170, 200, 100);
            });
            Thread.sleep(250);
            int original;
            try (Memory value = new Memory(4)) {
                value.clear();
                int hr = Dwm.INSTANCE.DwmGetWindowAttribute(hwnd, 14, value, 4);
                System.out.println("DPI scale=" + foreground.getGraphicsConfiguration().getDefaultTransform().getScaleX()
                        + ", get cloak HRESULT=" + hr + ", original=" + value.getInt(0));
                if (hr < 0) throw new AssertionError("无法读取原遮蔽属性");
                original = value.getInt(0) & 1;
            }
            Robot robot = new Robot();
            for (boolean immediate : new boolean[]{false, true}) {
                long[] times = new long[20];
                int residuals = 0;
                for (int i = 0; i < times.length; i++) {
                    setCloak(original);
                    SwingUtilities.invokeAndWait(() -> foreground.setVisible(true));
                    Thread.sleep(80);
                    Dwm.INSTANCE.DwmFlush();
                    WindowsCaptureSupport.Cloak captureCloak = immediate ? WindowsCaptureSupport.cloak(foreground) : null;
                    if (immediate && !captureCloak.available()) throw new AssertionError("正式遮蔽接口不可用");
                    long started = System.nanoTime();
                    SwingUtilities.invokeAndWait(() -> { foreground.setVisible(false); Toolkit.getDefaultToolkit().sync(); });
                    if (immediate) {
                        if (!WindowsCaptureSupport.flush()) throw new AssertionError("正式 DWM 同步接口失败");
                    } else Thread.sleep(150);
                    var image = robot.createScreenCapture(sample);
                    times[i] = System.nanoTime() - started;
                    boolean clean = true;
                    for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
                        if ((image.getRGB(x, y) & 0xffffff) != (BACKGROUND.getRGB() & 0xffffff)) clean = false;
                    }
                    if (!clean) residuals++;
                    if (captureCloak != null) captureCloak.close();
                    setCloak(original);
                }
                Arrays.sort(times);
                System.out.printf("mode=%s n=%d residuals=%d min=%.2f median=%.2f max=%.2f ms%n",
                        immediate ? "cloak+flush+0ms" : "original+150ms", times.length, residuals,
                        times[0]/1e6, times[times.length/2]/1e6, times[times.length-1]/1e6);
                if (residuals != 0) throw new AssertionError("检测到窗口残影");
            }
        } catch (Throwable error) { error.printStackTrace(); result = 1; }
        finally { SwingUtilities.invokeAndWait(() -> { if (foreground != null) foreground.dispose(); if (backdrop != null) backdrop.dispose(); }); }
        System.exit(result);
    }
    private static void setCloak(int value) {
        try (Memory memory = new Memory(4)) {
            memory.setInt(0, value);
            int hr = Dwm.INSTANCE.DwmSetWindowAttribute(hwnd, 13, memory, 4);
            if (hr < 0) throw new AssertionError("设置过渡失败：" + hr);
        }
    }
    private interface Dwm extends StdCallLibrary {
        Dwm INSTANCE = Native.load("dwmapi", Dwm.class);
        int DwmGetWindowAttribute(HWND hwnd, int attribute, Pointer value, int size);
        int DwmSetWindowAttribute(HWND hwnd, int attribute, Pointer value, int size);
        int DwmFlush();
    }
}
