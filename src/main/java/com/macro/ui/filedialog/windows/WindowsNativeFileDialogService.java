package com.macro.ui.filedialog.windows;

import com.macro.ui.filedialog.FileDialogException;
import com.macro.ui.filedialog.FileDialogOptions;
import com.macro.ui.filedialog.FileDialogService;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.COM.Unknown;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinNT.HRESULT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.EventQueue;
import java.awt.SecondaryLoop;
import java.awt.Toolkit;
import java.awt.Window;
import java.io.File;
import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

/** 通过现有 JNA 接入 Windows Shell Common Item Dialog。 */
public final class WindowsNativeFileDialogService implements FileDialogService {

    private static final int COINIT_APARTMENTTHREADED = 0x2;
    private static final int COINIT_DISABLE_OLE1DDE = 0x4;
    private static final int CLSCTX_INPROC_SERVER = 0x1;
    private static final int ERROR_CANCELLED_HRESULT = 0x800704C7;
    private static final int FOS_OVERWRITEPROMPT = 0x2;
    private static final int FOS_NOCHANGEDIR = 0x8;
    private static final int FOS_PICKFOLDERS = 0x20;
    private static final int FOS_FORCEFILESYSTEM = 0x40;
    private static final int FOS_ALLOWMULTISELECT = 0x200;
    private static final int FOS_PATHMUSTEXIST = 0x800;
    private static final int FOS_FILEMUSTEXIST = 0x1000;
    private static final int SIGDN_FILESYSPATH = 0x80058000;

    /** 虚函数表包含继承自 IUnknown 和 IModalWindow 的槽位，编号必须遵循 Windows ABI。 */
    private static final int SHOW = 3;
    private static final int SET_FILE_TYPES = 4;
    private static final int SET_FILE_TYPE_INDEX = 5;
    private static final int SET_OPTIONS = 9;
    private static final int GET_OPTIONS = 10;
    private static final int SET_FOLDER = 12;
    private static final int SET_FILE_NAME = 15;
    private static final int SET_TITLE = 17;
    private static final int GET_RESULT = 20;
    private static final int SET_DEFAULT_EXTENSION = 22;
    private static final int SHELL_ITEM_GET_DISPLAY_NAME = 5;

    /** 在 Swing 事件线程打开 Windows 原生文件面板；后台调用转交事件线程并等待所选结果。 */
    @Override
    public Optional<File> showDialog(Component parent, FileDialogOptions options) throws FileDialogException {
        Objects.requireNonNull(options, "文件面板配置不能为空");
        if (EventQueue.isDispatchThread()) return showOnEventThread(parent, options);
        CompletableFuture<Optional<File>> result = new CompletableFuture<>();
        EventQueue.invokeLater(() -> {
            try { result.complete(showOnEventThread(parent, options)); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
        });
        return awaitResult(result);
    }

    /** HWND 在 EDT 上读取；COM 仅在专属 STA 中调用，Swing 嵌套事件循环维持重绘。 */
    private Optional<File> showOnEventThread(Component parent, FileDialogOptions options) throws FileDialogException {
        final HWND owner;
        try {
            Window window = parent instanceof Window value ? value
                    : parent == null ? null : SwingUtilities.getWindowAncestor(parent);
            owner = window != null && window.isDisplayable() ? new HWND(Native.getWindowPointer(window)) : null;
        } catch (RuntimeException | LinkageError failure) {
            throw new FileDialogException("无法取得原生文件面板的父窗口", failure);
        }
        SecondaryLoop loop = Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
        CompletableFuture<Optional<File>> result = new CompletableFuture<>();
        Thread sta = new Thread(() -> {
            try { result.complete(showNative(owner, options)); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
            finally { EventQueue.invokeLater(loop::exit); }
        }, "windows-file-dialog-sta");
        sta.setDaemon(true);
        sta.start();
        // 退出请求经 EDT 排队，避免 STA 很快失败时早于 enter 而丢失唤醒。
        loop.enter();
        return awaitResult(result);
    }

    /** 等待文件面板返回并保留明确的原生异常，其他失败统一包装为文件面板异常。 */
    private Optional<File> awaitResult(CompletableFuture<Optional<File>> result) throws FileDialogException {
        try {
            return result.join();
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof FileDialogException dialogFailure) throw dialogFailure;
            throw new FileDialogException("Windows 原生文件面板不可用", failure.getCause());
        }
    }

    /** 初始化成功后必须在同一线程释放全部接口，再平衡调用 CoUninitialize。 */
    private Optional<File> showNative(HWND owner, FileDialogOptions options) throws FileDialogException {
        requireSuccess(Ole32.INSTANCE.CoInitializeEx(null,
                COINIT_APARTMENTTHREADED | COINIT_DISABLE_OLE1DDE).intValue(), "CoInitializeEx");
        try {
            boolean save = options.mode() == FileDialogOptions.Mode.SAVE_FILE;
            Guid.GUID classId = new Guid.GUID(save
                    ? "{C0B4E2F3-BA21-4773-8DBA-335EC946EB8B}"
                    : "{DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7}");
            Guid.IID interfaceId = new Guid.IID(save
                    ? "{84BCCD23-5FDE-4CDB-AEA4-AF64B83D78AB}"
                    : "{D57C7288-D4AD-4768-BE02-9D969532D960}");
            PointerByReference reference = new PointerByReference();
            requireSuccess(Ole32.INSTANCE.CoCreateInstance(classId, null, CLSCTX_INPROC_SERVER,
                    interfaceId, reference).intValue(), save ? "创建 IFileSaveDialog" : "创建 IFileOpenDialog");
            try (ComObject dialog = new ComObject(reference.getValue())) {
                FilterSpec[] filters = configure(dialog, options);
                try {
                    int status = dialog.call(SHOW, owner);
                    if (status == ERROR_CANCELLED_HRESULT) return Optional.empty();
                    requireSuccess(status, "IFileDialog::Show");
                    return Optional.of(readResult(dialog));
                } finally {
                    Reference.reachabilityFence(filters);
                }
            }
        } finally {
            Ole32.INSTANCE.CoUninitialize();
        }
    }

    /** 按用途设置原生面板选项、初始位置和文件类型，并返回需保持存活的筛选结构。 */
    private FilterSpec[] configure(ComObject dialog, FileDialogOptions options) throws FileDialogException {
        IntByReference currentOptions = new IntByReference();
        requireSuccess(dialog.call(GET_OPTIONS, currentOptions), "IFileDialog::GetOptions");
        int flags = (currentOptions.getValue() | FOS_FORCEFILESYSTEM | FOS_NOCHANGEDIR | FOS_PATHMUSTEXIST)
                & ~FOS_ALLOWMULTISELECT;
        if (options.mode() == FileDialogOptions.Mode.SELECT_DIRECTORY) flags |= FOS_PICKFOLDERS;
        if (options.mode() == FileDialogOptions.Mode.OPEN_FILE) flags |= FOS_FILEMUSTEXIST;
        if (options.mode() == FileDialogOptions.Mode.SAVE_FILE) {
            flags = options.confirmOverwrite() ? flags | FOS_OVERWRITEPROMPT : flags & ~FOS_OVERWRITEPROMPT;
        }
        requireSuccess(dialog.call(SET_OPTIONS, flags), "IFileDialog::SetOptions");
        if (!options.title().isBlank()) requireSuccess(dialog.call(SET_TITLE, new WString(options.title())), "设置标题");
        File directory = options.initialDirectory();
        if (directory != null && directory.isDirectory()) {
            PointerByReference folder = new PointerByReference();
            Guid.IID shellItemId = new Guid.IID("{43826D1E-E718-42EE-BC55-A1E261C37BFE}");
            requireSuccess(ShellApi.INSTANCE.SHCreateItemFromParsingName(new WString(directory.getAbsolutePath()),
                    null, new Guid.REFIID(shellItemId), folder).intValue(), "解析初始目录");
            try (ComObject item = new ComObject(folder.getValue())) {
                requireSuccess(dialog.call(SET_FOLDER, item.getPointer()), "IFileDialog::SetFolder");
            }
        }
        if (!options.fileName().isBlank()) requireSuccess(dialog.call(SET_FILE_NAME,
                new WString(options.fileName())), "设置初始文件名");
        if (!options.defaultExtension().isBlank()) requireSuccess(dialog.call(SET_DEFAULT_EXTENSION,
                new WString(options.defaultExtension())), "设置默认扩展名");
        if (options.mode() == FileDialogOptions.Mode.SELECT_DIRECTORY) return null;

        List<String[]> types = new ArrayList<>();
        for (var type : options.fileTypes()) {
            types.add(new String[]{type.description(), type.extensions().stream()
                    .map(extension -> "*." + extension).collect(Collectors.joining(";"))});
        }
        if (options.allowAllFiles()) types.add(new String[]{"所有文件 (*.*)", "*.*"});
        if (types.isEmpty()) return null;
        FilterSpec[] filters = (FilterSpec[]) new FilterSpec().toArray(types.size());
        for (int index = 0; index < filters.length; index++) {
            filters[index].name = new WString(types.get(index)[0]);
            filters[index].pattern = new WString(types.get(index)[1]);
            filters[index].write();
        }
        requireSuccess(dialog.call(SET_FILE_TYPES, filters.length, filters[0].getPointer()), "IFileDialog::SetFileTypes");
        requireSuccess(dialog.call(SET_FILE_TYPE_INDEX, 1), "IFileDialog::SetFileTypeIndex");
        return filters;
    }

    /** 从原生选择项取得文件系统路径，读取完成后释放选择项接口和系统分配的路径内存。 */
    private File readResult(ComObject dialog) throws FileDialogException {
        PointerByReference reference = new PointerByReference();
        requireSuccess(dialog.call(GET_RESULT, reference), "IFileDialog::GetResult");
        try (ComObject item = new ComObject(reference.getValue())) {
            PointerByReference path = new PointerByReference();
            try {
                requireSuccess(item.call(SHELL_ITEM_GET_DISPLAY_NAME, SIGDN_FILESYSPATH, path), "IShellItem::GetDisplayName");
                if (path.getValue() == null) throw new FileDialogException("原生文件面板未返回文件路径");
                String selectedPath = path.getValue().getWideString(0);
                if (selectedPath.isBlank()) throw new FileDialogException("原生文件面板返回的文件路径为空");
                return new File(selectedPath);
            } finally {
                if (path.getValue() != null) Ole32.INSTANCE.CoTaskMemFree(path.getValue());
            }
        }
    }

    /** 检查 HRESULT，失败时在异常中保留原生操作名和十六进制错误码。 */
    private static void requireSuccess(int status, String operation) throws FileDialogException {
        if (status < 0) throw new FileDialogException(operation + "失败，HRESULT=" + String.format("0x%08X", status));
    }

    /** IUnknown、IModalWindow、IFileDialog 的标准虚函数表调用均封装在平台实现内。 */
    private static final class ComObject extends Unknown implements AutoCloseable {
        /** 保存非空 COM 接口指针，后续原生方法调用及引用释放都使用该指针。 */
        private ComObject(Pointer pointer) { super(Objects.requireNonNull(pointer, "COM 接口指针为空")); }
        /** 把当前接口指针放在首个参数，按虚表索引调用 COM 方法并返回 HRESULT。 */
        private int call(int index, Object... parameters) {
            Object[] arguments = new Object[parameters.length + 1];
            arguments[0] = getPointer();
            System.arraycopy(parameters, 0, arguments, 1, parameters.length);
            return _invokeNativeInt(index, arguments);
        }
        /** 释放本次持有的 COM 接口引用，交还原生资源。 */
        @Override public void close() { Release(); }
    }

    /** 按 Windows 原生布局保存文件类型显示名称和匹配模式。 */
    @Structure.FieldOrder({"name", "pattern"})
    public static final class FilterSpec extends Structure {
        public WString name;
        public WString pattern;
    }

    /** 声明将初始文件路径转换为 Shell 选择项所需的原生接口。 */
    private interface ShellApi extends StdCallLibrary {
        ShellApi INSTANCE = Native.load("shell32", ShellApi.class);
        /** 从给定路径创建指定接口类型的 Shell 项，由调用方负责释放返回引用。 */
        HRESULT SHCreateItemFromParsingName(WString path, Pointer bindingContext,
                Guid.REFIID interfaceId, PointerByReference result);
    }
}
