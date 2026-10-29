package com.macro.flow;

/** 图像识别结果对应的流程行为，不属于底层输入动作类型。 */
public enum ImageCheckBehavior {
    RETRY("重试"),
    EXECUTE("执行"),
    TERMINATE("终止"),
    CLICK_EXECUTE("点击后执行"),
    CLICK_TERMINATE("点击后终止"),
    JUMP("跳转到指定节点");

    private final String displayName;

    ImageCheckBehavior(String displayName) {
        this.displayName = displayName;
    }

    public boolean requiresClick() {
        return this == CLICK_EXECUTE || this == CLICK_TERMINATE;
    }

    /** 点击只增加输入操作，后续流程仍沿用执行或终止语义。 */
    public ImageCheckBehavior flowBehavior() {
        return switch (this) {
            case CLICK_EXECUTE -> EXECUTE;
            case CLICK_TERMINATE -> TERMINATE;
            default -> this;
        };
    }

    /** 两种结果使用同一行为列表；以后增加行为时，跳转也始终在下拉列表最后。 */
    public static ImageCheckBehavior[] optionsForResult(boolean found) {
        return java.util.Arrays.stream(values())
                .sorted(java.util.Comparator.comparing(behavior -> behavior == JUMP))
                .toArray(ImageCheckBehavior[]::new);
    }

    @Override
    public String toString() {
        return displayName;
    }
}
