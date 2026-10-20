package com.macro.ui;

import com.macro.constant.UiConstants;
import com.macro.constant.MacroFormat;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.macro.config.AppConfig;
import com.macro.constant.ApplicationInfo;
import com.macro.exception.MacroFormatException;
import com.macro.flow.ImageCheckConfig;
import com.macro.flow.MacroFlow;
import com.macro.flow.MacroFlowFactory;
import com.macro.flow.MacroNode;
import com.macro.model.MacroAction;
import com.macro.model.TargetWindow;
import com.macro.model.WindowInfo;
import com.macro.player.MacroPlayer;
import com.macro.player.PlayerState;
import com.macro.player.StopKeyListener;
import com.macro.util.ImageTemplatePathUtil;
import com.macro.recorder.MacroRecorder;
import com.macro.recorder.RecordingControlInputFilter;
import com.macro.recorder.WindowSelector;
import com.macro.recognition.OpenCvImageMatcher;
import com.macro.recognition.ImageRecognitionDiagnostics;
import com.macro.storage.ImageCaptureStorage;
import com.macro.storage.MacroFile;
import com.macro.storage.MacroStorage;
import com.macro.ui.capture.ScreenCaptureController;
import com.macro.ui.dialog.AppMessageDialog;
import com.macro.ui.dialog.ImageRecognitionFrame;
import com.macro.ui.filedialog.FileDialogException;
import com.macro.ui.filedialog.FileDialogOptions;
import com.macro.ui.filedialog.FileDialogService;
import com.macro.ui.filedialog.FileDialogServiceFactory;
import com.macro.ui.page.ImageCapturePanel;
import com.macro.ui.util.ComponentStyleUtil;
import com.macro.ui.util.NumericInputUtil;
import com.macro.ui.util.WindowManager;
import com.macro.ui.window.WindowsRecordingTitleBar;
import com.macro.util.WindowsWindowUtil;
import com.macro.ui.theme.ThemeManager;
import com.macro.ui.theme.ThemePalette;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import com.macro.ui.util.DarkButtonUI;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

public class MacroFrame extends JFrame {

    /** 文件选择仅依赖平台无关接口。 */
    private final FileDialogService fileDialogs = FileDialogServiceFactory.create();

    /** 录制期间新建图像检查节点使用的首版默认阈值。 */
    private static final double RECORDING_IMAGE_MATCH_THRESHOLD = 0.90;

    // 颜色集中由主题提供；未来增加自定义主题时，不需要在窗口中逐处修改颜色。
    private static final ThemePalette THEME = ThemeManager.getPalette();
    private static final Color BACKGROUND_COLOR = THEME.background();
    private static final Color CARD_COLOR = THEME.card();
    private static final Color CARD_LIGHT_COLOR = THEME.controlBackground();
    private static final Color TARGET_CARD_COLOR = THEME.targetCard();
    private static final Color TEXT_PRIMARY = THEME.textPrimary();
    private static final Color TEXT_SECONDARY = THEME.textSecondary();
    private static final Color TEXT_MUTED = THEME.textMuted();
    private static final Color ACCENT_COLOR = THEME.accent();
    private static final Color ACCENT_HOVER_COLOR = THEME.accentHover();
    private static final Color SUCCESS_COLOR = THEME.success();
    private static final Color DANGER_COLOR = THEME.danger();

    private static final Font FONT_NORMAL = new Font(UiConstants.DEFAULT_FONT_FAMILY, Font.PLAIN, 13);

    private static final Font FONT_BOLD = new Font(UiConstants.DEFAULT_FONT_FAMILY, Font.BOLD, 13);

    private static final Font FONT_SMALL = new Font(UiConstants.DEFAULT_FONT_FAMILY, Font.PLAIN, 12);

    private static final Font FONT_TITLE = new Font(UiConstants.DEFAULT_FONT_FAMILY, Font.BOLD, 24);

    private static final String HOME_PAGE = "HOME";
    private static final String IMAGE_CAPTURE_PAGE = "IMAGE_CAPTURE";
    private static final String ACTION_TIMELINE_PAGE = "actionTimeline";
    private static final String WINDOW_SELECTOR_PAGE = "windowSelector";

    private final CardLayout pageLayout = new CardLayout();
    private final JPanel pagePanel = new JPanel(pageLayout);
    private ActionTimelinePanel actionTimelinePanel;
    /** 播放开始时关闭当前节点编辑器，保存及删除回调另有代次保护。 */
    private ActionEditorDialog timelineEditorDialog;
    private WindowSelectorPanel windowSelectorPanel;
    private ImageCapturePanel imageCapturePanel;
    private ScreenCaptureController screenCaptureController;
    private ScreenCaptureController recordingScreenCaptureController;
    private final WindowSelector windowSelector;

    /** 负责录制普通键鼠动作。 */
    private final MacroRecorder recorder;

    /** 同时支持旧动作列表和新流程图的播放器。 */
    private final MacroPlayer player;

    /** 负责 MR 文件的兼容读写。 */
    private final MacroStorage storage;
    private final ImageCaptureStorage imageCaptureStorage;

    /** 正式流程播放使用的 OpenCV 图像匹配器。 */
    private final OpenCvImageMatcher imageMatcher;
    private final AppConfig appConfig;

    /** 从流程 ACTION 节点提取的兼容动作视图，同时供录制流程使用。 */
    private List<MacroAction> actions = new ArrayList<>();

    /** 当前宏的完整流程图，是时间线保存和播放的权威数据。 */
    private MacroFlow macroFlow = MacroFlowFactory.fromActions(actions);

    /** 当前已加载或保存的 MR 文件。 */
    private File currentFile;

    /** 当前是否正在录制。 */
    private boolean recording;

    /** 播放开始前主窗口是否已经最小化。 */
    private boolean windowMinimizedBeforePlay;

    /** 本次播放是否由应用主动最小化了主窗口。 */
    private boolean windowMinimizedForPlay;

    /** 启动时设置逻辑尺寸期间使用的临时最小尺寸。 */
    private Dimension initialLogicalMinimumSize;

    private final JButton settingsButton = new JButton("设置");

    private final JButton imageCaptureButton = new JButton("采集图像");

    private final JButton imageRecognitionButton = new JButton("图像识别");

    private ImageRecognitionFrame imageRecognitionFrame;

    private final JLabel fileLabel = new JLabel("未加载 MR 文件");

    /** 占满宏卡片底部栏的时间线入口，文字周围的空白也可点击。 */
    private final JButton actionLabel = new JButton("0 个动作");

    private final JLabel statusLabel = new JLabel("已就绪");

    private final JLabel statusDot = new JLabel("●");

    private final JButton recordButton = new JButton("开始录制");

    private final JButton stopRecordButton = new JButton("停止录制");

    /** 录制过程中触发冻结截图选区的测试入口。 */
    private final JButton recordingImageButton = new JButton("图像操作 (F10)");

    private final JButton loadButton = new JButton("加载 MR");

    private final JButton saveButton = new JButton("保存 MR");

    private final JButton saveAsButton = new JButton("另存为");

    private final JButton playButton = new JButton("播放");

    private final JButton pausePlayButton = new JButton("暂停");

    private final JButton stopPlayButton = new JButton("停止");

    private final JSpinner loopCountSpinner = new JSpinner(new SpinnerNumberModel(1, 1, Integer.MAX_VALUE, 1));

    private final JCheckBox infiniteLoopCheckBox = new JCheckBox("无限循环");

    private final JButton selectWindowButton = new JButton("指定窗口开始");

    /** 当前目标窗口显示文本。 */
    private final JLabel targetWindowLabel = new JLabel("未指定");

    /** 清除目标窗口按钮。 */
    private final JButton clearWindowButton = new JButton("清除");

    /** 当前宏目标窗口。 */
    private TargetWindow targetWindow;

    private JPanel recordButtonPanel;
    private JPanel recordContextButtonPanel;
    private final List<JLabel> hotkeyHints = new ArrayList<>();

    /** 当前录制内等待截图结果的图像采集会话。 */
    private MacroRecorder.ImageCaptureSession activeRecordingImageCaptureSession;

    /** 后台保存录制模板的任务，用于在停止录制时取消未完成工作。 */
    private SwingWorker<File, Void> recordingImageSaveWorker;

    /** 同一个 JFrame 的录制内容区，保留原生标题栏及任务栏窗口身份。 */
    private JPanel recordingControlBar;
    /** 仅在紧凑录制模式显示，由 DWM 保留右侧原生窗口按钮。 */
    private JPanel recordingCaptionRow;
    private WindowsRecordingTitleBar nativeRecordingTitleBar;
    private final JLabel recordingBarStatus = new JLabel("正在录制");
    private final JLabel recordingBarCount = new JLabel("0 项");
    private final JButton recordingBarCaptureButton = new JButton("截图");
    private final JButton recordingBarPauseButton = new JButton("暂停");
    private final JButton recordingBarStopButton = new JButton("终止");
    private final Timer recordingBarTimer = new Timer(150, event -> refreshRecordingControlBar());
    private Rectangle fullWindowBounds;
    private Dimension fullWindowMinimum;
    private int fullWindowState;
    private boolean fullWindowResizable;
    /** 仅恢复本次录制启动主动最小化的窗口，不干预“无”模式的显示状态。 */
    private boolean windowMinimizedForRecording;
    private volatile boolean compactRecording;
    private volatile boolean recordingControlRegionEnabled;
    private volatile WinDef.HWND recordingControlHwnd;
    /** EDT 发布主页可见录制按钮的客户区逻辑坐标，原生回调只读取快照。 */
    private volatile List<Rectangle> homeRecordingControlBounds = List.of();


    public MacroFrame() {
        appConfig = new AppConfig();
        recorder = new MacroRecorder(appConfig);
        recorder.setControlRegionSupplier(this::getRecordingControlRegion);

        recorder.setOnStop(() -> SwingUtilities.invokeLater(this::handleRecorderStopped));
        recorder.setOnImageCaptureRequested(session ->
                SwingUtilities.invokeLater(() -> startRecordingImageCapture(session))
        );
        windowSelector = new WindowSelector();

        try {
            player = new MacroPlayer();
            player.setOnStateChange(state ->
                    SwingUtilities.invokeLater(() -> updatePlaybackState(state))
            );
        } catch (AWTException e) {
            throw new RuntimeException("无法初始化 Robot", e);
        }

        storage = new MacroStorage();
        imageCaptureStorage = new ImageCaptureStorage();
        imageMatcher = new OpenCvImageMatcher(this::getMacroBaseDirectory);
        initFrame();
        initLayout();
        initEvents();
        updateState();
        if (appConfig.isLoadLastMacroOnStartup()) {
            loadLastMacro();
        }
    }

    private void initFrame() {
        setTitle(ApplicationInfo.APP_NAME);

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        URL iconUrl = getClass().getResource("/MacroRecorder.png");

        if (iconUrl != null) {
            setIconImage(new ImageIcon(iconUrl).getImage());
        }
    }

    private void initLayout() {
        JPanel header = createHeader();

        JPanel macroCard = createMacroCard();

        JPanel recordSection = createRecordSection();

        JPanel playbackSection = createPlaybackSection();
        JPanel fileActions = createFileActions();

        JPanel root = new JPanel(new BorderLayout());

        root.setBackground(BACKGROUND_COLOR);

        root.setBorder(new EmptyBorder(22, 26, 16, 26));

        JPanel content = new JPanel(new GridBagLayout());

        content.setOpaque(false);

        GridBagConstraints gbc = new GridBagConstraints();

        gbc.gridx = 0;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.weighty = 0;

        int row = 0;

        gbc.gridy = row++;
        content.add(header, gbc);

        gbc.gridy = row++;
        gbc.insets = new Insets(20, 0, 0, 0);
        content.add(macroCard, gbc);

        gbc.gridy = row++;
        gbc.insets = new Insets(18, 0, 0, 0);
        content.add(recordSection, gbc);

        gbc.gridy = row++;
        gbc.insets = new Insets(18, 0, 0, 0);
        content.add(playbackSection, gbc);

        gbc.gridy = row++;
        gbc.insets = new Insets(12, 0, 0, 0);
        content.add(fileActions, gbc);

        root.add(content, BorderLayout.CENTER);

        root.add(createStatusBar(), BorderLayout.SOUTH);

        actionTimelinePanel = new ActionTimelinePanel();
        actionTimelinePanel.setEditPermission(() -> player.getState() == PlayerState.IDLE);
        windowSelectorPanel = new WindowSelectorPanel();
        imageCapturePanel = new ImageCapturePanel(
                this::showHomePage,
                this::openSettings,
                this::startImageCapture,
                appConfig,
                imageCaptureStorage
        );
        screenCaptureController = new ScreenCaptureController(
                this,
                imageCapturePanel::setCapturedImage
        );
        recordingScreenCaptureController = new ScreenCaptureController(
                this,
                this::handleRecordingImageCaptured,
                this::handleRecordingImageCaptureCancelled,
                this::handleRecordingImageCaptureFailed,
                true
        );


        actionTimelinePanel.setOnActionDoubleClicked(this::openActionEditor);
        actionTimelinePanel.setOnActionEditRequested(this::openActionEditor);
        actionTimelinePanel.setOnActionInsertRequested(this::openActionCreator);

        actionTimelinePanel.setOnActionDeleteRequested(ignored -> syncActionsFromTimeline());

        windowSelectorPanel = new WindowSelectorPanel();

        pagePanel.add(root, HOME_PAGE);
        pagePanel.add(imageCapturePanel, IMAGE_CAPTURE_PAGE);
        pagePanel.add(createActionTimelinePage(), ACTION_TIMELINE_PAGE);
        pagePanel.add(createWindowSelectorPage(), WINDOW_SELECTOR_PAGE);

        setContentPane(pagePanel);
        recordingControlBar = createRecordingControlBar();
        SwingUtilities.invokeLater(() -> {
            pack();

            Dimension logicalMinimumSize = getSize();
            setDpiAwareMinimumSize(logicalMinimumSize);

            setInitialLogicalSize(logicalMinimumSize);
        });
    }

    /** 标题内容交给 DWM 扩展区承载，系统最小化、最大化和关闭按钮不使用 Swing 替代。 */
    private JPanel createRecordingControlBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(BACKGROUND_COLOR);
        recordingCaptionRow = new JPanel(new BorderLayout(14, 0)) {
            @Override
            public void paint(Graphics graphics) {
                super.paint(graphics);
                // Swing 完成绘制后再清理系统按钮区域的像素透明度，图标仍由 DWM 绘制。
                if (nativeRecordingTitleBar != null) nativeRecordingTitleBar.afterCaptionPaint();
            }
        };
        recordingCaptionRow.setBackground(BACKGROUND_COLOR);
        recordingCaptionRow.setForeground(TEXT_PRIMARY);
        recordingCaptionRow.setBorder(new EmptyBorder(2, 10, 2, 10));
        JPanel status = new JPanel();
        status.setLayout(new BoxLayout(status, BoxLayout.X_AXIS));
        status.setOpaque(false);
        JLabel applicationName = new JLabel(ApplicationInfo.APP_NAME);
        applicationName.setFont(FONT_SMALL);
        applicationName.setForeground(TEXT_PRIMARY);
        recordingBarStatus.setFont(FONT_BOLD);
        recordingBarCount.setFont(FONT_SMALL);
        recordingBarCount.setForeground(TEXT_SECONDARY);
        recordingBarCount.setPreferredSize(new Dimension(88, recordingBarCount.getPreferredSize().height));
        status.add(applicationName);
        status.add(Box.createHorizontalStrut(12));
        status.add(recordingBarStatus);
        status.add(Box.createHorizontalStrut(7));
        status.add(recordingBarCount);
        JPanel buttons = new JPanel(new GridLayout(1, 3, 6, 0));
        buttons.setOpaque(false);
        for (JButton button : List.of(recordingBarCaptureButton, recordingBarPauseButton,
                recordingBarStopButton)) {
            styleSecondaryButton(button);
            button.setFont(FONT_SMALL);
            button.setBorder(new EmptyBorder(3, 10, 3, 10));
            // 避免按钮自动抢键盘焦点；仍保留正常的 Tab、空格激活能力。
            button.setRequestFocusEnabled(false);
            buttons.add(button);
        }
        recordingBarStopButton.setForeground(DANGER_COLOR);
        setButtonIcon(recordingBarCaptureButton, ButtonIconType.IMAGE_CAPTURE);
        setButtonIcon(recordingBarPauseButton, ButtonIconType.PAUSE);
        setButtonIcon(recordingBarStopButton, ButtonIconType.STOP);
        for (JButton button : List.of(recordingBarCaptureButton, recordingBarPauseButton, recordingBarStopButton)) {
            button.setIconTextGap(5);
        }
        recordingBarCaptureButton.setToolTipText("截图并加入图像识别节点（F10）");
        recordingBarStopButton.setToolTipText("停止录制并保留已录制内容（F12）");
        recordingCaptionRow.add(status, BorderLayout.CENTER);
        recordingCaptionRow.add(buttons, BorderLayout.EAST);
        bar.add(recordingCaptionRow, BorderLayout.NORTH);
        return bar;
    }

    /** NativeHook 线程只查询 Win32，将 EDT 的按钮快照按窗口当前 DPI 转为物理坐标。 */
    private RecordingControlInputFilter.Region getRecordingControlRegion() {
        WinDef.HWND handle = recordingControlHwnd;
        if (handle == null || !User32.INSTANCE.IsWindowVisible(handle) || NativeControlCoordinates.INSTANCE.IsIconic(handle)) {
            return RecordingControlInputFilter.Region.EMPTY;
        }
        if (recordingControlRegionEnabled) {
            WinDef.RECT bounds = new WinDef.RECT();
            if (!User32.INSTANCE.GetWindowRect(handle, bounds)) return RecordingControlInputFilter.Region.EMPTY;
            WinDef.HWND foreground = WindowsWindowUtil.getRootWindow(User32.INSTANCE.GetForegroundWindow());
            return new RecordingControlInputFilter.Region(bounds.toRectangle(), handle.equals(foreground));
        }
        WinDef.POINT origin = new WinDef.POINT();
        if (!NativeControlCoordinates.INSTANCE.ClientToScreen(handle, origin)) return RecordingControlInputFilter.Region.EMPTY;
        double scale = Math.max(96, NativeControlCoordinates.INSTANCE.GetDpiForWindow(handle)) / 96.0;
        List<Rectangle> areas = new ArrayList<>();
        for (Rectangle logical : homeRecordingControlBounds) {
            int left = origin.x + (int) Math.round(logical.x * scale);
            int top = origin.y + (int) Math.round(logical.y * scale);
            int right = origin.x + (int) Math.round((logical.x + logical.width) * scale);
            int bottom = origin.y + (int) Math.round((logical.y + logical.height) * scale);
            areas.add(new Rectangle(left, top, right - left, bottom - top));
        }
        // 主页只排除这些控件的鼠标操作，不因整个主窗口在前台就屏蔽键盘输入。
        return new RecordingControlInputFilter.Region(areas, false);
    }

    /** 在显示、布局及页面切换时发布区域，启动钩子前也调用，避免依赖按钮回调之后才建立过滤。 */
    private void refreshRecordingControlBounds() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("录制控件区域只能在事件派发线程更新");
        }
        if (isDisplayable()) recordingControlHwnd = new WinDef.HWND(Native.getComponentPointer(this));
        List<Rectangle> areas = new ArrayList<>();
        for (JButton button : List.of(recordButton, selectWindowButton, stopRecordButton, recordingImageButton)) {
            if (button.isShowing()) {
                Rectangle visible = SwingUtilities.convertRectangle(button, button.getVisibleRect(), getRootPane());
                if (!visible.isEmpty()) areas.add(visible);
            }
        }
        homeRecordingControlBounds = List.copyOf(areas);
        recorder.refreshControlRegion();
    }

    /** 录制成功启动后应用独立的窗口行为设置，播放窗口设置不参与此流程。 */
    private void applyRecordingStartBehavior() {
        windowMinimizedForRecording = false;
        recordingControlRegionEnabled = false;
        switch (appConfig.getRecordingStartBehavior()) {
            case MINIMIZE -> {
                windowMinimizedForRecording = !WindowManager.isMinimized(this);
                WindowManager.minimize(this);
            }
            case CONTROL_BAR -> enterCompactRecordingMode();
            case NONE -> { /* 保留当前内容、尺寸和窗口扩展状态。 */ }
        }
        recorder.refreshControlRegion();
    }

    /** 成功启动录制后保存完整窗口状态，再切换到同一主窗口的混合原生标题栏。 */
    private void enterCompactRecordingMode() {
        if (compactRecording) return;
        fullWindowBounds = getBounds();
        fullWindowMinimum = getMinimumSize();
        fullWindowState = getExtendedState();
        fullWindowResizable = isResizable();
        compactRecording = true;
        recordingControlRegionEnabled = true;
        setExtendedState(Frame.NORMAL);
        setMinimumSize(new Dimension(0, 0));
        // 系统最大化按钮和边缘缩放依赖可缩放样式，紧凑模式仍交给 Windows 处理。
        setResizable(true);
        setContentPane(recordingControlBar);
        refreshRecordingControlBar();
        if (nativeRecordingTitleBar == null) {
            nativeRecordingTitleBar = new WindowsRecordingTitleBar(this, recordingCaptionRow,
                    List.of(recordingBarCaptureButton, recordingBarPauseButton, recordingBarStopButton));
        }
        try {
            nativeRecordingTitleBar.activate();
        } catch (RuntimeException exception) {
            exitCompactRecordingMode();
            showRecordingCaptureError("录制标题栏暂不可用，录制仍在继续，可按 F12 停止。", exception);
            return;
        }
        setMinimumSize(new Dimension(recordingControlBar.getPreferredSize().width, 0));
        pack();
        Rectangle usable = getGraphicsConfiguration().getBounds();
        Insets screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(getGraphicsConfiguration());
        int x = Math.max(usable.x + screenInsets.left,
                Math.min(fullWindowBounds.x, usable.x + usable.width - screenInsets.right - getWidth()));
        int y = Math.max(usable.y + screenInsets.top,
                Math.min(fullWindowBounds.y, usable.y + usable.height - screenInsets.bottom - getHeight()));
        setLocation(x, y);
        nativeRecordingTitleBar.refresh();
        recorder.refreshControlRegion();
        recordingBarTimer.start();
    }

    /** 停止回调统一恢复尺寸、位置、最小尺寸、缩放能力和原来的窗口扩展状态。 */
    private void exitCompactRecordingMode() {
        if (!compactRecording) return;
        recordingBarTimer.stop();
        recordingControlRegionEnabled = false;
        compactRecording = false;
        if (nativeRecordingTitleBar != null) nativeRecordingTitleBar.deactivate();
        setExtendedState(Frame.NORMAL);
        setMinimumSize(new Dimension(0, 0));
        setResizable(fullWindowResizable);
        setContentPane(pagePanel);
        setMinimumSize(fullWindowMinimum);
        initialLogicalMinimumSize = new Dimension(0, 0);
        try { setBounds(fullWindowBounds); }
        finally { initialLogicalMinimumSize = null; }
        setExtendedState(fullWindowState & ~Frame.ICONIFIED);
        recorder.refreshControlRegion();
        revalidate();
        repaint();
    }

    /** 所有录制结束路径共用收尾；紧凑模式保留既有完整窗口恢复流程。 */
    private void restoreWindowAfterRecording() {
        exitCompactRecordingMode();
        recordingControlRegionEnabled = false;
        if (windowMinimizedForRecording) {
            WindowManager.restore(this);
        }
        windowMinimizedForRecording = false;
        recorder.refreshControlRegion();
    }

    private void toggleRecordingPause() {
        if (recorder.getRecordingStatus().paused()) recorder.resume();
        else recorder.pause();
        refreshRecordingControlBar();
    }

    /** 动作数量由录制器轻量快照取得，截图结束后仍以实际暂停状态显示按钮。 */
    private void refreshRecordingControlBar() {
        if (!compactRecording) return;
        MacroRecorder.RecordingStatus status = recorder.getRecordingStatus();
        recordingBarStatus.setText(status.capturing() ? "正在截图" : status.paused() ? "已暂停" : "正在录制");
        recordingBarStatus.setForeground(status.paused() ? ACCENT_COLOR : DANGER_COLOR);
        recordingBarCount.setText(status.nodeCount() + " 项");
        recordingBarPauseButton.setText(status.paused() ? "继续" : "暂停");
        ButtonIconType pauseIcon = status.paused() ? ButtonIconType.PLAY : ButtonIconType.PAUSE;
        if (!(recordingBarPauseButton.getIcon() instanceof LineIcon icon) || icon.type != pauseIcon) {
            setButtonIcon(recordingBarPauseButton, pauseIcon);
        }
        recordingBarCaptureButton.setEnabled(status.recording() && !status.capturing());
        recordingBarPauseButton.setEnabled(status.recording() && !status.capturing());
        recordingBarStopButton.setEnabled(status.recording());
        if (nativeRecordingTitleBar != null && nativeRecordingTitleBar.isActive()) {
            nativeRecordingTitleBar.refresh();
        }
        recorder.refreshControlRegion();
    }

    /**
     * 设置主窗口的初始逻辑尺寸。
     *
     * <p>Windows DPI 补偿后的 minimumSize 大于 Swing 逻辑最小尺寸，
     * {@link Window} 会用该补偿值钳制程序化的 setSize 调用。
     * 初始化期间临时返回逻辑最小尺寸，使默认窗口与用户手动缩放到的
     * 原生最小尺寸保持一致。</p>
     *
     * @param logicalSize Swing 逻辑坐标中的初始尺寸
     */
    private void setInitialLogicalSize(Dimension logicalSize) {
        initialLogicalMinimumSize = logicalSize;
        try {
            setSize(logicalSize);
            setLocationRelativeTo(null);
        } finally {
            initialLogicalMinimumSize = null;
        }
    }

    @Override
    public Dimension getMinimumSize() {
        if (initialLogicalMinimumSize != null) {
            return initialLogicalMinimumSize;
        }
        return super.getMinimumSize();
    }

    /**
     * 按当前窗口所在图形设备的 DPI 缩放比例设置原生最小窗口尺寸。
     *
     * @param logicalMinimumSize Swing 逻辑坐标中的最小尺寸
     */
    private void setDpiAwareMinimumSize(Dimension logicalMinimumSize) {
        GraphicsConfiguration graphicsConfiguration = getGraphicsConfiguration();
        double scaleX = graphicsConfiguration.getDefaultTransform().getScaleX();
        double scaleY = graphicsConfiguration.getDefaultTransform().getScaleY();

        Dimension nativeMinimumSize = new Dimension(
                (int) Math.round(logicalMinimumSize.width * scaleX),
                (int) Math.round(logicalMinimumSize.height * scaleY)
        );

        setMinimumSize(nativeMinimumSize);
    }

    private JPanel createActionTimelinePage() {
        JPanel panel = new JPanel(new BorderLayout());

        panel.setBackground(BACKGROUND_COLOR);

        panel.setBorder(new EmptyBorder(24, 28, 18, 28));

        JButton backButton = new JButton("返回");

        styleSecondaryButton(backButton);
        setButtonIcon(backButton, ButtonIconType.BACK);

        backButton.addActionListener(e -> showHomePage());

        JPanel top = new JPanel(new BorderLayout());

        top.setOpaque(false);

        top.setBorder(new EmptyBorder(0, 0, 16, 0));

        top.add(backButton, BorderLayout.WEST);

        panel.add(top, BorderLayout.NORTH);

        panel.add(actionTimelinePanel, BorderLayout.CENTER);

        return panel;
    }

    private void showActionTimeline() {
        actionTimelinePanel.setFlow(macroFlow);

        pageLayout.show(pagePanel, ACTION_TIMELINE_PAGE);
    }

    /** 显示主页面。 */
    public void showHomePage() {
        pageLayout.show(pagePanel, HOME_PAGE);
        refreshRecordingControlBounds();
    }

    /** 显示图像采集页面。 */
    public void showImageCapturePage() {
        pageLayout.show(pagePanel, IMAGE_CAPTURE_PAGE);
        refreshRecordingControlBounds();
    }

    private JPanel createHeader() {
        JPanel panel = new JPanel(new BorderLayout());

        panel.setOpaque(false);

        JLabel titleLabel = new JLabel(ApplicationInfo.APP_NAME);

        titleLabel.setFont(FONT_TITLE);
        titleLabel.setForeground(TEXT_PRIMARY);

        JLabel subtitleLabel = new JLabel("录制并重复你的鼠标与键盘操作");

        subtitleLabel.setFont(FONT_SMALL);
        subtitleLabel.setForeground(TEXT_SECONDARY);

        JPanel titlePanel = new JPanel();

        titlePanel.setOpaque(false);

        titlePanel.setLayout(new BoxLayout(titlePanel, BoxLayout.Y_AXIS));

        titlePanel.add(titleLabel);

        titlePanel.add(Box.createVerticalStrut(5));

        titlePanel.add(subtitleLabel);

        styleSecondaryButton(settingsButton);
        setButtonIcon(settingsButton, ButtonIconType.SETTINGS);
        settingsButton.setText("设置");
        settingsButton.setToolTipText("打开设置");
        settingsButton.setPreferredSize(new Dimension(88, 36));

        styleSecondaryButton(imageCaptureButton);
        setButtonIcon(imageCaptureButton, ButtonIconType.IMAGE_CAPTURE);
        imageCaptureButton.setToolTipText("打开图像采集页面");
        Dimension imageCaptureButtonSize = new Dimension(112, 36);
        imageCaptureButton.setPreferredSize(imageCaptureButtonSize);
        imageCaptureButton.setMinimumSize(imageCaptureButtonSize);
        imageCaptureButton.setMaximumSize(imageCaptureButtonSize);

        styleSecondaryButton(imageRecognitionButton);
        setButtonIcon(imageRecognitionButton, ButtonIconType.IMAGE_RECOGNITION);
        imageRecognitionButton.setFont(FONT_SMALL.deriveFont(Font.BOLD, 11f));
        imageRecognitionButton.setBorder(new EmptyBorder(3, 8, 3, 8));
        imageRecognitionButton.setIconTextGap(5);
        imageRecognitionButton.setToolTipText("打开图像识别测试窗口");
        Dimension imageRecognitionButtonSize = new Dimension(112, 24);
        imageRecognitionButton.setPreferredSize(imageRecognitionButtonSize);
        imageRecognitionButton.setMinimumSize(imageRecognitionButtonSize);
        imageRecognitionButton.setMaximumSize(imageRecognitionButtonSize);

        JPanel imageActions = new JPanel();
        imageActions.setOpaque(false);
        imageActions.setLayout(new BoxLayout(imageActions, BoxLayout.Y_AXIS));
        imageCaptureButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        imageRecognitionButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        imageActions.add(imageCaptureButton);
        imageActions.add(Box.createVerticalStrut(4));
        imageActions.add(imageRecognitionButton);

        JPanel actions = new JPanel();
        actions.setOpaque(false);
        actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS));
        imageActions.setAlignmentY(Component.TOP_ALIGNMENT);
        settingsButton.setAlignmentY(Component.TOP_ALIGNMENT);
        actions.add(imageActions);
        actions.add(Box.createHorizontalStrut(10));
        actions.add(settingsButton);

        panel.add(titlePanel, BorderLayout.WEST);
        panel.add(actions, BorderLayout.EAST);

        return panel;
    }

    private JPanel createMacroCard() {
        RoundedPanel card = createCard();

        card.setLayout(new BorderLayout());
        card.setBorder(new EmptyBorder(17, 20, 17, 20));

        JLabel title = createSectionLabel("当前宏");

        JLabel fileIcon = new JLabel("●");
        fileIcon.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        fileIcon.setForeground(ACCENT_COLOR);

        fileLabel.setFont(FONT_NORMAL);
        fileLabel.setForeground(TEXT_PRIMARY);

        JPanel fileInfo = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        fileInfo.setOpaque(false);
        fileInfo.add(fileIcon);
        fileInfo.add(Box.createHorizontalStrut(10));
        fileInfo.add(fileLabel);

        JPanel windowInfo = createTargetWindowInfo();

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setOpaque(false);
        bottom.setBorder(new EmptyBorder(14, 0, 0, 0));

        actionLabel.setFont(FONT_SMALL);
        actionLabel.setForeground(TEXT_SECONDARY);
        actionLabel.setHorizontalAlignment(SwingConstants.LEFT);
        actionLabel.setOpaque(false);
        actionLabel.setContentAreaFilled(false);
        actionLabel.setBorderPainted(false);
        actionLabel.setBorder(BorderFactory.createEmptyBorder());
        actionLabel.setFocusPainted(false);
        actionLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        actionLabel.setToolTipText("点击查看和编辑动作列表");

        actionLabel.addActionListener(e -> showActionTimeline());

        bottom.add(actionLabel, BorderLayout.CENTER);

        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.setOpaque(false);

        fileInfo.setAlignmentX(Component.LEFT_ALIGNMENT);
        center.add(fileInfo);
        center.add(Box.createVerticalStrut(12));
        windowInfo.setAlignmentX(Component.LEFT_ALIGNMENT);
        center.add(windowInfo);

        card.add(title, BorderLayout.NORTH);
        card.add(center, BorderLayout.CENTER);
        card.add(bottom, BorderLayout.SOUTH);

        return card;
    }

    private JPanel createRecordSection() {
        JPanel section = new JPanel(new BorderLayout());

        section.setOpaque(false);

        JLabel title = createSectionLabel("录制");

        section.add(title, BorderLayout.NORTH);

        JPanel recordButtons = new JPanel(new GridLayout(1, 2, 10, 0));
        recordButtons.setOpaque(false);

        recordButtonPanel = new JPanel(new CardLayout());
        recordButtonPanel.setOpaque(false);

        stylePrimaryButton(recordButton);
        styleDangerButton(stopRecordButton);
        styleSecondaryButton(selectWindowButton);
        styleSecondaryButton(recordingImageButton);
        setButtonIcon(recordButton, ButtonIconType.RECORD);
        setButtonIcon(stopRecordButton, ButtonIconType.STOP);
        setButtonIcon(selectWindowButton, ButtonIconType.WINDOW);
        setButtonIcon(recordingImageButton, ButtonIconType.IMAGE_CAPTURE);

        recordButtonPanel.add(recordButton, "record");
        recordButtonPanel.add(stopRecordButton, "stop");

        recordContextButtonPanel = new JPanel(new CardLayout());
        recordContextButtonPanel.setOpaque(false);
        recordContextButtonPanel.add(selectWindowButton, "selectWindow");
        recordContextButtonPanel.add(recordingImageButton, "imageOperation");

        recordButtons.add(recordButtonPanel);
        recordButtons.add(recordContextButtonPanel);

        section.add(recordButtons, BorderLayout.CENTER);

        JPanel hintPanel = new JPanel(new BorderLayout());
        hintPanel.setOpaque(false);
        hintPanel.setBorder(new EmptyBorder(8, 2, 0, 2));

        JLabel hint = createHotkeyHint("F10 图像操作 · F12 停止");
        hintPanel.add(hint, BorderLayout.EAST);
        section.add(hintPanel, BorderLayout.SOUTH);

        return section;
    }

    private JPanel createPlaybackSection() {
        RoundedPanel card = createCard();

        card.setLayout(new BorderLayout());

        card.setBorder(new EmptyBorder(17, 20, 17, 20));

        JLabel title = createSectionLabel("播放设置");

        JPanel settings = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));

        settings.setOpaque(false);

        JLabel loopLabel = new JLabel("循环次数");

        loopLabel.setFont(FONT_NORMAL);

        loopLabel.setForeground(TEXT_SECONDARY);

        loopCountSpinner.setFont(FONT_NORMAL);
        ComponentStyleUtil.styleSpinner(loopCountSpinner);
        NumericInputUtil.installSpinner(loopCountSpinner, "循环次数");
        infiniteLoopCheckBox.setFont(FONT_NORMAL);
        ComponentStyleUtil.styleCheckBox(infiniteLoopCheckBox);

        // 限制循环次数输入框的布局尺寸
        Dimension spinnerSize = new Dimension(76, 32);
        loopCountSpinner.setPreferredSize(spinnerSize);
        loopCountSpinner.setMinimumSize(spinnerSize);

        settings.add(loopLabel);

        settings.add(Box.createHorizontalStrut(12));

        settings.add(loopCountSpinner);

        settings.add(Box.createHorizontalStrut(18));

        settings.add(infiniteLoopCheckBox);

        JPanel buttons = new JPanel(new GridLayout(1, 3, 10, 0));
        buttons.setOpaque(false);

        styleSecondaryButton(playButton);
        styleSecondaryButton(pausePlayButton);
        styleDangerButton(stopPlayButton);
        setButtonIcon(playButton, ButtonIconType.PLAY);
        setButtonIcon(pausePlayButton, ButtonIconType.PAUSE);
        setButtonIcon(stopPlayButton, ButtonIconType.STOP);

        buttons.add(playButton);
        buttons.add(pausePlayButton);
        buttons.add(stopPlayButton);

        JPanel center = new JPanel(new BorderLayout());

        center.setOpaque(false);

        center.add(settings, BorderLayout.NORTH);

        center.add(Box.createVerticalStrut(14), BorderLayout.CENTER);

        center.add(buttons, BorderLayout.SOUTH);

        card.add(title, BorderLayout.NORTH);

        card.add(center, BorderLayout.CENTER);

        return card;
    }

    private JPanel createFileActions() {
        JPanel panel = new JPanel(new GridLayout(1, 3, 10, 0));
        panel.setOpaque(false);

        styleSecondaryButton(loadButton);
        styleSecondaryButton(saveButton);
        styleSecondaryButton(saveAsButton);
        setButtonIcon(loadButton, ButtonIconType.LOAD);
        setButtonIcon(saveButton, ButtonIconType.SAVE);
        setButtonIcon(saveAsButton, ButtonIconType.SAVE_AS);

        panel.add(loadButton);
        panel.add(saveButton);
        panel.add(saveAsButton);
        return panel;
    }

    private JPanel createStatusBar() {
        JPanel panel = new JPanel(new BorderLayout());

        panel.setOpaque(false);

        panel.setBorder(new EmptyBorder(14, 2, 0, 2));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));

        left.setOpaque(false);

        statusDot.setFont(new Font(UiConstants.DEFAULT_FONT_FAMILY, Font.PLAIN, 11));

        statusDot.setForeground(SUCCESS_COLOR);

        statusLabel.setFont(FONT_SMALL);

        statusLabel.setForeground(TEXT_SECONDARY);

        left.add(statusDot);

        left.add(Box.createHorizontalStrut(7));

        left.add(statusLabel);

        JLabel hotkey = createHotkeyHint("F11 暂停/继续 · F12 停止");

        panel.add(left, BorderLayout.WEST);

        panel.add(hotkey, BorderLayout.EAST);

        return panel;
    }

    private JLabel createHotkeyHint(String text) {
        JLabel label = new JLabel(text);
        label.setFont(new Font(UiConstants.DEFAULT_FONT_FAMILY, Font.PLAIN, 10));
        label.setForeground(TEXT_MUTED);
        hotkeyHints.add(label);
        return label;
    }

    private void updateHotkeyVisibility() {
        boolean visible = getWidth() >= 820;
        for (JLabel label : hotkeyHints) {
            label.setVisible(visible);
        }
    }

    private RoundedPanel createCard() {
        return new RoundedPanel(CARD_COLOR, 14);
    }

    private JLabel createSectionLabel(String text) {
        JLabel label = new JLabel(text);

        label.setFont(FONT_BOLD);

        label.setForeground(TEXT_PRIMARY);

        return label;
    }

    private void stylePrimaryButton(JButton button) {
        styleButton(button, ACCENT_COLOR, Color.WHITE, ACCENT_HOVER_COLOR);
    }

    private void styleSecondaryButton(JButton button) {
        styleButton(button, CARD_LIGHT_COLOR, TEXT_PRIMARY, new Color(43, 47, 56));
    }

    private void styleDangerButton(JButton button) {
        styleButton(button, DANGER_COLOR, Color.WHITE, THEME.dangerHover());
    }

    private void styleButton(JButton button, Color background, Color foreground, Color hover) {
        button.setFont(FONT_BOLD);
        button.setForeground(foreground);
        button.setBackground(background);
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setBorder(new EmptyBorder(10, 14, 10, 14));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setIconTextGap(8);
        button.setRolloverEnabled(true);
        button.setUI(new RoundedButtonUI(background, hover));
    }

    private void setButtonIcon(JButton button, ButtonIconType iconType) {
        button.setIcon(new LineIcon(iconType));
    }

    private void updatePauseButton(boolean showResumeAction) {
        pausePlayButton.setText(showResumeAction ? "继续" : "暂停");
        setButtonIcon(
                pausePlayButton,
                showResumeAction ? ButtonIconType.PLAY : ButtonIconType.PAUSE
        );
    }

    private void initEvents() {
        settingsButton.addActionListener(e -> openSettings());

        imageCaptureButton.addActionListener(e -> showImageCapturePage());

        imageRecognitionButton.addActionListener(e -> openImageRecognitionFrame());

        recordButton.addActionListener(e -> startRecording());

        stopRecordButton.addActionListener(e -> stopRecording());

        recordingImageButton.addActionListener(e -> requestRecordingImageCapture());
        recordingBarCaptureButton.addActionListener(e -> requestRecordingImageCapture());
        recordingBarPauseButton.addActionListener(e -> toggleRecordingPause());
        recordingBarStopButton.addActionListener(e -> stopRecording());

        loadButton.addActionListener(e -> loadMacro());

        saveButton.addActionListener(e -> saveMacro());

        saveAsButton.addActionListener(e -> saveMacroAs());

        playButton.addActionListener(e -> startPlaying());

        pausePlayButton.addActionListener(e -> togglePausePlay());

        stopPlayButton.addActionListener(e -> stopPlaying());

        infiniteLoopCheckBox.addActionListener(e -> loopCountSpinner.setEnabled(!infiniteLoopCheckBox.isSelected()));

        selectWindowButton.addActionListener(e -> showWindowSelectorPage());

        windowSelectorPanel.setOnBack(this::stopWindowSelection);
        windowSelectorPanel.setOnReselect(this::startWindowSelection);
        windowSelectorPanel.setOnConfirm(this::confirmWindowSelection);

        windowSelector.setOnWindowMove(windowInfo ->
                SwingUtilities.invokeLater(() ->
                        windowSelectorPanel.updateWindowInfo(windowInfo)
                )
        );

        windowSelector.setOnSelected(windowInfo ->
                SwingUtilities.invokeLater(() ->
                        windowSelectorPanel.updateWindowInfo(windowInfo)
                )
        );

        clearWindowButton.addActionListener(e -> clearTargetWindow());

        // 播放暂停快捷键统一由全局 StopKeyListener 处理：F11 暂停/继续，F12 停止。
        // 不保留窗口级空格绑定，避免与全局快捷键冲突，也避免输入文本时误触发。


        // 窗口缩放时更新快捷键显示。
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                updateHotkeyVisibility();
                refreshRecordingControlBounds();
            }
            @Override public void componentMoved(java.awt.event.ComponentEvent event) { refreshRecordingControlBounds(); }
            @Override public void componentShown(java.awt.event.ComponentEvent event) { refreshRecordingControlBounds(); }
            @Override public void componentHidden(java.awt.event.ComponentEvent event) { recorder.refreshControlRegion(); }
        });
        java.awt.event.ComponentAdapter controlLayoutListener = new java.awt.event.ComponentAdapter() {
            @Override public void componentMoved(java.awt.event.ComponentEvent event) { refreshRecordingControlBounds(); }
            @Override public void componentResized(java.awt.event.ComponentEvent event) { refreshRecordingControlBounds(); }
            @Override public void componentShown(java.awt.event.ComponentEvent event) { refreshRecordingControlBounds(); }
            @Override public void componentHidden(java.awt.event.ComponentEvent event) { refreshRecordingControlBounds(); }
        };
        for (JButton button : List.of(recordButton, selectWindowButton, stopRecordButton, recordingImageButton)) {
            button.addComponentListener(controlLayoutListener);
            button.addHierarchyListener(event -> {
                if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0) {
                    refreshRecordingControlBounds();
                }
            });
        }
        addWindowStateListener(event -> recorder.refreshControlRegion());
        addWindowFocusListener(new java.awt.event.WindowAdapter() {
            @Override public void windowGainedFocus(java.awt.event.WindowEvent event) { recorder.refreshControlRegion(); }
            @Override public void windowLostFocus(java.awt.event.WindowEvent event) { recorder.refreshControlRegion(); }
        });

    }

    /**
     * 打开设置窗口。
     */
    private void openSettings() {
        SettingsDialog dialog = new SettingsDialog(this, appConfig);
        dialog.setVisible(true);
    }

    /** 从图像采集页面启动区域截图。 */
    private void startImageCapture() {
        screenCaptureController.startCapture();
    }

    /** 请求录制器进入图像采集状态，按钮和 F10 共用同一入口。 */
    private void requestRecordingImageCapture() {
        if (!recorder.requestImageCapture()) {
            return;
        }
        recordingImageButton.setEnabled(false);
    }

    /** 在 Swing 线程中启动录制专用的冻结截图选区。 */
    private void startRecordingImageCapture(
            MacroRecorder.ImageCaptureSession session
    ) {
        if (!recording || session == null) {
            if (session != null) {
                recorder.cancelImageCapture(session.id());
            }
            return;
        }

        activeRecordingImageCaptureSession = session;
        recordingImageButton.setEnabled(false);
        statusLabel.setText("正在选择图像区域");
        statusDot.setForeground(ACCENT_COLOR);
        recordingScreenCaptureController.startCapture();
    }

    /** 选区确认后在后台保存 PNG，保存成功才提交 IMAGE_CHECK 节点。 */
    private void handleRecordingImageCaptured(BufferedImage image) {
        MacroRecorder.ImageCaptureSession session =
                activeRecordingImageCaptureSession;
        if (session == null || !recording) {
            return;
        }

        File targetFile = imageCaptureStorage.createCaptureFile(
                appConfig.getDefaultScreenCaptureDirectory()
        );
        statusLabel.setText("正在保存图像模板");
        recordingImageSaveWorker = new SwingWorker<>() {
            @Override
            protected File doInBackground() throws Exception {
                return imageCaptureStorage.savePng(image, targetFile);
            }

            @Override
            protected void done() {
                recordingImageSaveWorker = null;
                try {
                    File savedFile = get();
                    boolean completed = recorder.completeImageCapture(
                            session.id(),
                            new ImageCheckConfig(
                                    savedFile.getAbsolutePath(),
                                    RECORDING_IMAGE_MATCH_THRESHOLD
                            )
                    );
                    if (completed && recording) {
                        statusLabel.setText("正在录制 · 已加入图像识别");
                        statusDot.setForeground(DANGER_COLOR);
                    }
                } catch (CancellationException exception) {
                    recorder.cancelImageCapture(session.id());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    failRecordingImageCapture(session, exception);
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause() == null
                            ? exception
                            : exception.getCause();
                    failRecordingImageCapture(session, cause);
                } finally {
                    if (activeRecordingImageCaptureSession == session) {
                        activeRecordingImageCaptureSession = null;
                    }
                    recordingImageButton.setEnabled(recording);
                    refreshRecordingControlBar();
                }
            }
        };
        recordingImageSaveWorker.execute();
    }

    /** 用户取消选区后恢复录制时间基准与鼠标桥接。 */
    private void handleRecordingImageCaptureCancelled() {
        MacroRecorder.ImageCaptureSession session =
                activeRecordingImageCaptureSession;
        activeRecordingImageCaptureSession = null;
        if (session != null) {
            recorder.cancelImageCapture(session.id());
        }
        if (recording) {
            statusLabel.setText("正在录制 · 已取消图像操作");
            statusDot.setForeground(DANGER_COLOR);
            recordingImageButton.setEnabled(true);
        }
        refreshRecordingControlBar();
    }

    /** 截图流程异常时恢复录制并显示统一错误提示。 */
    private void handleRecordingImageCaptureFailed(Throwable throwable) {
        MacroRecorder.ImageCaptureSession session =
                activeRecordingImageCaptureSession;
        activeRecordingImageCaptureSession = null;
        if (session != null) {
            recorder.cancelImageCapture(session.id());
        }
        if (recording) {
            statusLabel.setText("正在录制 · 图像操作失败");
            statusDot.setForeground(DANGER_COLOR);
            recordingImageButton.setEnabled(true);
            showRecordingCaptureError("图像操作采集失败", throwable);
        }
        refreshRecordingControlBar();
    }

    /** PNG 保存失败时按取消处理，避免流程节点引用不存在的模板。 */
    private void failRecordingImageCapture(
            MacroRecorder.ImageCaptureSession session,
            Throwable throwable
    ) {
        recorder.cancelImageCapture(session.id());
        if (recording) {
            statusLabel.setText("正在录制 · 图像模板保存失败");
            statusDot.setForeground(DANGER_COLOR);
            showRecordingCaptureError("图像模板保存失败", throwable);
        }
    }

    /** 错误提示也属于采集交互，不把确认提示的键鼠或停留时间录入宏。 */
    private void showRecordingCaptureError(String message, Throwable throwable) {
        boolean resumeAfterDialog = recorder.pause();
        try { AppMessageDialog.showError(this, message, throwable); }
        finally {
            if (resumeAfterDialog && recording) recorder.resume();
            refreshRecordingControlBar();
        }
    }

    private void startRecording() {
        playButton.setEnabled(false);
        pausePlayButton.setEnabled(false);
        stopPlayButton.setEnabled(false);
        recordingControlRegionEnabled = false;
        refreshRecordingControlBounds();

        try {
            recorder.start(targetWindow);
        } catch (Exception e) {
            recordingControlRegionEnabled = false;
            recorder.refreshControlRegion();
            restoreRecordingStartFailureState();
            AppMessageDialog.showError(this, "录制启动失败", e);
            return;
        }

        recording = true;
        refreshSaveAsState();
        initializeNewRecordingState();
        statusLabel.setText("正在录制");
        statusDot.setForeground(DANGER_COLOR);
        showRecordStopButton();
        stopRecordButton.setEnabled(true);
        loadButton.setEnabled(false);
        saveButton.setEnabled(false);
        playButton.setEnabled(false);
        stopPlayButton.setEnabled(false);
        applyRecordingStartBehavior();
    }

    /** 录制成功启动后初始化新的宏状态。 */
    private void initializeNewRecordingState() {
        currentFile = null;
        fileLabel.setText("新录制 MR");
        actions = new ArrayList<>();
        macroFlow = MacroFlowFactory.fromActions(actions);
        actionTimelinePanel.setFlow(macroFlow);
        updateActionInfo();
    }

    /** 录制启动失败后恢复主窗口操作状态。 */
    private void restoreRecordingStartFailureState() {
        recording = false;
        showRecordButton();
        recordButton.setEnabled(true);
        stopRecordButton.setEnabled(false);
        loadButton.setEnabled(true);
        pausePlayButton.setEnabled(false);
        updatePauseButton(false);
        stopPlayButton.setEnabled(false);
        infiniteLoopCheckBox.setEnabled(true);
        loopCountSpinner.setEnabled(!infiniteLoopCheckBox.isSelected());
        statusLabel.setText("录制启动失败");
        statusDot.setForeground(DANGER_COLOR);
        refreshActionState();
    }

    private void stopRecording() {
        try {
            recorder.stop();
        } catch (NativeHookException e) {
            showError("停止录制失败：" + e.getMessage());
        }
    }

    /**
     * 加载上一次使用的 MR 文件。
     */
    private void loadLastMacro() {
        String lastMacroFile = appConfig.getLastMacroFile();

        if (lastMacroFile == null || lastMacroFile.isBlank()) {
            return;
        }

        File file = new File(lastMacroFile);

        if (!file.exists() || !file.isFile()) {

            appConfig.setLastMacroFile(null);

            return;
        }

        try {
            loadMacroFile(file);
            statusLabel.setText("已自动加载");
            statusLabel.setToolTipText(null);
            statusDot.setForeground(SUCCESS_COLOR);

        } catch (MacroFormatException e) {
            appConfig.setLastMacroFile(null);
            reportMacroFormatFailure(file, e);
        } catch (Exception e) {
            appConfig.setLastMacroFile(null);
            reportLoadFailure(file, e);
        }
    }

    private void loadMacro() {
        File file;
        try {
            file = fileDialogs.showDialog(this, createMacroFileOptions(false)).orElse(null);
        } catch (FileDialogException exception) {
            AppMessageDialog.showError(this, exception.getMessage(), exception);
            return;
        }
        if (file == null) return;

        try {
            loadMacroFile(file);
            statusLabel.setText("已加载");
            statusLabel.setToolTipText(null);
            statusDot.setForeground(SUCCESS_COLOR);
        } catch (MacroFormatException e) {
            reportMacroFormatFailure(file, e);
        } catch (Exception e) {
            reportLoadFailure(file, e);
        }
    }

    private void saveMacro() {
        if (!hasTimelineNodes()) {
            return;
        }

        if (currentFile == null) {
            saveMacroAs();
            return;
        }

        saveMacroToFile(currentFile);
    }

    /**
     * 另存为宏文件。
     */
    private void saveMacroAs() {
        if (!isIdle() || recorder.getRecordingStatus().recording()) {
            return;
        }
        if (!hasTimelineNodes()) {
            return;
        }

        File file;
        try {
            file = fileDialogs.showDialog(this, createMacroFileOptions(true)).orElse(null);
        } catch (FileDialogException exception) {
            AppMessageDialog.showError(this, exception.getMessage(), exception);
            return;
        }
        if (file == null) return;

        File selectedFile = file;
        if (!file.getName().toLowerCase().endsWith(MacroFormat.FILE_SUFFIX)) {
            file = new File(file.getAbsolutePath() + MacroFormat.FILE_SUFFIX);
        }

        // 业务补充扩展名后可能指向另一个已存在的文件，原生面板尚未确认过该最终路径。
        if (!file.equals(selectedFile) && file.exists() && !AppMessageDialog.showConfirm(
                this, "覆盖宏文件", "目标文件已经存在，是否覆盖？")) {
            return;
        }

        saveMacroToFile(file);
    }


    /**
     * 保存宏到指定文件。
     *
     * @param file 目标 MR 文件
     */
    private void saveMacroToFile(File file) {
        if (!isIdle()) {
            return;
        }

        try {
            MacroFlow flowToSave = ImageTemplatePathUtil.forSave(macroFlow, getMacroBaseDirectory(), file);
            storage.save(
                    actions,
                    targetWindow,
                    flowToSave,
                    file.getAbsolutePath()
            );

            // 文件写入成功后，再更新当前文件信息。
            if (macroFlow != flowToSave) {
                macroFlow = flowToSave;
                actionTimelinePanel.setFlow(macroFlow);
            }
            currentFile = file;
            appConfig.setLastMacroFile(file);

            fileLabel.setText(file.getName());
            statusLabel.setText("已保存");
            statusLabel.setToolTipText(null);
            statusDot.setForeground(SUCCESS_COLOR);
        } catch (Exception e) {
            reportSaveFailure(file, e);
        }
    }

    /** 使用状态栏报告宏文件加载失败，并将完整异常写入日志。 */
    private void reportLoadFailure(File file, Exception exception) {
        statusLabel.setText("加载失败：无法识别的 MR 文件");
        statusLabel.setToolTipText(null);
        statusDot.setForeground(DANGER_COLOR);
        logFileOperationFailure("加载", file, exception);
    }

    /** 使用状态栏报告明确的 MR 文件版本不兼容信息。 */
    private void reportMacroFormatFailure(
            File file,
            MacroFormatException exception
    ) {
        statusLabel.setText(
                "MR版本不兼容：文件版本" + exception.getFileVersion()
                        + "，当前支持" + exception.getCurrentVersion()
        );
        statusLabel.setToolTipText(exception.getMessage());
        statusDot.setForeground(DANGER_COLOR);
        logFileOperationFailure("加载", file, exception);
    }

    /** 使用状态栏报告宏文件保存失败，并将完整异常写入日志。 */
    private void reportSaveFailure(File file, Exception exception) {
        statusLabel.setText("保存失败：无法写入文件");
        statusLabel.setToolTipText(null);
        statusDot.setForeground(DANGER_COLOR);
        logFileOperationFailure("保存", file, exception);
    }

    /** 将文件操作异常及文件路径写入错误日志。 */
    private void logFileOperationFailure(
            String operation,
            File file,
            Exception exception
    ) {
        String filePath = file == null ? "<未知文件>" : file.getAbsolutePath();
        System.err.println("MR 文件" + operation + "失败：" + filePath);
        exception.printStackTrace(System.err);
    }

    /** 校验目标窗口并在线程中播放当前流程图。 */
    private void startPlaying() {
        if (!hasTimelineNodes() || player.getState() != PlayerState.IDLE) {
            return;
        }

        int loopCount;
        try {
            loopCount = infiniteLoopCheckBox.isSelected() ? -1 : NumericInputUtil.commitSpinnerInt(loopCountSpinner);
        } catch (IllegalArgumentException exception) {
            AppMessageDialog.showWarning(this, exception.getMessage());
            return;
        }

        ImageRecognitionDiagnostics.log("PLAYBACK", "WINDOW_PREPARATION_START",
                "TargetWindow: " + targetWindow
                        + "\nMRFile: " + currentFile
                        + "\nTemplateBaseDirectory: " + getMacroBaseDirectory().getAbsolutePath()
                        + "\nMinimizeOnPlayStart: " + appConfig.isMinimizeOnPlayStart()
                        + "\nMainWindowState: " + getExtendedState()
                        + "\nRecognitionTestWindowVisible: " + (imageRecognitionFrame != null
                                && imageRecognitionFrame.isVisible())
                        + "\nForeground: " + ImageRecognitionDiagnostics.foregroundWindow());
        if (targetWindow != null) {
            WinDef.HWND targetHwnd = WindowsWindowUtil.findWindow(targetWindow);

            if (targetHwnd == null) {
                showError("未找到目标窗口，请确认目标程序正在运行。");
                return;
            }

            if (!WindowsWindowUtil.activateWindow(targetHwnd)) {
                ImageRecognitionDiagnostics.log("PLAYBACK", "TARGET_ACTIVATION_FAILED",
                        "TargetHWND: " + targetHwnd);
                showError("无法激活目标窗口。");
                return;
            }
            ImageRecognitionDiagnostics.log("PLAYBACK", "TARGET_ACTIVATED",
                    "TargetHWND: " + targetHwnd
                            + "\nForeground: " + ImageRecognitionDiagnostics.foregroundWindow());
        }

        lockTimelineEditing();
        statusLabel.setText(loopCount == -1 ? "无限循环播放中" : "播放中 · " + loopCount + " 次");

        statusDot.setForeground(ACCENT_COLOR);

        recordButton.setEnabled(false);
        stopRecordButton.setEnabled(false);

        loadButton.setEnabled(false);
        saveButton.setEnabled(false);

        playButton.setEnabled(false);
        pausePlayButton.setEnabled(true);
        updatePauseButton(false);
        stopPlayButton.setEnabled(true);

        loopCountSpinner.setEnabled(false);
        infiniteLoopCheckBox.setEnabled(false);

        Thread playThread = new Thread(() -> {

            StopKeyListener listener = new StopKeyListener(player);
            Exception playbackFailure = null;

            try {
                if (!GlobalScreen.isNativeHookRegistered()) {

                    GlobalScreen.registerNativeHook();
                }

                GlobalScreen.addNativeKeyListener(listener);

                player.play(macroFlow, loopCount, imageMatcher);

            } catch (Exception e) {
                playbackFailure = e;
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }

            } finally {

                GlobalScreen.removeNativeKeyListener(listener);
                Exception failure = playbackFailure;

                SwingUtilities.invokeLater(() -> {
                    restoreWindowAfterPlayback();
                    restorePlaybackFinishedState(failure);
                    if (failure != null) {
                        String message = failure.getMessage();
                        AppMessageDialog.showError(this,
                                message == null ? "播放失败" : "播放失败：" + message, failure);
                    }
                });
            }

        }, "macro-player");

        prepareWindowForPlayback();
        ImageRecognitionDiagnostics.log("PLAYBACK", "WINDOW_PREPARATION_END",
                "MainWindowState: " + getExtendedState()
                        + "\nMainWindowVisible: " + isVisible()
                        + "\nMinimizedForPlayback: " + windowMinimizedForPlay
                        + "\nForeground: " + ImageRecognitionDiagnostics.foregroundWindow()
                        + "\nCaptureSettleDelayMillis: 0（原播放流程，无额外截图等待）");
        playThread.start();
    }

    /** 记录播放前窗口状态，并按配置最小化主窗口。 */
    private void prepareWindowForPlayback() {
        windowMinimizedBeforePlay = WindowManager.isMinimized(this);
        windowMinimizedForPlay = false;

        if (appConfig.isMinimizeOnPlayStart() && !windowMinimizedBeforePlay) {
            WindowManager.minimize(this);
            windowMinimizedForPlay = true;
        }
    }

    /** 播放结束后按配置恢复本次由应用最小化的主窗口。 */
    private void restoreWindowAfterPlayback() {
        boolean shouldRestore = windowMinimizedForPlay
                && !windowMinimizedBeforePlay
                && appConfig.isRestoreWindowAfterPlayStop();

        windowMinimizedBeforePlay = false;
        windowMinimizedForPlay = false;

        if (shouldRestore) {
            WindowManager.restore(this);
        }
    }

    /** 播放结束或异常时恢复主窗口操作状态。 */
    private void restorePlaybackFinishedState(Exception failure) {
        actionTimelinePanel.setEditingEnabled(player.getState() == PlayerState.IDLE);
        statusLabel.setText(failure == null ? "已停止" : "播放失败");
        statusDot.setForeground(failure == null ? SUCCESS_COLOR : DANGER_COLOR);
        recordButton.setEnabled(true);
        stopRecordButton.setEnabled(false);
        loadButton.setEnabled(true);
        pausePlayButton.setEnabled(false);
        updatePauseButton(false);
        stopPlayButton.setEnabled(false);
        refreshActionState();
        infiniteLoopCheckBox.setEnabled(true);
        loopCountSpinner.setEnabled(!infiniteLoopCheckBox.isSelected());
    }

    private void togglePausePlay() {
        if (player.getState() == PlayerState.PLAYING) {
            player.pause();

            updatePauseButton(true);
            statusLabel.setText("已暂停");
        } else if (player.getState() == PlayerState.PAUSED) {
            player.resume();

            updatePauseButton(false);
            statusLabel.setText("继续播放中");
        }
    }

    private void stopPlaying() {
        player.stop();
        statusLabel.setText("正在停止");
        statusDot.setForeground(ACCENT_COLOR);
        pausePlayButton.setEnabled(false);
        stopPlayButton.setEnabled(false);
    }

    /** 打开及另存为共用默认目录和 MR 过滤配置。 */
    private FileDialogOptions createMacroFileOptions(boolean save) {
        File defaultDirectory = appConfig.getDefaultSaveDirectory();

        if (!defaultDirectory.exists()) {
            defaultDirectory.mkdirs();
        }

        var options = FileDialogOptions.builder(save ? FileDialogOptions.Mode.SAVE_FILE
                        : FileDialogOptions.Mode.OPEN_FILE)
                .title(save ? "另存为 MR 文件" : "打开 MR 文件")
                .initialDirectory(defaultDirectory)
                .fileType(ApplicationInfo.APP_NAME + " 文件 (*" + MacroFormat.FILE_SUFFIX + ")",
                        MacroFormat.FILE_EXTENSION)
                .allowAllFiles(true);
        if (save) options.initialFile(currentFile).defaultExtension(MacroFormat.FILE_EXTENSION);
        return options.build();
    }

    private void updateActionInfo() {
        List<MacroNode> timelineNodes = MacroFlowFactory.timelineNodes(macroFlow);
        boolean containsFlowNode = timelineNodes.stream()
                .anyMatch(node -> node.getType() != com.macro.flow.NodeType.ACTION);
        actionLabel.setText(
                timelineNodes.size() + (containsFlowNode ? " 个节点" : " 个动作")
        );
    }

    private void updateState() {
        showRecordButton();

        stopRecordButton.setEnabled(false);

        stopPlayButton.setEnabled(false);

        playButton.setEnabled(false);
        pausePlayButton.setEnabled(false);
        updatePauseButton(false);
        stopPlayButton.setEnabled(false);
        saveButton.setEnabled(false);

        loadButton.setEnabled(true);

        recordButton.setEnabled(true);

        loopCountSpinner.setEnabled(true);

        infiniteLoopCheckBox.setEnabled(true);

        statusLabel.setText("已就绪");

        statusDot.setForeground(SUCCESS_COLOR);
        refreshSaveAsState();
    }

    private void updateRecordingStoppedState() {
        recording = false;
        restoreWindowAfterRecording();
        toFront();

        requestFocus();
        macroFlow = MacroFlowFactory.rebuildLinear(
                recorder.getRecordedNodes(),
                null
        );
        actions = MacroFlowFactory.extractActions(macroFlow);
        actionTimelinePanel.setFlow(macroFlow);

        statusLabel.setText("录制已停止");

        statusDot.setForeground(SUCCESS_COLOR);

        updateActionInfo();

        showRecordButton();

        recordButton.setEnabled(true);

        stopRecordButton.setEnabled(false);

        loadButton.setEnabled(true);
        refreshActionState();
        pausePlayButton.setEnabled(false);
        updatePauseButton(false);
        stopPlayButton.setEnabled(false);
    }

    /**
     * 录制器停止后的统一 UI 收尾。
     *
     * <p>F12 可能发生在截图、选区或 PNG 保存阶段，因此先终止这些异步
     * 工作，再从录制器快照构建最终流程。</p>
     */
    private void handleRecorderStopped() {
        recording = false;
        if (recordingImageSaveWorker != null
                && !recordingImageSaveWorker.isDone()) {
            recordingImageSaveWorker.cancel(true);
            recordingImageSaveWorker = null;
        }
        if (recordingScreenCaptureController != null) {
            recordingScreenCaptureController.cancelCapture();
        }
        activeRecordingImageCaptureSession = null;
        recordingImageButton.setEnabled(false);
        try {
            updateRecordingStoppedState();
        } finally {
            refreshSaveAsState();
        }
    }

    private void showRecordButton() {
        if (recordButtonPanel == null) {
            return;
        }

        CardLayout layout = (CardLayout) recordButtonPanel.getLayout();

        layout.show(recordButtonPanel, "record");
        if (recordContextButtonPanel != null) {
            CardLayout contextLayout =
                    (CardLayout) recordContextButtonPanel.getLayout();
            contextLayout.show(recordContextButtonPanel, "selectWindow");
        }
        recordingImageButton.setEnabled(false);
        refreshRecordingControlBounds();
    }

    private void showRecordStopButton() {
        if (recordButtonPanel == null) {
            return;
        }

        CardLayout layout = (CardLayout) recordButtonPanel.getLayout();

        layout.show(recordButtonPanel, "stop");
        if (recordContextButtonPanel != null) {
            CardLayout contextLayout =
                    (CardLayout) recordContextButtonPanel.getLayout();
            contextLayout.show(recordContextButtonPanel, "imageOperation");
        }
        recordingImageButton.setEnabled(true);
        refreshRecordingControlBounds();
    }

    private void showError(String message) {
        AppMessageDialog.showError(this, message);
    }

    /** 打开独立图像识别测试窗口，主窗口仍可继续操作。 */
    private void openImageRecognitionFrame() {
        if (imageRecognitionFrame == null || !imageRecognitionFrame.isDisplayable()) {
            imageRecognitionFrame = new ImageRecognitionFrame(
                    this,
                    appConfig::getDefaultScreenCaptureDirectory
            );
        }

        imageRecognitionFrame.setVisible(true);
        imageRecognitionFrame.toFront();
    }

    private enum ButtonIconType {
        BACK,
        SETTINGS,
        IMAGE_CAPTURE,
        IMAGE_RECOGNITION,
        RECORD,
        WINDOW,
        PLAY,
        PAUSE,
        STOP,
        LOAD,
        SAVE,
        SAVE_AS,
        CLEAR
    }

    /** 使用当前按钮前景色绘制的轻量矢量图标。 */
    private static class LineIcon implements Icon {

        private static final int SIZE = 16;
        private final ButtonIconType type;

        private LineIcon(ButtonIconType type) {
            this.type = type;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.translate(x, y);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Color foreground = component.getForeground();
            if (!component.isEnabled()) {
                foreground = new Color(
                        foreground.getRed(),
                        foreground.getGreen(),
                        foreground.getBlue(),
                        105
                );
            }

            g2.setColor(foreground);
            g2.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

            switch (type) {
                case BACK -> {
                    g2.drawLine(3, 8, 13, 8);
                    g2.drawLine(3, 8, 7, 4);
                    g2.drawLine(3, 8, 7, 12);
                }
                case SETTINGS -> {
                    g2.drawOval(5, 5, 6, 6);
                    for (int i = 0; i < 8; i++) {
                        double angle = i * Math.PI / 4;
                        int innerX = 8 + (int) Math.round(Math.cos(angle) * 5);
                        int innerY = 8 + (int) Math.round(Math.sin(angle) * 5);
                        int outerX = 8 + (int) Math.round(Math.cos(angle) * 7);
                        int outerY = 8 + (int) Math.round(Math.sin(angle) * 7);
                        g2.drawLine(innerX, innerY, outerX, outerY);
                    }
                    g2.fillOval(7, 7, 2, 2);
                }
                case IMAGE_CAPTURE -> {
                    g2.drawRoundRect(2, 3, 12, 10, 3, 3);
                    g2.drawOval(10, 5, 2, 2);
                    g2.drawLine(4, 11, 7, 8);
                    g2.drawLine(7, 8, 9, 10);
                    g2.drawLine(9, 10, 11, 8);
                    g2.drawLine(11, 8, 13, 11);
                }
                case IMAGE_RECOGNITION -> {
                    g2.drawOval(2, 2, 9, 9);
                    g2.drawLine(10, 10, 14, 14);
                    g2.drawLine(4, 6, 9, 6);
                    g2.drawLine(6, 4, 6, 9);
                }
                case RECORD -> g2.fillOval(4, 4, 8, 8);
                case WINDOW -> {
                    g2.drawRoundRect(2, 3, 12, 10, 3, 3);
                    g2.drawOval(6, 6, 4, 4);
                    g2.drawLine(8, 5, 8, 7);
                    g2.drawLine(8, 9, 8, 11);
                    g2.drawLine(5, 8, 7, 8);
                    g2.drawLine(9, 8, 11, 8);
                }
                case PLAY -> g2.fillPolygon(
                        new int[]{5, 13, 5},
                        new int[]{3, 8, 13},
                        3
                );
                case PAUSE -> {
                    g2.fillRoundRect(4, 3, 3, 10, 2, 2);
                    g2.fillRoundRect(9, 3, 3, 10, 2, 2);
                }
                case STOP -> g2.fillRoundRect(4, 4, 8, 8, 2, 2);
                case LOAD -> {
                    g2.drawRoundRect(2, 5, 12, 9, 3, 3);
                    g2.drawLine(3, 5, 6, 5);
                    g2.drawLine(6, 5, 7, 7);
                    g2.drawLine(8, 7, 8, 12);
                    g2.drawLine(5, 9, 8, 12);
                    g2.drawLine(11, 9, 8, 12);
                }
                case SAVE, SAVE_AS -> {
                    g2.drawRoundRect(2, 2, 12, 12, 3, 3);
                    g2.drawRect(5, 2, 6, 4);
                    g2.drawRoundRect(5, 9, 6, 5, 2, 2);
                    if (type == ButtonIconType.SAVE_AS) {
                        g2.fillOval(10, 9, 6, 6);
                        g2.setColor(CARD_LIGHT_COLOR);
                        g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        g2.drawLine(11, 12, 15, 12);
                        g2.drawLine(13, 10, 13, 14);
                    }
                }
                case CLEAR -> {
                    g2.drawLine(4, 4, 12, 12);
                    g2.drawLine(12, 4, 4, 12);
                }
            }

            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }
    }

    /** 统一绘制按钮的圆角、悬停、按下和禁用状态。 */
    private static class RoundedButtonUI extends DarkButtonUI {

        private final Color background;
        private final Color hoverBackground;

        private RoundedButtonUI(Color background, Color hoverBackground) {
            this.background = background;
            this.hoverBackground = hoverBackground;
        }

        @Override
        public void paint(Graphics graphics, JComponent component) {
            AbstractButton button = (AbstractButton) component;
            ButtonModel model = button.getModel();

            Color fill = background;
            if (!button.isEnabled()) {
                fill = blend(background, CARD_COLOR, 0.48);
            } else if (model.isPressed()) {
                fill = blend(hoverBackground, Color.BLACK, 0.16);
            } else if (model.isRollover()) {
                fill = hoverBackground;
            }

            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int arc = Math.min(14, Math.max(8, component.getHeight() - 2));
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, component.getWidth(), component.getHeight(), arc, arc);

            g2.setColor(new Color(255, 255, 255, button.isEnabled() ? 16 : 8));
            g2.drawRoundRect(0, 0, component.getWidth() - 1, component.getHeight() - 1, arc, arc);
            g2.dispose();

            super.paint(graphics, component);
        }

        private static Color blend(Color first, Color second, double secondRatio) {
            double firstRatio = 1.0 - secondRatio;
            return new Color(
                    (int) Math.round(first.getRed() * firstRatio + second.getRed() * secondRatio),
                    (int) Math.round(first.getGreen() * firstRatio + second.getGreen() * secondRatio),
                    (int) Math.round(first.getBlue() * firstRatio + second.getBlue() * secondRatio)
            );
        }
    }

    private static class RoundedPanel extends JPanel {

        private final Color background;
        private final int radius;

        private RoundedPanel(Color background, int radius) {
            this.background = background;
            this.radius = radius;

            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();

            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            g2.setColor(background);

            g2.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);

            g2.dispose();

            super.paintComponent(g);
        }
    }

    /**
     * 根据播放状态更新界面。
     *
     * @param state 当前播放状态
     */
    private void updatePlaybackState(PlayerState state) {
        if (player.getState() != PlayerState.IDLE) lockTimelineEditing();
        switch (state) {
            case PLAYING -> {
                pausePlayButton.setEnabled(true);
                updatePauseButton(false);
                stopPlayButton.setEnabled(true);
                statusLabel.setText("播放中");
            }

            case PAUSED -> {
                pausePlayButton.setEnabled(true);
                updatePauseButton(true);
                stopPlayButton.setEnabled(true);
                statusLabel.setText("已暂停");
            }

            case STOPPING -> {
                pausePlayButton.setEnabled(false);
                stopPlayButton.setEnabled(false);
                statusLabel.setText("正在停止");
            }

            case IDLE -> {
                pausePlayButton.setEnabled(false);
                updatePauseButton(false);
                stopPlayButton.setEnabled(false);
            }
        }
    }

    /**
     * 创建目标窗口选择页面。
     *
     * @return 目标窗口选择页面
     */
    private JPanel createWindowSelectorPage() {
        windowSelectorPanel.setOnBack(this::showHomePage);
        return windowSelectorPanel;
    }

    /**
     * 显示目标窗口选择页面。
     */
    private void showWindowSelectorPage() {
        windowSelectorPanel.updateWindowInfo(null);
        pageLayout.show(pagePanel, WINDOW_SELECTOR_PAGE);
        refreshRecordingControlBounds();
        startWindowSelection();
    }

    /**
     * 开始选择目标窗口。
     */
    private void startWindowSelection() {
        try {
            windowSelector.start();
        } catch (NativeHookException e) {
            showError("启动窗口选择失败：" + e.getMessage());
        }
    }

    /**
     * 停止窗口选择。
     */
    private void stopWindowSelection() {
        try {
            windowSelector.stop();
        } catch (NativeHookException e) {
            showError("停止窗口选择失败：" + e.getMessage());
        }

        showHomePage();
    }

    /**
     * 确认当前选择的目标窗口。
     */
    private void confirmWindowSelection() {
        WindowInfo windowInfo = windowSelectorPanel.getSelectedWindow();
        if (windowInfo == null) {
            showError("请先选择目标窗口");
            return;
        }

        targetWindow = TargetWindow.create()
                .setProcessName(windowInfo.getProcessName())
                .setWindowTitle(windowInfo.getWindowTitle())
                .setWindowClass(windowInfo.getWindowClass());

        updateTargetWindowLabel();

        stopWindowSelection();
    }

    /**
     * 创建目标窗口信息区域。
     *
     * @return 目标窗口信息区域
     */
    private JPanel createTargetWindowInfo() {
        JPanel panel = new RoundedPanel(TARGET_CARD_COLOR, 10);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(10, 12, 10, 12));

        JPanel titlePanel = new JPanel(new BorderLayout());
        titlePanel.setOpaque(false);
        titlePanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel title = new JLabel("目标窗口");
        title.setFont(FONT_SMALL);
        title.setForeground(TEXT_MUTED);

        styleSecondaryButton(clearWindowButton);
        setButtonIcon(clearWindowButton, ButtonIconType.CLEAR);
        Dimension clearButtonPreferredSize = clearWindowButton.getPreferredSize();
        Dimension clearButtonSize = new Dimension(
                Math.max(88, clearButtonPreferredSize.width),
                Math.max(32, clearButtonPreferredSize.height)
        );
        clearWindowButton.setPreferredSize(clearButtonSize);
        clearWindowButton.setMinimumSize(clearButtonSize);
        clearWindowButton.setMaximumSize(clearButtonSize);
        clearWindowButton.setEnabled(targetWindow != null);

        titlePanel.add(title, BorderLayout.WEST);
        titlePanel.add(clearWindowButton, BorderLayout.EAST);

        targetWindowLabel.setFont(FONT_NORMAL);
        targetWindowLabel.setForeground(TEXT_SECONDARY);
        targetWindowLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        panel.add(titlePanel);
        panel.add(Box.createVerticalStrut(3));
        panel.add(targetWindowLabel);

        return panel;
    }

    /**
     * 更新目标窗口显示。
     */
    private void updateTargetWindowLabel() {
        if (targetWindow == null) {
            targetWindowLabel.setText("未指定");
            return;
        }

        targetWindowLabel.setText(
                targetWindow.getProcessName() + " · " + targetWindow.getWindowTitle()
        );
        clearWindowButton.setEnabled(true);
    }


    /**
     * 从 MR 文件恢复宏数据。
     *
     * @param file MR 文件
     * @throws Exception 加载失败
     */
    private void loadMacroFile(File file) throws Exception {
        if (!isIdle()) {
            return;
        }

        // 先读取文件，成功后再更新当前宏数据。
        MacroFile macroFile = storage.load(file.getAbsolutePath());
        List<MacroAction> loadedActions =
                new ArrayList<>(macroFile.getActions());

        macroFlow = macroFile.getFlow() == null
                ? MacroFlowFactory.fromActions(loadedActions)
                : macroFile.getFlow();
        actions = MacroFlowFactory.extractActions(macroFlow);
        targetWindow = macroFile.getTargetWindow();
        currentFile = file;

        appConfig.setLastMacroFile(file);
        fileLabel.setText(file.getName());

        updateTargetWindowLabel();
        actionTimelinePanel.setFlow(macroFlow);
        refreshActionState();
    }

    /**
     * 清除当前目标窗口。
     */
    private void clearTargetWindow() {
        targetWindow = null;
        updateTargetWindowLabel();
    }

    /** 打开已有流程节点编辑器。 */
    private void openActionEditor(MacroNode node) {
        if (!actionTimelinePanel.isEditingAllowed()) return;
        ActionEditorDialog dialog = ActionEditorDialog.edit(
                this,
                node,
                getMacroBaseDirectory(),
                actionTimelinePanel.guardEditCallback(replacement -> {
                    if (actionTimelinePanel.replaceAction(node, replacement)) {
                        syncActionsFromTimeline();
                    }
                }),
                actionTimelinePanel.guardEditCallback(preserveDelay -> {
                    if (actionTimelinePanel.deleteAction(node, preserveDelay)) {
                        syncActionsFromTimeline();
                    }
                })
        );
        showTimelineEditor(dialog);
    }

    /** 打开新节点编辑器，按设置中的默认方向在所选节点附近插入。 */
    private void openActionCreator(MacroNode anchorNode) {
        if (!actionTimelinePanel.isEditingAllowed()) return;
        ActionEditorDialog dialog = ActionEditorDialog.create(
                this,
                getMacroBaseDirectory(),
                actionTimelinePanel.guardEditCallback(newNode -> {
                    if (actionTimelinePanel.insertAction(
                            anchorNode, newNode, appConfig.getTimelineInsertPosition())) {
                        syncActionsFromTimeline();
                    }
                })
        );
        showTimelineEditor(dialog);
    }

    /** 在线程启动前封住写入窗口，并使旧弹窗、行内编辑及确认回调失效。 */
    private void lockTimelineEditing() {
        actionTimelinePanel.setEditingEnabled(false);
        if (timelineEditorDialog != null) {
            timelineEditorDialog.dispose();
            timelineEditorDialog = null;
        }
    }

    private void showTimelineEditor(ActionEditorDialog dialog) {
        if (!actionTimelinePanel.isEditingAllowed()) {
            dialog.dispose();
            return;
        }
        timelineEditorDialog = dialog;
        try {
            dialog.setVisible(true);
        } finally {
            if (timelineEditorDialog == dialog) timelineEditorDialog = null;
            dialog.dispose();
        }
    }

    /** 将时间线流程及其兼容动作视图同步回主窗口。 */
    private void syncActionsFromTimeline() {
        macroFlow = actionTimelinePanel.getFlow();
        actions = new ArrayList<>(actionTimelinePanel.getActions());
        refreshActionState();
    }



    /**
     * 刷新节点数量及依赖当前流程的按钮状态。
     */
    private void refreshActionState() {
        updateActionInfo();

        boolean hasActions = hasTimelineNodes();
        boolean canModifyActions = isIdle();

        playButton.setEnabled(hasActions && canModifyActions);
        saveButton.setEnabled(hasActions && canModifyActions);
        refreshSaveAsState();
    }

    /** 所有另存为入口使用实际录制状态，停止及启动失败时由既有状态刷新统一恢复。 */
    private void refreshSaveAsState() {
        // 仅增加录制期间的禁用；空宏和播放状态仍由另存为入口原有的检查决定是否执行。
        saveAsButton.setEnabled(!recorder.getRecordingStatus().recording());
    }

    private interface NativeControlCoordinates extends StdCallLibrary {
        NativeControlCoordinates INSTANCE = Native.load("user32", NativeControlCoordinates.class, W32APIOptions.DEFAULT_OPTIONS);
        boolean ClientToScreen(WinDef.HWND window, WinDef.POINT point);
        int GetDpiForWindow(WinDef.HWND window);
        boolean IsIconic(WinDef.HWND window);
    }

    /** 判断当前时间线是否包含可播放节点。 */
    private boolean hasTimelineNodes() {
        return !MacroFlowFactory.timelineNodes(macroFlow).isEmpty();
    }

    /** 返回解析相对模板路径时使用的 MR 所在目录。 */
    private File getMacroBaseDirectory() {
        if (currentFile != null) {
            return currentFile.getAbsoluteFile().getParentFile();
        }
        return new File("").getAbsoluteFile();
    }

    /**
     * 判断当前是否允许修改宏动作或执行文件操作。
     *
     * @return 当前是否处于空闲状态
     */
    private boolean isIdle() {
        return !recording && player.getState() == PlayerState.IDLE;
    }

}
