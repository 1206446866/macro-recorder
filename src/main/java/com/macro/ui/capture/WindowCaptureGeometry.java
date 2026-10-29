package com.macro.ui.capture;

import java.awt.Rectangle;

/** 将屏幕上可见的物理客户区向内取整到逻辑选区，避免截入窗口边框或外部像素。 */
final class WindowCaptureGeometry {
    private WindowCaptureGeometry() { }
    static Rectangle selectableBounds(Rectangle client, Rectangle physicalScreen, Rectangle logicalScreen) {
        if (physicalScreen.width <= 0 || physicalScreen.height <= 0 || logicalScreen.width <= 0 || logicalScreen.height <= 0)
            throw new IllegalArgumentException("显示器尺寸无效");
        Rectangle visible = client.intersection(physicalScreen);
        if (visible.isEmpty()) return new Rectangle();
        int left = ceil((long) (visible.x - physicalScreen.x) * logicalScreen.width, physicalScreen.width);
        int top = ceil((long) (visible.y - physicalScreen.y) * logicalScreen.height, physicalScreen.height);
        int right = (int) ((long) (visible.x + visible.width - physicalScreen.x) * logicalScreen.width / physicalScreen.width);
        int bottom = (int) ((long) (visible.y + visible.height - physicalScreen.y) * logicalScreen.height / physicalScreen.height);
        return new Rectangle(logicalScreen.x + left, logicalScreen.y + top, Math.max(0, right-left), Math.max(0, bottom-top));
    }
    private static int ceil(long value, int divisor) { return (int) ((value + divisor - 1) / divisor); }
}
