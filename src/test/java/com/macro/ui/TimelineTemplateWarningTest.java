package com.macro.ui;

import com.macro.flow.*;
import com.macro.storage.MacroStorage;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** 只读合成图片及离屏时间线，不启动录制、截图或真实输入。 */
class TimelineTemplateWarningTest {
    @Test void yellowCornerAndHoverExplainWarningWithoutMovingContentOrBlockingEditing() throws Exception {
        Path directory = directory();
        ImageIO.write(new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB), "png", directory.resolve("solid.png").toFile());
        ActionTimelinePanel timeline = create(directory, "solid.png");
        await(() -> warning(timeline) != null);
        SwingUtilities.invokeAndWait(() -> {
            try {
                JPanel card = card(timeline);
                Map<Component, Rectangle> geometry = geometry(card);
                int childCount = card.getComponentCount();
                BufferedImage warned = paint(card, 1.5);
                Field text = card.getClass().getDeclaredField("templateQualityWarning");
                text.setAccessible(true);
                String warning = (String) text.get(card);
                text.set(card, null);
                BufferedImage plain = paint(card, 1.5);
                text.set(card, warning);
                int x = (int) ((158 - 17) * 1.5), y = 15;
                Color triangle = new Color(warned.getRGB(x, y), true);
                assertTrue(triangle.getRed() > 200 && triangle.getGreen() > 150 && triangle.getBlue() < 120);
                assertNotEquals(plain.getRGB(x, y), warned.getRGB(x, y));
                assertEquals(plain.getRGB(15, 22), warned.getRGB(15, 22), "左上角编号保持原样");
                MouseEvent hover = mouse(card, MouseEvent.MOUSE_MOVED, 141, 15, 0);
                assertTrue(card.getToolTipText(hover).contains("颜色几乎一致"));
                assertNull(card.getToolTipText(mouse(card, MouseEvent.MOUSE_MOVED, 60, 60, 0)));
                assertEquals(new Dimension(158, 176), card.getSize());
                assertEquals(childCount, card.getComponentCount());
                assertEquals(geometry, geometry(card));
                AtomicInteger edits = new AtomicInteger();
                timeline.setOnActionDoubleClicked(node -> edits.incrementAndGet());
                card.dispatchEvent(mouse(card, MouseEvent.MOUSE_PRESSED, 141, 15, 1));
                card.dispatchEvent(mouse(card, MouseEvent.MOUSE_CLICKED, 141, 15, 2));
                assertEquals(1, edits.get(), "警告图标不能吞掉现有双击编辑事件");
                timeline.setEditingEnabled(false);
                assertNotNull(card.getToolTipText(hover), "只读播放浏览时也可查看提醒");
                if (Boolean.getBoolean("macro.template.warning.preview")) {
                    Path preview = Path.of(System.getProperty("macro.template.warning.previewDirectory"));
                    Files.createDirectories(preview);
                    ImageIO.write(warned, "png", preview.resolve("warning-card-150.png").toFile());
                    JToolTip tip = card.createToolTip();
                    tip.setTipText(warning);
                    tip.setSize(tip.getPreferredSize());
                    ImageIO.write(paint(tip, 1.5), "png", preview.resolve("warning-tooltip-150.png").toFile());
                }
            } catch (Exception exception) { throw new AssertionError(exception); }
        });
    }

    @Test void relativePathsEditsAndFileChangesRefreshWarningsWithoutMutatingFlow() throws Exception {
        Path directory = directory();
        Path template = directory.resolve("template.png");
        ImageIO.write(new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB), "png", template.toFile());
        ActionTimelinePanel timeline = create(directory, "template.png");
        await(() -> warning(timeline) != null && warning(timeline).contains("颜色几乎一致"));
        var flow = timeline.getFlow();
        AtomicInteger changes = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> timeline.setOnFlowChanged(changes::incrementAndGet));
        ImageIO.write(noise(), "png", template.toFile());
        Files.setLastModifiedTime(template, FileTime.fromMillis(System.currentTimeMillis() + 2000));
        SwingUtilities.invokeAndWait(timeline::refreshTimeline);
        await(() -> finished(timeline));
        assertNull(warning(timeline));
        assertSame(flow, timeline.getFlow());
        assertEquals("template.png", flow.getNodeMap().get("image").getImageCheckConfig().getImagePath());
        assertEquals(0, changes.get());
        // 相同节点 ID 换模板后，旧后台任务及缓存不能覆盖新卡片。
        SwingUtilities.invokeAndWait(() -> {
            timeline.setFlow(flow("missing.png"));
            timeline.setFlow(flow("template.png"));
        });
        await(() -> finished(timeline));
        assertNull(warning(timeline));
    }

    @Test void missingAndUnreadableTemplatesWarnButDoNotPreventSavingOrOrdinaryActions() throws Exception {
        Path directory = directory();
        ActionTimelinePanel timeline = create(directory, "missing.png");
        await(() -> warning(timeline) != null);
        assertTrue(warning(timeline).contains("不存在或无法读取"));
        MacroStorage storage = new MacroStorage();
        Path mr = directory.resolve("warning.mr");
        storage.save(MacroFlowFactory.extractActions(timeline.getFlow()), null, timeline.getFlow(), mr.toString());
        assertEquals("missing.png", storage.load(mr.toString()).getFlow().getNodeMap().get("image").getImageCheckConfig().getImagePath());
        Files.writeString(directory.resolve("broken.png"), "not an image");
        SwingUtilities.invokeAndWait(() -> timeline.setFlow(flow("broken.png")));
        await(() -> warning(timeline) != null);
        assertTrue(warning(timeline).contains("无法读取"));
        SwingUtilities.invokeAndWait(() -> timeline.setFlow(MacroFlowFactory.rebuildLinear(
                List.of(TimelineJumpLayoutTest.action("move")), "end")));
        await(() -> finished(timeline));
        assertNull(warning(timeline));
    }

    private static ActionTimelinePanel create(Path directory, String reference) throws Exception {
        AtomicReference<ActionTimelinePanel> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            ActionTimelinePanel timeline = new ActionTimelinePanel();
            timeline.setTemplateBaseDirectorySupplier(directory::toFile);
            timeline.setFlow(flow(reference));
            result.set(timeline);
        });
        return result.get();
    }

    private static MacroFlow flow(String reference) {
        MacroNode image = new MacroNode("image", NodeType.IMAGE_CHECK, null,
                new ImageCheckConfig(reference, .9), new ArrayList<>());
        return MacroFlowFactory.rebuildLinear(List.of(image), "end");
    }

    private static Path directory() throws Exception {
        Path root = Path.of(System.getProperty("macro.test.sandboxRoot", "target/test-sandboxes"));
        Files.createDirectories(root);
        return Files.createTempDirectory(root, "template-warning-").toAbsolutePath();
    }

    private static JPanel card(ActionTimelinePanel timeline) throws Exception {
        JPanel track = TimelineJumpLayoutTest.track(timeline);
        TimelineJumpLayoutTest.layoutTrack(track);
        return Arrays.stream(track.getComponents()).filter(c -> c.getClass().getSimpleName().equals("NodePanel"))
                .map(c -> (JPanel) c).findFirst().orElseThrow();
    }

    private static String warning(ActionTimelinePanel timeline) {
        AtomicReference<String> result = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try { result.set(card(timeline).getToolTipText(mouse(card(timeline), MouseEvent.MOUSE_MOVED, 141, 15, 0))); }
                catch (Exception exception) { throw new AssertionError(exception); }
            });
        } catch (Exception exception) { throw new AssertionError(exception); }
        return result.get();
    }

    private static boolean finished(ActionTimelinePanel timeline) {
        AtomicReference<Boolean> result = new AtomicReference<>(false);
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    Field loaderField = ActionTimelinePanel.class.getDeclaredField("templateQualityWarnings");
                    loaderField.setAccessible(true);
                    Object loader = loaderField.get(timeline);
                    Field workerField = loader.getClass().getDeclaredField("worker");
                    workerField.setAccessible(true);
                    SwingWorker<?, ?> worker = (SwingWorker<?, ?>) workerField.get(loader);
                    result.set(worker == null);
                } catch (Exception exception) { throw new AssertionError(exception); }
            });
        } catch (Exception exception) { throw new AssertionError(exception); }
        return result.get();
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        SwingUtilities.invokeAndWait(() -> { });
        assertTrue(condition.getAsBoolean(), "后台质量检查未完成");
    }

    private static MouseEvent mouse(JPanel card, int type, int x, int y, int clicks) {
        return new MouseEvent(card, type, 0, 0, x, y, clicks, false,
                type == MouseEvent.MOUSE_MOVED ? MouseEvent.NOBUTTON : MouseEvent.BUTTON1);
    }

    private static Map<Component, Rectangle> geometry(Component component) {
        Map<Component, Rectangle> result = new LinkedHashMap<>();
        result.put(component, component.getBounds());
        if (component instanceof Container container) for (Component child : container.getComponents()) result.putAll(geometry(child));
        return result;
    }

    private static BufferedImage paint(JComponent component, double scale) {
        BufferedImage image = new BufferedImage((int) Math.ceil(component.getWidth() * scale),
                (int) Math.ceil(component.getHeight() * scale), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try { graphics.scale(scale, scale); component.paint(graphics); }
        finally { graphics.dispose(); }
        return image;
    }

    private static BufferedImage noise() {
        BufferedImage image = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(883);
        for (int y = 0; y < 40; y++) for (int x = 0; x < 40; x++) image.setRGB(x, y, random.nextInt(0x1000000));
        return image;
    }
}
