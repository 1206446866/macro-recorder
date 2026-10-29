package com.macro.recognition;

import com.macro.model.TargetWindow;
import com.macro.ui.capture.CaptureWindowService;
import java.awt.Rectangle;
import java.io.IOException;
import java.util.*;

/** 每次实际识别读取当前客户区，不激活窗口；同一会话绑定后不悄悄切换到其他窗口。 */
final class WindowRecognitionScope {
    private final CaptureWindowService windows;
    private final Map<Selector, CaptureWindowService.Target> bindings = new LinkedHashMap<>(16, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Selector, CaptureWindowService.Target> entry) { return size() > 128; }
    };
    WindowRecognitionScope(CaptureWindowService windows) { this.windows = windows; }
    synchronized void clear() { bindings.clear(); }

    Area resolve(TargetWindow description, List<ScreenRecognitionService.ScreenGeometry> screens) throws IOException {
        Selector selector = new Selector(description.getProcessName().toLowerCase(Locale.ROOT),
                description.getWindowClass(), Objects.toString(description.getWindowTitle(), ""));
        CaptureWindowService.Target target;
        synchronized (this) {
            target = bindings.get(selector);
            if (target == null) {
                List<CaptureWindowService.Target> candidates = windows.listWindows().stream()
                        .filter(item -> selector.process().equalsIgnoreCase(item.processName()) && selector.windowClass().equals(item.windowClass())).toList();
                List<CaptureWindowService.Target> exact = candidates.stream().filter(item -> selector.title().equals(item.title())).toList();
                List<CaptureWindowService.Target> preferred = exact.isEmpty() ? candidates : exact;
                if (preferred.isEmpty()) throw new IOException("未找到识别窗口：" + description.getProcessName() + " · " + selector.title());
                if (preferred.size() != 1) throw new IOException("存在多个相同的识别窗口，无法唯一定位；请关闭多余窗口或使用不同标题后重试");
                target = preferred.get(0); bindings.put(selector, target);
            }
        }
        Rectangle physicalClient = windows.clientBounds(target);
        List<ScreenRecognitionService.ScreenGeometry> limited = new ArrayList<>();
        Map<String, Mapping> mappings = new HashMap<>();
        for (var screen : screens) {
            Rectangle physicalMonitor = windows.monitorBounds(screen.bounds());
            mappings.put(screen.id(), new Mapping(screen, physicalMonitor));
            Rectangle visible = physicalClient.intersection(physicalMonitor);
            if (visible.isEmpty()) continue;
            Rectangle bounds = logicalBounds(visible, physicalMonitor, screen);
            if (!bounds.isEmpty()) limited.add(new ScreenRecognitionService.ScreenGeometry(screen.id(), bounds,
                    screen.scaleX(), screen.scaleY(), screen.displayName()));
        }
        if (limited.isEmpty()) throw new IOException("识别窗口没有可见的屏幕内容区");
        return new Area(target, physicalClient, List.copyOf(limited), Map.copyOf(mappings));
    }

    /** 使用 Robot 的每屏缩放变换，向内取整；每个捕获范围均位于物理客户区内。 */
    static Rectangle logicalBounds(Rectangle visible, Rectangle physicalMonitor, ScreenRecognitionService.ScreenGeometry screen) {
        Rectangle logical = screen.bounds();
        int left = logical.x + (int)Math.ceil((visible.x - physicalMonitor.x) / screen.scaleX());
        int top = logical.y + (int)Math.ceil((visible.y - physicalMonitor.y) / screen.scaleY());
        int right = logical.x + (int)Math.floor((visible.x + visible.width - physicalMonitor.x) / screen.scaleX());
        int bottom = logical.y + (int)Math.floor((visible.y + visible.height - physicalMonitor.y) / screen.scaleY());
        return new Rectangle(left, top, Math.max(0, right-left), Math.max(0, bottom-top)).intersection(logical);
    }

    final class Area {
        private final CaptureWindowService.Target target;
        private final Rectangle physicalClient;
        private final List<ScreenRecognitionService.ScreenGeometry> screens;
        private final Map<String, Mapping> mappings;
        private Area(CaptureWindowService.Target target, Rectangle client, List<ScreenRecognitionService.ScreenGeometry> screens,
                     Map<String, Mapping> mappings) {
            this.target = target; physicalClient = new Rectangle(client); this.screens = screens; this.mappings = mappings;
        }
        List<ScreenRecognitionService.ScreenGeometry> screens() { return screens; }
        String key() { return "WINDOW:" + target.handle() + ":" + target.processId(); }
        List<ScreenRecognitionService.ScreenGeometry> captureRegions(Rectangle region) {
            List<ScreenRecognitionService.ScreenGeometry> result = new ArrayList<>();
            for (var screen : screens) {
                Rectangle requested = region == null ? screen.bounds() : screen.bounds().intersection(region);
                if (requested.isEmpty()) continue;
                Mapping mapping = mappings.get(screen.id());
                Rectangle safe = safeCaptureBounds(requested, mapping.screen(), mapping.physical(), physicalClient.intersection(mapping.physical()));
                if (!safe.isEmpty()) result.add(new ScreenRecognitionService.ScreenGeometry(screen.id(), safe,
                        screen.scaleX(), screen.scaleY(), screen.displayName()));
            }
            return result;
        }
        void verify() throws IOException {
            if (!physicalClient.equals(windows.clientBounds(target))) {
                throw new IOException("识别期间目标窗口位置或大小发生变化，请保持窗口稳定后重试");
            }
        }
    }
    /** Robot 分别舍入原点和宽高，局扫起点改变时也要重新检查物理右/下边界。 */
    static Rectangle safeCaptureBounds(Rectangle requested, ScreenRecognitionService.ScreenGeometry screen,
                                       Rectangle monitor, Rectangle client) {
        Rectangle result = new Rectangle(requested);
        while (!result.isEmpty()) {
            int x = monitor.x + (int)Math.round((result.x-screen.bounds().x)*screen.scaleX());
            int y = monitor.y + (int)Math.round((result.y-screen.bounds().y)*screen.scaleY());
            int width = (int)Math.round(result.width*screen.scaleX());
            int height = (int)Math.round(result.height*screen.scaleY());
            if (x < client.x) { result.x++; result.width--; continue; }
            if (y < client.y) { result.y++; result.height--; continue; }
            if ((long)x + width > (long)client.x + client.width) { result.width--; continue; }
            if ((long)y + height > (long)client.y + client.height) { result.height--; continue; }
            return result;
        }
        return new Rectangle();
    }
    private record Mapping(ScreenRecognitionService.ScreenGeometry screen, Rectangle physical) { }
    private record Selector(String process, String windowClass, String title) { }
}
