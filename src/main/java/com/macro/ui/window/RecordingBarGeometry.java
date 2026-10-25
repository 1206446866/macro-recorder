package com.macro.ui.window;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.List;

/** 紧凑录制栏的纯几何约束，不访问桌面或配置文件。 */
public final class RecordingBarGeometry {
    /** 几何约束只提供纯计算入口，无需创建实例。 */
    private RecordingBarGeometry() { }

    /** 使用 Swing 逻辑坐标，选取相交最多或最近的可用屏幕，并完全移回工作区。 */
    public static Rectangle restore(Rectangle requested, Dimension minimum, List<Rectangle> workAreas) {
        if (minimum.width <= 0 || minimum.height <= 0 || workAreas.isEmpty()) {
            throw new IllegalArgumentException("操作栏尺寸和屏幕工作区必须有效");
        }
        List<Rectangle> usable = workAreas.stream()
                .filter(area -> area.width >= minimum.width && area.height >= minimum.height).toList();
        if (usable.isEmpty()) usable = workAreas;
        Rectangle chosen = usable.get(0);
        double bestArea = -1, bestDistance = Double.POSITIVE_INFINITY;
        for (Rectangle area : usable) {
            double overlapWidth = Math.max(0, Math.min((double) requested.x + requested.width,
                    (double) area.x + area.width) - Math.max(requested.x, area.x));
            double overlapHeight = Math.max(0, Math.min((double) requested.y + minimum.height,
                    (double) area.y + area.height) - Math.max(requested.y, area.y));
            double overlap = overlapWidth * overlapHeight;
            double dx = requested.x - Math.max(area.x, Math.min(requested.x, (double) area.x + area.width));
            double dy = requested.y - Math.max(area.y, Math.min(requested.y, (double) area.y + area.height));
            double distance = dx * dx + dy * dy;
            if (overlap > bestArea || (overlap == bestArea && distance < bestDistance)) {
                chosen = area;
                bestArea = overlap;
                bestDistance = distance;
            }
        }
        int width = Math.max(minimum.width, Math.min(requested.width, chosen.width));
        int x = (int) Math.max(chosen.x, Math.min(requested.x, (long) chosen.x + chosen.width - width));
        int y = (int) Math.max(chosen.y, Math.min(requested.y, (long) chosen.y + chosen.height - minimum.height));
        return new Rectangle(x, y, width, minimum.height);
    }

    /** Win32 WM_SIZING 使用物理像素；左右缩放保留另一侧边缘，纵向始终锁定高度。 */
    static Rectangle constrainSizing(Rectangle proposed, int edge, Dimension minimum) {
        Rectangle constrained = new Rectangle(proposed);
        int width = Math.max(minimum.width, proposed.width);
        if (edge == 1 || edge == 4 || edge == 7) constrained.x += proposed.width - width;
        if (edge == 3 || edge == 4 || edge == 5) constrained.y += proposed.height - minimum.height;
        constrained.setSize(width, minimum.height);
        return constrained;
    }

    /** 四角仅水平缩放；上下边缘不启动纵向缩放。 */
    static int horizontalHit(int nativeHit) {
        return switch (nativeHit) {
            case 13, 16 -> 10; // 左上/左下 -> 左
            case 14, 17 -> 11; // 右上/右下 -> 右
            case 12, 15 -> 1; // 顶/底 -> 客户区
            default -> nativeHit;
        };
    }
}
