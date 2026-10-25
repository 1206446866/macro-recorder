package com.macro.flow;

import lombok.Getter;

import java.awt.Rectangle;

/** 图像匹配扩展点返回的状态与匹配区域。 */
public final class ImageMatchResult {

    /** 是否达到阈值的匹配状态。
     * -- GETTER --
     * 返回达到阈值后的匹配状态。
     */
    @Getter
    private final ImageMatchStatus status;

    /** 最佳候选相似度，取值范围为 0 到 1。
     * -- GETTER --
     * 返回最佳候选相似度。
     */
    @Getter
    private final double similarity;

    /** 命中区域的屏幕逻辑坐标；未命中时为空。 */
    private final Rectangle matchBounds;

    private ImageMatchResult(
            ImageMatchStatus status,
            double similarity,
            Rectangle matchBounds
    ) {
        if (status == null) {
            throw new IllegalArgumentException("图像匹配状态不能为空");
        }
        if (status == ImageMatchStatus.FOUND && matchBounds == null) {
            throw new IllegalArgumentException("找到图像时必须提供匹配区域");
        }
        if (!Double.isFinite(similarity) || similarity < 0.0 || similarity > 1.0) {
            throw new IllegalArgumentException("图像相似度必须位于 0 到 1 之间");
        }
        this.status = status;
        this.similarity = similarity;
        this.matchBounds = matchBounds == null
                ? null
                : new Rectangle(matchBounds);
    }

    /** 创建相似度为 1 的命中结果。 */
    public static ImageMatchResult found(Rectangle matchBounds) {
        return found(1.0, matchBounds);
    }

    /** 创建包含实际相似度和命中区域的结果。 */
    public static ImageMatchResult found(
            double similarity,
            Rectangle matchBounds
    ) {
        return new ImageMatchResult(
                ImageMatchStatus.FOUND,
                similarity,
                matchBounds
        );
    }

    /** 创建没有最佳候选信息的未命中结果。 */
    public static ImageMatchResult notFound() {
        return notFound(0.0);
    }

    /** 创建保留最佳候选相似度的未命中结果。 */
    public static ImageMatchResult notFound(double bestSimilarity) {
        return new ImageMatchResult(
                ImageMatchStatus.NOT_FOUND,
                bestSimilarity,
                null
        );
    }

    /** 判断匹配结果是否达到阈值。 */
    public boolean isFound() {
        return status == ImageMatchStatus.FOUND;
    }

    /** 返回命中区域副本，防止调用方修改内部坐标。 */
    public Rectangle getMatchBounds() {
        return matchBounds == null ? null : new Rectangle(matchBounds);
    }
}
