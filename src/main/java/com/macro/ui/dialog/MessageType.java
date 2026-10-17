package com.macro.ui.dialog;

/** 应用消息弹窗类型。 */
public enum MessageType {
    ERROR("错误"),
    WARNING("警告"),
    INFO("提示");

    private final String title;

    MessageType(String title) {
        this.title = title;
    }

    /** 获取弹窗标题。 */
    public String getTitle() {
        return title;
    }
}
