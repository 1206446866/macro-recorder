package com.macro.ui;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.mouse.NativeMouseEvent;
import com.github.kwhat.jnativehook.mouse.NativeMouseListener;
import com.macro.config.AppConfig;
import com.macro.recorder.MacroRecorder;
import com.sun.jna.platform.win32.Kernel32;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.stream.Collectors;

/** 人工/Computer Use 实点夹具，必须从 target 内的隔离类目录启动，避免修改个人配置。 */
public final class RecordingControlBarLiveTest {
    private static long nativeWhen;
    private static long nativeTick;
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                MacroFrame frame = new MacroFrame();
                AppConfig config = (AppConfig) field(frame, "appConfig");
                Path applicationDirectory = config.getApplicationDirectory().toPath().toAbsolutePath().normalize();
                if (!applicationDirectory.startsWith(Path.of("target").toAbsolutePath().normalize())) {
                    frame.dispose();
                    throw new IllegalStateException("请从 target 内的隔离类目录运行实点夹具");
                }
                Properties properties = (Properties) field(config, "properties");
                properties.setProperty("record.startBehavior", "CONTROL_BAR");
                properties.setProperty("record.mouseMove", "false");
                MacroRecorder recorder = (MacroRecorder) field(frame, "recorder");
                frame.setTitle("录制控制栏实点验证");
                frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
                JFrame input = new JFrame("控制栏外部输入验证");
                input.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
                JTextField text = new JTextField();
                text.setFont(new Font("Dialog", Font.PLAIN, 20));
                JPanel content = new JPanel(new BorderLayout(0, 12));
                content.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
                content.add(new JLabel("在下方输入 abc，验证控制栏外的键鼠仍被录制"), BorderLayout.NORTH);
                content.add(text, BorderLayout.CENTER);
                input.setContentPane(content);
                input.setSize(480, 160);
                input.setLocation(40, 100);
                GlobalScreen.addNativeMouseListener(new NativeMouseListener() {
                    @Override public void nativeMousePressed(NativeMouseEvent event) {
                        nativeWhen = event.getWhen();
                        nativeTick = Kernel32.INSTANCE.GetTickCount64();
                    }
                });
                Timer report = new Timer(150, event -> {
                    var status = recorder.getRecordingStatus();
                    var actions = recorder.getActions();
                    String types = actions.stream().collect(Collectors.groupingBy(action -> action.getType().name(),
                            java.util.TreeMap::new, Collectors.counting())).toString();
                    String state = "recording=" + status.recording() + "\npaused=" + status.paused()
                            + "\ncapturing=" + status.capturing() + "\nnodes=" + status.nodeCount()
                            + "\nactions=" + actions.size() + "\ntypes=" + types
                            + "\ncompact=" + safeField(frame, "compactRecording")
                            + "\nvisible=" + frame.isVisible() + "\nwindowState=" + frame.getExtendedState()
                            + "\nnativeWhen=" + nativeWhen + "\nnativeTick=" + nativeTick;
                    try { Files.writeString(Path.of("target/recording-control-live-state.txt"), state, StandardCharsets.UTF_8); }
                    catch (Exception failure) { throw new RuntimeException(failure); }
                });
                Runnable close = () -> {
                    report.stop();
                    try { recorder.stop(); if (GlobalScreen.isNativeHookRegistered()) GlobalScreen.unregisterNativeHook(); }
                    catch (Exception exception) { exception.printStackTrace(); }
                    for (Window window : Window.getWindows()) window.dispose();
                    System.exit(0);
                };
                WindowAdapter closing = new WindowAdapter() {
                    @Override public void windowClosing(WindowEvent event) { close.run(); }
                };
                frame.addWindowListener(closing);
                input.addWindowListener(closing);
                // 原生标题栏验收需要观察多次窗口切换，可显式延长测试时间；默认仍在三分钟后退出。
                int timeoutMillis = args.length > 0 && "--extended".equals(args[0]) ? 900_000 : 180_000;
                Timer timeout = new Timer(timeoutMillis, event -> close.run());
                timeout.setRepeats(false);
                timeout.start();
                report.start();
                frame.setVisible(true);
                input.setVisible(true);
            } catch (Exception exception) { exception.printStackTrace(); System.exit(1); }
        });
    }
    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static Object safeField(Object owner, String name) {
        try { return field(owner, name); } catch (Exception exception) { return exception.toString(); }
    }
}
