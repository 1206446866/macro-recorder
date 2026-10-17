package com.macro.flow;

/** 流程节点类型。 */
public enum NodeType {

    /** 执行一个现有宏动作。 */
    ACTION,

    /** 调用图像匹配扩展点并产生找到或未找到结果。 */
    IMAGE_CHECK,

    /** 结束当前一轮流程。 */
    END
}
