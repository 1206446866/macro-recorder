package com.macro.flow;

import lombok.Getter;
import lombok.Setter;

/** 一次流程播放期间共享的运行上下文。 */
@Setter
@Getter
public final class FlowContext {

    /** 最近一次 IMAGE_CHECK 的完整结果。
     * -- SETTER --
     *  由流程播放器在图像检查完成后记录结果，供后续动态坐标节点读取。
     *
     */
    private ImageMatchResult lastImageMatch;

}
