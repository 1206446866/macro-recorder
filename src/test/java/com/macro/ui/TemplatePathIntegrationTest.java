package com.macro.ui;

import com.macro.config.AppConfig;
import com.macro.flow.*;
import com.macro.model.MacroAction;
import com.macro.model.TargetWindow;
import com.macro.player.MacroPlayer;
import com.macro.player.PlayerState;
import com.macro.recognition.OpenCvImageMatcher;
import com.macro.storage.MacroStorage;
import com.macro.util.ImageTemplatePathUtil;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.AWTException;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** FS-01 隔离回归：只写自己的临时 MR/PNG，不截屏、不发送真实键鼠输入。 */
public final class TemplatePathIntegrationTest {
    public static void main(String[] args) throws Exception {
        guard();
        Path old = Files.createDirectories(Path.of("target", "旧 宏目录")).toAbsolutePath();
        Path next = Files.createDirectories(Path.of("target", "新 宏目录")).toAbsolutePath();
        Path template = png(old.resolve("template.png"), 0x123456);
        Path other = png(next.resolve("template.png"), 0x654321);
        byte[] originalPng = Files.readAllBytes(template), otherPng = Files.readAllBytes(other);
        for (String reference : List.of("template.png", template.toString(), "sub/../template.png")) {
            Path expected = ImageTemplatePathUtil.resolve(reference, old.toFile());
            check(expected.equals(template), "绝对/相对路径解析不一致");
            check(invoke(new OpenCvImageMatcher(old::toFile), "resolvePath",
                    new Class<?>[]{String.class}, reference).equals(expected), "播放器未使用统一路径入口");
        }
        check(ImageTemplatePathUtil.resolve("template.png", null)
                .equals(Path.of("template.png").toAbsolutePath()), "未保存宏的工作目录基准改变");

        MacroFlow original = flow("template.png");
        MacroStorage storage = new MacroStorage();
        Path oldMr = old.resolve("legacy.mr");
        storage.save(MacroFlowFactory.extractActions(original), null, original, oldMr.toString());
        byte[] oldBytes = Files.readAllBytes(oldMr);
        MacroFlow loaded = storage.load(oldMr.toString()).getFlow();
        check(path(loaded).equals("template.png"), "历史 MR 相对引用被加载时重写");
        new OpenCvImageMatcher(old::toFile).validate(loaded.getNodeMap().get("image"));
        check(ImageTemplatePathUtil.forSave(loaded, old.toFile(), old.resolve("same.mr").toFile()) == loaded,
                "同目录保存不应改写相对引用");
        MacroFlow moved = ImageTemplatePathUtil.forSave(loaded, old.toFile(), next.resolve("new.mr").toFile());
        check(path(moved).equals(template.toString()) && path(loaded).equals("template.png"),
                "跨目录另存为未保留原模板，或提前修改原流程");
        check(moved.getStartId().equals(loaded.getStartId())
                && moved.getNodeMap().keySet().equals(loaded.getNodeMap().keySet())
                && moved.getNodeMap().get("image").getChildren().equals(loaded.getNodeMap().get("image").getChildren())
                && MacroFlowFactory.extractActions(moved).equals(MacroFlowFactory.extractActions(loaded)),
                "路径转换改变了其他动作或分支");
        storage.save(MacroFlowFactory.extractActions(moved), null, moved, next.resolve("new.mr").toString());
        MacroFlow reloaded = storage.load(next.resolve("new.mr").toString()).getFlow();
        check(ImageTemplatePathUtil.resolve(path(reloaded), next.toFile()).equals(template), "另存为指向同名错误图片");
        MacroFlow absolute = flow(template.toString());
        check(ImageTemplatePathUtil.forSave(absolute, old.toFile(), next.resolve("absolute.mr").toFile()) == absolute,
                "绝对模板引用不应改变");
        MacroFlow missing = ImageTemplatePathUtil.forSave(flow("missing.png"), old.toFile(), next.resolve("missing.mr").toFile());
        storage.save(MacroFlowFactory.extractActions(missing), null, missing, next.resolve("missing.mr").toString());
        check(path(storage.load(next.resolve("missing.mr").toString()).getFlow()).equals(old.resolve("missing.png").toString()),
                "缺失模板不能被保存/加载，或其原引用被清空");
        check(Arrays.equals(oldBytes, Files.readAllBytes(oldMr))
                && Arrays.equals(originalPng, Files.readAllBytes(template))
                && Arrays.equals(otherPng, Files.readAllBytes(other)), "另存为修改了原 MR 或图像");
        System.out.println("通过：统一解析、未保存基准、旧MR相对路径、跨目录同名模板、缺失模板保留、动作/分支及原素材保全");
    }

    public static final class Desktop {
        public static void main(String[] args) {
            // 真实主窗口会初始化 AWT/原生后台设施；此入口仅运行在独立测试子 JVM。
            try {
                run();
            } catch (Throwable failure) {
                failure.printStackTrace();
                System.exit(1);
                return;
            }
            System.exit(0);
        }

        private static void run() throws Exception {
            guard();
            Path old = Files.createTempDirectory("template-paths-");
            Path next = Files.createTempDirectory("template-save-as-");
            Path template = png(old.resolve("template.png"), 0x123456);
            png(next.resolve("template.png"), 0x654321);
            SwingUtilities.invokeAndWait(() -> {
                try {
                    for (String reference : List.of("template.png", template.toString(), "missing.png")) {
                        AtomicReference<MacroNode> saved = new AtomicReference<>();
                        ActionEditorDialog editor = ActionEditorDialog.edit(null,
                                flow(reference).getNodeMap().get("image"), old.toFile(), saved::set, ignored -> { });
                        try {
                            Object panel = field(editor, "currentPropertyPanel");
                            File selected = (File) field(panel, "templateFile");
                            check(selected.toPath().equals(ImageTemplatePathUtil.resolve(reference, old.toFile())),
                                    "编辑器与播放器的模板解析不一致");
                            invoke(editor, "saveAction", new Class<?>[0]);
                            check(saved.get() != null && saved.get().getImageCheckConfig().getImagePath().equals(selected.toString()),
                                    "编辑器无法保留缺失模板或保存路径不一致");
                        } finally { editor.dispose(); }
                    }
                    frameSave(old, next);
                } catch (Exception exception) { throw new AssertionError(exception); }
            });
            for (String missing : new String[]{"missing.png", "", null, old.toString()}) {
                Playback fixture = new Playback(old);
                fixture.rejected(flow(missing), "image");
            }
            // 简单明确地预检全部 IMAGE_CHECK，即使前一图像的终止分支使其运行时不可达。
            MacroFlow late = flow("template.png");
            late.getNodeMap().put("unreachable", image("unreachable", "missing.png"));
            new Playback(old).rejected(late, "unreachable");
            Playback valid = new Playback(old);
            valid.player.play(flow("template.png"), 1, valid.matcher);
            check(valid.inputs.get() == 1 && valid.matches.get() == 1 && valid.player.getState() == PlayerState.IDLE,
                    "合法模板影响正常输入或图像节点执行");
            System.out.println("通过：实际未显示编辑器/主窗口保存、保存失败不换基准、缺失/空/目录模板在任何输入前拒绝、全部图像预检及合法播放");
        }
    }

    /** 实际调用主窗口保存方法，未显示窗口；配置通过 TEST-01 隔离。 */
    private static void frameSave(Path old, Path next) throws Exception {
        MacroFrame frame = new MacroFrame();
        try {
            MacroFlow original = flow("template.png");
            set(frame, "macroFlow", original);
            set(frame, "actions", MacroFlowFactory.extractActions(original));
            File oldFile = old.resolve("original.mr").toFile();
            set(frame, "currentFile", oldFile);
            ((ActionTimelinePanel) field(frame, "actionTimelinePanel")).setFlow(original);
            File destination = next.resolve("frame.mr").toFile();
            invoke(frame, "saveMacroToFile", new Class<?>[]{File.class}, destination);
            check(field(frame, "currentFile").equals(destination), "保存成功未更新 currentFile");
            MacroFlow saved = (MacroFlow) field(frame, "macroFlow");
            check(path(saved).equals(old.resolve("template.png").toString())
                    && path(new MacroStorage().load(destination.toString()).getFlow()).equals(path(saved)),
                    "主窗口改变 currentFile 的顺序导致引用错位");
            check(((ActionTimelinePanel) field(frame, "actionTimelinePanel")).getFlow() == saved,
                    "保存后时间线仍持有旧相对路径");
            set(frame, "macroFlow", original);
            set(frame, "currentFile", oldFile);
            set(frame, "storage", new MacroStorage() {
                @Override public void save(List<MacroAction> actions, TargetWindow target, MacroFlow flow, String file)
                        throws IOException { throw new IOException("模拟保存失败"); }
            });
            invoke(frame, "saveMacroToFile", new Class<?>[]{File.class}, next.resolve("failed.mr").toFile());
            check(field(frame, "macroFlow") == original && field(frame, "currentFile").equals(oldFile)
                    && path(original).equals("template.png"), "保存失败提前改变当前文件或原模板引用");
        } finally { frame.dispose(); }
    }

    private static final class Playback {
        final MacroPlayer player = new MacroPlayer();
        final AtomicInteger inputs = new AtomicInteger(), matches = new AtomicInteger(), changes = new AtomicInteger();
        final ImageMatcher matcher;
        Playback(Path base) throws Exception {
            set(player, "robot", new CountingRobot(inputs));
            check(field(player, "robot") instanceof CountingRobot, "模拟执行器未安装，拒绝播放");
            player.setOnStateChange(state -> changes.incrementAndGet());
            OpenCvImageMatcher real = new OpenCvImageMatcher(base::toFile);
            matcher = new ImageMatcher() {
                @Override public void validate(MacroNode node) throws Exception { real.validate(node); }
                @Override public ImageMatchResult match(MacroNode node, FlowContext context) {
                    matches.incrementAndGet();
                    return ImageMatchResult.notFound();
                }
            };
        }
        void rejected(MacroFlow flow, String nodeId) throws Exception {
            try {
                player.play(flow, 1, matcher);
                throw new AssertionError("缺失模板未在播放前拒绝");
            } catch (IOException | IllegalArgumentException expected) {
                check(expected.getMessage().contains(nodeId), "错误缺少图像节点信息");
                String path = flow.getNodeMap().get(nodeId).getImageCheckConfig().getImagePath();
                if (path != null && !path.isBlank()) check(expected.getMessage().contains(path), "错误缺少模板路径");
            }
            check(inputs.get() == 0 && matches.get() == 0 && changes.get() == 0
                    && player.getState() == PlayerState.IDLE, "预检失败前已执行输入、匹配或进入 PLAYING");
        }
    }

    private static final class CountingRobot extends Robot {
        private final AtomicInteger calls;
        CountingRobot(AtomicInteger calls) throws AWTException { this.calls = calls; }
        @Override public void keyPress(int key) { calls.incrementAndGet(); }
        @Override public void keyRelease(int key) { calls.incrementAndGet(); }
        @Override public void mouseMove(int x, int y) { calls.incrementAndGet(); }
        @Override public void mousePress(int buttons) { calls.incrementAndGet(); }
        @Override public void mouseRelease(int buttons) { calls.incrementAndGet(); }
        @Override public void mouseWheel(int amount) { calls.incrementAndGet(); }
    }

    private static MacroFlow flow(String reference) {
        return MacroFlowFactory.rebuildLinear(List.of(new MacroNode("action", NodeType.ACTION,
                MacroAction.mouseMove(0, 0, 0), new ArrayList<>()), image("image", reference)), "end");
    }
    private static MacroNode image(String id, String reference) {
        ImageCheckConfig config = new ImageCheckConfig(reference, 0.93);
        config.setSuccessBehavior(ImageCheckBehavior.TERMINATE);
        config.setFailureBehavior(ImageCheckBehavior.TERMINATE);
        config.setRetryDelay(77L);
        return new MacroNode(id, NodeType.IMAGE_CHECK, null, config, new ArrayList<>());
    }
    private static String path(MacroFlow flow) { return flow.getNodeMap().get("image").getImageCheckConfig().getImagePath(); }
    private static Path png(Path path, int color) throws IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, color);
        ImageIO.write(image, "png", path.toFile());
        return path.toAbsolutePath().normalize();
    }
    private static void guard() throws Exception {
        Path actual = Path.of(AppConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        check(actual.equals(Path.of("classes").toRealPath()), "必须通过隔离入口运行，拒绝访问个人配置");
    }
    private static Object field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static void set(Object object, String name, Object value) throws Exception {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
    private static Object invoke(Object object, String name, Class<?>[] types, Object... args) throws Exception {
        var method = object.getClass().getDeclaredMethod(name, types); method.setAccessible(true);
        try { return method.invoke(object, args); }
        catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof Exception cause) throw cause;
            throw (Error) exception.getCause();
        }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
