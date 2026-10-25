package com.macro.exception;

import lombok.Getter;

/** MR 文件格式版本与当前软件不兼容时抛出的异常。 */
@Getter
public class MacroFormatException extends Exception {

    private final int fileVersion;
    private final int currentVersion;

    public MacroFormatException(int fileVersion, int currentVersion) {
        super(createMessage(fileVersion, currentVersion));
        this.fileVersion = fileVersion;
        this.currentVersion = currentVersion;
    }

    private static String createMessage(int fileVersion, int currentVersion) {
        if (fileVersion > currentVersion) {
            return "MR 文件版本 " + fileVersion
                    + " 超出当前支持版本 " + currentVersion;
        }
        if (fileVersion < currentVersion) {
            return "MR 文件版本 " + fileVersion
                    + " 低于当前支持版本 " + currentVersion;
        }
        return "MR 文件版本 " + fileVersion
                + " 与当前支持版本 " + currentVersion + " 不兼容";
    }
}
