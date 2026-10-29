package com.macro.diagnostics;

import java.util.function.BiConsumer;

/** 先落盘再通知界面；通知或旧处理器失败不能再次进入全局错误弹窗。 */
final class UncaughtErrorHandler implements Thread.UncaughtExceptionHandler {
    private final ErrorReportWriter writer;
    private final BiConsumer<String, Throwable> notification;
    private final Thread.UncaughtExceptionHandler previous;
    private final ThreadLocal<Boolean> handling = new ThreadLocal<>();

    UncaughtErrorHandler(ErrorReportWriter writer, BiConsumer<String, Throwable> notification,
                         Thread.UncaughtExceptionHandler previous) {
        this.writer = writer;
        this.notification = notification;
        this.previous = previous;
    }

    @Override public void uncaughtException(Thread thread, Throwable failure) {
        if (Boolean.TRUE.equals(handling.get())) return;
        handling.set(true);
        try {
            String message = "程序发生未处理的错误（" + thread.getName() + "）：" + failure;
            writer.write(message, failure, thread);
            try {
                if (previous != null) previous.uncaughtException(thread, failure);
                else failure.printStackTrace(System.err);
            } catch (Throwable ignored) { /* 不丢失已经写入的报告。 */ }
            try { notification.accept(message, failure); }
            catch (Throwable notificationFailure) { writer.write("显示未处理错误提示失败", notificationFailure, thread); }
        } finally {
            handling.remove();
        }
    }
}
