package com.macro.ui;

import com.macro.config.TimelineInsertPosition;
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
import java.awt.datatransfer.StringSelection;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

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

    private final JPanel timelinePanel;
    private final JLabel actionCountValue;
    private final JLabel totalDurationValue;
    /** 按时间线显示顺序保存的可见节点，不包含内部 END 节点。 */
    private final List<MacroNode> nodes = new ArrayList<>();
    private java.util.Map<String, String> displayNumbers = java.util.Map.of();

    /** 当前时间线对应的完整流程图，是节点编辑和播放的权威数据。
     * -- GETTER --
     * 返回包含 END 节点和全部分支的当前流程图。
     */
    @Getter
    private MacroFlow flow = MacroFlowFactory.fromActions(List.of());

    @Getter
    private MacroNode selectedMacroNode;
    private NodePanel selectedNode;
    /** 当前行内编辑会话；重建时间线前先取消，避免旧组件延迟提交。 */
    private InlineEditSession activeEdit;
    private long timelineRevision;

    /** 实际播放状态由主窗口提供；本地开关只补足播放线程启动及收尾的边界。 */
    private BooleanSupplier editPermission = () -> true;
    private boolean editingEnabled = true;
    private long editGeneration;

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

    public ActionTimelinePanel() {
        setLayout(new BorderLayout(0, 18));
        setOpaque(false);
        setFocusable(true);
        installDeleteKeyBinding();

        JPanel heading = new JPanel(new BorderLayout(0, 12));
        heading.setOpaque(false);

        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        JLabel title = label("动作时间线", 28, Font.BOLD, THEME.textPrimary());
        JLabel description = label("按时间顺序查看和管理录制的鼠标与键盘动作", 14, Font.PLAIN, THEME.textSecondary());
        title.setAlignmentX(LEFT_ALIGNMENT);
        description.setAlignmentX(LEFT_ALIGNMENT);
        titles.add(title);
        titles.add(Box.createVerticalStrut(7));
        titles.add(description);
        heading.add(titles, BorderLayout.NORTH);

        JPanel stats = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        stats.setOpaque(false);
        actionCountValue = label("0 个", 19, Font.BOLD, THEME.textPrimary());
        totalDurationValue = label("0 ms", 19, Font.BOLD, THEME.textPrimary());
        stats.add(createStatCard(IconType.MOUSE, "动作数量", actionCountValue, 116));
        stats.add(createStatCard(IconType.CLOCK, "总时长", totalDurationValue, 148));
        heading.add(stats, BorderLayout.SOUTH);
        add(heading, BorderLayout.NORTH);

        RoundedPanel card = new RoundedPanel(THEME.card(), THEME.border(), 18);
        card.setLayout(new BorderLayout());
        card.setBorder(new EmptyBorder(16, 18, 18, 18));

        JPanel hintPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        hintPanel.setOpaque(false);
        hintPanel.add(new TimelineIcon(IconType.INFO, 16, THEME.textMuted()));
        hintPanel.add(label("双击动作可编辑，单击坐标可直接修改", 12, Font.PLAIN, THEME.textMuted()));
        card.add(hintPanel, BorderLayout.NORTH);

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
        card.add(scrollPane, BorderLayout.CENTER);
        add(card, BorderLayout.CENTER);

        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && !isShowing()) {
                cancelInlineEdit();
            }
        });

        rebuildTimeline();
    }

    public void setActions(List<MacroAction> newActions) {
        setFlow(MacroFlowFactory.fromActions(newActions));
    }

    /** 每次写入时重新读取权限，不依赖异步状态通知是否已到达 EDT。 */
    public void setEditPermission(BooleanSupplier permission) {
        editPermission = Objects.requireNonNull(permission);
        if (!isEditingAllowed()) {
            editGeneration++;
            cancelInlineEdit();
        }
    }

    /** 只限制编辑，不禁用面板、节点选择、滚动或复制。 */
    public void setEditingEnabled(boolean enabled) {
        if (editingEnabled != enabled) {
            editingEnabled = enabled;
            editGeneration++;
        }
        if (!enabled) cancelInlineEdit();
    }

    public boolean isEditingAllowed() {
        return editingEnabled && editPermission.getAsBoolean();
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
        flow = newFlow == null
                ? MacroFlowFactory.fromActions(List.of())
                : newFlow;
        nodes.clear();
        nodes.addAll(MacroFlowFactory.timelineNodes(flow));
        selectedMacroNode = null;
        selectedNode = null;
        rebuildTimeline();
    }

    /** 提取 ACTION 节点中的动作，供旧 MR actions 字段兼容使用。 */
    public List<MacroAction> getActions() {
        return MacroFlowFactory.extractActions(flow);
    }

    public void refreshTimeline() {
        MacroNode previousSelection = selectedMacroNode;
        rebuildTimeline();
        if (previousSelection != null) {
            selectMacroNode(previousSelection);
        }
    }

    public boolean deleteAction(int index, boolean preserveDelay) {
        if (!isEditingAllowed() || index < 0 || index >= nodes.size()) {
            return false;
        }
        MacroNode node = nodes.get(index);
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
        return true;
    }

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

    private boolean insertAt(int index, MacroNode newNode) {
        if (!isEditingAllowed()) return false;
        newNode.setId(MacroFlowFactory.newNodeId());
        nodes.add(index, newNode);
        selectedMacroNode = newNode;
        selectedNode = null;
        rebuildFlowGraph();
        rebuildTimeline();
        selectMacroNode(newNode);
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

        newNode.setId(originalNode.getId());
        nodes.set(actionIndex, newNode);
        selectedMacroNode = newNode;
        selectedNode = null;
        rebuildFlowGraph();
        rebuildTimeline();
        selectMacroNode(newNode);
        return true;
    }

    /** 按当前显示顺序重建 ACTION、IMAGE_CHECK 和 END 的默认分支。 */
    private void rebuildFlowGraph() {
        flow = MacroFlowFactory.rebuildLinear(
                nodes,
                MacroFlowFactory.findEndId(flow)
        );
    }

    private void rebuildTimeline() {
        cancelInlineEdit();
        timelineRevision++;
        timelinePanel.removeAll();
        displayNumbers = TimelineNodeNumbers.labels(nodes);
        int branchCount = jumpEdges().size();
        timelinePanel.setBorder(new EmptyBorder(10, 50, branchCount == 0 ? 80 : 110 + branchCount * 32, 50));
        updateSummary();

        if (nodes.isEmpty()) {
            JPanel empty = new JPanel();
            empty.setOpaque(false);
            empty.setLayout(new BoxLayout(empty, BoxLayout.Y_AXIS));
            TimelineIcon icon = new TimelineIcon(IconType.EMPTY, 30, THEME.textMuted());
            icon.setAlignmentX(CENTER_ALIGNMENT);
            JLabel message = label("暂无动作，完成录制后将在这里显示时间线", 13, Font.PLAIN, THEME.textMuted());
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
            for (int index = 0; index < nodes.size(); index++) {
                MacroNode node = nodes.get(index);
                timelinePanel.add(createActionNode(node, displayNumbers.get(node.getId())));
                if (index < nodes.size() - 1) {
                    // 延迟属于后一个动作，表示当前节点与后一个节点之间的等待时间。
                    timelinePanel.add(createDelayConnector(node, nodes.get(index + 1)));
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
        JLabel captionLabel = label(caption, 11, Font.PLAIN, THEME.textSecondary());
        captionLabel.setAlignmentX(LEFT_ALIGNMENT);
        value.setAlignmentX(LEFT_ALIGNMENT);
        text.add(captionLabel);
        text.add(Box.createVerticalStrut(2));
        text.add(value);
        card.add(text, BorderLayout.CENTER);
        return card;
    }

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
        JLabel name = label(getNodeName(macroNode), 14, Font.BOLD, THEME.textPrimary());
        name.setAlignmentX(CENTER_ALIGNMENT);

        node.add(icon);
        node.add(Box.createVerticalStrut(8));
        node.add(name);
        node.add(Box.createVerticalStrut(11));
        addActionDetails(node, macroNode);
        node.add(Box.createVerticalGlue());

        MouseAdapter listener = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event)) {
                    selectNode(node, macroNode);
                }
                maybeShowPopup(event, node, macroNode);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                maybeShowPopup(event, node, macroNode);
            }

            @Override
            public void mouseClicked(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event)
                        && event.getClickCount() == 2
                        && isEditingAllowed()
                        && onActionDoubleClicked != null) {
                    onActionDoubleClicked.accept(macroNode);
                }
            }
        };
        node.addMouseListener(listener);
        installPopupListener(node, listener);

        if (macroNode == selectedMacroNode) {
            node.setSelected(true);
            selectedNode = node;
        }
        return node;
    }

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
                : config.getSuccessBehavior()) == behavior;
        boolean failure = (config == null ? ImageCheckBehavior.RETRY
                : config.getFailureBehavior()) == behavior;
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
                if (behavior != ImageCheckBehavior.JUMP && behavior != ImageCheckBehavior.EXECUTE) continue;
                var branch = MacroFlowFactory.branchFor(node, condition);
                if (branch == null) continue;
                String target = branch.getTargetId();
                int targetIndex = -1;
                for (int j = 0; j < nodes.size(); j++) if (Objects.equals(nodes.get(j).getId(), target)) { targetIndex = j; break; }
                if (targetIndex < 0 || targetIndex == i || behavior == ImageCheckBehavior.EXECUTE && targetIndex == i + 1) continue;
                result.add(new TimelineBranchLayout.Edge(node.getId(), target,
                        (condition == BranchCondition.FOUND ? "成功" : "未找到") + " · "
                                + (targetIndex > i ? "前跳" : "回跳") + " " + displayNumbers.get(target)));
            }
        }
        return result;
    }

    private static boolean defaultBranchTo(MacroNode node, BranchCondition condition, String targetId) {
        if (node.getImageCheckConfig() == null) return false;
        ImageCheckBehavior behavior = condition == BranchCondition.FOUND
                ? node.getImageCheckConfig().getSuccessBehavior() : node.getImageCheckConfig().getFailureBehavior();
        var branch = MacroFlowFactory.branchFor(node, condition);
        return behavior == ImageCheckBehavior.EXECUTE && branch != null && Objects.equals(branch.getTargetId(), targetId);
    }

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

        if (imageBranch) {
            String nextId = macroNode == null ? MacroFlowFactory.findEndId(flow) : macroNode.getId();
            boolean success = defaultBranchTo(previousNode, BranchCondition.FOUND, nextId);
            boolean failure = defaultBranchTo(previousNode, BranchCondition.NOT_FOUND, nextId);
            String conditions = success && failure ? "成功 / 未找到" : success ? "识别成功" : failure ? "未找到" : "";
            if (conditions.isEmpty()) {
                // 没有执行分支时保留节点间距，但不能绘制通向后续节点的箭头。
                return connector;
            }
            connector.add(Box.createVerticalGlue());
            JLabel condition = label(conditions, 11, Font.PLAIN, THEME.accent());
            condition.setAlignmentX(CENTER_ALIGNMENT);
            connector.add(condition);
            connector.add(Box.createVerticalStrut(4));
        } else {
            connector.add(Box.createVerticalGlue());
        }

        MacroAction action = macroNode != null && macroNode.getType() == NodeType.ACTION
                ? macroNode.getAction()
                : null;
        JLabel delay = label(
                macroNode == null ? "本轮结束" : action == null ? "检查" : formatDelay(action.getDelay()),
                11,
                Font.PLAIN,
                THEME.textSecondary()
        );
        delay.setAlignmentX(CENTER_ALIGNMENT);
        delay.setToolTipText(action == null ? "图像识别节点" : "单击编辑动作间隔");
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

    private void beginDelayEdit(JPanel connector, JLabel delayLabel, JComponent arrow, MacroAction action) {
        int labelIndex = indexOf(connector, delayLabel);
        if (labelIndex < 0 || !prepareInlineEdit()) {
            return;
        }
        JTextField editor = new JTextField(String.valueOf(action.getDelay()));
        editor.setHorizontalAlignment(SwingConstants.CENTER);
        editor.setFont(delayLabel.getFont());
        editor.setForeground(THEME.textPrimary());
        editor.setBackground(THEME.controlBackground());
        editor.setCaretColor(THEME.textPrimary());
        editor.setBorder(BorderFactory.createLineBorder(THEME.accent()));
        editor.setMaximumSize(new Dimension(65, 25));
        editor.setPreferredSize(new Dimension(65, 25));
        NumericInputUtil.installNonNegativeLong(editor, "动作延迟（ms）");

        connector.remove(delayLabel);
        connector.add(editor, labelIndex);
        connector.revalidate();
        connector.repaint();

        startInlineEdit(editor, () -> NumericInputUtil.readLong(editor), value -> {
            action.setDelay(value);
            delayLabel.setText(formatDelay(action.getDelay()));
            updateSummary();
        }, () -> replaceEditor(connector, editor, delayLabel, arrow));
    }

    /** 修改共用重试值，同时写回真正参与播放的重试分支，不重建或改写跳转关系。 */
    private void beginRetryDelayEdit(MacroNode node) {
        if (!nodes.contains(node) || node.getImageCheckConfig() == null
                || behaviorConditions(node, ImageCheckBehavior.RETRY).isEmpty()
                || !prepareInlineEdit()) return;
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

    /** 同一时刻只编辑一个数值，无效文本留在原输入框并显示统一校验提示。 */
    private boolean prepareInlineEdit() {
        if (!isEditingAllowed()) {
            cancelInlineEdit();
            return false;
        }
        return activeEdit == null || activeEdit.commit(false);
    }

    private void startInlineEdit(JTextField editor, LongSupplier parser,
            LongConsumer setter, Runnable restore) {
        InlineEditSession session = new InlineEditSession(editor, parser, setter, restore);
        activeEdit = session;
        session.install();
    }

    private void cancelInlineEdit() {
        if (activeEdit != null) activeEdit.cancel();
    }

    @Override
    public void removeNotify() {
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

        private InlineEditSession(JTextField editor, LongSupplier parser,
                LongConsumer setter, Runnable restore) {
            this.editor = editor;
            this.parser = parser;
            this.setter = setter;
            this.restore = restore;
            wasShowing = editor.isShowing();
        }

        private void install() {
            editor.addActionListener(event -> commit(true));
            editor.addFocusListener(new FocusAdapter() {
                @Override
                public void focusLost(FocusEvent event) {
                    commit(false);
                }
            });
            editor.addKeyListener(new KeyAdapter() {
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

        private void handleMouseEvent(AWTEvent event) {
            if (!(event instanceof MouseEvent mouse) || mouse.getID() != MouseEvent.MOUSE_PRESSED
                    || !(mouse.getSource() instanceof Component source)
                    || source == editor || SwingUtilities.isDescendingFrom(source, editor)) return;
            Window owner = SwingUtilities.getWindowAncestor(editor);
            if (owner == null || SwingUtilities.getWindowAncestor(source) != owner) return;
            // JLabel、空白面板不会主动取得焦点，因此在普通点击分发前完成有效提交。
            if (commit(false) && !source.isFocusable()) requestFocusInWindow();
        }

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
            setter.accept(value);
            restore.run();
            return true;
        }

        private void cancel() {
            if (finished) return;
            finish();
            restore.run();
        }

        private void finish() {
            finished = true;
            Toolkit.getDefaultToolkit().removeAWTEventListener(clickListener);
            if (activeEdit == this) activeEdit = null;
        }
    }

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

    private int indexOf(Container parent, Component child) {
        Component[] components = parent.getComponents();
        for (int i = 0; i < components.length; i++) {
            if (components[i] == child) return i;
        }
        return -1;
    }

    private void installPopupListener(Container container, MouseAdapter listener) {
        for (Component child : container.getComponents()) {
            if (child instanceof CoordinateEditor) continue;
            child.addMouseListener(listener);
            if (child instanceof Container childContainer) {
                installPopupListener(childContainer, listener);
            }
        }
    }

    private void maybeShowPopup(MouseEvent event, NodePanel node, MacroNode macroNode) {
        if (!event.isPopupTrigger()) return;
        selectNode(node, macroNode);
        createContextMenu(macroNode).show(
                event.getComponent(),
                event.getX(),
                event.getY()
        );
    }

    private JPopupMenu createContextMenu(MacroNode macroNode) {
        JPopupMenu menu = new JPopupMenu();
        menu.setOpaque(true);
        menu.setBackground(THEME.targetCard());
        menu.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(THEME.border()),
                new EmptyBorder(5, 5, 5, 5)
        ));

        JMenuItem edit = createMenuItem("编辑动作", IconType.EDIT);
        edit.setEnabled(isEditingAllowed());
        Consumer<java.awt.event.ActionEvent> editRequest = guardEditCallback(event -> {
            if (onActionEditRequested != null) onActionEditRequested.accept(macroNode);
        });
        edit.addActionListener(editRequest::accept);
        JMenuItem insert = createMenuItem("插入动作", IconType.INSERT);
        insert.setEnabled(isEditingAllowed());
        Consumer<java.awt.event.ActionEvent> insertRequest = guardEditCallback(event -> {
            if (onActionInsertRequested != null) onActionInsertRequested.accept(macroNode);
        });
        insert.addActionListener(insertRequest::accept);
        JMenuItem delete = createMenuItem("删除动作", IconType.DELETE);
        delete.setEnabled(isEditingAllowed());
        Consumer<java.awt.event.ActionEvent> deleteRequest = guardEditCallback(event -> deleteAndNotify(macroNode));
        delete.addActionListener(deleteRequest::accept);
        JMenuItem copy = createMenuItem("复制信息", IconType.COPY);
        copy.addActionListener(event -> copyActionInfo(macroNode));

        if (macroNode == null) {
            menu.add(insert);
            return menu;
        }
        menu.add(edit);
        menu.add(insert);
        menu.add(delete);
        menu.add(copy);
        return menu;
    }

    private JMenuItem createMenuItem(String text, IconType iconType) {
        JMenuItem item = new JMenuItem(text, new TimelineIcon(iconType, 16, THEME.textPrimary()));
        item.setFont(item.getFont().deriveFont(Font.PLAIN, 13f));
        item.setForeground(THEME.textPrimary());
        item.setBackground(THEME.targetCard());
        item.setBorder(new EmptyBorder(8, 9, 8, 18));
        item.setOpaque(true);
        item.setUI(new BasicMenuItemUI() {
            @Override
            protected void installDefaults() {
                super.installDefaults();
                selectionBackground = THEME.controlBackground();
                selectionForeground = THEME.textPrimary();
            }
        });
        return item;
    }

    private void copyActionInfo(MacroNode macroNode) {
        String info = getNodeName(macroNode)
                + " · "
                + getNodeDetail(macroNode);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(info), null);
    }

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

    private void selectMacroNode(MacroNode macroNode) {
        for (Component component : timelinePanel.getComponents()) {
            if (component instanceof NodePanel node
                    && node.macroNode == macroNode) {
                selectNode(node, macroNode);
                return;
            }
        }
    }

    private void selectNode(NodePanel node, MacroNode macroNode) {
        if (selectedNode != null && selectedNode != node) {
            selectedNode.setSelected(false);
        }
        selectedNode = node;
        selectedMacroNode = macroNode;
        node.setSelected(true);
        requestFocusInWindow();
        if (onActionSelected != null) {
            onActionSelected.accept(macroNode);
        }
    }

    /** 仅在时间线面板自身获得焦点时响应 Delete。 */
    private void installDeleteKeyBinding() {
        String actionKey = "deleteSelectedTimelineAction";
        getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0),
                actionKey
        );
        getActionMap().put(actionKey, new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                if (selectedMacroNode != null) {
                    deleteAndNotify(selectedMacroNode);
                }
            }
        });
    }

    private void updateSummary() {
        actionCountValue.setText(nodes.size() + " 个");
        long total = 0L;
        for (MacroNode node : nodes) {
            if (node.getType() == NodeType.ACTION && node.getAction() != null) {
                total += Math.max(0L, node.getAction().getDelay());
            }
        }
        totalDurationValue.setText(formatTotalDuration(total));
    }

    private String formatTotalDuration(long milliseconds) {
        if (milliseconds < 1000) return milliseconds + " ms";
        double seconds = milliseconds / 1000.0;
        return String.format(Locale.ROOT, seconds >= 10 ? "%.1f 秒" : "%.2f 秒", seconds);
    }

    private String formatDelay(long delay) {
        return Math.max(0L, delay) + " ms";
    }

    private boolean isMouseAction(ActionType type) {
        return type == ActionType.MOUSE_MOVE
                || type == ActionType.MOUSE_PRESS
                || type == ActionType.MOUSE_RELEASE
                || type == ActionType.MOUSE_WHEEL;
    }

    private IconType iconFor(MacroNode node) {
        if (node.getType() == NodeType.IMAGE_CHECK) {
            return IconType.IMAGE;
        }
        ActionType type = node.getAction().getType();
        if (type == ActionType.KEY_PRESS || type == ActionType.KEY_RELEASE) return IconType.KEYBOARD;
        if (type == ActionType.MOUSE_WHEEL) return IconType.WHEEL;
        return IconType.MOUSE;
    }

    static String getNodeName(MacroNode node) {
        return node.getType() == NodeType.IMAGE_CHECK
                ? "图像识别"
                : getActionName(node.getAction());
    }

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

    private String getMouseDetail(MacroAction action) {
        return switch (action.getType()) {
            case MOUSE_WHEEL -> action.getWheelAmount() > 0
                    ? "向下 " + Math.abs(action.getWheelAmount()) + " 格"
                    : "向上 " + Math.abs(action.getWheelAmount()) + " 格";
            case MOUSE_PRESS, MOUSE_RELEASE -> getMouseButtonName(action.getMouseButton());
            default -> "位置";
        };
    }

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

    private String getMouseButtonName(MouseButton button) {
        if (button == null) return "无";
        return switch (button) {
            case LEFT -> "左键";
            case MIDDLE -> "中键";
            case RIGHT -> "右键";
            case X1, X2 -> "不支持的按键";
        };
    }

    private JLabel createPill(String text) {
        JLabel label = label(text, 12, Font.PLAIN, THEME.textSecondary());
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

    private static JLabel label(String text, int size, int style, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(style, (float) size));
        label.setForeground(color);
        return label;
    }

    private final class CoordinateEditor extends RoundedPanel {
        private final String axis;
        private final Consumer<Integer> setter;
        private int value;
        private final JLabel valueLabel;

        private CoordinateEditor(String axis, int value, Consumer<Integer> setter) {
            super(THEME.controlBackground(), THEME.border(), 9);
            this.axis = axis;
            this.value = value;
            this.setter = setter;
            setLayout(new BorderLayout());
            setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));
            valueLabel = label(axis + ": " + value, 11, Font.PLAIN, THEME.textPrimary());
            valueLabel.setHorizontalAlignment(SwingConstants.CENTER);
            add(valueLabel, BorderLayout.CENTER);
            MouseAdapter listener = new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent event) {
                    if (SwingUtilities.isLeftMouseButton(event)) {
                        beginEdit();
                    }
                }

                @Override
                public void mousePressed(MouseEvent event) {
                    if (SwingUtilities.isLeftMouseButton(event)) {
                        selectParentNode();
                    }
                    showCoordinatePopup(event);
                }

                @Override
                public void mouseReleased(MouseEvent event) {
                    showCoordinatePopup(event);
                }

                private void showCoordinatePopup(MouseEvent event) {
                    if (!event.isPopupTrigger()) return;
                    Container parent = CoordinateEditor.this.getParent();
                    while (parent != null && !(parent instanceof NodePanel)) parent = parent.getParent();
                    if (parent instanceof NodePanel node) {
                        selectNode(node, node.macroNode);
                        createContextMenu(node.macroNode).show(event.getComponent(), event.getX(), event.getY());
                    }
                }

                /** 选择坐标编辑器所属的动作节点。 */
                private void selectParentNode() {
                    Container parent = CoordinateEditor.this.getParent();
                    while (parent != null && !(parent instanceof NodePanel)) {
                        parent = parent.getParent();
                    }
                    if (parent instanceof NodePanel node) {
                        selectNode(node, node.macroNode);
                    }
                }
            };
            addMouseListener(listener);
            valueLabel.addMouseListener(listener);
        }

        private void beginEdit() {
            if (getComponentCount() == 1 && getComponent(0) instanceof JTextField) return;
            if (!prepareInlineEdit()) return;
            JTextField editor = new JTextField(String.valueOf(value));
            editor.setHorizontalAlignment(SwingConstants.CENTER);
            editor.setFont(valueLabel.getFont());
            editor.setForeground(THEME.textPrimary());
            editor.setBackground(THEME.controlBackground());
            editor.setCaretColor(THEME.textPrimary());
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

    private final class NodePanel extends JPanel {
        private boolean selected;
        private MacroNode macroNode;
        private String displayNumber;

        private NodePanel() {
            setOpaque(false);
        }

        private void setSelected(boolean selected) {
            this.selected = selected;
            repaint();
        }

        /** 叠加绘制，不增加子组件、边距或布局占位。 */
        @Override
        protected void paintChildren(Graphics graphics) {
            super.paintChildren(graphics);
            if (displayNumber == null || displayNumber.isEmpty()) return;
            Graphics2D g2 = (Graphics2D) graphics.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setFont(getFont().deriveFont(Font.BOLD, 10f));
                FontMetrics metrics = g2.getFontMetrics();
                int width = metrics.stringWidth(displayNumber) + 12;
                g2.setColor(selected ? blend(THEME.controlBackground(), THEME.accent(), .18f)
                        : THEME.controlBackground());
                g2.fillRoundRect(8, 7, width, 18, 8, 8);
                g2.setColor(selected ? THEME.textPrimary() : THEME.textSecondary());
                g2.drawString(displayNumber, 14, 7 + (18 - metrics.getHeight()) / 2 + metrics.getAscent());
            } finally { g2.dispose(); }
        }

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
            g2.dispose();
            super.paintComponent(graphics);
        }
    }

    private static class RoundedPanel extends JPanel {
        private final Color fill;
        private final Color stroke;
        private final int arc;

        private RoundedPanel(Color fill, Color stroke, int arc) {
            this.fill = fill;
            this.stroke = stroke;
            this.arc = arc;
            setOpaque(false);
        }

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

    private static class ArrowComponent extends JComponent {
        private ArrowComponent() {
            setPreferredSize(new Dimension(MIN_CONNECTOR_WIDTH - 10, 14));
            setMaximumSize(new Dimension(MIN_CONNECTOR_WIDTH - 10, 14));
        }

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

        private TimelineTrackPanel() {
            MouseAdapter listener = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent event) {
                    if (SwingUtilities.isLeftMouseButton(event)) {
                        NodePanel hit = retryNodeAt(event.getPoint());
                        if (hit == null) ActionTimelinePanel.this.requestFocusInWindow();
                        else beginRetryDelayEdit(hit.macroNode);
                    }
                    showBackgroundPopup(event);
                }

                @Override
                public void mouseReleased(MouseEvent event) {
                    showBackgroundPopup(event);
                }

                @Override
                public void mouseMoved(MouseEvent event) {
                    setCursor(Cursor.getPredefinedCursor(retryNodeAt(event.getPoint()) == null
                            ? Cursor.DEFAULT_CURSOR : Cursor.TEXT_CURSOR));
                }

                private void showBackgroundPopup(MouseEvent event) {
                    if (event.isPopupTrigger()) {
                        createContextMenu(null).show(TimelineTrackPanel.this, event.getX(), event.getY());
                    }
                }
            };
            addMouseListener(listener);
            addMouseMotionListener(listener);
        }

        private NodePanel retryNodeAt(Point point) {
            for (Component component : getComponents()) {
                if (component instanceof NodePanel node && node.macroNode.getType() == NodeType.IMAGE_CHECK
                        && !behaviorConditions(node.macroNode, ImageCheckBehavior.RETRY).isEmpty()
                        && retryBounds(node).contains(point)) return node;
            }
            return null;
        }

        private Rectangle retryBounds(NodePanel node) {
            FontMetrics metrics = getFontMetrics(getFont().deriveFont(Font.PLAIN, 11f));
            int width = Math.max(68, metrics.stringWidth(formatDelay(
                    MacroFlowFactory.findRetryDelay(node.macroNode))) + 16);
            return new Rectangle(node.getX() + (node.getWidth() - width) / 2,
                    node.getY() + node.getHeight() + SELF_LOOP_BOTTOM_OFFSET - 13, width, 26);
        }

        @Override
        public void doLayout() {
            super.doLayout();
            positionRetryEditor();
        }

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

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return new Dimension(TIMELINE_VIEWPORT_WIDTH, TIMELINE_VIEWPORT_HEIGHT);
        }

        @Override
        public int getScrollableUnitIncrement(
                Rectangle visibleRect,
                int orientation,
                int direction
        ) {
            return orientation == SwingConstants.HORIZONTAL ? 20 : 16;
        }

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

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return false;
        }

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
        EMPTY
    }

    private static class TimelineIcon extends JComponent implements Icon {
        private final IconType type;
        private final int size;
        private final Color color;

        private TimelineIcon(IconType type, int size, Color color) {
            this.type = type;
            this.size = size;
            this.color = color;
            setPreferredSize(new Dimension(size, size));
            setMinimumSize(new Dimension(size, size));
            setMaximumSize(new Dimension(size, size));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            paintIcon(this, graphics, 0, 0);
        }

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
                case EMPTY -> {
                    g2.drawRoundRect(2, size / 4, size - 5, size / 2, 6, 6);
                    g2.drawLine(size / 4, size / 2, size * 3 / 4, size / 2);
                }
            }
            g2.dispose();
        }

        @Override
        public int getIconWidth() { return size; }

        @Override
        public int getIconHeight() { return size; }
    }

    private static Color blend(Color base, Color overlay, float amount) {
        float inverse = 1f - amount;
        return new Color(
                Math.round(base.getRed() * inverse + overlay.getRed() * amount),
                Math.round(base.getGreen() * inverse + overlay.getGreen() * amount),
                Math.round(base.getBlue() * inverse + overlay.getBlue() * amount)
        );
    }
}
