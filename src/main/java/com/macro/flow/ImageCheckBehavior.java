package com.macro.flow;

/** 图像识别结果对应的流程行为，不属于底层输入动作类型。 */
public enum ImageCheckBehavior {
    RETRY("重试"),
    EXECUTE("执行"),
    TERMINATE("终止");

    private final String displayName;

    ImageCheckBehavior(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
