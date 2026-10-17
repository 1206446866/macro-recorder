package com.macro.ui.filedialog;

/** 原生文件面板不可用或调用失败，与用户主动取消相区分。 */
public final class FileDialogException extends Exception {
    public FileDialogException(String message) {
        super(message);
    }

    public FileDialogException(String message, Throwable cause) {
        super(message, cause);
    }
}
