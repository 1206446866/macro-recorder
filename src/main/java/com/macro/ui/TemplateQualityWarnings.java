package com.macro.ui;

import com.macro.recognition.TemplateQualityAnalyzer;
import com.macro.util.ImageTemplatePathUtil;

import javax.imageio.ImageIO;
import javax.swing.SwingWorker;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/** 后台读取时间线引用的模板，只缓存有上限的文本结果，不保留图像或修改流程。 */
final class TemplateQualityWarnings {
    private static final int MAX_CACHE_ENTRIES = 128;
    private final Map<Path, CachedWarning> cache = new LinkedHashMap<>();
    private SwingWorker<Void, Update> worker;
    private long generation;

    /** 在 EDT 调用；每次重建取消旧任务，迟到结果不能更新新卡片。 */
    void refresh(Map<String, String> paths, File baseDirectory, BiConsumer<String, String> onWarning) {
        long revision = ++generation;
        if (worker != null) worker.cancel(true);
        if (paths.isEmpty()) { worker = null; return; }
        Map<String, String> requests = new LinkedHashMap<>(paths);
        Map<Path, CachedWarning> snapshot = new LinkedHashMap<>(cache);
        worker = new SwingWorker<>() {
            @Override protected Void doInBackground() {
                for (var request : requests.entrySet()) {
                    if (isCancelled()) break;
                    Update update = inspect(request.getKey(), request.getValue(), baseDirectory, snapshot);
                    if (isCancelled()) break;
                    if (update.cached() != null) remember(snapshot, update.path(), update.cached());
                    publish(update);
                }
                return null;
            }

            @Override protected void process(List<Update> updates) {
                if (isCancelled() || revision != generation) return;
                for (Update update : updates) {
                    if (update.cached() != null) remember(cache, update.path(), update.cached());
                    onWarning.accept(update.nodeId(), update.tooltip());
                }
            }

            @Override protected void done() {
                if (revision == generation) worker = null;
            }
        };
        worker.execute();
    }

    private static Update inspect(String nodeId, String reference, File baseDirectory,
                                   Map<Path, CachedWarning> cached) {
        if (reference == null || reference.isBlank()) {
            return new Update(nodeId, null, null, tooltip(List.of("尚未选择模板图片，无法检查质量。")));
        }
        try {
            Path path = ImageTemplatePathUtil.resolve(reference, baseDirectory).toAbsolutePath().normalize();
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            if (!attributes.isRegularFile()) throw new IOException("不是模板文件");
            CachedWarning previous = cached.get(path);
            if (previous != null && previous.modified().equals(attributes.lastModifiedTime())
                    && previous.size() == attributes.size()) {
                return new Update(nodeId, path, previous, previous.tooltip());
            }
            BufferedImage image = ImageIO.read(path.toFile());
            if (image == null) throw new IOException("无法解码模板");
            String tooltip = tooltip(TemplateQualityAnalyzer.analyze(image));
            CachedWarning result = new CachedWarning(attributes.lastModifiedTime(), attributes.size(), tooltip);
            return new Update(nodeId, path, result, tooltip);
        } catch (IOException | RuntimeException exception) {
            return new Update(nodeId, null, null,
                    tooltip(List.of("模板图片不存在或无法读取，请检查图片位置和格式。")));
        }
    }

    private static void remember(Map<Path, CachedWarning> cache, Path path, CachedWarning warning) {
        cache.remove(path);
        cache.put(path, warning);
        while (cache.size() > MAX_CACHE_ENTRIES) cache.remove(cache.keySet().iterator().next());
    }

    private static String tooltip(List<String> warnings) {
        if (warnings.isEmpty()) return null;
        return "<html><div style='width:260px'><b>模板质量提醒</b><br>"
                + String.join("<br>", warnings)
                + "<br><br>此提示仅作提醒，不会阻止录制或保存。"
                + "</div></html>";
    }

    private record CachedWarning(FileTime modified, long size, String tooltip) { }
    private record Update(String nodeId, Path path, CachedWarning cached, String tooltip) { }
}
