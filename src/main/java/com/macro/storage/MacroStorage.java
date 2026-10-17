package com.macro.storage;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.constant.MacroFormat;
import com.macro.exception.MacroFormatException;
import com.macro.flow.ImageCheckConfig;
import com.macro.flow.MacroBranch;
import com.macro.flow.MacroFlow;
import com.macro.flow.MacroFlowFactory;
import com.macro.flow.MacroNode;
import com.macro.flow.NodeType;
import com.macro.model.ActionType;
import com.macro.model.MacroAction;
import com.macro.model.MouseButton;
import com.macro.model.TargetWindow;

import java.io.File;
import java.nio.file.*;
import java.util.List;
import java.util.Map;


public class MacroStorage {

    private final ObjectMapper mapper = new ObjectMapper();



    /**
     * 保存 MR 文件。
     *
     * 先写入同目录下的临时文件，成功后再替换正式文件，
     * 避免写入失败时直接破坏原有文件。
     *
     * @param actions     宏动作列表
     * @param targetWindow 目标窗口信息
     * @param fileName    保存路径
     * @throws Exception 保存失败时抛出异常
     */
    public void save(List<MacroAction> actions,
                     TargetWindow targetWindow,
                     String fileName) throws Exception {
        save(actions, targetWindow, null, fileName);
    }

    /**
     * 保存兼容动作列表以及可选流程图。
     *
     * @param actions 供旧版读取的普通动作列表
     * @param targetWindow 目标窗口信息
     * @param flow 包含图像识别节点和分支的完整流程图
     * @param fileName 保存路径
     * @throws Exception 保存失败时抛出异常
     */
    public void save(
            List<MacroAction> actions,
            TargetWindow targetWindow,
            MacroFlow flow,
            String fileName
    ) throws Exception {

        Path targetPath = Paths.get(fileName).toAbsolutePath();
        Path parentPath = targetPath.getParent();

        // 确保目标目录存在。
        Files.createDirectories(parentPath);

        // 临时文件与正式文件位于同一目录，便于执行文件替换。
        Path tempPath = Files.createTempFile(
                parentPath,
                "macro-",
                ".tmp"
        );

        try {
            MacroFile macroFile = new MacroFile();
            macroFile.setVersion(MacroFormat.CURRENT_VERSION);
            macroFile.setActions(actions);
            macroFile.setTargetWindow(targetWindow);
            macroFile.setFlow(flow);

            // 先完整写入临时文件。
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(tempPath.toFile(), macroFile);

            // 写入成功后，再替换正式文件。
            try {
                Files.move(
                        tempPath,
                        targetPath,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException e) {
                // 文件系统不支持原子移动时，退回普通替换。
                Files.move(
                        tempPath,
                        targetPath,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }
        }finally {
            // 保存结束后清理临时文件。
            // 清理失败不应覆盖正式文件写入或替换阶段的结果。
            try {
                Files.deleteIfExists(tempPath);
            } catch (Exception e) {
                System.err.println("清理宏文件临时文件失败：" + tempPath);
                e.printStackTrace();
            }
        }
    }



    /**
     * 加载 MR 文件并校验动作数据。
     *
     * @param fileName 文件路径
     * @return 校验通过的宏文件
     * @throws Exception 文件读取或校验失败
     */
    public MacroFile load(String fileName) throws Exception {
        JsonNode rootNode = mapper.readTree(new File(fileName));

        if (rootNode == null || !rootNode.isObject()) {
            throw new IllegalArgumentException("MR 文件内容为空或格式无效");
        }

        JsonNode versionNode = rootNode.get("version");
        if (versionNode == null
                || !versionNode.isIntegralNumber()
                || !versionNode.canConvertToInt()) {
            throw new IllegalArgumentException("MR 文件缺少有效版本");
        }

        int fileVersion = versionNode.intValue();
        if (fileVersion != MacroFormat.CURRENT_VERSION) {
            throw new MacroFormatException(
                    fileVersion,
                    MacroFormat.CURRENT_VERSION
            );
        }

        MacroFile macroFile = mapper.treeToValue(rootNode, MacroFile.class);

        if (macroFile == null) {
            throw new IllegalArgumentException("MR 文件内容为空");
        }

        MacroFlow flow = macroFile.getFlow();
        List<MacroAction> actions = macroFile.getActions();
        if (actions == null && flow != null) {
            actions = MacroFlowFactory.extractActions(flow);
            macroFile.setActions(actions);
        }
        if (actions == null) {
            throw new IllegalArgumentException("MR 文件缺少动作列表");
        }

        for (int i = 0; i < actions.size(); i++) {
            MacroAction action = actions.get(i);
            String prefix = "第 " + (i + 1) + " 个动作：";

            if (action == null) {
                throw new IllegalArgumentException(prefix + "动作数据为空");
            }

            ActionType type = action.getType();
            if (type == null) {
                throw new IllegalArgumentException(prefix + "缺少动作类型");
            }

            if (action.getDelay() < 0) {
                throw new IllegalArgumentException(prefix + "延迟不能为负数");
            }

            switch (type) {
                case MOUSE_PRESS, MOUSE_RELEASE -> {
                    MouseButton button = action.getMouseButton();

                    if (button == null) {
                        throw new IllegalArgumentException(
                                prefix + "缺少鼠标按键"
                        );
                    }

                    if (button == MouseButton.X1 || button == MouseButton.X2) {
                        throw new IllegalArgumentException(
                                prefix + "暂不支持模拟鼠标侧键"
                        );
                    }
                }

                case KEY_PRESS, KEY_RELEASE -> {
                    if (action.getKeyCode() <= 0) {
                        throw new IllegalArgumentException(
                                prefix + "按键码无效"
                        );
                    }
                }
                case MOUSE_MOVE, MOUSE_WHEEL -> {
                    // 当前模型中，这两类动作没有额外必填字段。
                }
            }
        }

        if (flow != null) {
            validateFlow(flow);
        }

        return macroFile;
    }

    /** 校验 MR 中流程入口、节点引用、动作数据和图像识别配置。 */
    private void validateFlow(MacroFlow flow) {
        if (flow.getStartId() == null || flow.getStartId().isBlank()) {
            throw new IllegalArgumentException("MR 流程缺少 startId");
        }
        Map<String, MacroNode> nodeMap = flow.getNodeMap();
        if (nodeMap == null || !nodeMap.containsKey(flow.getStartId())) {
            throw new IllegalArgumentException("MR 流程入口节点不存在");
        }
        for (Map.Entry<String, MacroNode> entry : nodeMap.entrySet()) {
            MacroNode node = entry.getValue();
            if (node == null || node.getType() == null
                    || !entry.getKey().equals(node.getId())) {
                throw new IllegalArgumentException("MR 流程包含无效节点");
            }
            if (node.getType() == NodeType.ACTION) {
                validateFlowAction(node);
            }
            if (node.getType() == NodeType.IMAGE_CHECK) {
                validateImageCheck(node);
            }
            if (node.getChildren() == null) {
                throw new IllegalArgumentException(
                        "MR 流程节点缺少分支：" + node.getId()
                );
            }
            for (MacroBranch branch : node.getChildren()) {
                if (branch == null || branch.getCondition() == null
                        || branch.getTargetId() == null
                        || !nodeMap.containsKey(branch.getTargetId())
                        || branch.getDelay() < 0L) {
                    throw new IllegalArgumentException(
                            "MR 流程节点包含无效分支：" + node.getId()
                    );
                }
            }
        }
    }

    private void validateImageCheck(MacroNode node) {
        ImageCheckConfig config = node.getImageCheckConfig();
        if (config == null || config.getImagePath() == null
                || config.getImagePath().isBlank()) {
            throw new IllegalArgumentException(
                    "MR 图像识别节点缺少模板路径：" + node.getId()
            );
        }
        if (!Double.isFinite(config.getThreshold())
                || config.getThreshold() < 0.0
                || config.getThreshold() > 1.0) {
            throw new IllegalArgumentException(
                    "MR 图像识别节点阈值无效：" + node.getId()
            );
        }
        if (MacroFlowFactory.findRetryDelay(node) < 0L) {
            throw new IllegalArgumentException("MR 图像识别节点重试延迟无效：" + node.getId());
        }
    }

    /** 校验流程图中的 ACTION，避免兼容 actions 正常但 flow 节点损坏。 */
    private void validateFlowAction(MacroNode node) {
        MacroAction action = node.getAction();
        if (action == null || action.getType() == null) {
            throw new IllegalArgumentException(
                    "MR 流程动作节点缺少动作数据：" + node.getId()
            );
        }
        if (action.getDelay() < 0L) {
            throw new IllegalArgumentException(
                    "MR 流程动作节点延迟不能为负数：" + node.getId()
            );
        }
        if ((action.getType() == ActionType.MOUSE_PRESS
                || action.getType() == ActionType.MOUSE_RELEASE)
                && action.getMouseButton() == null) {
            throw new IllegalArgumentException(
                    "MR 流程动作节点缺少鼠标按键：" + node.getId()
            );
        }
        if ((action.getType() == ActionType.KEY_PRESS
                || action.getType() == ActionType.KEY_RELEASE)
                && action.getKeyCode() <= 0) {
            throw new IllegalArgumentException(
                    "MR 流程动作节点按键码无效：" + node.getId()
            );
        }
    }
}
