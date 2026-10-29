package com.macro.flow;

import lombok.Data;
import lombok.NoArgsConstructor;
import com.macro.model.TargetWindow;

/** IMAGE_CHECK 节点使用的模板图像配置。 */
@Data
@NoArgsConstructor
public class ImageCheckConfig {

    /** 模板 PNG 路径；相对路径以当前 MR 文件所在目录为基准。 */
    private String imagePath;

    /** 匹配阈值，取值范围为 0 到 1。 */
    private double threshold = 0.90;

    /** 原尺寸未命中时允许有限范围缩放匹配；旧文件缺少该字段时保持关闭。 */
    private boolean scaleMatchingEnabled;

    private ImageSearchScope searchScope = ImageSearchScope.DESKTOP;
    /** 只保存稳定描述，不将 HWND、PID 或当前坐标写进 MR。 */
    private TargetWindow searchWindow;

    public ImageSearchScope getSearchScope() {
        return searchScope == null ? ImageSearchScope.DESKTOP : searchScope;
    }

    public TargetWindow getSearchWindow() { return copyWindow(searchWindow); }
    public void setSearchWindow(TargetWindow value) { searchWindow = copyWindow(value); }

    private static TargetWindow copyWindow(TargetWindow value) {
        return value == null ? null : TargetWindow.create().setProcessName(value.getProcessName())
                .setWindowTitle(value.getWindowTitle()).setWindowClass(value.getWindowClass());
    }

    public void validateSearchScope() {
        if (getSearchScope() == ImageSearchScope.WINDOW && (searchWindow == null
                || searchWindow.getProcessName() == null || searchWindow.getProcessName().isBlank()
                || searchWindow.getWindowClass() == null || searchWindow.getWindowClass().isBlank())) {
            throw new IllegalArgumentException("窗口内识别需要指定有效的目标窗口");
        }
    }

    /** 点击行为使用的命中区域选点方式；旧文件缺省为中心点击。 */
    private ImageClickMode clickMode = ImageClickMode.CENTER;

    /** 未找到图像而选择点击行为时使用的独立屏幕逻辑坐标，缺省不猜测位置。 */
    private Integer failureClickX;
    private Integer failureClickY;

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

    public ImageClickMode getClickMode() {
        return clickMode == null ? ImageClickMode.CENTER : clickMode;
    }

    /** 未命中不使用旧命中区域，点击行为必须明确配置独立的屏幕坐标。 */
    public void validateClickBehaviors() {
        if (getFailureBehavior().requiresClick() && (failureClickX == null || failureClickY == null)) {
            throw new IllegalArgumentException("失败点击行为需要指定完整的屏幕 X/Y 坐标");
        }
    }

    /** 按本次实际匹配结果读取行为，不改变最近一次匹配区域。 */
    public ImageCheckBehavior behaviorFor(boolean found) {
        return found ? getSuccessBehavior() : getFailureBehavior();
    }
}
