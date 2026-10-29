package com.macro.ui.dialog;

import com.macro.ocr.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** 不显示窗口、不访问剪贴板或原生文件面板；用替身检查后台识别与取消生命周期。 */
@EnabledOnOs(OS.WINDOWS) @Timeout(10)
class OcrTextDialogTest {
    @Test void openingDoesNotExtractAndDefaultsToChineseAndEnglish() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            OcrTextDialog dialog = dialog((image, languages, line, stop) -> { fail("打开窗口不能自动提取"); return ""; });
            try {
                @SuppressWarnings("unchecked") var languages = (java.util.Map<OcrLanguage, JCheckBox>) field(dialog, "languages");
                assertEquals(5, languages.size());
                assertEquals(Set.of(OcrLanguage.SIMPLIFIED_CHINESE, OcrLanguage.ENGLISH), languages.entrySet().stream()
                        .filter(entry -> entry.getValue().isSelected()).map(java.util.Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()));
                assertFalse(button(dialog, "copy").isEnabled()); assertFalse(button(dialog, "cancel").isEnabled());
                render(dialog);
            } finally { dialog.dispose(); }
        });
    }

    @Test void extractsOffEdtThenAllowsEditingResult() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        AtomicReference<OcrTextDialog> reference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            OcrTextDialog dialog = dialog((image, languages, line, stop) -> {
                assertFalse(SwingUtilities.isEventDispatchThread());
                assertEquals(Set.of(OcrLanguage.SIMPLIFIED_CHINESE, OcrLanguage.ENGLISH), languages);
                return "中文\nHELLO";
            }); reference.set(dialog); button(dialog, "extract").doClick();
        });
        try {
            awaitIdle(reference.get());
            SwingUtilities.invokeAndWait(() -> {
                JTextArea text = (JTextArea) field(reference.get(), "text");
                assertEquals("中文\nHELLO", text.getText()); assertTrue(text.isEditable());
                text.append("\n修订"); assertTrue(button(reference.get(), "save").isEnabled()); render(reference.get());
            });
        } finally { SwingUtilities.invokeAndWait(reference.get()::dispose); }
    }

    @Test void cancellingDoesNotStartAnotherJobUntilEngineReallyReturns() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<OcrTextDialog> reference = new AtomicReference<>();
        AtomicReference<java.util.function.BooleanSupplier> token = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            OcrTextDialog dialog = dialog((image, languages, line, stop) -> {
                token.set(stop); entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); return "过时结果";
            }); reference.set(dialog); button(dialog, "extract").doClick();
        });
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> { button(reference.get(), "cancel").doClick(); assertFalse(button(reference.get(), "extract").isEnabled()); });
            assertTrue(token.get().getAsBoolean()); release.countDown(); awaitIdle(reference.get());
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("", ((JTextArea) field(reference.get(), "text")).getText());
                assertTrue(button(reference.get(), "extract").isEnabled());
                assertEquals("已取消文字提取", ((JLabel) field(reference.get(), "status")).getText());
            });
        } finally { release.countDown(); SwingUtilities.invokeAndWait(reference.get()::dispose); }
    }

    @Test void closeSignalsCancellationAndRejectsLateResults() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<OcrTextDialog> reference = new AtomicReference<>();
        AtomicReference<java.util.function.BooleanSupplier> token = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            OcrTextDialog dialog = dialog((image, languages, line, stop) -> {
                token.set(stop); entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); return "不应显示";
            }); reference.set(dialog); button(dialog, "extract").doClick();
        });
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS)); SwingUtilities.invokeAndWait(reference.get()::dispose);
            assertTrue(token.get().getAsBoolean()); release.countDown(); awaitIdle(reference.get());
            SwingUtilities.invokeAndWait(() -> assertEquals("", ((JTextArea) field(reference.get(), "text")).getText()));
        } finally { release.countDown(); SwingUtilities.invokeAndWait(reference.get()::dispose); }
    }

    @Test void savesEditedResultThroughExistingFilePanelAsUtf8() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        Path root = directory(); Path destination = root.resolve("文字");
        AtomicReference<OcrTextDialog> reference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            OcrTextDialog dialog = new OcrTextDialog(null, new BufferedImage(100, 60, BufferedImage.TYPE_INT_RGB), root::toFile,
                    (image, languages, line, stop) -> "中文\nHELLO", (parent, options) -> {
                        assertEquals("txt", options.defaultExtension()); assertFalse(options.confirmOverwrite());
                        return Optional.of(destination.toFile());
                    });
            reference.set(dialog); button(dialog, "extract").doClick();
        });
        try {
            awaitIdle(reference.get());
            SwingUtilities.invokeAndWait(() -> { ((JTextArea) field(reference.get(), "text")).append("\n编辑"); button(reference.get(), "save").doClick(); });
            awaitIdle(reference.get());
            assertEquals("中文\nHELLO\n编辑", Files.readString(root.resolve("文字.txt")));
        } finally { SwingUtilities.invokeAndWait(reference.get()::dispose); }
    }

    private static OcrTextDialog dialog(OcrEngine engine) {
        return new OcrTextDialog(null, new BufferedImage(400, 150, BufferedImage.TYPE_INT_RGB), () -> null, engine,
                (parent, options) -> { fail("不得自动打开文件面板"); return Optional.empty(); });
    }
    private static JButton button(OcrTextDialog dialog, String name) { return (JButton) field(dialog, name); }
    private static Object field(Object target, String name) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException failed) { throw new AssertionError(failed); }
    }
    private static void awaitIdle(OcrTextDialog dialog) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        AtomicBoolean idle = new AtomicBoolean();
        do {
            SwingUtilities.invokeAndWait(() -> idle.set(field(dialog, "worker") == null && !(Boolean) field(dialog, "saving")));
            if (idle.get()) return;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        fail("OCR 界面未及时完成后台操作");
    }
    private static void render(OcrTextDialog dialog) {
        JRootPane root = dialog.getRootPane(); root.setSize(760, 520); layout(root);
        for (JButton button : new JButton[]{button(dialog, "extract"), button(dialog, "cancel"), button(dialog, "copy"), button(dialog, "save")}) {
            assertTrue(button.getWidth() > 100); assertTrue(button.getHeight() > 20);
        }
        BufferedImage image = new BufferedImage(1140, 780, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics(); graphics.scale(1.5, 1.5); root.paint(graphics); graphics.dispose();
        try { javax.imageio.ImageIO.write(image, "png", directory().resolve("ocr-dialog-150.png").toFile()); }
        catch (Exception failed) { throw new AssertionError(failed); }
    }
    private static void layout(Container parent) {
        parent.doLayout(); for (Component component : parent.getComponents()) if (component instanceof Container child) layout(child);
    }
    private static Path directory() throws Exception {
        Path root = Path.of(System.getProperty("macro.test.sandboxRoot", "target/test-sandboxes")).toAbsolutePath();
        Files.createDirectories(root); return Files.createTempDirectory(root, "ocr-ui-");
    }
}
