package com.macro.storage;

import com.macro.flow.MacroFlow;
import com.macro.model.MacroAction;
import com.macro.model.TargetWindow;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * MR 文件数据。
 */
@Data
@Accessors(chain = true)
public class MacroFile {

    /**
     * MR 文件格式版本。
     */
    private int version = 1;

    /**
     * 宏动作列表。
     */
    private List<MacroAction> actions;

    /** 目标窗口信息。 */
    private TargetWindow targetWindow;

    /**
     * 可选流程图，是新版播放的完整数据来源；
     * 旧版 MR 没有此字段时继续使用 actions。
     */
    private MacroFlow flow;
}
