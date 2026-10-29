package com.macro.ocr;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** 先写完整 UTF-8 内容，再替换用户明确选择的文本文件。 */
public final class OcrTextExport {
    private OcrTextExport() { }

    public static void save(Path destination, String text) throws IOException {
        Path target = destination.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "ocr-text-", ".tmp");
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
