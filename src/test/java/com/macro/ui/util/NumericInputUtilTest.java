package com.macro.ui.util;

import javax.swing.JFormattedTextField;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import java.awt.datatransfer.StringSelection;
import java.awt.event.FocusEvent;
import java.util.List;

/** 在 EDT 上验证真实 Swing 文档、粘贴、格式化器和提交行为，不访问系统剪贴板。 */
public final class NumericInputUtilTest {
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            testTextFields();
            testSpinner(1, 1, Integer.MAX_VALUE, "循环次数");
            testSpinner(20, 0, Integer.MAX_VALUE, "采样间隔（ms）");
            testSpinner(90, 1, 100, "节点阈值（%）");
            testSpinner(90, 50, 100, "测试阈值（%）");
            testLongSpinner();
        });
        System.out.println("通过：字符过滤、粘贴/全选替换、临时空值、正负整数、边界/溢出、失焦及 Spinner 提交");
    }

    private static void testTextFields() {
        JTextField delay = new JTextField("0");
        ComponentStyleUtil.styleTextField(delay);
        NumericInputUtil.installNonNegativeLong(delay, "延迟（ms）");
        replace(delay, "120");
        check(NumericInputUtil.readLong(delay) == 120L, "数字输入失败");
        for (String invalid : List.of("12a", "-1", "1.5", "1e3", "+3", "1,000", " 3 ", "１２", "١٢")) {
            paste(delay, invalid);
            check(delay.getText().equals("120"), "非法粘贴未被整体拒绝：" + invalid);
        }
        replace(delay, "");
        check(delay.getText().isEmpty(), "不允许临时清空");
        expectInvalid(() -> NumericInputUtil.readLong(delay), "不能为空");
        check(delay.getToolTipText().contains("不能为空"), "缺少空值提示");
        replace(delay, "0");
        check(delay.getToolTipText() == null, "重新编辑未清除过期错误提示");
        check(NumericInputUtil.readLong(delay) == 0L, "延迟错误禁止了零");
        delay.setText(Long.toString(Long.MAX_VALUE));
        check(NumericInputUtil.readLong(delay) == Long.MAX_VALUE, "合法 long 最大值被拒绝");
        replace(delay, "9223372036854775808");
        check(delay.getText().equals("9223372036854775808"), "输入途中错误限制数值大小");
        expectInvalid(() -> NumericInputUtil.readLong(delay), "延迟（ms）必须在");

        JTextField coordinate = new JTextField("0");
        NumericInputUtil.installInteger(coordinate, "X 坐标");
        replace(coordinate, "-");
        expectInvalid(() -> NumericInputUtil.readInt(coordinate), "完整的整数");
        coordinate.setCaretPosition(1);
        coordinate.replaceSelection("25");
        check(NumericInputUtil.readInt(coordinate) == -25, "无法从负号继续输入负数");
        for (int value : new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE, 0}) {
            coordinate.setText(Integer.toString(value));
            check(NumericInputUtil.readInt(coordinate) == value, "合法 int 边界被拒绝");
        }
        coordinate.setText("2147483648");
        expectInvalid(() -> NumericInputUtil.readInt(coordinate), "X 坐标必须在");
        coordinate.setText("-2147483649");
        expectInvalid(() -> NumericInputUtil.readInt(coordinate), "X 坐标必须在");
        replace(coordinate, "123");
        coordinate.setCaretPosition(1);
        coordinate.replaceSelection("-");
        check(coordinate.getText().equals("123"), "允许在整数中间插入负号");
        coordinate.select(0, 2);
        coordinate.replaceSelection("");
        check(coordinate.getText().equals("3"), "局部删除失败");
        coordinate.setText("123");
        coordinate.setCaretPosition(3);
        coordinate.getActionMap().get(javax.swing.text.DefaultEditorKit.deletePrevCharAction)
                .actionPerformed(new java.awt.event.ActionEvent(coordinate, 0, "退格"));
        check(coordinate.getText().equals("12"), "退格失败");
        coordinate.setCaretPosition(0);
        coordinate.getActionMap().get(javax.swing.text.DefaultEditorKit.deleteNextCharAction)
                .actionPerformed(new java.awt.event.ActionEvent(coordinate, 0, "删除"));
        check(coordinate.getText().equals("2"), "Delete 删除失败");

        JTextField ordinary = new JTextField("C:/保存目录/abc123");
        ordinary.setText("Ctrl + Alt / D:/file.png");
        check(ordinary.getText().equals("Ctrl + Alt / D:/file.png"), "普通文本受到数字工具影响");
    }

    private static void testSpinner(int initial, int minimum, int maximum, String name) {
        SpinnerNumberModel model = new SpinnerNumberModel(initial, minimum, maximum, 1);
        JSpinner spinner = new JSpinner(model);
        ComponentStyleUtil.styleSpinner(spinner);
        NumericInputUtil.installSpinner(spinner, name);
        JFormattedTextField field = ((JSpinner.NumberEditor) spinner.getEditor()).getTextField();
        int edited = initial == maximum ? initial - 1 : initial + 1;
        replace(field, Integer.toString(edited));
        check(spinner.getValue().equals(initial), "编辑过程中擅自提交了 Spinner 值");
        check(NumericInputUtil.commitSpinnerInt(spinner) == edited, "提交仍读取旧值");
        check(spinner.getValue().equals(edited), "提交没有更新整数模型");
        paste(field, "19x");
        check(field.getText().equals(Integer.toString(edited)), "Spinner 允许非法粘贴");
        paste(field, "1.5");
        check(field.getText().equals(Integer.toString(edited)), "Spinner 允许小数");
        replace(field, "");
        blur(field);
        check(field.getText().isEmpty(), "失焦错误地用旧值覆盖了空文本");
        check(spinner.getValue().equals(edited), "空输入污染了模型");
        check(field.getToolTipText().contains("不能为空"), "Spinner 失焦没有给出提示");
        expectInvalid(() -> NumericInputUtil.commitSpinnerInt(spinner), "不能为空");

        replace(field, Long.toString((long) maximum + 1L));
        try {
            field.commitEdit();
            throw new AssertionError("原生格式化器提交绕过了范围校验");
        } catch (java.text.ParseException expected) {
            check(expected.getMessage().contains(name), "格式化器丢失了字段错误说明");
        }
        blur(field);
        check(spinner.getValue().equals(edited), "越界输入污染了模型");
        expectInvalid(() -> NumericInputUtil.commitSpinnerInt(spinner), name + "必须在");
        replace(field, "999999999999999999999999");
        expectInvalid(() -> NumericInputUtil.commitSpinnerInt(spinner), name + "必须在");
        for (int boundary : new int[]{minimum, maximum}) {
            replace(field, Integer.toString(boundary));
            blur(field);
            check(spinner.getValue().equals(boundary), "失焦没有提交合法边界");
            check(field.getToolTipText() == null, "合法值仍带有错误提示");
        }
        if (minimum > 0) {
            replace(field, "0");
            expectInvalid(() -> NumericInputUtil.commitSpinnerInt(spinner), name + "必须在");
        }
        // 模型通过代码更新、格式化器重装后，过滤器仍必须有效。
        spinner.setValue(initial);
        paste(field, "9a");
        check(field.getText().equals(Integer.toString(initial)), "程序设置值后丢失过滤器");
        replace(field, Integer.toString(minimum));
        javax.swing.JButton next = (javax.swing.JButton) java.util.Arrays.stream(spinner.getComponents())
                .filter(component -> "Spinner.nextButton".equals(component.getName())).findFirst().orElseThrow();
        next.doClick();
        check(spinner.getValue().equals(minimum + 1), "上箭头没有先提交文本再递增");
        replace(field, Integer.toString(minimum + 2));
        // Swing 的提交动作读取当前文本焦点，而不是 ActionEvent 的来源。
        for (var listener : field.getFocusListeners()) listener.focusGained(new FocusEvent(field, FocusEvent.FOCUS_GAINED));
        var enter = field.getActionMap().get("notify-field-accept");
        enter.actionPerformed(new java.awt.event.ActionEvent(field, 0, "提交"));
        check(spinner.getValue().equals(minimum + 2), "Enter 没有提交最新的 Spinner 文本");
        // 与模拟获得焦点配对，关闭光标闪烁，避免测试通过后 EDT 仍因计时器保持运行。
        blur(field);
        check(spinner.getModel() == model && model.getMinimum().equals(minimum)
                && model.getMaximum().equals(maximum) && model.getStepSize().equals(1), "改变了原模型、范围或步长");
    }

    private static void testLongSpinner() {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel((Number) 0L, Long.MIN_VALUE, Long.MAX_VALUE, 1L));
        NumericInputUtil.installSpinner(spinner, "长整数");
        JTextField field = ((JSpinner.NumberEditor) spinner.getEditor()).getTextField();
        replace(field, Long.toString(Long.MIN_VALUE));
        blur(field);
        check(spinner.getValue().equals(Long.MIN_VALUE), "long 格式化器丢失精度或数值类型");
    }

    private static void replace(JTextField field, String text) {
        field.selectAll();
        field.replaceSelection(text);
    }

    private static void paste(JTextField field, String text) {
        field.selectAll();
        field.getTransferHandler().importData(field, new StringSelection(text));
    }

    private static void blur(JTextField field) {
        FocusEvent event = new FocusEvent(field, FocusEvent.FOCUS_LOST);
        for (var listener : field.getFocusListeners()) listener.focusLost(event);
    }

    private static void expectInvalid(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError("预期校验失败：" + message);
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains(message), "错误提示不明确：" + expected.getMessage());
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
