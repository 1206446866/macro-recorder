package com.macro.ui.dialog;

import com.macro.constant.UiConstants;
import com.macro.recognition.ScreenRecognitionService;
import com.macro.recognition.TemplateScaleRange;
import com.macro.ocr.OcrEngine;
import com.macro.ocr.OcrLanguage;
import com.macro.ocr.OcrService;
import com.macro.flow.ImageCheckConfig;
import com.macro.flow.ImageSearchScope;
import com.macro.model.TargetWindow;
import com.macro.recognition.ImageRecognitionDiagnostics;
import com.macro.ui.component.RoundedPanel;
import com.macro.ui.capture.WindowsCaptureSupport;
import com.macro.ui.theme.ThemeManager;
import com.macro.ui.theme.ThemePalette;
import com.macro.ui.util.ComponentStyleUtil;
import com.macro.ui.util.NumericInputUtil;
import com.macro.ui.window.WindowTitleBarSupport;
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
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/** 用于加载模板并验证屏幕图像识别效果的独立窗口。 */
public final class ImageRecognitionFrame extends JFrame {
    private final WindowTitleBarSupport titleBarSupport;

    /** 识别窗口创建后复用主页的三个窗口按钮及 DWM 边框。 */
    @Override public void addNotify() {
        super.addNotify();
        if (titleBarSupport != null) titleBarSupport.activate();
    }

    /** 释放窗口句柄前恢复原消息链，识别取消和窗口恢复沿用现有 dispose。 */
    @Override public void removeNotify() {
        try { if (titleBarSupport != null) titleBarSupport.deactivate(); }
        finally { super.removeNotify(); }
    }

    private static final Dimension DEFAULT_SIZE = new Dimension(1080, 640);
    private static final Dimension MINIMUM_SIZE = new Dimension(1080, 640);
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

    private final ThemePalette theme = ThemeManager.getPalette();
    private final FileDialogService fileDialogs = FileDialogServiceFactory.create();
    private final Supplier<File> defaultDirectorySupplier;
    private final OcrEngine ocrEngine;
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
    private final JLabel peakMemoryValue = createValueLabel();
    private final JLabel memoryValue = createValueLabel();

    private final JButton loadButton = new JButton("加载图像");
    private final JButton clearButton = new JButton("清除");
    private final JButton recognizeButton = new JButton("识别当前屏幕");
    private final JButton copyPositionButton = new JButton("复制位置");
    private final JButton previousResultButton = new JButton("‹");
    private final JButton nextResultButton = new JButton("›");
    private final JLabel resultIndexValue = createValueLabel();
    private final JCheckBox showAllResultsCheckBox =
            new JCheckBox("显示全部", true);
    private final JSpinner thresholdSpinner = new JSpinner(
            new SpinnerNumberModel(90, 1, 100, 1)
    );
    private final JCheckBox scaleMatchingCheckBox = new JCheckBox("缩放匹配");
    private final JSpinner minimumScaleSpinner = new JSpinner(new SpinnerNumberModel(
            TemplateScaleRange.DEFAULT.minimum() * 100, 1.0, 100.0, 1.0));
    private final JSpinner maximumScaleSpinner = new JSpinner(new SpinnerNumberModel(
            TemplateScaleRange.DEFAULT.maximum() * 100, 100.0, 400.0, 1.0));
    private final JTextArea templateTextArea = new JTextArea();
    private final JPanel templateTextContent = new JPanel(new CardLayout());
    private final JLabel templateTextEmpty = createLabel("无信息", FONT_NORMAL, theme.textMuted());
    private final JLabel templateTextStatus = createValueLabel();
    private final JButton copyTextButton = new JButton("复制文字");
    private final JButton retryTextButton = new JButton("重新提取");
    private SwingWorker<String, Void> templateTextWorker;
    private AtomicBoolean templateTextCancelled;
    private boolean templateTextPending;
    private String extractedTemplateText = "";

    private BufferedImage templateImage;
    /** 从动作编辑器传入，仅影响测试，不修改尚未保存的动作。 */
    private boolean scaleMatchingEnabled;
    private final ImageCheckConfig scopeConfig = new ImageCheckConfig();
    private final JLabel scopeLabel = new JLabel("识别范围：桌面");
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
    private final List<HiddenWindowState> hiddenWindows = new ArrayList<>();
    private boolean closing;

    /** 创建独立识别预览窗口，绑定模板目录来源并初始化空结果，不自动采集屏幕。 */
    public ImageRecognitionFrame(
            Window applicationWindow,
            Supplier<File> defaultDirectorySupplier
    ) {
        this(applicationWindow, defaultDirectorySupplier, new OcrService());
    }

    /** 注入文字引擎供隔离测试使用；构造及隐藏预览不启动提取。 */
    ImageRecognitionFrame(Window applicationWindow, Supplier<File> defaultDirectorySupplier,
                          OcrEngine ocrEngine) {
        super("图像识别测试");
        this.defaultDirectorySupplier = defaultDirectorySupplier;
        this.ocrEngine = java.util.Objects.requireNonNull(ocrEngine);

        initializeWindow(applicationWindow);
        titleBarSupport = new WindowTitleBarSupport(this);
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
        JPanel content = new JPanel(new BorderLayout(14, 0));
        content.setOpaque(false);
        content.add(cards, BorderLayout.CENTER);
        content.add(createTemplateTextCard(), BorderLayout.EAST);
        root.add(content, BorderLayout.CENTER);
        root.add(createControlBar(), BorderLayout.SOUTH);
        setContentPane(root);
    }

    /** 保留识别范围，移除重复标题与用途说明。 */
    private JPanel createHeader() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        scopeLabel.setFont(FONT_SMALL); scopeLabel.setForeground(theme.textSecondary());
        panel.add(scopeLabel);
        return panel;
    }

    /** 文字仅来自当前模板，后台提取与屏幕匹配互不等待。 */
    private JPanel createTemplateTextCard() {
        RoundedPanel card = createCard();
        card.setLayout(new BorderLayout(0, 10));
        card.setPreferredSize(new Dimension(230, 0));
        card.add(createLabel("识别文字", FONT_SECTION, theme.textPrimary()), BorderLayout.NORTH);
        templateTextArea.setEditable(false);
        templateTextArea.setLineWrap(true);
        templateTextArea.setWrapStyleWord(true);
        templateTextArea.setFont(FONT_NORMAL);
        templateTextArea.setBackground(theme.targetCard());
        templateTextArea.setForeground(theme.textPrimary());
        templateTextArea.setCaretColor(theme.textPrimary());
        templateTextArea.setSelectionColor(theme.accent());
        templateTextArea.setSelectedTextColor(theme.textOnAccent());
        templateTextArea.setMargin(new Insets(4, 4, 4, 4));
        JScrollPane scroll = new JScrollPane(templateTextArea);
        ComponentStyleUtil.styleScrollPane(scroll);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setBackground(theme.targetCard());
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        templateTextContent.setOpaque(false);
        templateTextEmpty.setHorizontalAlignment(SwingConstants.CENTER);
        templateTextEmpty.setVerticalAlignment(SwingConstants.CENTER);
        templateTextContent.add(templateTextEmpty, "empty");
        templateTextContent.add(scroll, "text");
        RoundedPanel textBox = new RoundedPanel(theme.targetCard(), theme.border(), 12);
        textBox.setLayout(new BorderLayout());
        textBox.setBorder(new EmptyBorder(10, 10, 10, 10));
        textBox.add(templateTextContent, BorderLayout.CENTER);
        card.add(textBox, BorderLayout.CENTER);
        showTemplateText();
        JPanel footer = new JPanel(new BorderLayout(0, 8));
        footer.setOpaque(false);
        templateTextStatus.setText("当前模板图片");
        footer.add(templateTextStatus, BorderLayout.NORTH);
        JPanel buttons = new JPanel(new GridLayout(1, 2, 6, 0));
        buttons.setOpaque(false);
        for (JButton button : new JButton[]{retryTextButton, copyTextButton}) {
            button.setFont(FONT_SMALL);
            ComponentStyleUtil.styleSecondaryButton(button);
            button.setBorder(new EmptyBorder(7, 6, 7, 6));
            buttons.add(button);
        }
        copyTextButton.setEnabled(false);
        retryTextButton.setEnabled(false);
        footer.add(buttons, BorderLayout.SOUTH);
        card.add(footer, BorderLayout.SOUTH);
        return card;
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

        constraints.gridx = 3;
        constraints.weightx = 1.0;
        constraints.insets = new Insets(0, 0, 0, 12);
        JPanel scaleControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        scaleControls.setOpaque(false);
        scaleMatchingCheckBox.setFont(FONT_NORMAL);
        ComponentStyleUtil.styleCheckBox(scaleMatchingCheckBox);
        scaleMatchingCheckBox.setToolTipText("仅调整本测试窗口；先按原尺寸匹配，未命中再尝试范围内有限比例");
        scaleControls.add(scaleMatchingCheckBox);
        configureScaleSpinner(minimumScaleSpinner, "缩放下限：1%～100%");
        configureScaleSpinner(maximumScaleSpinner, "缩放上限：100%～400%");
        scaleControls.add(minimumScaleSpinner);
        scaleControls.add(createLabel("% ～", FONT_NORMAL, theme.textSecondary()));
        scaleControls.add(maximumScaleSpinner);
        scaleControls.add(createLabel("%", FONT_NORMAL, theme.textSecondary()));
        panel.add(scaleControls, constraints);

        JPanel recognitionButtons = new JPanel(new GridLayout(1, 1));
        recognitionButtons.setOpaque(false);
        recognizeButton.setFont(FONT_BOLD);
        ComponentStyleUtil.stylePrimaryButton(recognizeButton);
        recognitionButtons.add(recognizeButton);
        constraints.gridx = 4;
        constraints.weightx = 0.0;
        constraints.insets = new Insets(0, 0, 0, 0);
        panel.add(recognitionButtons, constraints);
        return panel;
    }

    private void configureScaleSpinner(JSpinner spinner, String tooltip) {
        spinner.setEditor(new JSpinner.NumberEditor(spinner, "0.###"));
        JSpinner.NumberEditor editor = (JSpinner.NumberEditor) spinner.getEditor();
        javax.swing.text.NumberFormatter formatter = new javax.swing.text.NumberFormatter(editor.getFormat()) {
            @Override public Object stringToValue(String text) throws java.text.ParseException {
                // Enter 和箭头触发的提交也执行完整校验，不能把 10x 截成 10。
                if (text.equals(valueToString(spinner.getValue()))) return spinner.getValue();
                SpinnerNumberModel model = (SpinnerNumberModel) spinner.getModel();
                if (!text.matches("[0-9]+(?:\\.[0-9]{1,3})?"))
                    throw new java.text.ParseException("请输入最多三位小数的百分比", 0);
                double value = Double.parseDouble(text);
                if (!Double.isFinite(value) || value < ((Number) model.getMinimum()).doubleValue()
                        || value > ((Number) model.getMaximum()).doubleValue())
                    throw new java.text.ParseException("百分比超出范围", 0);
                return value;
            }
        };
        formatter.setValueClass(Double.class);
        editor.getTextField().setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(formatter));
        // 保留未改动的精确默认 2/3；离开输入框时统一由识别按钮校验。
        ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField()
                .setFocusLostBehavior(JFormattedTextField.PERSIST);
        spinner.setPreferredSize(new Dimension(112, 34));
        spinner.setFont(FONT_NORMAL);
        spinner.setToolTipText(tooltip);
        ComponentStyleUtil.styleSpinner(spinner);
    }

    private static double commitScalePercent(JSpinner spinner, String name) {
        JFormattedTextField field = ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField();
        try {
            field.commitEdit();
            return ((Number) spinner.getValue()).doubleValue() / 100;
        } catch (java.text.ParseException | IllegalArgumentException exception) {
            throw new IllegalArgumentException(name + "请输入范围内的百分比，最多三位小数。", exception);
        }
    }

    private TemplateScaleRange readScaleRange() {
        double minimum = commitScalePercent(minimumScaleSpinner, "缩放下限");
        double maximum = commitScalePercent(maximumScaleSpinner, "缩放上限");
        if (Math.abs(minimum - TemplateScaleRange.DEFAULT.minimum()) < 1e-12)
            minimum = TemplateScaleRange.DEFAULT.minimum();
        return new TemplateScaleRange(minimum, maximum);
    }

    /** 注册按钮、快捷键和窗口关闭处理。 */
    private void initializeEvents() {
        loadButton.addActionListener(event -> loadTemplateImage());
        clearButton.addActionListener(event -> clearTemplateImage());
        recognizeButton.addActionListener(event -> startRecognition());
        copyPositionButton.addActionListener(event -> copyRecognitionPosition());
        previousResultButton.addActionListener(event -> selectAdjacentResult(-1));
        nextResultButton.addActionListener(event -> selectAdjacentResult(1));
        showAllResultsCheckBox.addActionListener(event -> refreshResultPreview());
        scaleMatchingCheckBox.addActionListener(event -> {
            scaleMatchingEnabled = scaleMatchingCheckBox.isSelected();
            updateActionState();
        });
        copyTextButton.addActionListener(event -> copyTemplateText());
        retryTextButton.addActionListener(event -> {
            templateTextPending = templateImage != null;
            startPendingTemplateText();
        });
        addComponentListener(new ComponentAdapter() {
            @Override public void componentShown(ComponentEvent event) { startPendingTemplateText(); }
        });

        getRootPane().registerKeyboardAction(
                event -> dispose(),
                KeyStroke.getKeyStroke("ESCAPE"),
                JComponent.WHEN_IN_FOCUSED_WINDOW
        );
        addWindowListener(new WindowAdapter() {
            /** 取消仍在等待或执行的识别任务，恢复本次临时隐藏的窗口。 */
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
            setTemplate(selectedFile, ((Number) thresholdSpinner.getValue()).doubleValue() / 100.0);
        } catch (IOException | RuntimeException exception) {
            AppMessageDialog.showError(this, "加载待识别图像失败", exception);
        }
    }

    /** 载入动作编辑器当前的模板；只准备预览，不自动识别或修改宏。 */
    public void setTemplate(File file, double threshold) throws IOException {
        setTemplate(file, threshold, scaleMatchingEnabled);
    }

    /** 仅传递未保存的范围配置，不定位窗口、不截图。 */
    public void setSearchScope(ImageSearchScope scope, TargetWindow window) {
        if (recognizing || closing) throw new IllegalArgumentException("识别窗口当前不可更换范围");
        ImageCheckConfig selection = new ImageCheckConfig();
        selection.setSearchScope(scope); selection.setSearchWindow(window); selection.validateSearchScope();
        scopeConfig.setSearchScope(selection.getSearchScope()); scopeConfig.setSearchWindow(selection.getSearchWindow());
        recognitionService.clearWindowBindings();
        boolean restricted = scopeConfig.getSearchScope() == ImageSearchScope.WINDOW;
        scopeLabel.setText(restricted ? "识别范围：指定窗口 · " + window.getWindowTitle() : "识别范围：桌面");
        scopeLabel.setToolTipText(restricted ? window.getProcessName() + " · 需保持窗口在前台，不会自动激活" : null);
        recognizeButton.setText(restricted ? "识别指定窗口" : "识别当前屏幕");
        clearResult();
    }

    /** 载入动作编辑器的模板、阈值及缩放开关，保持测试与播放的识别参数一致。 */
    public void setTemplate(File file, double threshold, boolean scaleMatchingEnabled) throws IOException {
        if (recognizing || closing) throw new IllegalArgumentException("识别窗口当前不可更换模板");
        if (file == null || !Double.isFinite(threshold) || threshold < .01 || threshold > 1.0) {
            throw new IllegalArgumentException("请选择有效模板和匹配阈值");
        }
        BufferedImage image = ImageIO.read(file);
        if (image == null) throw new IOException("无法读取模板图片：" + file.getName());
        templateImage = image;
        this.scaleMatchingEnabled = scaleMatchingEnabled;
        scaleMatchingCheckBox.setSelected(scaleMatchingEnabled);
        lastDirectory = file.getAbsoluteFile().getParentFile();
        templatePreview.setImage(image, null, theme.accent());
        templateNameValue.setText(file.getName());
        templateNameValue.setToolTipText(file.getAbsolutePath());
        templateSizeValue.setText(image.getWidth() + " × " + image.getHeight());
        thresholdSpinner.setValue((int) Math.round(threshold * 100.0));
        clearResult();
        updateActionState();
        resetTemplateText();
        if (isShowing()) startPendingTemplateText();
    }

    private void resetTemplateText() {
        if (templateTextCancelled != null) templateTextCancelled.set(true);
        templateTextPending = templateImage != null;
        extractedTemplateText = "";
        showTemplateText();
        templateTextStatus.setText("当前模板图片");
        templateTextStatus.setToolTipText(null);
        copyTextButton.setEnabled(false);
        retryTextButton.setEnabled(templateImage != null && templateTextWorker == null);
    }

    /** 在旧原生调用实际结束后才启动最新模板，避免快速换图产生并行积压。 */
    private void startPendingTemplateText() {
        if (closing || templateImage == null || !templateTextPending || templateTextWorker != null) return;
        templateTextPending = false;
        BufferedImage image = templateImage;
        AtomicBoolean cancelled = new AtomicBoolean();
        templateTextCancelled = cancelled;
        extractedTemplateText = "";
        showTemplateText();
        templateTextStatus.setText("正在提取…");
        templateTextStatus.setToolTipText(null);
        retryTextButton.setEnabled(false);
        copyTextButton.setEnabled(false);
        templateTextWorker = new SwingWorker<>() {
            @Override protected String doInBackground() throws Exception {
                return ocrEngine.extract(image, EnumSet.allOf(OcrLanguage.class), false, cancelled::get);
            }

            @Override protected void done() {
                if (templateTextWorker != this) return;
                templateTextWorker = null;
                if (closing) return;
                if (!cancelled.get() && templateImage == image) {
                    try {
                        String text = get();
                        extractedTemplateText = text == null ? "" : text.strip();
                        showTemplateText();
                        templateTextStatus.setText("当前模板图片");
                        templateTextStatus.setToolTipText(null);
                        copyTextButton.setEnabled(!extractedTemplateText.isEmpty());
                    } catch (CancellationException exception) {
                        templateTextStatus.setText("提取已取消");
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        templateTextStatus.setText("提取已中断");
                    } catch (ExecutionException exception) {
                        templateTextStatus.setText("提取失败，可重新提取");
                        Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                        templateTextStatus.setToolTipText(cause.getMessage());
                        if (!(cause instanceof CancellationException))
                            com.macro.diagnostics.ErrorReporter.report("提取模板文字失败", cause);
                    }
                }
                retryTextButton.setEnabled(templateImage != null);
                startPendingTemplateText();
            }
        };
        templateTextWorker.execute();
    }

    /** 空状态单独居中展示；有文字时保留左上对齐、选择及滚动。 */
    private void showTemplateText() {
        templateTextArea.setText(extractedTemplateText);
        templateTextArea.setCaretPosition(0);
        ((CardLayout) templateTextContent.getLayout()).show(templateTextContent,
                extractedTemplateText.isEmpty() ? "empty" : "text");
    }

    private void copyTemplateText() {
        if (extractedTemplateText.isEmpty()) return;
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(extractedTemplateText), null);
        } catch (IllegalStateException exception) {
            AppMessageDialog.showWarning(this, "剪贴板暂时不可用，请稍后重试。", exception);
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
        resetTemplateText();
        templatePreview.setImage(null, null, theme.accent());
        templateNameValue.setText("—");
        templateNameValue.setToolTipText(null);
        templateSizeValue.setText("—");
        clearResult();
        updateActionState();
    }

    /** 启动一次当前屏幕识别。 */
    private void startRecognition() {
        if (closing || templateImage == null || recognizing) {
            if (templateImage == null) {
                AppMessageDialog.showInfo(this, "请先加载待识别图像。");
            }
            return;
        }

        try {
            NumericInputUtil.commitSpinnerInt(thresholdSpinner);
            if (scaleMatchingEnabled) readScaleRange();
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

        try {
            hideApplicationWindows();
        } catch (RuntimeException exception) {
            recognizing = false;
            restoreApplicationWindows();
            showRecognitionFailure(exception);
            updateActionState();
            return;
        }
        ImageRecognitionDiagnostics.log("TEST", "WINDOW_PREPARATION",
                "TemplatePath: " + templateNameValue.getToolTipText()
                        + "\nApplicationWindowsHidden: " + applicationWindowsHidden
                        + "\nCaptureSettleDelayMillis: " + CAPTURE_SETTLE_DELAY_MILLIS);
        Toolkit.getDefaultToolkit().sync();
        captureTimer = new Timer(
                CAPTURE_SETTLE_DELAY_MILLIS,
                event -> runRecognitionWorker()
        );
        captureTimer.setRepeats(false);
        captureTimer.start();
    }

    /** 在后台完成屏幕采集和模板匹配。 */
    private void runRecognitionWorker() {
        captureTimer = null;
        if (closing || !recognizing) return;
        BufferedImage template = templateImage;
        double threshold = ((Number) thresholdSpinner.getValue()).doubleValue() / 100.0;
        boolean useScaleMatching = scaleMatchingEnabled;
        TemplateScaleRange scaleRange = useScaleMatching ? readScaleRange() : TemplateScaleRange.DEFAULT;
        ImageCheckConfig searchConfig = new ImageCheckConfig();
        searchConfig.setSearchScope(scopeConfig.getSearchScope()); searchConfig.setSearchWindow(scopeConfig.getSearchWindow());
        boolean restricted = searchConfig.getSearchScope() == ImageSearchScope.WINDOW;
        recognitionService.clearWindowBindings();
        String requestId = ImageRecognitionDiagnostics.nextRequestId("TEST");
        String input = "Request: " + requestId
                + "\nTemplatePath: " + templateNameValue.getToolTipText()
                + "\nThreshold: " + ImageRecognitionDiagnostics.similarity(threshold)
                + "\nScaleMatchingEnabled: " + useScaleMatching
                + "\nScaleRange: " + scaleRange
                + "\nSearchScope: " + searchConfig.getSearchScope().name()
                + "\nTemplatePixels: " + template.getWidth() + "x" + template.getHeight()
                + "\nApplicationWindowsHidden: " + applicationWindowsHidden;

        recognitionWorker = new SwingWorker<>() {
            /** 在后台同步画面、采集屏幕并执行 OpenCV 匹配；采集完成后即通知事件线程恢复程序窗口。 */
            @Override
            protected ScreenRecognitionService.RecognitionReport doInBackground()
                    throws Exception {
                ImageRecognitionDiagnostics.log("TEST", "MATCH_START", input);
                Toolkit.getDefaultToolkit().sync();
                WindowsCaptureSupport.flush();
                String foreground = ImageRecognitionDiagnostics.foregroundWindow();
                var scope = recognitionService.captureScope(searchConfig, this::isCancelled);
                List<ScreenRecognitionService.ScreenSnapshot> snapshots =
                        scope.captureScreens(null, this::isCancelled);
                String capture = "Request: " + requestId
                        + "\nCapturedAt: " + java.time.Instant.now()
                        + "\nForegroundBeforeCapture: " + foreground
                        + "\n" + ImageRecognitionDiagnostics.describeScreens(snapshots);
                ImageRecognitionDiagnostics.log("TEST", "CAPTURE", capture);
                SwingUtilities.invokeLater(() -> {
                    if (recognitionWorker != this || closing) return;
                    if (!restricted) restoreApplicationWindows();
                    if (isDisplayable()) {
                        setResultStatus("正在匹配…", theme.accent());
                    }
                });
                ScreenRecognitionService.RecognitionReport report = recognitionService.recognize(
                        snapshots,
                        template,
                        threshold,
                        useScaleMatching,
                        this::isCancelled,
                        scaleRange
                );
                scope.verify(this::isCancelled);
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

            /** 只处理当前识别任务的结果，先恢复窗口，再显示报告或异常，忽略已过期任务的通知。 */
            @Override
            protected void done() {
                if (recognitionWorker != this) return;
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

    /** 展示本次匹配的近似内存开销。 */
    private void showRecognitionMetrics(
            ScreenRecognitionService.RecognitionReport report
    ) {
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

    /** 将字节数格式化为 B、KB 或 MB，便于读取近似内存统计。 */
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
        ScreenRecognitionService.RecognitionResult result = displayedResults.get(selectedResultIndex);
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

    /** 根据当前结果及显示全部选项更新同一屏幕上的高亮框，不重新匹配图片。 */
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

    /** 取得当前有效的识别结果；选择索引过期或没有结果时返回 null。 */
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

    /** 先完整记录可见窗口，再统一隐藏；父窗口隐藏不能漏掉编辑器等子窗口。 */
    private void hideApplicationWindows() {
        if (applicationWindowsHidden) {
            return;
        }
        hiddenWindows.clear();
        for (Window window : Window.getWindows()) {
            if (window.isVisible() && window.isDisplayable()) {
                hiddenWindows.add(new HiddenWindowState(window,
                        window instanceof Frame frame ? frame.getExtendedState() : 0,
                        WindowsCaptureSupport.cloak(window)));
            }
        }
        applicationWindowsHidden = true;
        for (HiddenWindowState saved : hiddenWindows) saved.window().setVisible(false);
    }

    /** 恢复识别前的应用窗口可见状态。 */
    private void restoreApplicationWindows() {
        if (!applicationWindowsHidden) {
            return;
        }
        applicationWindowsHidden = false;
        List<HiddenWindowState> savedWindows = new ArrayList<>(hiddenWindows);
        hiddenWindows.clear();
        savedWindows.sort(Comparator.comparingInt(saved -> ownerDepth(saved.window())));
        boolean restoreRecognition = false;
        for (HiddenWindowState saved : savedWindows) {
            Window window = saved.window();
            try {
                if (!window.isDisplayable() || window == this && closing) continue;
                if (window instanceof Frame frame) frame.setExtendedState(saved.extendedState());
                window.setVisible(true);
                restoreRecognition |= window == this;
            } catch (RuntimeException failure) {
                com.macro.diagnostics.ErrorReporter.report("恢复识别前窗口状态失败", failure);
            } finally {
                saved.cloak().close();
            }
        }
        if (restoreRecognition) {
            toFront();
            requestFocus();
        }
    }

    /** 计算窗口所属链的深度，使恢复时先显示父窗口，再显示所属子窗口。 */
    private static int ownerDepth(Window window) {
        int depth = 0;
        while ((window = window.getOwner()) != null) depth++;
        return depth;
    }

    private record HiddenWindowState(Window window, int extendedState, WindowsCaptureSupport.Cloak cloak) { }

    /** 先标记关闭并取消任务，恢复其他窗口后释放当前窗口，防止迟到回调重新打开它。 */
    @Override
    public void dispose() {
        closing = true;
        templateTextPending = false;
        if (templateTextCancelled != null) templateTextCancelled.set(true);
        cancelPendingRecognition();
        super.dispose();
    }

    /** 取消尚未结束的延时或后台识别。 */
    private void cancelPendingRecognition() {
        if (captureTimer != null) {
            captureTimer.stop();
            captureTimer = null;
        }
        SwingWorker<?, ?> pending = recognitionWorker;
        recognitionWorker = null;
        if (pending != null && !pending.isDone()) pending.cancel(true);
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

    /** 清除匹配详情与内存显示，同时移除上一轮统计的工具提示。 */
    private void resetResultValues() {
        resetMatchValues();
        peakMemoryValue.setText("—");
        peakMemoryValue.setToolTipText(null);
        memoryValue.setText("—");
        memoryValue.setToolTipText(null);
    }

    /** 清空匹配位置详情，同时保留本次内存统计。 */
    private void resetMatchValues() {
        similarityValue.setText("—");
        positionValue.setText("—");
        positionValue.setToolTipText(null);
        centerValue.setText("—");
        resultSizeValue.setText("—");
        displayValue.setText("—");
    }

    /** 同步更新识别状态文字及其主题提示颜色。 */
    private void setResultStatus(String text, Color color) {
        resultStatusValue.setText(text);
        resultStatusValue.setForeground(color);
    }

    /** 根据当前模板和后台任务状态更新控件。 */
    private void updateActionState() {
        boolean hasTemplate = templateImage != null;
        loadButton.setEnabled(!recognizing);
        clearButton.setEnabled(hasTemplate && !recognizing);
        recognizeButton.setEnabled(hasTemplate && !recognizing);
        thresholdSpinner.setEnabled(!recognizing);
        scaleMatchingCheckBox.setEnabled(!recognizing);
        minimumScaleSpinner.setEnabled(!recognizing && scaleMatchingEnabled);
        maximumScaleSpinner.setEnabled(!recognizing && scaleMatchingEnabled);
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

    /** 创建识别预览使用的主题圆角卡片，并设置统一内容边距。 */
    private RoundedPanel createCard() {
        RoundedPanel card = new RoundedPanel(theme.card(), theme.border(), 16);
        card.setBorder(new EmptyBorder(14, 14, 14, 14));
        return card;
    }

    /** 创建透明的网格信息容器，供模板和结果字段复用。 */
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
        panel.add(createInformationItem("进程峰值", peakMemoryValue));
        panel.add(createInformationItem("中心坐标", centerValue));
        panel.add(createInformationItem("算法内存", memoryValue));
        panel.add(createInformationItem("区域尺寸", resultSizeValue));
        return panel;
    }

    /** 将字段名称和数值标签组合为一项可伸展的识别信息。 */
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

    /** 在指定信息行放置名称和数值，数值区域占用剩余横向空间。 */
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

    /** 创建使用指定字体和主题颜色的普通标签。 */
    private JLabel createLabel(String text, Font font, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(font);
        label.setForeground(color);
        return label;
    }

    /** 创建统一样式的空值标签，等待模板或识别结果填充。 */
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

        /** 初始化透明预览画布及其默认显示尺寸。 */
        private RecognitionPreviewPanel() {
            setOpaque(true);
            setBackground(theme.targetCard());
            setPreferredSize(PREVIEW_SIZE);
            setMinimumSize(new Dimension(220, 140));
            setBorder(BorderFactory.createLineBorder(theme.border(), 1, true));
        }

        /** 替换预览图片和单个高亮区域，复制区域信息后请求重绘。 */
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

        /** 保存同屏多个匹配框、当前重点框及各自颜色，刷新预览而不重新识别。 */
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

        /** 按可用区域缩放绘制图片与高亮框，没有图片时显示当前空状态提示。 */
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

        /** 在预览区域绘制居中的空结果或处理中提示。 */
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

        /** 按图片缩放和偏移换算匹配区域，分别绘制普通框或当前重点框。 */
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
