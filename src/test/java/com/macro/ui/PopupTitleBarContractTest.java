package com.macro.ui;

import com.macro.config.AppConfig;
import com.macro.ui.dialog.AppMessageDialog;
import com.macro.ui.dialog.ImageRecognitionFrame;
import com.macro.ui.window.MainTitleBar;
import com.macro.ui.window.WindowsRecordingTitleBar;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** 隔离配置中的弹窗标题栏检查，不显示模态窗口、不注册钩子或发送输入。 */
public final class PopupTitleBarContractTest {
    /** 先检查配置隔离，再验证四类真实弹窗及关闭后的原生回调释放。 */
    public static void main(String[] args) {
        int exit = 0;
        try {
            Path classes = Path.of(AppConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            check(classes.toString().contains("test-sandboxes"), "必须隔离个人配置");
            Path previews = classes.getParent().getParent().getParent().resolve("dialog-titlebars-20261025");
            Files.createDirectories(previews);
            SwingUtilities.invokeAndWait(() -> verify(previews));
            System.out.println("通过：弹窗统一标题栏、原关闭语义、模式与原生回调生命周期");
        } catch (Throwable failure) {
            System.getLogger(PopupTitleBarContractTest.class.getName()).log(System.Logger.Level.ERROR,
                    "弹窗标题栏检查失败", failure);
            exit = 1;
        }
        System.exit(exit);
    }

    /** 保留各窗原有模态和尺寸能力，用真实 HWND 核对安装及关闭，避免触发实际保存/识别。 */
    private static void verify(Path previews) {
        List<Window> windows = new ArrayList<>();
        try {
            AppConfig config = new AppConfig();
            SettingsDialog settings = new SettingsDialog(null, config);
            windows.add(settings);
            check(settings.isModal() && settings.isResizable(), "设置窗模态或尺寸行为改变");
            verifyWindow(settings, 1, previews.resolve("settings-preview.png"));
            check(!settings.isSettingsSaved(), "标题栏关闭意外保存设置");

            AtomicInteger saves = new AtomicInteger();
            ActionEditorDialog editor = ActionEditorDialog.create((Window) null, node -> saves.incrementAndGet());
            windows.add(editor);
            check(!editor.isModal() && !editor.isResizable(), "编辑器模态或固定尺寸改变");
            verifyWindow(editor, 1, previews.resolve("editor-preview.png"));
            check(saves.get() == 0 && Boolean.TRUE.equals(field(editor, "closed")), "关闭编辑器意外保存或没有清理");

            ImageRecognitionFrame recognition = new ImageRecognitionFrame(null, () -> null);
            windows.add(recognition);
            verifyWindow(recognition, 3, previews.resolve("recognition-preview.png"));
            check(Boolean.TRUE.equals(field(recognition, "closing")), "关闭识别窗口没有执行取消清理");

            Class<?> messageType = Class.forName("com.macro.ui.dialog.MessageType");
            var constructor = AppMessageDialog.class.getDeclaredConstructor(Window.class, messageType,
                    String.class, String.class, Throwable.class, boolean.class);
            constructor.setAccessible(true);
            for (boolean confirmation : new boolean[]{false, true}) {
                AppMessageDialog message = constructor.newInstance(null, messageType.getEnumConstants()[0],
                        confirmation ? "确认操作" : "操作提示", "标题栏关闭应保持原有取消行为。", null, confirmation);
                windows.add(message);
                check(message.isModal() && !message.isResizable(), "消息窗的模态或尺寸行为改变");
                verifyWindow(message, 1, previews.resolve(confirmation ? "confirm-preview.png" : "message-preview.png"));
                check(Boolean.FALSE.equals(field(message, "confirmed")), "标题栏关闭错误地返回确认");
            }
            AppMessageDialog confirmed = constructor.newInstance(null, messageType.getEnumConstants()[0],
                    "确认操作", "默认确认按钮仍沿用原有行为。", null, true);
            windows.add(confirmed);
            confirmed.getRootPane().getDefaultButton().doClick();
            check(Boolean.TRUE.equals(field(confirmed, "confirmed")) && !confirmed.isDisplayable(),
                    "默认确认按钮没有保持原有语义");
        } catch (Exception failure) { throw new AssertionError(failure); }
        finally { for (Window window : windows) window.dispose(); }
    }

    /** 验证统一标题、系统按钮隐藏、单一消息链及关闭时卸载；预览只取实际根面板离屏绘制。 */
    private static void verifyWindow(Window window, int buttonCount, Path preview) throws Exception {
        window.setAutoRequestFocus(false);
        if (!window.isDisplayable()) window.addNotify();
        window.validate();
        RootPaneContainer root = (RootPaneContainer) window;
        check(root.getRootPane().getJMenuBar() instanceof MainTitleBar, "没有使用统一标题栏");
        MainTitleBar bar = (MainTitleBar) root.getRootPane().getJMenuBar();
        check(bar.isVisible() && bar.getWindowButtons().size() == buttonCount, "窗口按钮数量或可见性错误");
        Object support = field(window, "titleBarSupport");
        WindowsRecordingTitleBar nativeBar = (WindowsRecordingTitleBar) field(support, "nativeTitleBar");
        nativeBar.refresh();
        check(nativeBar.isActive(), "原生标题栏安装失败");
        HWND hwnd = new HWND(Native.getComponentPointer(window));
        check((User32.INSTANCE.GetWindowLong(hwnd, -16) & 0x80000) == 0, "残留系统标题按钮");
        check(User32.INSTANCE.GetWindowLongPtr(hwnd, -4).longValue()
                != Pointer.nativeValue((Pointer) field(nativeBar, "originalProc")), "窗口过程没有安装");
        POINT origin = new POINT();
        check(Coordinates.INSTANCE.ClientToScreen(hwnd, origin), "无法取得弹窗客户区原点");
        double scale = Coordinates.INSTANCE.GetDpiForWindow(hwnd) / 96.0;
        Rectangle title = SwingUtilities.convertRectangle(bar.getParent(), bar.getBounds(), root.getRootPane());
        check(hit(hwnd, origin, scale, title.x + 24, title.getCenterY()) == 2, "标题栏不支持原生拖动");
        for (JButton button : bar.getWindowButtons()) {
            Rectangle bounds = SwingUtilities.convertRectangle(button.getParent(), button.getBounds(), root.getRootPane());
            check(hit(hwnd, origin, scale, bounds.getCenterX(), bounds.getCenterY()) == 1, "窗口按钮误判为拖动区域");
        }
        Container buttons = bar.getWindowButtons().get(0).getParent();
        check(buttons.getX() + buttons.getWidth() == bar.getWidth() - bar.getInsets().right, "按钮未固定在右侧");
        paint(root.getRootPane(), preview);
        bar.getWindowButtons().get(buttonCount - 1).doClick();
        check(!window.isDisplayable() && !nativeBar.isActive(), "关闭窗口未释放原生消息回调");
    }

    /** 导出 150% 离屏组件图，不捕获桌面、不代替真实桌面验收。 */
    private static void paint(JComponent component, Path path) throws Exception {
        double scale = 1.5;
        BufferedImage image = new BufferedImage((int) Math.ceil(component.getWidth() * scale),
                (int) Math.ceil(component.getHeight() * scale), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try { graphics.scale(scale, scale); component.printAll(graphics); }
        finally { graphics.dispose(); }
        ImageIO.write(image, "png", path.toFile());
    }

    /** 读取测试所需窗口及适配器状态，不暴露额外生产接口。 */
    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    /** 合成原生命中消息，不移动鼠标或激活窗口。 */
    private static long hit(HWND hwnd, POINT origin, double scale, double x, double y) {
        int screenX = origin.x + (int) Math.round(x * scale);
        int screenY = origin.y + (int) Math.round(y * scale);
        return User32.INSTANCE.SendMessage(hwnd, 0x84, new WPARAM(0),
                new LPARAM((screenX & 0xffffL) | ((screenY & 0xffffL) << 16))).longValue();
    }

    /** 提供测试的 DPI 和坐标转换，不扩展生产接口。 */
    private interface Coordinates extends StdCallLibrary {
        Coordinates INSTANCE = Native.load("user32", Coordinates.class, W32APIOptions.DEFAULT_OPTIONS);
        boolean ClientToScreen(HWND window, POINT point);
        int GetDpiForWindow(HWND window);
    }

    /** 保留契约失败的具体原因。 */
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
