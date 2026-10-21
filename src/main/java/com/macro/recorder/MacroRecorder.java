package com.macro.recorder;


import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import com.github.kwhat.jnativehook.mouse.NativeMouseEvent;
import com.github.kwhat.jnativehook.mouse.NativeMouseInputListener;
import com.github.kwhat.jnativehook.mouse.NativeMouseWheelEvent;
import com.github.kwhat.jnativehook.mouse.NativeMouseWheelListener;
import com.macro.config.AppConfig;
import com.macro.flow.ImageCheckConfig;
import com.macro.flow.MacroFlowFactory;
import com.macro.flow.MacroNode;
import com.macro.flow.NodeType;
import com.macro.model.MacroAction;
import com.macro.model.MouseButton;
import com.macro.model.TargetWindow;
import com.macro.util.WindowsDpiUtil;
import com.macro.util.WindowsMouseUtil;
import com.macro.util.WindowsWindowUtil;
import com.sun.jna.platform.win32.WinDef;
import lombok.Setter;
import lombok.SneakyThrows;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;


public class MacroRecorder implements NativeMouseInputListener, NativeMouseWheelListener, NativeKeyListener {

    /** 桥接轨迹允许生成的最大移动节点数，避免极端距离产生过多动作。 */
    private static final int MAX_BRIDGE_MOVE_COUNT = 80;

    /** 无采样间隔时，每个桥接节点期望覆盖的逻辑像素距离。 */
    private static final int DEFAULT_BRIDGE_STEP_DISTANCE = 24;

    private final AppConfig appConfig;

    /** 同时保护录制状态、时间基准、动作列表和流程节点列表。 */
    private final Object recordingLock = new Object();

    /** 控制栏输入及挂起区间的过滤器，不改变 NativeHook 的注册方式。 */
    private final RecordingControlInputFilter controlInputFilter = new RecordingControlInputFilter();

    /** 只跟踪确实写入宏的按下动作，用于跨控制栏、暂停或截图边界配对释放。 */
    private final Map<Integer, MacroAction> pressedMouseButtons = new HashMap<>();
    private final Map<Integer, MacroAction> pressedKeys = new HashMap<>();

    private long pauseStartedAt;
    private boolean imageCaptureStartedPaused;
    private boolean imageCaptureBridgeAllowed;
    private LogicalPoint lastRecordedMousePoint;

    private final List<MacroAction> actions = new ArrayList<>();

    /** 按真实录制顺序保存 ACTION 与 IMAGE_CHECK 节点。 */
    private final List<MacroNode> recordedNodes = new ArrayList<>();

    private final double dpiScale;

    /** 仅用于截图会话边界的即时位置查询，不能代替已发生事件的坐标。 */
    private final Supplier<WindowsMouseUtil.POINT> cursorPosition;

    /** 使用物理屏幕坐标查询顶层窗口，保持与控件区域过滤相同的坐标空间。 */
    private final BiFunction<Integer, Integer, WinDef.HWND> rootWindowAtPoint;

    private long lastTime;

    private long lastMouseMoveTime;

    /** 已从宏时间轴排除的图像采集及普通暂停总耗时。 */
    private long excludedCaptureDuration;

    /** 图像采集会话序号，用于拒绝过期的异步回调。 */
    private long captureSessionSequence;

    /** 当前录制器状态，NativeHook 回调依靠它过滤采集期间的输入。 */
    private RecorderState state = RecorderState.IDLE;

    /** 当前正在进行的图像采集会话。 */
    private ImageCaptureSession activeImageCaptureSession;

    /** 当前录制目标窗口，null 表示全局录制。 */
    @Setter
    private TargetWindow  targetWindow;

    /**
     * 当前录制目标窗口句柄。
     */
    private WinDef.HWND targetHwnd;

    @Setter
    private Runnable onStop;

    /** 录制期间请求打开冻结截图选区时调用。 */
    @Setter
    private Consumer<ImageCaptureSession> onImageCaptureRequested;

    public MacroRecorder(AppConfig appConfig) {
        this(appConfig, WindowsDpiUtil.getScale(), WindowsMouseUtil::getCursorPosition,
                (x, y) -> WindowsWindowUtil.getRootWindow(WindowsWindowUtil.getWindowFromPoint(x, y)));
    }

    /** 允许坐标回归检查注入指针与窗口查询，不注册钩子，也不操作真实桌面。 */
    MacroRecorder(AppConfig appConfig, double dpiScale, Supplier<WindowsMouseUtil.POINT> cursorPosition,
            BiFunction<Integer, Integer, WinDef.HWND> rootWindowAtPoint) {
        this.appConfig = appConfig;
        this.dpiScale = dpiScale;
        this.cursorPosition = cursorPosition;
        this.rootWindowAtPoint = rootWindowAtPoint;
    }

    /**
     * 开始全局录制。
     *
     * @throws NativeHookException 注册全局钩子失败
     */
    public void start() throws NativeHookException {
        start(null);
    }

    /**
     * 开始录制。
     *
     * @param targetWindow 目标窗口，为 null 时进行全局录制
     * @throws NativeHookException 注册全局钩子失败
     */
    public void start(TargetWindow targetWindow) throws NativeHookException {
        this.targetWindow = targetWindow;
        this.targetHwnd = targetWindow == null
                ? null
                : WindowsWindowUtil.findWindow(targetWindow);

        if (targetWindow != null && targetHwnd == null) {
            throw new IllegalStateException("未找到目标窗口");
        }

        if (!GlobalScreen.isNativeHookRegistered()) {
            GlobalScreen.registerNativeHook();
        }

        GlobalScreen.addNativeMouseListener(this);
        GlobalScreen.addNativeMouseWheelListener(this);
        GlobalScreen.addNativeKeyListener(this);
        GlobalScreen.addNativeMouseMotionListener(this);

        synchronized (recordingLock) {
            actions.clear();
            recordedNodes.clear();
            lastTime = System.currentTimeMillis();
            lastMouseMoveTime = 0;
            excludedCaptureDuration = 0;
            activeImageCaptureSession = null;
            pauseStartedAt = 0;
            imageCaptureStartedPaused = false;
            lastRecordedMousePoint = null;
            pressedMouseButtons.clear();
            pressedKeys.clear();
            controlInputFilter.reset();
            state = RecorderState.RECORDING;
        }

        System.out.println("开始录制");
    }


    /**
     * 停止录制
     */
    public void stop() throws NativeHookException {
        synchronized (recordingLock) {
            if (state == RecorderState.IDLE) {
                return;
            }
            state = RecorderState.IDLE;
            activeImageCaptureSession = null;
        }
        GlobalScreen.removeNativeMouseListener(this);
        GlobalScreen.removeNativeMouseWheelListener(this);
        GlobalScreen.removeNativeMouseMotionListener(this);
        GlobalScreen.removeNativeKeyListener(this);
        System.out.println("停止录制");
        if (onStop != null) {
            onStop.run();
        }
    }

    @Override
    public void nativeMouseMoved(NativeMouseEvent e) {
        synchronized (recordingLock) {
            if (controlInputFilter.ignoreMouseMotion(e) || state != RecorderState.RECORDING
                    || !shouldRecordMouse(e)
                    || !appConfig.isRecordMouseMove()) {
                return;
            }
            long now = timelineNow(System.currentTimeMillis());
            int interval = appConfig.getMouseMoveInterval();
            if (interval > 0 && now - lastMouseMoveTime < interval) {
                return;
            }

            LogicalPoint point = getLogicalEventPosition(e);
            recordAction(MacroAction.mouseMove(
                    point.x(),
                    point.y(),
                    elapsedSinceLastAction(now)
            ));
            lastTime = now;
            lastMouseMoveTime = now;
        }
    }

    @Override
    public void nativeMousePressed(NativeMouseEvent e) {
        MouseButton button = convertMouseButton(e.getButton());
        if (button == null) return;
        synchronized (recordingLock) {
            if (controlInputFilter.ignoreMousePress(e)
                    || state != RecorderState.RECORDING || !shouldRecordMouse(e)) {
                return;
            }
            long now = timelineNow(System.currentTimeMillis());
            LogicalPoint point = getLogicalEventPosition(e);
            if (button != null) {
                MacroAction action = MacroAction.mousePress(
                        point.x(),
                        point.y(),
                        button,
                        elapsedSinceLastAction(now)
                );
                recordAction(action);
                pressedMouseButtons.put(e.getButton(), action);
                lastTime = now;
            }
        }
    }

    @Override
    public void nativeMouseReleased(NativeMouseEvent e) {
        MouseButton button = convertMouseButton(e.getButton());
        if (button == null) return;
        synchronized (recordingLock) {
            boolean recordedPress = pressedMouseButtons.containsKey(e.getButton());
            if (controlInputFilter.ignoreMouseRelease(e, recordedPress)
                    || state != RecorderState.RECORDING || (!recordedPress && !shouldRecordMouse(e))) {
                return;
            }
            long now = timelineNow(System.currentTimeMillis());
            LogicalPoint point = getLogicalEventPosition(e);
            if (button != null) {
                recordAction(MacroAction.mouseRelease(
                        point.x(),
                        point.y(),
                        button,
                        elapsedSinceLastAction(now)
                ));
                lastTime = now;
                pressedMouseButtons.remove(e.getButton());
            }
        }
    }

    @Override
    public void nativeMouseWheelMoved(NativeMouseWheelEvent e) {
        synchronized (recordingLock) {
            if (controlInputFilter.ignoreMouseMotion(e)
                    || state != RecorderState.RECORDING || !shouldRecordMouse(e)) {
                return;
            }
            long now = timelineNow(System.currentTimeMillis());
            LogicalPoint point = getLogicalEventPosition(e);
            recordAction(MacroAction.mouseWheel(
                    point.x(),
                    point.y(),
                    e.getWheelRotation(),
                    elapsedSinceLastAction(now)
            ));
            lastTime = now;
        }
    }

    @SneakyThrows
    @Override
    public void nativeKeyPressed(NativeKeyEvent e) {
        if (e.getKeyCode() == NativeKeyEvent.VC_F12) {
            stop();
            return;
        }
        if (e.getKeyCode() == NativeKeyEvent.VC_F10) {
            requestImageCapture();
            return;
        }
        synchronized (recordingLock) {
            int keyId = RecordingControlInputFilter.keyId(e);
            if (controlInputFilter.ignoreKeyPress(e, pressedKeys.containsKey(keyId))
                    || state != RecorderState.RECORDING || !shouldRecordKeyboard()) {
                return;
            }
            long now = timelineNow(System.currentTimeMillis());
            int keyCode = convertKeyCode(e);
            String key = NativeKeyEvent.getKeyText(e.getKeyCode());
            if (keyCode != -1) {
                MacroAction action = MacroAction.keyPress(
                        keyCode,
                        key,
                        elapsedSinceLastAction(now)
                );
                recordAction(action);
                pressedKeys.put(keyId, action);
                lastTime = now;
            }
        }
    }

    @Override
    public void nativeKeyReleased(NativeKeyEvent e) {
        if (e.getKeyCode() == NativeKeyEvent.VC_F10
                || e.getKeyCode() == NativeKeyEvent.VC_F12) {
            return;
        }
        synchronized (recordingLock) {
            int keyId = RecordingControlInputFilter.keyId(e);
            boolean recordedPress = pressedKeys.containsKey(keyId);
            if (controlInputFilter.ignoreKeyRelease(e, recordedPress)
                    || state != RecorderState.RECORDING || (!recordedPress && !shouldRecordKeyboard())) {
                return;
            }
            long now = timelineNow(System.currentTimeMillis());
            int keyCode = convertKeyCode(e);
            String key = NativeKeyEvent.getKeyText(e.getKeyCode());
            if (keyCode != -1) {
                recordAction(MacroAction.keyRelease(
                        keyCode,
                        key,
                        elapsedSinceLastAction(now)
                ));
                lastTime = now;
                pressedKeys.remove(keyId);
            }
        }
    }

    /**
     * 请求在当前录制中插入一次图像操作。
     *
     * <p>状态会在调用 UI 回调前切换为采集中，从而保证触发键释放、
     * 截图遮罩和框选产生的所有原生事件都不会写入宏。</p>
     *
     * @return 是否成功开始图像采集
     */
    public boolean requestImageCapture() {
        Consumer<ImageCaptureSession> callback;
        ImageCaptureSession session;
        synchronized (recordingLock) {
            if (state != RecorderState.RECORDING && state != RecorderState.PAUSED) {
                return false;
            }
            imageCaptureStartedPaused = state == RecorderState.PAUSED;
            long realNow = System.currentTimeMillis();
            if (!imageCaptureStartedPaused) releasePressedInputs(timelineNow(realNow));
            LogicalPoint point = getLogicalCursorPosition();
            boolean fromControlBar = controlInputFilter.containsCurrentPoint(point.x() * dpiScale, point.y() * dpiScale);
            imageCaptureBridgeAllowed = !fromControlBar || lastRecordedMousePoint != null;
            if (fromControlBar && lastRecordedMousePoint != null) point = lastRecordedMousePoint;
            session = new ImageCaptureSession(
                    ++captureSessionSequence,
                    realNow,
                    timelineNow(imageCaptureStartedPaused ? pauseStartedAt : realNow),
                    point.x(),
                    point.y()
            );
            activeImageCaptureSession = session;
            controlInputFilter.beginSuspension();
            state = RecorderState.IMAGE_CAPTURE;
            callback = onImageCaptureRequested;
        }

        if (callback == null) {
            cancelImageCapture(session.id());
            return false;
        }
        try {
            callback.accept(session);
            return true;
        } catch (RuntimeException exception) {
            cancelImageCapture(session.id());
            throw exception;
        }
    }

    /** 完成图像采集，并按录制顺序加入 IMAGE_CHECK 与轨迹桥接节点。 */
    public boolean completeImageCapture(long sessionId, ImageCheckConfig config) {
        if (config == null) {
            return cancelImageCapture(sessionId);
        }
        return finishImageCapture(sessionId, config);
    }

    /** 取消图像采集，仅恢复时间轴并按需补充鼠标桥接轨迹。 */
    public boolean cancelImageCapture(long sessionId) {
        return finishImageCapture(sessionId, null);
    }

    /** 返回线程安全的普通动作快照。 */
    public List<MacroAction> getActions() {
        synchronized (recordingLock) {
            return new ArrayList<>(actions);
        }
    }

    /** 返回线程安全的录制流程节点快照。 */
    public List<MacroNode> getRecordedNodes() {
        synchronized (recordingLock) {
            return new ArrayList<>(recordedNodes);
        }
    }

    /** 完成采集会话的统一收尾。 */
    private boolean finishImageCapture(long sessionId, ImageCheckConfig config) {
        synchronized (recordingLock) {
            if (state != RecorderState.IMAGE_CAPTURE
                    || activeImageCaptureSession == null
                    || activeImageCaptureSession.id() != sessionId) {
                return false;
            }

            ImageCaptureSession session = activeImageCaptureSession;
            long realEnd = System.currentTimeMillis();
            if (!imageCaptureStartedPaused) {
                excludedCaptureDuration += Math.max(0, realEnd - session.realStartedAt());
            }

            if (config != null) {
                recordedNodes.add(new MacroNode(
                        MacroFlowFactory.newNodeId(),
                        NodeType.IMAGE_CHECK,
                        null,
                        config,
                        new ArrayList<>()
                ));
            }
            if (!imageCaptureStartedPaused) appendMouseBridge(session);

            activeImageCaptureSession = null;
            state = imageCaptureStartedPaused ? RecorderState.PAUSED : RecorderState.RECORDING;
            if (!imageCaptureStartedPaused) controlInputFilter.endSuspension();
            return true;
        }
    }

    /**
     * 用进入和离开采集时的坐标生成线性桥接轨迹。
     *
     * <p>桥接节点共享采集前尚未归属给动作的时间，不会把用户框选图像
     * 所花的时间重新带回宏时间轴。</p>
     */
    private void appendMouseBridge(ImageCaptureSession session) {
        if (!appConfig.isRecordMouseMove() || !imageCaptureBridgeAllowed) {
            return;
        }

        LogicalPoint end = getLogicalCursorPosition();
        if (controlInputFilter.containsCurrentPoint(end.x() * dpiScale, end.y() * dpiScale)) return;
        int deltaX = end.x() - session.startX();
        int deltaY = end.y() - session.startY();
        if (deltaX == 0 && deltaY == 0) {
            return;
        }

        long bridgeDuration = Math.max(0, session.timelineStartedAt() - lastTime);
        int interval = appConfig.getMouseMoveInterval();
        double distance = Math.hypot(deltaX, deltaY);
        int distanceSteps = Math.max(
                1,
                (int) Math.ceil(distance / DEFAULT_BRIDGE_STEP_DISTANCE)
        );
        int timeSteps = interval > 0
                ? Math.max(1, (int) Math.ceil(bridgeDuration / (double) interval))
                : 1;
        int stepCount = Math.min(
                MAX_BRIDGE_MOVE_COUNT,
                Math.max(distanceSteps, timeSteps)
        );

        recordAction(MacroAction.mouseMove(
                session.startX(),
                session.startY(),
                0
        ));
        long previousTimestamp = lastTime;
        for (int step = 1; step <= stepCount; step++) {
            double progress = step / (double) stepCount;
            int x = (int) Math.round(session.startX() + deltaX * progress);
            int y = (int) Math.round(session.startY() + deltaY * progress);
            long timestamp = lastTime + Math.round(bridgeDuration * progress);
            recordAction(MacroAction.mouseMove(
                    x,
                    y,
                    Math.max(0, timestamp - previousTimestamp)
            ));
            previousTimestamp = timestamp;
        }
        lastTime = session.timelineStartedAt();
        lastMouseMoveTime = lastTime;
    }

    /** 将普通输入动作同时加入旧动作视图和新流程节点列表。 */
    private void recordAction(MacroAction action) {
        switch (action.getType()) {
            case MOUSE_MOVE, MOUSE_PRESS, MOUSE_RELEASE, MOUSE_WHEEL ->
                    lastRecordedMousePoint = new LogicalPoint(action.getX(), action.getY());
            default -> { }
        }
        actions.add(action);
        recordedNodes.add(new MacroNode(
                MacroFlowFactory.newNodeId(),
                NodeType.ACTION,
                action,
                new ArrayList<>()
        ));
    }

    /** 将真实时间转换为排除图像采集交互后的录制时间。 */
    private long timelineNow(long realTime) {
        return realTime - excludedCaptureDuration;
    }

    /** 计算当前时间点距离上一个已记录动作的非负延迟。 */
    private long elapsedSinceLastAction(long now) {
        return Math.max(0, now - lastTime);
    }

    /** 主窗口提供各录制操作控件的物理屏幕区域与焦点，紧凑模式也可提供完整控制栏。 */
    public void setControlRegionSupplier(java.util.function.Supplier<RecordingControlInputFilter.Region> supplier) {
        controlInputFilter.setRegionSupplier(supplier);
    }

    public void refreshControlRegion() { controlInputFilter.refreshRegion(); }

    /** 普通暂停与截图共用输入过滤和时间补偿，保留此前全部动作。 */
    public boolean pause() {
        synchronized (recordingLock) {
            if (state != RecorderState.RECORDING) return false;
            pauseStartedAt = System.currentTimeMillis();
            releasePressedInputs(timelineNow(pauseStartedAt));
            controlInputFilter.beginSuspension();
            state = RecorderState.PAUSED;
            return true;
        }
    }

    public boolean resume() {
        synchronized (recordingLock) {
            if (state != RecorderState.PAUSED) return false;
            excludedCaptureDuration += Math.max(0, System.currentTimeMillis() - pauseStartedAt);
            controlInputFilter.endSuspension();
            state = RecorderState.RECORDING;
            return true;
        }
    }

    /** 控制栏定时读取轻量快照，不复制高频录制动作列表。 */
    public RecordingStatus getRecordingStatus() {
        synchronized (recordingLock) {
            return new RecordingStatus(state != RecorderState.IDLE,
                    state == RecorderState.PAUSED || (state == RecorderState.IMAGE_CAPTURE && imageCaptureStartedPaused),
                    state == RecorderState.IMAGE_CAPTURE, recordedNodes.size());
        }
    }

    /** 挂起前闭合已录入的按下动作；真实释放到来时只清除过滤标记，避免悬空按键。 */
    private void releasePressedInputs(long now) {
        for (var entry : pressedMouseButtons.entrySet()) {
            MacroAction pressed = entry.getValue();
            LogicalPoint releasePoint = lastRecordedMousePoint == null
                    ? new LogicalPoint(pressed.getX(), pressed.getY()) : lastRecordedMousePoint;
            recordAction(MacroAction.mouseRelease(releasePoint.x(), releasePoint.y(), pressed.getMouseButton(),
                    elapsedSinceLastAction(now)));
            lastTime = now;
            controlInputFilter.suppressMouseUntilRelease(entry.getKey());
        }
        for (var entry : pressedKeys.entrySet()) {
            MacroAction pressed = entry.getValue();
            recordAction(MacroAction.keyRelease(pressed.getKeyCode(), pressed.getKey(), elapsedSinceLastAction(now)));
            lastTime = now;
            controlInputFilter.suppressKeyUntilRelease(entry.getKey());
        }
        pressedMouseButtons.clear();
        pressedKeys.clear();
    }

    public record RecordingStatus(boolean recording, boolean paused, boolean capturing, int nodeCount) { }

    /** Windows 钩子事件携带物理屏幕坐标；仅生成动作时沿用现有系统 DPI 转换。 */
    private LogicalPoint getLogicalEventPosition(NativeMouseEvent event) {
        return toLogicalPoint(event.getX(), event.getY());
    }

    /** 截图开始与结束需要当下位置，不能把该查询用于排队的鼠标事件。 */
    private LogicalPoint getLogicalCursorPosition() {
        WindowsMouseUtil.POINT point = cursorPosition.get();
        return toLogicalPoint(point.x, point.y);
    }

    /** 保留既有逻辑坐标及整数截断方式，不修改 DPI、负坐标或轨迹采样规则。 */
    private LogicalPoint toLogicalPoint(int x, int y) {
        return new LogicalPoint(
                (int) (x / dpiScale),
                (int) (y / dpiScale)
        );
    }

    private int convertKeyCode(NativeKeyEvent e) {
        if (e.getKeyLocation() == NativeKeyEvent.KEY_LOCATION_NUMPAD) {
            int numpadKeyCode = convertNumpadKeyCode(e.getRawCode());
            if (numpadKeyCode != -1) {
                return numpadKeyCode;
            }
        }

        return convertKeyCode(e.getKeyCode());
    }

    private int convertKeyCode(int keyCode) {

        return switch (keyCode) {
            // 菜单键
            case NativeKeyEvent.VC_CONTEXT_MENU -> KeyEvent.VK_CONTEXT_MENU;

            // 系统按键
            case NativeKeyEvent.VC_PRINTSCREEN -> KeyEvent.VK_PRINTSCREEN;
            case NativeKeyEvent.VC_SCROLL_LOCK -> KeyEvent.VK_SCROLL_LOCK;
            case NativeKeyEvent.VC_PAUSE -> KeyEvent.VK_PAUSE;

            // 功能键
            case NativeKeyEvent.VC_ESCAPE -> KeyEvent.VK_ESCAPE;

            case NativeKeyEvent.VC_F1 -> KeyEvent.VK_F1;
            case NativeKeyEvent.VC_F2 -> KeyEvent.VK_F2;
            case NativeKeyEvent.VC_F3 -> KeyEvent.VK_F3;
            case NativeKeyEvent.VC_F4 -> KeyEvent.VK_F4;
            case NativeKeyEvent.VC_F5 -> KeyEvent.VK_F5;
            case NativeKeyEvent.VC_F6 -> KeyEvent.VK_F6;
            case NativeKeyEvent.VC_F7 -> KeyEvent.VK_F7;
            case NativeKeyEvent.VC_F8 -> KeyEvent.VK_F8;
            case NativeKeyEvent.VC_F9 -> KeyEvent.VK_F9;
            case NativeKeyEvent.VC_F10 -> KeyEvent.VK_F10;
            case NativeKeyEvent.VC_F11 -> KeyEvent.VK_F11;
            case NativeKeyEvent.VC_F12 -> KeyEvent.VK_F12;
            case NativeKeyEvent.VC_F13 -> KeyEvent.VK_F13;
            case NativeKeyEvent.VC_F14 -> KeyEvent.VK_F14;
            case NativeKeyEvent.VC_F15 -> KeyEvent.VK_F15;
            case NativeKeyEvent.VC_F16 -> KeyEvent.VK_F16;
            case NativeKeyEvent.VC_F17 -> KeyEvent.VK_F17;
            case NativeKeyEvent.VC_F18 -> KeyEvent.VK_F18;
            case NativeKeyEvent.VC_F19 -> KeyEvent.VK_F19;
            case NativeKeyEvent.VC_F20 -> KeyEvent.VK_F20;
            case NativeKeyEvent.VC_F21 -> KeyEvent.VK_F21;
            case NativeKeyEvent.VC_F22 -> KeyEvent.VK_F22;
            case NativeKeyEvent.VC_F23 -> KeyEvent.VK_F23;
            case NativeKeyEvent.VC_F24 -> KeyEvent.VK_F24;

            // 数字
            case NativeKeyEvent.VC_1 -> KeyEvent.VK_1;
            case NativeKeyEvent.VC_2 -> KeyEvent.VK_2;
            case NativeKeyEvent.VC_3 -> KeyEvent.VK_3;
            case NativeKeyEvent.VC_4 -> KeyEvent.VK_4;
            case NativeKeyEvent.VC_5 -> KeyEvent.VK_5;
            case NativeKeyEvent.VC_6 -> KeyEvent.VK_6;
            case NativeKeyEvent.VC_7 -> KeyEvent.VK_7;
            case NativeKeyEvent.VC_8 -> KeyEvent.VK_8;
            case NativeKeyEvent.VC_9 -> KeyEvent.VK_9;
            case NativeKeyEvent.VC_0 -> KeyEvent.VK_0;

            case NativeKeyEvent.VC_BACKQUOTE -> KeyEvent.VK_BACK_QUOTE;
            case NativeKeyEvent.VC_MINUS -> KeyEvent.VK_MINUS;
            case NativeKeyEvent.VC_EQUALS -> KeyEvent.VK_EQUALS;
            case NativeKeyEvent.VC_BACKSPACE -> KeyEvent.VK_BACK_SPACE;

            // 制表键
            case NativeKeyEvent.VC_TAB -> KeyEvent.VK_TAB;

            // 第一行字母
            case NativeKeyEvent.VC_Q -> KeyEvent.VK_Q;
            case NativeKeyEvent.VC_W -> KeyEvent.VK_W;
            case NativeKeyEvent.VC_E -> KeyEvent.VK_E;
            case NativeKeyEvent.VC_R -> KeyEvent.VK_R;
            case NativeKeyEvent.VC_T -> KeyEvent.VK_T;
            case NativeKeyEvent.VC_Y -> KeyEvent.VK_Y;
            case NativeKeyEvent.VC_U -> KeyEvent.VK_U;
            case NativeKeyEvent.VC_I -> KeyEvent.VK_I;
            case NativeKeyEvent.VC_O -> KeyEvent.VK_O;
            case NativeKeyEvent.VC_P -> KeyEvent.VK_P;

            case NativeKeyEvent.VC_OPEN_BRACKET -> KeyEvent.VK_OPEN_BRACKET;
            case NativeKeyEvent.VC_CLOSE_BRACKET -> KeyEvent.VK_CLOSE_BRACKET;
            case NativeKeyEvent.VC_BACK_SLASH -> KeyEvent.VK_BACK_SLASH;

            // 大写锁定键
            case NativeKeyEvent.VC_CAPS_LOCK -> KeyEvent.VK_CAPS_LOCK;

            // 第二行字母
            case NativeKeyEvent.VC_A -> KeyEvent.VK_A;
            case NativeKeyEvent.VC_S -> KeyEvent.VK_S;
            case NativeKeyEvent.VC_D -> KeyEvent.VK_D;
            case NativeKeyEvent.VC_F -> KeyEvent.VK_F;
            case NativeKeyEvent.VC_G -> KeyEvent.VK_G;
            case NativeKeyEvent.VC_H -> KeyEvent.VK_H;
            case NativeKeyEvent.VC_J -> KeyEvent.VK_J;
            case NativeKeyEvent.VC_K -> KeyEvent.VK_K;
            case NativeKeyEvent.VC_L -> KeyEvent.VK_L;

            case NativeKeyEvent.VC_SEMICOLON -> KeyEvent.VK_SEMICOLON;
            case NativeKeyEvent.VC_QUOTE -> KeyEvent.VK_QUOTE;
            case NativeKeyEvent.VC_ENTER -> KeyEvent.VK_ENTER;

            // 修饰键
            case NativeKeyEvent.VC_SHIFT -> KeyEvent.VK_SHIFT;

            case NativeKeyEvent.VC_CONTROL -> KeyEvent.VK_CONTROL;

            case NativeKeyEvent.VC_ALT -> KeyEvent.VK_ALT;

            case NativeKeyEvent.VC_META -> KeyEvent.VK_WINDOWS;

            // 第三行
            case NativeKeyEvent.VC_Z -> KeyEvent.VK_Z;
            case NativeKeyEvent.VC_X -> KeyEvent.VK_X;
            case NativeKeyEvent.VC_C -> KeyEvent.VK_C;
            case NativeKeyEvent.VC_V -> KeyEvent.VK_V;
            case NativeKeyEvent.VC_B -> KeyEvent.VK_B;
            case NativeKeyEvent.VC_N -> KeyEvent.VK_N;
            case NativeKeyEvent.VC_M -> KeyEvent.VK_M;

            case NativeKeyEvent.VC_COMMA -> KeyEvent.VK_COMMA;
            case NativeKeyEvent.VC_PERIOD -> KeyEvent.VK_PERIOD;
            case NativeKeyEvent.VC_SLASH -> KeyEvent.VK_SLASH;

            // 空格
            case NativeKeyEvent.VC_SPACE -> KeyEvent.VK_SPACE;

            // 方向键
            case NativeKeyEvent.VC_UP -> KeyEvent.VK_UP;
            case NativeKeyEvent.VC_DOWN -> KeyEvent.VK_DOWN;
            case NativeKeyEvent.VC_LEFT -> KeyEvent.VK_LEFT;
            case NativeKeyEvent.VC_RIGHT -> KeyEvent.VK_RIGHT;

            // 导航键
            case NativeKeyEvent.VC_INSERT -> KeyEvent.VK_INSERT;
            case NativeKeyEvent.VC_DELETE -> KeyEvent.VK_DELETE;
            case NativeKeyEvent.VC_HOME -> KeyEvent.VK_HOME;
            case NativeKeyEvent.VC_END -> KeyEvent.VK_END;
            case NativeKeyEvent.VC_PAGE_UP -> KeyEvent.VK_PAGE_UP;
            case NativeKeyEvent.VC_PAGE_DOWN -> KeyEvent.VK_PAGE_DOWN;

            // 小键盘
            case NativeKeyEvent.VC_NUM_LOCK -> KeyEvent.VK_NUM_LOCK;
            case NativeKeyEvent.VC_SEPARATOR -> KeyEvent.VK_SEPARATOR;

            default -> -1;
        };
    }

    private int convertNumpadKeyCode(int rawCode) {
        return switch (rawCode) {
            case 96 -> KeyEvent.VK_NUMPAD0;
            case 97 -> KeyEvent.VK_NUMPAD1;
            case 98 -> KeyEvent.VK_NUMPAD2;
            case 99 -> KeyEvent.VK_NUMPAD3;
            case 100 -> KeyEvent.VK_NUMPAD4;
            case 101 -> KeyEvent.VK_NUMPAD5;
            case 102 -> KeyEvent.VK_NUMPAD6;
            case 103 -> KeyEvent.VK_NUMPAD7;
            case 104 -> KeyEvent.VK_NUMPAD8;
            case 105 -> KeyEvent.VK_NUMPAD9;
            case 106 -> KeyEvent.VK_MULTIPLY;
            case 107 -> KeyEvent.VK_ADD;
            case 109 -> KeyEvent.VK_SUBTRACT;
            case 110 -> KeyEvent.VK_DECIMAL;
            case 111 -> KeyEvent.VK_DIVIDE;
            default -> -1;
        };
    }

    private MouseButton convertMouseButton(int button) {
        return switch (button) {
            case NativeMouseEvent.BUTTON1 -> MouseButton.LEFT;
            case NativeMouseEvent.BUTTON2 -> MouseButton.RIGHT;
            case NativeMouseEvent.BUTTON3 -> MouseButton.MIDDLE;
            default -> null;
        };
    }

    /**
     * 判断鼠标事件发生位置是否位于目标窗口。
     *
     * @param event 携带物理屏幕坐标的原生事件
     * @return 是否应该记录
     */
    private boolean shouldRecordMouse(NativeMouseEvent event) {
        if (targetHwnd == null) {
            return true;
        }

        WinDef.HWND currentWindow = rootWindowAtPoint.apply(event.getX(), event.getY());
        return targetHwnd.equals(currentWindow);
    }

    /**
     * 判断当前前台窗口是否为目标窗口。
     *
     * @return 是否应该记录
     */
    private boolean shouldRecordKeyboard() {
        if (targetHwnd == null) {
            return true;
        }

        WinDef.HWND currentWindow = WindowsWindowUtil.getForegroundWindow();
        currentWindow = WindowsWindowUtil.getRootWindow(currentWindow);

        return targetHwnd.equals(currentWindow);
    }

    /** 录制器内部状态。 */
    private enum RecorderState {
        IDLE,
        RECORDING,
        PAUSED,
        IMAGE_CAPTURE
    }

    /**
     * 一次录制内图像采集的不可变上下文。
     *
     * @param id 会话 ID
     * @param realStartedAt 进入采集的真实时间
     * @param timelineStartedAt 扣除既有采集耗时后的宏时间
     * @param startX 进入采集时的逻辑 X 坐标
     * @param startY 进入采集时的逻辑 Y 坐标
     */
    public record ImageCaptureSession(
            long id,
            long realStartedAt,
            long timelineStartedAt,
            int startX,
            int startY
    ) {
    }

    /** Swing 逻辑坐标中的鼠标点。 */
    private record LogicalPoint(int x, int y) {
    }

}
