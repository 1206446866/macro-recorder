package com.macro.flow;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/** 使用入口 ID 和节点索引描述的宏流程。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MacroFlow {

    /** 每轮流程开始执行的节点 ID。 */
    private String startId;

    /** 按节点 ID 快速取得节点的索引。 */
    private Map<String, MacroNode> nodeMap = new LinkedHashMap<>();
}
