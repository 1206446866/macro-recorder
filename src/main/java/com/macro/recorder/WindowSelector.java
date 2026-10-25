package com.macro.recorder;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.mouse.NativeMouseEvent;
import com.github.kwhat.jnativehook.mouse.NativeMouseInputListener;
import com.macro.model.WindowInfo;
import com.macro.util.WindowsWindowUtil;
import lombok.Setter;

import java.util.function.Consumer;

/**
 * 目标窗口选择器。
 *
 * <p>用于监听鼠标移动和点击，根据鼠标所在屏幕坐标识别 Windows 窗口。</p>
 *
 * <p>选择窗口不会产生宏动作，仅负责获取目标窗口信息。</p>
 */
public class WindowSelector implements NativeMouseInputListener {

    /**
     * 当前窗口信息回调。
     */
    @Setter
    private Consumer<WindowInfo> onWindowMove;

    /**
     * 窗口选择完成回调。
     */
    @Setter
    private Consumer<WindowInfo> onSelected;

    private boolean running;

    /**
     * 开始监听窗口选择。
     *
     * @throws NativeHookException 注册 Native Hook 失败
     */
    public void start() throws NativeHookException {
        if (running) {
            return;
        }

        if (!GlobalScreen.isNativeHookRegistered()) {
            GlobalScreen.registerNativeHook();
        }

        GlobalScreen.addNativeMouseListener(this);
        GlobalScreen.addNativeMouseMotionListener(this);

        running = true;
    }

    /**
     * 停止监听窗口选择。
     *
     * @throws NativeHookException 移除 Native Hook 监听失败
     */
    public void stop() throws NativeHookException {
        if (!running) {
            return;
        }

        GlobalScreen.removeNativeMouseListener(this);
        GlobalScreen.removeNativeMouseMotionListener(this);

        running = false;
    }

    /** 查询鼠标所在窗口，并将结果交给界面更新候选窗口信息。 */
    @Override
    public void nativeMouseMoved(NativeMouseEvent e) {
        WindowInfo windowInfo = WindowsWindowUtil.getWindowInfo(
                e.getX(),
                e.getY()
        );

        if (onWindowMove != null) {
            onWindowMove.accept(windowInfo);
        }
    }

    /** 仅用鼠标左键确认有效窗口，停止选择监听后回传最终选择结果。 */
    @Override
    public void nativeMousePressed(NativeMouseEvent e) {
        if (e.getButton() != NativeMouseEvent.BUTTON1) {
            return;
        }

        WindowInfo windowInfo = WindowsWindowUtil.getWindowInfo(
                e.getX(),
                e.getY()
        );

        if (windowInfo == null) {
            return;
        }

        logSelectedWindow(windowInfo);

        try {
            stop();
        } catch (NativeHookException ignored) {
            // 当前选择结果已经获取，不影响窗口选择结果。
        }

        if (onSelected != null) {
            onSelected.accept(windowInfo);
        }
    }

    /** 窗口选择在按下时完成，此处无需处理释放事件。 */
    @Override
    public void nativeMouseReleased(NativeMouseEvent e) {
    }

    /** 输出选中窗口的完整诊断信息。 */
    private void logSelectedWindow(WindowInfo windowInfo) {
        String executablePath = WindowsWindowUtil.getExecutablePath(
                windowInfo.getProcessId()
        );

        System.out.println("========== Selected Window ==========");
        System.out.println("HWND: " + windowInfo.getHwnd());
        System.out.println("ProcessName: " + windowInfo.getProcessName());
        System.out.println("WindowTitle: " + windowInfo.getWindowTitle());
        System.out.println("WindowClass: " + windowInfo.getWindowClass());
        System.out.println("ExecutablePath: " + displayValue(executablePath));
        System.out.println("=====================================");
    }

    /** 将空诊断值转换为清晰的提示文字。 */
    private String displayValue(String value) {
        return value == null || value.isBlank() ? "<无法获取>" : value;
    }
}
