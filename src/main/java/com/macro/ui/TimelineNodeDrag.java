package com.macro.ui;

import com.macro.flow.MacroFlow;
import com.macro.flow.MacroFlowFactory;
import com.macro.flow.MacroNode;
import com.macro.ui.theme.ThemeManager;

import javax.swing.*;
import javax.swing.plaf.LayerUI;
import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.util.List;

/** 一次拖动只保留卡片快照和预览位置；释放时才提交顺序，所有退出路径移除临时监听。 */
final class TimelineNodeDrag extends LayerUI<JScrollPane> {
    private static final int THRESHOLD = 6;
    private static final int EDGE = 36;
    private final ActionTimelinePanel timeline;
    private final JPanel track;
    private final JScrollPane scroll;
    private final JLayer<JScrollPane> layer;
    private final Timer scrollTimer;
    private final AWTEventListener listener = this::handleEvent;
    private MacroNode source;
    private List<MacroNode> group = List.of();
    private int sourceRank;
    private JComponent sourceCard;
    private MacroFlow initialFlow;
    private Point press, pointer, offset;
    private int[] centers;
    private int targetIndex;
    private boolean dragging, validDrop, suppressClick;
    private BufferedImage ghost;
    private Cursor previousCursor;

    TimelineNodeDrag(ActionTimelinePanel timeline, JPanel track, JScrollPane scroll) {
        this.timeline = timeline;
        this.track = track;
        this.scroll = scroll;
        layer = new JLayer<>(scroll, this);
        scrollTimer = new Timer(40, event -> scrollAtEdge());
    }

    JLayer<JScrollPane> layer() { return layer; }
    boolean isActive() { return dragging; }
    boolean isDragging(MacroNode node) { return dragging && group.contains(node); }
    boolean suppressesClick() { return suppressClick; }
    int targetIndex() { return targetIndex; }
    int previewIndex(MacroNode node) { return targetIndex + Math.max(0, group.indexOf(node)); }

    /** 卡片内容共用按下入口；坐标编辑器不接此监听，因此保持单击编辑语义。 */
    void arm(MacroNode node, JComponent card, MouseEvent event) {
        cancel(true);
        suppressClick = false;
        if (!timeline.isEditingAllowed() || !SwingUtilities.isLeftMouseButton(event)
                || event.isPopupTrigger() || event.getClickCount() != 1) return;
        List<MacroNode> nodes = MacroFlowFactory.timelineNodes(timeline.getFlow());
        int index = -1;
        for (int i = 0; i < nodes.size(); i++) if (nodes.get(i) == node) { index = i; break; }
        if (index < 0 || nodes.size() < 2) return;
        source = node;
        group = timeline.getSelectedNodes();
        if (!group.contains(node)) group = List.of(node);
        sourceRank = group.indexOf(node);
        sourceCard = card;
        initialFlow = timeline.getFlow();
        targetIndex = nodes.indexOf(group.get(0));
        press = SwingUtilities.convertPoint(event.getComponent(), event.getPoint(), layer);
        pointer = new Point(press);
        offset = SwingUtilities.convertPoint(event.getComponent(), event.getPoint(), card);
        // 固定原布局的槽位中点，避免占位卡移动后反复改变判定边界产生抖动。
        centers = new int[nodes.size()];
        int slot = 0;
        for (Component component : track.getComponents()) {
            if (component.getClass() == card.getClass()) centers[slot++] = component.getX() + component.getWidth() / 2;
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(listener,
                AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK
                        | AWTEvent.KEY_EVENT_MASK | AWTEvent.WINDOW_EVENT_MASK);
    }

    private void handleEvent(AWTEvent event) {
        if (source == null) return;
        if (!timeline.isEditingAllowed() || timeline.getFlow() != initialFlow) { cancel(true); return; }
        if (event instanceof KeyEvent key && key.getID() == KeyEvent.KEY_PRESSED
                && key.getKeyCode() == KeyEvent.VK_ESCAPE) {
            cancel(true);
            key.consume();
        } else if (event instanceof WindowEvent window
                && window.getID() == WindowEvent.WINDOW_DEACTIVATED
                && window.getWindow() == SwingUtilities.getWindowAncestor(layer)) {
            cancel(true);
        } else if (event instanceof MouseEvent mouse) {
            if (mouse.getID() == MouseEvent.MOUSE_DRAGGED) {
                pointer = SwingUtilities.convertPoint(mouse.getComponent(), mouse.getPoint(), layer);
                if (!dragging && press.distance(pointer) >= THRESHOLD) beginDrag();
                if (dragging) { updatePreview(); mouse.consume(); }
            } else if (mouse.getID() == MouseEvent.MOUSE_RELEASED && SwingUtilities.isLeftMouseButton(mouse)) {
                pointer = SwingUtilities.convertPoint(mouse.getComponent(), mouse.getPoint(), layer);
                if (!dragging) { cancel(false); return; }
                updatePreview();
                List<MacroNode> moving = group;
                int index = targetIndex;
                boolean commit = validDrop;
                cancel(false);
                if (!commit || !timeline.moveNodes(moving, index)) timeline.restoreNodeOrder();
                mouse.consume();
            } else if (mouse.getID() == MouseEvent.MOUSE_PRESSED && dragging) {
                cancel(true);
                mouse.consume();
            }
        }
    }

    private void beginDrag() {
        timeline.prepareNodeDrag();
        // 依照实际 DPI 生成一次离屏快照；移动过程中只绘制缓存影像。
        GraphicsConfiguration configuration = layer.getGraphicsConfiguration();
        double scale = configuration == null ? 1 : configuration.getDefaultTransform().getScaleX();
        BufferedImage image = new BufferedImage(Math.max(1, (int) Math.ceil(sourceCard.getWidth() * scale)),
                Math.max(1, (int) Math.ceil(sourceCard.getHeight() * scale)), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try { graphics.scale(scale, scale); sourceCard.printAll(graphics); }
        finally { graphics.dispose(); }
        float[] blur = {1f/16, 2f/16, 1f/16, 2f/16, 4f/16, 2f/16, 1f/16, 2f/16, 1f/16};
        ghost = new ConvolveOp(new Kernel(3, 3, blur), ConvolveOp.EDGE_NO_OP, null).filter(image, null);
        dragging = true;
        suppressClick = true;
        previousCursor = layer.getCursor();
        layer.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
        scrollTimer.start();
        sourceCard.repaint();
        if (group.size() > 1) {
            timeline.previewNodesMove(group, targetIndex);
            track.doLayout();
        }
    }

    private Rectangle viewportBounds() {
        JViewport viewport = scroll.getViewport();
        return SwingUtilities.convertRectangle(viewport.getParent(), viewport.getBounds(), layer);
    }

    private void updatePreview() {
        validDrop = viewportBounds().contains(pointer);
        if (validDrop) {
            Point onTrack = SwingUtilities.convertPoint(layer, pointer, track);
            int center = onTrack.x - offset.x + sourceCard.getWidth() / 2;
            int index = 0;
            while (index + 1 < centers.length && center > ((long) centers[index] + centers[index + 1]) / 2) index++;
            index = Math.max(0, Math.min(centers.length - group.size(), index - sourceRank));
            if (index != targetIndex) {
                targetIndex = index;
                timeline.previewNodesMove(group, index);
                track.doLayout();
            }
        }
        layer.repaint();
    }

    /** 靠近视口边缘时缓慢滚动；鼠标停住也继续刷新落点，不触碰流程模型。 */
    private void scrollAtEdge() {
        if (!dragging || !timeline.isEditingAllowed() || timeline.getFlow() != initialFlow) { cancel(true); return; }
        Rectangle viewport = viewportBounds();
        if (!viewport.contains(pointer)) return;
        int left = pointer.x - viewport.x, right = viewport.x + viewport.width - 1 - pointer.x;
        int delta = left < EDGE ? -Math.max(4, (EDGE - left) / 2)
                : right < EDGE ? Math.max(4, (EDGE - right) / 2) : 0;
        if (delta == 0) return;
        JScrollBar bar = scroll.getHorizontalScrollBar();
        bar.setValue(bar.getValue() + delta);
        updatePreview();
    }

    /** 可由播放锁定、隐藏、重建和 Esc 调用；取消不写模型，也不发送流程变更通知。 */
    void cancel(boolean restorePreview) {
        if (source == null) return;
        boolean hadPreview = dragging;
        scrollTimer.stop();
        Toolkit.getDefaultToolkit().removeAWTEventListener(listener);
        source = null;
        group = List.of();
        sourceCard = null;
        initialFlow = null;
        ghost = null;
        centers = null;
        dragging = false;
        validDrop = false;
        if (hadPreview) layer.setCursor(previousCursor);
        previousCursor = null;
        if (restorePreview && hadPreview) timeline.restoreNodeOrder();
        layer.repaint();
    }

    @Override public void paint(Graphics graphics, JComponent component) {
        super.paint(graphics, component);
        if (!dragging || ghost == null) return;
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setComposite(AlphaComposite.SrcOver.derive(validDrop ? .67f : .38f));
            g2.drawImage(ghost, pointer.x - offset.x, pointer.y - offset.y,
                    sourceCard.getWidth(), sourceCard.getHeight(), null);
            if (group.size() > 1) {
                g2.setComposite(AlphaComposite.SrcOver);
                g2.setFont(new Font(com.macro.constant.UiConstants.DEFAULT_FONT_FAMILY, Font.BOLD, 12));
                String text = group.size() + " 个节点";
                int width = g2.getFontMetrics().stringWidth(text) + 20;
                int x = pointer.x - offset.x + sourceCard.getWidth() - width - 6, y = pointer.y - offset.y + 7;
                g2.setColor(ThemeManager.getPalette().accent()); g2.fillRoundRect(x, y, width, 25, 12, 12);
                g2.setColor(ThemeManager.getPalette().textOnAccent()); g2.drawString(text, x + 10, y + 17);
            }
            if (validDrop) {
                JComponent first = timeline.nodeCard(group.get(0));
                Point slot = SwingUtilities.convertPoint(first, 0, 0, layer);
                g2.setComposite(AlphaComposite.SrcOver);
                g2.setColor(ThemeManager.getPalette().accent());
                g2.fillRoundRect(slot.x - 7, slot.y, 3, sourceCard.getHeight(), 3, 3);
            }
        } finally { g2.dispose(); }
    }
}
