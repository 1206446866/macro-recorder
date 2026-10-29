package com.macro.ui;

import com.macro.config.TimelineInsertPosition;
import com.macro.constant.UiConstants;
import com.macro.flow.BranchCondition;
import com.macro.flow.ImageCheckConfig;
import com.macro.flow.ImageCheckBehavior;
import com.macro.flow.MacroFlow;
import com.macro.flow.MacroFlowFactory;
import com.macro.flow.MacroNode;
import com.macro.flow.NodeType;
import com.macro.model.ActionType;
import com.macro.model.MacroAction;
import com.macro.model.MouseButton;
import com.macro.ui.dialog.AppMessageDialog;
import com.macro.ui.theme.ThemeManager;
import com.macro.ui.theme.ThemePalette;
import com.macro.ui.util.ComponentStyleUtil;
import com.macro.ui.util.NumericInputUtil;
import lombok.Getter;
import lombok.Setter;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicMenuItemUI;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.AWTEventListener;
import java.awt.event.HierarchyEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 支持横向滚动的流程节点时间线。
 *
 * <p>面板展示普通动作和图像识别节点，并负责选择、行内编辑及默认分支重建；
 * 弹窗编辑和主窗口状态同步仍通过回调交给外层窗口处理。</p>
 */
public class ActionTimelinePanel extends JPanel {

    private static final ThemePalette THEME = ThemeManager.getPalette();
    private static final int NODE_WIDTH = 158;
    private static final int NODE_HEIGHT = 176;
    private static final int MIN_CONNECTOR_WIDTH = 72;
    private static final int SELF_LOOP_SIDE_EXTENSION = 28;
    private static final int SELF_LOOP_BOTTOM_OFFSET = 31;
    private static final int TIMELINE_VIEWPORT_WIDTH = 420;
    private static final int TIMELINE_VIEWPORT_HEIGHT = NODE_HEIGHT + 90;
    private static final Color TEMPLATE_WARNING_COLOR = new Color(0xF2C94C);

    private final JPanel timelinePanel;
    private final TimelineNodeDrag nodeDrag;
    private final TimelineNodeSearch nodeSearch;
    private long searchNavigationRevision;
    private final JLabel actionCountValue;
    private final JLabel totalDurationValue;
    private final JLabel timelineEditHint = label("拖动卡片调整顺序，双击编辑，单击坐标修改", 12, false, THEME.textMuted());
    private final JCheckBox standbyHeadCheckBox = new JCheckBox("增加待机头节点");
    @Setter
    private Predicate<Boolean> onStandbyHeadToggle = enabled -> true;
    @Setter
    private Runnable onFlowChanged = () -> {};
    /** 按时间线显示顺序保存的可见节点，不包含内部 END 节点。 */
    private final List<MacroNode> nodes = new ArrayList<>();
    private java.util.Map<String, String> displayNumbers = java.util.Map.of();
    private final Map<String, NodePanel> nodePanels = new LinkedHashMap<>();
    private final TemplateQualityWarnings templateQualityWarnings = new TemplateQualityWarnings();
    private Supplier<File> templateBaseDirectorySupplier = () -> null;
    private final Set<String> completedNodeIds = new LinkedHashSet<>();
    @Getter
    private String currentPlaybackNodeId;
    @Getter
    private int playbackLoop;
    private boolean headPlaybackCurrent;
    private boolean headPlaybackCompleted;
    private JComponent standbyHeadCard;

    /** 当前时间线对应的完整流程图，是节点编辑和播放的权威数据。
     * -- GETTER --
     * 返回包含 END 节点和全部分支的当前流程图。
     */
    @Getter
    private MacroFlow flow = MacroFlowFactory.fromActions(List.of());

    @Getter
    private MacroNode selectedMacroNode;
    private NodePanel selectedNode;
    private final Set<String> selectedNodeIds = new LinkedHashSet<>();
    private String selectionAnchorId;
    private final JPanel selectionToolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
    private final JLabel selectionCount = label("", 12, true, THEME.accent());
    private final JButton batchEditButton = new JButton("批量编辑");
    private final JButton batchDeleteButton = new JButton("删除所选");
    /** 当前行内编辑会话；重建时间线前先取消，避免旧组件延迟提交。 */
    private InlineEditSession activeEdit;
    private long timelineRevision;

    /** 实际播放状态由主窗口提供；本地开关只补足播放线程启动及收尾的边界。 */
    private BooleanSupplier editPermission = () -> true;
    private boolean editingEnabled = true;
    private long editGeneration;
    private final TimelineUndoHistory undoHistory = new TimelineUndoHistory();
    private final TimelineNodeClipboard nodeClipboard;

    @Setter
    private Consumer<MacroNode> onActionSelected;
    @Setter
    private Consumer<MacroNode> onActionDoubleClicked;
    @Setter
    private Consumer<MacroNode> onActionEditRequested;
    @Setter
    private Consumer<MacroNode> onActionInsertRequested;
    @Setter
    private Consumer<MacroNode> onActionDeleteRequested;

    /** 创建时间线统计、头部待机开关和可滚动轨道，安装选择、编辑与删除入口。 */
    public ActionTimelinePanel() {
        this(() -> Toolkit.getDefaultToolkit().getSystemClipboard());
    }

    /** 隔离测试使用独立剪贴板，不改动系统内容。 */
    ActionTimelinePanel(Clipboard clipboard) { this(() -> clipboard); }

    private ActionTimelinePanel(Supplier<Clipboard> clipboardSupplier) {
        nodeClipboard = new TimelineNodeClipboard(clipboardSupplier);
        setLayout(new BorderLayout(0, 18));
        setOpaque(false);
        setFocusable(true);
        installDeleteKeyBinding();
        installUndoKeyBinding();
        installNodeClipboardKeyBindings();
        installSelectionKeyBindings();
        installSearchKeyBindings();
        nodeSearch = new TimelineNodeSearch(this::repaintSearchResults, this::scrollToSearchResult, this::closeNodeSearch);
        timelineEditHint.setToolTipText("Ctrl 点选，Shift 连选，Ctrl+A 全选；Ctrl+Z 撤销最近一次修改");

        JPanel heading = new JPanel(new BorderLayout(0, 12));
        heading.setOpaque(false);

        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        JLabel title = label("动作时间线", 28, true, THEME.textPrimary());
        JLabel description = label("按时间顺序查看和管理录制的鼠标与键盘动作", 14, false, THEME.textSecondary());
        title.setAlignmentX(LEFT_ALIGNMENT);
        description.setAlignmentX(LEFT_ALIGNMENT);
        titles.add(title);
        titles.add(Box.createVerticalStrut(7));
        titles.add(description);
        heading.add(titles, BorderLayout.NORTH);

        JPanel stats = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        stats.setOpaque(false);
        actionCountValue = label("0 个", 19, true, THEME.textPrimary());
        totalDurationValue = label("0 ms", 19, true, THEME.textPrimary());
        stats.add(createStatCard(IconType.MOUSE, "动作数量", actionCountValue, 116));
        stats.add(createStatCard(IconType.CLOCK, "总时长", totalDurationValue, 148));
        JPanel toolbar = new JPanel(new BorderLayout(12, 0));
        toolbar.setOpaque(false);
        ComponentStyleUtil.styleCheckBox(standbyHeadCheckBox);
        standbyHeadCheckBox.setToolTipText("开头增加待机，单击连线上的时间修改延迟");
        standbyHeadCheckBox.addActionListener(event -> {
            boolean enabled = standbyHeadCheckBox.isSelected();
            if (!isEditingAllowed() || !onStandbyHeadToggle.test(enabled)
                    || !setStandbyHeadEnabled(enabled)) {
                standbyHeadCheckBox.setSelected(flow.getHeadWaitMillis() != null);
            }
        });
        toolbar.add(standbyHeadCheckBox, BorderLayout.WEST);
        toolbar.add(stats, BorderLayout.EAST);
        heading.add(toolbar, BorderLayout.SOUTH);
        add(heading, BorderLayout.NORTH);

        RoundedPanel card = new RoundedPanel(THEME.card(), THEME.border(), 18);
        card.setLayout(new BorderLayout());
        card.setBorder(new EmptyBorder(16, 18, 18, 18));

        JPanel hintPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        hintPanel.setOpaque(false);
        hintPanel.add(new TimelineIcon(IconType.INFO, 16, THEME.textMuted()));
        hintPanel.add(timelineEditHint);
        JPanel trackHeader = new JPanel(new BorderLayout());
        trackHeader.setOpaque(false);
        JPanel trackTools = new JPanel(new BorderLayout(12, 0)); trackTools.setOpaque(false);
        JButton findNodesButton = new JButton("查找节点");
        ComponentStyleUtil.styleSecondaryButton(findNodesButton);
        findNodesButton.setToolTipText("查找节点（Ctrl+F）");
        findNodesButton.addActionListener(event -> openNodeSearch());
        trackTools.add(findNodesButton, BorderLayout.WEST);
        trackTools.add(hintPanel, BorderLayout.CENTER);
        trackHeader.add(trackTools, BorderLayout.NORTH);
        selectionToolbar.setOpaque(false);
        selectionToolbar.add(selectionCount);
        for (JButton button : List.of(batchEditButton, batchDeleteButton)) {
            ComponentStyleUtil.styleSecondaryButton(button);
            selectionToolbar.add(button);
        }
        JButton clearSelectionButton = new JButton("取消选择");
        ComponentStyleUtil.styleSecondaryButton(clearSelectionButton);
        clearSelectionButton.addActionListener(event -> clearSelection());
        selectionToolbar.add(clearSelectionButton);
        batchEditButton.addActionListener(event -> requestBatchEdit());
        batchDeleteButton.addActionListener(event -> requestBatchDelete());
        selectionToolbar.setVisible(false);
        JPanel trackBars = new JPanel(); trackBars.setOpaque(false);
        trackBars.setLayout(new BoxLayout(trackBars, BoxLayout.Y_AXIS));
        trackBars.add(nodeSearch); trackBars.add(selectionToolbar);
        trackHeader.add(trackBars, BorderLayout.SOUTH);
        card.add(trackHeader, BorderLayout.NORTH);

        timelinePanel = new TimelineTrackPanel();
        timelinePanel.setOpaque(false);
        timelinePanel.setLayout(new BoxLayout(timelinePanel, BoxLayout.X_AXIS));
        // 回路下方另留一行终止标记，避免重试与终止同时配置时相互覆盖。
        timelinePanel.setBorder(new EmptyBorder(10, 38, 80, 38));

        JScrollPane scrollPane = new JScrollPane(
                timelinePanel,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        );
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        ComponentStyleUtil.styleScrollPane(scrollPane);
        scrollPane.getHorizontalScrollBar().setUnitIncrement(20);
        nodeDrag = new TimelineNodeDrag(this, timelinePanel, scrollPane);
        card.add(nodeDrag.layer(), BorderLayout.CENTER);
        add(card, BorderLayout.CENTER);

        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                if (isShowing()) refreshTemplateQualityWarnings();
                else {
                    cancelInlineEdit();
                    nodeDrag.cancel(true);
                }
            }
        });

        rebuildTimeline();
    }

    /** 把旧版普通动作列表转换为流程图，再通过统一入口重建时间线。 */
    public void setActions(List<MacroAction> newActions) {
        setFlow(MacroFlowFactory.fromActions(newActions));
    }

    /** 使用与编辑器和播放器相同的 MR 目录解析相对模板路径。 */
    public void setTemplateBaseDirectorySupplier(Supplier<File> supplier) {
        templateBaseDirectorySupplier = Objects.requireNonNull(supplier);
        refreshTemplateQualityWarnings();
    }

    private void refreshTemplateQualityWarnings() {
        Map<String, String> paths = new LinkedHashMap<>();
        for (MacroNode node : nodes) {
            if (node.getType() == NodeType.IMAGE_CHECK) {
                ImageCheckConfig config = node.getImageCheckConfig();
                paths.put(node.getId(), config == null ? null : config.getImagePath());
            }
        }
        templateQualityWarnings.refresh(paths, templateBaseDirectorySupplier.get(), (id, warning) -> {
            NodePanel card = nodePanels.get(id);
            if (card != null) card.setTemplateQualityWarning(warning);
        });
    }

    /** 每次写入时重新读取权限，不依赖异步状态通知是否已到达 EDT。 */
    public void setEditPermission(BooleanSupplier permission) {
        editPermission = Objects.requireNonNull(permission);
        updateEditingControls();
        if (!isEditingAllowed()) {
            editGeneration++;
            cancelInlineEdit();
            nodeDrag.cancel(true);
        }
    }

    /** 只限制编辑，不禁用面板、节点选择、滚动或复制。 */
    public void setEditingEnabled(boolean enabled) {
        if (editingEnabled != enabled) {
            editingEnabled = enabled;
            editGeneration++;
        }
        if (!enabled) {
            cancelInlineEdit();
            nodeDrag.cancel(true);
        }
        updateEditingControls();
    }

    /** 专用头部显示，不插入真实操作、不重编号或改写分支引用。 */
    public boolean setStandbyHeadEnabled(boolean enabled) {
        if (!isEditingAllowed()) return false;
        boolean currentlyEnabled = flow.getHeadWaitMillis() != null;
        if (enabled != currentlyEnabled) {
            var before = captureUndoState();
            flow.setHeadWaitMillis(enabled ? MacroFlow.DEFAULT_HEAD_WAIT_MILLIS : null);
            rebuildTimeline();
            undoHistory.record(before, flow);
            onFlowChanged.run();
        }
        standbyHeadCheckBox.setSelected(enabled);
        return true;
    }

    /** 同时检查面板开关和外部播放录制权限，供所有修改入口共用。 */
    public boolean isEditingAllowed() {
        return editingEnabled && editPermission.getAsBoolean();
    }

    /** 按当前权限更新待机开关和编辑提示，播放查看状态仍允许选择及复制。 */
    private void updateEditingControls() {
        boolean allowed = isEditingAllowed();
        standbyHeadCheckBox.setEnabled(allowed);
        timelineEditHint.setText(allowed ? "Ctrl 点选 / Shift 连选 · 拖动排序 · 双击编辑" : "当前仅可查看，可选择或复制；编辑已锁定");
        batchEditButton.setEnabled(allowed && hasBatchEditableSelection());
        batchDeleteButton.setEnabled(allowed);
    }

    /** 弹窗及确认操作跨越播放边界后，即使已经恢复空闲，也不再接受旧结果。 */
    public <T> Consumer<T> guardEditCallback(Consumer<T> callback) {
        long generation = editGeneration;
        return value -> {
            if (generation == editGeneration && isEditingAllowed()) callback.accept(value);
        };
    }

    /**
     * 载入完整流程图并按 nodeMap 稳定顺序重建时间线。
     *
     * @param newFlow 待显示的流程图；为空时使用空流程
     */
    public void setFlow(MacroFlow newFlow) {
        if (newFlow != flow) {
            clearUndoHistory();
            nodeSearch.reset();
            searchNavigationRevision++;
        }
        displayFlow(newFlow);
    }

    private void displayFlow(MacroFlow newFlow) {
        nodeDrag.cancel(false);
        if (newFlow != flow) clearPlaybackProgress();
        flow = newFlow == null
                ? MacroFlowFactory.fromActions(List.of())
                : newFlow;
        nodes.clear();
        nodes.addAll(MacroFlowFactory.timelineNodes(flow));
        selectedMacroNode = null;
        selectedNode = null;
        selectedNodeIds.clear();
        selectionAnchorId = null;
        rebuildTimeline();
    }

    private TimelineUndoHistory.Snapshot captureUndoState() {
        return undoHistory.capture(flow, selectedMacroNode == null ? null : selectedMacroNode.getId(),
                new ArrayList<>(selectedNodeIds), selectionAnchorId);
    }

    public boolean canUndo() { return isEditingAllowed() && undoHistory.peek() != null; }

    public boolean canPasteNode() { return isEditingAllowed() && nodeClipboard.hasNode(); }

    /** 复制确认时的独立快照；播放查看期间也可复制当前节点。 */
    boolean copyNode(MacroNode node) throws IOException {
        if (node == null || nodes.stream().noneMatch(current -> current == node)) return false;
        nodeClipboard.copy(node, templateBaseDirectorySupplier.get(), getNodeName(node) + " · " + getNodeDetail(node));
        return true;
    }

    /** 选中节点后粘贴，没有锚点时追加；一次粘贴对应一步撤销。 */
    boolean pasteNode(MacroNode anchor) throws IOException {
        if (!isEditingAllowed()) return false;
        int index = nodes.size();
        if (anchor != null) {
            index = -1;
            for (int i = 0; i < nodes.size(); i++) if (nodes.get(i) == anchor) { index = i + 1; break; }
            if (index < 0) return false;
        }
        List<MacroNode> pastedNodes = nodeClipboard.readNodes(templateBaseDirectorySupplier.get());
        if (pastedNodes.isEmpty()) return false;
        Map<String, String> newIds = new LinkedHashMap<>();
        for (MacroNode pasted : pastedNodes) newIds.put(pasted.getId(), MacroFlowFactory.newNodeId());
        for (MacroNode pasted : pastedNodes) if (pasted.getType() == NodeType.IMAGE_CHECK) {
            for (BranchCondition result : List.of(BranchCondition.FOUND, BranchCondition.NOT_FOUND)) {
                if (pasted.getImageCheckConfig().behaviorFor(result == BranchCondition.FOUND) != ImageCheckBehavior.JUMP) continue;
                var branch = MacroFlowFactory.branchFor(pasted, result);
                MacroNode target = branch == null ? null : flow.getNodeMap().get(branch.getTargetId());
                boolean internal = pastedNodes.size() > 1 && branch != null && newIds.containsKey(branch.getTargetId());
                if (!internal && (target == null || target.getType() == NodeType.END))
                    throw new IllegalArgumentException("原跳转目标不在当前宏中，无法粘贴该图像节点。请先在来源宏中调整跳转行为。");
                if (internal) branch.setTargetId(newIds.get(branch.getTargetId()));
            }
        }
        var before = captureUndoState();
        selectedNodeIds.clear();
        for (MacroNode pasted : pastedNodes) {
            pasted.setId(newIds.get(pasted.getId()));
            selectedNodeIds.add(pasted.getId());
        }
        nodes.addAll(index, pastedNodes);
        selectedMacroNode = pastedNodes.get(pastedNodes.size() - 1);
        selectionAnchorId = pastedNodes.get(0).getId();
        rebuildFlowGraph();
        rebuildTimeline();
        undoHistory.record(before, flow);
        onFlowChanged.run();
        return true;
    }

    private void requestNodeClipboardAction(MacroNode node, boolean copy) {
        try {
            if (copy && node != null && selectedNodeIds.contains(node.getId()) && selectedNodeIds.size() > 1) {
                List<MacroNode> selection = getSelectedNodes();
                nodeClipboard.copy(selection, templateBaseDirectorySupplier.get(), String.join(System.lineSeparator(),
                        selection.stream().map(current -> getNodeName(current) + " · " + getNodeDetail(current)).toList()));
            } else if (copy) copyNode(node); else pasteNode(node);
        }
        catch (IOException | IllegalArgumentException | IllegalStateException exception) {
            AppMessageDialog.showWarning(this, (copy ? "复制节点失败：" : "粘贴节点失败：") + exception.getMessage(), exception);
        }
    }

    /** 切换宏或改变模板路径基准时丢弃旧记录，不能撤回到其他文件上下文。 */
    public void clearUndoHistory() {
        undoHistory.clear();
        editGeneration++;
    }

    /** 恢复完整流程与选择，按 ID 保留分支关系；旧编辑器和确认回调立即失效。 */
    public boolean undo() {
        if (!canUndo()) return false;
        var snapshot = undoHistory.peek();
        MacroFlow previous = undoHistory.restore(snapshot);
        boolean previousHead = previous.getHeadWaitMillis() != null;
        if (previousHead != (flow.getHeadWaitMillis() != null)
                && !onStandbyHeadToggle.test(previousHead)) return false;
        if (!isEditingAllowed()) return false;
        cancelInlineEdit();
        nodeDrag.cancel(true);
        undoHistory.removeLast();
        editGeneration++;
        displayFlow(previous);
        selectedNodeIds.addAll(snapshot.selectedIds());
        selectionAnchorId = snapshot.anchorId();
        selectedMacroNode = previous.getNodeMap().get(snapshot.selectedId());
        updateSelection();
        onFlowChanged.run();
        requestFocusInWindow();
        return true;
    }

    /** 返回已完成节点 ID 的只读快照，避免外部直接修改进度状态。 */
    public Set<String> getCompletedNodeIds() {
        return Set.copyOf(completedNodeIds);
    }

    /** 仅刷新状态确实变化的卡片，不重建布局、节点或连线。 */
    void applyPlaybackProgress(PlaybackProgressBuffer.Batch batch) {
        Set<String> changed = new LinkedHashSet<>();
        if (batch.reset()) {
            changed.addAll(completedNodeIds);
            completedNodeIds.clear();
        }
        for (String id : batch.completed()) {
            if (nodePanels.containsKey(id) && completedNodeIds.add(id)) changed.add(id);
        }
        String next = nodePanels.containsKey(batch.current()) ? batch.current() : null;
        if (!Objects.equals(currentPlaybackNodeId, next)) {
            if (currentPlaybackNodeId != null) changed.add(currentPlaybackNodeId);
            if (next != null) changed.add(next);
            currentPlaybackNodeId = next;
        }
        playbackLoop = batch.loop();
        if (headPlaybackCurrent != batch.headCurrent() || headPlaybackCompleted != batch.headCompleted()) {
            headPlaybackCurrent = batch.headCurrent();
            headPlaybackCompleted = batch.headCompleted();
            if (standbyHeadCard != null) standbyHeadCard.repaint();
        }
        for (String id : changed) {
            NodePanel card = nodePanels.get(id);
            if (card != null) card.repaint();
        }
    }

    /** 清除当前与已完成标记，并复用批量进度入口只重绘受影响卡片。 */
    public void clearPlaybackProgress() {
        applyPlaybackProgress(new PlaybackProgressBuffer.Batch(0, true, Set.of(), null, false, false));
    }

    /** 暂停后才定位当前卡片，播放中不持续移动用户的滚动位置。 */
    public void scrollToPlaybackNode() {
        JComponent card = headPlaybackCurrent ? standbyHeadCard : nodePanels.get(currentPlaybackNodeId);
        if (card != null) card.scrollRectToVisible(new Rectangle(0, 0, card.getWidth(), card.getHeight()));
    }

    /** 提取 ACTION 节点中的动作，供旧 MR actions 字段兼容使用。 */
    public List<MacroAction> getActions() {
        return MacroFlowFactory.extractActions(flow);
    }

    /** 重建当前节点视图后恢复仍存在的选择，不改变流程执行顺序。 */
    public void refreshTimeline() {
        rebuildTimeline();
    }

    /** 仅在允许编辑时删除目标节点，按用户选项把普通动作延迟保留给后项，再重建流程和视图。 */
    public boolean deleteAction(int index, boolean preserveDelay) {
        if (!isEditingAllowed() || index < 0 || index >= nodes.size()) {
            return false;
        }
        MacroNode node = nodes.get(index);
        var before = captureUndoState();
        if (preserveDelay
                && node.getType() == NodeType.ACTION
                && index < nodes.size() - 1) {
            MacroNode nextNode = nodes.get(index + 1);
            if (nextNode.getType() == NodeType.ACTION) {
                MacroAction action = node.getAction();
                MacroAction nextAction = nextNode.getAction();
                nextAction.setDelay(nextAction.getDelay() + action.getDelay());
            }
        }
        nodes.remove(index);
        if (selectedMacroNode == node) {
            selectedMacroNode = null;
            selectedNode = null;
        }
        rebuildFlowGraph();
        rebuildTimeline();
        undoHistory.record(before, flow);
        return true;
    }

    /** 仅在允许编辑时删除目标节点，按用户选项把普通动作延迟保留给后项，再重建流程和视图。 */
    public boolean deleteAction(MacroNode node, boolean preserveDelay) {
        return deleteAction(nodes.indexOf(node), preserveDelay);
    }

    /**
     * 在指定节点之前插入新节点并重建默认分支。
     *
     * @param anchorNode 作为插入位置的节点
     * @param newNode 要插入的新节点
     * @return 是否成功插入
     */
    public boolean insertActionBefore(MacroNode anchorNode, MacroNode newNode) {
        int anchorIndex = nodes.indexOf(anchorNode);
        if (anchorIndex < 0 || newNode == null) {
            return false;
        }
        return insertAt(anchorIndex, newNode);
    }

    /** 按显式锚点或当前选择应用默认方向；无选择时追加，过期锚点不插入。 */
    public boolean insertAction(MacroNode anchorNode, MacroNode newNode, TimelineInsertPosition position) {
        if (newNode == null) return false;
        MacroNode anchor = anchorNode == null ? selectedMacroNode : anchorNode;
        if (anchor == null) return insertAt(nodes.size(), newNode);
        int anchorIndex = nodes.indexOf(anchor);
        if (anchorIndex < 0) return false;
        return insertAt(anchorIndex + (position == TimelineInsertPosition.AFTER ? 1 : 0), newNode);
    }

    /** 明确指定向后插入，不受设置中的默认方向影响。 */
    public boolean insertActionAfter(MacroNode anchorNode, MacroNode newNode) {
        int anchorIndex = nodes.indexOf(anchorNode);
        return anchorIndex >= 0 && newNode != null && insertAt(anchorIndex + 1, newNode);
    }

    /** 为新节点分配稳定 ID，在指定位置插入并重建分支视图，最后选中新节点。 */
    private boolean insertAt(int index, MacroNode newNode) {
        if (!isEditingAllowed()) return false;
        var before = captureUndoState();
        newNode.setId(MacroFlowFactory.newNodeId());
        nodes.add(index, newNode);
        selectedMacroNode = newNode;
        selectedNode = null;
        rebuildFlowGraph();
        rebuildTimeline();
        selectMacroNode(newNode);
        undoHistory.record(before, flow);
        return true;
    }

    /**
     * 使用新节点替换指定节点，并保留节点 ID 与时间线位置。
     *
     * @param originalNode 原节点
     * @param newNode 新节点
     * @return 是否成功替换
     */
    public boolean replaceAction(MacroNode originalNode, MacroNode newNode) {
        int actionIndex = nodes.indexOf(originalNode);
        if (!isEditingAllowed() || actionIndex < 0 || newNode == null) {
            return false;
        }

        var before = captureUndoState();
        newNode.setId(originalNode.getId());
        nodes.set(actionIndex, newNode);
        selectedMacroNode = newNode;
        selectedNode = null;
        rebuildFlowGraph();
        rebuildTimeline();
        selectMacroNode(newNode);
        undoHistory.record(before, flow);
        return true;
    }

    /** 移到最终的零基位置，保留节点身份、动作延迟和指定跳转；仅重接默认后继。 */
    public boolean moveNode(MacroNode node, int targetIndex) {
        return node != null && moveNodes(List.of(node), targetIndex);
    }

    /** 所选节点按原顺序聚为一组；位置是移除该组后剩余节点中的插入位置。 */
    boolean moveNodes(List<MacroNode> targets, int targetIndex) {
        if (!isEditingAllowed() || !validTargets(targets) || targetIndex < 0
                || targetIndex > nodes.size() - targets.size()) return false;
        List<MacroNode> group = nodes.stream().filter(targets::contains).toList();
        List<MacroNode> order = new ArrayList<>(nodes);
        order.removeAll(group);
        order.addAll(targetIndex, group);
        if (order.equals(nodes)) return false;
        var before = captureUndoState();
        nodeDrag.cancel(false);
        nodes.clear();
        nodes.addAll(order);
        selectedNodeIds.clear();
        group.forEach(node -> selectedNodeIds.add(node.getId()));
        if (selectedMacroNode == null || !selectedNodeIds.contains(selectedMacroNode.getId())) selectedMacroNode = group.get(0);
        selectionAnchorId = selectedMacroNode.getId();
        rebuildFlowGraph();
        rebuildTimeline();
        undoHistory.record(before, flow);
        onFlowChanged.run();
        return true;
    }

    /** 拖动只改变组件排列，不提前写入流程、编号或分支。 */
    void previewNodeMove(MacroNode node, int targetIndex) {
        previewNodesMove(List.of(node), targetIndex);
    }

    void previewNodesMove(List<MacroNode> group, int targetIndex) {
        List<MacroNode> preview = new ArrayList<>(nodes);
        preview.removeAll(group);
        preview.addAll(targetIndex, group);
        renderNodeOrder(preview);
    }

    /** 返回时间线顺序的只读选择；主选中节点仍供单节点编辑、插入及粘贴定位。 */
    public List<MacroNode> getSelectedNodes() {
        return nodes.stream().filter(node -> selectedNodeIds.contains(node.getId())).toList();
    }

    JComponent nodeCard(MacroNode node) { return nodePanels.get(node.getId()); }

    private boolean validTargets(List<MacroNode> targets) {
        return targets != null && !targets.isEmpty() && targets.size() == targets.stream().distinct().count()
                && targets.stream().allMatch(target -> nodes.stream().anyMatch(node -> node == target));
    }

    /** 只写入明确勾选的字段；图像重试间隔仅应用于具有重试行为的节点。 */
    boolean applyBatchEdits(List<MacroNode> targets, Long actionDelay, Long retryDelay) {
        if (!isEditingAllowed() || !validTargets(targets)) return false;
        if (actionDelay != null && actionDelay < 0 || retryDelay != null && retryDelay < 0)
            throw new IllegalArgumentException("延迟不能小于 0");
        var before = captureUndoState();
        boolean changed = false;
        for (MacroNode node : targets) {
            if (actionDelay != null && node.getType() == NodeType.ACTION && node.getAction().getDelay() != actionDelay) {
                node.getAction().setDelay(actionDelay);
                changed = true;
            }
            if (retryDelay != null && hasRetry(node) && MacroFlowFactory.findRetryDelay(node) != retryDelay) {
                node.getImageCheckConfig().setRetryDelay(retryDelay);
                changed = true;
            }
        }
        if (!changed) return false;
        rebuildFlowGraph();
        rebuildTimeline();
        undoHistory.record(before, flow);
        onFlowChanged.run();
        return true;
    }

    private boolean hasRetry(MacroNode node) {
        return node.getType() == NodeType.IMAGE_CHECK && (!behaviorConditions(node, ImageCheckBehavior.RETRY).isEmpty());
    }

    private boolean hasBatchEditableSelection() {
        return getSelectedNodes().stream().anyMatch(node -> node.getType() == NodeType.ACTION || hasRetry(node));
    }

    /** 整批删除只记一次撤销；指定跳转保留原目标 ID，目标被删时由现有校验报告。 */
    boolean deleteNodes(List<MacroNode> targets) {
        if (!isEditingAllowed() || !validTargets(targets)) return false;
        var before = captureUndoState();
        nodes.removeAll(targets);
        rebuildFlowGraph();
        rebuildTimeline();
        undoHistory.record(before, flow);
        onFlowChanged.run();
        return true;
    }

    private void requestBatchEdit() {
        if (!isEditingAllowed()) return;
        List<MacroNode> targets = getSelectedNodes();
        if (targets.size() < 2 || !hasBatchEditableSelection()) return;
        long revision = timelineRevision;
        Consumer<TimelineBatchEditDialog.Values> apply = guardEditCallback(values -> {
            if (revision == timelineRevision) applyBatchEdits(targets, values.actionDelay(), values.retryDelay());
        });
        TimelineBatchEditDialog dialog = new TimelineBatchEditDialog(SwingUtilities.getWindowAncestor(this),
                targets.size(), (int) targets.stream().filter(node -> node.getType() == NodeType.ACTION).count(),
                (int) targets.stream().filter(this::hasRetry).count(), apply);
        dialog.setVisible(true);
    }

    private void requestBatchDelete() {
        if (!isEditingAllowed()) return;
        List<MacroNode> targets = getSelectedNodes();
        if (targets.size() < 2) return;
        long revision = timelineRevision;
        Set<String> ids = targets.stream().map(MacroNode::getId).collect(java.util.stream.Collectors.toSet());
        long incoming = nodes.stream().filter(node -> !ids.contains(node.getId()) && node.getType() == NodeType.IMAGE_CHECK)
                .flatMap(node -> node.getChildren().stream().filter(branch ->
                        (branch.getCondition() == BranchCondition.FOUND || branch.getCondition() == BranchCondition.NOT_FOUND)
                        && node.getImageCheckConfig().behaviorFor(branch.getCondition() == BranchCondition.FOUND) == ImageCheckBehavior.JUMP))
                .filter(branch -> ids.contains(branch.getTargetId())).count();
        Consumer<Boolean> confirm = guardEditCallback(confirmed -> {
            if (confirmed && revision == timelineRevision) deleteNodes(targets);
        });
        confirm.accept(AppMessageDialog.showConfirm(this, "批量删除", "确定删除选中的 " + targets.size()
                + " 个节点吗？\n不合并动作延迟，可按 Ctrl+Z 撤销。" + (incoming == 0 ? "" : "\n其他图像节点指向这些节点的跳转将失效，请重新指定目标。")));
    }

    void restoreNodeOrder() { renderNodeOrder(nodes); }

    void prepareNodeDrag() { cancelInlineEdit(); requestFocusInWindow(); }

    /** 按当前显示顺序重建 ACTION、IMAGE_CHECK 和 END 的默认分支。 */
    private void rebuildFlowGraph() {
        flow = MacroFlowFactory.rebuildLinear(
                nodes,
                MacroFlowFactory.findEndId(flow),
                flow.getHeadWaitMillis()
        );
    }

    /** 取消旧行内编辑，按当前节点顺序重建卡片、编号和连接器，并刷新轨道尺寸及统计。 */
    private void rebuildTimeline() {
        nodeDrag.cancel(false);
        cancelInlineEdit();
        timelineRevision++;
        timelinePanel.removeAll();
        nodePanels.clear();
        standbyHeadCard = null;
        standbyHeadCheckBox.setSelected(flow.getHeadWaitMillis() != null);
        updateEditingControls();
        displayNumbers = TimelineNodeNumbers.labels(nodes);
        completedNodeIds.retainAll(displayNumbers.keySet());
        if (!displayNumbers.containsKey(currentPlaybackNodeId)) currentPlaybackNodeId = null;
        int branchCount = jumpEdges().size();
        timelinePanel.setBorder(new EmptyBorder(10, 50, branchCount == 0 ? 80 : 110 + branchCount * 32, 50));
        updateSummary();

        renderNodeOrder(nodes);
        updateSelection();
        nodeSearch.refresh(nodes);
        refreshTemplateQualityWarnings();
    }

    /** 正常刷新和拖动预览共用卡片对象；连接器依据预览顺序显示后项延迟。 */
    private void renderNodeOrder(List<MacroNode> order) {
        timelinePanel.removeAll();
        if (flow.getHeadWaitMillis() != null) {
            if (standbyHeadCard == null) standbyHeadCard = createStandbyHead();
            timelinePanel.add(standbyHeadCard);
            if (!order.isEmpty()) timelinePanel.add(createStandbyHeadConnector());
        }

        if (order.isEmpty()) {
            JPanel empty = new JPanel();
            empty.setOpaque(false);
            empty.setLayout(new BoxLayout(empty, BoxLayout.Y_AXIS));
            TimelineIcon icon = new TimelineIcon(IconType.EMPTY, 30, THEME.textMuted());
            icon.setAlignmentX(CENTER_ALIGNMENT);
            JLabel message = label("暂无动作，完成录制后将在这里显示时间线", 13, false, THEME.textMuted());
            message.setAlignmentX(CENTER_ALIGNMENT);
            empty.add(Box.createVerticalGlue());
            empty.add(icon);
            empty.add(Box.createVerticalStrut(10));
            empty.add(message);
            empty.add(Box.createVerticalGlue());
            timelinePanel.add(Box.createHorizontalGlue());
            timelinePanel.add(empty);
            timelinePanel.add(Box.createHorizontalGlue());
        } else {
            timelinePanel.add(Box.createHorizontalStrut(4));
            for (int index = 0; index < order.size(); index++) {
                MacroNode node = order.get(index);
                NodePanel card = nodePanels.get(node.getId());
                if (card == null) card = createActionNode(node, displayNumbers.get(node.getId()));
                timelinePanel.add(card);
                if (index < order.size() - 1) {
                    // 延迟属于后一个动作，表示当前节点与后一个节点之间的等待时间。
                    timelinePanel.add(createDelayConnector(node, order.get(index + 1)));
                } else if (node.getType() == NodeType.IMAGE_CHECK
                        && !behaviorConditions(node, ImageCheckBehavior.EXECUTE).isEmpty()) {
                    timelinePanel.add(createDelayConnector(node, null));
                }
            }
            timelinePanel.add(Box.createHorizontalStrut(4));
        }

        timelinePanel.revalidate();
        timelinePanel.repaint();
    }

    /** 把统计图标、标题和值组合为固定宽度的信息卡片。 */
    private JComponent createStatCard(
            IconType iconType,
            String caption,
            JLabel value,
            int width
    ) {
        RoundedPanel card = new RoundedPanel(THEME.targetCard(), THEME.border(), 14);
        card.setLayout(new BorderLayout(9, 0));
        card.setBorder(new EmptyBorder(10, 12, 10, 13));
        card.setPreferredSize(new Dimension(width, 58));
        card.add(new TimelineIcon(iconType, 24, THEME.accent()), BorderLayout.WEST);

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        JLabel captionLabel = label(caption, 11, false, THEME.textSecondary());
        captionLabel.setAlignmentX(LEFT_ALIGNMENT);
        value.setAlignmentX(LEFT_ALIGNMENT);
        text.add(captionLabel);
        text.add(Box.createVerticalStrut(2));
        text.add(value);
        card.add(text, BorderLayout.CENTER);
        return card;
    }

    /** 创建只用于显示的头部待机卡片，不向真实流程插入额外节点。 */
    private JComponent createStandbyHead() {
        RoundedPanel card = new RoundedPanel(THEME.targetCard(), THEME.border(), 16) {
            /** 先绘制待机卡片背景，再叠加头部待机的播放状态标记。 */
            @Override protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                paintPlaybackDecoration(graphics, getWidth(), getHeight(), headPlaybackCompleted, headPlaybackCurrent, THEME);
            }
        };
        standbyHeadCard = card;
        card.setName("standbyHead");
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        Dimension size = new Dimension(NODE_WIDTH, NODE_HEIGHT);
        card.setPreferredSize(size);
        card.setMinimumSize(size);
        card.setMaximumSize(size);
        card.setAlignmentY(Component.TOP_ALIGNMENT);
        card.add(Box.createVerticalGlue());
        TimelineIcon icon = new TimelineIcon(IconType.CLOCK, 26, THEME.textSecondary());
        icon.setAlignmentX(CENTER_ALIGNMENT);
        card.add(icon);
        card.add(Box.createVerticalStrut(8));
        JLabel title = label("待机", 14, true, THEME.textPrimary());
        title.setAlignmentX(CENTER_ALIGNMENT);
        card.add(title);
        card.add(Box.createVerticalGlue());
        return card;
    }

    /** 创建头部待机到首节点的延迟连线，编辑结果只更新流程头部等待值。 */
    private JComponent createStandbyHeadConnector() {
        JPanel connector = new JPanel();
        connector.setName("standbyHeadConnector");
        connector.setOpaque(false);
        connector.setLayout(new BoxLayout(connector, BoxLayout.Y_AXIS));
        connector.setAlignmentY(Component.TOP_ALIGNMENT);
        JLabel delay = label(formatDelay(flow.getHeadWaitMillis()), 11, false, THEME.textSecondary());
        delay.setName("standbyHeadDelay");
        delay.setAlignmentX(CENTER_ALIGNMENT);
        delay.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        delay.setToolTipText("单击编辑待机延迟");
        Dimension size = new Dimension(Math.max(MIN_CONNECTOR_WIDTH, delay.getPreferredSize().width + 16), NODE_HEIGHT);
        connector.setPreferredSize(size);
        connector.setMinimumSize(size);
        connector.setMaximumSize(size);
        connector.add(Box.createVerticalGlue());
        connector.add(delay);
        connector.add(Box.createVerticalStrut(5));
        ArrowComponent arrow = new ArrowComponent();
        arrow.setAlignmentX(CENTER_ALIGNMENT);
        connector.add(arrow);
        connector.add(Box.createVerticalGlue());
        delay.addMouseListener(new MouseAdapter() {
            /** 允许编辑时点击待机延迟文字，打开现有行内数字编辑。 */
            @Override public void mouseClicked(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event) && flow.getHeadWaitMillis() != null) {
                    beginDelayEdit(connector, delay, arrow, () -> flow.getHeadWaitMillis(), value -> {
                        flow.setHeadWaitMillis(value);
                        onFlowChanged.run();
                    }, "待机延迟（ms）");
                }
            }
        });
        return connector;
    }

    /** 使用节点真实身份创建操作卡，加入动态编号、参数和选择编辑事件。 */
    private NodePanel createActionNode(MacroNode macroNode, String displayNumber) {
        NodePanel node = new NodePanel();
        node.macroNode = macroNode;
        node.displayNumber = displayNumber;
        node.setPreferredSize(new Dimension(NODE_WIDTH, NODE_HEIGHT));
        node.setMinimumSize(new Dimension(NODE_WIDTH, NODE_HEIGHT));
        node.setMaximumSize(new Dimension(NODE_WIDTH, NODE_HEIGHT));
        node.setAlignmentY(Component.TOP_ALIGNMENT);
        node.setLayout(new BoxLayout(node, BoxLayout.Y_AXIS));
        node.setBorder(new EmptyBorder(15, 14, 13, 14));

        IconType iconType = iconFor(macroNode);
        TimelineIcon icon = new TimelineIcon(iconType, 26, THEME.textPrimary());
        icon.setAlignmentX(CENTER_ALIGNMENT);
        JLabel name = label(getNodeName(macroNode), 14, true, THEME.textPrimary());
        name.setAlignmentX(CENTER_ALIGNMENT);

        node.add(icon);
        node.add(Box.createVerticalStrut(8));
        node.add(name);
        node.add(Box.createVerticalStrut(11));
        addActionDetails(node, macroNode);
        node.add(Box.createVerticalGlue());

        MouseAdapter listener = new MouseAdapter() {
            private boolean collapseOnRelease;
            /** 左键选中当前节点，并兼容在按下阶段触发的系统右键菜单。 */
            @Override
            public void mousePressed(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event)) {
                    collapseOnRelease = !event.isControlDown() && !event.isShiftDown()
                            && selectedNodeIds.contains(macroNode.getId()) && selectedNodeIds.size() > 1;
                    selectWithModifiers(macroNode, event.isControlDown(), event.isShiftDown(), true);
                    if (!event.isControlDown() && !event.isShiftDown()) nodeDrag.arm(macroNode, node, event);
                }
                maybeShowPopup(event, node, macroNode);
            }

            /** 兼容在释放阶段触发的系统右键菜单。 */
            @Override
            public void mouseReleased(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event) && collapseOnRelease && !nodeDrag.suppressesClick())
                    selectNode(node, macroNode);
                collapseOnRelease = false;
                maybeShowPopup(event, node, macroNode);
            }

            /** 双击有效操作卡且允许编辑时，通知主窗口打开该节点编辑器。 */
            @Override
            public void mouseClicked(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event)
                        && !nodeDrag.suppressesClick()
                        && event.getClickCount() == 2
                        && isEditingAllowed()
                        && onActionDoubleClicked != null) {
                    onActionDoubleClicked.accept(macroNode);
                }
            }
        };
        node.addMouseListener(listener);
        installPopupListener(node, listener);

        if (selectedNodeIds.contains(macroNode.getId())) {
            node.setSelected(true);
            if (macroNode == selectedMacroNode) selectedNode = node;
        }
        nodePanels.put(macroNode.getId(), node);
        return node;
    }

    /** 按图像检查或普通动作类型添加对应参数，鼠标坐标沿用现有行内编辑控件。 */
    private void addActionDetails(JPanel node, MacroNode macroNode) {
        if (macroNode.getType() == NodeType.IMAGE_CHECK) {
            ImageCheckConfig config = macroNode.getImageCheckConfig();
            String fileName = config == null || config.getImagePath() == null
                    ? "未选择模板"
                    : new File(config.getImagePath()).getName();
            JLabel template = createPill(fileName);
            template.setToolTipText(config == null ? null : config.getImagePath());
            template.setAlignmentX(CENTER_ALIGNMENT);
            node.add(template);
            node.add(Box.createVerticalStrut(9));
            int threshold = config == null
                    ? 0
                    : (int) Math.round(config.getThreshold() * 100.0);
            JLabel thresholdLabel = createPill("阈值 " + threshold + "%");
            thresholdLabel.setAlignmentX(CENTER_ALIGNMENT);
            node.add(thresholdLabel);
            return;
        }

        MacroAction action = macroNode.getAction();
        ActionType type = action.getType();
        if (isMouseAction(type)) {
            if (type != ActionType.MOUSE_MOVE) {
                JLabel button = createPill(getMouseDetail(action));
                button.setAlignmentX(CENTER_ALIGNMENT);
                node.add(button);
                node.add(Box.createVerticalStrut(9));
            }
            JPanel coordinates = new JPanel(new GridLayout(1, 2, 7, 0));
            coordinates.setOpaque(false);
            coordinates.setAlignmentX(CENTER_ALIGNMENT);
            coordinates.setMaximumSize(new Dimension(NODE_WIDTH - 28, 31));
            CoordinateEditor xEditor = new CoordinateEditor("X", action.getX(), action::setX);
            CoordinateEditor yEditor = new CoordinateEditor("Y", action.getY(), action::setY);
            coordinates.add(xEditor);
            coordinates.add(yEditor);
            node.add(coordinates);
        } else {
            JLabel detail = createPill(getActionDetail(action));
            detail.setAlignmentX(CENTER_ALIGNMENT);
            node.add(detail);
        }
    }

    /** 合并具有相同行为的条件，供主链、回路和终止标记统一使用。 */
    private static String behaviorConditions(MacroNode node, ImageCheckBehavior behavior) {
        ImageCheckConfig config = node.getImageCheckConfig();
        boolean success = (config == null ? ImageCheckBehavior.EXECUTE
                : config.getSuccessBehavior().flowBehavior()) == behavior;
        boolean failure = (config == null ? ImageCheckBehavior.RETRY
                : config.getFailureBehavior().flowBehavior()) == behavior;
        return success && failure ? "成功 / 未找到"
                : success ? "识别成功" : failure ? "未找到" : "";
    }

    /** 直接读取有效分支引用；指定相邻目标也走独立跳转轨道。 */
    private List<TimelineBranchLayout.Edge> jumpEdges() {
        List<TimelineBranchLayout.Edge> result = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            MacroNode node = nodes.get(i);
            if (node.getType() != NodeType.IMAGE_CHECK || node.getImageCheckConfig() == null) continue;
            for (BranchCondition condition : List.of(BranchCondition.FOUND, BranchCondition.NOT_FOUND)) {
                ImageCheckBehavior behavior = condition == BranchCondition.FOUND
                        ? node.getImageCheckConfig().getSuccessBehavior() : node.getImageCheckConfig().getFailureBehavior();
                behavior = behavior.flowBehavior();
                if (behavior != ImageCheckBehavior.JUMP && behavior != ImageCheckBehavior.EXECUTE) continue;
                var branch = MacroFlowFactory.branchFor(node, condition);
                if (branch == null) continue;
                String target = branch.getTargetId();
                int targetIndex = -1;
                for (int j = 0; j < nodes.size(); j++) if (Objects.equals(nodes.get(j).getId(), target)) { targetIndex = j; break; }
                if (targetIndex < 0 || targetIndex == i || behavior == ImageCheckBehavior.EXECUTE && targetIndex == i + 1) continue;
                boolean forward = targetIndex > i;
                if (nodeDrag.isActive()) {
                    NodePanel sourceCard = nodePanels.get(node.getId()), targetCard = nodePanels.get(target);
                    if (sourceCard != null && targetCard != null) forward = targetCard.getX() > sourceCard.getX();
                }
                result.add(new TimelineBranchLayout.Edge(node.getId(), target,
                        (condition == BranchCondition.FOUND ? "成功" : "未找到") + " · "
                                + (forward ? "前跳" : "回跳") + " " + displayNumbers.get(target)));
            }
        }
        return result;
    }

    /** 判断指定结果是否沿默认后继进入给定节点，用于决定主链连接是否应显示。 */
    private static boolean defaultBranchTo(MacroNode node, BranchCondition condition, String targetId) {
        if (node.getImageCheckConfig() == null) return false;
        ImageCheckBehavior behavior = condition == BranchCondition.FOUND
                ? node.getImageCheckConfig().getSuccessBehavior() : node.getImageCheckConfig().getFailureBehavior();
        var branch = MacroFlowFactory.branchFor(node, condition);
        return behavior.flowBehavior() == ImageCheckBehavior.EXECUTE && branch != null && Objects.equals(branch.getTargetId(), targetId);
    }

    /** 创建相邻节点的等待文字和水平箭头，图像分支仅对实际默认后继显示主链。 */
    private JComponent createDelayConnector(MacroNode previousNode, MacroNode macroNode) {
        JPanel connector = new JPanel();
        connector.setOpaque(false);
        connector.setLayout(new BoxLayout(connector, BoxLayout.Y_AXIS));
        boolean imageBranch = previousNode.getType() == NodeType.IMAGE_CHECK;
        int connectorWidth = imageBranch ? 108 : MIN_CONNECTOR_WIDTH;
        connector.setPreferredSize(new Dimension(connectorWidth, NODE_HEIGHT));
        connector.setMinimumSize(new Dimension(connectorWidth, NODE_HEIGHT));
        connector.setMaximumSize(new Dimension(imageBranch ? connectorWidth : 100, NODE_HEIGHT));
        connector.setAlignmentY(Component.TOP_ALIGNMENT);

        String conditions = "执行";
        if (imageBranch) {
            String nextId = macroNode == null ? MacroFlowFactory.findEndId(flow) : macroNode.getId();
            boolean success = defaultBranchTo(previousNode, BranchCondition.FOUND, nextId);
            boolean failure = defaultBranchTo(previousNode, BranchCondition.NOT_FOUND, nextId);
            conditions = success && failure ? "成功 / 未找到" : success ? "识别成功" : failure ? "未找到" : "";
            if (conditions.isEmpty()) {
                // 没有执行分支时保留节点间距，但不能绘制通向后续节点的箭头。
                return connector;
            }
        }
        connector.add(Box.createVerticalGlue());
        if (imageBranch || macroNode != null && macroNode.getType() == NodeType.IMAGE_CHECK) {
            JLabel condition = label(conditions, 11, false, THEME.accent());
            condition.setAlignmentX(CENTER_ALIGNMENT);
            connector.add(condition);
            connector.add(Box.createVerticalStrut(4));
        }

        MacroAction action = macroNode != null && macroNode.getType() == NodeType.ACTION
                ? macroNode.getAction()
                : null;
        JLabel delay = macroNode != null && action == null ? createImageEntryDelayLabel(previousNode, macroNode)
                : label(macroNode == null ? "本轮结束" : formatDelay(action.getDelay()),
                        11, false, THEME.textSecondary());
        delay.setAlignmentX(CENTER_ALIGNMENT);
        if (macroNode == null) delay.setToolTipText("当前执行分支到达本轮结束");
        else if (action != null) delay.setToolTipText("单击编辑动作间隔");
        if (action != null) {
            delay.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }
        connector.add(delay);
        connector.add(Box.createVerticalStrut(5));
        ArrowComponent arrow = new ArrowComponent();
        arrow.setAlignmentX(CENTER_ALIGNMENT);
        connector.add(arrow);
        connector.add(Box.createVerticalGlue());

        if (action != null) {
            delay.addMouseListener(new MouseAdapter() {
                /** 单击普通动作间隔时打开行内编辑，不改变图像节点的配置入口。 */
                @Override
                public void mouseClicked(MouseEvent event) {
                    if (SwingUtilities.isLeftMouseButton(event)) {
                        beginDelayEdit(connector, delay, arrow, action);
                    }
                }
            });
        }
        return connector;
    }

    /** 图像节点没有普通动作 delay，显示实际入向分支等待，不借用重试间隔。 */
    private JLabel createImageEntryDelayLabel(MacroNode previousNode, MacroNode imageNode) {
        Set<Long> delays = new LinkedHashSet<>();
        StringBuilder hint = new StringBuilder("<html>进入图像识别前的分支等待");
        for (BranchCondition condition : previousNode.getType() == NodeType.IMAGE_CHECK
                ? List.of(BranchCondition.FOUND, BranchCondition.NOT_FOUND) : List.of(BranchCondition.ALWAYS)) {
            if (condition != BranchCondition.ALWAYS && !defaultBranchTo(previousNode, condition, imageNode.getId())) continue;
            var branch = MacroFlowFactory.branchFor(previousNode, condition);
            if (branch == null || !Objects.equals(branch.getTargetId(), imageNode.getId())) continue;
            long delay = Math.max(0L, branch.getDelay());
            delays.add(delay);
            hint.append("<br>").append(condition == BranchCondition.FOUND ? "识别成功" : condition == BranchCondition.NOT_FOUND ? "未找到" : "执行")
                    .append("：").append(formatDelay(delay));
        }
        String text = delays.stream().map(String::valueOf).reduce((left, right) -> left + " / " + right).orElse("0") + " ms";
        JLabel label = label(text, 11, false, THEME.textSecondary());
        label.setToolTipText(hint.append("<br>重试间隔单独显示在图像节点下方的回路</html>").toString());
        return label;
    }

    /** 在原延迟文字位置放入数字输入，提交后调用指定数据写入器，再恢复原连接器。 */
    private void beginDelayEdit(JPanel connector, JLabel delayLabel, JComponent arrow, MacroAction action) {
        beginDelayEdit(connector, delayLabel, arrow, action::getDelay, action::setDelay, "动作延迟（ms）");
    }

    /** 在原延迟文字位置放入数字输入，提交后调用指定数据写入器，再恢复原连接器。 */
    private void beginDelayEdit(JPanel connector, JLabel delayLabel, JComponent arrow,
                               LongSupplier getter, LongConsumer setter, String fieldName) {
        int labelIndex = indexOf(connector, delayLabel);
        if (labelIndex < 0 || cannotStartInlineEdit()) {
            return;
        }
        JTextField editor = new JTextField(Long.toString(getter.getAsLong()));
        editor.setHorizontalAlignment(SwingConstants.CENTER);
        editor.setFont(delayLabel.getFont());
        editor.setForeground(THEME.textPrimary());
        editor.setBackground(THEME.controlBackground());
        editor.setCaretColor(THEME.textPrimary());
        editor.setSelectionColor(THEME.accent());
        editor.setSelectedTextColor(THEME.textOnAccent());
        editor.setBorder(BorderFactory.createLineBorder(THEME.accent()));
        editor.setMaximumSize(new Dimension(65, 25));
        editor.setPreferredSize(new Dimension(65, 25));
        NumericInputUtil.installNonNegativeLong(editor, fieldName);

        connector.remove(delayLabel);
        connector.add(editor, labelIndex);
        connector.revalidate();
        connector.repaint();

        startInlineEdit(editor, () -> NumericInputUtil.readLong(editor), value -> {
            setter.accept(value);
            delayLabel.setText(formatDelay(getter.getAsLong()));
            updateSummary();
        }, () -> replaceEditor(connector, editor, delayLabel, arrow));
    }

    /** 修改共用重试值，同时写回真正参与播放的重试分支，不重建或改写跳转关系。 */
    private void beginRetryDelayEdit(MacroNode node) {
        if (!nodes.contains(node) || node.getImageCheckConfig() == null
                || behaviorConditions(node, ImageCheckBehavior.RETRY).isEmpty()
                || cannotStartInlineEdit()) return;
        TimelineTrackPanel track = (TimelineTrackPanel) timelinePanel;
        JTextField editor = new JTextField(Long.toString(MacroFlowFactory.findRetryDelay(node)));
        ComponentStyleUtil.styleTextField(editor);
        editor.setHorizontalAlignment(SwingConstants.CENTER);
        editor.setFont(track.getFont().deriveFont(Font.PLAIN, 11f));
        editor.setBorder(BorderFactory.createLineBorder(THEME.accent()));
        NumericInputUtil.installNonNegativeLong(editor, "重试延迟（ms）");
        // 编辑器叠放在回路原有文字处，零布局占位保持节点与连接线位置不变。
        editor.setPreferredSize(new Dimension(0, 0));
        editor.setMinimumSize(new Dimension(0, 0));
        editor.setMaximumSize(new Dimension(0, 0));
        track.retryNode = node;
        track.retryEditor = editor;
        track.add(editor);
        track.positionRetryEditor();
        track.revalidate();
        track.repaint();
        startInlineEdit(editor, () -> NumericInputUtil.readLong(editor), value -> {
            ImageCheckConfig config = node.getImageCheckConfig();
            config.setRetryDelay(value);
            if (node.getChildren() != null) {
                node.getChildren().stream().filter(branch -> branch != null
                        && ((branch.getCondition() == BranchCondition.FOUND
                                && config.getSuccessBehavior() == ImageCheckBehavior.RETRY)
                            || (branch.getCondition() == BranchCondition.NOT_FOUND
                                && config.getFailureBehavior() == ImageCheckBehavior.RETRY)))
                        .forEach(branch -> branch.setDelay(value));
            }
        }, () -> {
            track.remove(editor);
            track.retryEditor = null;
            track.retryNode = null;
            track.revalidate();
            track.repaint();
        });
    }

    /** 检查是否必须阻止新编辑，无效文本留在原输入框并显示统一校验提示。 */
    private boolean cannotStartInlineEdit() {
        if (!isEditingAllowed()) {
            cancelInlineEdit();
            return true;
        }
        return activeEdit != null && !activeEdit.commit(false);
    }

    /** 建立本次编辑会话并安装统一提交、取消和焦点处理。 */
    private void startInlineEdit(JTextField editor, LongSupplier parser,
            LongConsumer setter, Runnable restore) {
        InlineEditSession session = new InlineEditSession(editor, parser, setter, restore);
        activeEdit = session;
        session.install();
    }

    /** 取消仍有效的行内编辑，恢复原显示而不写入未提交数值。 */
    private void cancelInlineEdit() {
        if (activeEdit != null) activeEdit.cancel();
    }

    /** 时间线离开显示层级前取消行内编辑并移除临时监听，避免旧回调继续写入。 */
    @Override
    public void removeNotify() {
        nodeDrag.cancel(true);
        cancelInlineEdit();
        super.removeNotify();
    }

    /** 统一处理失焦、回车、取消和组件生命周期，防止同一次编辑重复写入模型。 */
    private final class InlineEditSession {
        private final JTextField editor;
        private final LongSupplier parser;
        private final LongConsumer setter;
        private final Runnable restore;
        private final long revision = timelineRevision;
        private final boolean wasShowing;
        private boolean finished;
        private final AWTEventListener clickListener = this::handleMouseEvent;

        /** 保存解析、写入及显示恢复入口，并记录当前时间线版本和原显示状态。 */
        private InlineEditSession(JTextField editor, LongSupplier parser,
                LongConsumer setter, Runnable restore) {
            this.editor = editor;
            this.parser = parser;
            this.setter = setter;
            this.restore = restore;
            wasShowing = editor.isShowing();
        }

        /** 安装回车提交、失焦提交、Esc 取消和同窗口点击监听，并在安全时请求输入焦点。 */
        private void install() {
            editor.addActionListener(event -> commit(true));
            editor.addFocusListener(new FocusAdapter() {
                /** 失焦时尝试提交有效内容，无效输入保留提示而不弹出模态警告。 */
                @Override
                public void focusLost(FocusEvent event) {
                    commit(false);
                }
            });
            editor.addKeyListener(new KeyAdapter() {
                /** Esc 取消当前输入并消费事件，防止同时触发其他窗口操作。 */
                @Override
                public void keyPressed(KeyEvent event) {
                    if (event.getKeyCode() == KeyEvent.VK_ESCAPE) {
                        cancel();
                        event.consume();
                    }
                }
            });
            // 仅在本窗口编辑期间观察鼠标，不消费事件，也不改变其他控件的操作。
            Toolkit.getDefaultToolkit().addAWTEventListener(clickListener, AWTEvent.MOUSE_EVENT_MASK);
            SwingUtilities.invokeLater(() -> {
                if (!finished && activeEdit == this && editor.isShowing()) {
                    editor.requestFocusInWindow();
                    editor.selectAll();
                }
            });
        }

        /** 在同窗口的外部点击分发前尝试提交，补足标签和空白区域不会主动转移焦点的情况。 */
        private void handleMouseEvent(AWTEvent event) {
            if (!(event instanceof MouseEvent mouse) || mouse.getID() != MouseEvent.MOUSE_PRESSED
                    || !(mouse.getSource() instanceof Component source)
                    || source == editor || SwingUtilities.isDescendingFrom(source, editor)) return;
            Window owner = SwingUtilities.getWindowAncestor(editor);
            if (owner == null || SwingUtilities.getWindowAncestor(source) != owner) return;
            // JLabel、空白面板不会主动取得焦点，因此在普通点击分发前完成有效提交。
            if (commit(false) && !source.isFocusable()) requestFocusInWindow();
        }

        /** 先检查编辑权限、版本及组件生命周期，再校验并写入一次；非法输入返回 false，过期会话仅取消。 */
        private boolean commit(boolean showError) {
            if (finished) return true;
            if (!isEditingAllowed() || revision != timelineRevision || editor.getParent() == null
                    || (wasShowing && !editor.isShowing())) {
                cancel();
                return true;
            }
            long value;
            try {
                value = parser.getAsLong();
            } catch (IllegalArgumentException exception) {
                if (showError) AppMessageDialog.showWarning(ActionTimelinePanel.this, exception.getMessage());
                return false;
            }
            if (!isEditingAllowed()) {
                cancel();
                return true;
            }
            finish();
            var before = captureUndoState();
            setter.accept(value);
            restore.run();
            undoHistory.record(before, flow);
            return true;
        }

        /** 结束当前会话并恢复原显示，不调用数据写入器。 */
        private void cancel() {
            if (finished) return;
            finish();
            restore.run();
        }

        /** 标记会话结束，移除临时全局鼠标监听，并清除当前活动编辑引用。 */
        private void finish() {
            finished = true;
            Toolkit.getDefaultToolkit().removeAWTEventListener(clickListener);
            if (activeEdit == this) activeEdit = null;
        }
    }

    /** 在原组件位置恢复延迟显示，找不到编辑器时使用锚点位置，随后重新布局和绘制。 */
    private void replaceEditor(JPanel parent, JComponent editor, JComponent replacement, JComponent anchor) {
        int editorIndex = indexOf(parent, editor);
        if (editorIndex < 0) {
            editorIndex = Math.max(0, indexOf(parent, anchor) - 1);
        }
        parent.remove(editor);
        parent.add(replacement, editorIndex);
        parent.revalidate();
        parent.repaint();
    }

    /** 按对象身份查找组件在父容器中的位置，不存在时返回 -1。 */
    private int indexOf(Container parent, Component child) {
        Component[] components = parent.getComponents();
        for (int i = 0; i < components.length; i++) {
            if (components[i] == child) return i;
        }
        return -1;
    }

    /** 把同一右键监听安装到卡片内容层级，让标签等子组件也能打开节点菜单。 */
    private void installPopupListener(Container container, MouseAdapter listener) {
        for (Component child : container.getComponents()) {
            if (child instanceof CoordinateEditor) continue;
            child.addMouseListener(listener);
            if (child instanceof Container childContainer) {
                installPopupListener(childContainer, listener);
            }
        }
    }

    /** 仅在系统判定为菜单触发时选中对应节点，并在原鼠标位置显示菜单。 */
    private void maybeShowPopup(MouseEvent event, NodePanel node, MacroNode macroNode) {
        if (!event.isPopupTrigger()) return;
        selectWithModifiers(macroNode, false, false, true);
        createContextMenu(macroNode).show(
                event.getComponent(),
                event.getX(),
                event.getY()
        );
    }

    /** 按节点是否存在和当前编辑权限创建编辑、插入、删除或复制菜单，并保护迟到回调。 */
    private JPopupMenu createContextMenu(MacroNode macroNode) {
        JPopupMenu menu = new JPopupMenu() {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(THEME.card());
                    g.fillRoundRect(0, 0, getWidth(), getHeight(), 20, 20);
                    g.setColor(THEME.border());
                    g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 20, 20);
                } finally { g.dispose(); }
            }
        };
        menu.setOpaque(false);
        menu.setBackground(THEME.card());
        menu.setBorder(new EmptyBorder(8, 8, 8, 8));
        menu.setLayout(new GridBagLayout());

        boolean multiple = macroNode != null && selectedNodeIds.contains(macroNode.getId()) && selectedNodeIds.size() > 1;
        List<MacroNode> menuSelection = getSelectedNodes();
        long menuRevision = timelineRevision;
        JMenuItem edit = createMenuItem(multiple ? "批量编辑" : "编辑动作", IconType.EDIT);
        edit.setEnabled(isEditingAllowed() && (!multiple || hasBatchEditableSelection()));
        Consumer<java.awt.event.ActionEvent> editRequest = guardEditCallback(event -> {
            if (multiple) { if (menuRevision == timelineRevision && menuSelection.equals(getSelectedNodes())) requestBatchEdit(); }
            else if (onActionEditRequested != null) onActionEditRequested.accept(macroNode);
        });
        edit.addActionListener(editRequest::accept);
        JMenuItem insert = createMenuItem("插入动作", IconType.INSERT);
        insert.setEnabled(isEditingAllowed());
        Consumer<java.awt.event.ActionEvent> insertRequest = guardEditCallback(event -> {
            if (onActionInsertRequested != null) onActionInsertRequested.accept(macroNode);
        });
        insert.addActionListener(insertRequest::accept);
        JMenuItem delete = createMenuItem(multiple ? "删除所选" : "删除动作", IconType.DELETE);
        delete.setEnabled(isEditingAllowed());
        Consumer<java.awt.event.ActionEvent> deleteRequest = guardEditCallback(event -> {
            if (multiple) { if (menuRevision == timelineRevision && menuSelection.equals(getSelectedNodes())) requestBatchDelete(); }
            else deleteAndNotify(macroNode);
        });
        delete.addActionListener(deleteRequest::accept);
        JMenuItem copy = createMenuItem(multiple ? "复制所选" : "复制节点", IconType.COPY);
        copy.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, KeyEvent.CTRL_DOWN_MASK));
        copy.setToolTipText("复制节点（Ctrl+C），也可在普通文本框粘贴节点信息");
        copy.addActionListener(event -> {
            if (!multiple || menuRevision == timelineRevision && menuSelection.equals(getSelectedNodes())) requestNodeClipboardAction(macroNode, true);
        });

        if (macroNode == null) {
            addMenuCell(menu, insert, 0, 0, 1);
            addMenuCell(menu, createPasteMenuItem(null, false), 1, 0, 1);
            addMenuCell(menu, createUndoMenuItem(true), 0, 1, 2);
            return menu;
        }
        addMenuCell(menu, edit, 0, 0, 1);
        addMenuCell(menu, insert, 1, 0, 1);
        addMenuCell(menu, copy, 0, 1, 1);
        addMenuCell(menu, delete, 1, 1, 1);
        addMenuCell(menu, createPasteMenuItem(macroNode, true), 0, 2, 1);
        addMenuCell(menu, createUndoMenuItem(true), 1, 2, 1);
        return menu;
    }

    /** 菜单项保持直属 PopupMenu，保留 Swing 的选择、Enter、Esc 和辅助功能。 */
    private void addMenuCell(JPopupMenu menu, JMenuItem item, int column, int row, int span) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = column; c.gridy = row; c.gridwidth = span;
        c.weightx = 1; c.fill = GridBagConstraints.BOTH;
        c.insets = new Insets(span == 2 ? 6 : 4, 4, 4, 4);
        menu.add(item, c);
    }

    private JMenuItem createUndoMenuItem(boolean footer) {
        JMenuItem undo = createMenuItem("撤销", IconType.UNDO, footer);
        undo.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, KeyEvent.CTRL_DOWN_MASK));
        undo.setEnabled(canUndo());
        Consumer<java.awt.event.ActionEvent> request = guardEditCallback(event -> undo());
        undo.addActionListener(request::accept);
        return undo;
    }

    private JMenuItem createPasteMenuItem(MacroNode anchor, boolean footer) {
        JMenuItem paste = createMenuItem(footer ? "粘贴" : "粘贴节点", IconType.PASTE, footer);
        paste.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_V, KeyEvent.CTRL_DOWN_MASK));
        paste.setToolTipText(anchor == null ? "追加复制的节点（Ctrl+V）" : "粘贴到此节点后面（Ctrl+V）");
        paste.setEnabled(canPasteNode());
        Consumer<java.awt.event.ActionEvent> request = guardEditCallback(event -> requestNodeClipboardAction(anchor, false));
        paste.addActionListener(request::accept);
        return paste;
    }

    private JMenuItem createMenuItem(String text, IconType iconType) {
        return createMenuItem(text, iconType, false);
    }

    /** 图标居上的操作宫格；保留 JMenuItem 原生菜单行为，只替换绘制。 */
    private JMenuItem createMenuItem(String text, IconType iconType, boolean footer) {
        JMenuItem item = new JMenuItem(text);
        item.setFont(new Font(UiConstants.DEFAULT_FONT_FAMILY, Font.PLAIN, 13));
        item.setForeground(THEME.textPrimary());
        item.setOpaque(false);
        item.setBorder(new EmptyBorder(0, 0, 0, 0));
        item.setPreferredSize(new Dimension(116, footer ? 44 : 84));
        Color accent = iconType == IconType.DELETE ? THEME.danger() : THEME.accent();
        TimelineIcon normalIcon = new TimelineIcon(iconType, footer ? 17 : 24, accent);
        TimelineIcon disabledIcon = new TimelineIcon(iconType, footer ? 17 : 24, THEME.textMuted());
        item.setUI(new BasicMenuItemUI() {
            @Override public void paint(Graphics graphics, JComponent component) {
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    boolean enabled = item.isEnabled();
                    boolean highlighted = enabled && (item.isArmed() || item.getModel().isRollover());
                    Color surface = footer ? THEME.targetCard() : THEME.controlBackground();
                    if (highlighted) surface = blend(surface, accent, item.getModel().isPressed() ? .24f : .13f);
                    g.setColor(surface);
                    g.fillRoundRect(0, 0, item.getWidth(), item.getHeight(), 12, 12);
                    g.setColor(highlighted ? blend(THEME.border(), accent, .6f) : THEME.border());
                    g.drawRoundRect(0, 0, item.getWidth() - 1, item.getHeight() - 1, 12, 12);
                    Color foreground = !enabled ? THEME.textMuted()
                            : highlighted && iconType == IconType.DELETE ? THEME.dangerHover() : THEME.textPrimary();
                    TimelineIcon icon = enabled ? normalIcon : disabledIcon;
                    g.setFont(item.getFont());
                    FontMetrics metrics = g.getFontMetrics();
                    if (footer) {
                        icon.paintIcon(item, g, 12, (item.getHeight() - 17) / 2);
                        g.setColor(foreground);
                        boolean compact = item.getWidth() < 180;
                        g.drawString(text, 38, compact ? 19 : (item.getHeight() - metrics.getHeight()) / 2 + metrics.getAscent());
                        g.setFont(item.getFont().deriveFont(10f));
                        String shortcut = "Ctrl+" + KeyEvent.getKeyText(item.getAccelerator().getKeyCode());
                        FontMetrics small = g.getFontMetrics();
                        if (compact) {
                            g.setColor(enabled ? THEME.textSecondary() : THEME.textMuted());
                            g.drawString(shortcut, 38, 35);
                            return;
                        }
                        int chipWidth = small.stringWidth(shortcut) + 16;
                        int chipX = item.getWidth() - chipWidth - 10;
                        g.setColor(THEME.controlBackground());
                        g.fillRoundRect(chipX, (item.getHeight() - 22) / 2, chipWidth, 22, 7, 7);
                        g.setColor(enabled ? THEME.textSecondary() : THEME.textMuted());
                        g.drawString(shortcut, chipX + 8, (item.getHeight() - small.getHeight()) / 2 + small.getAscent());
                    } else {
                        icon.paintIcon(item, g, (item.getWidth() - 24) / 2, 15);
                        g.setColor(foreground);
                        g.drawString(text, (item.getWidth() - metrics.stringWidth(text)) / 2, 63);
                    }
                } finally { g.dispose(); }
            }
        });
        return item;
    }

    /** 通过统一节点删除入口处理删除，并在成功后通知主窗口同步宏信息。 */
    private void deleteAndNotify(MacroNode macroNode) {
        if (!isEditingAllowed()) return;
        Consumer<Boolean> delete = guardEditCallback(confirmed -> {
            if (confirmed && deleteAction(macroNode, false) && onActionDeleteRequested != null) {
                onActionDeleteRequested.accept(macroNode);
            }
        });
        boolean confirmed = AppMessageDialog.showConfirm(
                this,
                "删除动作",
                "确定删除这个动作吗？"
        );
        delete.accept(confirmed);
    }

    /** 按真实节点身份定位对应卡片并更新当前选择。 */
    private void selectMacroNode(MacroNode macroNode) {
        for (Component component : timelinePanel.getComponents()) {
            if (component instanceof NodePanel node
                    && node.macroNode == macroNode) {
                selectNode(node, macroNode);
                return;
            }
        }
    }

    /** 更新旧卡片与新卡片的选中状态，并让时间线取得键盘操作焦点。 */
    private void selectNode(NodePanel node, MacroNode macroNode) {
        selectedNodeIds.clear();
        selectedNodeIds.add(macroNode.getId());
        selectionAnchorId = macroNode.getId();
        selectedMacroNode = macroNode;
        updateSelection();
        requestFocusInWindow();
        if (onActionSelected != null) {
            onActionSelected.accept(macroNode);
        }
    }

    /** Shift 以最近一次普通/Ctrl 点击为锚点；Ctrl+Shift 把区间加入现有选择。 */
    void selectWithModifiers(MacroNode node, boolean control, boolean shift, boolean keepGroup) {
        if (nodes.stream().noneMatch(current -> current == node)) return;
        if (shift) {
            int anchor = 0;
            for (int i = 0; i < nodes.size(); i++) if (nodes.get(i).getId().equals(selectionAnchorId)) { anchor = i; break; }
            int end = nodes.indexOf(node);
            if (!control) selectedNodeIds.clear();
            for (int i = Math.min(anchor, end); i <= Math.max(anchor, end); i++) selectedNodeIds.add(nodes.get(i).getId());
            if (selectionAnchorId == null) selectionAnchorId = nodes.get(anchor).getId();
        } else if (control) {
            if (!selectedNodeIds.remove(node.getId())) selectedNodeIds.add(node.getId());
            selectionAnchorId = node.getId();
        } else {
            if (!keepGroup || !selectedNodeIds.contains(node.getId())) selectedNodeIds.clear();
            selectedNodeIds.add(node.getId());
            selectionAnchorId = node.getId();
        }
        selectedMacroNode = selectedNodeIds.contains(node.getId()) ? node : null;
        updateSelection();
        requestFocusInWindow();
        if (onActionSelected != null) onActionSelected.accept(selectedMacroNode);
    }

    void clearSelection() {
        selectedNodeIds.clear();
        selectedMacroNode = null;
        selectionAnchorId = null;
        updateSelection();
        requestFocusInWindow();
    }

    private void updateSelection() {
        selectedNodeIds.retainAll(nodes.stream().map(MacroNode::getId).toList());
        if (selectedMacroNode == null || !selectedNodeIds.contains(selectedMacroNode.getId()))
            selectedMacroNode = getSelectedNodes().stream().findFirst().orElse(null);
        selectedNode = selectedMacroNode == null ? null : nodePanels.get(selectedMacroNode.getId());
        nodePanels.forEach((id, card) -> card.setSelected(selectedNodeIds.contains(id)));
        selectionCount.setText("已选 " + selectedNodeIds.size() + " 个节点");
        batchEditButton.setEnabled(isEditingAllowed() && hasBatchEditableSelection());
        batchEditButton.setToolTipText(hasBatchEditableSelection() ? "统一设置普通动作延迟或图像重试间隔"
                : "所选节点没有普通动作或图像重试间隔可修改");
        selectionToolbar.setVisible(selectedNodeIds.size() > 1);
        selectionToolbar.revalidate();
    }

    private void installSelectionKeyBindings() {
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_A, KeyEvent.CTRL_DOWN_MASK), "selectAllTimelineNodes");
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_A, KeyEvent.CTRL_DOWN_MASK), "selectAllTimelineNodes");
        getActionMap().put("selectAllTimelineNodes", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                if (KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() instanceof javax.swing.text.JTextComponent) return;
                nodes.forEach(node -> selectedNodeIds.add(node.getId()));
                if (!nodes.isEmpty()) selectionAnchorId = nodes.get(0).getId();
                updateSelection();
            }
        });
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "clearTimelineSelection");
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "clearTimelineSelection");
        getActionMap().put("clearTimelineSelection", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                if (!(KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() instanceof javax.swing.text.JTextComponent)) {
                    if (nodeSearch.isVisible()) closeNodeSearch(); else clearSelection();
                }
            }
        });
    }

    private void openNodeSearch() {
        cancelInlineEdit(); nodeDrag.cancel(true);
        nodeSearch.open();
    }

    private void closeNodeSearch() {
        searchNavigationRevision++;
        nodeSearch.close();
        requestFocusInWindow();
    }

    private void repaintSearchResults() {
        nodePanels.values().forEach(Component::repaint);
    }

    /** 等布局完成再定位；连续输入、关闭、换宏或重建后不执行过时的滚动请求。 */
    private void scrollToSearchResult(String id) {
        nodeDrag.cancel(true);
        long navigation = ++searchNavigationRevision;
        long revision = timelineRevision;
        SwingUtilities.invokeLater(() -> {
            if (navigation != searchNavigationRevision || revision != timelineRevision
                    || !Objects.equals(id, nodeSearch.currentId())) return;
            NodePanel card = nodePanels.get(id);
            if (card != null) card.scrollRectToVisible(new Rectangle(0, 0, card.getWidth(), card.getHeight()));
        });
    }

    private void installSearchKeyBindings() {
        for (int direction : new int[]{0, 1, -1}) {
            String name = direction == 0 ? "findTimelineNode" : direction > 0 ? "findNextTimelineNode" : "findPreviousTimelineNode";
            KeyStroke key = direction == 0 ? KeyStroke.getKeyStroke(KeyEvent.VK_F, KeyEvent.CTRL_DOWN_MASK)
                    : KeyStroke.getKeyStroke(KeyEvent.VK_F3, direction < 0 ? KeyEvent.SHIFT_DOWN_MASK : 0);
            getInputMap(WHEN_FOCUSED).put(key, name);
            getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(key, name);
            getActionMap().put(name, new AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                    if (direction == 0 || !nodeSearch.isVisible()) openNodeSearch(); else nodeSearch.navigate(direction);
                }
            });
        }
    }

    /** 时间线及批量按钮内响应 Delete，文字输入框保留自身删除行为。 */
    private void installDeleteKeyBinding() {
        String actionKey = "deleteSelectedTimelineAction";
        getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0),
                actionKey
        );
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), actionKey);
        getActionMap().put(actionKey, new AbstractAction() {
            /** 收到时间线 Delete 绑定后删除当前选中节点，仍由统一删除入口检查权限。 */
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                if (KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() instanceof javax.swing.text.JTextComponent) return;
                if (selectedMacroNode != null) {
                    if (getSelectedNodes().size() > 1) requestBatchDelete();
                    else deleteAndNotify(selectedMacroNode);
                }
            }
        });
    }

    /** 只在时间线及其子控件中响应，输入框保留自己的文字编辑行为。 */
    private void installUndoKeyBinding() {
        String actionKey = "undoTimelineEdit";
        KeyStroke key = KeyStroke.getKeyStroke(KeyEvent.VK_Z, KeyEvent.CTRL_DOWN_MASK);
        getInputMap(JComponent.WHEN_FOCUSED).put(key, actionKey);
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(key, actionKey);
        getActionMap().put(actionKey, new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                if (!(focus instanceof javax.swing.text.JTextComponent)) undo();
            }
        });
    }

    private void installNodeClipboardKeyBindings() {
        for (boolean copy : new boolean[]{true, false}) {
            String actionKey = copy ? "copyTimelineNode" : "pasteTimelineNode";
            KeyStroke key = KeyStroke.getKeyStroke(copy ? KeyEvent.VK_C : KeyEvent.VK_V, KeyEvent.CTRL_DOWN_MASK);
            getInputMap(JComponent.WHEN_FOCUSED).put(key, actionKey);
            getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(key, actionKey);
            getActionMap().put(actionKey, new AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                    Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                    if (!(focus instanceof javax.swing.text.JTextComponent)) requestNodeClipboardAction(selectedMacroNode, copy);
                }
            });
        }
    }

    /** 按真实节点数量和动作等待计算统计，头部待机计入总时长并避免加法溢出。 */
    private void updateSummary() {
        actionCountValue.setText(nodes.size() + " 个");
        long total = flow.getHeadWaitMillis() == null ? 0L : flow.getHeadWaitMillis();
        for (MacroNode node : nodes) {
            if (node.getType() == NodeType.ACTION && node.getAction() != null) {
                long delay = Math.max(0L, node.getAction().getDelay());
                total = Long.MAX_VALUE - total < delay ? Long.MAX_VALUE : total + delay;
            }
        }
        totalDurationValue.setText(formatTotalDuration(total));
    }

    /** 把累计等待时间格式化为适合统计卡片显示的毫秒或秒。 */
    private String formatTotalDuration(long milliseconds) {
        if (milliseconds < 1000) return milliseconds + " ms";
        double seconds = milliseconds / 1000.0;
        return String.format(Locale.ROOT, seconds >= 10 ? "%.1f 秒" : "%.2f 秒", seconds);
    }

    /** 把单条连线等待时间格式化为毫秒文字。 */
    private String formatDelay(long delay) {
        return Math.max(0L, delay) + " ms";
    }

    /** 判断动作是否属于移动、按钮或滚轮等鼠标输入。 */
    private boolean isMouseAction(ActionType type) {
        return type == ActionType.MOUSE_MOVE
                || type == ActionType.MOUSE_PRESS
                || type == ActionType.MOUSE_RELEASE
                || type == ActionType.MOUSE_WHEEL;
    }

    /** 按图像节点或普通动作类型选择时间线图标。 */
    private IconType iconFor(MacroNode node) {
        if (node.getType() == NodeType.IMAGE_CHECK) {
            return IconType.IMAGE;
        }
        ActionType type = node.getAction().getType();
        if (type == ActionType.KEY_PRESS || type == ActionType.KEY_RELEASE) return IconType.KEYBOARD;
        if (type == ActionType.MOUSE_WHEEL) return IconType.WHEEL;
        return IconType.MOUSE;
    }

    /** 返回图像节点或普通输入动作的可读名称，供卡片、目标菜单和编辑器共同使用。 */
    static String getNodeName(MacroNode node) {
        return node.getType() == NodeType.IMAGE_CHECK
                ? "图像识别"
                : getActionName(node.getAction());
    }

    /** 生成节点参数摘要；图像节点使用模板名称及阈值，普通动作沿用动作详情。 */
    private String getNodeDetail(MacroNode node) {
        if (node.getType() == NodeType.IMAGE_CHECK) {
            ImageCheckConfig config = node.getImageCheckConfig();
            if (config == null || config.getImagePath() == null
                    || config.getImagePath().isBlank()) {
                return "未配置";
            }
            return new File(config.getImagePath()).getName()
                    + " · "
                    + Math.round(config.getThreshold() * 100.0)
                    + "%";
        }
        return getActionDetail(node.getAction());
    }

    /** 把普通输入动作类型转换为卡片标题文字。 */
    private static String getActionName(MacroAction action) {
        return switch (action.getType()) {
            case MOUSE_MOVE -> "鼠标移动";
            case MOUSE_PRESS -> "鼠标按下";
            case MOUSE_RELEASE -> "鼠标释放";
            case MOUSE_WHEEL -> "鼠标滚轮";
            case KEY_PRESS -> "键盘按下";
            case KEY_RELEASE -> "键盘释放";
        };
    }

    /** 将鼠标按钮或滚动方向转换为卡片参数说明。 */
    private String getMouseDetail(MacroAction action) {
        return switch (action.getType()) {
            case MOUSE_WHEEL -> action.getWheelAmount() > 0
                    ? "向下 " + Math.abs(action.getWheelAmount()) + " 格"
                    : "向上 " + Math.abs(action.getWheelAmount()) + " 格";
            case MOUSE_PRESS, MOUSE_RELEASE -> getMouseButtonName(action.getMouseButton());
            default -> "位置";
        };
    }

    /** 按动作类型生成坐标、鼠标按钮、滚轮或键盘参数的可读摘要。 */
    private String getActionDetail(MacroAction action) {
        return switch (action.getType()) {
            case MOUSE_MOVE -> "X: " + action.getX() + "  Y: " + action.getY();
            case MOUSE_PRESS, MOUSE_RELEASE -> getMouseButtonName(action.getMouseButton())
                    + "  (" + action.getX() + ", " + action.getY() + ")";
            case MOUSE_WHEEL -> getMouseDetail(action) + "  (" + action.getX() + ", " + action.getY() + ")";
            case KEY_PRESS, KEY_RELEASE -> {
                String key = action.getKey();
                yield (key == null || key.isBlank()) ? "键码 " + action.getKeyCode() : key + " 键";
            }
        };
    }

    /** 显示支持的鼠标按钮名称，旧侧键数据明确显示为不支持而不误标为普通按钮。 */
    private String getMouseButtonName(MouseButton button) {
        if (button == null) return "无";
        return switch (button) {
            case LEFT -> "左键";
            case MIDDLE -> "中键";
            case RIGHT -> "右键";
            case X1, X2 -> "不支持的按键";
        };
    }

    /** 创建参数显示用的主题小标签，保持固定间距和卡片内对齐。 */
    private JLabel createPill(String text) {
        JLabel label = label(text, 12, false, THEME.textSecondary());
        label.setHorizontalAlignment(SwingConstants.CENTER);
        label.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(THEME.border()),
                new EmptyBorder(6, 9, 6, 9)
        ));
        label.setOpaque(true);
        label.setBackground(THEME.controlBackground());
        label.setMaximumSize(new Dimension(NODE_WIDTH - 28, 31));
        return label;
    }

    /** 按指定字号、字重和颜色创建时间线标签。 */
    private static JLabel label(String text, int size, boolean bold, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(bold ? Font.BOLD : Font.PLAIN, (float) size));
        label.setForeground(color);
        return label;
    }

    /** 在原坐标标签位置提供轻量行内编辑与右键复制，不增加额外动作节点。 */
    private final class CoordinateEditor extends RoundedPanel {
        private final String axis;
        private final Consumer<Integer> setter;
        private int value;
        private final JLabel valueLabel;

        /** 保存坐标轴、当前值和写回回调，安装点击编辑及坐标菜单。 */
        private CoordinateEditor(String axis, int value, Consumer<Integer> setter) {
            super(THEME.controlBackground(), THEME.border(), 9);
            this.axis = axis;
            this.value = value;
            this.setter = setter;
            setLayout(new BorderLayout());
            setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));
            valueLabel = label(axis + ": " + value, 11, false, THEME.textPrimary());
            valueLabel.setHorizontalAlignment(SwingConstants.CENTER);
            add(valueLabel, BorderLayout.CENTER);
            MouseAdapter listener = new MouseAdapter() {
                /** 单击坐标标签时尝试开始编辑。 */
                @Override
                public void mouseClicked(MouseEvent event) {
                    if (SwingUtilities.isLeftMouseButton(event) && !event.isControlDown() && !event.isShiftDown()) {
                        beginEdit();
                    }
                }

                /** 兼容按下触发的坐标右键菜单。 */
                @Override
                public void mousePressed(MouseEvent event) {
                    if (SwingUtilities.isLeftMouseButton(event)) {
                        selectParentNode(event);
                    }
                    showCoordinatePopup(event);
                }

                /** 兼容释放触发的坐标右键菜单。 */
                @Override
                public void mouseReleased(MouseEvent event) {
                    showCoordinatePopup(event);
                }

                /** 在系统菜单触发位置显示坐标相关操作，复用当前坐标值。 */
                private void showCoordinatePopup(MouseEvent event) {
                    if (!event.isPopupTrigger()) return;
                    Container parent = CoordinateEditor.this.getParent();
                    while (parent != null && !(parent instanceof NodePanel)) parent = parent.getParent();
                    if (parent instanceof NodePanel node) {
                        selectWithModifiers(node.macroNode, false, false, true);
                        createContextMenu(node.macroNode).show(event.getComponent(), event.getX(), event.getY());
                    }
                }

                /** 选择坐标编辑器所属的动作节点。 */
                private void selectParentNode(MouseEvent event) {
                    Container parent = CoordinateEditor.this.getParent();
                    while (parent != null && !(parent instanceof NodePanel)) {
                        parent = parent.getParent();
                    }
                    if (parent instanceof NodePanel node) {
                        selectWithModifiers(node.macroNode, event.isControlDown(), event.isShiftDown(), false);
                    }
                }
            };
            addMouseListener(listener);
            valueLabel.addMouseListener(listener);
        }

        /** 检查当前编辑权限和旧会话，再把坐标标签替换为受校验的整数输入。 */
        private void beginEdit() {
            if (getComponentCount() == 1 && getComponent(0) instanceof JTextField) return;
            if (cannotStartInlineEdit()) return;
            JTextField editor = new JTextField(String.valueOf(value));
            editor.setHorizontalAlignment(SwingConstants.CENTER);
            editor.setFont(valueLabel.getFont());
            editor.setForeground(THEME.textPrimary());
            editor.setBackground(THEME.controlBackground());
            editor.setCaretColor(THEME.textPrimary());
            editor.setSelectionColor(THEME.accent());
            editor.setSelectedTextColor(THEME.textOnAccent());
            editor.setBorder(null);
            NumericInputUtil.installInteger(editor, axis + " 坐标");
            removeAll();
            add(editor, BorderLayout.CENTER);
            revalidate();
            repaint();

            startInlineEdit(editor, () -> NumericInputUtil.readInt(editor), newValue -> {
                value = (int) newValue;
                setter.accept(value);
            }, () -> {
                valueLabel.setText(axis + ": " + value);
                removeAll();
                add(valueLabel, BorderLayout.CENTER);
                revalidate();
                repaint();
            });
        }
    }

    /** 在卡片内部叠加已完成柔光和当前节点边框，不改变尺寸或新增动画任务。 */
    private static void paintPlaybackDecoration(Graphics graphics, int width, int height,
                                                boolean completed, boolean current, ThemePalette theme) {
        if (!completed && !current) return;
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (completed && width > 12 && height > 12) {
                Color start = theme.playbackGlowStart();
                Color end = theme.playbackGlowEnd();
                g2.setPaint(new GradientPaint(4, height - 4, withAlpha(start, 62),
                        width - 4, 4, withAlpha(end, 62)));
                g2.fillRoundRect(4, 4, width - 8, height - 8, 14, 14);
                for (int inset = 4; inset <= 9; inset++) {
                    int alpha = (10 - inset) * 9;
                    g2.setPaint(new GradientPaint(4, height - 4, withAlpha(start, alpha),
                            width - 4, 4, withAlpha(end, alpha)));
                    g2.drawRoundRect(inset, inset, width - inset * 2 - 1, height - inset * 2 - 1, 14, 14);
                }
            }
            if (current) {
                g2.setColor(theme.accent());
                g2.setStroke(new BasicStroke(2f));
                g2.drawRoundRect(3, 3, width - 7, height - 7, 15, 15);
            }
        } finally { g2.dispose(); }
    }

    /** 只调整主题颜色的透明度，保留其 RGB 配色。 */
    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    /** 绘制真实操作卡片的选择、播放进度和编号叠层，保留原有内容布局。 */
    private final class NodePanel extends JPanel {
        private boolean selected;
        private MacroNode macroNode;
        private String displayNumber;
        private String templateQualityWarning;

        /** 创建由卡片自身绘制主题背景的透明节点容器。 */
        private NodePanel() {
            setOpaque(false);
        }

        /** 拖动源在预览位置充当占位卡，真实内容由上层半透明影像展示。 */
        @Override public void paint(Graphics graphics) {
            if (!nodeDrag.isDragging(macroNode)) { super.paint(graphics); return; }
            Graphics2D g2 = (Graphics2D) graphics.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(withAlpha(THEME.accent(), 24));
                g2.fillRoundRect(3, 3, getWidth() - 6, getHeight() - 6, 15, 15);
                g2.setColor(THEME.accent());
                g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                        1f, new float[]{6f, 5f}, 0));
                g2.drawRoundRect(3, 3, getWidth() - 7, getHeight() - 7, 15, 15);
                String text = "放置到 " + String.format(Locale.ROOT, "%02d", nodeDrag.previewIndex(macroNode) + 1);
                g2.setFont(getFont().deriveFont(Font.BOLD, 13f));
                FontMetrics metrics = g2.getFontMetrics();
                g2.drawString(text, (getWidth() - metrics.stringWidth(text)) / 2, getHeight() / 2);
            } finally { g2.dispose(); }
        }

        /** 记录卡片选中状态并请求重绘，不重新计算节点数据。 */
        private void setSelected(boolean selected) {
            this.selected = selected;
            repaint();
        }

        private void setTemplateQualityWarning(String warning) {
            templateQualityWarning = warning;
            setToolTipText(warning);
            getAccessibleContext().setAccessibleDescription(warning);
            repaint();
        }

        private Rectangle warningBounds() {
            return new Rectangle(getWidth() - 26, 7, 18, 18);
        }

        /** 只有黄色三角区域显示质量提示，保留其他控件原有提示和鼠标事件。 */
        @Override public String getToolTipText(MouseEvent event) {
            return templateQualityWarning != null && warningBounds().contains(event.getPoint())
                    ? templateQualityWarning : null;
        }

        @Override public JToolTip createToolTip() {
            JToolTip tip = super.createToolTip();
            tip.setBackground(THEME.controlBackground());
            tip.setForeground(THEME.textPrimary());
            tip.setFont(new Font(UiConstants.DEFAULT_FONT_FAMILY, Font.PLAIN, 12));
            tip.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(THEME.border()),
                    new EmptyBorder(8, 10, 8, 10)));
            return tip;
        }

        /** 叠加绘制，不增加子组件、边距或布局占位。 */
        @Override
        protected void paintChildren(Graphics graphics) {
            super.paintChildren(graphics);
            Graphics2D g2 = (Graphics2D) graphics.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (displayNumber != null && !displayNumber.isEmpty()) {
                    g2.setFont(getFont().deriveFont(Font.BOLD, 10f));
                    FontMetrics metrics = g2.getFontMetrics();
                    int width = metrics.stringWidth(displayNumber) + 12;
                    g2.setColor(selected ? blend(THEME.controlBackground(), THEME.accent(), .18f)
                            : THEME.controlBackground());
                    g2.fillRoundRect(8, 7, width, 18, 8, 8);
                    g2.setColor(selected ? THEME.textPrimary() : THEME.textSecondary());
                    g2.drawString(displayNumber, 14, 7 + (18 - metrics.getHeight()) / 2 + metrics.getAscent());
                }
                if (templateQualityWarning != null) {
                    Rectangle bounds = warningBounds();
                    g2.translate(bounds.x, bounds.y);
                    Path2D triangle = new Path2D.Double();
                    triangle.moveTo(9, .5);
                    triangle.lineTo(17.5, 17);
                    triangle.lineTo(.5, 17);
                    triangle.closePath();
                    g2.setColor(TEMPLATE_WARNING_COLOR);
                    g2.fill(triangle);
                    g2.setColor(new Color(0x30270E));
                    g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.drawLine(9, 6, 9, 10);
                    g2.fillOval(8, 13, 2, 2);
                }
            } finally { g2.dispose(); }
        }

        /** 绘制常规或选中卡片背景，并叠加当前和已完成播放状态。 */
        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (selected) {
                g2.setColor(new Color(THEME.accent().getRed(), THEME.accent().getGreen(), THEME.accent().getBlue(), 38));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 18, 18);
                g2.setColor(blend(THEME.targetCard(), THEME.accent(), 0.12f));
                g2.fillRoundRect(3, 3, getWidth() - 6, getHeight() - 6, 15, 15);
                g2.setStroke(new BasicStroke(2f));
                g2.setColor(THEME.accent());
                g2.drawRoundRect(3, 3, getWidth() - 7, getHeight() - 7, 15, 15);
            } else {
                g2.setColor(THEME.targetCard());
                g2.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 16, 16);
                g2.setColor(THEME.border());
                g2.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 16, 16);
            }
            paintPlaybackDecoration(g2, getWidth(), getHeight(), completedNodeIds.contains(macroNode.getId()),
                    Objects.equals(currentPlaybackNodeId, macroNode.getId()), THEME);
            if (Objects.equals(nodeSearch.currentId(), macroNode.getId())) {
                g2.setColor(TEMPLATE_WARNING_COLOR);
                g2.setStroke(new BasicStroke(2f));
                g2.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 17, 17);
            }
            g2.dispose();
            super.paintComponent(graphics);
        }
    }

    /** 为时间线统计和参数区域提供通用圆角背景及边框。 */
    private static class RoundedPanel extends JPanel {
        private final Color fill;
        private final Color stroke;
        private final int arc;

        /** 保存面板背景、边框颜色和圆角半径。 */
        private RoundedPanel(Color fill, Color stroke, int arc) {
            this.fill = fill;
            this.stroke = stroke;
            this.arc = arc;
            setOpaque(false);
        }

        /** 按实际组件边界绘制主题圆角卡片，再绘制其内容。 */
        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
            if (stroke != null) {
                g2.setColor(stroke);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            }
            g2.dispose();
            super.paintComponent(graphics);
        }
    }

    /** 绘制相邻节点之间的普通水平主链箭头，不表达远距离分支关系。 */
    private static class ArrowComponent extends JComponent {
        /** 为普通主链箭头设置透明绘制区域和既定尺寸。 */
        private ArrowComponent() {
            setPreferredSize(new Dimension(MIN_CONNECTOR_WIDTH - 10, 14));
            setMaximumSize(new Dimension(MIN_CONNECTOR_WIDTH - 10, 14));
        }

        /** 绘制普通主链的水平线段及向右箭头。 */
        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(THEME.textMuted());
            int mid = getHeight() / 2;
            int end = getWidth() - 5;
            g2.drawLine(3, mid, end, mid);
            g2.drawLine(end - 5, mid - 4, end, mid);
            g2.drawLine(end - 5, mid + 4, end, mid);
            g2.dispose();
        }
    }

    /**
     * 提供稳定首选视口尺寸的横向时间线轨道。
     *
     * <p>轨道本身仍按节点数量增长，超出视口的部分由滚动条访问，
     * 避免完整轨道宽度参与外层窗口的 {@code pack()} 计算。</p>
     */
    private final class TimelineTrackPanel extends JPanel implements Scrollable {
        private MacroNode retryNode;
        private JTextField retryEditor;

        /** 安装轨道空白区菜单、重试标签命中和鼠标指针反馈，不接管节点执行。 */
        private TimelineTrackPanel() {
            MouseAdapter listener = new MouseAdapter() {
                /** 左键命中重试文字时开始延迟编辑，否则让时间线取得焦点，并处理背景菜单。 */
                @Override
                public void mousePressed(MouseEvent event) {
                    if (SwingUtilities.isLeftMouseButton(event)) {
                        NodePanel hit = retryNodeAt(event.getPoint());
                        if (hit == null) { if (!event.isControlDown() && !event.isShiftDown()) clearSelection(); }
                        else beginRetryDelayEdit(hit.macroNode);
                    }
                    showBackgroundPopup(event);
                }

                /** 兼容在释放时触发的轨道背景菜单。 */
                @Override
                public void mouseReleased(MouseEvent event) {
                    showBackgroundPopup(event);
                }

                /** 按是否命中可编辑的重试文字切换鼠标指针。 */
                @Override
                public void mouseMoved(MouseEvent event) {
                    setCursor(Cursor.getPredefinedCursor(retryNodeAt(event.getPoint()) == null
                            ? Cursor.DEFAULT_CURSOR : Cursor.TEXT_CURSOR));
                }

                /** 在空白轨道打开插入菜单，不伪造当前节点。 */
                private void showBackgroundPopup(MouseEvent event) {
                    if (event.isPopupTrigger()) {
                        createContextMenu(null).show(TimelineTrackPanel.this, event.getX(), event.getY());
                    }
                }
            };
            addMouseListener(listener);
            addMouseMotionListener(listener);
        }

        /** 按实际重试标签区域查找命中的图像卡片，没有命中时返回 null。 */
        private NodePanel retryNodeAt(Point point) {
            for (Component component : getComponents()) {
                if (component instanceof NodePanel node && node.macroNode.getType() == NodeType.IMAGE_CHECK
                        && !behaviorConditions(node.macroNode, ImageCheckBehavior.RETRY).isEmpty()
                        && retryBounds(node).contains(point)) return node;
            }
            return null;
        }

        /** 根据节点当前边界计算重试文字的命中矩形，随卡片移动更新。 */
        private Rectangle retryBounds(NodePanel node) {
            FontMetrics metrics = getFontMetrics(getFont().deriveFont(Font.PLAIN, 11f));
            int width = Math.max(68, metrics.stringWidth(formatDelay(
                    MacroFlowFactory.findRetryDelay(node.macroNode))) + 16);
            return new Rectangle(node.getX() + (node.getWidth() - width) / 2,
                    node.getY() + node.getHeight() + SELF_LOOP_BOTTOM_OFFSET - 13, width, 26);
        }

        /** 沿用轨道正常布局后重新定位正在编辑的重试延迟字段。 */
        @Override
        public void doLayout() {
            super.doLayout();
            positionRetryEditor();
        }

        /** 从当前流程分支读取真实目标 ID，并依据最新卡片边界计算远距离显示路径。 */
        List<TimelineBranchLayout.Route> branchRoutes() {
            java.util.Map<String, Rectangle> bounds = new java.util.LinkedHashMap<>();
            int sequenceY = Integer.MIN_VALUE;
            for (Component component : getComponents()) if (component instanceof NodePanel node) {
                bounds.put(node.macroNode.getId(), node.getBounds());
            }
            for (Component component : getComponents()) if (component instanceof Container connector) {
                for (Component child : connector.getComponents()) if (child instanceof ArrowComponent) {
                    sequenceY = Math.max(sequenceY,
                            SwingUtilities.convertPoint(child, 0, child.getHeight() / 2, this).y);
                }
            }
            return sequenceY == Integer.MIN_VALUE ? TimelineBranchLayout.routes(bounds, jumpEdges())
                    : TimelineBranchLayout.routes(bounds, jumpEdges(), sequenceY);
        }

        /** 按对应节点的当前重试标签位置调整输入框，避免轨道布局后编辑框停留在旧坐标。 */
        private void positionRetryEditor() {
            if (retryEditor == null) return;
            for (Component component : getComponents()) {
                if (component instanceof NodePanel node && node.macroNode == retryNode) {
                    Rectangle bounds = retryBounds(node);
                    bounds.width = Math.max(88, bounds.width);
                    bounds.x = node.getX() + (node.getWidth() - bounds.width) / 2;
                    retryEditor.setBounds(bounds);
                    return;
                }
            }
        }

        /**
         * 按成功和失败配置合并绘制自回路与终止标记。
         *
         * <p>循环线只读取现有分支的延迟和回指关系，不创建额外组件节点，
         * 也不参与流程数据的重建。</p>
         */
        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
            );
            g2.setColor(THEME.accent());
            g2.setStroke(new BasicStroke(
                    1.7f,
                    BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND
            ));

            for (Component component : getComponents()) {
                if (component instanceof NodePanel node
                        && node.macroNode != null
                        && node.macroNode.getType() == NodeType.IMAGE_CHECK) {
                    String retryConditions = behaviorConditions(node.macroNode, ImageCheckBehavior.RETRY);
                    if (!retryConditions.isEmpty()) {
                        drawImageCheckSelfLoop(g2, node,
                                MacroFlowFactory.findRetryDelay(node.macroNode), retryConditions);
                    }
                    String terminateConditions = behaviorConditions(node.macroNode, ImageCheckBehavior.TERMINATE);
                    if (!terminateConditions.isEmpty()) {
                        drawTermination(g2, node, terminateConditions, !retryConditions.isEmpty());
                    }
                }
            }
            List<TimelineBranchLayout.Route> routes = branchRoutes();
            for (TimelineBranchLayout.Route route : routes) drawJump(g2, route);
            for (TimelineBranchLayout.Route route : routes) drawJumpLabel(g2, route, routes);
            g2.dispose();
        }

        /** 绘制一条远距离分支路径，并让最终箭头从目标卡片侧边水平进入。 */
        private void drawJump(Graphics2D graphics, TimelineBranchLayout.Route route) {
            Path2D path = TimelineBranchLayout.path(route);
            // 交叉处留出细小断口，避免多条轨道看起来合并为一条线。
            graphics.setColor(THEME.card());
            graphics.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            graphics.draw(path);
            graphics.setColor(route.forward() ? THEME.accent() : THEME.danger());
            graphics.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            List<Point> points = route.points();
            graphics.draw(path);
            Point end = points.get(points.size() - 1);
            int direction = route.forward() ? 1 : -1;
            graphics.drawLine(end.x - direction * 7, end.y - 4, end.x, end.y);
            graphics.drawLine(end.x - direction * 7, end.y + 4, end.x, end.y);
        }

        /** 依据所有分支路径寻找标签可用区域，避开跨越当前轨道的竖线。 */
        private void drawJumpLabel(Graphics2D graphics, TimelineBranchLayout.Route route,
                List<TimelineBranchLayout.Route> routes) {
            graphics.setColor(route.forward() ? THEME.accent() : THEME.danger());
            graphics.setFont(getFont().deriveFont(Font.PLAIN, 11f));
            FontMetrics metrics = graphics.getFontMetrics();
            String text = route.edge().label();
            Rectangle label = TimelineBranchLayout.labelBounds(route, routes, metrics.stringWidth(text), getWidth());
            int center = label.x + label.width / 2;
            int left = Math.min(route.points().get(2).x, route.points().get(3).x);
            int right = Math.max(route.points().get(2).x, route.points().get(3).x);
            graphics.drawLine(Math.max(left, Math.min(center, right)), route.laneY(), center, route.laneY());
            Color color = graphics.getColor();
            graphics.setColor(THEME.targetCard());
            graphics.fillRoundRect(label.x, label.y, label.width, label.height, 10, 10);
            graphics.setColor(blend(THEME.border(), color, .35f));
            graphics.setStroke(new BasicStroke(1f));
            graphics.drawRoundRect(label.x, label.y, label.width, label.height, 10, 10);
            graphics.setColor(color);
            graphics.drawString(text, label.x + 10, route.laneY() + 4);
        }

        /** 绘制从节点右侧发出、在左侧以向右箭头回收的闭合分支。 */
        private void drawImageCheckSelfLoop(
                Graphics2D graphics,
                NodePanel node,
                long retryDelay,
                String conditions
        ) {
            graphics.setColor(THEME.accent());
            int left = node.getX();
            int right = node.getX() + node.getWidth() - 1;
            int entryY = node.getY() + node.getHeight() - TimelineBranchLayout.RETRY_PORT_BOTTOM_OFFSET;
            int bottomY = node.getY()
                    + node.getHeight()
                    + SELF_LOOP_BOTTOM_OFFSET;
            int outerLeft = left - SELF_LOOP_SIDE_EXTENSION;
            int outerRight = right + SELF_LOOP_SIDE_EXTENSION;
            int cornerRadius = 12;

            String delayText = formatDelay(retryDelay);
            graphics.setFont(getFont().deriveFont(Font.PLAIN, 11f));
            FontMetrics metrics = graphics.getFontMetrics();
            int labelHalfWidth = metrics.stringWidth(delayText) / 2;
            int lineGap = 10;
            int centerX = left + node.getWidth() / 2;

            // 右侧只作为回路起点，不绘制箭头。
            Path2D rightHalf = new Path2D.Double();
            rightHalf.moveTo(right, entryY);
            rightHalf.lineTo(outerRight - cornerRadius, entryY);
            rightHalf.quadTo(outerRight, entryY, outerRight, entryY + cornerRadius);
            rightHalf.lineTo(outerRight, bottomY - cornerRadius);
            rightHalf.quadTo(outerRight, bottomY, outerRight - cornerRadius, bottomY);
            rightHalf.lineTo(centerX + labelHalfWidth + lineGap, bottomY);
            graphics.draw(rightHalf);

            Path2D leftHalf = new Path2D.Double();
            leftHalf.moveTo(centerX - labelHalfWidth - lineGap, bottomY);
            leftHalf.lineTo(outerLeft + cornerRadius, bottomY);
            leftHalf.quadTo(outerLeft, bottomY, outerLeft, bottomY - cornerRadius);
            leftHalf.lineTo(outerLeft, entryY + cornerRadius);
            leftHalf.quadTo(outerLeft, entryY, outerLeft + cornerRadius, entryY);
            leftHalf.lineTo(left, entryY);
            graphics.draw(leftHalf);

            // 仅在左侧回收端绘制指向 IMAGE_CHECK 的向右箭头。
            graphics.drawLine(left - 7, entryY - 5, left, entryY);
            graphics.drawLine(left - 7, entryY + 5, left, entryY);

            graphics.setColor(THEME.textSecondary());
            int textX = centerX - metrics.stringWidth(delayText) / 2;
            int textY = bottomY + (metrics.getAscent() - metrics.getDescent()) / 2;
            if (retryNode != node.macroNode || retryEditor == null) {
                graphics.drawString(delayText, textX, textY);
            }
            String conditionText = conditions + " · 重试";
            graphics.drawString(conditionText,
                    centerX - metrics.stringWidth(conditionText) / 2, bottomY - 12);
        }

        /** 用终止符和条件文字表达整任务结束，不绘制指向后继的箭头。 */
        private void drawTermination(Graphics2D graphics, NodePanel node,
                String conditions, boolean hasRetry) {
            graphics.setFont(getFont().deriveFont(Font.PLAIN, 11f));
            FontMetrics metrics = graphics.getFontMetrics();
            String text = conditions + " · 终止";
            int width = metrics.stringWidth(text) + 30;
            int x = node.getX() + (node.getWidth() - width) / 2;
            int y = node.getY() + node.getHeight() + (hasRetry ? 49 : 18);
            graphics.setColor(THEME.targetCard());
            graphics.fillRoundRect(x, y, width, 23, 8, 8);
            graphics.setColor(THEME.danger());
            graphics.drawRoundRect(x, y, width, 23, 8, 8);
            graphics.fillRect(x + 8, y + 8, 7, 7);
            graphics.drawString(text, x + 21, y + 16);
        }

        /** 返回外层窗口布局使用的固定视口首选尺寸，避免长轨道撑大整个窗口。 */
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return new Dimension(TIMELINE_VIEWPORT_WIDTH, TIMELINE_VIEWPORT_HEIGHT);
        }

        /** 分别返回水平和垂直方向的单步滚动距离。 */
        @Override
        public int getScrollableUnitIncrement(
                Rectangle visibleRect,
                int orientation,
                int direction
        ) {
            return orientation == SwingConstants.HORIZONTAL ? 20 : 16;
        }

        /** 按当前可见宽高计算翻页距离，并保留一小段上下文。 */
        @Override
        public int getScrollableBlockIncrement(
                Rectangle visibleRect,
                int orientation,
                int direction
        ) {
            int extent = orientation == SwingConstants.HORIZONTAL
                    ? visibleRect.width
                    : visibleRect.height;
            return Math.max(20, extent - 20);
        }

        /** 保持轨道独立宽度，让超出视口的操作卡通过水平滚动访问。 */
        @Override
        public boolean getScrollableTracksViewportWidth() {
            return false;
        }

        /** 内容较矮时填满视口，内容更高时保留实际高度以便垂直滚动。 */
        @Override
        public boolean getScrollableTracksViewportHeight() {
            return getParent() == null || getParent().getHeight() >= getPreferredSize().height;
        }
    }

    private enum IconType {
        MOUSE,
        KEYBOARD,
        WHEEL,
        IMAGE,
        CLOCK,
        INFO,
        EDIT,
        INSERT,
        DELETE,
        COPY,
        PASTE,
        UNDO,
        EMPTY
    }

    /** 统一绘制时间线卡片和菜单中的轻量功能图标。 */
    private static class TimelineIcon extends JComponent implements Icon {
        private final IconType type;
        private final int size;
        private final Color color;

        /** 保存图标用途、逻辑尺寸和主题颜色。 */
        private TimelineIcon(IconType type, int size, Color color) {
            this.type = type;
            this.size = size;
            this.color = color;
            setPreferredSize(new Dimension(size, size));
            setMinimumSize(new Dimension(size, size));
            setMaximumSize(new Dimension(size, size));
        }

        /** 把组件形式的图标绘制委托给同一图标入口。 */
        @Override
        protected void paintComponent(Graphics graphics) {
            paintIcon(this, graphics, 0, 0);
        }

        /** 按功能类型绘制主题线条图标，使用独立图形副本避免改变调用方状态。 */
        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.translate(x, y);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.setStroke(new BasicStroke(Math.max(1.5f, size / 12f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int p = Math.max(2, size / 6);
            switch (type) {
                case MOUSE -> {
                    g2.drawRoundRect(p, 1, size - p * 2, size - 2, size / 2, size / 2);
                    g2.drawLine(size / 2, 2, size / 2, size / 3);
                }
                case KEYBOARD -> {
                    g2.drawRoundRect(1, size / 5, size - 3, size * 3 / 5, 4, 4);
                    for (int row = 0; row < 2; row++) {
                        for (int col = 0; col < 4; col++) {
                            g2.fillRoundRect(4 + col * Math.max(3, size / 5), size / 3 + row * Math.max(3, size / 5), 2, 2, 1, 1);
                        }
                    }
                    g2.drawLine(size / 3, size * 2 / 3, size * 2 / 3, size * 2 / 3);
                }
                case WHEEL -> {
                    g2.drawOval(2, 2, size - 5, size - 5);
                    g2.drawLine(size / 2, size / 4, size / 2, size * 3 / 4);
                    g2.drawLine(size / 2 - 3, size * 2 / 3, size / 2, size * 3 / 4);
                    g2.drawLine(size / 2 + 3, size * 2 / 3, size / 2, size * 3 / 4);
                }
                case IMAGE -> {
                    g2.drawRoundRect(2, 3, size - 5, size - 7, 4, 4);
                    g2.drawOval(size * 2 / 3, size / 4, 3, 3);
                    g2.drawLine(4, size - 7, size / 3, size / 2);
                    g2.drawLine(size / 3, size / 2, size / 2, size * 2 / 3);
                    g2.drawLine(size / 2, size * 2 / 3, size - 4, size / 3);
                }
                case CLOCK -> {
                    g2.drawOval(2, 2, size - 5, size - 5);
                    g2.drawLine(size / 2, size / 2, size / 2, size / 4);
                    g2.drawLine(size / 2, size / 2, size * 3 / 4, size / 2);
                }
                case INFO -> {
                    g2.drawOval(1, 1, size - 3, size - 3);
                    g2.fillOval(size / 2 - 1, size / 4, 3, 3);
                    g2.drawLine(size / 2, size / 2, size / 2, size * 3 / 4);
                }
                case EDIT -> {
                    g2.drawLine(3, size - 4, size - 3, 3);
                    g2.drawLine(2, size - 2, 6, size - 3);
                    g2.drawLine(size - 5, 2, size - 2, 5);
                }
                case INSERT -> {
                    g2.drawRoundRect(2, 2, size - 5, size - 5, 4, 4);
                    g2.drawLine(size / 2, 5, size / 2, size - 6);
                    g2.drawLine(5, size / 2, size - 6, size / 2);
                }
                case DELETE -> {
                    g2.drawRoundRect(4, 5, size - 8, size - 8, 2, 2);
                    g2.drawLine(2, 4, size - 2, 4);
                    g2.drawLine(size / 3, 1, size * 2 / 3, 1);
                }
                case COPY -> {
                    g2.drawRoundRect(1, 1, size - 6, size - 6, 3, 3);
                    g2.drawRoundRect(5, 5, size - 6, size - 6, 3, 3);
                }
                case PASTE -> {
                    g2.drawRoundRect(size / 4, size / 5, size * 2 / 3 - 2, size * 3 / 4, 3, 3);
                    g2.drawRoundRect(size / 3, 1, size / 3, size / 5, 2, 2);
                    g2.drawLine(size / 2, size / 2, size * 3 / 4, size / 2);
                    g2.drawLine(size / 2, size * 2 / 3, size * 3 / 4, size * 2 / 3);
                }
                case UNDO -> {
                    g2.drawArc(3, size / 4, size - 6, size * 2 / 3, -45, 235);
                    g2.drawLine(3, size / 4, 3, size / 2);
                    g2.drawLine(3, size / 2, size / 3 + 2, size / 2);
                }
                case EMPTY -> {
                    g2.drawRoundRect(2, size / 4, size - 5, size / 2, 6, 6);
                    g2.drawLine(size / 4, size / 2, size * 3 / 4, size / 2);
                }
            }
            g2.dispose();
        }

        /** 返回图标的逻辑宽度。 */
        @Override
        public int getIconWidth() { return size; }

        /** 返回图标的逻辑高度。 */
        @Override
        public int getIconHeight() { return size; }
    }

    /** 按比例混合两个主题颜色，为卡片选择状态生成过渡色。 */
    private static Color blend(Color base, Color overlay, float amount) {
        float inverse = 1f - amount;
        return new Color(
                Math.round(base.getRed() * inverse + overlay.getRed() * amount),
                Math.round(base.getGreen() * inverse + overlay.getGreen() * amount),
                Math.round(base.getBlue() * inverse + overlay.getBlue() * amount)
        );
    }
}
