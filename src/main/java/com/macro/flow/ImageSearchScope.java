package com.macro.flow;

/** 图像动作的搜索边界；旧 MR 默认保持桌面搜索。 */
public enum ImageSearchScope {
    DESKTOP("桌面"), WINDOW("指定窗口");
    private final String label;
    ImageSearchScope(String label) { this.label = label; }
    @Override public String toString() { return label; }
}
