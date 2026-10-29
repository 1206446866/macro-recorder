package com.macro.ocr;

import java.awt.image.BufferedImage;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** 图片到文字，不发送输入或改变宏流程。 */
@FunctionalInterface
public interface OcrEngine {
    String extract(BufferedImage image, Set<OcrLanguage> languages, boolean singleLine,
                   BooleanSupplier cancelled) throws Exception;
}
