package com.macro.constant;

/** 截图编码与文件选择共用的格式信息。 */
public final class ImageCaptureFormat {

    public static final String FORMAT_NAME = "png";
    public static final String FILE_SUFFIX = "." + FORMAT_NAME;
    public static final String FILE_FILTER_DESCRIPTION = "PNG 图片 (*" + FILE_SUFFIX + ")";

    private ImageCaptureFormat() {
    }
}
