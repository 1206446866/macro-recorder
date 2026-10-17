package com.macro.flow;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 从当前节点通往目标节点的一条流程分支。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MacroBranch {

    /** 选择本分支所需的节点结果。 */
    private BranchCondition condition;

    /** 目标节点 ID。 */
    private String targetId;

    /** 跳转前的流程等待时间，单位毫秒。 */
    private long delay;
}
