package com.macro.ui.filedialog;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 人工或桌面自动化验证原生面板；只选择路径，不写入或覆盖被选择的文件。 */
public final class NativeFileDialogSmokeTest {
    public static void main(String[] args) throws Exception {
        boolean unowned = args.length > 0 && args[0].equals("--unowned");
        Path root = Path.of("target/native-file-dialog-smoke").toAbsolutePath();
        Files.createDirectories(root.resolve("默认 图片目录"));
        Files.writeString(root.resolve("示例 宏.mr"), "原生面板测试文件，不执行宏播放");
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png",
                root.resolve("模板 图像.png").toFile());
        var open = FileDialogOptions.builder(FileDialogOptions.Mode.OPEN_FILE)
                .title("原生验证 1：打开 MR").initialFile(root.resolve("示例 宏.mr").toFile())
                .fileType("宏文件 (*.mr)", "mr").allowAllFiles(true).build();
        var save = FileDialogOptions.builder(FileDialogOptions.Mode.SAVE_FILE)
                .title("原生验证 2：保存 MR").initialDirectory(root.toFile()).fileName("新的 宏")
                .fileType("宏文件 (*.mr)", "mr").defaultExtension("mr").build();
        var folder = FileDialogOptions.builder(FileDialogOptions.Mode.SELECT_DIRECTORY)
                .title("原生验证 3：选择目录").initialDirectory(root.resolve("默认 图片目录").toFile()).build();
        var image = FileDialogOptions.builder(FileDialogOptions.Mode.OPEN_FILE)
                .title("原生验证 4：选择 PNG").initialFile(root.resolve("模板 图像.png").toFile())
                .fileType("PNG 图片 (*.png)", "png").build();
        var overwrite = FileDialogOptions.builder(FileDialogOptions.Mode.SAVE_FILE)
                .title("原生验证 5：覆盖确认").initialFile(root.resolve("示例 宏.mr").toFile())
                .fileType("宏文件 (*.mr)", "mr").defaultExtension("mr").build();
        var cancel = FileDialogOptions.builder(FileDialogOptions.Mode.OPEN_FILE)
                .title("原生验证 6：取消选择").initialDirectory(root.toFile())
                .fileType("图片", "png", "jpg", "jpeg", "bmp", "gif", "webp").build();
        SwingUtilities.invokeAndWait(() -> {
            JFrame owner = new JFrame("原生文件面板验证");
            JLabel heartbeat = new JLabel("等待文件面板");
            owner.add(heartbeat);
            owner.setSize(1250, 850);
            owner.setLocation(0, 0);
            owner.setVisible(true);
            int[] ticks = {0};
            Timer timer = new Timer(100, event -> heartbeat.setText("界面重绘计数：" + ++ticks[0]));
            timer.start();
            try {
                FileDialogService service = FileDialogServiceFactory.create();
                List<FileDialogOptions> cases = List.of(open, save, folder, image, overwrite, cancel);
                for (int index = 0; index < cases.size(); index++) {
                    int before = ticks[0];
                    var selected = service.showDialog(unowned ? null : owner, cases.get(index));
                    System.out.println("CASE " + (index + 1) + ": " + selected
                            + "，EDT 重绘次数=" + (ticks[0] - before));
                    if (ticks[0] <= before) throw new AssertionError("原生面板阻塞了 EDT");
                }
                System.out.println("原生面板验证完成；未执行任何保存/覆盖操作");
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            } finally {
                timer.stop();
                owner.dispose();
            }
        });
    }
}
