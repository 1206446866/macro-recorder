package com.macro.ui.filedialog;

import java.awt.Component;
import java.io.File;
import java.util.Optional;

/** 平台原生文件面板入口；取消返回空结果，调用失败抛出异常。 */
@FunctionalInterface
public interface FileDialogService {
    /** 同步取得选择结果；实现负责 UI 线程协作及平台资源的生命周期。 */
    Optional<File> showDialog(Component parent, FileDialogOptions options) throws FileDialogException;
}
