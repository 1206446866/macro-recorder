package com.macro.recognition;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class TemplateQualityAnalyzerTest {
    @Test void tinyAndAlmostUniformTemplatesExplainTheirRisks() {
        assertTrue(TemplateQualityAnalyzer.analyze(noise(8, 8, 255)).stream().anyMatch(s -> s.contains("尺寸较小")));
        BufferedImage solid = new BufferedImage(40, 32, BufferedImage.TYPE_INT_RGB);
        var warnings = TemplateQualityAnalyzer.analyze(solid);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("颜色几乎一致"));
    }

    @Test void smoothLowTextureAndRepeatedTilesAreAdvisory() {
        BufferedImage smooth = new BufferedImage(80, 80, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 80; y++) for (int x = 0; x < 80; x++) smooth.setRGB(x, y, x * 3 * 0x010101);
        assertTrue(TemplateQualityAnalyzer.analyze(smooth).stream().anyMatch(s -> s.contains("纹理较少")));
        BufferedImage tiles = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) {
            tiles.setRGB(x, y, (x / 2 + y / 2) % 2 == 0 ? 0 : 0xFFFFFF);
        }
        assertTrue(TemplateQualityAnalyzer.analyze(tiles).stream().anyMatch(s -> s.contains("重复纹理")));
    }

    @Test void variedOpaqueAndSemitransparentImagesHaveNoWarnings() {
        assertTrue(TemplateQualityAnalyzer.analyze(noise(64, 48, 255)).isEmpty());
        assertTrue(TemplateQualityAnalyzer.analyze(noise(64, 48, 127)).isEmpty(), "保留全部半透明模板的有效像素规则");
    }

    @Test void invisibleColorsDoNotHideSparseOrTransparentTemplates() {
        BufferedImage transparent = noise(64, 48, 0);
        assertTrue(TemplateQualityAnalyzer.analyze(transparent).stream().anyMatch(s -> s.contains("完全透明")));
        BufferedImage sparse = noise(64, 48, 219);
        sparse.setRGB(30, 20, 0xFFFFFFFF);
        var warnings = TemplateQualityAnalyzer.analyze(sparse);
        assertTrue(warnings.stream().anyMatch(s -> s.contains("1 个")), "出现不透明像素后应与匹配器一样忽略低于220的像素");
        assertTrue(warnings.stream().anyMatch(s -> s.contains("颜色几乎一致")));
    }

    @Test void cancelledAnalysisPreservesInterruptAndLeavesInputUnchanged() {
        BufferedImage image = noise(40, 40, 255);
        int[] before = image.getRGB(0, 0, 40, 40, null, 0, 40);
        Thread.currentThread().interrupt();
        try {
            assertTrue(TemplateQualityAnalyzer.analyze(image).isEmpty());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        assertArrayEquals(before, image.getRGB(0, 0, 40, 40, null, 0, 40));
    }

    private static BufferedImage noise(int width, int height, int alpha) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Random random = new Random(1729);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            image.setRGB(x, y, alpha << 24 | random.nextInt(0x1000000));
        }
        return image;
    }
}
