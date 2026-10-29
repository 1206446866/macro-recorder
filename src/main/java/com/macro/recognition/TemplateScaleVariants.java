package com.macro.recognition;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

/** 为单个不可变模板提供有限比例及有容量上限的缩放缓存，不保存屏幕图像或原生 Mat。 */
final class TemplateScaleVariants {
    static final double MIN_SCALE = 2.0 / 3.0;
    static final double MAX_SCALE = 1.5;
    static final double[] COARSE_SCALES = {0.9, 1.1, 5.0 / 6.0, 1.2, 0.8, 1.25,
            0.75, 4.0 / 3.0, MIN_SCALE, MAX_SCALE};
    private static final long MAX_CACHE_BYTES = 32L * 1024 * 1024;
    private final Map<Long, BufferedImage> cache = new LinkedHashMap<>(16, .75f, true);
    private BufferedImage original;
    private long cachedBytes;

    /** 返回候选附近的四个细查比例；调用方仍需过滤范围、重复尺寸和屏幕边界。 */
    static double[] fineScales(double bestScale) {
        return new double[]{bestScale - .025, bestScale + .025, bestScale - .05, bestScale + .05};
    }

    /** 按实际像素尺寸复用缩放结果；更换模板时立即丢弃旧缓存。 */
    synchronized BufferedImage image(BufferedImage template, int width, int height) {
        prepare(template);
        long key = sizeKey(width, height);
        BufferedImage existing = cache.get(key);
        if (existing != null) return existing;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(template, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        long bytes = (long) width * height * Integer.BYTES;
        if (bytes <= MAX_CACHE_BYTES) {
            var iterator = cache.entrySet().iterator();
            while (cachedBytes + bytes > MAX_CACHE_BYTES && iterator.hasNext()) {
                BufferedImage oldest = iterator.next().getValue();
                cachedBytes -= (long) oldest.getWidth() * oldest.getHeight() * Integer.BYTES;
                iterator.remove();
            }
            cache.put(key, image);
            cachedBytes += bytes;
        }
        return image;
    }

    /** 在原尺寸搜索开始时也更新模板身份，避免旧模板及其缓存长期驻留。 */
    synchronized void prepare(BufferedImage template) {
        if (original != template) {
            cache.clear();
            cachedBytes = 0;
            original = template;
        }
    }

    /** 返回缓存像素的估算占用，不包含对象头和原模板。 */
    synchronized long cachedBytes() {
        return cachedBytes;
    }

    /** 将宽高组合成稳定键，按实际尺寸跳过比例舍入后相同的模板。 */
    static long sizeKey(int width, int height) {
        return ((long) width << 32) | (height & 0xFFFFFFFFL);
    }
}
