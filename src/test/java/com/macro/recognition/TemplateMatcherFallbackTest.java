package com.macro.recognition;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Random;

/** 用合成图像验证 Java 回退结果契约；不访问桌面、文件或键鼠输入。 */
public final class TemplateMatcherFallbackTest {
    private static final TemplateMatcher MATCHER = new TemplateMatcher();

    public static void main(String[] args) {
        fallbackAtKnownPosition();
        thresholdIsUnchanged();
        noMatch();
        rankingAndLimit();
        normalAndFallbackDeduplicate();
        System.out.println("通过：64/100/127回退命中、偏移坐标、阈值边界、未匹配/无候选、最佳选择、排序、截断及普通/回退去重");
    }

    private static void fallbackAtKnownPosition() {
        for (int size : new int[]{64, 100, 127}) {
            BufferedImage template = noise(size, 123);
            BufferedImage source = image(size + 3, size + 4);
            copy(fallbackPatch(template, 0), source, 2, 3);
            var result = MATCHER.findAll(source, template, 0.9, 100);
            Rectangle expected = new Rectangle(2, 3, size, size);
            check(result.bestCandidate() != null && result.bestCandidate().matched()
                    && result.bestCandidate().pixelBounds().equals(expected), "回退候选评分或位置改变");
            check(result.matches().size() == 1 && result.matches().get(0).equals(result.bestCandidate()),
                    "已达标的回退候选未加入结果列表：size=" + size + ", " + result);
            check(!result.truncated(), "单个回退结果错误标为截断");
        }
    }

    private static void thresholdIsUnchanged() {
        BufferedImage template = noise(64, 123);
        BufferedImage source = fallbackPatch(template, 0);
        var candidate = MATCHER.findAll(source, template, 0.9, 10).bestCandidate();
        double score = candidate.similarity();
        check(score > 0.9 && score < 1.0, "测试素材未形成近似回退候选");
        var accepted = MATCHER.findAll(source, template, score - 1e-6, 10);
        var rejected = MATCHER.findAll(source, template, score + 1e-6, 10);
        check(accepted.matches().size() == 1 && accepted.bestCandidate().similarity() == score,
                "低于原评分的阈值应接受回退候选");
        check(rejected.matches().isEmpty() && !rejected.bestCandidate().matched()
                && rejected.bestCandidate().similarity() == score, "回退放宽了阈值或改变了评分");
    }

    private static void noMatch() {
        BufferedImage black = image(8, 8);
        BufferedImage white = image(8, 8);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) white.setRGB(x, y, 0xFFFFFF);
        }
        var rejected = MATCHER.findAll(white, black, 0.9, 10);
        check(rejected.matches().isEmpty() && rejected.bestCandidate() != null
                && !rejected.bestCandidate().matched(), "未达标候选被加入结果列表");
        var transparent = MATCHER.findAll(white,
                new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB), 0.9, 10);
        check(transparent.matches().isEmpty() && transparent.bestCandidate() == null,
                "无有效采样点时未正确返回无候选");
    }

    private static void rankingAndLimit() {
        int size = 64;
        BufferedImage template = noise(size, 123);
        BufferedImage source = image(3 * size + 8, size);
        copy(fallbackPatch(template, 128), source, 0, 0);
        copy(fallbackPatch(template, 64), source, size + 4, 0);
        copy(fallbackPatch(template, 0), source, 2 * (size + 4), 0);
        var all = MATCHER.findAll(source, template, 0.9, 10);
        List<Rectangle> expected = List.of(new Rectangle(136, 0, size, size),
                new Rectangle(68, 0, size, size), new Rectangle(0, 0, size, size));
        check(all.matches().stream().map(TemplateMatcher.MatchResult::pixelBounds).toList().equals(expected),
                "多个回退结果位置或评分排序改变：" + all);
        check(all.bestCandidate().equals(all.matches().get(0)) && !all.truncated(),
                "最佳回退候选未参与最终结果选择");
        var limited = MATCHER.findAll(source, template, 0.9, 1);
        check(limited.matches().equals(List.of(all.bestCandidate())) && limited.truncated(),
                "达到上限后未保留最高分回退候选");
        check(MATCHER.findBest(source, template, 0.9).equals(all.bestCandidate()), "findBest选择规则改变");
    }

    private static void normalAndFallbackDeduplicate() {
        BufferedImage template = noise(8, 5);
        BufferedImage source = image(30, 8);
        copy(template, source, 0, 0);
        copy(template, source, 20, 0);
        var result = MATCHER.findAll(source, template, 0.99, 10);
        check(result.matches().size() == 2 && !result.truncated(), "普通扫描与回退重复计入同一位置");
        check(result.matches().stream().map(TemplateMatcher.MatchResult::pixelBounds).toList()
                .equals(List.of(new Rectangle(0, 0, 8, 8), new Rectangle(20, 0, 8, 8))),
                "正常候选位置或同分顺序改变");
    }

    /** 沿用审查素材：反转中层网格点，使中层筛选失败，但细节评分仍达标。 */
    private static BufferedImage fallbackPatch(BufferedImage template, int extraDamage) {
        int size = template.getWidth();
        BufferedImage patch = image(size, size);
        copy(template, patch, 0, 0);
        int step = (int) Math.ceil(Math.sqrt(size * size / 256.0));
        for (int y = 0; y < size; y += step) {
            for (int x = 0; x < size; x += step) patch.setRGB(x, y, template.getRGB(x, y) ^ 0xFFFFFF);
        }
        // 额外破坏不位于中层网格行的点，产生可区分的细节评分。
        for (int i = 0; i < extraDamage; i++) {
            int x = 1 + i % (size - 1);
            int y = 1 + i / (size - 1);
            patch.setRGB(x, y, template.getRGB(x, y) ^ 0xFFFFFF);
        }
        return patch;
    }

    private static BufferedImage image(int width, int height) {
        return new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    }

    private static BufferedImage noise(int size, int seed) {
        BufferedImage result = image(size, size);
        Random random = new Random(seed);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) result.setRGB(x, y, random.nextInt(0x1000000));
        }
        return result;
    }

    private static void copy(BufferedImage patch, BufferedImage source, int x, int y) {
        source.setRGB(x, y, patch.getWidth(), patch.getHeight(),
                patch.getRGB(0, 0, patch.getWidth(), patch.getHeight(), null, 0, patch.getWidth()),
                0, patch.getWidth());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
