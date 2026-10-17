package com.macro.ui.page;

import com.macro.constant.ImageCaptureFormat;

import com.macro.constant.UiConstants;

import com.macro.config.AppConfig;
import com.macro.constant.ApplicationInfo;
import com.macro.storage.ImageCaptureStorage;
import com.macro.ui.component.RoundedPanel;
import com.macro.ui.dialog.AppMessageDialog;
import com.macro.ui.filedialog.FileDialogException;
import com.macro.ui.filedialog.FileDialogOptions;
import com.macro.ui.filedialog.FileDialogService;
import com.macro.ui.filedialog.FileDialogServiceFactory;
import com.macro.ui.theme.ThemeManager;
import com.macro.ui.theme.ThemePalette;
import com.macro.ui.util.ComponentStyleUtil;
import com.macro.ui.util.ImageTransferUtil;

import javax.swing.*;
import javax.imageio.ImageIO;
import javax.swing.border.EmptyBorder;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutionException;

/** 图像采集页面，负责启动区域截图并展示截图结果。 */
public final class ImageCapturePanel extends JPanel {

    private static final DateTimeFormatter CAPTURE_FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    private static final Font FONT_NORMAL =
            new Font(UiConstants.DEFAULT_FONT_FAMILY, Font.PLAIN, 13);
    private static final Font FONT_SMALL =
            new Font(UiConstants.DEFAULT_FONT_FAMILY, Font.PLAIN, 12);
    private static final Font FONT_BOLD =
            new Font(UiConstants.DEFAULT_FONT_FAMILY, Font.BOLD, 13);
    private static final Font FONT_SECTION =
            new Font(UiConstants.DEFAULT_FONT_FAMILY, Font.BOLD, 16);
    private static final Font FONT_TITLE =
            new Font(UiConstants.DEFAULT_FONT_FAMILY, Font.BOLD, 24);

    private final ThemePalette theme = ThemeManager.getPalette();
    private final AppConfig appConfig;
    private final FileDialogService fileDialogs = FileDialogServiceFactory.create();
    private final ImageCaptureStorage imageCaptureStorage;
    private final Runnable onBack;
    private final Runnable onOpenSettings;
    private final Runnable onCaptureRequested;
    private final ImagePreviewPanel previewPanel;
    private final JLabel fileNameValueLabel;
    private final JLabel imageSizeValueLabel;
    private final JLabel saveStatusValueLabel;
    private final JButton captureButton;
    private final JButton clearButton;
    private final JButton saveButton;
    private final JButton saveAsButton;
    private BufferedImage capturedImage;
    private String captureFileName;
    private File currentSaveFile;
    private boolean saving;
    private boolean importing;

    public ImageCapturePanel(
            Runnable onBack,
            Runnable onOpenSettings,
            Runnable onCaptureRequested,
            AppConfig appConfig,
            ImageCaptureStorage imageCaptureStorage
    ) {
        this.onBack = onBack;
        this.onOpenSettings = onOpenSettings;
        this.onCaptureRequested = onCaptureRequested;
        this.appConfig = appConfig;
        this.imageCaptureStorage = imageCaptureStorage;
        previewPanel = new ImagePreviewPanel();
        fileNameValueLabel = createLabel("—", FONT_NORMAL, theme.textPrimary());
        imageSizeValueLabel = createLabel("—", FONT_NORMAL, theme.textPrimary());
        saveStatusValueLabel = createLabel("—", FONT_NORMAL, theme.textMuted());
        captureButton = createButton("截图", IconType.CAPTURE, true);
        clearButton = createButton("清除", IconType.CLEAR, false);
        saveButton = createButton("保存", IconType.SAVE, false);
        saveAsButton = createButton("另存为", IconType.SAVE_AS, false);
        initializeLayout();
        installImageImport();
        updateImageActions(false);
    }

    /** 接收图片粘贴和文件拖入，沿用现有预览与保存流程。 */
    private void installImageImport() {
        setTransferHandler(new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                boolean addressDrop = support.isDrop() && java.util.Arrays.stream(support.getDataFlavors())
                        .anyMatch(ImageTransferUtil::supportsAddress);
                if (saving || importing || (!support.isDataFlavorSupported(DataFlavor.imageFlavor)
                        && !support.isDataFlavorSupported(DataFlavor.javaFileListFlavor) && !addressDrop)) {
                    return false;
                }
                // 仅复制图像内容，避免拖放源把原文件当作移动操作删除。
                if (support.isDrop()) {
                    if ((support.getSourceDropActions() & COPY) == 0) {
                        return false;
                    }
                    support.setDropAction(COPY);
                }
                return true;
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) {
                    return false;
                }
                try {
                    Transferable transferable = support.getTransferable();
                    if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        Object value = null;
                        try {
                            value = transferable.getTransferData(DataFlavor.javaFileListFlavor);
                        } catch (IOException | UnsupportedFlavorException exception) {
                            // 部分应用声明了文件格式，却未提供原生文件数据，继续尝试图片格式。
                        }
                        // 预览区一次只接收一张图片，拒绝目录和多文件拖入。
                        if (value instanceof java.util.List<?> files && !files.isEmpty()) {
                            if (files.size() != 1 || !(files.get(0) instanceof File file)) {
                                return false;
                            }
                            if (file.isFile()) {
                                BufferedImage image = ImageIO.read(file);
                                if (image == null) {
                                    AppMessageDialog.showWarning(ImageCapturePanel.this,
                                            "无法识别该图片，文件可能已损坏或格式暂不支持。");
                                    return false;
                                }
                                // 作为新图片导入，保存时不覆盖拖入的源文件。
                                setCapturedImage(image);
                                return true;
                            }
                            if (file.isDirectory()) {
                                return false;
                            }
                        }
                    }
                    if (!support.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                        return importImageAddress(support);
                    }
                    Object value;
                    try {
                        value = transferable.getTransferData(DataFlavor.imageFlavor);
                    } catch (IOException | UnsupportedFlavorException exception) {
                        return importImageAddress(support);
                    }
                    if (!(value instanceof Image image)) {
                        return importImageAddress(support);
                    }
                    // 等待延迟加载的图像就绪，再复制为独立的像素数据。
                    ImageIcon loadedImage = new ImageIcon(image);
                    int width = loadedImage.getIconWidth();
                    int height = loadedImage.getIconHeight();
                    if (loadedImage.getImageLoadStatus() != MediaTracker.COMPLETE
                            || width <= 0 || height <= 0) {
                        return false;
                    }
                    BufferedImage copy = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D graphics = copy.createGraphics();
                    try {
                        graphics.setComposite(AlphaComposite.Src);
                        graphics.drawImage(loadedImage.getImage(), 0, 0, null);
                    } finally {
                        graphics.dispose();
                    }
                    setCapturedImage(copy);
                    return true;
                } catch (Exception exception) {
                    AppMessageDialog.showError(ImageCapturePanel.this, "导入图片失败", exception);
                    return false;
                }
            }
        });
        previewPanel.setTransferHandler(getTransferHandler());

        String actionKey = "pasteClipboardImage";
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK), actionKey);
        getActionMap().put(actionKey, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                KeyboardFocusManager focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
                if (!isShowing() || saving || importing
                        || focusManager.getFocusedWindow() != SwingUtilities.getWindowAncestor(ImageCapturePanel.this)
                        || focusManager.getFocusOwner() instanceof JTextComponent) {
                    return;
                }
                try {
                    Transferable contents = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
                    if (contents != null) {
                        getTransferHandler().importData(new TransferHandler.TransferSupport(
                                ImageCapturePanel.this, contents));
                    }
                } catch (IllegalStateException | SecurityException exception) {
                    AppMessageDialog.showError(ImageCapturePanel.this, "暂时无法读取剪贴板，请稍后重试", exception);
                }
            }
        });
    }

    /** 在拖放回调结束前提取地址，再异步读取图片，避免原生数据过期和界面卡顿。 */
    private boolean importImageAddress(TransferHandler.TransferSupport support) {
        String address = support.isDrop()
                ? ImageTransferUtil.readImageAddress(support.getTransferable()) : null;
        if (address == null) {
            System.err.println("图片导入失败，来源声明的格式：");
            for (DataFlavor flavor : support.getDataFlavors()) {
                System.err.println(flavor.getMimeType());
            }
            showUnavailableImageTransfer();
            return false;
        }
        importing = true;
        updateImageActions(capturedImage != null);
        new SwingWorker<BufferedImage, Void>() {
            @Override
            protected BufferedImage doInBackground() throws Exception {
                return ImageTransferUtil.loadImage(address);
            }

            @Override
            protected void done() {
                importing = false;
                try {
                    setCapturedImage(get());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException exception) {
                    AppMessageDialog.showError(ImageCapturePanel.this,
                            "无法读取来源图片，可能需要来源应用的登录权限，或使用了临时地址。可复制图片后按 Ctrl+V。",
                            exception.getCause());
                } finally {
                    updateImageActions(capturedImage != null);
                }
            }
        }.execute();
        return true;
    }

    /** 拖放源没有交付可读取的数据时保留原预览，并提供可用的替代操作。 */
    private void showUnavailableImageTransfer() {
        AppMessageDialog.showWarning(this,
                "来源应用未提供可读取的图片数据。请在来源应用中复制图片后按 Ctrl+V，"
                        + "或先保存为本地图片再拖入。");
    }

    /** 初始化页面布局。 */
    private void initializeLayout() {
        setLayout(new BorderLayout(0, 18));
        setBackground(theme.background());
        setBorder(new EmptyBorder(22, 26, 16, 26));

        add(createHeader(), BorderLayout.NORTH);
        add(createContent(), BorderLayout.CENTER);
    }

    /** 创建与主页一致的标题和操作区。 */
    private JPanel createHeader() {
        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setOpaque(false);

        JLabel title = createLabel(
                ApplicationInfo.APP_NAME,
                FONT_TITLE,
                theme.textPrimary()
        );
        JLabel subtitle = createLabel(
                "截图并保存屏幕上的图像，用于图像识别与脚本自动化",
                FONT_SMALL,
                theme.textSecondary()
        );

        JPanel titlePanel = new JPanel();
        titlePanel.setOpaque(false);
        titlePanel.setLayout(new BoxLayout(titlePanel, BoxLayout.Y_AXIS));
        titlePanel.add(title);
        titlePanel.add(Box.createVerticalStrut(5));
        titlePanel.add(subtitle);

        JButton backButton = createButton("返回", IconType.BACK, false);
        compactHeaderButton(backButton);
        backButton.setToolTipText("返回主页");
        backButton.addActionListener(event -> runCallback(onBack));

        JButton settingsButton = createButton("设置", IconType.SETTINGS, false);
        compactHeaderButton(settingsButton);
        settingsButton.setToolTipText("打开设置");
        settingsButton.addActionListener(event -> runCallback(onOpenSettings));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);
        actions.add(backButton);
        actions.add(settingsButton);

        header.add(titlePanel, BorderLayout.WEST);
        header.add(actions, BorderLayout.EAST);
        return header;
    }

    /** 创建预览、信息与操作按钮区域。 */
    private JPanel createContent() {
        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setOpaque(false);
        content.add(createPreviewCard(), BorderLayout.CENTER);

        JPanel bottom = new JPanel();
        bottom.setOpaque(false);
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        bottom.add(createInfoPanel());
        bottom.add(Box.createVerticalStrut(12));
        bottom.add(createButtonPanel());
        content.add(bottom, BorderLayout.SOUTH);
        return content;
    }

    /** 创建图像预览卡片。 */
    private JPanel createPreviewCard() {
        RoundedPanel card = new RoundedPanel(theme.card(), theme.border(), 16);
        card.setLayout(new BorderLayout(0, 12));
        card.setBorder(new EmptyBorder(16, 18, 16, 18));
        card.add(createLabel("图像预览", FONT_SECTION, theme.textPrimary()),
                BorderLayout.NORTH);
        card.add(previewPanel, BorderLayout.CENTER);
        return card;
    }

    /** 创建图像信息区域。 */
    private JPanel createInfoPanel() {
        RoundedPanel panel = new RoundedPanel(
                theme.targetCard(),
                theme.border(),
                12
        );
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(12, 16, 12, 16));
        panel.add(createInfoRow("文件名", fileNameValueLabel));
        panel.add(Box.createVerticalStrut(8));
        panel.add(createInfoRow("图像尺寸", imageSizeValueLabel));
        panel.add(Box.createVerticalStrut(8));
        panel.add(createInfoRow("保存状态", saveStatusValueLabel));
        return panel;
    }

    /** 创建单行图片信息。 */
    private JPanel createInfoRow(String name, JLabel valueLabel) {
        JPanel row = new JPanel(new BorderLayout(16, 0));
        row.setOpaque(false);

        JLabel nameLabel = createLabel(name, FONT_NORMAL, theme.textSecondary());
        nameLabel.setPreferredSize(new Dimension(72, 20));
        row.add(nameLabel, BorderLayout.WEST);
        row.add(valueLabel, BorderLayout.CENTER);
        return row;
    }

    /** 创建等宽的底部操作按钮。 */
    private JPanel createButtonPanel() {
        JPanel panel = new JPanel(new GridLayout(1, 4, 10, 0));
        panel.setOpaque(false);

        captureButton.addActionListener(event -> runCallback(onCaptureRequested));
        clearButton.addActionListener(event -> clearCapturedImage());
        saveButton.addActionListener(event -> saveCapturedImage());
        saveAsButton.addActionListener(event -> saveCapturedImageAs());

        panel.add(captureButton);
        panel.add(clearButton);
        panel.add(saveButton);
        panel.add(saveAsButton);
        return panel;
    }

    /** 接收区域截图结果并刷新预览信息。 */
    public void setCapturedImage(BufferedImage image) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setCapturedImage(image));
            return;
        }
        if (image == null) {
            clearCapturedImage();
            return;
        }

        capturedImage = image;
        currentSaveFile = null;
        previewPanel.setImage(image);
        captureFileName = "capture_"
                + LocalDateTime.now().format(CAPTURE_FILE_TIME)
                + ImageCaptureFormat.FILE_SUFFIX;
        fileNameValueLabel.setText(captureFileName);
        imageSizeValueLabel.setText(image.getWidth() + " × " + image.getHeight());
        saveStatusValueLabel.setText("未保存");
        saveStatusValueLabel.setForeground(theme.textSecondary());
        saveStatusValueLabel.setToolTipText(null);
        updateImageActions(true);
    }

    /** 清除当前截图并恢复空状态。 */
    public void clearCapturedImage() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::clearCapturedImage);
            return;
        }

        capturedImage = null;
        captureFileName = null;
        currentSaveFile = null;
        previewPanel.setImage(null);
        fileNameValueLabel.setText("—");
        imageSizeValueLabel.setText("—");
        saveStatusValueLabel.setText("—");
        saveStatusValueLabel.setForeground(theme.textMuted());
        saveStatusValueLabel.setToolTipText(null);
        updateImageActions(false);
    }

    /** 将当前截图保存到设置中的默认图片目录。 */
    private void saveCapturedImage() {
        if (capturedImage == null || captureFileName == null || saving) {
            return;
        }

        File targetFile = currentSaveFile;
        if (targetFile == null) {
            targetFile = new File(
                    appConfig.getDefaultScreenCaptureDirectory(),
                    captureFileName
            );
        }
        saveCapturedImageTo(targetFile);
    }

    /** 选择目标文件并将当前截图另存为 PNG。 */
    private void saveCapturedImageAs() {
        if (capturedImage == null || captureFileName == null || saving) {
            return;
        }

        File selected;
        try {
            selected = fileDialogs.showDialog(this, createImageFileOptions()).orElse(null);
        } catch (FileDialogException exception) {
            AppMessageDialog.showError(this, exception.getMessage(), exception);
            return;
        }
        if (selected == null) return;

        File targetFile = ensurePngExtension(selected);
        if (targetFile.exists() && !AppMessageDialog.showConfirm(
                this,
                "覆盖图片",
                "目标文件已经存在，是否覆盖？"
        )) {
            return;
        }

        saveCapturedImageTo(targetFile);
    }

    /** 保留 PNG 默认目录、文件名和业务层已有的覆盖确认。 */
    private FileDialogOptions createImageFileOptions() {
        File defaultDirectory = currentSaveFile == null
                ? appConfig.getDefaultScreenCaptureDirectory()
                : currentSaveFile.getParentFile();
        if (defaultDirectory == null) {
            defaultDirectory = appConfig.getDefaultScreenCaptureDirectory();
        }
        if (!defaultDirectory.exists()) {
            defaultDirectory.mkdirs();
        }

        return FileDialogOptions.builder(FileDialogOptions.Mode.SAVE_FILE)
                .title("图像另存为").initialDirectory(defaultDirectory)
                .fileType(ImageCaptureFormat.FILE_FILTER_DESCRIPTION, ImageCaptureFormat.FORMAT_NAME)
                .defaultExtension(ImageCaptureFormat.FORMAT_NAME)
                .initialFile(currentSaveFile == null ? new File(defaultDirectory, captureFileName) : currentSaveFile)
                .confirmOverwrite(false).build();
    }

    /** 将缺少扩展名的目标文件规范为 PNG 文件。 */
    private File ensurePngExtension(File file) {
        if (file.getName().toLowerCase().endsWith(ImageCaptureFormat.FILE_SUFFIX)) {
            return file;
        }
        return new File(file.getAbsolutePath() + ImageCaptureFormat.FILE_SUFFIX);
    }

    /** 在后台将当前截图保存到指定文件。 */
    private void saveCapturedImageTo(File targetFile) {
        BufferedImage imageToSave = capturedImage;
        saving = true;
        saveStatusValueLabel.setText("正在保存…");
        saveStatusValueLabel.setForeground(theme.accent());
        updateImageActions(true);

        new SwingWorker<File, Void>() {
            @Override
            protected File doInBackground() throws Exception {
                return imageCaptureStorage.savePng(imageToSave, targetFile);
            }

            @Override
            protected void done() {
                saving = false;
                try {
                    File savedFile = get();
                    currentSaveFile = savedFile;
                    captureFileName = savedFile.getName();
                    fileNameValueLabel.setText(captureFileName);
                    saveStatusValueLabel.setText("已保存");
                    saveStatusValueLabel.setForeground(theme.success());
                    saveStatusValueLabel.setToolTipText(savedFile.getAbsolutePath());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    showSaveFailure(exception);
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause() == null
                            ? exception
                            : exception.getCause();
                    showSaveFailure(cause);
                }
                updateImageActions(capturedImage != null);
            }
        }.execute();
    }

    /** 显示图片保存失败状态与统一错误提示。 */
    private void showSaveFailure(Throwable throwable) {
        saveStatusValueLabel.setText("保存失败");
        saveStatusValueLabel.setForeground(theme.danger());
        saveStatusValueLabel.setToolTipText(null);
        AppMessageDialog.showError(this, "保存图片失败", throwable);
    }

    /** 根据是否存在截图更新后续操作状态。 */
    private void updateImageActions(boolean hasImage) {
        captureButton.setEnabled(!saving && !importing);
        clearButton.setEnabled(hasImage && !saving && !importing);
        saveButton.setEnabled(hasImage && !saving && !importing);
        saveAsButton.setEnabled(hasImage && !saving && !importing);
    }

    /** 创建主题按钮。 */
    private JButton createButton(String text, IconType iconType, boolean primary) {
        JButton button = new JButton(text, new CaptureIcon(iconType));
        button.setFont(FONT_BOLD);
        if (primary) {
            ComponentStyleUtil.stylePrimaryButton(button);
        } else {
            ComponentStyleUtil.styleSecondaryButton(button);
        }
        return button;
    }

    /** 收紧标题区按钮，避免子页面扩大主窗口的首选尺寸。 */
    private void compactHeaderButton(JButton button) {
        button.setBorder(new EmptyBorder(8, 10, 8, 10));
        button.setIconTextGap(6);
    }

    /** 创建主题文字。 */
    private JLabel createLabel(String text, Font font, Color foreground) {
        JLabel label = new JLabel(text);
        label.setFont(font);
        label.setForeground(foreground);
        return label;
    }

    /** 安全执行页面回调。 */
    private void runCallback(Runnable callback) {
        if (callback != null) {
            callback.run();
        }
    }

    /** 按可用空间等比绘制截图预览。 */
    private final class ImagePreviewPanel extends JPanel {

        private BufferedImage image;

        private ImagePreviewPanel() {
            setOpaque(false);
        }

        private void setImage(BufferedImage image) {
            this.image = image;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
            );

            int inset = 1;
            int width = Math.max(0, getWidth() - inset * 2);
            int height = Math.max(0, getHeight() - inset * 2);
            g2.setColor(theme.targetCard());
            g2.fillRoundRect(inset, inset, width, height, 14, 14);
            g2.setColor(theme.border());
            g2.drawRoundRect(inset, inset, Math.max(0, width - 1),
                    Math.max(0, height - 1), 14, 14);

            Shape oldClip = g2.getClip();
            g2.clip(new RoundRectangle2D.Float(
                    inset + 8,
                    inset + 8,
                    Math.max(0, width - 16),
                    Math.max(0, height - 16),
                    10,
                    10
            ));
            int tileSize = 16;
            for (int y = inset + 8; y < getHeight() - inset - 8; y += tileSize) {
                for (int x = inset + 8; x < getWidth() - inset - 8; x += tileSize) {
                    boolean alternate = ((x - inset) / tileSize
                            + (y - inset) / tileSize) % 2 == 0;
                    g2.setColor(alternate ? theme.card() : theme.controlBackground());
                    g2.fillRect(x, y, tileSize, tileSize);
                }
            }
            g2.setClip(oldClip);

            if (image == null) {
                paintEmptyState(g2);
            } else {
                paintImage(g2, image);
            }
            g2.dispose();
        }

        /** 绘制尚未截图时的提示。 */
        private void paintEmptyState(Graphics2D g2) {
            g2.setFont(FONT_BOLD);
            g2.setColor(theme.textMuted());
            String text = "暂无图像";
            FontMetrics metrics = g2.getFontMetrics();
            int textX = (getWidth() - metrics.stringWidth(text)) / 2;
            int textY = (getHeight() - metrics.getHeight()) / 2
                    + metrics.getAscent();
            g2.drawString(text, textX, textY);
        }

        /** 将截图完整缩放到预览区域中，不改变页面首选尺寸。 */
        private void paintImage(Graphics2D g2, BufferedImage source) {
            int padding = 16;
            int availableWidth = Math.max(1, getWidth() - padding * 2);
            int availableHeight = Math.max(1, getHeight() - padding * 2);
            double scale = Math.min(
                    availableWidth / (double) source.getWidth(),
                    availableHeight / (double) source.getHeight()
            );
            int drawWidth = Math.max(1, (int) Math.round(source.getWidth() * scale));
            int drawHeight = Math.max(1, (int) Math.round(source.getHeight() * scale));
            int drawX = (getWidth() - drawWidth) / 2;
            int drawY = (getHeight() - drawHeight) / 2;

            g2.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR
            );
            g2.drawImage(source, drawX, drawY, drawWidth, drawHeight, null);
            g2.setColor(theme.border());
            g2.drawRect(drawX, drawY, drawWidth - 1, drawHeight - 1);
        }
    }

    private enum IconType {
        BACK,
        SETTINGS,
        CAPTURE,
        CLEAR,
        SAVE,
        SAVE_AS
    }

    /** 使用按钮前景色绘制的页面图标。 */
    private record CaptureIcon(IconType type) implements Icon {

        private static final int SIZE = 16;

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.translate(x, y);
            g2.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
            );
            g2.setColor(component.getForeground());
            g2.setStroke(new BasicStroke(
                    1.7f,
                    BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND
            ));

            switch (type) {
                case BACK -> {
                    g2.drawLine(3, 8, 13, 8);
                    g2.drawLine(3, 8, 7, 4);
                    g2.drawLine(3, 8, 7, 12);
                }
                case SETTINGS -> {
                    g2.drawOval(5, 5, 6, 6);
                    g2.drawLine(8, 1, 8, 4);
                    g2.drawLine(8, 12, 8, 15);
                    g2.drawLine(1, 8, 4, 8);
                    g2.drawLine(12, 8, 15, 8);
                }
                case CAPTURE -> {
                    g2.drawLine(2, 6, 2, 2);
                    g2.drawLine(2, 2, 6, 2);
                    g2.drawLine(10, 2, 14, 2);
                    g2.drawLine(14, 2, 14, 6);
                    g2.drawLine(2, 10, 2, 14);
                    g2.drawLine(2, 14, 6, 14);
                    g2.drawLine(10, 14, 14, 14);
                    g2.drawLine(14, 10, 14, 14);
                }
                case CLEAR -> {
                    g2.drawRoundRect(4, 5, 8, 9, 2, 2);
                    g2.drawLine(3, 4, 13, 4);
                    g2.drawLine(6, 2, 10, 2);
                    g2.drawLine(7, 7, 7, 12);
                    g2.drawLine(9, 7, 9, 12);
                }
                case SAVE, SAVE_AS -> {
                    g2.drawRoundRect(2, 2, 12, 12, 3, 3);
                    g2.drawRect(5, 2, 6, 4);
                    g2.drawRoundRect(5, 9, 6, 5, 2, 2);
                    if (type == IconType.SAVE_AS) {
                        g2.drawLine(10, 12, 15, 12);
                        g2.drawLine(13, 10, 15, 12);
                        g2.drawLine(13, 14, 15, 12);
                    }
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
}
