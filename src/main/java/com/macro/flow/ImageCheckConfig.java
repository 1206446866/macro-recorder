package com.macro.flow;

import lombok.Data;
import lombok.NoArgsConstructor;

/** IMAGE_CHECK 节点使用的模板图像配置。 */
@Data
@NoArgsConstructor
public class ImageCheckConfig {

    /** 模板 PNG 路径；相对路径以当前 MR 文件所在目录为基准。 */
    private String imagePath;

    /** 匹配阈值，取值范围为 0 到 1。 */
    private double threshold = 0.90;

    /** 命中后的行为；旧文件缺少该字段时继续执行。 */
    private ImageCheckBehavior successBehavior = ImageCheckBehavior.EXECUTE;

    /** 未命中后的行为；旧文件缺少该字段时重试当前节点。 */
    private ImageCheckBehavior failureBehavior = ImageCheckBehavior.RETRY;

    /** 两种结果共用的重试等待；旧文件为空时从原分支读取。 */
    private Long retryDelay;

    /** 保留录制采集等入口使用的原构造方式。 */
    public ImageCheckConfig(String imagePath, double threshold) {
        this.imagePath = imagePath;
        this.threshold = threshold;
    }

    public ImageCheckBehavior getSuccessBehavior() {
        return successBehavior == null ? ImageCheckBehavior.EXECUTE : successBehavior;
    }

    public ImageCheckBehavior getFailureBehavior() {
        return failureBehavior == null ? ImageCheckBehavior.RETRY : failureBehavior;
    }

    /** 按本次实际匹配结果读取行为，不改变最近一次匹配区域。 */
    public ImageCheckBehavior behaviorFor(boolean found) {
        return found ? getSuccessBehavior() : getFailureBehavior();
    }
}
