package com.macro.model;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Windows 窗口信息。
 *
 * <p>用于封装窗口识别结果，隔离 Windows API 与上层业务。</p>
 */
@Data(staticConstructor = "create")
@Accessors(chain = true)
public class WindowInfo {

    /** 窗口句柄。 */
    private String hwnd;

    /** 窗口标题。 */
    private String windowTitle;

    /** 窗口类名。 */
    private String windowClass;

    /** 窗口所属进程 ID。 */
    private int processId;

    /** 窗口所属进程名称。 */
    private String processName;
}