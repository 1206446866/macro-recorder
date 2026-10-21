package com.macro.ui;

import com.macro.flow.MacroNode;
import com.macro.flow.NodeType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 仅供展示的当前顺序编号，绝不写回节点 ID 或分支引用。 */
final class TimelineNodeNumbers {
    private TimelineNodeNumbers() { }

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
