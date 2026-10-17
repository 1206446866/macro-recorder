package com.macro.flow;

import com.macro.model.MacroAction;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** 宏流程中的一个可寻址节点。 */
@Data
@NoArgsConstructor
public class MacroNode {

    /** 节点唯一 ID。 */
    private String id;

    /** 节点类型。 */
    private NodeType type;

    /** ACTION 节点执行的现有宏动作。 */
    private MacroAction action;

    /** IMAGE_CHECK 节点使用的图像匹配配置。 */
    private ImageCheckConfig imageCheckConfig;

    /** 当前节点执行完成后可选择的分支。 */
    private List<MacroBranch> children = new ArrayList<>();

    /** 创建不带图像识别配置的 ACTION 或 END 节点。 */
    public MacroNode(
            String id,
            NodeType type,
            MacroAction action,
            List<MacroBranch> children
    ) {
        this(id, type, action, null, children);
    }

    /** 创建包含完整类型配置和分支信息的流程节点。 */
    public MacroNode(
            String id,
            NodeType type,
            MacroAction action,
            ImageCheckConfig imageCheckConfig,
            List<MacroBranch> children
    ) {
        this.id = id;
        this.type = type;
        this.action = action;
        this.imageCheckConfig = imageCheckConfig;
        this.children = children;
    }
}
