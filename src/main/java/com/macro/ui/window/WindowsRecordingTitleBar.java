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
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 将录制控制行放入 DWM 混合标题栏，保留 DWM 边框；标题内容与唯一最小化按钮由 Swing 绘制。
 * 原生消息线程只读取布局快照；Swing 组件及业务回调始终由事件派发线程处理。
 */
public final class WindowsRecordingTitleBar {
    private static final int GWLP_WNDPROC = -4;
    private static final int GWL_STYLE = -16;
    private static final int SYSTEM_BUTTON_STYLES = 0x00080000 | 0x00020000 | 0x00010000;
    private static final int WM_SIZE = 0x0005;
    private static final int WM_ACTIVATE = 0x0006;
    private static final int WM_WINDOWPOSCHANGED = 0x0047;
    private static final int WM_WINDOWPOSCHANGING = 0x0046;
    private static final int WM_SIZING = 0x0214;
    private static final int WM_EXITSIZEMOVE = 0x0232;
    private static final int WM_GETMINMAXINFO = 0x0024;
    private static final int WM_NCCALCSIZE = 0x0083;
    private static final int WM_NCHITTEST = 0x0084;
    private static final int WM_NCDESTROY = 0x0082;
    private static final int WM_DPICHANGED = 0x02E0;
    private static final int WM_DWMCOMPOSITIONCHANGED = 0x031E;
    private static final int HTCLIENT = 1;
    private static final int HTCAPTION = 2;
    private static final int HTLEFT = 10;
    private static final int HTRIGHT = 11;
    private static final int SM_CYSIZEFRAME = 33;
    private static final int SM_CXSIZEFRAME = 32;
    private static final int SM_CXPADDEDBORDER = 92;
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
    private final AtomicBoolean finalRepaintRequested = new AtomicBoolean();

    private volatile LayoutSnapshot layout = LayoutSnapshot.EMPTY;

    private volatile int captionHeight;
    private volatile boolean active;
    private volatile HWND hwnd;
    private volatile Pointer originalProc;
    private Pointer installedProc;
    private Border originalBorder;
    private Dimension originalPreferredSize;
    private int originalButtonStyles;
    private boolean stylesChanged;
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
            int style = User32.INSTANCE.GetWindowLong(hwnd, GWL_STYLE);
            originalButtonStyles = style & SYSTEM_BUTTON_STYLES;
            setWindowStyle(style & ~SYSTEM_BUTTON_STYLES);
            stylesChanged = true;
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

    /** 更新 DPI 边距与点击区域快照；重复调用不会反复重算非客户区。 */
    public void refresh() {
        refresh(false);
    }

    private void refresh(boolean forceRepaint) {
        requireEdt();
        if (!active || hwnd == null || !User32.INSTANCE.IsWindow(hwnd)) return;
        boolean appearanceChanged = applyCaptionColors();
        if (User32.INSTANCE.IsWindowVisible(hwnd) && !NativeUser.INSTANCE.IsIconic(hwnd)) {
            appearanceChanged |= updateMetrics();
        }
        boolean repairedLayout = false;
        if (!NativeUser.INSTANCE.IsIconic(hwnd) && needsLayout()) {
            // AWT 通常已完成布局；只修复失效或仍保留旧尺寸的容器。
            // 根面板可能自认为有效，单独 invalidate JFrame 不会遍历有效子树。
            frame.getRootPane().invalidate();
            frame.invalidate();
            frame.validate();
            repairedLayout = true;
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
        Dimension minimum = new Dimension(frame.getMinimumSize());
        if (minimum.height > 0) {
            minimum.height = titleBar.getPreferredSize().height + frame.getInsets().top + frame.getInsets().bottom;
        }
        LayoutSnapshot updated = new LayoutSnapshot(titleBounds, List.copyOf(buttonBounds), minimum);
        boolean boundsChanged = !updated.equals(layout);
        layout = updated;
        if (forceRepaint || appearanceChanged || repairedLayout || boundsChanged) titleBar.repaint();
    }

    /** 比较 Swing 客户区及各级内容尺寸；不因重复尺寸消息无条件重新布局。 */
    private boolean needsLayout() {
        JRootPane root = frame.getRootPane();
        Container content = frame.getContentPane();
        Insets frameInsets = frame.getInsets();
        int rootWidth = Math.max(0, frame.getWidth() - frameInsets.left - frameInsets.right);
        int rootHeight = Math.max(0, frame.getHeight() - frameInsets.top - frameInsets.bottom);
        Insets rootInsets = root.getInsets();
        int width = Math.max(0, rootWidth - rootInsets.left - rootInsets.right);
        int height = Math.max(0, rootHeight - rootInsets.top - rootInsets.bottom);
        int menuHeight = root.getJMenuBar() != null && root.getJMenuBar().isVisible()
                ? root.getJMenuBar().getPreferredSize().height : 0;
        return !frame.isValid() || !root.isValid() || !content.isValid() || !titleBar.isValid()
                || root.getWidth() != rootWidth || root.getHeight() != rootHeight
                || root.getLayeredPane().getWidth() != width || root.getLayeredPane().getHeight() != height
                || content.getWidth() != width || content.getHeight() != Math.max(0, height - menuHeight);
    }

    public boolean isActive() {
        return active;
    }

    /** 返回控制行使用的 Swing 逻辑像素高度。 */
    public int getCaptionHeight() {
        return captionHeight;
    }

    private boolean updateMetrics() {
        int dpi = windowDpi();
        double scale = dpi / 96.0;
        boolean dpiChanged = dpi != appliedDpi;
        appliedDpi = dpi;
        boolean maximized = NativeUser.INSTANCE.IsZoomed(hwnd);
        if (dpiChanged || maximized != appliedMaximized) {
            appliedMaximized = maximized;
            int topPadding = maximized ? 0
                    : (int) Math.ceil(topResizeBorder(dpi) / scale);
            titleBar.setBorder(BorderFactory.createCompoundBorder(originalBorder,
                    BorderFactory.createEmptyBorder(topPadding, 0, 0, 0)));
            titleBar.setPreferredSize(null);
            Dimension preferred = titleBar.getPreferredSize();
            captionHeight = preferred.height;
            titleBar.revalidate();
            applyMargins();
            return true;
        }
        return false;
    }

    private LRESULT handleMessage(HWND window, int message, WPARAM wParam, LPARAM lParam) {
        if (active && message == WM_GETMINMAXINFO) {
            LRESULT result = callOriginal(window, message, wParam, lParam);
            Pointer information = new Pointer(lParam.longValue());
            Dimension minimum = layout.minimumSize();
            double scale = windowDpi() / 96.0;
            // MINMAXINFO.ptMinTrackSize 位于第四个 POINT；原生最小拖动尺寸使用物理像素。
            information.setInt(24, Math.max(information.getInt(24), (int) Math.ceil(minimum.width * scale)));
            if (minimum.height > 0) {
                int fixedHeight = (int) Math.ceil(minimum.height * scale);
                information.setInt(28, fixedHeight); // ptMinTrackSize.y
                information.setInt(36, fixedHeight); // ptMaxTrackSize.y
                information.setInt(12, fixedHeight); // ptMaxSize.y，阻止系统贴靠撑高窗口
            }
            return result;
        }
        if (active && message == WM_SIZING && layout.minimumSize().height > 0) {
            Pointer rectangle = new Pointer(lParam.longValue());
            Rectangle proposed = new Rectangle(rectangle.getInt(0), rectangle.getInt(4),
                    rectangle.getInt(8) - rectangle.getInt(0), rectangle.getInt(12) - rectangle.getInt(4));
            double scale = windowDpi() / 96.0;
            Dimension minimum = layout.minimumSize();
            Rectangle constrained = RecordingBarGeometry.constrainSizing(proposed, wParam.intValue(),
                    new Dimension((int) Math.ceil(minimum.width * scale), (int) Math.ceil(minimum.height * scale)));
            rectangle.setInt(0, constrained.x);
            rectangle.setInt(4, constrained.y);
            rectangle.setInt(8, constrained.x + constrained.width);
            rectangle.setInt(12, constrained.y + constrained.height);
            // 保留原 AWT 的动态布局及 D3D/OGL 绘图表面更新，不能只转交 DefWindowProc。
            callOriginal(window, message, wParam, lParam);
            // 本层已处理尺寸矩形，按 WM_SIZING 契约返回 TRUE，不沿用默认过程的返回值。
            return new LRESULT(1);
        }
        if (active && message == WM_WINDOWPOSCHANGING && layout.minimumSize().height > 0
                && !NativeUser.INSTANCE.IsIconic(window)) {
            LRESULT result = callOriginal(window, message, wParam, lParam);
            Pointer position = new Pointer(lParam.longValue());
            int offset = Native.POINTER_SIZE * 2;
            if ((position.getInt(offset + 16) & 0x0001) == 0 && position.getInt(offset + 12) > 0) {
                double scale = windowDpi() / 96.0;
                Dimension minimum = layout.minimumSize();
                position.setInt(offset + 8, Math.max(position.getInt(offset + 8), (int) Math.ceil(minimum.width * scale)));
                position.setInt(offset + 12, (int) Math.ceil(minimum.height * scale));
            }
            return result;
        }
        if (active && message == WM_NCCALCSIZE) {
            Pointer rectangle = new Pointer(lParam.longValue());
            int windowTop = rectangle.getInt(Integer.BYTES);
            callOriginal(window, message, wParam, lParam);
            // 顶部由自绘内容占用；左右及底部继续保留系统边框。
            // 顶边保留拖动；下面的命中分支将角落限制为水平缩放。
            rectangle.setInt(Integer.BYTES, windowTop + (NativeUser.INSTANCE.IsZoomed(window)
                    ? topResizeBorder(windowDpi()) : 0));
            return new LRESULT(0);
        }
        if (active && message == WM_NCHITTEST) {
            LRESULT original = callOriginal(window, message, wParam, lParam);
            long hit = original.longValue();
            int x = (short) (lParam.longValue() & 0xFFFF);
            int y = (short) ((lParam.longValue() >>> 16) & 0xFFFF);
            if (!NativeUser.INSTANCE.IsZoomed(window)) {
                RECT bounds = new RECT();
                int dpi = windowDpi();
                if (User32.INSTANCE.GetWindowRect(window, bounds)
                        && y >= bounds.top && y < bounds.top + topResizeBorder(dpi)) {
                    int cornerWidth = NativeUser.INSTANCE.GetSystemMetricsForDpi(SM_CXSIZEFRAME, dpi)
                            + NativeUser.INSTANCE.GetSystemMetricsForDpi(SM_CXPADDEDBORDER, dpi);
                    return new LRESULT(x < bounds.left + cornerWidth ? HTLEFT
                            : x >= bounds.right - cornerWidth ? HTRIGHT : HTCAPTION);
                }
                if (hit >= 10 && hit <= 17) return new LRESULT(RecordingBarGeometry.horizontalHit((int) hit));
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
        LRESULT result = callOriginal(window, message, wParam, lParam);
        if (message == WM_NCDESTROY) {
            active = false;
            // 原窗口过程已完成销毁，稍后在 EDT 释放回调的额外强引用。
            SwingUtilities.invokeLater(() -> INSTALLED.remove(this));
        } else if (active && (message == WM_SIZE || message == WM_WINDOWPOSCHANGED
                || message == WM_DPICHANGED || message == WM_ACTIVATE
                || message == WM_DWMCOMPOSITIONCHANGED || message == WM_EXITSIZEMOVE)) {
            queueRefresh(message == WM_DWMCOMPOSITIONCHANGED,
                    message == WM_EXITSIZEMOVE || message == WM_DPICHANGED
                            || message == WM_ACTIVATE || message == WM_DWMCOMPOSITIONCHANGED);
        }
        return result;
    }

    private void queueRefresh(boolean resetMargins, boolean forceRepaint) {
        if (resetMargins) marginsRefreshRequested.set(true);
        if (forceRepaint) finalRepaintRequested.set(true);
        if (!refreshQueued.compareAndSet(false, true)) return;
        SwingUtilities.invokeLater(() -> {
            refreshQueued.set(false);
            if (!active) return;
            boolean reset = marginsRefreshRequested.getAndSet(false);
            if (reset) applyMargins();
            // 松手时总有最后一次提交；普通重复尺寸消息不强制整栏重绘。
            refresh(finalRepaintRequested.getAndSet(false) || reset);
        });
    }

    private LRESULT callOriginal(HWND window, int message, WPARAM wParam, LPARAM lParam) {
        return User32.INSTANCE.CallWindowProc(originalProc, window, message, wParam, lParam);
    }

    private void applyMargins() {
        if (hwnd == null || !active) return;
        Margins margins = new Margins();
        // 顶部全部由 Swing 绘制；保留 DWM 原生外框，不再扩展玻璃按钮区。
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
                    if (stylesChanged) {
                        int currentStyle = User32.INSTANCE.GetWindowLong(hwnd, GWL_STYLE);
                        setWindowStyle((currentStyle & ~SYSTEM_BUTTON_STYLES) | originalButtonStyles);
                        stylesChanged = false;
                    }
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
        appliedDpi = 0;
        finalRepaintRequested.set(false);
        marginsRefreshRequested.set(false);
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

    private void setWindowStyle(int style) {
        Native.setLastError(0);
        int previous = User32.INSTANCE.SetWindowLong(hwnd, GWL_STYLE, style);
        if (previous == 0 && Native.getLastError() != 0) {
            throw new IllegalStateException("无法更新录制窗口样式：Windows 错误 " + Native.getLastError());
        }
    }

    private static RuntimeException addCleanupFailure(RuntimeException current, RuntimeException next) {
        if (current == null) return next;
        current.addSuppressed(next);
        return current;
    }

    private boolean applyCaptionColors() {
        Color background = titleBar.getBackground();
        int captionColor = colorRef(background == null ? ThemeManager.getPalette().background() : background);
        int textColor = colorRef(ThemeManager.getPalette().textPrimary());
        if (!captionColorsApplied || captionColor != appliedCaptionColor || textColor != appliedTextColor) {
            setDwmColor(DWMWA_CAPTION_COLOR, captionColor);
            setDwmColor(DWMWA_TEXT_COLOR, textColor);
            captionColorsApplied = true;
            appliedCaptionColor = captionColor;
            appliedTextColor = textColor;
            return true;
        }
        return false;
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

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("录制标题栏只能在 Swing 事件线程中修改");
        }
    }

    private record LayoutSnapshot(Rectangle titleBar, List<Rectangle> buttons, Dimension minimumSize) {
        private static final LayoutSnapshot EMPTY = new LayoutSnapshot(new Rectangle(), List.of(), new Dimension());
    }

    @Structure.FieldOrder({"left", "right", "top", "bottom"})
    public static final class Margins extends Structure {
        public int left;
        public int right;
        public int top;
        public int bottom;
    }

    private interface NativeDwm extends StdCallLibrary {
        NativeDwm INSTANCE = Native.load("dwmapi", NativeDwm.class);
        int DwmGetWindowAttribute(HWND window, int attribute, Pointer value, int size);
        int DwmSetWindowAttribute(HWND window, int attribute, Pointer value, int size);
        int DwmExtendFrameIntoClientArea(HWND window, Margins margins);
    }

    private interface NativeUser extends StdCallLibrary {
        NativeUser INSTANCE = Native.load("user32", NativeUser.class, W32APIOptions.DEFAULT_OPTIONS);
        int GetDpiForWindow(HWND window);
        int GetSystemMetricsForDpi(int index, int dpi);
        boolean IsIconic(HWND window);
        boolean IsZoomed(HWND window);
        boolean ClientToScreen(HWND window, POINT point);
    }


}
