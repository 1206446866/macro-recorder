package com.macro.model;

public enum MouseButton {
    LEFT,
    MIDDLE,
    RIGHT,
    X1,
    X2;

    /** X1/X2 仅保留旧数据的反序列化身份，不对外提供录制、编辑或执行支持。 */
    public boolean isSupported() {
        return this == LEFT || this == MIDDLE || this == RIGHT;
    }
}
