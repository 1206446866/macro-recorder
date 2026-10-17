package com.macro.model;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 宏目标窗口信息。
 *
 * <p>用于描述宏需要操作的目标窗口。
 * HWND 仅作为运行时窗口句柄，不作为宏文件中的永久标识。</p>
 */
@Data(staticConstructor = "create")
@Accessors(chain = true)
public class TargetWindow {

    /** 目标窗口所属进程名称。 */
    private String processName;

    /** 目标窗口标题。 */
    private String windowTitle;

    /** 目标窗口类名。 */
    private String windowClass;
}