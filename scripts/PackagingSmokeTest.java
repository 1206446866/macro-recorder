import com.fasterxml.jackson.databind.ObjectMapper;
import com.formdev.flatlaf.FlatDarkLaf;
import com.github.kwhat.jnativehook.GlobalScreen;
import com.macro.constant.ApplicationInfo;
import com.macro.recognition.OpenCvTemplateMatcher;
import com.macro.ocr.OcrLanguage;
import com.macro.ocr.OcrService;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;

import javax.imageio.ImageIO;
import javax.net.ssl.SSLContext;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.Color;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.EnumSet;
import java.nio.file.Path;

/** 在精简发行包的 Java 中检查实际运行依赖，不启动主窗口或发送键鼠输入。 */
public final class PackagingSmokeTest {
    private PackagingSmokeTest() { }

    public static void main(String[] args) throws Exception {
        check(ApplicationInfo.APP_VERSION.equals(args[0]), "软件版本与发行包版本一致");
        check(Kernel32.INSTANCE.GetCurrentProcessId() > 0
                && User32.INSTANCE.GetDesktopWindow() != null, "JNA Windows 本机库加载");
        GlobalScreen.getAutoRepeatRate();
        check(!GlobalScreen.isNativeHookRegistered(), "JNativeHook 本机库加载（未注册监听）");
        checkJson();
        checkSwing();
        checkImages();
        checkRecognition();
        checkOcr();
        Class.forName("sun.misc.Unsafe");
        Class.forName("java.sql.DriverManager");
        SSLContext.getDefault();
        check(Charset.forName("GBK") != null, "反射支持、字符集及安全提供者");
        System.out.println("打包基础检查：9 项通过");
    }

    private static void checkJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(Map.of("name", "Macro Recorder", "version", ApplicationInfo.APP_VERSION));
        check("Macro Recorder".equals(mapper.readTree(json).get("name").asText()), "JSON 保存与读取");
    }

    private static void checkSwing() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            check(FlatDarkLaf.setup(), "Swing 深色界面与字体绘制");
            JPanel panel = new JPanel();
            panel.add(new JButton("Macro Recorder"));
            panel.add(new JComboBox<>(new String[]{"01", "02"}));
            panel.setSize(320, 80);
            panel.doLayout();
            BufferedImage image = new BufferedImage(320, 80, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try { panel.paint(graphics); } finally { graphics.dispose(); }
        });
    }

    private static void checkImages() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean written = ImageIO.write(image, "png", bytes);
        check(written && ImageIO.read(new ByteArrayInputStream(bytes.toByteArray())) != null
                && ImageIO.getImageReadersByFormatName("WebP").hasNext(), "PNG 读写与 WebP 插件发现");
    }

    private static void checkRecognition() {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) image.setRGB(x, y, (x * 23 << 16) | (y * 27 << 8) | (x * y * 3));
        }
        BufferedImage template = image.getSubimage(3, 2, 3, 3);
        var results = new OpenCvTemplateMatcher().findAll(image, template, 1.0, 10);
        check(results.matches().stream().anyMatch(match -> match.pixelBounds().equals(new Rectangle(3, 2, 3, 3))),
                "裁剪后的 OpenCV 加载与合成图像识别");
    }

    private static void checkOcr() throws Exception {
        // 构建脚本已将临时目录隔离在本轮输出目录，不向待发布应用写入展开缓存。
        System.setProperty("macro.ocr.cacheDirectory", Path.of(System.getProperty("java.io.tmpdir"), "ocr-cache").toString());
        BufferedImage image = new BufferedImage(500, 90, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 500, 90);
        graphics.setColor(Color.BLACK); graphics.setFont(new Font("Arial", Font.PLAIN, 42));
        graphics.drawString("HELLO 12345", 15, 60); graphics.dispose();
        String text = new OcrService().extract(image, EnumSet.allOf(OcrLanguage.class), true, () -> false);
        check(text.contains("HELLO") && text.contains("12345"), "离线 OCR 本机库、五种语言包及文字提取");
    }

    private static void check(boolean success, String description) {
        if (!success) throw new AssertionError(description);
        System.out.println("[通过] " + description);
    }
}
