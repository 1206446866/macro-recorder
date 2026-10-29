package com.macro.ui.dialog;

import com.macro.constant.UiConstants;
import com.macro.ocr.*;
import com.macro.ui.filedialog.*;
import com.macro.ui.theme.ThemeManager;
import com.macro.ui.theme.ThemePalette;
import com.macro.ui.util.ComponentStyleUtil;
import com.macro.ui.window.WindowTitleBarSupport;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** 图片文字提取；仅由用户点击开始，后台运行，可编辑、复制及保存结果。 */
public final class OcrTextDialog extends JDialog {
    private final WindowTitleBarSupport titleBarSupport;
    private final ThemePalette theme = ThemeManager.getPalette();
    private final BufferedImage image;
    private final OcrEngine engine;
    private final Supplier<File> directory;
    private final FileDialogService fileDialogs;
    private final EnumMap<OcrLanguage, JCheckBox> languages = new EnumMap<>(OcrLanguage.class);
    private final JCheckBox singleLine = new JCheckBox("单行文字");
    private final JTextArea text = new JTextArea();
    private final JLabel status = new JLabel("选择图片中的语言后，点击提取文字");
    private final JButton extract = new JButton("提取文字");
    private final JButton cancel = new JButton("取消提取");
    private final JButton copy = new JButton("复制文字");
    private final JButton save = new JButton("保存文字");
    private SwingWorker<String, Void> worker;
    private AtomicBoolean cancellation = new AtomicBoolean();
    private boolean saving;
    private boolean closed;

    public OcrTextDialog(Window owner, BufferedImage image, Supplier<File> directory) {
        this(owner, image, directory, new OcrService(), FileDialogServiceFactory.create());
    }

    OcrTextDialog(Window owner, BufferedImage image, Supplier<File> directory,
                  OcrEngine engine, FileDialogService fileDialogs) {
        super(owner, "提取图片文字", ModalityType.MODELESS);
        this.image = java.util.Objects.requireNonNull(image);
        this.engine = engine;
        this.directory = directory;
        this.fileDialogs = fileDialogs;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(760, 520);
        setMinimumSize(new Dimension(660, 440));
        setLocationRelativeTo(owner);
        setContentPane(createContent());
        titleBarSupport = new WindowTitleBarSupport(this);
        extract.addActionListener(event -> startExtraction());
        cancel.addActionListener(event -> { cancellation.set(true); status.setText("正在取消…"); cancel.setEnabled(false); });
        copy.addActionListener(event -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text.getText()), null);
            status.setText("文字已复制");
        });
        save.addActionListener(event -> saveText());
        text.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent event) { updateControls(); }
            public void removeUpdate(javax.swing.event.DocumentEvent event) { updateControls(); }
            public void changedUpdate(javax.swing.event.DocumentEvent event) { updateControls(); }
        });
        updateControls();
    }

    private JPanel createContent() {
        Font font = new Font(UiConstants.DEFAULT_FONT_FAMILY, Font.PLAIN, 13);
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(theme.background());
        root.setBorder(new EmptyBorder(16, 18, 16, 18));
        JPanel top = new JPanel(new BorderLayout(0, 10)); top.setOpaque(false);
        JLabel description = new JLabel("从当前图片提取文字，可编辑后复制或保存为 TXT（" + image.getWidth() + " × " + image.getHeight() + "）");
        description.setForeground(theme.textSecondary()); description.setFont(font);
        top.add(description, BorderLayout.NORTH);
        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0)); options.setOpaque(false);
        for (OcrLanguage language : OcrLanguage.values()) {
            JCheckBox check = new JCheckBox(language.toString(), language == OcrLanguage.SIMPLIFIED_CHINESE || language == OcrLanguage.ENGLISH);
            check.setName("ocrLanguage-" + language.code()); check.setOpaque(false); check.setFont(font);
            check.setForeground(theme.textPrimary()); languages.put(language, check); options.add(check);
        }
        singleLine.setOpaque(false); singleLine.setForeground(theme.textPrimary()); singleLine.setFont(font);
        singleLine.setToolTipText("图片只有一行文字时勾选；未勾选时自动分析文字布局");
        options.add(singleLine); top.add(options, BorderLayout.CENTER); root.add(top, BorderLayout.NORTH);
        text.setName("ocrResult"); text.setFont(new Font(UiConstants.DEFAULT_FONT_FAMILY, Font.PLAIN, 16));
        text.setLineWrap(true); text.setWrapStyleWord(true);
        text.setBackground(theme.card()); text.setForeground(theme.textPrimary()); text.setCaretColor(theme.textPrimary());
        text.setMargin(new Insets(10, 12, 10, 12));
        root.add(new JScrollPane(text), BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout(0, 10)); bottom.setOpaque(false);
        status.setFont(font); status.setForeground(theme.textSecondary()); bottom.add(status, BorderLayout.NORTH);
        JPanel buttons = new JPanel(new GridLayout(1, 4, 10, 0)); buttons.setOpaque(false);
        for (JButton button : new JButton[]{extract, cancel, copy, save}) {
            button.setFont(font); ComponentStyleUtil.styleSecondaryButton(button); buttons.add(button);
        }
        ComponentStyleUtil.stylePrimaryButton(extract);
        bottom.add(buttons, BorderLayout.SOUTH); root.add(bottom, BorderLayout.SOUTH);
        return root;
    }

    private void startExtraction() {
        if (closed || worker != null || saving) return;
        Set<OcrLanguage> selection = EnumSet.noneOf(OcrLanguage.class);
        languages.forEach((language, check) -> { if (check.isSelected()) selection.add(language); });
        if (selection.isEmpty()) { status.setText("请至少选择一种识别语言"); return; }
        boolean line = singleLine.isSelected();
        AtomicBoolean token = new AtomicBoolean(); cancellation = token;
        long started = System.nanoTime();
        status.setToolTipText(null);
        status.setText("正在提取文字…");
        worker = new SwingWorker<>() {
            @Override protected String doInBackground() throws Exception {
                return engine.extract(image, selection, line, token::get);
            }
            @Override protected void done() {
                try {
                    if (closed) return;
                    String result = get();
                    if (token.get()) { status.setText("已取消文字提取"); return; }
                    text.setText(result); text.setCaretPosition(0);
                    status.setText(result.isBlank() ? "未提取到文字，可尝试缩小图片范围或调整语言"
                            : "已提取 " + result.codePointCount(0, result.length()) + " 个字符，耗时 "
                                    + (System.nanoTime() - started) / 1_000_000L + " ms");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); status.setText("文字提取已中断");
                } catch (ExecutionException failed) {
                    Throwable cause = failed.getCause();
                    status.setText(token.get() || cause instanceof CancellationException ? "已取消文字提取"
                            : "提取失败：" + cause.getMessage());
                    status.setToolTipText(cause.toString());
                    if (!token.get() && !(cause instanceof CancellationException))
                        com.macro.diagnostics.ErrorReporter.report("提取图片文字失败", cause);
                } finally {
                    worker = null;
                    if (!closed) updateControls();
                }
            }
        };
        updateControls(); worker.execute();
    }

    private void saveText() {
        if (closed || worker != null || saving || text.getText().isEmpty()) return;
        File destination;
        try {
            destination = fileDialogs.showDialog(this, FileDialogOptions.builder(FileDialogOptions.Mode.SAVE_FILE)
                    .title("保存提取的文字").initialDirectory(directory.get()).fileName("提取文字.txt")
                    .fileType("文本文件 (*.txt)", "txt").defaultExtension("txt").confirmOverwrite(false).build()).orElse(null);
        } catch (FileDialogException failed) { AppMessageDialog.showError(this, failed.getMessage(), failed); return; }
        if (destination == null) return;
        if (!destination.getName().toLowerCase(Locale.ROOT).endsWith(".txt")) destination = new File(destination.getPath() + ".txt");
        if (destination.exists() && !AppMessageDialog.showConfirm(this, "覆盖文字文件", "目标文件已经存在，是否覆盖？")) return;
        java.nio.file.Path target = destination.toPath();
        String snapshot = text.getText(); saving = true; updateControls(); status.setText("正在保存文字…");
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception { OcrTextExport.save(target, snapshot); return null; }
            @Override protected void done() {
                try {
                    get(); if (!closed) status.setText("文字已保存");
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                catch (ExecutionException failed) { if (!closed) AppMessageDialog.showError(OcrTextDialog.this, "保存文字失败", failed.getCause()); }
                finally { saving = false; if (!closed) updateControls(); }
            }
        }.execute();
    }

    private void updateControls() {
        boolean busy = worker != null || saving;
        extract.setEnabled(!busy); cancel.setEnabled(worker != null && !cancellation.get());
        copy.setEnabled(!busy && !text.getText().isEmpty()); save.setEnabled(copy.isEnabled());
        text.setEditable(!busy); singleLine.setEnabled(!busy);
        languages.values().forEach(check -> check.setEnabled(!busy));
    }

    @Override public void dispose() {
        closed = true; cancellation.set(true);
        super.dispose();
    }
    @Override public void addNotify() {
        super.addNotify(); if (titleBarSupport != null) titleBarSupport.activate();
    }
    @Override public void removeNotify() {
        try { if (titleBarSupport != null) titleBarSupport.deactivate(); }
        finally { super.removeNotify(); }
    }
}
