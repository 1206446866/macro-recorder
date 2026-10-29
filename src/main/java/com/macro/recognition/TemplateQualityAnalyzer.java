package com.macro.recognition;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/** 模板质量的启发式提醒；不改变匹配阈值，也不阻止录制或播放。 */
public final class TemplateQualityAnalyzer {
    private static final int MIN_SIDE = 12;
    private static final int MIN_VISIBLE_PIXELS = 256;
    private static final int REPEAT_PROBES = 48;

    private TemplateQualityAnalyzer() { }

    /** 使用与匹配器一致的透明度规则分析图片，只返回提醒，不持有模板像素。 */
    public static List<String> analyze(BufferedImage image) {
        List<String> warnings = new ArrayList<>();
        int width = image.getWidth();
        int height = image.getHeight();
        if (width < MIN_SIDE || height < MIN_SIDE) {
            warnings.add("模板尺寸较小（" + width + " × " + height + "），容易匹配到相似位置。");
        }
        Stats opaque = new Stats(220);
        Stats visible = new Stats(1);
        int[] previous = new int[width];
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            if (Thread.currentThread().isInterrupted()) return List.of();
            image.getRGB(0, y, width, 1, row, 0, width);
            for (int x = 0; x < width; x++) {
                opaque.pixel(row[x]);
                visible.pixel(row[x]);
                if (x > 0) {
                    opaque.edge(row[x - 1], row[x]);
                    visible.edge(row[x - 1], row[x]);
                }
                if (y > 0) {
                    opaque.edge(previous[x], row[x]);
                    visible.edge(previous[x], row[x]);
                }
            }
            int[] swap = previous;
            previous = row;
            row = swap;
        }
        Stats stats = opaque.count > 0 ? opaque : visible;
        if (stats.count == 0) {
            warnings.add("模板完全透明，没有可用于匹配的像素。");
            return List.copyOf(warnings);
        }
        if (stats.count < MIN_VISIBLE_PIXELS) {
            warnings.add("可用于匹配的像素较少（" + stats.count + " 个），建议截取更多有辨识度的内容。");
        }
        double variance = stats.variance();
        double edges = stats.pairs == 0 ? 0 : (double) stats.edges / stats.pairs;
        if (variance < 1.0) {
            warnings.add("模板颜色几乎一致，大片同色背景也可能命中；建议包含图标、文字或边框。");
        } else if (variance < 64.0 || edges < .005) {
            warnings.add("模板纹理较少，容易与背景或相似控件混淆；建议包含稳定的图标、文字或边框。");
        } else if (hasRepeatingPattern(image, stats.alphaThreshold)) {
            warnings.add("模板含有明显重复纹理，可能匹配多个位置；建议包含附近独特且稳定的内容。");
        }
        return List.copyOf(warnings);
    }

    /** 只提示明显的短周期纹理，不能据此判断模板在真实屏幕中是否唯一。 */
    private static boolean hasRepeatingPattern(BufferedImage image, int alphaThreshold) {
        for (boolean horizontal : new boolean[]{true, false}) {
            int length = horizontal ? image.getWidth() : image.getHeight();
            for (int period = 2; period <= Math.min(16, length / 3); period++) {
                if (Thread.currentThread().isInterrupted()) return false;
                int spanX = image.getWidth() - (horizontal ? period : 0);
                int spanY = image.getHeight() - (horizontal ? 0 : period);
                int columns = Math.min(REPEAT_PROBES, spanX);
                int rows = Math.min(REPEAT_PROBES, spanY);
                int pairs = 0, same = 0, changes = 0;
                for (int row = 0; row < rows; row++) for (int column = 0; column < columns; column++) {
                    int x = (int) ((long) column * spanX / columns);
                    int y = (int) ((long) row * spanY / rows);
                    int first = image.getRGB(x, y);
                    int shifted = image.getRGB(x + (horizontal ? period : 0), y + (horizontal ? 0 : period));
                    int adjacent = image.getRGB(x + (horizontal ? 1 : 0), y + (horizontal ? 0 : 1));
                    // 透明轮廓也必须周期一致，避免把孤立图标的透明背景当成重复纹理。
                    if ((first >>> 24) < alphaThreshold || (shifted >>> 24) < alphaThreshold
                            || (adjacent >>> 24) < alphaThreshold) {
                        // 此探针不参与颜色比较；轮廓不一致计为不匹配。
                        if ((first >>> 24) >= alphaThreshold || (shifted >>> 24) >= alphaThreshold) pairs++;
                        continue;
                    }
                    pairs++;
                    if (colorDistance(first, shifted) <= 2) same++;
                    if (colorDistance(first, adjacent) >= 16) changes++;
                }
                if (pairs >= 64 && same >= pairs * .98 && changes >= pairs * .05) return true;
            }
        }
        return false;
    }

    private static int colorDistance(int first, int second) {
        return Math.max(Math.abs((first >> 16 & 255) - (second >> 16 & 255)),
                Math.max(Math.abs((first >> 8 & 255) - (second >> 8 & 255)),
                        Math.abs((first & 255) - (second & 255))));
    }

    private static final class Stats {
        private final int alphaThreshold;
        private long count, red, green, blue, squares, pairs, edges;

        private Stats(int alphaThreshold) { this.alphaThreshold = alphaThreshold; }

        private void pixel(int argb) {
            if ((argb >>> 24) < alphaThreshold) return;
            int r = argb >> 16 & 255, g = argb >> 8 & 255, b = argb & 255;
            count++;
            red += r; green += g; blue += b;
            squares += r * r + g * g + b * b;
        }

        private void edge(int first, int second) {
            if ((first >>> 24) < alphaThreshold || (second >>> 24) < alphaThreshold) return;
            pairs++;
            if (colorDistance(first, second) >= 16) edges++;
        }

        private double variance() {
            double r = (double) red / count, g = (double) green / count, b = (double) blue / count;
            return Math.max(0, ((double) squares / count - r * r - g * g - b * b) / 3);
        }
    }
}
