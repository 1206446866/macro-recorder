package com.macro.ui.filedialog;

import com.macro.ui.filedialog.windows.WindowsNativeFileDialogService;
import java.util.Locale;

/** 仅选择已实现的平台原生服务，不提供备用文件面板。 */
public final class FileDialogServiceFactory {
    private FileDialogServiceFactory() { }

    public static FileDialogService create() {
        return forOperatingSystem(System.getProperty("os.name", "未知操作系统"));
    }

    /** 保留明确的平台边界，未来可在此增加 macOS 原生实现。 */
    static FileDialogService forOperatingSystem(String osName) {
        if (osName.toLowerCase(Locale.ROOT).startsWith("windows")) {
            return new WindowsNativeFileDialogService();
        }
        return (parent, options) -> {
            throw new FileDialogException("当前操作系统尚不支持原生文件面板：" + osName);
        };
    }
}
