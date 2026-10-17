package com.macro.ui.capture;

import com.macro.ui.dialog.AppMessageDialog;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.MultiResolutionImage;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * 屏幕截图流程控制器。
 *
 * <p>负责隐藏和恢复主窗口、采集鼠标所在屏幕，并把选中的图像交还给调用方。</p>
 */
public final class ScreenCaptureController {

    private static final int CAPTURE_DELAY_MILLIS = 150;

    private final JFrame owner;
    private final Consumer<BufferedImage> onCaptured;
    private final Runnable onCancelled;
    private final Consumer<Throwable> onFailed;

    private boolean capturing;
    private boolean ownerWasVisible;
    private int ownerExtendedState;
    private Timer captureTimer;
    private SwingWorker<ScreenSnapshot, Void> captureWorker;
    private ScreenSelectionOverlay overlay;
    private long sessionSequence;
    private long activeSessionId;

    public ScreenCaptureController(
            JFrame owner,
            Consumer<BufferedImage> onCaptured
    ) {
        this(
                owner,
                onCaptured,
                () -> {
                },
                throwable -> AppMessageDialog.showError(owner, "截图失败", throwable)
        );
    }

    /**
     * 创建带完整结束回调的截图控制器。
     *
     * @param owner 所属主窗口
     * @param onCaptured 选区确认回调
     * @param onCancelled 取消选区回调
     * @param onFailed 截图或选区处理失败回调
     */
    public ScreenCaptureController(
            JFrame owner,
            Consumer<BufferedImage> onCaptured,
            Runnable onCancelled,
            Consumer<Throwable> onFailed
    ) {
        this.owner = Objects.requireNonNull(owner, "主窗口不能为空");
        this.onCaptured = Objects.requireNonNull(onCaptured, "截图回调不能为空");
        this.onCancelled = Objects.requireNonNull(onCancelled, "取消回调不能为空");
        this.onFailed = Objects.requireNonNull(onFailed, "失败回调不能为空");
    }

    /** 开始一次截图。重复调用时会忽略尚未结束的截图请求。 */
    public void startCapture() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::startCapture);
            return;
        }

        if (capturing) {
            return;
        }

        capturing = true;
        long sessionId = ++sessionSequence;
        activeSessionId = sessionId;
        ownerWasVisible = owner.isVisible();
        ownerExtendedState = owner.getExtendedState();
        GraphicsDevice device;
        try {
            device = findPointerDevice();
        } catch (RuntimeException exception) {
            failCapture(sessionId, exception);
            return;
        }
        owner.setVisible(false);
        Toolkit.getDefaultToolkit().sync();

        // 给桌面窗口管理器留出刷新时间，避免截图中残留主窗口。
        captureTimer = new Timer(
                CAPTURE_DELAY_MILLIS,
                event -> captureScreen(sessionId, device)
        );
        captureTimer.setRepeats(false);
        captureTimer.start();
    }

    /** 从外部取消当前截图，例如录制期间按下 F12。 */
    public void cancelCapture() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::cancelCapture);
            return;
        }
        if (capturing) {
            cancelCapture(activeSessionId);
        }
    }

    /** 在工作线程中采集鼠标所在屏幕。 */
    private void captureScreen(long sessionId, GraphicsDevice device) {
        if (!isCurrentSession(sessionId)) {
            return;
        }
        captureTimer = null;
        captureWorker = new SwingWorker<>() {
            @Override
            protected ScreenSnapshot doInBackground() throws Exception {
                GraphicsConfiguration configuration = device.getDefaultConfiguration();
                Rectangle screenBounds = configuration.getBounds();
                Robot robot = new Robot(device);
                MultiResolutionImage capture =
                        robot.createMultiResolutionScreenCapture(screenBounds);
                BufferedImage image = copyHighestResolutionVariant(capture);
                return new ScreenSnapshot(image, screenBounds, configuration);
            }

            @Override
            protected void done() {
                if (!isCurrentSession(sessionId)) {
                    return;
                }
                captureWorker = null;
                try {
                    ScreenSnapshot snapshot = get();
                    showSelectionOverlay(sessionId, snapshot);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    failCapture(sessionId, exception);
                } catch (CancellationException exception) {
                    cancelCapture(sessionId);
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause() == null
                            ? exception
                            : exception.getCause();
                    failCapture(sessionId, cause);
                } catch (RuntimeException exception) {
                    failCapture(sessionId, exception);
                }
            }
        };
        captureWorker.execute();
    }

    /** 获取鼠标当前所在的屏幕设备。 */
    private GraphicsDevice findPointerDevice() {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("当前环境不支持屏幕截图");
        }

        PointerInfo pointerInfo = MouseInfo.getPointerInfo();
        if (pointerInfo != null && pointerInfo.getDevice() != null) {
            return pointerInfo.getDevice();
        }

        return GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .getDefaultScreenDevice();
    }

    /** 复制截图中像素数量最多的分辨率版本。 */
    private BufferedImage copyHighestResolutionVariant(
            MultiResolutionImage capture
    ) {
        List<Image> variants = capture.getResolutionVariants();
        Image highestResolution = variants.stream()
                .filter(image -> image.getWidth(null) > 0 && image.getHeight(null) > 0)
                .max(Comparator.comparingLong(image ->
                        (long) image.getWidth(null) * image.getHeight(null)))
                .orElseThrow(() -> new IllegalStateException("未获取到屏幕图像"));

        int width = highestResolution.getWidth(null);
        int height = highestResolution.getHeight(null);
        BufferedImage copied = new BufferedImage(
                width,
                height,
                BufferedImage.TYPE_INT_ARGB
        );
        Graphics2D graphics = copied.createGraphics();
        graphics.drawImage(highestResolution, 0, 0, null);
        graphics.dispose();
        return copied;
    }

    /** 显示冻结画面的区域选择窗口。 */
    private void showSelectionOverlay(long sessionId, ScreenSnapshot snapshot) {
        overlay = new ScreenSelectionOverlay(
                owner,
                snapshot.image(),
                snapshot.screenBounds(),
                snapshot.configuration(),
                image -> completeCapture(sessionId, image),
                () -> cancelCapture(sessionId),
                throwable -> failCapture(sessionId, throwable)
        );
        overlay.showOverlay();
    }

    /** 完成截图并通知页面更新预览。 */
    private void completeCapture(long sessionId, BufferedImage image) {
        if (!isCurrentSession(sessionId)) {
            return;
        }
        finishCapture(sessionId);
        try {
            onCaptured.accept(image);
        } catch (RuntimeException exception) {
            onFailed.accept(exception);
        }
    }

    /** 取消当前截图。 */
    private void cancelCapture(long sessionId) {
        if (!isCurrentSession(sessionId)) {
            return;
        }
        finishCapture(sessionId);
        onCancelled.run();
    }

    /** 结束截图并显示统一错误提示。 */
    private void failCapture(long sessionId, Throwable throwable) {
        if (!isCurrentSession(sessionId)) {
            return;
        }
        finishCapture(sessionId);
        onFailed.accept(throwable);
    }

    /**
     * 统一释放截图窗口并恢复主窗口状态。
     *
     * <p>成功、取消和异常都通过此方法收尾，避免主窗口被永久隐藏。</p>
     */
    private void finishCapture(long sessionId) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> finishCapture(sessionId));
            return;
        }
        if (!isCurrentSession(sessionId)) {
            return;
        }

        activeSessionId = 0;
        capturing = false;

        if (captureTimer != null) {
            captureTimer.stop();
            captureTimer = null;
        }
        if (captureWorker != null && !captureWorker.isDone()) {
            captureWorker.cancel(true);
            captureWorker = null;
        }
        if (overlay != null) {
            overlay.closeOverlay();
            overlay = null;
        }

        owner.setExtendedState(ownerExtendedState);
        owner.setVisible(ownerWasVisible);
        if (ownerWasVisible) {
            if ((ownerExtendedState & Frame.ICONIFIED) == 0) {
                owner.toFront();
                owner.requestFocus();
            }
        }
    }

    /** 判断回调是否仍属于当前截图会话。 */
    private boolean isCurrentSession(long sessionId) {
        return capturing && activeSessionId == sessionId;
    }

    /** 一次屏幕采集结果。 */
    private record ScreenSnapshot(
            BufferedImage image,
            Rectangle screenBounds,
            GraphicsConfiguration configuration
    ) {
    }
}
