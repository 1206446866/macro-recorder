package com.macro.ui;

import com.macro.config.AppConfig;
import com.macro.flow.*;
import com.macro.model.MacroAction;
import com.macro.ui.dialog.ImageRecognitionFrame;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** 隔离配置下的隐藏窗口生命周期检查；不显示窗口、不截图、不注册 Hook。 */
public final class ModelessActionEditorTest {
    public static void main(String[] args) {
        int exit = 0;
        try {
            Path classes = Path.of(AppConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            check(classes.toString().contains("test-sandboxes"), "必须隔离个人配置");
            SwingUtilities.invokeAndWait(() -> {
                try { verify(); } catch (Exception failure) { throw new AssertionError(failure); }
            });
            System.out.println("通过：非模态替换、旧回调拒绝、编号随顺序更新、旧目标关闭、主页入口移除、当前模板/阈值预载及编辑器释放识别窗口");
        } catch (Throwable failure) {
            failure.printStackTrace();
            exit = 1;
        }
        System.exit(exit);
    }

    private static void verify() throws Exception {
        MacroFrame frame = new MacroFrame();
        try {
            check(descendants(frame.getContentPane()).stream().noneMatch(c -> c instanceof JButton button
                    && "图像识别".equals(button.getText())), "主页仍有识别按钮");
            ActionTimelinePanel timeline = (ActionTimelinePanel) get(frame, "actionTimelinePanel");
            MacroNode first = action("first"), second = action("second");
            timeline.setFlow(MacroFlowFactory.rebuildLinear(List.of(first, second), "end"));
            invoke(frame, "syncActionsFromTimeline");
            AtomicInteger writes = new AtomicInteger();
            ActionEditorDialog previous = ActionEditorDialog.edit(frame, first, ignored -> writes.incrementAndGet(), null);
            previous.setAvailableBranchTargets(List.of(first, second));
            previous.setLocation(120, 130);
            frame.replaceTimelineEditor(previous);
            check(!previous.isModal(), "编辑窗口仍然模态");
            check("节点 01".equals(((JLabel) get(previous, "nodeNumberLabel")).getText()), "首节点编号错误");
            ActionEditorDialog current = ActionEditorDialog.edit(frame, second, ignored -> writes.incrementAndGet(), null);
            current.setAvailableBranchTargets(List.of(first, second));
            frame.replaceTimelineEditor(current);
            check(!previous.isDisplayable() && get(frame, "timelineEditorDialog") == current, "新编辑器未替换旧编辑器");
            check(current.getLocation().equals(new Point(120, 130)), "替换后没有沿用编辑位置");
            invoke(previous, "saveAction");
            check(writes.get() == 0, "已关闭编辑器仍然能保存");
            // 模拟旧窗口迟到的 windowClosed，不能清除后来安装的窗口。
            for (var listener : previous.getWindowListeners()) listener.windowClosed(
                    new java.awt.event.WindowEvent(previous, java.awt.event.WindowEvent.WINDOW_CLOSED));
            check(get(frame, "timelineEditorDialog") == current, "旧关闭事件清除了新编辑器");
            check(timeline.insertActionBefore(first, action("inserted")), "插入失败");
            invoke(frame, "syncActionsFromTimeline");
            check("节点 03".equals(((JLabel) get(current, "nodeNumberLabel")).getText()), "插入后编号未更新");
            check("second".equals(second.getId()), "显示编号改写了节点 ID");
            timeline.deleteAction(first, false);
            invoke(frame, "syncActionsFromTimeline");
            check("节点 02".equals(((JLabel) get(current, "nodeNumberLabel")).getText()), "删除后编号未更新");
            timeline.deleteAction(second, false);
            invoke(frame, "syncActionsFromTimeline");
            check(!current.isDisplayable() && get(frame, "timelineEditorDialog") == null, "删除编辑目标后残留窗口");
            ActionEditorDialog creator = ActionEditorDialog.create(frame, ignored -> writes.incrementAndGet());
            frame.replaceTimelineEditor(creator);
            timeline.setFlow(MacroFlowFactory.fromActions(List.of()));
            invoke(frame, "syncActionsFromTimeline");
            check(!creator.isDisplayable(), "换宏后旧创建窗口没有关闭");
            invoke(creator, "saveAction");
            check(writes.get() == 0, "旧创建窗口仍能写入新宏");

            Path directory = Files.createTempDirectory(Path.of("target"), "editor-recognition-");
            Path imageFile = directory.resolve("template.png");
            ImageIO.write(new BufferedImage(7, 9, BufferedImage.TYPE_INT_RGB), "png", imageFile.toFile());
            ImageCheckConfig config = new ImageCheckConfig("template.png", .37);
            MacroNode image = new MacroNode("image", NodeType.IMAGE_CHECK, null, config, new ArrayList<>());
            ActionEditorDialog editor = ActionEditorDialog.edit(frame, image, directory.toFile(),
                    ignored -> writes.incrementAndGet(), null);
            try {
                Object panel = get(editor, "currentPropertyPanel");
                check(((JButton) get(panel, "recognizeButton")).isEnabled(), "有模板时识别入口仍禁用");
                ImageRecognitionFrame preview = (ImageRecognitionFrame) invoke(panel, "prepareRecognitionTest");
                check(((JLabel) get(preview, "templateNameValue")).getText().equals("template.png"), "没有默认载入当前模板");
                check(((BufferedImage) get(preview, "templateImage")).getWidth() == 7, "模板内容未载入");
                check(((Number) ((JSpinner) get(preview, "thresholdSpinner")).getValue()).intValue() == 37,
                        "未使用动作阈值");
                check(!(boolean) get(preview, "recognizing"), "打开预览自动开始截图");
                check(writes.get() == 0 && config.getImagePath().equals("template.png"), "预览修改了宏");
                preview.addNotify(); // 创建正常显示会创建的原生句柄，但保持不可见。
                check(invoke(panel, "prepareRecognitionTest") == preview, "同一编辑器重复打开产生多个识别窗口");
                editor.dispose();
                check(!preview.isDisplayable(), "关闭编辑器后识别窗口仍残留");
            } finally { editor.dispose(); }
            ActionEditorDialog locked = ActionEditorDialog.edit(frame, image, ignored -> writes.incrementAndGet(), null);
            frame.replaceTimelineEditor(locked);
            invoke(frame, "lockTimelineEditing");
            check(!locked.isDisplayable(), "录制/播放前未关闭非模态窗口");
            invoke(locked, "saveAction");
            check(writes.get() == 0, "锁定后旧保存仍生效");
        } finally { frame.dispose(); }
    }

    private static MacroNode action(String id) {
        return new MacroNode(id, NodeType.ACTION, MacroAction.mouseMove(1, 2, 0), new ArrayList<>());
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object invoke(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static List<Component> descendants(Container parent) {
        List<Component> result = new ArrayList<>();
        for (Component component : parent.getComponents()) {
            result.add(component);
            if (component instanceof Container child) result.addAll(descendants(child));
        }
        return result;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
