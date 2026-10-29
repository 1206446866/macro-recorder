package com.macro.ocr;

/** 内置轻量语言包；选择实际需要的语言可减少加载和识别开销。 */
public enum OcrLanguage {
    SIMPLIFIED_CHINESE("简体中文", "chi_sim"),
    TRADITIONAL_CHINESE("繁体中文", "chi_tra"),
    ENGLISH("英语", "eng"),
    JAPANESE("日语", "jpn"),
    KOREAN("韩语", "kor");

    private final String label;
    private final String code;
    OcrLanguage(String label, String code) { this.label = label; this.code = code; }
    public String code() { return code; }
    @Override public String toString() { return label; }
}
