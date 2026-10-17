package com.macro.flow;

/** IMAGE_CHECK 节点使用的图像匹配扩展点。 */
@FunctionalInterface
public interface ImageMatcher {

    /**
     * 执行当前图像检查节点。
     *
     * <p>正式播放默认由 OpenCV 实现完成屏幕采集与模板匹配。</p>
     *
     * @param node    当前 IMAGE_CHECK 节点
     * @param context 本次流程播放上下文
     * @return 找到或未找到，以及找到时的匹配区域
     * @throws Exception 图像采集或匹配失败
     */
    ImageMatchResult match(MacroNode node, FlowContext context) throws Exception;
}
