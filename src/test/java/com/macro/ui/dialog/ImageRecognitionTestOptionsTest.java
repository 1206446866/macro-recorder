package com.macro.ui.dialog;

import com.macro.ocr.OcrEngine;
import com.macro.recognition.TemplateScaleRange;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** 隐藏 Swing 窗口和可控文字引擎，不截图、不写剪贴板、不提取个人图片。 */
class ImageRecognitionTestOptionsTest {
    @Test void minimumSizeScopeAndScaleControlsStayUsable() throws Exception {
        ImageRecognitionFrame frame = create((image, languages, single, stop) -> "");
        try { edt(() -> {
            Dimension minimum = frame.getMinimumSize();
            frame.setSize(1, 1);
            assertEquals(minimum, frame.getSize());
            assertEquals(new Dimension(1080, 640), minimum);
            List<String> labels = labels(frame.getContentPane());
            assertTrue(labels.contains("识别范围：桌面"));
            assertTrue(labels.contains("识别文字"));
            assertFalse(labels.contains("图像识别测试"));
            assertTrue(labels.stream().noneMatch(t -> t.contains("不发送点击") || t.contains("采集时自动隐藏")));
            assertEquals(TemplateScaleRange.DEFAULT, invoke(frame, "readScaleRange"));
            JCheckBox enabled = field(frame, "scaleMatchingCheckBox");
            JSpinner lower = field(frame, "minimumScaleSpinner"), upper = field(frame, "maximumScaleSpinner");
            assertFalse(lower.isEnabled());
            enabled.doClick();
            assertTrue(lower.isEnabled()); assertTrue(upper.isEnabled());
            set(frame, "recognizing", true); invoke(frame, "updateActionState");
            assertFalse(enabled.isEnabled()); assertFalse(lower.isEnabled()); assertFalse(upper.isEnabled());
            set(frame, "recognizing", false);
            invoke(frame, "updateActionState"); assertTrue(lower.isEnabled());
            return null;
        }); } finally { dispose(frame); }
    }

    @Test void scaleInputsAcceptSeparateDecimalsAndRejectInvalidValues() throws Exception {
        ImageRecognitionFrame frame = create((image, languages, single, stop) -> "");
        try { edt(() -> {
            JSpinner lower = field(frame, "minimumScaleSpinner"), upper = field(frame, "maximumScaleSpinner");
            text(lower, "50.25"); text(upper, "200");
            assertEquals(new TemplateScaleRange(.5025, 2), invoke(frame, "readScaleRange"));
            for (String invalid : List.of("", "-1", "0", "101", "NaN", "3.1234", "10x")) {
                text(lower, invalid);
                assertThrows(IllegalArgumentException.class, () -> invoke(frame, "readScaleRange"));
                assertThrows(java.text.ParseException.class,
                        () -> ((JSpinner.DefaultEditor) lower.getEditor()).getTextField().commitEdit());
                assertEquals(50.25, ((Number) lower.getValue()).doubleValue());
            }
            text(lower, "66.667"); text(upper, "99");
            assertThrows(IllegalArgumentException.class, () -> invoke(frame, "readScaleRange"));
            text(upper, "400.001");
            assertThrows(IllegalArgumentException.class, () -> invoke(frame, "readScaleRange"));
            text(upper, "150");
            assertEquals(.66667, ((TemplateScaleRange) invoke(frame, "readScaleRange")).minimum(), 1e-12);
            return null;
        }); } finally { dispose(frame); }
    }

    @Test void hiddenTemplateWaitsForOpeningAndEmptyTextCanBeRetried() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ImageRecognitionFrame frame = create((image, languages, single, stop) -> {
            assertEquals(5, languages.size()); assertFalse(single);
            assertEquals(37, image.getWidth());
            return calls.incrementAndGet() == 1 ? " \n" : "中文 Example\n123";
        });
        try {
            File file = image(37);
            edt(() -> { frame.setTemplate(file, .9, true); return null; });
            assertEquals(0, calls.get(), "隐藏编辑器预览不启动 OCR");
            edt(() -> { invoke(frame, "startPendingTemplateText"); return null; });
            awaitIdle(frame);
            edt(() -> {
                assertEquals("", ((JTextArea) field(frame, "templateTextArea")).getText());
                JLabel empty = field(frame, "templateTextEmpty");
                assertTrue(empty.isVisible());
                assertEquals("无信息", empty.getText());
                assertFalse(((JButton) field(frame, "copyTextButton")).isEnabled());
                ((JButton) field(frame, "retryTextButton")).doClick(); return null;
            });
            awaitIdle(frame);
            edt(() -> {
                assertEquals("中文 Example\n123", ((JTextArea) field(frame, "templateTextArea")).getText());
                assertFalse(((JLabel) field(frame, "templateTextEmpty")).isVisible());
                assertTrue(((JButton) field(frame, "copyTextButton")).isEnabled());
                return null;
            });
        } finally { dispose(frame); }
    }

    @Test void replacingTemplatesCancelsStaleResultAndOnlyQueuesLatest() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<BooleanSupplier> firstStop = new AtomicReference<>();
        ImageRecognitionFrame frame = create((image, languages, single, stop) -> {
            if (calls.incrementAndGet() == 1) {
                firstStop.set(stop); started.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return "过时文字";
            }
            assertEquals(39, image.getWidth()); return "最新文字";
        });
        try {
            File first = image(37), middle = image(38), last = image(39);
            edt(() -> { frame.setTemplate(first, .9); invoke(frame, "startPendingTemplateText"); return null; });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            edt(() -> { frame.setTemplate(middle, .9); frame.setTemplate(last, .9); return null; });
            assertTrue(firstStop.get().getAsBoolean()); assertEquals(1, calls.get());
            release.countDown(); awaitIdle(frame);
            assertEquals(2, calls.get());
            assertEquals("最新文字", edt(() -> ((JTextArea) field(frame, "templateTextArea")).getText()));
        } finally { release.countDown(); dispose(frame); }
    }

    @Test void clearAndCloseDiscardLateNativeResults() throws Exception {
        for (boolean close : new boolean[]{false, true}) {
            CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicBoolean cancelled = new AtomicBoolean();
            ImageRecognitionFrame frame = create((image, languages, single, stop) -> {
                started.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS));
                cancelled.set(stop.getAsBoolean()); return "不应显示";
            });
            try {
                File file = image(37);
                edt(() -> { frame.setTemplate(file, .9); invoke(frame, "startPendingTemplateText"); return null; });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                edt(() -> { if (close) frame.dispose(); else invoke(frame, "clearTemplateImage"); return null; });
                release.countDown(); awaitIdle(frame);
                assertTrue(cancelled.get());
                assertFalse(edt(() -> ((JTextArea) field(frame, "templateTextArea")).getText()).contains("不应显示"));
                if (!close) assertTrue(edt(() -> ((JLabel) field(frame, "templateTextEmpty")).isVisible()));
                assertFalse(edt(() -> ((JButton) field(frame, "copyTextButton")).isEnabled()));
                assertFalse(frame.isShowing());
            } finally { release.countDown(); dispose(frame); }
        }
    }

    @Test void extractionFailureIsVisibleAndRetryRecovers() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ImageRecognitionFrame frame = create((image, languages, single, stop) -> {
            if (calls.incrementAndGet() == 1) throw new IOException("已有文字提取正在进行");
            return "恢复成功";
        });
        try {
            File file = image(37);
            edt(() -> { frame.setTemplate(file, .9); invoke(frame, "startPendingTemplateText"); return null; });
            awaitIdle(frame);
            edt(() -> {
                JLabel status = field(frame, "templateTextStatus");
                assertTrue(status.getText().contains("提取失败"));
                assertEquals("已有文字提取正在进行", status.getToolTipText());
                assertFalse(((JButton) field(frame, "copyTextButton")).isEnabled());
                ((JButton) field(frame, "retryTextButton")).doClick(); return null;
            });
            awaitIdle(frame);
            assertEquals("恢复成功", edt(() -> ((JTextArea) field(frame, "templateTextArea")).getText()));
        } finally { dispose(frame); }
    }

    private static ImageRecognitionFrame create(OcrEngine engine) throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        return edt(() -> new ImageRecognitionFrame(null, () -> null, engine));
    }
    private static File image(int width) throws IOException {
        Path root = Files.createDirectories(Path.of(System.getProperty("macro.test.sandboxRoot", "target/test-sandboxes")));
        Path file = Files.createTempDirectory(root, "recognition-options-").resolve("template.png");
        ImageIO.write(new BufferedImage(width, 28, BufferedImage.TYPE_INT_RGB), "png", file.toFile());
        return file.toFile();
    }
    private static void awaitIdle(ImageRecognitionFrame frame) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            if (edt(() -> field(frame, "templateTextWorker") == null && !(boolean) field(frame, "templateTextPending"))) return;
            Thread.sleep(15);
        }
        fail("文字任务未按时收尾");
    }
    private static void text(JSpinner spinner, String text) {
        ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().setText(text);
    }
    @SuppressWarnings("unchecked") private static <T> T field(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return (T) field.get(target);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static Object invoke(Object target, String name) throws Exception {
        var method = target.getClass().getDeclaredMethod(name); method.setAccessible(true);
        try { return method.invoke(target); }
        catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
    }
    private static List<String> labels(Container parent) {
        List<String> result = new ArrayList<>();
        for (Component c : parent.getComponents()) {
            if (c instanceof JLabel label && label.getText() != null) result.add(label.getText());
            if (c instanceof Container child) result.addAll(labels(child));
        }
        return result;
    }
    private static <T> T edt(Checked<T> task) throws Exception {
        AtomicReference<T> result = new AtomicReference<>(); AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> { try { result.set(task.run()); } catch (Throwable e) { failure.set(e); } });
        if (failure.get() instanceof Exception e) throw e;
        if (failure.get() != null) throw new AssertionError(failure.get());
        return result.get();
    }
    private static void dispose(ImageRecognitionFrame frame) throws Exception { edt(() -> { frame.dispose(); return null; }); }
    @FunctionalInterface private interface Checked<T> { T run() throws Exception; }
}
