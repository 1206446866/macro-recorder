package com.macro.ui;

import com.macro.constant.UiConstants;

import com.macro.flow.BranchCondition;
import com.macro.flow.ImageCheckConfig;
import com.macro.flow.ImageCheckBehavior;
import com.macro.flow.MacroBranch;
import com.macro.flow.MacroFlowFactory;
import com.macro.flow.MacroNode;
import com.macro.flow.NodeType;
import com.macro.model.ActionType;
import com.macro.model.KeySide;
import com.macro.model.MacroAction;
import com.macro.model.MouseButton;
import com.macro.ui.dialog.AppMessageDialog;
import com.macro.ui.dialog.ImageRecognitionFrame;
import com.macro.ui.theme.ThemeManager;
import com.macro.ui.theme.ThemePalette;
import com.macro.ui.util.ComponentStyleUtil;
import com.macro.ui.util.NumericInputUtil;
import com.macro.ui.window.WindowTitleBarSupport;
import com.macro.util.ImageTemplatePathUtil;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import com.macro.ui.filedialog.FileDialogException;
import com.macro.ui.filedialog.FileDialogOptions;
import com.macro.ui.filedialog.FileDialogService;
import com.macro.ui.filedialog.FileDialogServiceFactory;
import com.macro.ui.util.DarkButtonUI;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 统一的流程节点编辑器。
 *
 * <p>普通输入动作与图像识别节点共用创建、编辑、校验和主题布局，
 * 图像识别配置不会写入 {@link ActionType} 或 {@link MacroAction}。</p>
 */
public final class ActionEditorDialog extends JDialog {
    private final WindowTitleBarSupport titleBarSupport;

    /** 创建原生窗口后安装统一标题栏，保留非模态编辑器及现有表单。 */
    @Override public void addNotify() {
        super.addNotify();
        if (titleBarSupport != null) titleBarSupport.activate();
    }

    /** 销毁窗口前恢复原 AWT 过程，业务关闭清理由现有 dispose 执行。 */
    @Override public void removeNotify() {
        try { if (titleBarSupport != null) titleBarSupport.deactivate(); }
        finally { super.removeNotify(); }
    }

    /** 流程节点编辑器的创建或编辑模式。 */
    public enum Mode {
        CREATE,
        EDIT
    }

    /** 编辑器可选择的节点类型，图像检查不会写入 ActionType。 */
    private enum EditorType {
        MOUSE_MOVE(ActionType.MOUSE_MOVE),
        MOUSE_PRESS(ActionType.MOUSE_PRESS),
        MOUSE_RELEASE(ActionType.MOUSE_RELEASE),
        MOUSE_WHEEL(ActionType.MOUSE_WHEEL),
        KEY_PRESS(ActionType.KEY_PRESS),
        KEY_RELEASE(ActionType.KEY_RELEASE),
        IMAGE_CHECK(null);

        private final ActionType actionType;

        EditorType(ActionType actionType) {
            this.actionType = actionType;
        }

        private static EditorType fromNode(MacroNode node) {
            if (node != null && node.getType() == NodeType.IMAGE_CHECK) {
                return IMAGE_CHECK;
            }
            ActionType type = node == null || node.getAction() == null
                    ? ActionType.MOUSE_MOVE
                    : node.getAction().getType();
            return EditorType.valueOf(type.name());
        }
    }

    private static final ThemePalette PALETTE = ThemeManager.getPalette();
    private static final Font FONT_NORMAL =
            new Font(UiConstants.DEFAULT_FONT_FAMILY, Font.PLAIN, 13);
    private static final Font FONT_BOLD =
            new Font(UiConstants.DEFAULT_FONT_FAMILY, Font.BOLD, 14);
    private static final Font FONT_TITLE =
            new Font(UiConstants.DEFAULT_FONT_FAMILY, Font.BOLD, 19);

    private final Mode mode;
    private final FileDialogService fileDialogs;
    private final File templateBaseDirectory;

    /** 编辑模式下的原节点；创建模式下为空。 */
    private final MacroNode sourceNode;

    /** 将编辑结果交回时间线的回调。 */
    private final Consumer<MacroNode> onSave;

    /** 编辑模式下的删除回调，参数表示是否保留动作延迟。 */
    private final Consumer<Boolean> onDelete;

    /** 可切换普通动作与图像识别节点的类型选择框。 */
    private final JComboBox<EditorType> actionTypeCombo =
            new JComboBox<>(EditorType.values());

    /** 普通动作延迟，或图像识别成功/失败选择重试时共用的等待时间。 */
    private final JTextField delayField = new JTextField(12);

    /** 承载当前节点类型对应属性面板的容器。 */
    private final JPanel propertyHost = new JPanel(new BorderLayout());

    private JLabel subtitleLabel;
    private JCheckBox preserveDelayCheckBox;
    private PropertyPanel currentPropertyPanel;
    private List<MacroNode> availableBranchTargets = List.of();
    private final JLabel nodeNumberLabel = new JLabel("新节点");
    private ImageRecognitionFrame imageRecognitionFrame;
    private boolean closed;

    /** 从当前时间线注入节点选项；不向用户暴露需要手输的节点 ID。 */
    public void setAvailableBranchTargets(List<MacroNode> nodes) {
        availableBranchTargets = List.copyOf(nodes);
        String number = sourceNode == null ? null : TimelineNodeNumbers.labels(nodes).get(sourceNode.getId());
        nodeNumberLabel.setText(sourceNode == null ? "新节点" : number == null ? "节点 —" : "节点 " + number);
        if (currentPropertyPanel instanceof ImageCheckPanel image) image.refreshTargets();
    }

    /** 非模态期间删除/换宏后，旧节点编辑器不能再写回。 */
    boolean canKeepEditing(List<MacroNode> nodes, boolean sameFlow) {
        return sourceNode == null ? sameFlow : nodes.stream().anyMatch(node -> node == sourceNode);
    }

    /** 标记编辑器已关闭，先释放自身，再取消并释放所属识别窗口，使旧保存和删除入口失效。 */
    @Override
    public void dispose() {
        closed = true;
        ImageRecognitionFrame recognition = imageRecognitionFrame;
        imageRecognitionFrame = null;
        super.dispose();
        if (recognition != null) recognition.dispose();
    }

    /**
     * 创建新增流程节点编辑器。
     *
     * @param owner 父窗口
     * @param onSave 新节点保存回调
     * @return 新增流程节点编辑器
     */
    public static ActionEditorDialog create(
            Window owner,
            Consumer<MacroNode> onSave
    ) {
        return new ActionEditorDialog(owner, Mode.CREATE, null, onSave, null);
    }

    /** 创建使用当前 MR 目录解析模板路径的非模态插入窗口，保存结果仍交由时间线回调处理。 */
    public static ActionEditorDialog create(Window owner, File macroDirectory, Consumer<MacroNode> onSave) {
        return new ActionEditorDialog(owner, Mode.CREATE, null, onSave, null,
                FileDialogServiceFactory.create(), macroDirectory);
    }

    /** 允许界面回归测试注入文件选择结果，生产入口始终由平台工厂创建服务。 */
    static ActionEditorDialog create(Window owner, Consumer<MacroNode> onSave, FileDialogService fileDialogs) {
        return new ActionEditorDialog(owner, Mode.CREATE, null, onSave, null, fileDialogs);
    }

    /**
     * 创建已有流程节点编辑器。
     *
     * @param owner 父窗口
     * @param node 原流程节点
     * @param onSave 替换节点回调
     * @param onDelete 删除节点回调
     * @return 已有流程节点编辑器
     */
    public static ActionEditorDialog edit(
            Window owner,
            MacroNode node,
            Consumer<MacroNode> onSave,
            Consumer<Boolean> onDelete
    ) {
        return new ActionEditorDialog(owner, Mode.EDIT, node, onSave, onDelete);
    }

    /** 创建使用当前 MR 目录的节点编辑窗口，保留原节点身份及保存、删除回调。 */
    public static ActionEditorDialog edit(Window owner, MacroNode node, File macroDirectory,
            Consumer<MacroNode> onSave, Consumer<Boolean> onDelete) {
        return new ActionEditorDialog(owner, Mode.EDIT, node, onSave, onDelete,
                FileDialogServiceFactory.create(), macroDirectory);
    }

    /** 创建非模态节点编辑器，绑定原节点、文件面板和结果回调，并按初始类型装配属性表单。 */
    private ActionEditorDialog(
            Window owner,
            Mode mode,
            MacroNode sourceNode,
            Consumer<MacroNode> onSave,
            Consumer<Boolean> onDelete
    ) {
        this(owner, mode, sourceNode, onSave, onDelete, FileDialogServiceFactory.create());
    }

    /** 创建非模态节点编辑器，绑定原节点、文件面板和结果回调，并按初始类型装配属性表单。 */
    private ActionEditorDialog(Window owner, Mode mode, MacroNode sourceNode,
            Consumer<MacroNode> onSave, Consumer<Boolean> onDelete, FileDialogService fileDialogs) {
        this(owner, mode, sourceNode, onSave, onDelete, fileDialogs, null);
    }

    /** 创建非模态节点编辑器，绑定原节点、文件面板和结果回调，并按初始类型装配属性表单。 */
    private ActionEditorDialog(Window owner, Mode mode, MacroNode sourceNode,
            Consumer<MacroNode> onSave, Consumer<Boolean> onDelete, FileDialogService fileDialogs,
            File templateBaseDirectory) {
        super(owner, mode == Mode.CREATE ? "插入动作" : "编辑动作",
                ModalityType.MODELESS);

        if (onSave == null) {
            throw new IllegalArgumentException("动作保存回调不能为空");
        }
        if (mode == Mode.EDIT && sourceNode == null) {
            throw new IllegalArgumentException("编辑模式下原动作不能为空");
        }

        this.mode = mode;
        this.fileDialogs = java.util.Objects.requireNonNull(fileDialogs);
        this.templateBaseDirectory = templateBaseDirectory;
        this.sourceNode = sourceNode;
        nodeNumberLabel.setText(sourceNode == null ? "新节点" : "节点 —");
        this.onSave = onSave;
        this.onDelete = onDelete;

        configureActionTypeComboBox();
        delayField.setFont(FONT_NORMAL);
        ComponentStyleUtil.styleTextField(delayField);
        NumericInputUtil.installNonNegativeLong(delayField, "延迟（ms）");
        delayField.setText(String.valueOf(getInitialDelay(sourceNode)));
        propertyHost.setOpaque(false);

        EditorType initialType = EditorType.fromNode(sourceNode);
        actionTypeCombo.setSelectedItem(initialType);

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setContentPane(createContent(initialType));
        titleBarSupport = new WindowTitleBarSupport(this);
        actionTypeCombo.addActionListener(event -> changeActionType(
                (EditorType) actionTypeCombo.getSelectedItem()
        ));

        pack();
        int initialHeight = initialType == EditorType.IMAGE_CHECK ? 460 : 380;
        setMinimumSize(new Dimension(720, initialHeight));
        setSize(720, initialHeight);
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    /** 创建编辑器主体。 */
    private JPanel createContent(EditorType initialType) {
        JPanel root = new JPanel(new BorderLayout(0, 16));
        root.setBackground(PALETTE.background());
        root.setBorder(new EmptyBorder(18, 20, 16, 20));

        JLabel title = new JLabel(mode == Mode.CREATE ? "插入动作" : "编辑动作");
        title.setFont(FONT_TITLE);
        title.setForeground(PALETTE.textPrimary());

        subtitleLabel = new JLabel(getEditorTypeName(initialType));
        subtitleLabel.setFont(FONT_NORMAL);
        subtitleLabel.setForeground(PALETTE.accent());

        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        heading.add(title);
        heading.add(Box.createVerticalStrut(6));
        heading.add(subtitleLabel);

        nodeNumberLabel.setFont(FONT_BOLD);
        nodeNumberLabel.setForeground(PALETTE.accent());
        nodeNumberLabel.setVerticalAlignment(SwingConstants.TOP);
        nodeNumberLabel.setBorder(new EmptyBorder(4, 10, 4, 0));
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(heading, BorderLayout.CENTER);
        header.add(nodeNumberLabel, BorderLayout.EAST);

        RoundedPanel formCard = new RoundedPanel(new BorderLayout());
        formCard.setBorder(new EmptyBorder(14, 14, 14, 14));
        formCard.add(propertyHost, BorderLayout.CENTER);

        showPropertyPanel(initialType, sourceNode);

        JPanel footer = createFooter();
        root.add(header, BorderLayout.NORTH);
        root.add(formCard, BorderLayout.CENTER);
        root.add(footer, BorderLayout.SOUTH);
        return root;
    }

    /** 创建底部操作区域。 */
    private JPanel createFooter() {
        JPanel footer = new JPanel(new BorderLayout(16, 0));
        footer.setOpaque(false);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        buttons.setOpaque(false);

        if (mode == Mode.EDIT) {
            preserveDelayCheckBox = new JCheckBox("删除时保留延迟至下一项");
            preserveDelayCheckBox.setFont(FONT_NORMAL);
            ComponentStyleUtil.styleCheckBox(preserveDelayCheckBox);
            footer.add(preserveDelayCheckBox, BorderLayout.WEST);

            JButton deleteButton = createButton("删除动作", false);
            deleteButton.setForeground(PALETTE.danger());
            deleteButton.addActionListener(event -> deleteAction());
            buttons.add(deleteButton);
        }

        JButton cancelButton = createButton("取消", false);
        cancelButton.addActionListener(event -> dispose());
        buttons.add(cancelButton);

        JButton saveButton = createButton(mode == Mode.CREATE ? "插入" : "保存", true);
        saveButton.addActionListener(event -> saveAction());
        buttons.add(saveButton);

        footer.add(buttons, BorderLayout.EAST);
        return footer;
    }

    /** 切换节点类型，并丢弃旧类型字段后创建对应属性面板。 */
    private void changeActionType(EditorType type) {
        if (type == null) {
            return;
        }
        if (imageRecognitionFrame != null) {
            imageRecognitionFrame.dispose();
            imageRecognitionFrame = null;
        }
        subtitleLabel.setText(getEditorTypeName(type));
        delayField.setText(type == EditorType.IMAGE_CHECK ? "1000" : "0");
        showPropertyPanel(type, null);
        int height = type == EditorType.IMAGE_CHECK ? 460 : 380;
        setMinimumSize(new Dimension(720, height));
        setSize(720, height);
    }

    /** 显示指定节点类型的属性面板，并按需载入原节点数据。 */
    private void showPropertyPanel(EditorType type, MacroNode node) {
        currentPropertyPanel = createPropertyPanel(type);
        if (currentPropertyPanel instanceof ImageCheckPanel imagePanel) {
            imagePanel.loadConfig(node == null ? null : node.getImageCheckConfig());
        } else {
            currentPropertyPanel.load(node == null ? null : node.getAction());
        }
        propertyHost.removeAll();
        propertyHost.add(currentPropertyPanel, BorderLayout.CENTER);
        propertyHost.revalidate();
        propertyHost.repaint();
    }

    /** 为节点类型创建对应的属性面板。 */
    private PropertyPanel createPropertyPanel(EditorType type) {
        return switch (type) {
            case MOUSE_MOVE -> new MouseMovePanel();
            case MOUSE_PRESS -> new MousePressPanel();
            case MOUSE_RELEASE -> new MouseReleasePanel();
            case MOUSE_WHEEL -> new MouseWheelPanel();
            case KEY_PRESS -> new KeyPressPanel();
            case KEY_RELEASE -> new KeyReleasePanel();
            case IMAGE_CHECK -> new ImageCheckPanel();
        };
    }

    /** 校验表单并创建新的流程节点交回时间线。 */
    private void saveAction() {
        if (closed) return;
        try {
            long delay = parseDelay();
            MacroNode result;
            if (currentPropertyPanel instanceof ImageCheckPanel imagePanel) {
                result = imagePanel.createImageNode(delay);
            } else {
                MacroAction action = currentPropertyPanel.createAction(delay);
                result = new MacroNode(
                        null,
                        NodeType.ACTION,
                        action,
                        new ArrayList<>()
                );
            }
            onSave.accept(result);
            dispose();
        } catch (NumberFormatException exception) {
            AppMessageDialog.showError(this, "输入内容不是有效整数");
        } catch (IllegalArgumentException exception) {
            String message = exception.getMessage();
            AppMessageDialog.showError(
                    this,
                    message == null || message.isBlank() ? "参数校验失败" : message
            );
        }
    }

    /** 读取并校验普通动作延迟或图像识别重试延迟。 */
    private long parseDelay() {
        return NumericInputUtil.readLong(delayField);
    }

    /** 普通动作取原动作延迟，图像节点取重试等待时间，新节点使用零。 */
    private long getInitialDelay(MacroNode node) {
        if (node == null) {
            return 0L;
        }
        if (node.getType() == NodeType.IMAGE_CHECK) {
            return MacroFlowFactory.findRetryDelay(node);
        }
        return node.getAction() == null ? 0L : node.getAction().getDelay();
    }

    /** 确认删除当前流程节点。 */
    private void deleteAction() {
        if (closed) return;
        boolean confirmed = AppMessageDialog.showConfirm(
                this,
                "删除动作",
                "确定删除这个动作吗？"
        );
        if (!confirmed || closed) {
            return;
        }
        if (onDelete == null) {
            AppMessageDialog.showInfo(this, "当前编辑器尚未接入删除功能。");
            return;
        }
        onDelete.accept(preserveDelayCheckBox.isSelected());
        dispose();
    }

    /** 创建表单字段行。 */
    private JPanel createFormRow(String name, JComponent component) {
        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));

        component.setPreferredSize(new Dimension(200, 34));
        component.setMinimumSize(new Dimension(180, 34));
        component.setMaximumSize(new Dimension(200, 34));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.NONE;

        gbc.gridx = 0;
        gbc.insets = new Insets(0, 0, 0, 14);
        row.add(createFieldLabel(name, 72), gbc);

        gbc.gridx = 1;
        gbc.insets = new Insets(0, 0, 0, 0);
        row.add(component, gbc);

        gbc.gridx = 2;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        row.add(Box.createHorizontalGlue(), gbc);
        return row;
    }

    /** 创建主题化字段标签。 */
    private JLabel createFieldLabel(String text, int width) {
        JLabel label = new JLabel(text);
        label.setFont(FONT_NORMAL);
        label.setForeground(PALETTE.textSecondary());
        label.setPreferredSize(new Dimension(width, 34));
        label.setVerticalAlignment(SwingConstants.CENTER);
        return label;
    }

    /** 设置动作类型下拉框。 */
    private void configureActionTypeComboBox() {
        actionTypeCombo.setFont(FONT_NORMAL);
        ComponentStyleUtil.styleComboBox(actionTypeCombo);
        actionTypeCombo.setRenderer((list, value, index, selected, focus) ->
                createComboLabel(
                        value == null ? "" : getEditorTypeName(value),
                        selected
                ));
    }

    /** 创建鼠标按键下拉框。 */
    private JComboBox<MouseButton> createMouseButtonComboBox() {
        JComboBox<MouseButton> comboBox = new JComboBox<>(java.util.Arrays.stream(MouseButton.values())
                .filter(MouseButton::isSupported).toArray(MouseButton[]::new));
        comboBox.setFont(FONT_NORMAL);
        ComponentStyleUtil.styleComboBox(comboBox);
        comboBox.setRenderer((list, value, index, selected, focus) ->
                createComboLabel(getMouseButtonName(value), selected));
        return comboBox;
    }

    /** 创建深色下拉框选项。 */
    private JLabel createComboLabel(String text, boolean selected) {
        JLabel label = new JLabel(text);
        label.setOpaque(true);
        label.setFont(FONT_NORMAL);
        label.setForeground(PALETTE.textPrimary());
        label.setBackground(selected
                ? PALETTE.controlBackground()
                : PALETTE.targetCard());
        label.setBorder(selected
                ? BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(PALETTE.accent(), 1, true),
                        new EmptyBorder(4, 7, 4, 7)
                )
                : new EmptyBorder(5, 8, 5, 8));
        return label;
    }

    /** 创建文本输入框并应用统一主题。 */
    private JTextField createTextField(String value) {
        JTextField field = new JTextField(value, 12);
        field.setFont(FONT_NORMAL);
        ComponentStyleUtil.styleTextField(field);
        return field;
    }

    /** 数值字段单独安装整数规则，不影响按键名称等普通文本。 */
    private JTextField createIntegerField(String value, String name) {
        JTextField field = createTextField(value);
        NumericInputUtil.installInteger(field, name);
        return field;
    }

    /** 创建可复制的只读文本框。 */
    private JTextField createReadOnlyTextField(String value) {
        JTextField field = createTextField(value);
        field.setEditable(false);
        field.setBackground(PALETTE.background());
        field.setForeground(PALETTE.textMuted());
        return field;
    }

    /** 创建圆角操作按钮。 */
    private JButton createButton(String text, boolean primary) {
        JButton button = new JButton(text);
        button.setFont(FONT_BOLD);
        button.setForeground(primary ? PALETTE.textOnAccent() : PALETTE.textPrimary());
        Color background = primary ? PALETTE.accent() : PALETTE.controlBackground();
        Color hover = primary ? PALETTE.accentHover() : PALETTE.targetCard();
        button.setBackground(background);
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setBorder(new EmptyBorder(9, 18, 9, 18));
        button.setUI(new RoundedButtonUI(background, hover));
        return button;
    }

    /** 获取动作类型名称。 */
    private static String getActionName(ActionType type) {
        return switch (type) {
            case MOUSE_MOVE -> "鼠标移动";
            case MOUSE_PRESS -> "鼠标按下";
            case MOUSE_RELEASE -> "鼠标释放";
            case MOUSE_WHEEL -> "鼠标滚轮";
            case KEY_PRESS -> "键盘按下";
            case KEY_RELEASE -> "键盘释放";
        };
    }

    /** 将编辑器类型转换为界面展示名称，不改变宏动作或流程节点类型。 */
    private static String getEditorTypeName(EditorType type) {
        if (type == EditorType.IMAGE_CHECK) {
            return "图像识别";
        }
        return getActionName(type.actionType);
    }

    /** 获取鼠标按键名称。 */
    private static String getMouseButtonName(MouseButton button) {
        if (button == null) {
            return "无";
        }
        return switch (button) {
            case LEFT -> "左键";
            case MIDDLE -> "中键";
            case RIGHT -> "右键";
            case X1, X2 -> "不支持的按键";
        };
    }

    /** 将按键名称转换为 AWT 按键代码。 */
    private static int resolveKeyCode(String keyName) {
        String trimmedName = keyName == null ? "" : keyName.trim();
        if (trimmedName.isEmpty()) {
            throw new IllegalArgumentException("按键名称不能为空");
        }

        if (trimmedName.codePointCount(0, trimmedName.length()) == 1) {
            int character = Character.toUpperCase(trimmedName.codePointAt(0));
            int keyCode = KeyEvent.getExtendedKeyCodeForChar(character);
            if (keyCode != KeyEvent.VK_UNDEFINED) {
                return keyCode;
            }
        }

        Integer displayNameCode = findKeyCodeByDisplayName(trimmedName);
        if (displayNameCode != null) {
            return displayNameCode;
        }

        String constantName = normalizeKeyConstantName(trimmedName);
        try {
            Field field = KeyEvent.class.getField("VK_" + constantName);
            if (field.getType() == int.class && Modifier.isStatic(field.getModifiers())) {
                int keyCode = field.getInt(null);
                if (keyCode != KeyEvent.VK_UNDEFINED) {
                    return keyCode;
                }
            }
        } catch (NoSuchFieldException | IllegalAccessException ignored) {
            // 统一在方法结尾提示无法识别。
        }

        throw new IllegalArgumentException("无法识别按键名称：" + trimmedName);
    }

    /** 按照当前系统的 AWT 按键显示名称查找键码。 */
    private static Integer findKeyCodeByDisplayName(String keyName) {
        for (Field field : KeyEvent.class.getFields()) {
            if (!field.getName().startsWith("VK_")
                    || field.getType() != int.class
                    || !Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            try {
                int keyCode = field.getInt(null);
                if (keyCode != KeyEvent.VK_UNDEFINED
                        && KeyEvent.getKeyText(keyCode).equalsIgnoreCase(keyName)) {
                    return keyCode;
                }
            } catch (IllegalAccessException ignored) {
                // 公开常量正常情况下均可读取，异常项直接跳过。
            }
        }
        return null;
    }

    /** 将常见按键名称转换为 {@link KeyEvent} 常量后缀。 */
    private static String normalizeKeyConstantName(String keyName) {
        String normalized = keyName.trim()
                .toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        while (normalized.contains("__")) {
            normalized = normalized.replace("__", "_");
        }

        normalized = switch (normalized) {
            case "制表符" -> "TAB";
            case "回车", "返回", "RETURN" -> "ENTER";
            case "空格", "SPACEBAR" -> "SPACE";
            case "退格", "BACKSPACE" -> "BACK_SPACE";
            case "删除" -> "DELETE";
            case "上" -> "UP";
            case "下" -> "DOWN";
            case "左" -> "LEFT";
            case "右" -> "RIGHT";
            case "ESC" -> "ESCAPE";
            case "CTRL" -> "CONTROL";
            case "WIN", "WINDOW", "WINDOWS_KEY", "META" -> "WINDOWS";
            case "PRINT_SCREEN" -> "PRINTSCREEN";
            case "PAGEUP", "PGUP" -> "PAGE_UP";
            case "PAGEDOWN", "PGDN" -> "PAGE_DOWN";
            case "CAPSLOCK" -> "CAPS_LOCK";
            case "NUMLOCK" -> "NUM_LOCK";
            case "SCROLLLOCK" -> "SCROLL_LOCK";
            default -> normalized;
        };

        if (normalized.startsWith("LEFT_") || normalized.startsWith("RIGHT_")) {
            normalized = normalized.substring(normalized.indexOf('_') + 1);
        }
        if (normalized.equals("CTRL")) {
            normalized = "CONTROL";
        } else if (normalized.equals("META")) {
            normalized = "WINDOWS";
        }
        return normalized
                .replace("NUM_PAD_", "NUMPAD")
                .replace("NUMPAD_", "NUMPAD");
    }

    /** 获取与已有键码一致的按键名称。 */
    private static String getConsistentKeyName(MacroAction action) {
        String keyName = action.getKey() == null ? "" : action.getKey().trim();
        try {
            if (resolveKeyCode(keyName) == action.getKeyCode()) {
                return keyName;
            }
        } catch (IllegalArgumentException ignored) {
            // 无法识别旧名称时，使用现有键码生成标准名称。
        }
        return action.getKeyCode() > KeyEvent.VK_UNDEFINED
                ? KeyEvent.getKeyText(action.getKeyCode())
                : keyName;
    }

    /** 根据节点类型动态创建的属性面板基类。 */
    private abstract class PropertyPanel extends JPanel {

        private int propertyCount;

        /** 建立左右两栏公共表单，复用动作类型和延迟字段，并预留属性行。 */
        private PropertyPanel() {
            this("动作延迟");
        }

        /** 建立左右两栏公共表单，复用动作类型和延迟字段，并预留属性行。 */
        private PropertyPanel(String delayLabel) {
            setOpaque(false);
            setLayout(new GridBagLayout());

            addEditorRow(createFormRow("动作类型", actionTypeCombo), 0, 0);
            addEditorRow(createFormRow(delayLabel, delayField), 2, 0);

            JPanel separator = new JPanel();
            separator.setBackground(PALETTE.border());
            separator.setPreferredSize(new Dimension(1, 1));

            GridBagConstraints separatorConstraints = new GridBagConstraints();
            separatorConstraints.gridx = 1;
            separatorConstraints.gridy = 0;
            separatorConstraints.gridheight = 4;
            separatorConstraints.weighty = 1;
            separatorConstraints.fill = GridBagConstraints.VERTICAL;
            separatorConstraints.insets = new Insets(0, 14, 0, 14);
            add(separator, separatorConstraints);

            GridBagConstraints fillerConstraints = new GridBagConstraints();
            fillerConstraints.gridx = 0;
            fillerConstraints.gridy = 4;
            fillerConstraints.gridwidth = 3;
            fillerConstraints.weighty = 1;
            fillerConstraints.fill = GridBagConstraints.VERTICAL;
            add(Box.createVerticalGlue(), fillerConstraints);
        }

        /** 把下一个属性按既定顺序加入左右两栏，沿用公共表单间距。 */
        protected void addProperty(String name, JComponent component) {
            int column = propertyCount % 2 == 0 ? 0 : 2;
            int row = propertyCount / 2 + 1;
            addEditorRow(createFormRow(name, component), column, row);
            propertyCount++;
        }

        /** 将字段行放入左右两栏表单。 */
        private void addEditorRow(JPanel rowPanel, int column, int row) {
            GridBagConstraints constraints = new GridBagConstraints();
            constraints.gridx = column;
            constraints.gridy = row;
            constraints.weightx = 1;
            constraints.fill = GridBagConstraints.HORIZONTAL;
            constraints.anchor = GridBagConstraints.NORTHWEST;
            constraints.insets = new Insets(row == 0 ? 0 : 10, 0, 0, 0);
            add(rowPanel, constraints);
        }

        /** 将已有普通动作的参数装入当前属性表单，空动作使用该面板的默认值。 */
        protected abstract void load(MacroAction action);

        /** 校验当前表单并创建带指定延迟的普通输入动作，由具体面板决定动作类型。 */
        protected abstract MacroAction createAction(long delay);
    }

    /** IMAGE_CHECK 独立配置面板。 */
    private final class ImageCheckPanel extends PropertyPanel {

        /** 显示并选择模板 PNG 的入口按钮。 */
        private final JButton templateButton = createButton("选择模板 PNG", false);
        private final JButton recognizeButton = createButton("识别", false);

        /** 以百分数编辑匹配阈值，保存时换算为 0 到 1。 */
        private final JSpinner thresholdSpinner = new JSpinner(
                new SpinnerNumberModel(90, 1, 100, 1)
        );

        /** 当前选中的模板文件。 */
        private File templateFile;

        /** 成功与失败独立配置，均复用现有深色下拉框样式。 */
        private final JComboBox<ImageCheckBehavior> successBehavior = createBehaviorCombo();
        private final JComboBox<ImageCheckBehavior> failureBehavior = createBehaviorCombo();
        private final JComboBox<BranchTarget> successTarget = createTargetCombo("successTarget");
        private final JComboBox<BranchTarget> failureTarget = createTargetCombo("failureTarget");

        /** 创建带稳定控件名称和公共深色样式的目标节点下拉框。 */
        private JComboBox<BranchTarget> createTargetCombo(String name) {
            JComboBox<BranchTarget> combo = new JComboBox<>();
            combo.setName(name);
            combo.setFont(FONT_NORMAL);
            ComponentStyleUtil.styleComboBox(combo);
            combo.setPreferredSize(new Dimension(220, 30));
            return combo;
        }

        /** 刷新成功和失败目标列表，并按各自分支行为独立更新启用状态。 */
        private void refreshTargets() {
            fillTargets(successTarget, BranchCondition.FOUND);
            fillTargets(failureTarget, BranchCondition.NOT_FOUND);
            updateTargetEnabled();
        }

        /** 按当前时间线编号生成目标选项，保留已选 ID；目标失效时显示需重新选择的占位项。 */
        private void fillTargets(JComboBox<BranchTarget> combo, BranchCondition condition) {
            BranchTarget old = (BranchTarget) combo.getSelectedItem();
            MacroBranch branch = sourceNode == null ? null : MacroFlowFactory.branchFor(sourceNode, condition);
            String selected = old != null ? old.id() : branch == null ? null : branch.getTargetId();
            combo.removeAllItems();
            BranchTarget selection = null;
            var numbers = TimelineNodeNumbers.labels(availableBranchTargets);
            for (MacroNode target : availableBranchTargets) {
                if (target.getType() == NodeType.END || target.getId() == null
                        || sourceNode != null && target.getId().equals(sourceNode.getId())) continue;
                BranchTarget option = new BranchTarget(target.getId(), numbers.get(target.getId()) + " · "
                        + ActionTimelinePanel.getNodeName(target), true);
                combo.addItem(option);
                if (target.getId().equals(selected)) selection = option;
            }
            if (selected != null && selection == null
                    && (condition == BranchCondition.FOUND ? successBehavior : failureBehavior).getSelectedItem() == ImageCheckBehavior.JUMP) {
                selection = new BranchTarget(selected, "原目标已不存在，请重新选择", false);
                combo.addItem(selection);
            }
            if (selection != null) combo.setSelectedItem(selection);
        }

        /** 仅当对应分支选择指定跳转时启用其目标下拉框，成功和失败互不影响。 */
        private void updateTargetEnabled() {
            successTarget.setEnabled(successBehavior.getSelectedItem() == ImageCheckBehavior.JUMP);
            failureTarget.setEnabled(failureBehavior.getSelectedItem() == ImageCheckBehavior.JUMP);
        }

        /** 指定跳转必须返回有效节点 ID，其他行为返回空目标，保留默认后继、重试与终止的独立语义。 */
        private String selectedTarget(JComboBox<BranchTarget> combo, ImageCheckBehavior behavior) {
            if (behavior != ImageCheckBehavior.JUMP) return null;
            BranchTarget target = (BranchTarget) combo.getSelectedItem();
            if (target == null || !target.available()) throw new IllegalArgumentException("请选择有效的跳转目标节点");
            return target.id();
        }

        /** 创建使用公共主题和行为名称渲染的分支行为选择框。 */
        private JComboBox<ImageCheckBehavior> createBehaviorCombo() {
            JComboBox<ImageCheckBehavior> combo = new JComboBox<>(ImageCheckBehavior.values());
            combo.setFont(FONT_NORMAL);
            ComponentStyleUtil.styleComboBox(combo);
            combo.setRenderer((list, value, index, selected, focus) ->
                    createComboLabel(value == null ? "" : value.toString(), selected));
            return combo;
        }

        /** 组装模板、识别预览、阈值和成功失败分支控件，识别配置不进入 MacroAction。 */
        private ImageCheckPanel() {
            super("重试延迟");
            ComponentStyleUtil.styleSpinner(thresholdSpinner);
            NumericInputUtil.installSpinner(thresholdSpinner, "图像匹配阈值（%）");
            templateButton.addActionListener(event -> chooseTemplateImage());
            recognizeButton.setEnabled(false);
            recognizeButton.setToolTipText("使用当前模板测试屏幕识别");
            recognizeButton.addActionListener(event -> openRecognitionTest());
            JPanel templateControls = new JPanel(new BorderLayout(6, 0));
            templateControls.setOpaque(false);
            templateControls.add(templateButton, BorderLayout.CENTER);
            templateControls.add(recognizeButton, BorderLayout.EAST);
            addProperty("模板 PNG", templateControls);
            addProperty("匹配阈值", thresholdSpinner);
            addProperty("成功行为", successBehavior);
            addProperty("失败行为", failureBehavior);
            addProperty("成功目标", successTarget);
            addProperty("失败目标", failureTarget);
            successBehavior.addActionListener(event -> updateTargetEnabled());
            failureBehavior.addActionListener(event -> updateTargetEnabled());
            refreshTargets();
        }

        /** 载入图像分支行为、模板路径和阈值；缺少配置时恢复新图像节点的默认选择。 */
        private void loadConfig(ImageCheckConfig config) {
            successBehavior.setSelectedItem(config == null
                    ? ImageCheckBehavior.EXECUTE : config.getSuccessBehavior());
            failureBehavior.setSelectedItem(config == null
                    ? ImageCheckBehavior.RETRY : config.getFailureBehavior());
            refreshTargets();
            if (config == null || config.getImagePath() == null || config.getImagePath().isBlank()) {
                templateFile = null;
                templateButton.setText("选择模板 PNG");
                templateButton.setToolTipText(null);
                thresholdSpinner.setValue(90);
                recognizeButton.setEnabled(false);
                return;
            }
            templateFile = ImageTemplatePathUtil.resolve(config.getImagePath(), templateBaseDirectory).toFile();
            templateButton.setText(templateFile.getName());
            templateButton.setToolTipText(templateFile.getAbsolutePath());
            recognizeButton.setEnabled(true);
            int thresholdPercent = (int) Math.round(
                    config.getThreshold() * 100.0
            );
            thresholdSpinner.setValue(Math.max(
                    1,
                    Math.min(100, thresholdPercent)
            ));
        }

        /** 通过现有文件面板选择并验证 PNG，确认后仅更新当前编辑器的未保存模板。 */
        private void chooseTemplateImage() {
            File selected;
            try {
                selected = fileDialogs.showDialog(ActionEditorDialog.this,
                        FileDialogOptions.builder(FileDialogOptions.Mode.OPEN_FILE)
                                .title("选择图像识别模板").fileType("PNG 图像 (*.png)", "png")
                                .allowAllFiles(true).initialFile(templateFile).build()).orElse(null);
            } catch (FileDialogException exception) {
                AppMessageDialog.showError(ActionEditorDialog.this, exception.getMessage(), exception);
                return;
            }
            if (selected == null) return;
            if (!selected.getName().toLowerCase(Locale.ROOT).endsWith(".png")) {
                AppMessageDialog.showError(
                        ActionEditorDialog.this,
                        "请选择 PNG 格式的模板图片"
                );
                return;
            }
            try {
                if (ImageIO.read(selected) == null) {
                    throw new IllegalArgumentException("无法识别该 PNG 图片");
                }
            } catch (Exception exception) {
                AppMessageDialog.showError(
                        ActionEditorDialog.this,
                        "无法读取模板 PNG",
                        exception
                );
                return;
            }

            templateFile = selected.getAbsoluteFile();
            templateButton.setText(templateFile.getName());
            templateButton.setToolTipText(templateFile.getAbsolutePath());
            recognizeButton.setEnabled(true);
        }

        /** 使用当前尚未保存的模板与阈值，不修改节点，也不自动开始截图。 */
        private void openRecognitionTest() {
            if (closed || templateFile == null) return;
            try {
                ImageRecognitionFrame preview = prepareRecognitionTest();
                preview.setVisible(true);
                preview.toFront();
            } catch (java.io.IOException | IllegalArgumentException exception) {
                AppMessageDialog.showError(ActionEditorDialog.this, "无法加载当前识别模板", exception);
            }
        }

        /** 复用或创建识别预览窗口，载入当前未保存的模板及阈值，不自动识别也不写回宏。 */
        private ImageRecognitionFrame prepareRecognitionTest() throws java.io.IOException {
            int threshold = NumericInputUtil.commitSpinnerInt(thresholdSpinner);
            if (imageRecognitionFrame == null || !imageRecognitionFrame.isDisplayable()) {
                imageRecognitionFrame = new ImageRecognitionFrame(ActionEditorDialog.this,
                        () -> templateFile == null ? templateBaseDirectory : templateFile.getParentFile());
            }
            imageRecognitionFrame.setTemplate(templateFile, threshold / 100.0);
            return imageRecognitionFrame;
        }

        /** 校验模板、阈值和指定跳转目标，组装独立图像配置及成功失败分支，节点 ID 留给时间线分配。 */
        private MacroNode createImageNode(long retryDelay) {
            if (templateFile == null) {
                throw new IllegalArgumentException("请选择模板 PNG");
            }
            int thresholdPercent = NumericInputUtil.commitSpinnerInt(thresholdSpinner);
            ImageCheckConfig config = new ImageCheckConfig(
                    templateFile.getAbsolutePath(),
                    thresholdPercent / 100.0
            );
            config.setSuccessBehavior((ImageCheckBehavior) successBehavior.getSelectedItem());
            config.setFailureBehavior((ImageCheckBehavior) failureBehavior.getSelectedItem());
            config.setRetryDelay(retryDelay);
            return new MacroNode(
                    null,
                    NodeType.IMAGE_CHECK,
                    null,
                    config,
                    new ArrayList<>(List.of(
                            new MacroBranch(
                                    BranchCondition.FOUND,
                                    selectedTarget(successTarget, config.getSuccessBehavior()),
                                    0L
                            ),
                            new MacroBranch(
                                    BranchCondition.NOT_FOUND,
                                    selectedTarget(failureTarget, config.getFailureBehavior()),
                                    config.getFailureBehavior() == ImageCheckBehavior.RETRY ? retryDelay : 0L
                            )
                    ))
            );
        }

        /** 图像节点不从普通输入动作读取配置，图像参数由专用加载入口处理。 */
        @Override
        protected void load(MacroAction action) {
            // 图像识别不使用 MacroAction。
        }

        /** 拒绝把图像检查创建为普通键鼠动作，避免混淆两种节点模型。 */
        @Override
        protected MacroAction createAction(long delay) {
            throw new UnsupportedOperationException("图像识别不创建 MacroAction");
        }
    }

    private record BranchTarget(String id, String label, boolean available) {
        /** 显示带编号的目标名称，实际分支引用仍使用单独保存的节点 ID。 */
        @Override public String toString() { return label; }
    }

    /** 含坐标字段的鼠标动作面板。 */
    private abstract class CoordinatePanel extends PropertyPanel {

        protected final JTextField xField = createIntegerField("0", "X 坐标");
        protected final JTextField yField = createIntegerField("0", "Y 坐标");

        /** 加入 X、Y 整数字段，供鼠标移动、按钮和滚轮属性复用。 */
        private CoordinatePanel() {
            addProperty("X 坐标", xField);
            addProperty("Y 坐标", yField);
        }

        /** 载入动作坐标，没有原动作时把两个坐标初始化为零。 */
        protected void loadCoordinates(MacroAction action) {
            if (action == null) {
                xField.setText("0");
                yField.setText("0");
                return;
            }
            xField.setText(String.valueOf(action.getX()));
            yField.setText(String.valueOf(action.getY()));
        }

        /** 按已安装规则读取 X 坐标，拒绝未完成或溢出的整数输入。 */
        protected int getXValue() {
            return NumericInputUtil.readInt(xField);
        }

        /** 按已安装规则读取 Y 坐标，拒绝未完成或溢出的整数输入。 */
        protected int getYValue() {
            return NumericInputUtil.readInt(yField);
        }
    }

    /** 鼠标移动属性面板。 */
    private final class MouseMovePanel extends CoordinatePanel {

        /** 将已有移动动作的坐标载入当前字段。 */
        @Override
        protected void load(MacroAction action) {
            loadCoordinates(action);
        }

        /** 读取已校验的坐标并创建指定延迟的鼠标移动动作。 */
        @Override
        protected MacroAction createAction(long delay) {
            return MacroAction.mouseMove(getXValue(), getYValue(), delay);
        }
    }

    /** 含鼠标按键的坐标动作面板。 */
    private abstract class MouseButtonPanel extends CoordinatePanel {

        protected final JComboBox<MouseButton> mouseButtonCombo =
                createMouseButtonComboBox();

        /** 在公共坐标字段后加入受支持鼠标按钮选择。 */
        private MouseButtonPanel() {
            addProperty("鼠标按键", mouseButtonCombo);
        }

        /** 载入坐标和按钮；旧数据含未支持侧键时保留未选择状态，要求重新选择。 */
        @Override
        protected void load(MacroAction action) {
            loadCoordinates(action);
            if (action != null && action.getMouseButton() != null && !action.getMouseButton().isSupported()) {
                mouseButtonCombo.setSelectedIndex(-1);
                return;
            }
            mouseButtonCombo.setSelectedItem(
                    action == null || action.getMouseButton() == null
                            ? MouseButton.LEFT
                            : action.getMouseButton()
            );
        }

        /** 返回当前支持的鼠标按钮，未选择或侧键数据明确拒绝。 */
        protected MouseButton getMouseButton() {
            MouseButton button = (MouseButton) mouseButtonCombo.getSelectedItem();
            if (button == null) {
                throw new IllegalArgumentException("请选择鼠标按键");
            }
            if (!button.isSupported()) {
                throw new IllegalArgumentException("不支持鼠标侧键");
            }
            return button;
        }
    }

    /** 鼠标按下属性面板。 */
    private final class MousePressPanel extends MouseButtonPanel {

        /** 读取坐标与受支持按钮，创建鼠标按下动作。 */
        @Override
        protected MacroAction createAction(long delay) {
            return MacroAction.mousePress(
                    getXValue(),
                    getYValue(),
                    getMouseButton(),
                    delay
            );
        }
    }

    /** 鼠标释放属性面板。 */
    private final class MouseReleasePanel extends MouseButtonPanel {

        /** 读取坐标与受支持按钮，创建鼠标释放动作。 */
        @Override
        protected MacroAction createAction(long delay) {
            return MacroAction.mouseRelease(
                    getXValue(),
                    getYValue(),
                    getMouseButton(),
                    delay
            );
        }
    }

    /** 鼠标滚轮属性面板。 */
    private final class MouseWheelPanel extends CoordinatePanel {

        private final JTextField wheelAmountField = createIntegerField("1", "滚轮量");

        /** 在公共坐标字段后加入滚动幅度输入。 */
        private MouseWheelPanel() {
            addProperty("滚轮量", wheelAmountField);
        }

        /** 载入滚轮动作的坐标和滚动值，新动作沿用默认幅度。 */
        @Override
        protected void load(MacroAction action) {
            loadCoordinates(action);
            wheelAmountField.setText(String.valueOf(
                    action == null ? 1 : action.getWheelAmount()
            ));
        }

        /** 校验坐标与滚动幅度后创建滚轮动作。 */
        @Override
        protected MacroAction createAction(long delay) {
            return MacroAction.mouseWheel(
                    getXValue(),
                    getYValue(),
                    NumericInputUtil.readInt(wheelAmountField),
                    delay
            );
        }
    }

    /** 键盘动作属性面板。 */
    private abstract class KeyboardPanel extends PropertyPanel {

        protected final JTextField keyField = createTextField("A");
        protected final JTextField keyCodeField = createReadOnlyTextField(
                String.valueOf(KeyEvent.VK_A)
        );
        protected KeySide keySide = KeySide.NONE;

        /** 建立可编辑按键名称和只读代码预览，并安装名称变化同步。 */
        private KeyboardPanel() {
            installKeyNameSync();
            addProperty("按键名称", keyField);
            addProperty("按键代码", keyCodeField);
        }

        /** 监听按键名称文本变化，及时更新只读按键代码预览。 */
        private void installKeyNameSync() {
            keyField.getDocument().addDocumentListener(new DocumentListener() {
                /** 按键名称新增字符后重新解析代码预览。 */
                @Override
                public void insertUpdate(DocumentEvent event) {
                    updateKeyCodePreview();
                }

                /** 按键名称删除字符后重新解析代码预览。 */
                @Override
                public void removeUpdate(DocumentEvent event) {
                    updateKeyCodePreview();
                }

                /** 按键名称文档变化后同步代码预览。 */
                @Override
                public void changedUpdate(DocumentEvent event) {
                    updateKeyCodePreview();
                }
            });
        }

        /** 尝试解析当前按键名称，输入尚无效时只调整预览，不弹出校验提示。 */
        private void updateKeyCodePreview() {
            try {
                keyCodeField.setText(String.valueOf(resolveKeyCode(keyField.getText())));
            } catch (IllegalArgumentException ignored) {
                keyCodeField.setText("");
            }
        }

        /** 载入按键名称、代码和左右键位置；新动作使用 A 键默认值。 */
        @Override
        protected void load(MacroAction action) {
            if (action == null) {
                keySide = KeySide.NONE;
                keyField.setText("A");
                keyCodeField.setText(String.valueOf(KeyEvent.VK_A));
                return;
            }
            keySide = action.getKeySide() == null ? KeySide.NONE : action.getKeySide();
            keyField.setText(getConsistentKeyName(action));
            keyCodeField.setText(String.valueOf(action.getKeyCode()));
        }

        /** 校验并返回去除首尾空白的按键名称，同时同步代码字段。 */
        protected String getKeyName() {
            String keyName = keyField.getText().trim();
            int keyCode = resolveKeyCode(keyName);
            keyCodeField.setText(String.valueOf(keyCode));
            return keyName;
        }

        /** 按公共名称解析规则取得当前按键代码，非法名称继续交由保存入口反馈。 */
        protected int getKeyCode() {
            return resolveKeyCode(keyField.getText());
        }
    }

    /** 键盘按下属性面板。 */
    private final class KeyPressPanel extends KeyboardPanel {

        /** 保留左右键位置，按校验后的名称和代码创建键盘按下动作。 */
        @Override
        protected MacroAction createAction(long delay) {
            String keyName = getKeyName();
            return MacroAction.keyPress(getKeyCode(), keyName, keySide, delay);
        }
    }

    /** 键盘释放属性面板。 */
    private final class KeyReleasePanel extends KeyboardPanel {

        /** 保留左右键位置，按校验后的名称和代码创建键盘释放动作。 */
        @Override
        protected MacroAction createAction(long delay) {
            String keyName = getKeyName();
            return MacroAction.keyRelease(getKeyCode(), keyName, keySide, delay);
        }
    }

    /** 深色圆角卡片。 */
    private static final class RoundedPanel extends JPanel {

        /** 用指定布局创建属性卡片，背景由主题圆角绘制负责。 */
        private RoundedPanel(LayoutManager layout) {
            super(layout);
            setOpaque(false);
        }

        /** 绘制编辑表单的圆角主题背景及边框，再绘制面板内容。 */
        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(PALETTE.card());
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 16, 16);
            g2.setColor(PALETTE.border());
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 16, 16);
            g2.dispose();
            super.paintComponent(graphics);
        }
    }

    /** 深色圆角按钮。 */
    private static final class RoundedButtonUI extends DarkButtonUI {

        private final Color background;
        private final Color hover;

        /** 保存编辑器按钮的常规和悬停背景颜色。 */
        private RoundedButtonUI(Color background, Color hover) {
            this.background = background;
            this.hover = hover;
        }

        /** 根据按钮交互状态绘制圆角背景和边框，再沿用按钮内容绘制。 */
        @Override
        public void paint(Graphics graphics, JComponent component) {
            AbstractButton button = (AbstractButton) component;
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            Color fill = button.getModel().isRollover() ? hover : background;
            if (button.getModel().isPressed()) {
                fill = fill.darker();
            }
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, component.getWidth(), component.getHeight(), 12, 12);
            g2.setColor(PALETTE.border());
            g2.drawRoundRect(0, 0, component.getWidth() - 1,
                    component.getHeight() - 1, 12, 12);
            g2.dispose();
            super.paint(graphics, component);
        }
    }
}
