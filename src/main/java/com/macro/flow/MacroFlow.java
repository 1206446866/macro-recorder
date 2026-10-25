package com.macro.flow;

import com.fasterxml.jackson.annotation.JsonInclude;
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

    public static final long DEFAULT_HEAD_WAIT_MILLIS = 1_000L;

    /** 每轮流程开始执行的节点 ID。 */
    private String startId;

    /** 按节点 ID 快速取得节点的索引。 */
    private Map<String, MacroNode> nodeMap = new LinkedHashMap<>();

    /** 可选的每轮头部等待；null 表示关闭，不占用实际操作节点或分支 ID。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long headWaitMillis;

    /** 保留旧流程构造入口，旧 MR 未包含等待值时行为不变。 */
    public MacroFlow(String startId, Map<String, MacroNode> nodeMap) {
        this(startId, nodeMap, null);
    }
}
