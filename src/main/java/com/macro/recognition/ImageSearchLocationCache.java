package com.macro.recognition;

import java.awt.Rectangle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 当前 MR 会话的搜索位置提示；仅保留小量坐标和元数据，不进入 MR、不持有截图。 */
final class ImageSearchLocationCache {
    private static final int MAX_ENTRIES = 128;
    private final Map<SearchKey, Location> matches = boundedMap();
    private final Map<TemplateStamp, Location> captures = boundedMap();

    synchronized Rectangle find(SearchKey key, List<ScreenRecognitionService.ScreenGeometry> screens) {
        // 节点参数或模板变化后不复用它此前的命中位置。
        matches.keySet().removeIf(old -> java.util.Objects.equals(old.nodeId(), key.nodeId()) && !old.equals(key));
        Location location = matches.get(key);
        if (location == null && "DESKTOP".equals(key.scopeKey())) location = captures.remove(key.template());
        if (location == null) return null;
        if (!location.screens().equals(screens)) {
            matches.remove(key);
            return null;
        }
        // 首次实际执行时把录制提示绑定到节点，之后参数变化不能再次取回旧提示。
        matches.put(key, location);
        return new Rectangle(location.bounds());
    }

    synchronized void remember(SearchKey key, Rectangle bounds,
                               List<ScreenRecognitionService.ScreenGeometry> screens) {
        matches.put(key, new Location(new Rectangle(bounds), List.copyOf(screens)));
    }

    synchronized void rememberCapture(TemplateStamp template, Rectangle bounds,
                                      List<ScreenRecognitionService.ScreenGeometry> screens) {
        if (bounds != null && !bounds.isEmpty()) {
            captures.put(template, new Location(new Rectangle(bounds), List.copyOf(screens)));
        }
    }

    synchronized void clear() {
        matches.clear();
        captures.clear();
    }

    /** 两级边距均以原命中框为基准，使用逻辑坐标；裁剪交给各显示器捕获。 */
    static Rectangle searchBounds(Rectangle match, boolean expanded) {
        double ratio = expanded ? .75 : .25;
        int minimum = expanded ? 96 : 32;
        int marginX = Math.max(minimum, (int) Math.ceil(match.width * ratio));
        int marginY = Math.max(minimum, (int) Math.ceil(match.height * ratio));
        Rectangle region = new Rectangle(match);
        region.grow(marginX, marginY);
        return region;
    }

    record SearchKey(String nodeId, TemplateStamp template, double threshold, boolean scaleMatchingEnabled, String scopeKey) {
        SearchKey(String nodeId, TemplateStamp template, double threshold, boolean scaleMatchingEnabled) {
            this(nodeId, template, threshold, scaleMatchingEnabled, "DESKTOP");
        }
    }
    record TemplateStamp(Path path, FileTime modified, long size) {
        static TemplateStamp read(Path path) throws IOException {
            Path absolute = path.toAbsolutePath().normalize();
            BasicFileAttributes attributes = Files.readAttributes(absolute, BasicFileAttributes.class);
            if (!attributes.isRegularFile()) throw new IOException("模板图片不存在或不是文件：" + absolute);
            return new TemplateStamp(absolute, attributes.lastModifiedTime(), attributes.size());
        }
    }
    private record Location(Rectangle bounds, List<ScreenRecognitionService.ScreenGeometry> screens) { }

    private static <K> Map<K, Location> boundedMap() {
        return new LinkedHashMap<>(16, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<K, Location> eldest) {
                return size() > MAX_ENTRIES;
            }
        };
    }
}
