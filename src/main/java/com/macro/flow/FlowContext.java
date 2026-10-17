package com.macro.flow;

/** 一次流程播放期间共享的运行上下文。 */
public final class FlowContext {

    /** 最近一次 IMAGE_CHECK 的完整结果。 */
    private ImageMatchResult lastImageMatch;

    public ImageMatchResult getLastImageMatch() {
        return lastImageMatch;
    }

    /**
     * 由流程播放器在图像检查完成后记录结果，供后续动态坐标节点读取。
     *
     * @param lastImageMatch 最近一次图像匹配结果
     */
    public void setLastImageMatch(ImageMatchResult lastImageMatch) {
        this.lastImageMatch = lastImageMatch;
    }
}
