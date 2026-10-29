package com.macro.ocr;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** 内置本机引擎与合成文字，不采集桌面、不联网、不接触个人图片。 */
@EnabledOnOs(OS.WINDOWS)
class OcrServiceTest {
    private static String oldCacheDirectory;
    private final OcrService service = new OcrService();

    @BeforeAll static void isolateRuntimeFiles() throws Exception {
        oldCacheDirectory = System.getProperty("macro.ocr.cacheDirectory");
        Path sandbox = Path.of(System.getProperty("macro.test.sandboxRoot", "target/test-sandboxes")).toAbsolutePath();
        Files.createDirectories(sandbox);
        System.setProperty("macro.ocr.cacheDirectory", Files.createTempDirectory(sandbox, "ocr-中文-").toString());
    }

    @AfterAll static void restoreRuntimeDirectory() {
        if (oldCacheDirectory == null) System.clearProperty("macro.ocr.cacheDirectory");
        else System.setProperty("macro.ocr.cacheDirectory", oldCacheDirectory);
    }

    @Test void readsEnglishAndDigitsWithoutChangingInput() throws Exception {
        BufferedImage image = text("HELLO WORLD 12345");
        int[] before = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        assertEquals("HELLO WORLD 12345", service.extract(image, Set.of(OcrLanguage.ENGLISH), true, () -> false));
        assertArrayEquals(before, image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()));
    }

    @Test void readsJapaneseAndKoreanGlyphs() throws Exception {
        assertEquals("こんにちは", compact(service.extract(text("こんにちは", "Yu Gothic"),
                Set.of(OcrLanguage.JAPANESE), true, () -> false)));
        assertEquals("안녕하세요", compact(service.extract(text("안녕하세요", "Malgun Gothic"),
                Set.of(OcrLanguage.KOREAN), true, () -> false)));
    }

    @Test void busyEngineIsBoundedAndCancelledWorkReleasesItsSlot() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> pending = worker.submit(() -> service.extract(text("HELLO"), Set.of(OcrLanguage.ENGLISH), true, () -> {
                entered.countDown();
                try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                return true;
            }));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertThrows(java.io.IOException.class, () -> service.extract(text("HELLO"), Set.of(OcrLanguage.ENGLISH), true, () -> false));
            release.countDown();
            assertInstanceOf(CancellationException.class, assertThrows(ExecutionException.class,
                    () -> pending.get(3, TimeUnit.SECONDS)).getCause());
            assertEquals("HELLO", service.extract(text("HELLO"), Set.of(OcrLanguage.ENGLISH), true, () -> false));
        } finally { release.countDown(); worker.shutdownNow(); }
    }

    @Test @Timeout(10) void nativeRecognitionMonitorHonoursCancellationAndNextCallRecovers() throws Exception {
        BufferedImage image = new BufferedImage(1000, 2400, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, 1000, 2400); g.setColor(Color.BLACK);
        g.setFont(new Font("Arial", Font.PLAIN, 28));
        for (int y = 40; y < 2380; y += 40) g.drawString("HELLO WORLD 12345 SAMPLE TEXT", 24, y);
        g.dispose(); AtomicBoolean stop = new AtomicBoolean();
        assertThrows(CancellationException.class, () -> service.extract(image, Set.of(OcrLanguage.ENGLISH), false, () -> {
            if (StackWalker.getInstance().walk(frames -> frames.anyMatch(frame -> frame.getClassName().equals(OcrService.class.getName())
                    && frame.getMethodName().startsWith("lambda$extractExclusive")))) stop.set(true);
            return stop.get();
        }));
        assertTrue(stop.get(), "应在原生进度回调中触发取消");
        assertEquals("HELLO", service.extract(text("HELLO"), Set.of(OcrLanguage.ENGLISH), true, () -> false));
    }

    @Test void readsSimplifiedAndTraditionalChinese() throws Exception {
        assertEquals("中文测试12345", compact(service.extract(text("中文测试 12345"),
                Set.of(OcrLanguage.SIMPLIFIED_CHINESE, OcrLanguage.ENGLISH), true, () -> false)));
        assertEquals("繁體中文12345", compact(service.extract(text("繁體中文 12345"),
                Set.of(OcrLanguage.TRADITIONAL_CHINESE), true, () -> false)));
    }

    @Test void eachOfTheFiveLanguagePacksLoadsAndExtractsDigits() throws Exception {
        for (OcrLanguage language : OcrLanguage.values()) {
            assertEquals("12345", compact(service.extract(text("12345"), Set.of(language), true, () -> false)), language.name());
        }
        assertEquals(5, OcrLanguage.values().length);
        assertEquals("12345", compact(service.extract(text("12345"), EnumSet.allOf(OcrLanguage.class), true, () -> false)));
    }

    @Test void blankAndTransparentImagesReturnNoText() throws Exception {
        BufferedImage blank = text("");
        assertEquals("", service.extract(blank, Set.of(OcrLanguage.ENGLISH), false, () -> false));
        assertEquals("", service.extract(new BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB),
                Set.of(OcrLanguage.ENGLISH), false, () -> false));
    }

    @Test void rejectsMissingLanguageAndHonoursCancellationAndInterrupt() {
        BufferedImage image = text("HELLO");
        assertThrows(IllegalArgumentException.class, () -> service.extract(image, Set.of(), false, () -> false));
        assertThrows(CancellationException.class, () -> service.extract(image, Set.of(OcrLanguage.ENGLISH), false, () -> true));
        try {
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class, () -> service.extract(image, Set.of(OcrLanguage.ENGLISH), false, () -> false));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    private static BufferedImage text(String text) {
        return text(text, "Microsoft YaHei");
    }

    private static BufferedImage text(String text, String font) {
        BufferedImage image = new BufferedImage(1000, 140, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.setColor(Color.BLACK); g.setFont(new Font(font, Font.PLAIN, 52));
        g.drawString(text, 24, 90); g.dispose();
        return image;
    }

    private static String compact(String value) { return value.replaceAll("\\s+", ""); }
}
