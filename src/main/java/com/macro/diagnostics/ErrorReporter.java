package com.macro.diagnostics;

import com.macro.config.AppConfig;

import java.nio.file.Path;
import java.time.Clock;
import java.util.function.BiConsumer;

/** 统一错误报告入口；不实例化配置、不改变个人数据，也不依赖弹窗成功显示。 */
public final class ErrorReporter {
    private static final ErrorReportWriter WRITER = new ErrorReportWriter(
            () -> {
                // 自动测试显式隔离日志；正常启动未设该参数时始终使用应用根目录。
                String isolatedRoot = System.getProperty("macro.errorReport.applicationDirectory");
                return isolatedRoot == null ? AppConfig.resolveApplicationDirectory().toPath() : Path.of(isolatedRoot);
            }, DeviceInformation::collect, Clock.systemDefaultZone());

    private ErrorReporter() { }

    /** 在调用线程立即记录原错误；返回报告路径，写入失败则返回 null。 */
    public static Path report(String message, Throwable failure) {
        return WRITER.write(message, failure, Thread.currentThread());
    }

    /** 应用入口安装一次，覆盖 Swing 事件线程及普通后台线程的未捕获异常。 */
    public static synchronized void installUncaughtHandler(BiConsumer<String, Throwable> notification) {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        if (previous instanceof UncaughtErrorHandler) return;
        Thread.setDefaultUncaughtExceptionHandler(new UncaughtErrorHandler(WRITER, notification, previous));
    }
}
