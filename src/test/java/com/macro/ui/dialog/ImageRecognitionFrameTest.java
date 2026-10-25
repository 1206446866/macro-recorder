package com.macro.ui.dialog;

import com.macro.recognition.ScreenRecognitionService;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** 仅构造隐藏窗口和注入结果；不触发屏幕捕获、剪贴板、文件选择或后台识别。 */
class ImageRecognitionFrameTest {
    @Test
    void singleRecognitionButtonKeepsTemplateAndBusyStates() throws Exception {
        onEdt(frame -> {
            var components = descendants(frame.getContentPane());
            var buttons = components.stream().filter(JButton.class::isInstance).map(JButton.class::cast)
                    .filter(button -> button.getText() != null && button.getText().startsWith("识别当前屏幕")).toList();
            assertEquals(1, buttons.size());
            JButton recognize = buttons.get(0);
            assertEquals("识别当前屏幕", recognize.getText());
            assertEquals(1, recognize.getActionListeners().length);
            assertFalse(recognize.isEnabled());
            assertTrue(components.stream().noneMatch(c -> c instanceof JLabel label
                    && "识别引擎".equals(label.getText())));
            set(frame, "templateImage", new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB));
            invoke(frame, "updateActionState");
            assertTrue(recognize.isEnabled());
            set(frame, "recognizing", true);
            invoke(frame, "updateActionState");
            assertFalse(recognize.isEnabled());
            set(frame, "recognizing", false);
            invoke(frame, "clearTemplateImage");
            assertFalse(recognize.isEnabled());
        });
    }

    @Test
    void reportsAndResetKeepResultsWithoutEngineInformation() throws Exception {
        onEdt(frame -> {
            BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
            var result = new ScreenRecognitionService.RecognitionResult(true, 1.0,
                    new Rectangle(-2, 3, 4, 4), new Rectangle(0, 0, 4, 4), image, "测试屏幕");
            var report = new ScreenRecognitionService.RecognitionReport(List.of(result), result, false,
                    12, 5, 64, 128, 256);
            Method show = ImageRecognitionFrame.class.getDeclaredMethod("showRecognitionReport",
                    ScreenRecognitionService.RecognitionReport.class);
            show.setAccessible(true);
            show.invoke(frame, report);
            List<String> text = descendants(frame.getContentPane()).stream().filter(JLabel.class::isInstance)
                    .map(JLabel.class::cast).map(JLabel::getText).filter(java.util.Objects::nonNull).toList();
            assertTrue(text.contains("识别到 1 个结果"));
            assertTrue(text.stream().anyMatch(value -> value.contains("12 ms")));
            assertTrue(text.stream().noneMatch(value -> value.contains("识别引擎") || value.contains("OpenCV")));
            show.invoke(frame, new ScreenRecognitionService.RecognitionReport(List.of(), result, false,
                    3, 0, 0, 0, 0));
            assertTrue(descendants(frame.getContentPane()).stream().anyMatch(c -> c instanceof JLabel label
                    && "未找到匹配结果".equals(label.getText())));
            invoke(frame, "clearResult");
        });
    }

    private static void onEdt(Check check) throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "隐藏识别窗口需要桌面环境");
        SwingUtilities.invokeAndWait(() -> {
            ImageRecognitionFrame frame = new ImageRecognitionFrame(null, () -> null);
            try { check.run(frame); } catch (Exception failure) { throw new AssertionError(failure); }
            finally { frame.dispose(); }
        });
    }

    private static void set(ImageRecognitionFrame frame, String name, Object value) throws Exception {
        Field field = ImageRecognitionFrame.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(frame, value);
    }

    private static void invoke(ImageRecognitionFrame frame, String name) throws Exception {
        Method method = ImageRecognitionFrame.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(frame);
    }

    private static List<Component> descendants(Container parent) {
        List<Component> components = new ArrayList<>();
        for (Component component : parent.getComponents()) {
            components.add(component);
            if (component instanceof Container child) components.addAll(descendants(child));
        }
        return components;
    }

    @FunctionalInterface
    private interface Check { void run(ImageRecognitionFrame frame) throws Exception; }
}
