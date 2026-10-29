package com.macro.diagnostics;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;

import java.awt.GraphicsEnvironment;
import java.awt.GraphicsDevice;
import java.awt.Rectangle;
import java.lang.management.ManagementFactory;
import java.util.function.Supplier;

/** 只采集排查兼容性需要的环境字段，不读取宏、截图、配置内容或环境变量。 */
final class DeviceInformation {
    private DeviceInformation() { }

    static String collect() {
        StringBuilder text = new StringBuilder(2048);
        section(text, "操作系统", () -> properties("os.name", "os.version", "os.arch"));
        section(text, "Java", () -> properties("java.version", "java.runtime.version", "java.vendor",
                "java.vm.name", "java.vm.version", "sun.arch.data.model", "file.encoding", "user.language", "user.country"));
        section(text, "进程", () -> "PID=" + ProcessHandle.current().pid()
                + "；运行时间(ms)=" + ManagementFactory.getRuntimeMXBean().getUptime()
                + "；可用处理器=" + Runtime.getRuntime().availableProcessors());
        section(text, "CPU 型号", () -> Platform.isWindows()
                ? Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE,
                        "HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0", "ProcessorNameString").strip()
                : "当前平台未采集型号");
        section(text, "Windows 版本", () -> {
            if (!Platform.isWindows()) return "不适用";
            String key = "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion";
            return "DisplayVersion=" + Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE, key, "DisplayVersion")
                    + "；Build=" + Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE, key, "CurrentBuildNumber")
                    + "." + Advapi32Util.registryGetIntValue(WinReg.HKEY_LOCAL_MACHINE, key, "UBR");
        });
        section(text, "内存采样（bytes）", () -> {
            Runtime runtime = Runtime.getRuntime();
            String heap = "JVM heap used=" + (runtime.totalMemory() - runtime.freeMemory())
                    + "；committed=" + runtime.totalMemory() + "；max=" + runtime.maxMemory();
            var os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean memory) {
                heap += "\nphysical total=" + memory.getTotalMemorySize() + "；free=" + memory.getFreeMemorySize()
                        + "\nswap total=" + memory.getTotalSwapSpaceSize() + "；free=" + memory.getFreeSwapSpaceSize();
            }
            return heap + "\n内存为运行时采样，不是原生内存的精确分配追踪。";
        });
        section(text, "显示器", () -> {
            if (GraphicsEnvironment.isHeadless()) return "无图形环境（headless）";
            StringBuilder screens = new StringBuilder();
            for (GraphicsDevice screen : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                var configuration = screen.getDefaultConfiguration();
                Rectangle bounds = configuration.getBounds();
                var scale = configuration.getDefaultTransform();
                var mode = screen.getDisplayMode();
                screens.append("显示设备 ID=").append(screen.getIDstring())
                        .append("；逻辑区域=").append(bounds)
                        .append("；缩放=").append(scale.getScaleX() * 100).append("% × ")
                        .append(scale.getScaleY() * 100).append("%")
                        .append("；显示模式=").append(mode.getWidth()).append('×').append(mode.getHeight())
                        .append("；刷新率=").append(mode.getRefreshRate()).append("Hz\n");
            }
            return screens.toString();
        });
        return text.toString();
    }

    private static String properties(String... keys) {
        StringBuilder text = new StringBuilder();
        for (String key : keys) text.append(key).append('=').append(System.getProperty(key, "未知")).append('\n');
        return text.toString();
    }

    private static void section(StringBuilder text, String title, Supplier<String> value) {
        text.append('[').append(title).append("]\n");
        try { text.append(value.get()); }
        catch (Throwable failure) { text.append("采集不可用：").append(failure); }
        text.append('\n');
    }
}
