package com.macro.ui.util;

import com.macro.ui.theme.ThemeManager;

import javax.swing.JFormattedTextField;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.BorderFactory;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultFormatterFactory;
import javax.swing.text.DocumentFilter;
import javax.swing.text.NumberFormatter;
import java.awt.Insets;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.text.NumberFormat;
import java.text.ParseException;
import java.util.Locale;

/** 数字输入的格式过滤与提交校验；不负责配置、动作或播放逻辑。 */
public final class NumericInputUtil {
    private static final String BINDING_KEY = "macro.numericInput";

    /** 数字输入规则通过静态入口安装和读取，无需创建工具实例。 */
    private NumericInputUtil() { }

    /** 安装完整 int 范围的有符号整数输入，坐标和滚轮量均可使用。 */
    public static void installInteger(JTextField field, String name) {
        installInteger(field, name, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /** 范围只在校验时检查；编辑过程中允许暂时清空，允许负数的字段还允许单独的负号。 */
    public static void installInteger(JTextField field, String name, long minimum, long maximum) {
        Rule rule = new Rule(name, minimum, maximum);
        ((AbstractDocument) field.getDocument()).setDocumentFilter(new IntegerFilter(minimum < 0));
        bind(field, rule, null);
    }

    /** 为字段安装非负长整数规则，允许的最大值仍为 Long.MAX_VALUE。 */
    public static void installNonNegativeLong(JTextField field, String name) {
        installInteger(field, name, 0L, Long.MAX_VALUE);
    }

    /** 保留整数 Spinner 模型的数值类型、范围、步长和默认值，只调整编辑与提交机制。 */
    public static void installSpinner(JSpinner spinner, String name) {
        if (!(spinner.getModel() instanceof SpinnerNumberModel model)
                || !(spinner.getEditor() instanceof JSpinner.NumberEditor editor)) {
            throw new IllegalArgumentException("数字输入工具需要整数 SpinnerNumberModel 和 NumberEditor");
        }
        boolean integer = model.getNumber() instanceof Integer;
        if (!integer && !(model.getNumber() instanceof Long)) {
            throw new IllegalArgumentException("当前工具仅处理整数 Spinner");
        }
        long minimum = model.getMinimum() == null ? (integer ? Integer.MIN_VALUE : Long.MIN_VALUE)
                : ((Number) model.getMinimum()).longValue();
        long maximum = model.getMaximum() == null ? (integer ? Integer.MAX_VALUE : Long.MAX_VALUE)
                : ((Number) model.getMaximum()).longValue();
        Rule rule = new Rule(name, minimum, maximum);
        JFormattedTextField field = editor.getTextField();
        field.setFormatterFactory(new DefaultFormatterFactory(new IntegerFormatter(rule, integer)));
        field.setFocusLostBehavior(JFormattedTextField.PERSIST);
        field.setValue(model.getValue());
        bind(field, rule, spinner);
    }

    /** 必须读取正在编辑的文本，不能用 Spinner 旧模型值掩盖空值或无效输入。 */
    public static int commitSpinnerInt(JSpinner spinner) {
        int value = readInt(spinnerField(spinner));
        commitSpinner(spinner);
        return value;
    }

    /** 取得标准数字微调框内的文本字段，非标准编辑器明确拒绝。 */
    private static JFormattedTextField spinnerField(JSpinner spinner) {
        if (!(spinner.getEditor() instanceof JSpinner.DefaultEditor editor)) {
            throw new IllegalArgumentException("数字微调框缺少编辑器");
        }
        return editor.getTextField();
    }

    /** 按字段规则校验并提交微调框当前文本，非法输入保留错误状态而不覆盖模型。 */
    private static long commitSpinner(JSpinner spinner) {
        JTextField field = spinnerField(spinner);
        long value = readLong(field);
        try {
            spinner.commitEdit();
        } catch (ParseException exception) {
            Binding binding = binding(field);
            binding.markError(exception.getMessage());
            throw new IllegalArgumentException(binding.rule.name + "无法提交：" + exception.getMessage(), exception);
        }
        return value;
    }

    /** 读取并校验整数字段，超出 int 范围时显示字段错误并拒绝返回截断值。 */
    public static int readInt(JTextField field) {
        long value = readLong(field);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            String message = binding(field).rule.name + "超出整数范围";
            binding(field).markError(message);
            throw new IllegalArgumentException(message);
        }
        return (int) value;
    }

    /** 按绑定规则读取长整数，成功时清除提示，失败时标记字段并继续抛出校验异常。 */
    public static long readLong(JTextField field) {
        Binding binding = binding(field);
        try {
            long value = parse(field.getText(), binding.rule);
            binding.clearError();
            return value;
        } catch (IllegalArgumentException exception) {
            binding.markError(exception.getMessage());
            throw exception;
        }
    }

    /** 只接受完整十进制整数，并检查长整数溢出和字段允许的数值范围。 */
    private static long parse(String text, Rule rule) {
        if (text == null || text.isEmpty()) throw new IllegalArgumentException(rule.name + "不能为空");
        if (!text.matches("-?[0-9]+")) throw new IllegalArgumentException(rule.name + "必须是完整的整数");
        long value;
        try {
            value = Long.parseLong(text);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(rule.rangeMessage(), exception);
        }
        if (value < rule.minimum || value > rule.maximum) throw new IllegalArgumentException(rule.rangeMessage());
        return value;
    }

    /** 取得字段已安装的校验绑定，未安装规则时明确拒绝读取。 */
    private static Binding binding(JTextField field) {
        if (field.getClientProperty(BINDING_KEY) instanceof Binding binding) return binding;
        throw new IllegalArgumentException("尚未为该输入框安装数字规则");
    }

    /** 替换字段旧校验绑定，安装焦点和文档监听，并同步无障碍字段名称。 */
    private static void bind(JTextField field, Rule rule, JSpinner spinner) {
        if (field.getClientProperty(BINDING_KEY) instanceof Binding previous) {
            previous.clearError();
            field.removeFocusListener(previous);
            field.getDocument().removeDocumentListener(previous);
        }
        Binding binding = new Binding(field, rule, spinner);
        field.putClientProperty(BINDING_KEY, binding);
        field.addFocusListener(binding);
        field.getDocument().addDocumentListener(binding);
        field.getAccessibleContext().setAccessibleName(rule.name);
    }

    private record Rule(String name, long minimum, long maximum) {
        /** 校验字段名称与数值上下界，建立一条完整的整数输入规则。 */
        private Rule {
            if (name == null || name.isBlank() || minimum > maximum) throw new IllegalArgumentException("数字规则无效");
        }
        /** 生成包含字段名称及允许范围的统一错误提示。 */
        private String rangeMessage() { return name + "必须在 " + minimum + " 到 " + maximum + " 之间"; }
    }

    /** 只检查整个替换结果的字符格式，不在输入途中检查数值大小或溢出。 */
    private static final class IntegerFilter extends DocumentFilter {
        private final boolean signed;
        private final DocumentFilter delegate;
        /** 创建不委托其他过滤器的整数字符过滤器，按参数允许或禁止负号。 */
        private IntegerFilter(boolean signed) { this(signed, null); }
        /** 保存负号规则及原文档过滤器，字符校验通过后仍交回原过滤器处理。 */
        private IntegerFilter(boolean signed, DocumentFilter delegate) {
            this.signed = signed;
            this.delegate = delegate;
        }
        /** 将插入操作转为统一替换校验，保持粘贴和普通键入使用同一规则。 */
        @Override public void insertString(FilterBypass bypass, int offset, String text, AttributeSet attributes)
                throws BadLocationException {
            replace(bypass, offset, 0, text, attributes);
        }
        /** 先检查替换后的完整文本，再交给原过滤器或文档提交，允许编辑过程中的空值和单独负号。 */
        @Override public void replace(FilterBypass bypass, int offset, int length, String text, AttributeSet attributes)
                throws BadLocationException {
            String current = bypass.getDocument().getText(0, bypass.getDocument().getLength());
            String candidate = current.substring(0, offset) + (text == null ? "" : text)
                    + current.substring(offset + length);
            if (candidate.matches(signed ? "-?[0-9]*" : "[0-9]*")) {
                if (delegate == null) bypass.replace(offset, length, text == null ? "" : text, attributes);
                else delegate.replace(bypass, offset, length, text == null ? "" : text, attributes);
            }
        }
        /** 通过同一替换入口处理删除，避免绕过原格式化器的编辑状态。 */
        @Override public void remove(FilterBypass bypass, int offset, int length) throws BadLocationException {
            replace(bypass, offset, length, "", null);
        }
    }

    /** 格式化器重新安装或程序设置值后，仍使用相同的字符过滤和完整整数校验。 */
    private static final class IntegerFormatter extends NumberFormatter {
        private final Rule rule;
        private final boolean integer;
        private final DocumentFilter filter;
        /** 安装带范围校验的整数格式化器，保留原编辑语义，并按参数输出 int 或 long。 */
        private IntegerFormatter(Rule rule, boolean integer) {
            super(NumberFormat.getIntegerInstance(Locale.ROOT));
            this.rule = rule;
            this.integer = integer;
            // 交给原格式化器维护编辑状态、光标及 Enter 提交，不能仅绕过文档直接写文本。
            this.filter = new IntegerFilter(rule.minimum < 0, super.getDocumentFilter());
            ((NumberFormat) getFormat()).setGroupingUsed(false);
            setValueClass(integer ? Integer.class : Long.class);
            setAllowsInvalid(true);
            setCommitsOnValidEdit(false);
            setOverwriteMode(false);
        }
        /** 返回保留原格式化行为的整数过滤器。 */
        @Override protected DocumentFilter getDocumentFilter() { return filter; }
        /** 把通过规则校验的文本转换为字段要求的整数类型，校验失败转为格式化异常。 */
        @Override public Object stringToValue(String text) throws ParseException {
            try {
                long value = parse(text, rule);
                if (integer) return (int) value;
                return value;
            } catch (IllegalArgumentException exception) {
                throw new ParseException(exception.getMessage(), 0);
            }
        }
    }

    /** 失焦时不弹窗、不抢焦点；保留错误文本，以现有主题的边框和提示反馈。 */
    private static final class Binding extends FocusAdapter implements DocumentListener {
        private final JTextField field;
        private final Rule rule;
        private final JSpinner spinner;
        private Border originalBorder;
        private String originalToolTip;
        private String originalDescription;
        private String error;
        /** 绑定字段、数值规则及可选微调框，供焦点提交和错误样式恢复使用。 */
        private Binding(JTextField field, Rule rule, JSpinner spinner) {
            this.field = field; this.rule = rule; this.spinner = spinner;
        }
        /** 字段失焦时提交当前有效数值；禁用或只读字段跳过，无效文本仅保留提示。 */
        @Override public void focusLost(FocusEvent event) {
            if (!field.isEnabled() || !field.isEditable()) return;
            try {
                if (spinner == null) readLong(field);
                else commitSpinner(spinner);
            } catch (IllegalArgumentException ignored) {
                // 校验方法已设置字段提示，禁止将无效文本提交到模型。
            }
        }
        /** 重新取得焦点时保留已有错误边框，避免主题焦点样式覆盖校验提示。 */
        @Override public void focusGained(FocusEvent event) {
            if (error != null) paintError();
        }
        /** 首次出错时保存原样式和说明，再更新错误提示及无障碍描述。 */
        private void markError(String message) {
            if (error == null) {
                originalBorder = field.getBorder();
                originalToolTip = field.getToolTipText();
                originalDescription = field.getAccessibleContext().getAccessibleDescription();
            }
            error = message;
            field.setToolTipText(message);
            field.getAccessibleContext().setAccessibleDescription(message);
            paintError();
        }
        /** 绘制错误边框并补偿原内边距，避免字段校验时内容位置跳动。 */
        private void paintError() {
            Insets insets = originalBorder == null ? new Insets(0, 0, 0, 0)
                    : originalBorder.getBorderInsets(field);
            field.setBorder(new CompoundBorder(BorderFactory.createLineBorder(
                    ThemeManager.getPalette().danger(), 1, true),
                    new EmptyBorder(Math.max(0, insets.top - 1), Math.max(0, insets.left - 1),
                            Math.max(0, insets.bottom - 1), Math.max(0, insets.right - 1))));
        }
        /** 恢复出错前的边框、提示和无障碍描述，重复清理无额外操作。 */
        private void clearError() {
            if (error == null) return;
            field.setBorder(originalBorder);
            field.setToolTipText(originalToolTip);
            field.getAccessibleContext().setAccessibleDescription(originalDescription);
            error = null;
        }
        /** 文本新增后移除旧错误提示，让下一次提交重新判断当前内容。 */
        @Override public void insertUpdate(DocumentEvent event) { clearError(); }
        /** 文本删除后移除旧错误提示，不提前把未完成输入写入模型。 */
        @Override public void removeUpdate(DocumentEvent event) { clearError(); }
        /** 文档属性变化时清除已过期的错误提示。 */
        @Override public void changedUpdate(DocumentEvent event) { clearError(); }
    }
}
