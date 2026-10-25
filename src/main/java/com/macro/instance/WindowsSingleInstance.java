package com.macro.instance;

import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.*;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 限制同一 Windows 用户、同一登录会话只运行一个应用实例，并把重复启动转为唤起通知。
 *
 * <p>首个实例持有命名互斥量，由专用线程接收原生窗口消息，不替换 Swing/AWT 的窗口过程。
 * 应用退出时需关闭此对象，释放消息窗口和互斥量。</p>
 */
public final class WindowsSingleInstance implements AutoCloseable {
    private static final int ACTIVATE = 0x8001; // WM_APP + 1
    private static final int STARTUP_TIMEOUT_SECONDS = 5;
    // JNA 5.15 的 int 常量会在 64 位下零扩展；HWND_MESSAGE 要保留有符号指针值。
    private static final HWND MESSAGE_ONLY = new HWND(Pointer.createConstant(-3L));

    private final String windowClass;
    private final WinNT.HANDLE mutex;
    private final Runnable onActivate;
    // 强引用一直保留到消息窗口销毁、窗口类注销之后，防止原生回调被回收。
    private final WinUser.WindowProc windowProc = this::handleMessage;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicBoolean released = new AtomicBoolean();
    private final Thread messageThread;
    private volatile HWND messageWindow;

    /** 接管已取得的互斥量并准备通知线程；构造期间尚不创建消息窗口。 */
    private WindowsSingleInstance(String windowClass, WinNT.HANDLE mutex, Runnable onActivate) {
        this.windowClass = windowClass;
        this.mutex = mutex;
        this.onActivate = onActivate;
        messageThread = new Thread(this::runMessageLoop, "single-instance-messages");
        messageThread.setDaemon(true);
    }

    /**
     * 使用正式应用标识申请单实例资格，重复启动时仅通知已有实例。
     *
     * @param onActivate 在原生消息线程执行的唤起回调；操作 Swing 时需转交事件线程
     * @return 首个实例持有的资源对象；已通知其他实例时返回 null，调用方应直接结束启动
     * @throws IOException 平台不支持、原生资源创建失败或已有实例无法被通知
     */
    public static WindowsSingleInstance acquire(Runnable onActivate) throws IOException {
        return acquire("Main", onActivate);
    }

    /**
     * 按指定标识申请互斥量，等待正在初始化的实例，必要时在其退出后重新取得资格。
     * 独立标识用于隔离测试；正式入口固定使用 Main，使不同安装目录共享实例身份。
     */
    static WindowsSingleInstance acquire(String identity, Runnable onActivate) throws IOException {
        Objects.requireNonNull(onActivate, "onActivate");
        if (!Platform.isWindows()) throw new IOException("单实例启动需要 Windows");
        if (!identity.matches("[A-Za-z0-9.-]{1,40}")) throw new IllegalArgumentException("无效的实例标识");
        String windowClass = "MacroRecorder.SingleInstance." + currentUserSid() + "." + identity;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STARTUP_TIMEOUT_SECONDS);
        do {
            Kernel32.INSTANCE.SetLastError(0);
            WinNT.HANDLE mutex = Kernel32.INSTANCE.CreateMutex(null, false, "Local\\" + windowClass);
            int error = Kernel32.INSTANCE.GetLastError();
            if (mutex == null) throw nativeFailure("无法创建单实例锁", error);
            if (error != 183) return startPrimary(windowClass, mutex, onActivate); // ERROR_ALREADY_EXISTS
            Kernel32.INSTANCE.CloseHandle(mutex);
            if (notifyExisting(windowClass)) return null;
            // 首个进程可能正在初始化，或者异常退出尚在回收资源；重试原子获取，不能直接放行。
            try {
                Thread.sleep(50);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException("等待已有应用被中断", failure);
            }
        } while (System.nanoTime() < deadline);
        throw new IOException("Macro Recorder 已在运行，但暂时无法唤起窗口，请从任务栏打开");
    }

    /** 启动首个实例的消息线程并等待就绪；启动失败或等待中断时回收已取得的资源。 */
    private static WindowsSingleInstance startPrimary(String windowClass, WinNT.HANDLE mutex,
                                                       Runnable onActivate) throws IOException {
        WindowsSingleInstance instance = new WindowsSingleInstance(windowClass, mutex, onActivate);
        try {
            instance.messageThread.start();
        } catch (RuntimeException | Error failure) {
            Kernel32.INSTANCE.CloseHandle(mutex);
            throw failure;
        }
        try {
            instance.ready.get(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return instance;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            instance.close();
            throw new IOException("单实例启动被中断", failure);
        } catch (ExecutionException | TimeoutException failure) {
            instance.close();
            throw new IOException("无法建立单实例通知窗口", failure);
        }
    }

    /** 从当前进程令牌读取用户 SID，用于隔离不同用户的实例标识，并在读取后关闭令牌。 */
    private static String currentUserSid() throws IOException {
        WinNT.HANDLEByReference token = new WinNT.HANDLEByReference();
        if (!Advapi32.INSTANCE.OpenProcessToken(Kernel32.INSTANCE.GetCurrentProcess(), WinNT.TOKEN_QUERY, token)) {
            throw nativeFailure("无法读取当前用户身份", Kernel32.INSTANCE.GetLastError());
        }
        try {
            return Advapi32Util.getTokenAccount(token.getValue()).sidString;
        } finally {
            Kernel32.INSTANCE.CloseHandle(token.getValue());
        }
    }

    /**
     * 查找已有实例的消息窗口，尝试转交前台权限，再异步发送唤起消息。
     *
     * @return 已发送通知时为 true；消息窗口尚不存在时为 false，供调用方继续等待
     * @throws IOException 找到窗口但无法投递通知
     */
    private static boolean notifyExisting(String windowClass) throws IOException {
        HWND existing = User32.INSTANCE.FindWindowEx(MESSAGE_ONLY, null, windowClass, null);
        if (existing == null) return false;
        IntByReference processId = new IntByReference();
        User32.INSTANCE.GetWindowThreadProcessId(existing, processId);
        // 将本次用户启动获得的前台权限交给已有实例；失败时仍发送通知。
        if (processId.getValue() != 0) InstanceUser32.INSTANCE.AllowSetForegroundWindow(processId.getValue());
        if (!InstanceUser32.INSTANCE.PostMessage(existing, ACTIVATE, new WPARAM(0), new LPARAM(0))) {
            throw nativeFailure("应用已经运行，但无法通知其显示窗口", Kernel32.INSTANCE.GetLastError());
        }
        return true;
    }

    /**
     * 在专用线程创建消息窗口并处理通知，退出时按创建顺序的逆序回收窗口和窗口类。
     * 意外退出消息循环时仍保留互斥量，直到应用关闭，避免主窗口尚存却允许第二个实例启动。
     */
    private void runMessageLoop() {
        HMODULE module = null;
        boolean registered = false;
        try {
            module = Kernel32.INSTANCE.GetModuleHandle(null);
            WinUser.WNDCLASSEX definition = new WinUser.WNDCLASSEX();
            definition.hInstance = module;
            definition.lpszClassName = windowClass;
            definition.lpfnWndProc = windowProc;
            ATOM atom = User32.INSTANCE.RegisterClassEx(definition);
            if (atom == null || atom.intValue() == 0) {
                throw nativeFailure("无法注册单实例通知窗口", Kernel32.INSTANCE.GetLastError());
            }
            registered = true;
            messageWindow = User32.INSTANCE.CreateWindowEx(0, windowClass, "", 0,
                    0, 0, 0, 0, MESSAGE_ONLY, null, module, null);
            if (messageWindow == null) {
                throw nativeFailure("无法创建单实例通知窗口", Kernel32.INSTANCE.GetLastError());
            }
            ready.complete(null);
            WinUser.MSG message = new WinUser.MSG();
            while (!closing.get()) {
                int result = User32.INSTANCE.GetMessage(message, null, 0, 0);
                if (result == 0) break;
                if (result == -1) throw nativeFailure("单实例通知中断", Kernel32.INSTANCE.GetLastError());
                User32.INSTANCE.TranslateMessage(message);
                User32.INSTANCE.DispatchMessage(message);
            }
        } catch (Throwable failure) {
            if (!ready.completeExceptionally(failure)) failure.printStackTrace(System.err);
        } finally {
            if (messageWindow != null) User32.INSTANCE.DestroyWindow(messageWindow);
            messageWindow = null;
            if (registered) User32.INSTANCE.UnregisterClass(windowClass, module);
            // 通知线程异常时也保留互斥量，不能让仍在运行的主窗口失去单实例保护。
            if (closing.get()) releaseMutex();
        }
    }

    /** 处理唤起与关闭消息，其余交给默认窗口过程；唤起回调异常在原生边界内记录，不向外抛出。 */
    private LRESULT handleMessage(HWND window, int message, WPARAM wParam, LPARAM lParam) {
        if (message == ACTIVATE) {
            if (!closing.get()) {
                try {
                    onActivate.run();
                } catch (Throwable failure) {
                    // 异常不得穿过原生回调，也不能令已有实例丢失保护。
                    failure.printStackTrace(System.err);
                }
            }
            return new LRESULT(0);
        }
        if (message == WinUser.WM_CLOSE) {
            closing.set(true);
            return new LRESULT(0);
        }
        return User32.INSTANCE.DefWindowProc(window, message, wParam, lParam);
    }

    /**
     * 请求消息线程退出，等待其完成清理后释放互斥量，可重复调用。
     * 等待超时时由仍在运行的消息线程负责收尾，不提前放开单实例保护。
     */
    @Override
    public void close() {
        closing.set(true);
        HWND window = messageWindow;
        if (window != null) User32.INSTANCE.PostMessage(window, WinUser.WM_CLOSE, new WPARAM(0), new LPARAM(0));
        if (Thread.currentThread() != messageThread) {
            try {
                messageThread.join(TimeUnit.SECONDS.toMillis(2));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }
        }
        if (!messageThread.isAlive()) releaseMutex();
    }

    /** 保证互斥量句柄最多关闭一次，允许主动关闭和消息线程收尾共用此入口。 */
    private void releaseMutex() {
        if (released.compareAndSet(false, true)) Kernel32.INSTANCE.CloseHandle(mutex);
    }

    /** 将操作说明和 Windows 错误码组合为调用方可处理的异常。 */
    private static IOException nativeFailure(String message, int error) {
        return new IOException(message + "（Windows 错误码 " + error + "）");
    }

    /** 补充单实例通知所需、现有 JNA User32 声明未直接覆盖的原生调用。 */
    private interface InstanceUser32 extends StdCallLibrary {
        InstanceUser32 INSTANCE = Native.load("user32", InstanceUser32.class, W32APIOptions.DEFAULT_OPTIONS);
        /** 将当前进程获得的前台激活权限授予指定进程。 */
        boolean AllowSetForegroundWindow(int processId);
        /** 向消息窗口投递通知，不等待接收方执行回调。 */
        boolean PostMessage(HWND window, int message, WPARAM wParam, LPARAM lParam);
    }
}
