package com.macro.ui;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.flow.MacroFlow;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;

/** 只在确认修改时保存独立流程快照，不保存图片、磁盘文件或重做记录。 */
final class TimelineUndoHistory {
    private final ObjectMapper mapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final ArrayDeque<Snapshot> entries = new ArrayDeque<>();
    private final int maximumEntries;
    private final long maximumBytes;
    private long retainedBytes;

    TimelineUndoHistory() { this(100, 32L * 1024 * 1024); }

    TimelineUndoHistory(int maximumEntries, long maximumBytes) {
        if (maximumEntries < 1 || maximumBytes < 1) throw new IllegalArgumentException("撤销容量必须为正数");
        this.maximumEntries = maximumEntries;
        this.maximumBytes = maximumBytes;
    }

    Snapshot capture(MacroFlow flow, String selectedId) {
        return capture(flow, selectedId, selectedId == null ? List.of() : List.of(selectedId), selectedId);
    }

    Snapshot capture(MacroFlow flow, String selectedId, List<String> selectedIds, String anchorId) {
        return new Snapshot(encode(flow), selectedId, List.copyOf(selectedIds), anchorId);
    }

    void record(Snapshot before, MacroFlow after) {
        if (Arrays.equals(before.data(), encode(after))) return;
        entries.addLast(before);
        retainedBytes += before.data().length;
        // 单个特大宏仍保留最近一步；优先淘汰更早的记录。
        while (entries.size() > 1 && (entries.size() > maximumEntries || retainedBytes > maximumBytes))
            retainedBytes -= entries.removeFirst().data().length;
    }

    Snapshot peek() { return entries.peekLast(); }

    MacroFlow restore(Snapshot snapshot) {
        try { return mapper.readValue(snapshot.data(), MacroFlow.class); }
        catch (IOException exception) { throw new IllegalStateException("无法恢复撤销记录", exception); }
    }

    void removeLast() { retainedBytes -= entries.removeLast().data().length; }

    void clear() { entries.clear(); retainedBytes = 0; }

    private byte[] encode(MacroFlow flow) {
        try { return mapper.writeValueAsBytes(flow); }
        catch (IOException exception) { throw new IllegalStateException("无法准备撤销记录", exception); }
    }

    record Snapshot(byte[] data, String selectedId, List<String> selectedIds, String anchorId) { }
}
