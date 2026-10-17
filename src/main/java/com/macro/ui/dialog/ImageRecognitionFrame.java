package com.macro.ui.dialog;

import com.macro.constant.UiConstants;
import com.macro.recognition.ScreenRecognitionService;
import com.macro.recognition.ImageRecognitionDiagnostics;
import com.macro.ui.component.RoundedPanel;
import com.macro.ui.theme.ThemeManager;
import com.macro.ui.theme.ThemePalette;
import com.macro.ui.util.ComponentStyleUtil;
import com.macro.ui.util.NumericInputUtil;
import com.macro.ui.filedialog.FileDialogException;
import com.macro.ui.filedialog.FileDialogOptions;
import com.macro.ui.filedialog.FileDialogService;
import com.macro.ui.filedialog.FileDialogServiceFactory;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/** 用于加载模板并验证屏幕图像识别效果的独立窗口。 */
public final class ImageRecognitionFrame extends JFrame {

    private static final Dimension DEFAULT_SIZE = new Dimension(860, 600);
    private static final Dimension MINIMUM_SIZE = new Dimension(760, 540);
    private static final Dimension PREVIEW_SIZE = new Dimension(330, 210);
    private static final int CAPTURE_SETTLE_DELAY_MILLIS = 180;

    private static final Font FONT_NORMAL =
            new Font(UiConstants.DEFAULT_FONT_FAMILY, Font.PLAIN, 13);
    private static final Font FONT_SMALL =
            new Font(UiConstants.DEFAULT_FONT_FAMILY, Font.PLAIN, 12);
    private static final Font FONT_BOLD =
            new Font(UiConstants.DEFAULT_FONT_FAMILY, Font.BOLD, 13);
    private static final Font FONT_SECTION =
            new Font(UiConstants.DEFAULT_FONT_FAMILY, Font.BOLD, 16);
    private static final Font FONT_TITLE =
            new Font(UiConstants.DEFAULT_FONT_FAMILY, Font.BOLD, 22);

    private final ThemePalette theme = ThemeManager.getPalette();
    private final Window applicationWindow;
    private final FileDialogService fileDialogs = FileDialogServiceFactory.create();
    private final Supplier<File> defaultDirectorySupplier;
    private final ScreenRecognitionService recognitionService =
            new ScreenRecognitionService();

    private final RecognitionPreviewPanel templatePreview =
            new RecognitionPreviewPanel();
    private final RecognitionPreviewPanel resultPreview =
            new RecognitionPreviewPanel();

    private final JLabel templateNameValue = createValueLabel();
    private final JLabel templateSizeValue = createValueLabel();
    private final JLabel resultStatusValue = createValueLabel();
    private final JLabel similarityValue = createValueLabel();
    private final JLabel positionValue = createValueLabel();
    private final JLabel centerValue = createValueLabel();
    private final JLabel resultSizeValue = createValueLabel();
    private final JLabel displayValue = createValueLabel();
    private final JLabel engineValue = createValueLabel();
    private final JLabel peakMemoryValue = createValueLabel();
    private final JLabel memoryValue = createValueLabel();

    private final JButton loadButton = new JButton("加载图像");
    private final JButton clearButton = new JButton("清除");
    private final JButton recognizeJavaButton =
            new JButton("识别当前屏幕（Java）");
    private final JButton recognizeOpenCvButton =
            new JButton("识别当前屏幕（OpenCV）");
    private final JButton copyPositionButton = new JButton("复制位置");
    private final JButton previousResultButton = new JButton("‹");
    private final JButton nextResultButton = new JButton("›");
    private final JLabel resultIndexValue = createValueLabel();
    private final JCheckBox showAllResultsCheckBox =
            new JCheckBox("显示全部", true);
    private final JSpinner thresholdSpinner = new JSpinner(
            new SpinnerNumberModel(90, 50, 100, 1)
    );
    private final JCheckBox hideWindowsCheckBox =
            new JCheckBox("识别时隐藏应用窗口", true);

    private BufferedImage templateImage;
    private File lastDirectory;
    private List<ScreenRecognitionService.RecognitionResult> displayedResults =
            List.of();
    private int selectedResultIndex = -1;
    private long latestRecognitionElapsedMillis;
    private boolean latestResultsMatched;
    private SwingWorker<ScreenRecognitionService.RecognitionReport, Void>
            recognitionWorker;
    private Timer captureTimer;
    private boolean recognizing;
    private boolean applicationWindowsHidden;
    private boolean recognitionWindowWasVisible;
    private boolean ownerWasVisible;
    private int ownerFrameState;

    public ImageRecognitionFrame(
            Window applicationWindow,
            Supplier<File> defaultDirectorySupplier
    ) {
        super("图像识别测试");
        this.applicationWindow = applicationWindow;
        this.defaultDirectorySupplier = defaultDirectorySupplier;

        initializeWindow(applicationWindow);
        initializeLayout();
        initializeEvents();
        clearResult();
        updateActionState();
    }

    /** 初始化独立窗口自身属性。 */
    private void initializeWindow(Window applicationWindow) {
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setSize(DEFAULT_SIZE);
        setMinimumSize(MINIMUM_SIZE);
        setLocationRelativeTo(applicationWindow);
        if (applicationWindow instanceof Frame frame) {
            setIconImage(frame.getIconImage());
        }
    }

    /** 创建窗口布局。 */
    private void initializeLayout() {
        JPanel root = new JPanel(new BorderLayout(0, 14));
        root.setBackground(theme.background());
        root.setBorder(new EmptyBorder(18, 20, 18, 20));
        root.add(createHeader(), BorderLayout.NORTH);

        JPanel cards = new JPanel(new GridLayout(1, 2, 14, 0));
        cards.setOpaque(false);
        cards.add(createTemplateCard());
        cards.add(createResultCard());
        root.add(cards, BorderLayout.CENTER);
        root.add(createControlBar(), BorderLayout.SOUTH);
        setContentPane(root);
    }

    /** 创建标题和用途说明。 */
    private JPanel createHeader() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        JLabel title = createLabel("图像识别测试", FONT_TITLE, theme.textPrimary());
        JLabel subtitle = createLabel(
                "加载模板图像，按原始尺寸在所有显示器中查找并返回最佳位置",
                FONT_SMALL,
                theme.textSecondary()
        );
        panel.add(title);
        panel.add(Box.createVerticalStrut(4));
        panel.add(subtitle);
        return panel;
    }

    /** 创建模板图像区域。 */
    private JPanel createTemplateCard() {
        RoundedPanel card = createCard();
        card.setLayout(new BorderLayout(0, 10));
        card.add(createLabel("待识别图像", FONT_SECTION, theme.textPrimary()),
                BorderLayout.NORTH);
        card.add(templatePreview, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(0, 10));
        footer.setOpaque(false);
        JPanel information = createInformationPanel();
        addInformationRow(information, 0, "文件名", templateNameValue);
        addInformationRow(information, 1, "图像尺寸", templateSizeValue);
        footer.add(information, BorderLayout.CENTER);

        JPanel actions = new JPanel(new GridLayout(1, 2, 8, 0));
        actions.setOpaque(false);
        loadButton.setFont(FONT_BOLD);
        clearButton.setFont(FONT_BOLD);
        ComponentStyleUtil.stylePrimaryButton(loadButton);
        ComponentStyleUtil.styleSecondaryButton(clearButton);
        actions.add(loadButton);
        actions.add(clearButton);
        footer.add(actions, BorderLayout.SOUTH);
        card.add(footer, BorderLayout.SOUTH);
        return card;
    }

    /** 创建识别结果区域。 */
    private JPanel createResultCard() {
        RoundedPanel card = createCard();
        card.setLayout(new BorderLayout(0, 10));

        JPanel titlePanel = new JPanel(new BorderLayout());
        titlePanel.setOpaque(false);
        titlePanel.add(
                createLabel("识别结果", FONT_SECTION, theme.textPrimary()),
                BorderLayout.WEST
        );
        resultStatusValue.setHorizontalAlignment(SwingConstants.RIGHT);
        titlePanel.add(resultStatusValue, BorderLayout.EAST);
        card.add(titlePanel, BorderLayout.NORTH);
        card.add(resultPreview, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(0, 8));
        footer.setOpaque(false);
        footer.add(createResultInformationPanel(), BorderLayout.CENTER);

        JPanel resultActions = new JPanel(new BorderLayout(8, 0));
        resultActions.setOpaque(false);

        JPanel navigation = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        navigation.setOpaque(false);
        previousResultButton.setFont(FONT_SECTION);
        nextResultButton.setFont(FONT_SECTION);
        previousResultButton.setToolTipText("上一个结果");
        nextResultButton.setToolTipText("下一个结果");
        ComponentStyleUtil.styleSecondaryButton(previousResultButton);
        ComponentStyleUtil.styleSecondaryButton(nextResultButton);
        previousResultButton.setBorder(new EmptyBorder(5, 10, 5, 10));
        nextResultButton.setBorder(new EmptyBorder(5, 10, 5, 10));
        resultIndexValue.setText("0 / 0");
        resultIndexValue.setHorizontalAlignment(SwingConstants.CENTER);
        resultIndexValue.setPreferredSize(new Dimension(64, 30));
        navigation.add(previousResultButton);
        navigation.add(resultIndexValue);
        navigation.add(nextResultButton);
        resultActions.add(navigation, BorderLayout.WEST);

        JPanel displayActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        displayActions.setOpaque(false);
        showAllResultsCheckBox.setFont(FONT_SMALL);
        ComponentStyleUtil.styleCheckBox(showAllResultsCheckBox);
        copyPositionButton.setFont(FONT_SMALL);
        ComponentStyleUtil.styleSecondaryButton(copyPositionButton);
        copyPositionButton.setBorder(new EmptyBorder(7, 10, 7, 10));
        displayActions.add(showAllResultsCheckBox);
        displayActions.add(copyPositionButton);
        resultActions.add(displayActions, BorderLayout.EAST);

        footer.add(resultActions, BorderLayout.SOUTH);
        card.add(footer, BorderLayout.SOUTH);
        return card;
    }

    /** 创建阈值与识别操作区。 */
    private JPanel createControlBar() {
        RoundedPanel panel = new RoundedPanel(
                theme.card(),
                theme.border(),
                14
        );
        panel.setLayout(new GridBagLayout());
        panel.setBorder(new EmptyBorder(10, 12, 10, 12));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridy = 0;
        constraints.anchor = GridBagConstraints.WEST;

        JLabel thresholdLabel = createLabel(
                "匹配阈值",
                FONT_NORMAL,
                theme.textSecondary()
        );
        constraints.gridx = 0;
        panel.add(thresholdLabel, constraints);

        thresholdSpinner.setFont(FONT_NORMAL);
        thresholdSpinner.setPreferredSize(new Dimension(76, 34));
        ComponentStyleUtil.styleSpinner(thresholdSpinner);
        NumericInputUtil.installSpinner(thresholdSpinner, "测试匹配阈值（%）");
        constraints.gridx = 1;
        constraints.insets = new Insets(0, 8, 0, 4);
        panel.add(thresholdSpinner, constraints);

        JLabel percentLabel = createLabel("%", FONT_NORMAL, theme.textSecondary());
        constraints.gridx = 2;
        constraints.insets = new Insets(0, 0, 0, 16);
        panel.add(percentLabel, constraints);

        hideWindowsCheckBox.setFont(FONT_NORMAL);
        ComponentStyleUtil.styleCheckBox(hideWindowsCheckBox);
        constraints.gridx = 3;
        constraints.weightx = 1.0;
        constraints.insets = new Insets(0, 0, 0, 12);
        panel.add(hideWindowsCheckBox, constraints);

        JPanel recognitionButtons = new JPanel(new GridLayout(1, 2, 8, 0));
        recognitionButtons.setOpaque(false);
        recognizeJavaButton.setFont(FONT_BOLD);
        recognizeOpenCvButton.setFont(FONT_BOLD);
        ComponentStyleUtil.stylePrimaryButton(recognizeJavaButton);
        ComponentStyleUtil.styleSecondaryButton(recognizeOpenCvButton);
        recognitionButtons.add(recognizeJavaButton);
        recognitionButtons.add(recognizeOpenCvButton);
        constraints.gridx = 4;
        constraints.weightx = 0.0;
        constraints.insets = new Insets(0, 0, 0, 0);
        panel.add(recognitionButtons, constraints);
        return panel;
    }

    /** 注册按钮、快捷键和窗口关闭处理。 */
    private void initializeEvents() {
        loadButton.addActionListener(event -> loadTemplateImage());
        clearButton.addActionListener(event -> clearTemplateImage());
        recognizeJavaButton.addActionListener(event -> startRecognition(
                ScreenRecognitionService.RecognitionEngine.JAVA
        ));
        recognizeOpenCvButton.addActionListener(event -> startRecognition(
                ScreenRecognitionService.RecognitionEngine.OPEN_CV
        ));
        copyPositionButton.addActionListener(event -> copyRecognitionPosition());
        previousResultButton.addActionListener(event -> selectAdjacentResult(-1));
        nextResultButton.addActionListener(event -> selectAdjacentResult(1));
        showAllResultsCheckBox.addActionListener(event -> refreshResultPreview());

        getRootPane().registerKeyboardAction(
                event -> dispose(),
                KeyStroke.getKeyStroke("ESCAPE"),
                JComponent.WHEN_IN_FOCUSED_WINDOW
        );
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                cancelPendingRecognition();
            }
        });
    }

    /** 选择并载入模板图片。 */
    private void loadTemplateImage() {
        File selectedFile;
        try {
            selectedFile = fileDialogs.showDialog(this, createImageFileOptions()).orElse(null);
        } catch (FileDialogException exception) {
            AppMessageDialog.showError(this, exception.getMessage(), exception);
            return;
        }
        if (selectedFile == null) return;
        try {
            BufferedImage image = ImageIO.read(selectedFile);
            if (image == null) {
                AppMessageDialog.showWarning(
                        this,
                        "无法识别该图片，文件可能已损坏或格式暂不支持。"
                );
                return;
            }
            templateImage = image;
            lastDirectory = selectedFile.getParentFile();
            templatePreview.setImage(image, null, theme.accent());
            templateNameValue.setText(selectedFile.getName());
            templateNameValue.setToolTipText(selectedFile.getAbsolutePath());
            templateSizeValue.setText(image.getWidth() + " × " + image.getHeight());
            clearResult();
            updateActionState();
        } catch (IOException | RuntimeException exception) {
            AppMessageDialog.showError(this, "加载待识别图像失败", exception);
        }
    }

    /** 保留最近目录以及测试窗口支持的全部图片格式。 */
    private FileDialogOptions createImageFileOptions() {
        File directory = lastDirectory;
        if (directory == null && defaultDirectorySupplier != null) {
            directory = defaultDirectorySupplier.get();
        }
        return FileDialogOptions.builder(FileDialogOptions.Mode.OPEN_FILE)
                .title("加载待识别图像").initialDirectory(directory)
                .fileType("图片文件（PNG、JPG、BMP、GIF、WebP）", "png", "jpg", "jpeg", "bmp", "gif", "webp")
                .build();
    }

    /** 清除模板与当前结果。 */
    private void clearTemplateImage() {
        if (recognizing) {
            return;
        }
        templateImage = null;
        templatePreview.setImage(null, null, theme.accent());
        templateNameValue.setText("—");
        templateNameValue.setToolTipText(null);
        templateSizeValue.setText("—");
        clearResult();
        updateActionState();
    }

    /** 启动一次当前屏幕识别。 */
    private void startRecognition(
            ScreenRecognitionService.RecognitionEngine engine
    ) {
        if (templateImage == null || recognizing) {
            if (templateImage == null) {
                AppMessageDialog.showInfo(this, "请先加载待识别图像。");
            }
            return;
        }

        try {
            NumericInputUtil.commitSpinnerInt(thresholdSpinner);
        } catch (IllegalArgumentException exception) {
            AppMessageDialog.showWarning(this, exception.getMessage());
            return;
        }

        recognizing = true;
        displayedResults = List.of();
        selectedResultIndex = -1;
        latestRecognitionElapsedMillis = 0L;
        latestResultsMatched = false;
        resultPreview.showEmptyState("正在识别…");
        setResultStatus("正在采集屏幕…", theme.accent());
        resetResultValues();
        updateActionState();

        if (hideWindowsCheckBox.isSelected()) {
            hideApplicationWindows();
        }
        ImageRecognitionDiagnostics.log("TEST", "WINDOW_PREPARATION",
                "TemplatePath: " + templateNameValue.getToolTipText()
                        + "\nEngine: " + engine.name()
                        + "\nHideApplicationWindows: " + hideWindowsCheckBox.isSelected()
                        + "\nApplicationWindowsHidden: " + applicationWindowsHidden
                        + "\nCaptureSettleDelayMillis: " + CAPTURE_SETTLE_DELAY_MILLIS);
        Toolkit.getDefaultToolkit().sync();
        captureTimer = new Timer(
                CAPTURE_SETTLE_DELAY_MILLIS,
                event -> runRecognitionWorker(engine)
        );
        captureTimer.setRepeats(false);
        captureTimer.start();
    }

    /** 在后台完成屏幕采集和模板匹配。 */
    private void runRecognitionWorker(
            ScreenRecognitionService.RecognitionEngine engine
    ) {
        captureTimer = null;
        BufferedImage template = templateImage;
        double threshold = ((Number) thresholdSpinner.getValue()).doubleValue() / 100.0;
        String requestId = ImageRecognitionDiagnostics.nextRequestId("TEST");
        String input = "Request: " + requestId
                + "\nTemplatePath: " + templateNameValue.getToolTipText()
                + "\nThreshold: " + ImageRecognitionDiagnostics.similarity(threshold)
                + "\nEngine: " + engine.name()
                + "\nTemplatePixels: " + template.getWidth() + "x" + template.getHeight()
                + "\nApplicationWindowsHidden: " + applicationWindowsHidden;

        recognitionWorker = new SwingWorker<>() {
            @Override
            protected ScreenRecognitionService.RecognitionReport doInBackground()
                    throws Exception {
                ImageRecognitionDiagnostics.log("TEST", "MATCH_START", input);
                String foreground = ImageRecognitionDiagnostics.foregroundWindow();
                List<ScreenRecognitionService.ScreenSnapshot> snapshots =
                        recognitionService.captureScreens();
                String capture = "Request: " + requestId
                        + "\nCapturedAt: " + java.time.Instant.now()
                        + "\nForegroundBeforeCapture: " + foreground
                        + "\n" + ImageRecognitionDiagnostics.describeScreens(snapshots);
                ImageRecognitionDiagnostics.log("TEST", "CAPTURE", capture);
                SwingUtilities.invokeLater(() -> {
                    restoreApplicationWindows();
                    if (isDisplayable()) {
                        setResultStatus("正在匹配…", theme.accent());
                    }
                });
                ScreenRecognitionService.RecognitionReport report = recognitionService.recognize(
                        snapshots,
                        template,
                        threshold,
                        engine
                );
                String outcome = "Request: " + requestId
                        + "\nFound: " + !report.matches().isEmpty()
                        + "\nBestCandidateSimilarity: " + (report.bestCandidate() == null
                                ? "<无候选>" : ImageRecognitionDiagnostics.similarity(
                                        report.bestCandidate().similarity()))
                        + "\nMatchCount: " + report.matches().size()
                        + "\nMatchBounds: " + (report.matches().isEmpty()
                                ? null : report.matches().get(0).logicalBounds());
                ImageRecognitionDiagnostics.log("TEST", "MATCH_RESULT", outcome);
                ImageRecognitionDiagnostics.saveCapture("TEST", requestId, snapshots, template,
                        input + "\n" + capture + "\n" + outcome);
                return report;
            }

            @Override
            protected void done() {
                restoreApplicationWindows();
                recognizing = false;
                recognitionWorker = null;
                if (!isDisplayable()) {
                    return;
                }
                try {
                    showRecognitionReport(get());
                } catch (CancellationException exception) {
                    clearResult();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    showRecognitionFailure(exception);
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause() == null
                            ? exception
                            : exception.getCause();
                    showRecognitionFailure(cause);
                } finally {
                    updateActionState();
                }
            }
        };
        recognitionWorker.execute();
    }

    /** 展示全部命中，并默认选中相似度最高的结果。 */
    private void showRecognitionReport(
            ScreenRecognitionService.RecognitionReport report
    ) {
        latestRecognitionElapsedMillis = report.elapsedMillis();
        showRecognitionMetrics(report);
        if (!report.matches().isEmpty()) {
            displayedResults = report.matches();
            latestResultsMatched = true;
            String countText = report.truncated()
                    ? displayedResults.size() + "+ 个结果"
                    : displayedResults.size() + " 个结果";
            setResultStatus("识别到 " + countText, theme.success());
            selectResult(0);
            return;
        }

        ScreenRecognitionService.RecognitionResult bestCandidate =
                report.bestCandidate();
        if (bestCandidate == null) {
            displayedResults = List.of();
            selectedResultIndex = -1;
            setResultStatus("没有可比较区域", theme.danger());
            resultPreview.showEmptyState("当前屏幕没有可比较区域");
            resetMatchValues();
            displayValue.setText("—   ·   " + report.elapsedMillis() + " ms");
            resultIndexValue.setText("0 / 0");
            return;
        }

        displayedResults = List.of();
        selectedResultIndex = -1;
        latestResultsMatched = false;
        int thresholdPercent = ((Number) thresholdSpinner.getValue()).intValue();
        setResultStatus("未找到匹配结果", theme.danger());
        resultPreview.showEmptyState(
                "未发现达到 " + thresholdPercent + "% 阈值的结果"
        );
        resetMatchValues();
        similarityValue.setText(String.format(
                Locale.ROOT,
                "最佳 %.2f%%",
                bestCandidate.similarity() * 100.0
        ));
        displayValue.setText(
                bestCandidate.displayName()
                        + "   ·   "
                        + report.elapsedMillis()
                        + " ms"
        );
        resultIndexValue.setText("0 / 0");
        updateActionState();
    }

    /** 展示本次匹配引擎、初始化时间和近似内存开销。 */
    private void showRecognitionMetrics(
            ScreenRecognitionService.RecognitionReport report
    ) {
        String engineText = report.engine().displayName();
        if (report.initializationMillis() > 0L) {
            engineText += "   ·   首次初始化 "
                    + report.initializationMillis()
                    + " ms";
        }
        engineValue.setText(engineText);

        long workingSetIncrease = report.peakWorkingSetIncreaseBytes();
        peakMemoryValue.setText(
                workingSetIncrease < 0L
                        ? "不可用"
                        : "Δ " + formatBytes(workingSetIncrease)
        );
        peakMemoryValue.setToolTipText(
                "以识别开始时的进程工作集为基线，每 5 ms 采样一次并记录最高增量"
        );
        memoryValue.setText(
                "堆 Δ " + formatBytes(report.heapDeltaBytes())
                        + " · 估算 "
                        + formatBytes(report.estimatedWorkingMemoryBytes())
        );
        memoryValue.setToolTipText(
                "堆 Δ 是匹配前后的 Java 已用堆差值；估算值包含主要 Java 数组与 OpenCV Mat"
        );
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double kibibytes = bytes / 1024.0;
        if (kibibytes < 1024.0) {
            return String.format(Locale.ROOT, "%.1f KB", kibibytes);
        }
        return String.format(Locale.ROOT, "%.1f MB", kibibytes / 1024.0);
    }

    /** 切换当前重点结果，并同步详情与预览框。 */
    private void selectResult(int index) {
        if (displayedResults.isEmpty()) {
            selectedResultIndex = -1;
            resultIndexValue.setText("0 / 0");
            updateActionState();
            return;
        }

        selectedResultIndex = Math.floorMod(index, displayedResults.size());
        ScreenRecognitionService.RecognitionResult result = getSelectedResult();
        Color resultColor = latestResultsMatched
                ? theme.success()
                : theme.danger();
        similarityValue.setText(String.format(
                Locale.ROOT,
                "%.2f%%",
                result.similarity() * 100.0
        ));
        Rectangle bounds = result.logicalBounds();
        positionValue.setText("X: " + bounds.x + "   Y: " + bounds.y);
        centerValue.setText(
                "X: " + (bounds.x + bounds.width / 2)
                        + "   Y: " + (bounds.y + bounds.height / 2)
        );
        resultSizeValue.setText(bounds.width + " × " + bounds.height);
        displayValue.setText(
                result.displayName()
                        + "   ·   "
                        + latestRecognitionElapsedMillis
                        + " ms"
        );
        Rectangle pixelBounds = result.pixelBounds();
        positionValue.setToolTipText(
                "截图像素位置：X=" + pixelBounds.x
                        + "，Y=" + pixelBounds.y
        );
        resultIndexValue.setText(
                (selectedResultIndex + 1) + " / " + displayedResults.size()
        );
        refreshResultPreview(resultColor);
        updateActionState();
    }

    /** 循环查看上一个或下一个识别位置。 */
    private void selectAdjacentResult(int offset) {
        if (!displayedResults.isEmpty()) {
            selectResult(selectedResultIndex + offset);
        }
    }

    /** 根据“显示全部”设置刷新当前屏幕上的结果框。 */
    private void refreshResultPreview() {
        refreshResultPreview(
                latestResultsMatched ? theme.success() : theme.danger()
        );
    }

    private void refreshResultPreview(Color resultColor) {
        ScreenRecognitionService.RecognitionResult selected = getSelectedResult();
        if (selected == null) {
            resultPreview.setImage(null, null, theme.accent());
            return;
        }

        List<Rectangle> visibleBounds = showAllResultsCheckBox.isSelected()
                ? displayedResults.stream()
                .filter(result -> result.screenImage() == selected.screenImage())
                .map(ScreenRecognitionService.RecognitionResult::pixelBounds)
                .toList()
                : List.of(selected.pixelBounds());
        resultPreview.setHighlights(
                selected.screenImage(),
                visibleBounds,
                selected.pixelBounds(),
                resultColor,
                latestResultsMatched ? theme.accent() : theme.danger()
        );
    }

    private ScreenRecognitionService.RecognitionResult getSelectedResult() {
        if (selectedResultIndex < 0
                || selectedResultIndex >= displayedResults.size()) {
            return null;
        }
        return displayedResults.get(selectedResultIndex);
    }

    /** 将识别位置复制为可直接查看的文本。 */
    private void copyRecognitionPosition() {
        ScreenRecognitionService.RecognitionResult selected = getSelectedResult();
        if (selected == null) {
            return;
        }
        Rectangle bounds = selected.logicalBounds();
        String text = "X=" + bounds.x
                + ", Y=" + bounds.y
                + ", Width=" + bounds.width
                + ", Height=" + bounds.height
                + ", Similarity="
                + String.format(Locale.ROOT, "%.2f%%", selected.similarity() * 100.0);
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                    new StringSelection(text),
                    null
            );
            copyPositionButton.setText("已复制");
            Timer resetTimer = new Timer(
                    1200,
                    event -> copyPositionButton.setText("复制位置")
            );
            resetTimer.setRepeats(false);
            resetTimer.start();
        } catch (IllegalStateException exception) {
            AppMessageDialog.showError(this, "复制识别位置失败", exception);
        }
    }

    /** 识别前暂时隐藏识别窗口和主窗口，避免应用自身进入截图。 */
    private void hideApplicationWindows() {
        if (applicationWindowsHidden) {
            return;
        }
        recognitionWindowWasVisible = isVisible();
        ownerWasVisible = applicationWindow != null && applicationWindow.isVisible();
        if (applicationWindow instanceof Frame frame) {
            ownerFrameState = frame.getExtendedState();
        }

        setVisible(false);
        if (ownerWasVisible) {
            applicationWindow.setVisible(false);
        }
        applicationWindowsHidden = true;
    }

    /** 恢复识别前的应用窗口可见状态。 */
    private void restoreApplicationWindows() {
        if (!applicationWindowsHidden) {
            return;
        }
        applicationWindowsHidden = false;
        if (applicationWindow instanceof Frame frame) {
            frame.setExtendedState(ownerFrameState);
        }
        if (applicationWindow != null) {
            applicationWindow.setVisible(ownerWasVisible);
        }
        if (recognitionWindowWasVisible && isDisplayable()) {
            setVisible(true);
            toFront();
            requestFocus();
        }
    }

    /** 取消尚未结束的延时或后台识别。 */
    private void cancelPendingRecognition() {
        if (captureTimer != null) {
            captureTimer.stop();
            captureTimer = null;
        }
        if (recognitionWorker != null && !recognitionWorker.isDone()) {
            recognitionWorker.cancel(true);
            recognitionWorker = null;
        }
        recognizing = false;
        restoreApplicationWindows();
    }

    /** 显示识别异常并恢复可操作状态。 */
    private void showRecognitionFailure(Throwable throwable) {
        setResultStatus("识别失败", theme.danger());
        resultPreview.showEmptyState("识别过程出现错误");
        resetResultValues();
        AppMessageDialog.showError(this, "图像识别失败", throwable);
    }

    /** 清除上一次识别结果。 */
    private void clearResult() {
        displayedResults = List.of();
        selectedResultIndex = -1;
        latestRecognitionElapsedMillis = 0L;
        latestResultsMatched = false;
        resultPreview.showEmptyState("暂无识别结果");
        setResultStatus("等待识别", theme.textMuted());
        resetResultValues();
        resultIndexValue.setText("0 / 0");
        positionValue.setToolTipText(null);
    }

    private void resetResultValues() {
        resetMatchValues();
        engineValue.setText("—");
        peakMemoryValue.setText("—");
        peakMemoryValue.setToolTipText(null);
        memoryValue.setText("—");
        memoryValue.setToolTipText(null);
    }

    /** 清空匹配位置详情，同时保留本次引擎与内存统计。 */
    private void resetMatchValues() {
        similarityValue.setText("—");
        positionValue.setText("—");
        positionValue.setToolTipText(null);
        centerValue.setText("—");
        resultSizeValue.setText("—");
        displayValue.setText("—");
    }

    private void setResultStatus(String text, Color color) {
        resultStatusValue.setText(text);
        resultStatusValue.setForeground(color);
    }

    /** 根据当前模板和后台任务状态更新控件。 */
    private void updateActionState() {
        boolean hasTemplate = templateImage != null;
        loadButton.setEnabled(!recognizing);
        clearButton.setEnabled(hasTemplate && !recognizing);
        recognizeJavaButton.setEnabled(hasTemplate && !recognizing);
        recognizeOpenCvButton.setEnabled(hasTemplate && !recognizing);
        thresholdSpinner.setEnabled(!recognizing);
        hideWindowsCheckBox.setEnabled(!recognizing);
        boolean hasResult = getSelectedResult() != null;
        copyPositionButton.setEnabled(!recognizing && hasResult);
        previousResultButton.setEnabled(
                !recognizing && displayedResults.size() > 1
        );
        nextResultButton.setEnabled(
                !recognizing && displayedResults.size() > 1
        );
        showAllResultsCheckBox.setEnabled(
                !recognizing && displayedResults.size() > 1
        );
    }

    private RoundedPanel createCard() {
        RoundedPanel card = new RoundedPanel(theme.card(), theme.border(), 16);
        card.setBorder(new EmptyBorder(14, 14, 14, 14));
        return card;
    }

    private JPanel createInformationPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        return panel;
    }

    /** 创建固定等宽的两列识别统计信息。 */
    private JPanel createResultInformationPanel() {
        JPanel panel = new JPanel(new GridLayout(4, 2, 18, 0));
        panel.setOpaque(false);
        panel.add(createInformationItem("相似度", similarityValue));
        panel.add(createInformationItem("显示器 / 耗时", displayValue));
        panel.add(createInformationItem("识别位置", positionValue));
        panel.add(createInformationItem("识别引擎", engineValue));
        panel.add(createInformationItem("中心坐标", centerValue));
        panel.add(createInformationItem("进程峰值", peakMemoryValue));
        panel.add(createInformationItem("区域尺寸", resultSizeValue));
        panel.add(createInformationItem("算法内存", memoryValue));
        return panel;
    }

    private JPanel createInformationItem(String name, JLabel value) {
        JPanel item = new JPanel(new GridBagLayout());
        item.setOpaque(false);
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridy = 0;
        constraints.anchor = GridBagConstraints.WEST;

        JLabel nameLabel = createLabel(name, FONT_SMALL, theme.textSecondary());
        constraints.gridx = 0;
        constraints.insets = new Insets(2, 0, 2, 8);
        item.add(nameLabel, constraints);

        constraints.gridx = 1;
        constraints.weightx = 1.0;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(2, 0, 2, 0);
        item.add(value, constraints);
        return item;
    }

    private void addInformationRow(
            JPanel panel,
            int row,
            String name,
            JLabel value
    ) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridy = row;
        constraints.insets = new Insets(2, 0, 2, 8);
        constraints.anchor = GridBagConstraints.WEST;

        JLabel nameLabel = createLabel(name, FONT_SMALL, theme.textSecondary());
        constraints.gridx = 0;
        panel.add(nameLabel, constraints);

        constraints.gridx = 1;
        constraints.weightx = 1.0;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(2, 8, 2, 0);
        panel.add(value, constraints);
    }

    private JLabel createLabel(String text, Font font, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(font);
        label.setForeground(color);
        return label;
    }

    private JLabel createValueLabel() {
        JLabel label = new JLabel("—");
        label.setFont(FONT_NORMAL);
        label.setForeground(theme.textPrimary());
        return label;
    }

    /** 按可用区域缩放图像，并显示一个或多个识别框。 */
    private final class RecognitionPreviewPanel extends JPanel {

        private BufferedImage image;
        private String emptyStateText = "暂无图像";
        private List<Rectangle> highlightBounds = List.of();
        private Rectangle selectedBounds;
        private Color highlightColor = theme.accent();
        private Color selectedColor = theme.accent();

        private RecognitionPreviewPanel() {
            setOpaque(true);
            setBackground(theme.targetCard());
            setPreferredSize(PREVIEW_SIZE);
            setMinimumSize(new Dimension(220, 140));
            setBorder(BorderFactory.createLineBorder(theme.border(), 1, true));
        }

        private void setImage(
                BufferedImage image,
                Rectangle highlightBounds,
                Color highlightColor
        ) {
            List<Rectangle> bounds = highlightBounds == null
                    ? List.of()
                    : List.of(new Rectangle(highlightBounds));
            setHighlights(
                    image,
                    bounds,
                    highlightBounds,
                    highlightColor,
                    highlightColor
            );
        }

        /** 显示不带候选截图和识别框的居中状态提示。 */
        private void showEmptyState(String text) {
            image = null;
            highlightBounds = List.of();
            selectedBounds = null;
            emptyStateText = text == null || text.isBlank()
                    ? "暂无图像"
                    : text;
            repaint();
        }

        private void setHighlights(
                BufferedImage image,
                List<Rectangle> highlightBounds,
                Rectangle selectedBounds,
                Color highlightColor,
                Color selectedColor
        ) {
            this.image = image;
            this.highlightBounds = highlightBounds == null
                    ? List.of()
                    : highlightBounds.stream()
                    .map(Rectangle::new)
                    .toList();
            this.selectedBounds = selectedBounds == null
                    ? null
                    : new Rectangle(selectedBounds);
            this.highlightColor = highlightColor == null
                    ? theme.accent()
                    : highlightColor;
            this.selectedColor = selectedColor == null
                    ? theme.accent()
                    : selectedColor;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR
            );
            g2.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
            );

            if (image == null) {
                drawEmptyState(g2);
                g2.dispose();
                return;
            }

            int padding = 10;
            int availableWidth = Math.max(1, getWidth() - padding * 2);
            int availableHeight = Math.max(1, getHeight() - padding * 2);
            double scale = Math.min(
                    availableWidth / (double) image.getWidth(),
                    availableHeight / (double) image.getHeight()
            );
            int drawWidth = Math.max(1, (int) Math.round(image.getWidth() * scale));
            int drawHeight = Math.max(1, (int) Math.round(image.getHeight() * scale));
            int drawX = (getWidth() - drawWidth) / 2;
            int drawY = (getHeight() - drawHeight) / 2;
            g2.drawImage(image, drawX, drawY, drawWidth, drawHeight, null);

            for (Rectangle bounds : highlightBounds) {
                if (!bounds.equals(selectedBounds)) {
                    drawHighlight(
                            g2,
                            bounds,
                            drawX,
                            drawY,
                            scale,
                            highlightColor,
                            false
                    );
                }
            }
            if (selectedBounds != null) {
                drawHighlight(
                        g2,
                        selectedBounds,
                        drawX,
                        drawY,
                        scale,
                        selectedColor,
                        true
                );
            }
            g2.dispose();
        }

        private void drawEmptyState(Graphics2D graphics) {
            graphics.setFont(FONT_NORMAL);
            graphics.setColor(theme.textMuted());
            FontMetrics metrics = graphics.getFontMetrics();
            graphics.drawString(
                    emptyStateText,
                    (getWidth() - metrics.stringWidth(emptyStateText)) / 2,
                    (getHeight() + metrics.getAscent()) / 2
            );
        }

        private void drawHighlight(
                Graphics2D graphics,
                Rectangle bounds,
                int drawX,
                int drawY,
                double scale,
                Color color,
                boolean selected
        ) {
            int width = Math.max(6, (int) Math.round(bounds.width * scale));
            int height = Math.max(6, (int) Math.round(bounds.height * scale));
            int centerX = drawX + (int) Math.round(
                    (bounds.x + bounds.width / 2.0) * scale
            );
            int centerY = drawY + (int) Math.round(
                    (bounds.y + bounds.height / 2.0) * scale
            );
            int x = centerX - width / 2;
            int y = centerY - height / 2;

            graphics.setColor(color);
            graphics.setStroke(new BasicStroke(selected ? 2.8f : 1.8f));
            graphics.drawRoundRect(x, y, width, height, 6, 6);
            if (selected) {
                graphics.drawLine(centerX - 6, centerY, centerX + 6, centerY);
                graphics.drawLine(centerX, centerY - 6, centerX, centerY + 6);
            }
        }
    }
}
