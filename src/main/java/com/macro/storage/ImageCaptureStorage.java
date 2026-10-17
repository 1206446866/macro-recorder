package com.macro.storage;

import com.macro.constant.ImageCaptureFormat;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/** 负责将截图安全保存为 PNG 文件。 */
public final class ImageCaptureStorage {

    private static final DateTimeFormatter CAPTURE_FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    /** 为一次新截图生成默认 PNG 文件位置。 */
    public File createCaptureFile(File directory) {
        Objects.requireNonNull(directory, "截图目录不能为空");
        String fileName = "capture_"
                + LocalDateTime.now().format(CAPTURE_FILE_TIME)
                + ImageCaptureFormat.FILE_SUFFIX;
        return new File(directory, fileName);
    }

    /**
     * 保存截图。
     *
     * <p>先在目标目录写入临时文件，编码完成后再替换正式文件，
     * 避免保存中断时留下不完整图片。</p>
     *
     * @param image      待保存截图
     * @param targetFile 目标 PNG 文件
     * @return 实际保存文件
     * @throws IOException 图片编码或文件写入失败
     */
    public File savePng(BufferedImage image, File targetFile) throws IOException {
        Objects.requireNonNull(image, "截图不能为空");
        Objects.requireNonNull(targetFile, "保存文件不能为空");

        File normalizedFile = ensurePngExtension(targetFile).getAbsoluteFile();
        Path targetPath = normalizedFile.toPath();
        Path parentPath = targetPath.getParent();
        if (parentPath == null) {
            throw new IOException("无法确定图片保存目录");
        }
        Files.createDirectories(parentPath);

        Path tempPath = Files.createTempFile(
                parentPath,
                "screen-capture-",
                ".tmp"
        );
        try {
            boolean written = ImageIO.write(image, ImageCaptureFormat.FORMAT_NAME, tempPath.toFile());
            if (!written) {
                throw new IOException("当前环境不支持 PNG 图片编码");
            }

            try {
                Files.move(
                        tempPath,
                        targetPath,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(
                        tempPath,
                        targetPath,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }
            return normalizedFile;
        } finally {
            Files.deleteIfExists(tempPath);
        }
    }

    /** 保证目标文件使用 PNG 扩展名。 */
    private File ensurePngExtension(File file) {
        if (file.getName().toLowerCase().endsWith(ImageCaptureFormat.FILE_SUFFIX)) {
            return file;
        }
        return new File(file.getAbsolutePath() + ImageCaptureFormat.FILE_SUFFIX);
    }
}
