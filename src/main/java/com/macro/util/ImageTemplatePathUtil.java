package com.macro.util;

import com.macro.flow.ImageCheckConfig;
import com.macro.flow.MacroFlow;
import com.macro.flow.MacroNode;
import com.macro.flow.NodeType;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** 统一模板引用的 MR 目录基准；没有已保存宏时沿用工作目录。 */
public final class ImageTemplatePathUtil {
    /** 模板路径规则由静态入口统一提供，无需创建实例。 */
    private ImageTemplatePathUtil() { }

    /** 保留绝对路径，或按当前 MR 所在目录解析相对路径，并规范化其中的目录段。 */
    public static Path resolve(String imagePath, File macroDirectory) {
        if (imagePath == null || imagePath.isBlank()) {
            throw new IllegalArgumentException("模板路径不能为空");
        }
        Path path = Path.of(imagePath);
        return (path.isAbsolute() ? path : basePath(macroDirectory).resolve(path)).normalize();
    }

    /** 只检查文件引用，不解码图片或截屏；错误保留对应节点和最终路径。 */
    public static Path requireTemplate(MacroNode node, File macroDirectory) throws IOException {
        ImageCheckConfig config = node.getImageCheckConfig();
        if (config == null || config.getImagePath() == null || config.getImagePath().isBlank()) {
            throw new IllegalArgumentException("图像识别节点 " + node.getId() + " 缺少模板路径");
        }
        Path path;
        try {
            path = resolve(config.getImagePath(), macroDirectory);
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("图像识别节点 " + node.getId()
                    + " 的模板路径无效：" + config.getImagePath(), exception);
        }
        if (!Files.isRegularFile(path)) {
            throw new IOException("图像识别节点 " + node.getId() + " 的模板图片不存在或不是文件：" + path);
        }
        return path;
    }

    /**
     * 跨目录保存时按旧基准固定相对引用；不检查存在性，不改写原流程或复制素材。
     * 调用方仅在持久化成功后采用返回流程，保存失败则保留全部原引用。
     */
    public static MacroFlow forSave(MacroFlow flow, File oldDirectory, File destination) {
        if (flow == null || basePath(oldDirectory).equals(
                destination.toPath().toAbsolutePath().normalize().getParent())) return flow;
        Map<String, MacroNode> nodes = new LinkedHashMap<>(flow.getNodeMap());
        boolean changed = false;
        for (Map.Entry<String, MacroNode> entry : nodes.entrySet()) {
            MacroNode node = entry.getValue();
            if (node == null || node.getType() != NodeType.IMAGE_CHECK) continue;
            ImageCheckConfig config = node.getImageCheckConfig();
            if (config == null || config.getImagePath() == null || config.getImagePath().isBlank()
                    || Path.of(config.getImagePath()).isAbsolute()) continue;
            ImageCheckConfig copy = new ImageCheckConfig(
                    resolve(config.getImagePath(), oldDirectory).toString(), config.getThreshold());
            copy.setSuccessBehavior(config.getSuccessBehavior());
            copy.setFailureBehavior(config.getFailureBehavior());
            copy.setRetryDelay(config.getRetryDelay());
            copy.setScaleMatchingEnabled(config.isScaleMatchingEnabled());
            copy.setClickMode(config.getClickMode());
            copy.setFailureClickX(config.getFailureClickX());
            copy.setFailureClickY(config.getFailureClickY());
            copy.setSearchScope(config.getSearchScope());
            copy.setSearchWindow(config.getSearchWindow());
            entry.setValue(new MacroNode(node.getId(), node.getType(), node.getAction(), copy, node.getChildren()));
            changed = true;
        }
        return changed ? new MacroFlow(flow.getStartId(), nodes, flow.getHeadWaitMillis()) : flow;
    }

    /** 取得绝对且规范化的基准目录；宏尚未保存时沿用当前工作目录。 */
    private static Path basePath(File directory) {
        return (directory == null ? Path.of("") : directory.toPath()).toAbsolutePath().normalize();
    }
}
