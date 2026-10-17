package com.macro.ui.filedialog;

import com.macro.ui.filedialog.windows.WindowsNativeFileDialogService;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.COM.Unknown;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

/** 创建真实 COM 对象并读取配置，不显示窗口，不通过其他自动化方式操作桌面。 */
public final class WindowsNativeFileDialogContractTest {
    public static void main(String[] args) throws Exception {
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            System.out.println("跳过：本测试需要 Windows COM");
            return;
        }
        Path directory = Files.createTempDirectory(Path.of("target"), "原生面板 配置验证-").toAbsolutePath();
        int initialized = Ole32.INSTANCE.CoInitializeEx(null, 2 | 4).intValue();
        check(initialized >= 0, "无法初始化测试 STA");
        try {
            for (FileDialogOptions.Mode mode : FileDialogOptions.Mode.values()) {
                for (boolean overwrite : new boolean[]{false, true}) {
                    boolean save = mode == FileDialogOptions.Mode.SAVE_FILE;
                    PointerByReference created = new PointerByReference();
                    int status = Ole32.INSTANCE.CoCreateInstance(new Guid.GUID(save
                                    ? "{C0B4E2F3-BA21-4773-8DBA-335EC946EB8B}"
                                    : "{DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7}"), null, 1,
                            new Guid.IID(save ? "{84BCCD23-5FDE-4CDB-AEA4-AF64B83D78AB}"
                                    : "{D57C7288-D4AD-4768-BE02-9D969532D960}"), created).intValue();
                    check(status >= 0, "无法创建原生对话框：" + status);
                    Class<?> type = Class.forName(WindowsNativeFileDialogService.class.getName() + "$ComObject");
                    Constructor<?> constructor = type.getDeclaredConstructor(Pointer.class);
                    constructor.setAccessible(true);
                    try (AutoCloseable dialog = (AutoCloseable) constructor.newInstance(created.getValue())) {
                        var options = FileDialogOptions.builder(mode).title("测试原生配置")
                                .initialDirectory(directory.toFile()).fileName("含 空格的文件.mr")
                                .fileType("宏文件", "mr").defaultExtension("mr")
                                .allowAllFiles(true).confirmOverwrite(overwrite).build();
                        Method configure = WindowsNativeFileDialogService.class
                                .getDeclaredMethod("configure", type, FileDialogOptions.class);
                        configure.setAccessible(true);
                        Object retainedFilters = configure.invoke(new WindowsNativeFileDialogService(), dialog, options);
                        NativeProbe probe = new NativeProbe(created.getValue());
                        IntByReference flags = new IntByReference();
                        check(probe.call(10, flags) >= 0, "无法读取原生选项");
                        check((flags.getValue() & 0x40) != 0, "未限制为真实文件系统路径");
                        check((flags.getValue() & 0x8) != 0, "面板可能改变当前工作目录");
                        check((flags.getValue() & 0x200) == 0, "错误开启多选");
                        if (save) check(((flags.getValue() & 2) != 0) == overwrite, "覆盖确认开关错误");
                        if (mode == FileDialogOptions.Mode.SELECT_DIRECTORY) {
                            check((flags.getValue() & 0x20) != 0, "目录选择未开启 PICKFOLDERS");
                            check(retainedFilters == null, "目录选择错误地设置文件过滤器");
                        } else {
                            IntByReference index = new IntByReference();
                            check(probe.call(6, index) >= 0 && index.getValue() == 1, "文件类型未正确设置");
                        }
                        PointerByReference fileName = new PointerByReference();
                        try {
                            check(probe.call(16, fileName) >= 0, "无法读取初始文件名");
                            check(fileName.getValue().getWideString(0).equals(options.fileName()), "中文文件名编码错误");
                        } finally {
                            if (fileName.getValue() != null) Ole32.INSTANCE.CoTaskMemFree(fileName.getValue());
                        }
                        PointerByReference folder = new PointerByReference();
                        check(probe.call(13, folder) >= 0, "无法读取初始目录");
                        NativeProbe item = new NativeProbe(folder.getValue());
                        PointerByReference folderPath = new PointerByReference();
                        try {
                            check(item.call(5, 0x80058000, folderPath) >= 0, "无法解析 Shell 文件路径");
                            check(Path.of(folderPath.getValue().getWideString(0)).equals(directory), "中文目录改变");
                        } finally {
                            if (folderPath.getValue() != null) Ole32.INSTANCE.CoTaskMemFree(folderPath.getValue());
                            item.Release();
                        }
                        java.lang.ref.Reference.reachabilityFence(retainedFilters);
                    }
                }
            }
        } finally {
            Ole32.INSTANCE.CoUninitialize();
            Files.deleteIfExists(directory);
        }
        System.out.println("通过：真实 COM 创建/释放、打开/保存/目录配置、过滤器、覆盖开关和中文路径往返");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final class NativeProbe extends Unknown {
        private NativeProbe(Pointer pointer) { super(pointer); }
        private int call(int slot, Object... parameters) {
            Object[] arguments = new Object[parameters.length + 1];
            arguments[0] = getPointer();
            System.arraycopy(parameters, 0, arguments, 1, parameters.length);
            return _invokeNativeInt(slot, arguments);
        }
    }
}
