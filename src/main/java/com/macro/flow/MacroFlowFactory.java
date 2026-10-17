package com.macro.flow;

import com.macro.model.MacroAction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 创建和读取当前线性时间线使用的流程图。 */
public final class MacroFlowFactory {

    private MacroFlowFactory() {
    }

    /**
     * 将旧版动作列表转换为等价的 ACTION 线性流程。
     *
     * @param actions 旧版宏动作列表
     * @return 包含入口、动作节点和 END 节点的流程图
     */
    public static MacroFlow fromActions(List<MacroAction> actions) {
        List<MacroNode> nodes = new ArrayList<>();
        if (actions != null) {
            for (MacroAction action : actions) {
                nodes.add(new MacroNode(
                        newNodeId(),
                        NodeType.ACTION,
                        action,
                        new ArrayList<>()
                ));
            }
        }
        return rebuildLinear(nodes, null);
    }

    /**
     * 按 nodeMap 的稳定顺序取得时间线可见节点。
     *
     * @param flow 流程图
     * @return 不包含内部 END 节点的新列表
     */
    public static List<MacroNode> timelineNodes(MacroFlow flow) {
        if (flow == null || flow.getNodeMap() == null) {
            return new ArrayList<>();
        }
        return flow.getNodeMap().values().stream()
                .filter(node -> node != null && node.getType() != NodeType.END)
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    /**
     * 从流程中提取现有输入动作，用于兼容旧 UI 和 MR actions 字段。
     *
     * @param flow 流程图
     * @return ACTION 节点中的动作列表
     */
    public static List<MacroAction> extractActions(MacroFlow flow) {
        return timelineNodes(flow).stream()
                .filter(node -> node.getType() == NodeType.ACTION)
                .map(MacroNode::getAction)
                .filter(action -> action != null)
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    /**
     * 按时间线顺序重建默认分支。
     *
     * <p>图像检查按成功和失败行为分别连接自身、后继或终点。</p>
     *
     * @param timelineNodes 按显示顺序排列的节点
     * @param preferredEndId 希望复用的 END 节点 ID
     * @return 重建后的完整流程图
     */
    public static MacroFlow rebuildLinear(
            List<MacroNode> timelineNodes,
            String preferredEndId
    ) {
        List<MacroNode> nodes = timelineNodes == null
                ? new ArrayList<>()
                : new ArrayList<>(timelineNodes);
        String endId = preferredEndId == null || preferredEndId.isBlank()
                ? newNodeId()
                : preferredEndId;
        Map<String, MacroNode> nodeMap = new LinkedHashMap<>();

        for (int index = 0; index < nodes.size(); index++) {
            MacroNode node = nodes.get(index);
            if (node.getId() == null || node.getId().isBlank()) {
                node.setId(newNodeId());
            }
            String nextId = index + 1 < nodes.size()
                    ? ensureNodeId(nodes.get(index + 1))
                    : endId;
            if (node.getType() == NodeType.IMAGE_CHECK) {
                long retryDelay = findRetryDelay(node);
                ImageCheckConfig config = node.getImageCheckConfig();
                // 保存重试值，避免两条分支都不重试时丢失用户配置。
                config.setRetryDelay(retryDelay);
                node.setChildren(new ArrayList<>(List.of(
                        imageBranch(BranchCondition.FOUND, config.getSuccessBehavior(),
                                node.getId(), nextId, endId, retryDelay),
                        imageBranch(BranchCondition.NOT_FOUND, config.getFailureBehavior(),
                                node.getId(), nextId, endId, retryDelay)
                )));
            } else {
                node.setChildren(new ArrayList<>(List.of(
                        new MacroBranch(BranchCondition.ALWAYS, nextId, 0L)
                )));
            }
            nodeMap.put(node.getId(), node);
        }

        MacroNode end = new MacroNode(
                endId,
                NodeType.END,
                null,
                new ArrayList<>()
        );
        nodeMap.put(endId, end);
        return new MacroFlow(
                nodes.isEmpty() ? endId : nodes.get(0).getId(),
                nodeMap
        );
    }

    /** 终止分支保留合法 END 引用，播放器依据配置退出整个任务而非开始下一轮。 */
    private static MacroBranch imageBranch(BranchCondition condition, ImageCheckBehavior behavior,
            String currentId, String nextId, String endId, long retryDelay) {
        return new MacroBranch(condition, switch (behavior) {
            case RETRY -> currentId;
            case EXECUTE -> nextId;
            case TERMINATE -> endId;
        }, behavior == ImageCheckBehavior.RETRY ? retryDelay : 0L);
    }

    /** 创建不会与现有节点冲突的新节点 ID。 */
    public static String newNodeId() {
        return "node-" + UUID.randomUUID();
    }

    /** 查找流程中的 END 节点 ID，未找到时返回空。 */
    public static String findEndId(MacroFlow flow) {
        if (flow == null || flow.getNodeMap() == null) {
            return null;
        }
        return flow.getNodeMap().values().stream()
                .filter(node -> node != null && node.getType() == NodeType.END)
                .map(MacroNode::getId)
                .findFirst()
                .orElse(null);
    }

    /** 优先读取配置中的重试延迟，兼容旧版 NOT_FOUND 分支保存的延迟。 */
    public static long findRetryDelay(MacroNode node) {
        if (node != null && node.getImageCheckConfig() != null
                && node.getImageCheckConfig().getRetryDelay() != null) {
            return node.getImageCheckConfig().getRetryDelay();
        }
        if (node == null || node.getChildren() == null) {
            return 1_000L;
        }
        return node.getChildren().stream()
                .filter(branch -> branch != null
                        && branch.getCondition() == BranchCondition.NOT_FOUND)
                .mapToLong(MacroBranch::getDelay)
                .findFirst()
                .orElse(1_000L);
    }

    private static String ensureNodeId(MacroNode node) {
        if (node.getId() == null || node.getId().isBlank()) {
            node.setId(newNodeId());
        }
        return node.getId();
    }
}
