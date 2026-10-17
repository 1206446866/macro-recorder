package com.macro.ui.capture;

import com.macro.ui.theme.ThemeManager;
import com.macro.ui.theme.ThemePalette;
import com.macro.ui.util.ComponentStyleUtil;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.Objects;
import java.util.function.Consumer;

/** 显示冻结屏幕并允许拖动选择截图区域的全屏窗口。 */
public final class ScreenSelectionOverlay extends JDialog {

    private final BufferedImage screenImage;
    private final Rectangle screenBounds;
    private final Consumer<BufferedImage> onConfirmed;
    private final Runnable onCanceled;
    private final Consumer<Throwable> onFailed;
    private final SelectionCanvas canvas;
    private final KeyEventDispatcher keyEventDispatcher =
            this::dispatchCaptureKeyEvent;

    private boolean closed;
    private boolean keyDispatcherInstalled;

    public ScreenSelectionOverlay(
            Window owner,
            BufferedImage screenImage,
            Rectangle screenBounds,
            GraphicsConfiguration configuration,
            Consumer<BufferedImage> onConfirmed,
            Runnable onCanceled,
            Consumer<Throwable> onFailed
    ) {
        super(
                Objects.requireNonNull(owner, "所属窗口不能为空"),
                "",
                Dialog.ModalityType.MODELESS,
                Objects.requireNonNull(configuration, "屏幕配置不能为空")
        );
        this.screenImage = Objects.requireNonNull(screenImage, "屏幕图像不能为空");
        this.screenBounds = new Rectangle(
                Objects.requireNonNull(screenBounds, "屏幕范围不能为空")
        );
        this.onConfirmed = Objects.requireNonNull(onConfirmed, "完成回调不能为空");
        this.onCanceled = Objects.requireNonNull(onCanceled, "取消回调不能为空");
        this.onFailed = Objects.requireNonNull(onFailed, "异常回调不能为空");

        canvas = new SelectionCanvas();
        setUndecorated(true);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setContentPane(canvas);
        setBounds(this.screenBounds);
        setAlwaysOnTop(true);
        setFocusableWindowState(true);
        setAutoRequestFocus(true);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                cancelSelection();
            }
        });
        installKeyboardActions();
    }

    /** 显示选区窗口并获取键盘焦点。 */
    public void showOverlay() {
        installKeyDispatcher();
        setVisible(true);
        toFront();
        requestFocus();
        canvas.requestFocusInWindow();
        EventQueue.invokeLater(() -> {
            if (!closed && isShowing()) {
                toFront();
                requestFocus();
                canvas.requestFocusInWindow();
            }
        });
    }

    /** 由控制器统一关闭选区窗口。 */
    public void closeOverlay() {
        uninstallKeyDispatcher();
        if (isDisplayable()) {
            dispose();
        }
    }

    /** 安装取消和完成快捷键。 */
    private void installKeyboardActions() {
        JRootPane rootPane = getRootPane();
        InputMap inputMap = rootPane.getInputMap(
                JComponent.WHEN_IN_FOCUSED_WINDOW
        );
        ActionMap actionMap = rootPane.getActionMap();

        inputMap.put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                "cancelCapture"
        );
        inputMap.put(
                KeyStroke.getKeyStroke(KeyEvent.VK_F4, InputEvent.ALT_DOWN_MASK),
                "cancelCapture"
        );
        inputMap.put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
                "confirmCapture"
        );
        actionMap.put("cancelCapture", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                cancelSelection();
            }
        });
        actionMap.put("confirmCapture", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                confirmSelection();
            }
        });
    }

    /** 注册应用级按键分发器，确保焦点落在所属窗口时仍可取消截图。 */
    private void installKeyDispatcher() {
        if (keyDispatcherInstalled) {
            return;
        }
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .addKeyEventDispatcher(keyEventDispatcher);
        keyDispatcherInstalled = true;
    }

    /** 移除应用级按键分发器，避免影响截图结束后的键盘操作。 */
    private void uninstallKeyDispatcher() {
        if (!keyDispatcherInstalled) {
            return;
        }
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .removeKeyEventDispatcher(keyEventDispatcher);
        keyDispatcherInstalled = false;
    }

    /** 在选区窗口和所属窗口之间统一处理截图快捷键。 */
    private boolean dispatchCaptureKeyEvent(KeyEvent event) {
        if (closed || event.getID() != KeyEvent.KEY_PRESSED) {
            return false;
        }
        if (event.getKeyCode() == KeyEvent.VK_ESCAPE) {
            cancelSelection();
            return true;
        }
        if (event.getKeyCode() == KeyEvent.VK_ENTER) {
            confirmSelection();
            return true;
        }
        if (event.getKeyCode() == KeyEvent.VK_F4 && event.isAltDown()) {
            cancelSelection();
            return true;
        }
        return false;
    }

    /** 取消截图，确保回调只执行一次。 */
    private void cancelSelection() {
        if (closed) {
            return;
        }
        closed = true;
        onCanceled.run();
    }

    /** 裁剪当前选区并完成截图。 */
    private void confirmSelection() {
        if (closed || !canvas.hasValidSelection()) {
            return;
        }

        try {
            BufferedImage selectedImage = canvas.cropSelection();
            closed = true;
            onConfirmed.accept(selectedImage);
        } catch (RuntimeException exception) {
            closed = true;
            onFailed.accept(exception);
        }
    }

    /** 截图选区画布。 */
    private final class SelectionCanvas extends JPanel {

        private static final int MINIMUM_SELECTION_SIZE = 2;
        private static final int TOOLBAR_GAP = 8;

        private final ThemePalette theme = ThemeManager.getPalette();
        private final JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));

        private Point dragStart;
        private Rectangle selection;
        private boolean dragging;

        private SelectionCanvas() {
            setLayout(null);
            setOpaque(true);
            setBackground(theme.background());
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            setFocusable(true);

            JButton cancelButton = new JButton("取消");
            ComponentStyleUtil.styleSecondaryButton(cancelButton);
            cancelButton.addActionListener(event -> cancelSelection());

            JButton completeButton = new JButton("完成");
            ComponentStyleUtil.stylePrimaryButton(completeButton);
            completeButton.addActionListener(event -> confirmSelection());

            toolbar.setOpaque(false);
            toolbar.add(cancelButton);
            toolbar.add(completeButton);
            toolbar.setVisible(false);
            add(toolbar);

            SelectionMouseHandler mouseHandler = new SelectionMouseHandler();
            addMouseListener(mouseHandler);
            addMouseMotionListener(mouseHandler);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
            );

            drawScreenImage(g2);
            drawDarkMask(g2);
            drawUsageHint(g2);

            if (hasValidSelection()) {
                drawSelection(g2);
            }
            g2.dispose();
        }

        /** 绘制适配当前屏幕逻辑尺寸的冻结画面。 */
        private void drawScreenImage(Graphics2D graphics) {
            graphics.drawImage(
                    screenImage,
                    0,
                    0,
                    getWidth(),
                    getHeight(),
                    null
            );
        }

        /** 使用当前主题背景绘制半透明暗幕。 */
        private void drawDarkMask(Graphics2D graphics) {
            Composite oldComposite = graphics.getComposite();
            graphics.setComposite(AlphaComposite.SrcOver.derive(0.58f));
            graphics.setColor(theme.background());
            graphics.fillRect(0, 0, getWidth(), getHeight());
            graphics.setComposite(oldComposite);
        }

        /** 绘制操作提示。 */
        private void drawUsageHint(Graphics2D graphics) {
            String hint = "拖动鼠标选择区域 · 双击或 Enter 完成 · Esc 或右键取消";
            graphics.setFont(getFont().deriveFont(Font.PLAIN, 13f));
            FontMetrics metrics = graphics.getFontMetrics();
            int horizontalPadding = 14;
            int hintWidth = metrics.stringWidth(hint) + horizontalPadding * 2;
            int hintHeight = metrics.getHeight() + 12;
            int hintX = Math.max(12, (getWidth() - hintWidth) / 2);
            int hintY = 14;

            Composite oldComposite = graphics.getComposite();
            graphics.setComposite(AlphaComposite.SrcOver.derive(0.94f));
            graphics.setColor(theme.card());
            graphics.fillRoundRect(hintX, hintY, hintWidth, hintHeight, 12, 12);
            graphics.setComposite(oldComposite);
            graphics.setColor(theme.textPrimary());
            graphics.drawString(
                    hint,
                    hintX + horizontalPadding,
                    hintY + 6 + metrics.getAscent()
            );
        }

        /** 重绘原始选区、边框及最终像素尺寸。 */
        private void drawSelection(Graphics2D graphics) {
            Shape oldClip = graphics.getClip();
            graphics.clip(selection);
            drawScreenImage(graphics);
            graphics.setClip(oldClip);

            graphics.setColor(theme.accent());
            graphics.setStroke(new BasicStroke(2f));
            graphics.drawRect(
                    selection.x,
                    selection.y,
                    Math.max(0, selection.width - 1),
                    Math.max(0, selection.height - 1)
            );
            drawSelectionHandles(graphics);
            drawSelectionSize(graphics);
        }

        /** 绘制选区四角与边中点控制柄。 */
        private void drawSelectionHandles(Graphics2D graphics) {
            int handleSize = 6;
            int half = handleSize / 2;
            int left = selection.x;
            int centerX = selection.x + selection.width / 2;
            int right = selection.x + selection.width;
            int top = selection.y;
            int centerY = selection.y + selection.height / 2;
            int bottom = selection.y + selection.height;
            int[][] points = {
                    {left, top}, {centerX, top}, {right, top},
                    {left, centerY}, {right, centerY},
                    {left, bottom}, {centerX, bottom}, {right, bottom}
            };

            graphics.setColor(theme.accent());
            for (int[] point : points) {
                graphics.fillRect(
                        point[0] - half,
                        point[1] - half,
                        handleSize,
                        handleSize
                );
            }
        }

        /** 按实际截图像素比例显示当前选区尺寸。 */
        private void drawSelectionSize(Graphics2D graphics) {
            Rectangle sourceBounds = sourceSelectionBounds();
            String sizeText = sourceBounds.width + " × " + sourceBounds.height;
            graphics.setFont(getFont().deriveFont(Font.PLAIN, 12f));
            FontMetrics metrics = graphics.getFontMetrics();
            int labelWidth = metrics.stringWidth(sizeText) + 16;
            int labelHeight = metrics.getHeight() + 8;
            int labelX = selection.x;
            int labelY = selection.y - labelHeight - 6;
            if (labelY < 4) {
                labelY = Math.min(
                        getHeight() - labelHeight - 4,
                        selection.y + 6
                );
            }
            labelX = Math.max(4, Math.min(labelX, getWidth() - labelWidth - 4));

            Composite oldComposite = graphics.getComposite();
            graphics.setComposite(AlphaComposite.SrcOver.derive(0.95f));
            graphics.setColor(theme.card());
            graphics.fillRoundRect(
                    labelX,
                    labelY,
                    labelWidth,
                    labelHeight,
                    8,
                    8
            );
            graphics.setComposite(oldComposite);
            graphics.setColor(theme.textPrimary());
            graphics.drawString(
                    sizeText,
                    labelX + 8,
                    labelY + 4 + metrics.getAscent()
            );
        }

        /** 依据实际截图与覆盖窗口比例裁剪选区。 */
        private BufferedImage cropSelection() {
            Rectangle sourceBounds = sourceSelectionBounds();

            BufferedImage cropped = new BufferedImage(
                    sourceBounds.width,
                    sourceBounds.height,
                    BufferedImage.TYPE_INT_ARGB
            );
            Graphics2D graphics = cropped.createGraphics();
            graphics.drawImage(
                    screenImage,
                    0,
                    0,
                    cropped.getWidth(),
                    cropped.getHeight(),
                    sourceBounds.x,
                    sourceBounds.y,
                    sourceBounds.x + sourceBounds.width,
                    sourceBounds.y + sourceBounds.height,
                    null
            );
            graphics.dispose();
            return cropped;
        }

        /** 计算当前逻辑选区在原始截图中的像素范围。 */
        private Rectangle sourceSelectionBounds() {
            double scaleX = imageScaleX();
            double scaleY = imageScaleY();
            int sourceX1 = clamp(
                    (int) Math.floor(selection.x * scaleX),
                    0,
                    screenImage.getWidth() - 1
            );
            int sourceY1 = clamp(
                    (int) Math.floor(selection.y * scaleY),
                    0,
                    screenImage.getHeight() - 1
            );
            int sourceX2 = clamp(
                    (int) Math.ceil((selection.x + selection.width) * scaleX),
                    sourceX1 + 1,
                    screenImage.getWidth()
            );
            int sourceY2 = clamp(
                    (int) Math.ceil((selection.y + selection.height) * scaleY),
                    sourceY1 + 1,
                    screenImage.getHeight()
            );
            return new Rectangle(
                    sourceX1,
                    sourceY1,
                    sourceX2 - sourceX1,
                    sourceY2 - sourceY1
            );
        }

        private double imageScaleX() {
            return screenImage.getWidth() / (double) Math.max(1, getWidth());
        }

        private double imageScaleY() {
            return screenImage.getHeight() / (double) Math.max(1, getHeight());
        }

        private boolean hasValidSelection() {
            return selection != null
                    && selection.width >= MINIMUM_SELECTION_SIZE
                    && selection.height >= MINIMUM_SELECTION_SIZE;
        }

        /** 根据选区位置显示操作按钮，确保按钮不会超出屏幕。 */
        private void updateToolbarBounds() {
            boolean visible = hasValidSelection() && !dragging;
            toolbar.setVisible(visible);
            if (!visible) {
                return;
            }

            Dimension preferred = toolbar.getPreferredSize();
            int toolbarX = selection.x + selection.width - preferred.width;
            int toolbarY = selection.y + selection.height + TOOLBAR_GAP;
            if (toolbarY + preferred.height > getHeight() - 4) {
                toolbarY = selection.y - preferred.height - TOOLBAR_GAP;
            }
            toolbarX = clamp(toolbarX, 4, Math.max(4, getWidth() - preferred.width - 4));
            toolbarY = clamp(toolbarY, 4, Math.max(4, getHeight() - preferred.height - 4));
            toolbar.setBounds(
                    toolbarX,
                    toolbarY,
                    preferred.width,
                    preferred.height
            );
        }

        /** 将拖动端点转换为窗口范围内的规范矩形。 */
        private Rectangle createSelection(Point start, Point end) {
            int startX = clamp(start.x, 0, getWidth());
            int startY = clamp(start.y, 0, getHeight());
            int endX = clamp(end.x, 0, getWidth());
            int endY = clamp(end.y, 0, getHeight());
            return new Rectangle(
                    Math.min(startX, endX),
                    Math.min(startY, endY),
                    Math.abs(endX - startX),
                    Math.abs(endY - startY)
            );
        }

        /** 选区鼠标交互。 */
        private final class SelectionMouseHandler extends MouseAdapter {

            @Override
            public void mousePressed(MouseEvent event) {
                if (SwingUtilities.isRightMouseButton(event)) {
                    cancelSelection();
                    return;
                }
                if (!SwingUtilities.isLeftMouseButton(event)) {
                    return;
                }
                if (event.getClickCount() >= 2
                        && hasValidSelection()
                        && selection.contains(event.getPoint())) {
                    confirmSelection();
                    return;
                }
                if (hasValidSelection() && selection.contains(event.getPoint())) {
                    return;
                }

                dragStart = event.getPoint();
                selection = new Rectangle(dragStart);
                dragging = true;
                toolbar.setVisible(false);
                repaint();
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (!dragging || dragStart == null) {
                    return;
                }
                selection = createSelection(dragStart, event.getPoint());
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                if (!dragging || !SwingUtilities.isLeftMouseButton(event)) {
                    return;
                }
                selection = createSelection(dragStart, event.getPoint());
                dragging = false;
                dragStart = null;
                updateToolbarBounds();
                repaint();
            }

            @Override
            public void mouseClicked(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event)
                        && event.getClickCount() == 2
                        && hasValidSelection()
                        && selection.contains(event.getPoint())) {
                    confirmSelection();
                }
            }
        }
    }

    /** 限制整数范围。 */
    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }
}
