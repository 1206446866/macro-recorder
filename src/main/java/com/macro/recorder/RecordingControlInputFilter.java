package com.macro.recorder;

import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.mouse.NativeMouseEvent;
import com.sun.jna.platform.win32.Kernel32;

import java.awt.Rectangle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** 仅排除录制操作控件和录制挂起期间的输入，不拦截系统事件或其他应用窗口。 */
public final class RecordingControlInputFilter {
    private static final int HISTORY_LIMIT = 256;
    private static final long TICK_WRAP = 1L << 32;
    private final LongSupplier ticks;
    private Supplier<Region> regionSupplier = () -> Region.EMPTY;
    private final ArrayDeque<RegionAt> regions = new ArrayDeque<>();
    private final ArrayDeque<Interval> suspensions = new ArrayDeque<>();
    private final Set<Integer> ignoredMouseButtons = new HashSet<>();
    private final Set<Integer> ignoredKeys = new HashSet<>();
    private long startedAt;
    private long suspendedAt = -1;

    public RecordingControlInputFilter() {
        this(Kernel32.INSTANCE::GetTickCount64);
    }

    /** 测试可注入与原生事件同单位的单调时钟，不注册全局钩子。 */
    RecordingControlInputFilter(LongSupplier ticks) {
        this.ticks = ticks;
    }

    /** 提供物理屏幕控件区域及其键盘焦点状态，回调中禁止访问 Swing 组件。 */
    public synchronized void setRegionSupplier(Supplier<Region> supplier) {
        regionSupplier = supplier;
        refreshRegion();
    }

    public synchronized void reset() {
        suspensions.clear();
        ignoredMouseButtons.clear();
        ignoredKeys.clear();
        suspendedAt = -1;
        startedAt = ticks.getAsLong();
        // 启动按钮释放与窗口切换可能处于同一原生毫秒，保留此前最后的有效边界。
        // 更早的事件仍由 startedAt 拦截，旧录制会话的其他历史不再参与判断。
        while (regions.size() > 1) {
            var iterator = regions.iterator();
            iterator.next();
            if (iterator.next().since >= startedAt) break;
            regions.removeFirst();
        }
        refreshRegion();
    }

    /** 在窗口显隐和最小化切换后立即留存边界，识别已排队的旧输入。 */
    public synchronized void refreshRegion() {
        Region region = regionSupplier.get();
        if (region == null) region = Region.EMPTY;
        if (regions.isEmpty() || !regions.getLast().region.equals(region)) {
            regions.addLast(new RegionAt(ticks.getAsLong(), region));
            while (regions.size() > HISTORY_LIMIT) regions.removeFirst();
        }
    }

    public synchronized void beginSuspension() {
        if (suspendedAt < 0) suspendedAt = ticks.getAsLong();
    }

    public synchronized void endSuspension() {
        if (suspendedAt < 0) return;
        suspensions.addLast(new Interval(suspendedAt, ticks.getAsLong()));
        suspendedAt = -1;
        while (suspensions.size() > HISTORY_LIMIT) suspensions.removeFirst();
    }

    public synchronized boolean ignoreMousePress(NativeMouseEvent event) {
        boolean ignore = ignoredMouseButtons.contains(event.getButton())
                || blocked(event.getWhen()) || inControlRegion(event.getWhen(), region -> region.contains(event));
        if (ignore) ignoredMouseButtons.add(event.getButton());
        return ignore;
    }

    public synchronized boolean ignoreMouseRelease(NativeMouseEvent event, boolean recordedPress) {
        if (ignoredMouseButtons.remove(event.getButton())) return true;
        // 在外部按下、在控制栏上松开时，保留已录入按下动作的配对释放。
        if (recordedPress) return false;
        return blocked(event.getWhen()) || inControlRegion(event.getWhen(), region -> region.contains(event));
    }

    public synchronized boolean ignoreMouseMotion(NativeMouseEvent event) {
        return !ignoredMouseButtons.isEmpty() || blocked(event.getWhen())
                || inControlRegion(event.getWhen(), region -> region.contains(event));
    }

    public synchronized boolean ignoreKeyPress(NativeKeyEvent event, boolean recordedPress) {
        int key = keyId(event);
        boolean ignore = ignoredKeys.contains(key) || blocked(event.getWhen())
                || inControlRegion(event.getWhen(), region -> region.keyboardFocused);
        if (ignore && !recordedPress) ignoredKeys.add(key);
        return ignore;
    }

    public synchronized boolean ignoreKeyRelease(NativeKeyEvent event, boolean recordedPress) {
        if (ignoredKeys.remove(keyId(event))) return true;
        if (recordedPress) return false;
        return blocked(event.getWhen()) || inControlRegion(event.getWhen(), region -> region.keyboardFocused);
    }

    public synchronized void suppressMouseUntilRelease(int button) { ignoredMouseButtons.add(button); }
    public synchronized void suppressKeyUntilRelease(int key) { ignoredKeys.add(key); }

    /** 截图桥接不得以控制栏按钮坐标作为宏轨迹的起点或终点。 */
    public synchronized boolean containsCurrentPoint(double x, double y) {
        Region region = regionSupplier.get();
        return region != null && region.contains(x, y);
    }

    static int keyId(NativeKeyEvent event) {
        return (event.getKeyCode() << 3) | event.getKeyLocation();
    }

    private boolean blocked(long when) {
        long time = eventTime(when);
        if (time < startedAt || (suspendedAt >= 0 && time >= suspendedAt)) return true;
        for (Interval interval : suspensions) {
            if (time >= interval.start && time < interval.end) return true;
        }
        return false;
    }

    private boolean inControlRegion(long when, Predicate<Region> matches) {
        refreshRegion();
        long time = eventTime(when);
        Region region = Region.EMPTY;
        for (RegionAt entry : regions) {
            if (entry.since > time) break;
            // Win32 毫秒计数存在量化；显隐与点击同一时刻时，同时保留前一边界。
            if (entry.since == time && matches.test(region)) return true;
            region = entry.region;
        }
        return matches.test(region);
    }

    /** Windows 钩子时间为开机毫秒低 32 位；展开回绕，不能当成日历时间。 */
    private long eventTime(long when) {
        long now = ticks.getAsLong();
        if (when == 0) return now; // 手工构造的 NativeInputEvent 没有原生时间戳。
        long value = (now & ~(TICK_WRAP - 1)) | (when & (TICK_WRAP - 1));
        if (value > now + 1000) value -= TICK_WRAP;
        return value;
    }

    /**
     * 坐标统一为 Win32 物理像素；主页传入分离的按钮区域，紧凑模式可传入完整窗口区域。
     * 键盘焦点仅表示这些操作控件获得焦点，不等同于整个主窗口处于前台。
     */
    public record Region(List<Rectangle> areas, boolean keyboardFocused) {
        public static final Region EMPTY = new Region(List.of(), false);

        public Region {
            Objects.requireNonNull(areas, "录制控件区域不能为空");
            List<Rectangle> copies = new ArrayList<>(areas.size());
            for (Rectangle area : areas) {
                Rectangle copy = new Rectangle(Objects.requireNonNull(area, "录制控件矩形不能为空"));
                if (!copy.isEmpty()) copies.add(copy);
            }
            areas = List.copyOf(copies);
        }

        /** 保留现有紧凑控制栏及测试代码的单矩形调用方式。 */
        public Region(Rectangle bounds, boolean keyboardFocused) {
            this(List.of(bounds), keyboardFocused);
        }

        /** 防止调用方修改已进入原生输入历史的控件边界。 */
        @Override public List<Rectangle> areas() {
            return areas.stream().map(Rectangle::new).toList();
        }

        /** 兼容需要整体外接矩形的调用方；鼠标过滤始终逐个判断实际控件区域。 */
        public Rectangle bounds() {
            Rectangle bounds = null;
            for (Rectangle area : areas) bounds = bounds == null ? new Rectangle(area) : bounds.union(area);
            return bounds == null ? new Rectangle() : bounds;
        }

        private boolean contains(NativeMouseEvent event) { return contains(event.getX(), event.getY()); }

        private boolean contains(double x, double y) {
            for (Rectangle area : areas) if (area.contains(x, y)) return true;
            return false;
        }
    }

    private record RegionAt(long since, Region region) { }
    private record Interval(long start, long end) { }
}
