package com.macro.ui.window;

import com.macro.ui.theme.ThemeManager;
import com.sun.jna.CallbackReference;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.HDC;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.LRESULT;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 将录制控制行放入 DWM 混合标题栏，系统仍负责边框及最小化、最大化、关闭按钮。
 * 原生消息线程只读取布局快照；Swing 组件及业务回调始终由事件派发线程处理。
 */
public final class WindowsRecordingTitleBar {
    private static final int GWLP_WNDPROC = -4;
    private static final int WM_SIZE = 0x0005;
    private static final int WM_ACTIVATE = 0x0006;
    private static final int WM_WINDOWPOSCHANGED = 0x0047;
    private static final int WM_NCCALCSIZE = 0x0083;
    private static final int WM_NCHITTEST = 0x0084;
    private static final int WM_NCDESTROY = 0x0082;
    private static final int WM_NCMOUSELEAVE = 0x02A2;
    private static final int WM_DPICHANGED = 0x02E0;
    private static final int WM_DWMCOMPOSITIONCHANGED = 0x031E;
    private static final int WM_GETTITLEBARINFOEX = 0x033F;
    private static final int HTCLIENT = 1;
    private static final int HTCAPTION = 2;
    private static final int HTMINBUTTON = 8;
    private static final int HTMAXBUTTON = 9;
    private static final int HTCLOSE = 20;
    private static final int HTTOP = 12;
    private static final int HTTOPLEFT = 13;
    private static final int HTTOPRIGHT = 14;
    private static final int SM_CYCAPTION = 4;
    private static final int SM_CYSIZEFRAME = 33;
    private static final int SM_CXSIZEFRAME = 32;
    private static final int SM_CXPADDEDBORDER = 92;
    private static final int DWMWA_CAPTION_BUTTON_BOUNDS = 5;
    private static final int DWMWA_CAPTION_COLOR = 35;
    private static final int DWMWA_TEXT_COLOR = 36;
    private static final int FRAME_CHANGE_FLAGS = 0x0001 | 0x0002 | 0x0004 | 0x0010 | 0x0020;

    /** 只要窗口过程仍可能回调本类，就持有强引用，避免原生函数指针指向已回收对象。 */
    private static final Set<WindowsRecordingTitleBar> INSTALLED = ConcurrentHashMap.newKeySet();

    private final JFrame frame;
    private final JComponent titleBar;
    private final List<? extends AbstractButton> interactiveComponents;
    private final WinUser.WindowProc windowProc = this::handleMessage;
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private final AtomicBoolean marginsRefreshRequested = new AtomicBoolean();
    private final AtomicBoolean clearQueued = new AtomicBoolean();
    private volatile LayoutSnapshot layout = LayoutSnapshot.EMPTY;
    private volatile Rectangle systemButtonBounds = new Rectangle();
    private volatile int captionHeight;
    private volatile boolean active;
    private volatile HWND hwnd;
    private volatile Pointer originalProc;
    private Pointer installedProc;
    private Border originalBorder;
    private Dimension originalPreferredSize;
    private int reservedWidth = -1;
    private int appliedCaptionHeight = -1;
    private int appliedDpi;
    private boolean appliedMaximized;
    private int originalCaptionColor = -1;
    private int originalTextColor = -1;
    private int appliedCaptionColor;
    private int appliedTextColor;
    private boolean captionColorsApplied;

    public WindowsRecordingTitleBar(JFrame frame, JComponent titleBar,
                                    List<? extends AbstractButton> interactiveComponents) {
        this.frame = Objects.requireNonNull(frame, "主窗口不能为空");
        this.titleBar = Objects.requireNonNull(titleBar, "标题栏不能为空");
        this.interactiveComponents = List.copyOf(interactiveComponents);
    }

    /** 在已创建的原生窗口上安装扩展；失败时撤销本次已经完成的修改。 */
    public void activate() {
        requireEdt();
        if (active) return;
        if (!Platform.isWindows() || !frame.isDisplayable() || frame.isUndecorated()) {
            throw new IllegalStateException("录制标题栏需要已创建的 Windows 原生装饰窗口");
        }
        originalBorder = titleBar.getBorder();
        originalPreferredSize = titleBar.isPreferredSizeSet() ? titleBar.getPreferredSize() : null;
        hwnd = new HWND(Native.getComponentPointer(frame));
        installedProc = CallbackReference.getFunctionPointer(windowProc);
        originalProc = Pointer.createConstant(User32.INSTANCE.GetWindowLongPtr(hwnd, GWLP_WNDPROC).longValue());
        if (Pointer.nativeValue(originalProc) == 0) {
            throw new IllegalStateException("无法取得主窗口的原生消息处理器");
        }
        boolean installed = false;
        try {
            // 原生依赖先加载，避免安装消息过程后才发现所需系统接口不可用。
            RECT initialBounds = new RECT();
            NativeDwm.INSTANCE.DwmGetWindowAttribute(hwnd, DWMWA_CAPTION_BUTTON_BOUNDS,
                    initialBounds.getPointer(), initialBounds.size());
            NativeUser.INSTANCE.GetDpiForWindow(hwnd);
            originalCaptionColor = readDwmColor(DWMWA_CAPTION_COLOR);
            originalTextColor = readDwmColor(DWMWA_TEXT_COLOR);
            // 预先保留原过程，避免安装返回之前到达的原生消息遇到空指针。
            Native.setLastError(0);
            Pointer previous = User32.INSTANCE.SetWindowLongPtr(hwnd, GWLP_WNDPROC, installedProc);
            if (previous == null && Native.getLastError() != 0) {
                throw new IllegalStateException("无法安装录制标题栏：Windows 错误 " + Native.getLastError());
            }
            if (previous != null) originalProc = previous;
            installed = true;
            INSTALLED.add(this);
            active = true;
            refresh();
            applyMargins();
            frameChanged();
            refresh();
        } catch (RuntimeException | LinkageError exception) {
            active = false;
            if (installed) {
                try {
                    removeNativeExtension();
                } catch (RuntimeException cleanupException) {
                    exception.addSuppressed(cleanupException);
                }
            }
            restoreComponent();
            throw new IllegalStateException("无法启用 Windows 录制标题栏", exception);
        }
    }

    /** 恢复原生消息链及标题栏边距，不影响主窗口原有的业务状态。 */
    public void deactivate() {
        requireEdt();
        if (!active && !INSTALLED.contains(this)) return;
        active = false;
        try {
            removeNativeExtension();
        } finally {
            restoreComponent();
        }
    }

    /** 更新系统按钮保留区与点击区域快照；重复调用不会反复重算非客户区。 */
    public void refresh() {
        requireEdt();
        if (!active || hwnd == null || !User32.INSTANCE.IsWindow(hwnd)) return;
        applyCaptionColors();
        if (User32.INSTANCE.IsWindowVisible(hwnd) && !NativeUser.INSTANCE.IsIconic(hwnd)) {
            updateMetrics();
        }
        List<Rectangle> buttonBounds = new ArrayList<>();
        for (AbstractButton button : interactiveComponents) {
            if (button.isVisible() && SwingUtilities.isDescendingFrom(button, titleBar)) {
                buttonBounds.add(SwingUtilities.convertRectangle(button.getParent(),
                        button.getBounds(), frame.getRootPane()));
            }
        }
        Rectangle titleBounds = SwingUtilities.isDescendingFrom(titleBar, frame.getRootPane())
                ? SwingUtilities.convertRectangle(titleBar.getParent(), titleBar.getBounds(), frame.getRootPane())
                : new Rectangle();
        LayoutSnapshot updated = new LayoutSnapshot(titleBounds, List.copyOf(buttonBounds));
        LayoutSnapshot previous = layout;
        layout = updated;
        if (!updated.equals(previous)) afterCaptionPaint();
    }

    /**
     * 在标题行完成绘制后调用。延后到 Swing 双缓冲提交之后，为真实 DWM 系统按钮保留透明区域。
     * 不绘制按钮图标，也不触发新的 Swing 重绘，避免形成重绘循环。
     */
    public void afterCaptionPaint() {
        if (!active || !clearQueued.compareAndSet(false, true)) return;
        SwingUtilities.invokeLater(() -> {
            clearQueued.set(false);
            clearSystemButtonSurface();
        });
    }

    public boolean isActive() {
        return active;
    }

    /** 返回窗口相对的物理像素矩形，调用方不能修改内部布局缓存。 */
    public Rectangle getSystemButtonBounds() {
        return new Rectangle(systemButtonBounds);
    }

    /** 返回控制行使用的 Swing 逻辑像素高度。 */
    public int getCaptionHeight() {
        return captionHeight;
    }

    private void updateMetrics() {
        int dpi = windowDpi();
        double scale = dpi / 96.0;
        RECT window = new RECT();
        RECT buttons = new RECT();
        if (!User32.INSTANCE.GetWindowRect(hwnd, window)) return;
        int result = NativeDwm.INSTANCE.DwmGetWindowAttribute(hwnd, DWMWA_CAPTION_BUTTON_BOUNDS,
                buttons.getPointer(), buttons.size());
        buttons.read();
        if (result < 0 || buttons.right <= buttons.left || buttons.bottom <= buttons.top
                || buttons.left < 0 || buttons.right > window.right - window.left + 2) {
            if (systemButtonBounds.isEmpty()) {
                throw new IllegalStateException("无法取得 Windows 标题栏按钮区域");
            }
            return;
        }
        systemButtonBounds = buttons.toRectangle();
        int nativeCaption = Math.max(NativeUser.INSTANCE.GetSystemMetricsForDpi(SM_CYCAPTION, dpi)
                + topResizeBorder(dpi), buttons.bottom);
        int nativeHeight = Math.max(1, (int) Math.ceil(nativeCaption / scale));
        int reserve = (int) Math.ceil((window.right - window.left - buttons.left) / scale) + 6;
        boolean dpiChanged = dpi != appliedDpi;
        appliedDpi = dpi;
        boolean maximized = NativeUser.INSTANCE.IsZoomed(hwnd);
        if (reserve != reservedWidth || nativeHeight != appliedCaptionHeight || dpiChanged
                || maximized != appliedMaximized) {
            reservedWidth = reserve;
            appliedCaptionHeight = nativeHeight;
            appliedMaximized = maximized;
            int topPadding = maximized ? 0
                    : (int) Math.ceil(topResizeBorder(dpi) / scale);
            titleBar.setBorder(BorderFactory.createCompoundBorder(originalBorder,
                    BorderFactory.createEmptyBorder(topPadding, 0, 0, reserve)));
            titleBar.setPreferredSize(null);
            Dimension preferred = titleBar.getPreferredSize();
            captionHeight = Math.max(nativeHeight, preferred.height);
            titleBar.setPreferredSize(new Dimension(preferred.width, captionHeight));
            titleBar.revalidate();
            applyMargins();
        }
    }

    private LRESULT handleMessage(HWND window, int message, WPARAM wParam, LPARAM lParam) {
        if (active && message == WM_NCCALCSIZE) {
            Pointer rectangle = new Pointer(lParam.longValue());
            int windowTop = rectangle.getInt(Integer.BYTES);
            callOriginal(window, message, wParam, lParam);
            // 顶部不能留下非客户区，否则部分 Windows 11 的 DWM 不再处理系统按钮命中。
            // 左、右、下仍使用系统边框；顶边缩放命中由下面的消息分支补充。
            rectangle.setInt(Integer.BYTES, windowTop + (NativeUser.INSTANCE.IsZoomed(window)
                    ? topResizeBorder(windowDpi()) : 0));
            return new LRESULT(0);
        }
        if (active && message == WM_NCHITTEST) {
            try (Memory value = new Memory(Native.POINTER_SIZE)) {
                boolean handled = NativeDwm.INSTANCE.DwmDefWindowProc(window, message, wParam, lParam, value);
                long hit = Native.POINTER_SIZE == Long.BYTES ? value.getLong(0) : value.getInt(0);
                if (handled && isSystemButton(hit)) return new LRESULT(hit);
            }
            LRESULT original = callOriginal(window, message, wParam, lParam);
            long hit = original.longValue();
            if (isSystemButton(hit)) return original;
            int x = (short) (lParam.longValue() & 0xFFFF);
            int y = (short) ((lParam.longValue() >>> 16) & 0xFFFF);
            // 部分 Windows 11 最大化窗口的 DWM 命中结果为空，使用系统提供的各按钮真实矩形补充。
            if (NativeUser.INSTANCE.IsZoomed(window)) {
                int systemHit = titleBarInfoHit(window, x, y);
                if (systemHit != 0) return new LRESULT(systemHit);
            }
            if (!NativeUser.INSTANCE.IsZoomed(window)) {
                RECT bounds = new RECT();
                int dpi = windowDpi();
                if (User32.INSTANCE.GetWindowRect(window, bounds)
                        && y >= bounds.top && y < bounds.top + topResizeBorder(dpi)) {
                    int cornerWidth = NativeUser.INSTANCE.GetSystemMetricsForDpi(SM_CXSIZEFRAME, dpi)
                            + NativeUser.INSTANCE.GetSystemMetricsForDpi(SM_CXPADDEDBORDER, dpi);
                    return new LRESULT(x < bounds.left + cornerWidth ? HTTOPLEFT
                            : x >= bounds.right - cornerWidth ? HTTOPRIGHT : HTTOP);
                }
                if (hit >= 10 && hit <= 17) return original;
            }
            POINT origin = new POINT();
            if (!NativeUser.INSTANCE.ClientToScreen(window, origin)) return original;
            double scale = windowDpi() / 96.0;
            LayoutSnapshot snapshot = layout;
            for (Rectangle button : snapshot.buttons()) {
                if (containsScreenPoint(button, origin, scale, x, y)) return new LRESULT(HTCLIENT);
            }
            if (containsScreenPoint(snapshot.titleBar(), origin, scale, x, y)) {
                return new LRESULT(HTCAPTION);
            }
            return original;
        }
        if (active && message == WM_NCMOUSELEAVE) {
            try (Memory value = new Memory(Native.POINTER_SIZE)) {
                NativeDwm.INSTANCE.DwmDefWindowProc(window, message, wParam, lParam, value);
            }
        }
        LRESULT result = callOriginal(window, message, wParam, lParam);
        if (message == WM_NCDESTROY) {
            active = false;
            // 原窗口过程已完成销毁，稍后在 EDT 释放回调的额外强引用。
            SwingUtilities.invokeLater(() -> INSTALLED.remove(this));
        } else if (active && (message == WM_SIZE || message == WM_WINDOWPOSCHANGED
                || message == WM_DPICHANGED || message == WM_ACTIVATE
                || message == WM_DWMCOMPOSITIONCHANGED)) {
            queueRefresh(message == WM_DWMCOMPOSITIONCHANGED);
        }
        return result;
    }

    private void queueRefresh(boolean resetMargins) {
        if (resetMargins) marginsRefreshRequested.set(true);
        if (!refreshQueued.compareAndSet(false, true)) return;
        SwingUtilities.invokeLater(() -> {
            refreshQueued.set(false);
            if (!active) return;
            refresh();
            if (marginsRefreshRequested.getAndSet(false)) applyMargins();
            // 激活及原生边框重画也可能覆盖玻璃区，即使 Swing 布局尺寸没有变化。
            afterCaptionPaint();
        });
    }

    private LRESULT callOriginal(HWND window, int message, WPARAM wParam, LPARAM lParam) {
        return User32.INSTANCE.CallWindowProc(originalProc, window, message, wParam, lParam);
    }

    private void applyMargins() {
        if (hwnd == null || !active) return;
        Margins margins = new Margins();
        margins.top = (int) Math.ceil(captionHeight * windowDpi() / 96.0);
        if (NativeDwm.INSTANCE.DwmExtendFrameIntoClientArea(hwnd, margins) < 0) {
            throw new IllegalStateException("无法扩展 Windows 录制标题栏");
        }
    }

    private void removeNativeExtension() {
        if (hwnd != null && User32.INSTANCE.IsWindow(hwnd)) {
            RuntimeException failure = null;
            // 消息链恢复不依赖后续 DWM 属性恢复是否成功。
            try {
                Pointer current = Pointer.createConstant(User32.INSTANCE.GetWindowLongPtr(hwnd, GWLP_WNDPROC).longValue());
                if (!Objects.equals(current, installedProc)) {
                    throw new IllegalStateException("窗口消息链已变化，无法安全移除录制标题栏");
                }
                Native.setLastError(0);
                Pointer previous = User32.INSTANCE.SetWindowLongPtr(hwnd, GWLP_WNDPROC, originalProc);
                if (previous == null && Native.getLastError() != 0) {
                    throw new IllegalStateException("无法恢复窗口消息处理器：Windows 错误 " + Native.getLastError());
                }
                INSTALLED.remove(this);
            } catch (RuntimeException exception) {
                failure = exception;
            } finally {
                try {
                    if (NativeDwm.INSTANCE.DwmExtendFrameIntoClientArea(hwnd, new Margins()) < 0) {
                        throw new IllegalStateException("无法恢复 Windows 窗口边距");
                    }
                } catch (RuntimeException exception) {
                    failure = addCleanupFailure(failure, exception);
                }
                if (captionColorsApplied) {
                    for (int attribute : new int[]{DWMWA_CAPTION_COLOR, DWMWA_TEXT_COLOR}) {
                        try {
                            setDwmColor(attribute, attribute == DWMWA_CAPTION_COLOR
                                    ? originalCaptionColor : originalTextColor);
                        } catch (RuntimeException exception) {
                            failure = addCleanupFailure(failure, exception);
                        }
                    }
                    captionColorsApplied = false;
                }
                try {
                    frameChanged();
                } catch (RuntimeException exception) {
                    failure = addCleanupFailure(failure, exception);
                }
            }
            if (failure != null) throw failure;
        } else {
            INSTALLED.remove(this);
        }
    }

    private void restoreComponent() {
        titleBar.setBorder(originalBorder);
        titleBar.setPreferredSize(originalPreferredSize);
        reservedWidth = -1;
        appliedCaptionHeight = -1;
        appliedDpi = 0;
        layout = LayoutSnapshot.EMPTY;
        titleBar.revalidate();
    }

    private void frameChanged() {
        if (!User32.INSTANCE.SetWindowPos(hwnd, null, 0, 0, 0, 0, FRAME_CHANGE_FLAGS)) {
            throw new IllegalStateException("无法刷新 Windows 原生窗口边框");
        }
    }

    private int windowDpi() {
        return Math.max(96, NativeUser.INSTANCE.GetDpiForWindow(hwnd));
    }

    private void clearSystemButtonSurface() {
        if (!active || hwnd == null || !User32.INSTANCE.IsWindow(hwnd)
                || !User32.INSTANCE.IsWindowVisible(hwnd) || NativeUser.INSTANCE.IsIconic(hwnd)) return;
        RECT bounds = new RECT();
        if (NativeDwm.INSTANCE.DwmGetWindowAttribute(hwnd, DWMWA_CAPTION_BUTTON_BOUNDS,
                bounds.getPointer(), bounds.size()) < 0) return;
        bounds.read();
        if (bounds.right <= bounds.left || bounds.bottom <= bounds.top) return;
        // 先提交 Java2D 缓冲，避免稍后的 D3D 提交再次盖住刚清理的系统按钮区。
        Toolkit.getDefaultToolkit().sync();
        HDC dc = NativeUser.INSTANCE.GetWindowDC(hwnd);
        if (dc == null) return;
        try {
            // BLACKNESS 写入零 alpha，是 DWM 玻璃区协议，颜色仍由系统标题栏主题绘制。
            NativeGdi.INSTANCE.PatBlt(dc, bounds.left, bounds.top,
                    bounds.right - bounds.left, bounds.bottom - bounds.top, 0x0042);
            NativeGdi.INSTANCE.GdiFlush();
        } finally {
            User32.INSTANCE.ReleaseDC(hwnd, dc);
        }
    }

    private static int titleBarInfoHit(HWND window, int x, int y) {
        TitleBarInfoEx information = new TitleBarInfoEx();
        information.write();
        User32.INSTANCE.SendMessage(window, WM_GETTITLEBARINFOEX, new WPARAM(0),
                new LPARAM(Pointer.nativeValue(information.getPointer())));
        information.read();
        int[] indices = {2, 3, 5};
        int[] results = {HTMINBUTTON, HTMAXBUTTON, HTCLOSE};
        for (int i = 0; i < indices.length; i++) {
            int index = indices[i];
            // 不把不可用、不可见或屏幕外的系统按钮变成可点击区域。
            if ((information.states[index] & (0x0001 | 0x8000 | 0x10000)) == 0
                    && information.rectangles[index].toRectangle().contains(x, y)) {
                return results[i];
            }
        }
        return 0;
    }

    private static RuntimeException addCleanupFailure(RuntimeException current, RuntimeException next) {
        if (current == null) return next;
        current.addSuppressed(next);
        return current;
    }

    private void applyCaptionColors() {
        Color background = titleBar.getBackground();
        int captionColor = colorRef(background == null ? ThemeManager.getPalette().background() : background);
        int textColor = colorRef(ThemeManager.getPalette().textPrimary());
        if (!captionColorsApplied || captionColor != appliedCaptionColor || textColor != appliedTextColor) {
            setDwmColor(DWMWA_CAPTION_COLOR, captionColor);
            setDwmColor(DWMWA_TEXT_COLOR, textColor);
            captionColorsApplied = true;
            appliedCaptionColor = captionColor;
            appliedTextColor = textColor;
        }
    }

    private int readDwmColor(int attribute) {
        try (Memory value = new Memory(Integer.BYTES)) {
            return NativeDwm.INSTANCE.DwmGetWindowAttribute(hwnd, attribute, value, Integer.BYTES) < 0
                    ? -1 : value.getInt(0);
        }
    }

    private void setDwmColor(int attribute, int color) {
        try (Memory value = new Memory(Integer.BYTES)) {
            value.setInt(0, color);
            if (NativeDwm.INSTANCE.DwmSetWindowAttribute(hwnd, attribute, value, Integer.BYTES) < 0) {
                throw new IllegalStateException("无法设置 Windows 标题栏主题颜色");
            }
        }
    }

    private static int colorRef(Color color) {
        return color.getRed() | color.getGreen() << 8 | color.getBlue() << 16;
    }

    private static int topResizeBorder(int dpi) {
        return NativeUser.INSTANCE.GetSystemMetricsForDpi(SM_CYSIZEFRAME, dpi)
                + NativeUser.INSTANCE.GetSystemMetricsForDpi(SM_CXPADDEDBORDER, dpi);
    }

    private static boolean containsScreenPoint(Rectangle logical, POINT origin, double scale, int x, int y) {
        return x >= origin.x + Math.round(logical.x * scale)
                && y >= origin.y + Math.round(logical.y * scale)
                && x < origin.x + Math.round((logical.x + logical.width) * scale)
                && y < origin.y + Math.round((logical.y + logical.height) * scale);
    }

    private static boolean isSystemButton(long hit) {
        return hit == HTMINBUTTON || hit == HTMAXBUTTON || hit == HTCLOSE;
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("录制标题栏只能在 Swing 事件线程中修改");
        }
    }

    private record LayoutSnapshot(Rectangle titleBar, List<Rectangle> buttons) {
        private static final LayoutSnapshot EMPTY = new LayoutSnapshot(new Rectangle(), List.of());
    }

    @Structure.FieldOrder({"left", "right", "top", "bottom"})
    public static final class Margins extends Structure {
        public int left;
        public int right;
        public int top;
        public int bottom;
    }

    @Structure.FieldOrder({"size", "titleBar", "states", "rectangles"})
    public static final class TitleBarInfoEx extends Structure {
        public int size;
        public RECT titleBar = new RECT();
        public int[] states = new int[6];
        public RECT[] rectangles = (RECT[]) new RECT().toArray(6);

        public TitleBarInfoEx() {
            size = size();
        }
    }

    private interface NativeDwm extends StdCallLibrary {
        NativeDwm INSTANCE = Native.load("dwmapi", NativeDwm.class);
        int DwmGetWindowAttribute(HWND window, int attribute, Pointer value, int size);
        int DwmSetWindowAttribute(HWND window, int attribute, Pointer value, int size);
        int DwmExtendFrameIntoClientArea(HWND window, Margins margins);
        boolean DwmDefWindowProc(HWND window, int message, WPARAM wParam, LPARAM lParam, Pointer result);
    }

    private interface NativeUser extends StdCallLibrary {
        NativeUser INSTANCE = Native.load("user32", NativeUser.class, W32APIOptions.DEFAULT_OPTIONS);
        int GetDpiForWindow(HWND window);
        int GetSystemMetricsForDpi(int index, int dpi);
        boolean IsIconic(HWND window);
        boolean IsZoomed(HWND window);
        boolean ClientToScreen(HWND window, POINT point);
        HDC GetWindowDC(HWND window);
    }

    private interface NativeGdi extends StdCallLibrary {
        NativeGdi INSTANCE = Native.load("gdi32", NativeGdi.class);
        boolean PatBlt(HDC dc, int left, int top, int width, int height, int operation);
        boolean GdiFlush();
    }
}
