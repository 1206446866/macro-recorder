package com.macro.ocr;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.ref.Reference;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/** 本机离线 OCR；每次识别独占并释放原生引擎，不持有图片或宏状态。 */
public final class OcrService implements OcrEngine {
    private static final Semaphore ENGINE_SLOT = new Semaphore(1);
    @Override public String extract(BufferedImage image, Set<OcrLanguage> languages, boolean singleLine,
                                    BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(image, "待识别图片不能为空");
        Objects.requireNonNull(cancelled, "取消信号不能为空");
        if (languages == null || languages.isEmpty() || languages.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("请至少选择一种识别语言");
        }
        String language = EnumSet.copyOf(languages).stream().map(OcrLanguage::code).collect(Collectors.joining("+"));
        if (!ENGINE_SLOT.tryAcquire()) throw new IOException("已有文字提取正在进行，请稍后重试");
        try {
            return extractExclusive(image, language, singleLine, cancelled);
        } finally {
            ENGINE_SLOT.release();
        }
    }

    private String extractExclusive(BufferedImage image, String language, boolean singleLine,
                                    BooleanSupplier cancelled) throws IOException {
        OcrNativeRuntime.checkCancelled(cancelled);
        var runtime = OcrNativeRuntime.load(cancelled);
        var api = runtime.api();
        int stride = Math.multiplyExact(image.getWidth(), 3);
        Thread caller = Thread.currentThread();
        OcrNativeRuntime.CancelCallback callback = (context, words) ->
                (byte) ((caller.isInterrupted() || cancelled.getAsBoolean()) ? 1 : 0);
        try (Memory rgb = new Memory(Math.multiplyExact((long) stride, image.getHeight()))) {
            copyRgb(image, rgb, stride, cancelled);
            Pointer engine = api.TessBaseAPICreate();
            if (engine == null) throw new IOException("无法创建文字提取引擎");
            Pointer monitor = null;
            Pointer text = null;
            try {
                if (api.TessBaseAPIInit3(engine, runtime.nativeDataPath(), language) != 0) {
                    throw new IOException("无法加载所选文字语言包");
                }
                OcrNativeRuntime.checkCancelled(cancelled);
                api.TessBaseAPISetPageSegMode(engine, singleLine ? 7 : 3);
                api.TessBaseAPISetImage(engine, rgb, image.getWidth(), image.getHeight(), 3, stride);
                monitor = api.TessMonitorCreate();
                if (monitor == null) throw new IOException("无法创建文字提取进度控制");
                api.TessMonitorSetCancelFunc(monitor, callback);
                api.TessMonitorSetDeadlineMSecs(monitor, 30_000);
                int status = api.TessBaseAPIRecognize(engine, monitor);
                OcrNativeRuntime.checkCancelled(cancelled);
                if (status != 0) throw new IOException("文字提取未完成或已超时，请缩小图片范围后重试");
                text = api.TessBaseAPIGetUTF8Text(engine);
                OcrNativeRuntime.checkCancelled(cancelled);
                return text == null ? "" : text.getString(0, "UTF-8").replace("\r\n", "\n").strip();
            } finally {
                if (text != null) api.TessDeleteText(text);
                api.TessBaseAPIDelete(engine);
                if (monitor != null) api.TessMonitorDelete(monitor);
                Reference.reachabilityFence(callback);
            }
        }
    }

    private static void copyRgb(BufferedImage image, Memory output, int stride, BooleanSupplier cancelled) {
        int[] pixels = new int[image.getWidth()];
        byte[] row = new byte[stride];
        for (int y = 0; y < image.getHeight(); y++) {
            OcrNativeRuntime.checkCancelled(cancelled);
            image.getRGB(0, y, image.getWidth(), 1, pixels, 0, image.getWidth());
            for (int x = 0; x < pixels.length; x++) {
                int pixel = pixels[x], alpha = pixel >>> 24;
                for (int channel = 0; channel < 3; channel++) {
                    int value = (pixel >>> (16 - channel * 8)) & 255;
                    row[x * 3 + channel] = (byte) ((value * alpha + 255 * (255 - alpha) + 127) / 255);
                }
            }
            output.write((long) y * stride, row, 0, row.length);
        }
    }
}
