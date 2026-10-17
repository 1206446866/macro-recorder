package com.macro.recognition;

import com.macro.model.TargetWindow;
import com.macro.util.WindowsWindowUtil;
import com.sun.jna.Platform;
import com.sun.jna.platform.win32.WinDef.HWND;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** 临时识别诊断：只记录观测信息，不改变截图、匹配或分支行为。 */
public final class ImageRecognitionDiagnostics {

    /** 默认输出诊断文本，可通过启动参数关闭。 */
    private static final String ENABLED_PROPERTY = "macro.image.diagnostics.enabled";

    /** 实际参与匹配的屏幕图像和模板仅在显式开启时写入磁盘。 */
    private static final String CAPTURE_PROPERTY = "macro.image.diagnostics.capture";

    /** 默认使用系统临时目录，允许测试或排查时指定诊断目录。 */
    private static final String DIRECTORY_PROPERTY = "macro.image.diagnostics.directory";

    /** 区分同一节点的重复识别和测试窗口同时发起的识别。 */
    private static final AtomicLong REQUEST_SEQUENCE = new AtomicLong();

    private ImageRecognitionDiagnostics() {
    }

    public static String nextRequestId(String source) {
        return source + "-" + REQUEST_SEQUENCE.incrementAndGet();
    }

    /** 使用一次输出保留完整日志块，避免播放线程和测试线程的字段互相穿插。 */
    public static void log(String source, String phase, String details) {
        if (Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"))) {
            System.out.println("[图像识别诊断][" + source + "][" + phase + "] "
                    + Instant.now() + " 线程=" + Thread.currentThread().getName()
                    + System.lineSeparator() + details);
        }
    }

    /** 保留原始比例和百分数，避免把 0.9 与 90 混淆。 */
    public static String similarity(double value) {
        return String.format(Locale.ROOT, "%.6f (%.2f%%)", value, value * 100.0);
    }

    /** 只读取截图前的前台窗口，不激活、隐藏或恢复任何窗口。 */
    public static String foregroundWindow() {
        if (!Platform.isWindows()) {
            return "<非 Windows 环境>";
        }
        try {
            HWND hwnd = WindowsWindowUtil.getForegroundWindow();
            if (hwnd == null) {
                return "<无前台窗口>";
            }
            TargetWindow window = WindowsWindowUtil.getTargetWindow(hwnd);
            return "HWND=" + hwnd + ", Process=" + window.getProcessName()
                    + ", Title=" + window.getWindowTitle()
                    + ", Class=" + window.getWindowClass();
        } catch (RuntimeException | LinkageError exception) {
            // 获取诊断信息失败不能让原本可用的识别变成播放异常。
            return "<读取失败：" + exception + ">";
        }
    }

    /** 输出实际截图的像素尺寸、逻辑范围及 DPI 换算比例。 */
    public static String describeScreens(List<ScreenRecognitionService.ScreenSnapshot> snapshots) {
        StringBuilder description = new StringBuilder("ScreenCount: ").append(snapshots.size());
        for (int index = 0; index < snapshots.size(); index++) {
            var snapshot = snapshots.get(index);
            var bounds = snapshot.logicalBounds();
            description.append(String.format(Locale.ROOT,
                    "%nScreen[%d]: %s, Pixels=%dx%d, LogicalBounds=%s, Scale=%.3fx%.3f",
                    index + 1, snapshot.displayName(), snapshot.image().getWidth(),
                    snapshot.image().getHeight(), bounds,
                    snapshot.image().getWidth() / (double) Math.max(1, bounds.width),
                    snapshot.image().getHeight() / (double) Math.max(1, bounds.height)));
        }
        return description.toString();
    }

    /**
     * 按需保存已经用于匹配的图像，不再次截图；失败仅写诊断日志。
     * 每次识别使用独立目录，避免重试覆盖上一帧。
     */
    public static void saveCapture(String source, String requestId,
            List<ScreenRecognitionService.ScreenSnapshot> snapshots,
            BufferedImage template, String metadata) {
        if (!Boolean.getBoolean(CAPTURE_PROPERTY)) {
            return;
        }
        try {
            Path root = Path.of(System.getProperty(DIRECTORY_PROPERTY,
                    Path.of(System.getProperty("java.io.tmpdir"),
                            "MacroRecorder-image-diagnostics").toString()));
            Path directory = root.toAbsolutePath().normalize()
                    .resolve(requestId + "-" + UUID.randomUUID());
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("diagnostics.txt"),
                    metadata + System.lineSeparator() + describeScreens(snapshots), StandardCharsets.UTF_8);
            ImageIO.write(template, "png", directory.resolve("template.png").toFile());
            for (int index = 0; index < snapshots.size(); index++) {
                ImageIO.write(snapshots.get(index).image(), "png",
                        directory.resolve("screen-" + (index + 1) + ".png").toFile());
            }
            log(source, "CAPTURE_SAVED", "Request: " + requestId + "\nDirectory: " + directory);
        } catch (Exception | LinkageError exception) {
            System.err.println("[图像识别诊断][" + source + "][CAPTURE_SAVE_ERROR] Request: "
                    + requestId + "，保存诊断图像失败：" + exception);
        }
    }
}
