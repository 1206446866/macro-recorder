package com.macro.diagnostics;

import com.macro.config.AppConfig;
import com.macro.ui.dialog.AppMessageDialog;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** 经隔离入口运行，真实触发后台和 EDT 异常；不打开窗口、不碰个人日志。 */
public final class ErrorReportingIntegrationTest {
    public static void main(String[] args) throws Exception {
        Path root = AppConfig.resolveApplicationDirectory().toPath().toRealPath();
        check(root.getFileName().toString().equals("classes")
                        && root.getParent().getFileName().toString().startsWith("ErrorReportingIntegrationTest-"),
                "未隔离类来源，拒绝写入个人目录");
        check(GraphicsEnvironment.isHeadless(), "本检查应在无窗口模式下运行");
        check(System.getProperty("macro.errorReport.applicationDirectory") == null, "应验证默认应用目录而不是测试覆盖路径");
        Path log = Files.createDirectory(root.resolve("log"));
        Path kept = log.resolve("existing.log"); Files.writeString(kept, "旧报告必须保留");
        IOException cause = new IOException("文件不存在：中文图片.png");
        RuntimeException error = new IllegalStateException("测试错误", cause);
        error.addSuppressed(new IOException("测试清理失败"));
        AppMessageDialog.showError(null, "识别测试失败", error);
        check(reports(log).size() == 2, "错误弹窗入口没有立即落盘或重复记录");
        check(contents(log).contains("Caused by: java.io.IOException: 文件不存在：中文图片.png")
                        && contents(log).contains("Suppressed: java.io.IOException: 测试清理失败"), "统一弹窗丢失异常详情");
        AppMessageDialog.showError(null, "目标窗口未找到");
        check(reports(log).size() == 3 && contents(log).contains("未提供异常对象"), "仅消息报错缺少调用位置");
        AppMessageDialog.showWarning(null, "普通输入校验");
        AppMessageDialog.showInfo(null, "正常提示");
        check(reports(log).size() == 3, "普通提示或校验被当作错误");
        AppMessageDialog.showWarning(null, "剪贴板操作失败", new IllegalStateException("剪贴板占用"));
        check(reports(log).size() == 4 && contents(log).contains("剪贴板占用"), "警告样式的操作错误未记录");
        check(!Files.exists(root.resolve("config.properties")) && !Files.exists(root.resolve("save"))
                && !Files.exists(root.resolve("ScreenCapture")), "错误报告意外初始化配置或个人目录");

        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        AtomicInteger forwarded = new AtomicInteger();
        try {
            Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> forwarded.incrementAndGet());
            AppMessageDialog.installUncaughtExceptionHandler();
            var installed = Thread.getDefaultUncaughtExceptionHandler();
            AppMessageDialog.installUncaughtExceptionHandler();
            check(installed == Thread.getDefaultUncaughtExceptionHandler(), "重复安装叠加处理器");
            Thread worker = new Thread(() -> { throw new IllegalStateException("真正后台异常", cause); }, "error-report-worker");
            worker.start(); worker.join(10_000);
            check(!worker.isAlive(), "后台错误处理未返回");
            check(reports(log).size() == 5 && contents(log).contains("记录线程：error-report-worker"), "后台异常未记录或重复记录");
            SwingUtilities.invokeLater(() -> { throw new IllegalArgumentException("真正 EDT 异常"); });
            SwingUtilities.invokeAndWait(() -> { });
            check(reports(log).size() == 6 && contents(log).contains("真正 EDT 异常"), "EDT 异常未记录或导致事件线程停止");
            check(forwarded.get() == 2, "未保留原来的未捕获异常处理器");
        } finally { Thread.setDefaultUncaughtExceptionHandler(previous); }

        Path blocked = Files.createDirectory(root.resolve("blocked-root"));
        Path blocker = blocked.resolve("log"); Files.writeString(blocker, "保留阻挡文件");
        PrintStream originalError = System.err;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setErr(capture);
            System.setProperty("macro.errorReport.applicationDirectory", blocked.toString());
            AppMessageDialog.showError(null, "仍应保留原错误", new IOException("真实操作失败"));
        } finally {
            System.clearProperty("macro.errorReport.applicationDirectory"); System.setErr(originalError);
        }
        check(bytes.toString(StandardCharsets.UTF_8).contains("IOException: 真实操作失败"), "日志目录不可写时丢失原错误");
        check(Files.readString(blocker).equals("保留阻挡文件"), "日志错误覆盖了已有文件");
        check(ErrorReporter.report("恢复正常后仍可记录", cause) != null && reports(log).size() == 7, "日志失败后不能恢复");
        check(Files.readString(kept).equals("旧报告必须保留"), "旧报告被覆盖");
        String all = contents(log);
        for (String expected : new String[]{"os.name=", "java.runtime.version=", "[CPU 型号]", "JVM heap used=", "无图形环境（headless）"})
            check(all.contains(expected), "缺少真实运行环境：" + expected);
        System.out.println("通过：统一弹窗、异常链、消息调用位置、提示分类、默认根目录、数据保全、后台/EDT 异常、日志失败恢复");
    }

    private static List<Path> reports(Path log) throws IOException {
        try (var files = Files.list(log)) { return files.filter(Files::isRegularFile).toList(); }
    }
    private static String contents(Path log) throws IOException {
        StringBuilder text = new StringBuilder();
        for (Path file : reports(log)) text.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
        return text.toString();
    }
    private static void check(boolean result, String message) { if (!result) throw new AssertionError(message); }
}
