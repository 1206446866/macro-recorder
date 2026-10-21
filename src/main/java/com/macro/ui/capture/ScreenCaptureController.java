package com.macro.ui.capture;

import com.macro.ui.dialog.AppMessageDialog;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.MultiResolutionImage;
import java.util.Comparator;
import java.util.ArrayList;
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
    private final boolean hideAllApplicationWindows;
    private final CaptureSource captureSource;
    private long captureStartedNanos;
    /** 只恢复本次截图前确实可见的窗口，不复活截图期间已关闭的窗口。 */
    private final List<VisibleWindowState> hiddenWindows = new ArrayList<>();

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
        this(owner, onCaptured, onCancelled, onFailed, false);
    }

    /** 录制截图可隐藏本进程所有可见窗口，普通采集页面仍沿用原来的所属窗口行为。 */
    public ScreenCaptureController(JFrame owner, Consumer<BufferedImage> onCaptured,
            Runnable onCancelled, Consumer<Throwable> onFailed, boolean hideAllApplicationWindows) {
        this(owner, onCaptured, onCancelled, onFailed, hideAllApplicationWindows,
                (device, bounds) -> new Robot(device).createMultiResolutionScreenCapture(bounds));
    }

    /** 包内采集替身仅用于隔离测试，不改变正式的 Robot 捕获和冻结框选流程。 */
    ScreenCaptureController(JFrame owner, Consumer<BufferedImage> onCaptured,
            Runnable onCancelled, Consumer<Throwable> onFailed, boolean hideAllApplicationWindows,
            CaptureSource captureSource) {
        this.owner = Objects.requireNonNull(owner, "主窗口不能为空");
        this.onCaptured = Objects.requireNonNull(onCaptured, "截图回调不能为空");
        this.onCancelled = Objects.requireNonNull(onCancelled, "取消回调不能为空");
        this.onFailed = Objects.requireNonNull(onFailed, "失败回调不能为空");
        this.hideAllApplicationWindows = hideAllApplicationWindows;
        this.captureSource = Objects.requireNonNull(captureSource);
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
        captureStartedNanos = System.nanoTime();
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
        try {
            hideCaptureWindows();
        } catch (RuntimeException exception) {
            failCapture(sessionId, exception);
            return;
        }
        if (hideAllApplicationWindows) {
            // 录制专用路径：窗口已立即遮蔽，直接启动后台同步与采集，不等待隐藏动画。
            captureScreen(sessionId, device);
            return;
        }
        Toolkit.getDefaultToolkit().sync();

        // 给桌面窗口管理器留出刷新时间，避免截图中残留主窗口。
        captureTimer = new Timer(
                CAPTURE_DELAY_MILLIS,
                event -> captureScreen(sessionId, device)
        );
        captureTimer.setRepeats(false);
        captureTimer.start();
    }

    /** 先快照全部窗口，再隐藏，避免隐藏所属窗口后丢失子窗口原先的可见状态。 */
    private void hideCaptureWindows() {
        hiddenWindows.clear();
        if (hideAllApplicationWindows) {
            for (Window window : Window.getWindows()) {
                if (window.isVisible()) hiddenWindows.add(new VisibleWindowState(window,
                        window instanceof Frame frame ? frame.getExtendedState() : Frame.NORMAL));
            }
        } else if (ownerWasVisible) {
            hiddenWindows.add(new VisibleWindowState(owner, ownerExtendedState));
        }
        for (int index = hiddenWindows.size() - 1; index >= 0; index--) {
            VisibleWindowState saved = hiddenWindows.get(index);
            WindowsCaptureSupport.Cloak cloak = hideAllApplicationWindows
                    ? WindowsCaptureSupport.cloak(saved.window) : WindowsCaptureSupport.Cloak.UNAVAILABLE;
            hiddenWindows.set(index, new VisibleWindowState(saved.window, saved.extendedState, cloak));
            saved.window.setVisible(false);
        }
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
        boolean immediatelyHidden = hiddenWindows.stream().allMatch(saved -> saved.cloak.available());
        long startedNanos = captureStartedNanos;
        captureWorker = new SwingWorker<>() {
            @Override
            protected ScreenSnapshot doInBackground() throws Exception {
                long syncStarted = System.nanoTime();
                boolean immediateCapture = hideAllApplicationWindows && immediatelyHidden;
                if (hideAllApplicationWindows) {
                    Toolkit.getDefaultToolkit().sync();
                    if (!immediatelyHidden || !WindowsCaptureSupport.flush()) {
                        immediateCapture = false;
                        // 原生同步不可用时保留原有保守等待，仅在工作线程等待。
                        Thread.sleep(CAPTURE_DELAY_MILLIS);
                    }
                }
                if (isCancelled()) throw new CancellationException();
                long captureStarted = System.nanoTime();
                GraphicsConfiguration configuration = device.getDefaultConfiguration();
                Rectangle screenBounds = configuration.getBounds();
                MultiResolutionImage capture = captureSource.capture(device, screenBounds);
                BufferedImage image = copyHighestResolutionVariant(capture);
                if (Boolean.getBoolean("macro.capture.timing")) {
                    long finished = System.nanoTime();
                    System.out.printf("capture timing: immediate=%s sync=%.2f capture=%.2f requestToImage=%.2f ms%n",
                            immediateCapture, (captureStarted-syncStarted)/1e6,
                            (finished-captureStarted)/1e6, (finished-startedNanos)/1e6);
                }
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
        // 所属窗口的遮蔽可被子窗口继承；图像已经采集，先解除临时遮蔽再显示框选层。
        // 原窗口仍保持 Swing 隐藏，不会重新进入这张冻结图像。
        for (VisibleWindowState saved : hiddenWindows) saved.cloak.close();
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

        for (VisibleWindowState saved : hiddenWindows) {
            try {
                if (!saved.window.isDisplayable()) continue;
                if (saved.window instanceof Frame frame) frame.setExtendedState(saved.extendedState);
                saved.window.setVisible(true);
            } catch (RuntimeException failure) {
                System.err.println("截图结束后恢复窗口失败：" + failure.getMessage());
            } finally {
                saved.cloak.close();
            }
        }
        hiddenWindows.clear();
        if (ownerWasVisible) {
            if ((ownerExtendedState & Frame.ICONIFIED) == 0) {
                owner.toFront();
                owner.requestFocus();
            }
        }
    }

    private record VisibleWindowState(Window window, int extendedState, WindowsCaptureSupport.Cloak cloak) {
        private VisibleWindowState(Window window, int extendedState) {
            this(window, extendedState, WindowsCaptureSupport.Cloak.UNAVAILABLE);
        }
    }

    @FunctionalInterface
    interface CaptureSource {
        MultiResolutionImage capture(GraphicsDevice device, Rectangle bounds) throws Exception;
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
