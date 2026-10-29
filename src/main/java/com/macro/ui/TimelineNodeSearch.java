package com.macro.ui;

import com.macro.flow.MacroNode;
import com.macro.flow.NodeType;
import com.macro.model.ActionType;
import com.macro.ui.theme.ThemeManager;
import com.macro.ui.util.ComponentStyleUtil;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.Map;
import java.util.HashMap;
import java.lang.reflect.Modifier;
import java.util.regex.Pattern;

/** 仅定位时间线中的可见节点，不筛掉卡片、不改变选择或流程，也不读取模板文件。 */
final class TimelineNodeSearch extends JPanel {
    private static final Map<Integer, String> KEY_NAMES = keyNames();
    private final JTextField query = new JTextField();
    private final JLabel count = new JLabel("待输入", SwingConstants.CENTER);
    private final JButton previous = new JButton("上一项");
    private final JButton next = new JButton("下一项");
    private final Runnable onResultsChanged;
    private final Consumer<String> onNavigate;
    private final Runnable onClose;
    private List<MacroNode> nodes = List.of();
    private List<String> matches = List.of();
    private String currentId;

    TimelineNodeSearch(Runnable onResultsChanged, Consumer<String> onNavigate, Runnable onClose) {
        this.onResultsChanged = onResultsChanged;
        this.onNavigate = onNavigate;
        this.onClose = onClose;
        setLayout(new BorderLayout(10, 0)); setOpaque(false);
        setBorder(new EmptyBorder(8, 0, 6, 0));
        JLabel title = new JLabel("查找"); title.setForeground(ThemeManager.getPalette().textSecondary());
        add(title, BorderLayout.WEST);
        query.setName("timelineFindInput");
        query.setColumns(18);
        query.getAccessibleContext().setAccessibleName("查找时间线节点");
        query.putClientProperty("JTextField.placeholderText", "编号 / 动作 / 按键 / 图片名");
        query.setToolTipText("编号精确查找（12 或 #12）；文字忽略大小写，空格分隔多个条件；Enter 下一项，Shift+Enter 上一项");
        ComponentStyleUtil.styleTextField(query);
        add(query, BorderLayout.CENTER);
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0)); controls.setOpaque(false);
        count.setPreferredSize(new Dimension(88, 30));
        count.setForeground(ThemeManager.getPalette().textSecondary());
        controls.add(count);
        JButton close = new JButton("关闭");
        for (JButton button : List.of(previous, next, close)) {
            ComponentStyleUtil.styleSecondaryButton(button); controls.add(button);
        }
        previous.setToolTipText("上一项（Shift+Enter / Shift+F3）");
        next.setToolTipText("下一项（Enter / F3）");
        close.setToolTipText("关闭查找（Esc），保留原来的节点选择");
        previous.addActionListener(event -> navigate(-1));
        next.addActionListener(event -> navigate(1));
        close.addActionListener(event -> onClose.run());
        add(controls, BorderLayout.EAST);
        query.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { update(true); }
            public void removeUpdate(DocumentEvent event) { update(true); }
            public void changedUpdate(DocumentEvent event) { update(true); }
        });
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "next", () -> navigate(1));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), "previous", () -> navigate(-1));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close", onClose);
        setVisible(false);
        updateControls();
    }

    private void bind(KeyStroke stroke, String name, Runnable operation) {
        query.getInputMap().put(stroke, name);
        query.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { operation.run(); }
        });
    }

    void open() {
        setVisible(true);
        update(true);
        query.requestFocusInWindow(); query.selectAll();
    }

    void close() {
        setVisible(false);
        onResultsChanged.run();
    }

    /** 外部换宏才清空查询；关闭/重开及撤销保留查询。 */
    void reset() {
        setVisible(false);
        query.setText(""); matches = List.of(); currentId = null;
        updateControls(); onResultsChanged.run();
    }

    /** 模型改变时刷新结果但不抢滚动位置；隐藏时不执行查找。 */
    void refresh(List<MacroNode> nodes) {
        this.nodes = List.copyOf(nodes);
        if (isVisible()) update(false);
    }

    private void update(boolean scroll) {
        if (!isVisible()) return;
        matches = find(nodes, query.getText());
        if (currentId == null || !matches.contains(currentId)) currentId = matches.isEmpty() ? null : matches.get(0);
        updateControls();
        onResultsChanged.run();
        if (scroll && currentId != null) onNavigate.accept(currentId);
    }

    void navigate(int direction) {
        if (!isVisible() || matches.isEmpty()) return;
        currentId = matches.get(Math.floorMod(matches.indexOf(currentId) + direction, matches.size()));
        updateControls(); onResultsChanged.run(); onNavigate.accept(currentId);
    }

    private void updateControls() {
        boolean found = !matches.isEmpty();
        previous.setEnabled(found); next.setEnabled(found);
        count.setText(found ? (matches.indexOf(currentId) + 1) + " / " + matches.size()
                : normalize(query.getText()).isBlank() ? "待输入" : "无匹配");
        count.setToolTipText(found ? "仅定位，不改变原来的节点选择；到末项后循环查找" : "查找编号、动作名称、按键或模板图片名");
    }

    String currentId() { return isVisible() ? currentId : null; }
    List<String> matches() { return isVisible() ? matches : List.of(); }

    static List<String> find(List<MacroNode> nodes, String text) {
        String query = normalize(text);
        if (query.isBlank()) return List.of();
        String ordinal = query.startsWith("#") ? query.substring(1).strip() : query;
        boolean numeric = ordinal.matches("[0-9]+");
        if (numeric) ordinal = ordinal.replaceFirst("^0+(?!$)", "");
        String[] words = query.split("\\s+");
        Pattern[] letters = new Pattern[words.length];
        for (int i = 0; i < words.length; i++) if (words[i].matches("[a-z]"))
            letters[i] = Pattern.compile("(?<![a-z0-9])" + words[i] + "(?![a-z0-9])");
        List<String> result = new ArrayList<>();
        int number = 0;
        for (MacroNode node : nodes) {
            if (node == null || node.getType() == NodeType.END) continue;
            number++;
            if (numeric) {
                if (Integer.toString(number).equals(ordinal)) result.add(node.getId());
                continue;
            }
            String content = searchableText(node);
            boolean match = true;
            for (int i = 0; i < words.length; i++) {
                // 单个字母按独立名称查找，避免 A 命中 RELEASE/IMAGE 等类型名中的字母。
                if (letters[i] == null ? !content.contains(words[i]) : !letters[i].matcher(content).find()) { match = false; break; }
            }
            if (match) result.add(node.getId());
        }
        return List.copyOf(result);
    }

    private static String searchableText(MacroNode node) {
        String content = ActionTimelinePanel.getNodeName(node) + " ";
        if (node.getType() == NodeType.IMAGE_CHECK) {
            String path = node.getImageCheckConfig() == null ? null : node.getImageCheckConfig().getImagePath();
            if (path != null) {
                path = path.replace('\\', '/'); content += path.substring(path.lastIndexOf('/') + 1);
            }
            return normalize(content + " 图片 image image_check");
        }
        var action = node.getAction();
        content += action.getType().name() + " ";
        if (action.getType() == ActionType.KEY_PRESS || action.getType() == ActionType.KEY_RELEASE) {
            content += (action.getKey() == null ? "" : action.getKey()) + " " + KeyEvent.getKeyText(action.getKeyCode())
                    + " " + KEY_NAMES.getOrDefault(action.getKeyCode(), "") + " 键码 " + action.getKeyCode();
            if (action.getKeySide() != null) content += switch (action.getKeySide()) {
                case LEFT -> " 左侧 left"; case RIGHT -> " 右侧 right"; default -> "";
            };
        }
        if (action.getMouseButton() != null) content += " " + switch (action.getMouseButton()) {
            case LEFT -> "左键 left"; case MIDDLE -> "中键 middle"; case RIGHT -> "右键 right";
            default -> "不支持的按键";
        };
        return normalize(content);
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
    }

    /** 缓存公开按键常量名，避免系统本地化名称不同导致 Escape 等英文查询失效。 */
    private static Map<Integer, String> keyNames() {
        Map<Integer, String> result = new HashMap<>();
        for (var field : KeyEvent.class.getFields()) {
            if (!field.getName().startsWith("VK_") || field.getType() != int.class || !Modifier.isStatic(field.getModifiers())) continue;
            try {
                String name = field.getName().substring(3);
                result.merge(field.getInt(null), name + " " + name.replace('_', ' '), (first, second) -> first + " " + second);
            } catch (IllegalAccessException ignored) { /* 公开常量不可读时仍可用已存名称及 AWT 显示名查找。 */ }
        }
        return Map.copyOf(result);
    }
}
