package com.macro.ui.dialog;

import lombok.Getter;

/** 应用消息弹窗类型。 */
@Getter
public enum MessageType {
    ERROR("错误"),
    WARNING("警告"),
    INFO("提示");

    /**
     * -- GETTER --
     * 获取弹窗标题。
     */
    private final String title;

    MessageType(String title) {
        this.title = title;
    }

}
