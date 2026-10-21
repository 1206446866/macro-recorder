package com.macro.ui.capture;

import com.macro.config.AppConfig;
import com.macro.ui.MacroFrame;
import com.macro.recorder.MacroRecorder;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.Arrays;

/** 显式桌面验收夹具；独立绿色背景进程，配置及模板都在新建的隔离目录。 */
public final class CaptureDesktopAcceptance {
    private static final Color GREEN = new Color(19, 127, 61);
    private static Process background;
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--background")) {
            SwingUtilities.invokeLater(() -> {
                JFrame frame = new JFrame("Macro Recorder 截图验收背景");
                frame.setUndecorated(true); frame.setAlwaysOnTop(true); frame.setAutoRequestFocus(false);
                JPanel panel = new JPanel() {
                    @Override protected void paintComponent(Graphics g) {
                        super.paintComponent(g); g.setColor(Color.WHITE); g.setFont(new Font(Font.DIALOG, Font.BOLD, 18));
                        g.drawString("Macro Recorder 截图验收：绿色为专用测试背景", 25, 35);
                    }
                };
                panel.setBackground(GREEN); frame.setContentPane(panel);
                frame.setBounds(Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]), Integer.parseInt(args[4]));
                frame.setVisible(true);
                Timer timeout = new Timer(360000, event -> System.exit(0)); timeout.setRepeats(false); timeout.start();
            });
            return;
        }
        Path classes = Path.of(AppConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!classes.toString().contains("capture-live-")) throw new AssertionError("需要专用隔离类目录");
        GraphicsDevice device = MouseInfo.getPointerInfo().getDevice();
        Rectangle screen = device.getDefaultConfiguration().getBounds();
        String classpath = Arrays.stream(System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                .map(path -> Path.of(path).toAbsolutePath().toString()).collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
        background = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(), "-cp", classpath,
                CaptureDesktopAcceptance.class.getName(), "--background", ""+screen.x, ""+screen.y, ""+screen.width, ""+screen.height).start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> background.destroy()));
        Thread.sleep(700);
        SwingUtilities.invokeAndWait(() -> {
            MacroFrame frame = new MacroFrame();
            frame.setTitle("Macro Recorder 截图流程验收");
            frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            frame.addWindowListener(new WindowAdapter() { @Override public void windowClosed(WindowEvent e) { System.exit(0); } });
            SwingUtilities.invokeLater(() -> {
                try {
                    MacroRecorder recorder = (MacroRecorder) get(frame, "recorder");
                    Field state = MacroRecorder.class.getDeclaredField("state"); state.setAccessible(true);
                    state.set(recorder, Arrays.stream(state.getType().getEnumConstants()).filter(v -> v.toString().equals("PAUSED")).findFirst().orElseThrow());
                    set(frame, "recording", true);
                    ScreenCaptureController control = (ScreenCaptureController) get(frame, "recordingScreenCaptureController");
                    ScreenCaptureController.CaptureSource original = (ScreenCaptureController.CaptureSource) get(control, "captureSource");
                    set(control, "captureSource", (ScreenCaptureController.CaptureSource) (captureDevice, bounds) -> {
                        Rectangle[] bar = new Rectangle[1];
                        SwingUtilities.invokeAndWait(() -> bar[0] = frame.getBounds());
                        var captured = original.capture(captureDevice, bounds);
                        BufferedImage image = (BufferedImage) captured.getResolutionVariants().stream()
                                .max(java.util.Comparator.comparingLong(i -> (long)i.getWidth(null)*i.getHeight(null))).orElseThrow();
                        int residuals = 0;
                        for (int row=1;row<=4;row++) for (int column=1;column<=8;column++) {
                            int x=(int)Math.round((bar[0].x-bounds.x+bar[0].width*column/9.0)*image.getWidth()/bounds.width);
                            int y=(int)Math.round((bar[0].y-bounds.y+bar[0].height*row/5.0)*image.getHeight()/bounds.height);
                            if ((image.getRGB(x,y)&0xffffff)!=(GREEN.getRGB()&0xffffff)) residuals++;
                        }
                        System.out.println("desktop capture residual samples="+residuals+"/32, scale="+frame.getGraphicsConfiguration().getDefaultTransform().getScaleX());
                        return captured;
                    });
                    frame.setLocation(screen.x+60, screen.y+140); frame.setVisible(true);
                    invoke(frame, "enterCompactRecordingMode");
                    Timer log = new Timer(500, e -> {
                        try { Files.writeString(classes.getParent().resolve("state.txt"), "recording="+recorder.getRecordingStatus()
                                +", visible="+frame.isVisible()+", top="+frame.isAlwaysOnTop()+", bounds="+frame.getBounds()); }
                        catch (Exception ignored) { }
                    });
                    log.start();
                    Timer timeout = new Timer(360000, event -> System.exit(0)); timeout.setRepeats(false); timeout.start();
                } catch (Exception error) { error.printStackTrace(); System.exit(1); }
            });
        });
    }
    private static Object get(Object o,String name)throws Exception {Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static void set(Object o,String name,Object v)throws Exception {Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,v);}
    private static void invoke(Object o,String name)throws Exception {Method m=o.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(o);}
}
