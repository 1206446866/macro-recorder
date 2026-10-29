package com.macro.flow;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.concurrent.ThreadLocalRandom;

/** 点击实际命中区域，使用识别结果已经换算好的屏幕逻辑坐标。 */
public enum ImageClickMode {
    CENTER("中心点击"),
    RANDOM("范围内随机点击");

    private final String displayName;

    ImageClickMode(String displayName) { this.displayName = displayName; }

    public Point pointWithin(Rectangle bounds) {
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
            throw new IllegalArgumentException("识别命中范围无效，无法点击");
        }
        int dx = this == CENTER ? bounds.width / 2 : ThreadLocalRandom.current().nextInt(bounds.width);
        int dy = this == CENTER ? bounds.height / 2 : ThreadLocalRandom.current().nextInt(bounds.height);
        return new Point(Math.toIntExact((long) bounds.x + dx), Math.toIntExact((long) bounds.y + dy));
    }

    @Override public String toString() { return displayName; }
}
