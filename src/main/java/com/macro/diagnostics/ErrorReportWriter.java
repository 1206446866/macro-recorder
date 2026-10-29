package com.macro.diagnostics;

import com.macro.constant.ApplicationInfo;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Supplier;

/** 每次失败独立落盘，不覆盖已有报告；日志本身失败时只回退标准错误流。 */
final class ErrorReportWriter {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private final Supplier<Path> applicationDirectory;
    private final Supplier<String> deviceInformation;
    private final Clock clock;
    private final ThreadLocal<Boolean> writing = new ThreadLocal<>();

    ErrorReportWriter(Supplier<Path> applicationDirectory, Supplier<String> deviceInformation, Clock clock) {
        this.applicationDirectory = applicationDirectory;
        this.deviceInformation = deviceInformation;
        this.clock = clock;
    }

    Path write(String message, Throwable failure, Thread origin) {
        if (Boolean.TRUE.equals(writing.get())) return null;
        writing.set(true);
        try {
            ZonedDateTime now = ZonedDateTime.now(clock);
            Path root = applicationDirectory.get().toAbsolutePath().normalize();
            StringBuilder report = new StringBuilder(4096);
            report.append(ApplicationInfo.APP_NAME).append(" 错误报告\n")
                    .append("程序版本：").append(ApplicationInfo.APP_VERSION).append('\n')
                    .append("发生时间：").append(now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)).append('\n')
                    .append("记录线程：").append(origin.getName()).append("；ID=").append(origin.getId())
                    .append("；daemon=").append(origin.isDaemon()).append('\n')
                    .append("程序目录：").append(root).append('\n')
                    .append("错误描述：").append(message == null ? "（无描述）" : message).append("\n\n")
                    .append("=== 异常详情 ===\n");
            StringWriter stack = new StringWriter();
            if (failure != null) failure.printStackTrace(new PrintWriter(stack));
            else {
                report.append("未提供异常对象，以下仅为报告调用位置。\n");
                new Exception("报告调用位置").printStackTrace(new PrintWriter(stack));
            }
            report.append(stack).append("\n=== 设备与运行环境 ===\n");
            try {
                report.append(deviceInformation.get());
            } catch (Throwable collectionFailure) {
                report.append("环境信息采集失败：").append(collectionFailure).append('\n');
            }
            Path directory = root.resolve("log");
            Files.createDirectories(directory);
            // createTempFile 原子创建唯一文件，同毫秒及并发报错也不会相互覆盖。
            Path file = Files.createTempFile(directory, "error-" + now.format(FILE_TIME) + "-", ".log");
            Files.writeString(file, report, StandardCharsets.UTF_8, StandardOpenOption.WRITE);
            return file;
        } catch (Throwable loggingFailure) {
            try {
                System.err.println("错误报告写入失败：" + loggingFailure);
                System.err.println("原始错误：" + message);
                if (failure != null) failure.printStackTrace(System.err);
            } catch (Throwable ignored) {
                // 例如内存耗尽时，尽力记录，但绝不递归触发弹窗或替换原错误。
            }
            return null;
        } finally {
            writing.remove();
        }
    }
}
