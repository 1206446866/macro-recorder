package com.macro.recognition;

import com.macro.flow.FlowContext;
import com.macro.flow.ImageCheckConfig;
import com.macro.flow.ImageMatchResult;
import com.macro.flow.MacroNode;
import com.macro.flow.NodeType;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/** 在受控测试窗口中实际截屏，验证诊断不改变 OpenCV 结果，且诊断保存失败不会中断播放。 */
public final class ImageRecognitionDiagnosticsTest {

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory(Path.of("target"), "image-diagnostics-test-");
        Path captureDirectory = directory.resolve("captures");
        String previousCapture = System.getProperty("macro.image.diagnostics.capture");
        String previousDirectory = System.getProperty("macro.image.diagnostics.directory");
        String previousEnabled = System.getProperty("macro.image.diagnostics.enabled");
        PrintStream originalOut = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        JFrame frame = new JFrame("识别诊断测试画面");
        FixturePanel panel = new FixturePanel();
        try {
            System.setProperty("macro.image.diagnostics.enabled", "true");
            System.clearProperty("macro.image.diagnostics.capture");
            System.setProperty("macro.image.diagnostics.directory", captureDirectory.toString());
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            SwingUtilities.invokeAndWait(() -> {
                frame.setContentPane(panel);
                frame.pack();
                Rectangle display = frame.getGraphicsConfiguration().getBounds();
                frame.setLocation(display.x + 40, display.y + 40);
                frame.setAlwaysOnTop(true);
                frame.setVisible(true);
            });
            Thread.sleep(350L);

            // 从真实屏幕截取测试图块，避免系统缩放对测试模板本身造成额外缩放误差。
            Point[] location = new Point[1];
            SwingUtilities.invokeAndWait(() -> location[0] = panel.getLocationOnScreen());
            ScreenRecognitionService service = new ScreenRecognitionService();
            var snapshot = service.captureScreens().stream()
                    .filter(screen -> screen.logicalBounds().contains(location[0]))
                    .findFirst().orElseThrow();
            double scaleX = snapshot.image().getWidth() / (double) snapshot.logicalBounds().width;
            double scaleY = snapshot.image().getHeight() / (double) snapshot.logicalBounds().height;
            BufferedImage template = snapshot.image().getSubimage(
                    (int) Math.round((location[0].x - snapshot.logicalBounds().x + 32) * scaleX),
                    (int) Math.round((location[0].y - snapshot.logicalBounds().y + 32) * scaleY),
                    (int) Math.round(64 * scaleX), (int) Math.round(48 * scaleY));
            Path templateFile = directory.resolve("template.png");
            ImageIO.write(template, "png", templateFile.toFile());
            MacroNode node = new MacroNode("diagnostic-image-node", NodeType.IMAGE_CHECK,
                    null, new ImageCheckConfig("template.png", 0.99), new ArrayList<>());
            OpenCvImageMatcher matcher = new OpenCvImageMatcher(() -> directory.toFile());

            // 同一份屏幕和模板先经过测试窗口使用的服务，再经过播放匹配器。
            var testReport = service.recognize(List.of(snapshot), template, 0.99,
                    ScreenRecognitionService.RecognitionEngine.OPEN_CV);
            check(!testReport.matches().isEmpty(), "测试服务未命中受控模板：" + testReport.bestCandidate());
            ImageMatchResult visible = matcher.match(node, new FlowContext());
            check(visible.isFound(), "实际播放匹配器未命中可见模板");
            check(!Files.exists(captureDirectory), "默认关闭时创建了诊断截图目录");

            System.setProperty("macro.image.diagnostics.capture", "true");
            ImageMatchResult saved = matcher.match(node, new FlowContext());
            check(saved.isFound(), "开启截图诊断改变了识别结果");
            try (var files = Files.walk(captureDirectory)) {
                Path metadata = files.filter(path -> path.getFileName().toString().equals("diagnostics.txt"))
                        .findFirst().orElseThrow();
                String details = Files.readString(metadata);
                check(details.contains("NodeId: diagnostic-image-node"), "缺少节点 ID");
                check(details.contains(templateFile.toAbsolutePath().normalize().toString()), "相对模板路径未正确记录");
                check(Files.isRegularFile(metadata.resolveSibling("screen-1.png")), "未保存实际屏幕图像");
                BufferedImage savedTemplate = ImageIO.read(metadata.resolveSibling("template.png").toFile());
                check(savedTemplate.getRGB(10, 10) == template.getRGB(10, 10), "保存的模板不是参与识别的像素");
            }

            // 将诊断目录指向普通文件，模拟目录无法写入；识别本身仍应成功。
            Path blockedDirectory = directory.resolve("not-a-directory");
            Files.writeString(blockedDirectory, "测试诊断目录不可写");
            System.setProperty("macro.image.diagnostics.directory", blockedDirectory.toString());
            check(matcher.match(node, new FlowContext()).isFound(), "诊断写入失败破坏了匹配结果");
            System.clearProperty("macro.image.diagnostics.capture");

            SwingUtilities.invokeAndWait(() -> {
                panel.showPattern = false;
                panel.repaint();
            });
            Thread.sleep(350L);
            check(!matcher.match(node, new FlowContext()).isFound(), "被遮盖的模板仍然命中");

            node.getImageCheckConfig().setImagePath("missing-template.png");
            try {
                matcher.match(node, new FlowContext());
                throw new AssertionError("模板读取异常被诊断代码吞掉");
            } catch (IOException expected) {
                check(expected.getMessage().contains("missing-template.png"), "原异常信息丢失");
            }
            String logs = output.toString(StandardCharsets.UTF_8);
            for (String expected : List.of("MATCH_START", "Threshold: 0.990000", "Engine: OPEN_CV",
                    "ForegroundBeforeCapture:", "Scale=", "Found: true", "Found: false",
                    "BestCandidateSimilarity:", "MatchBounds:", "MATCH_ERROR")) {
                check(logs.contains(expected), "缺少诊断字段：" + expected);
            }
        } finally {
            System.setOut(originalOut);
            originalOut.print(output.toString(StandardCharsets.UTF_8));
            SwingUtilities.invokeAndWait(frame::dispose);
            restoreProperty("macro.image.diagnostics.capture", previousCapture);
            restoreProperty("macro.image.diagnostics.directory", previousDirectory);
            restoreProperty("macro.image.diagnostics.enabled", previousEnabled);
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        System.out.println("通过：实际屏幕 OpenCV 命中/未命中、相对路径、默认不保存截图、按需保存及诊断失败隔离");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) System.clearProperty(name);
        else System.setProperty(name, value);
    }

    /** 图块显示与遮盖不改变窗口位置，确保两次识别仅有屏幕内容不同。 */
    private static final class FixturePanel extends JPanel {
        private final BufferedImage pattern = new BufferedImage(128, 96, BufferedImage.TYPE_INT_RGB);
        private boolean showPattern = true;

        private FixturePanel() {
            setPreferredSize(new Dimension(200, 160));
            Random random = new Random(20261017L);
            for (int y = 0; y < pattern.getHeight(); y++) {
                for (int x = 0; x < pattern.getWidth(); x++) pattern.setRGB(x, y, random.nextInt(0x1000000));
            }
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (showPattern) graphics.drawImage(pattern, 20, 20, null);
        }
    }
}
