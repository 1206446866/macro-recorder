package com.macro.ui;

import com.macro.flow.MacroNode;
import com.macro.flow.NodeType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 为时间线卡片、分支目标和动作编辑器生成一致的展示编号。
 * 编号随显示顺序重新计算，不修改节点 ID 或分支引用。
 */
final class TimelineNodeNumbers {
    /** 工具类只提供编号计算入口，不需要创建实例。 */
    private TimelineNodeNumbers() { }

    /**
     * 按给定顺序建立节点 ID 到连续编号的映射，忽略空条目和结束节点。
     *
     * @return 保留输入顺序的新映射；编号至少两位，超过 99 时保留完整数字
     */
    static Map<String, String> labels(List<MacroNode> nodes) {
        Map<String, String> result = new LinkedHashMap<>();
        int ordinal = 0;
        for (MacroNode node : nodes) {
            if (node == null || node.getType() == NodeType.END) continue;
            result.put(node.getId(), String.format(Locale.ROOT, "%02d", ++ordinal));
        }
        return result;
    }
}
