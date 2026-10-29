package com.macro.ui;
import com.macro.flow.*;
import com.macro.model.MacroAction;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TimelineUndoHistoryTest {
    @Test void unchangedFlowDoesNotConsumeHistoryAndRestoredValuesAreIndependent() {
        TimelineUndoHistory history = new TimelineUndoHistory();
        MacroFlow flow = flow();
        var before = history.capture(flow, "selected"); history.record(before, flow); assertNull(history.peek());
        action(flow).setX(2); history.record(before, flow);
        assertEquals("selected", history.peek().selectedId());
        action(flow).setX(3);
        MacroFlow restored = history.restore(history.peek()); assertEquals(1, action(restored).getX());
        action(restored).setX(999); assertEquals(1, action(history.restore(history.peek())).getX());
    }
    @Test void countLimitEvictsOldestAndClearingReleasesHistory() {
        TimelineUndoHistory history = new TimelineUndoHistory(2, 1000000);
        MacroFlow flow = flow();
        for (int x = 2; x <= 4; x++) { var before = history.capture(flow, null); action(flow).setX(x); history.record(before, flow); }
        assertEquals(3, action(history.restore(history.peek())).getX()); history.removeLast();
        assertEquals(2, action(history.restore(history.peek())).getX()); history.removeLast(); assertNull(history.peek());
        var before = history.capture(flow, null); action(flow).setX(10); history.record(before, flow); history.clear(); assertNull(history.peek());
    }
    @Test void byteBudgetKeepsMostRecentStepEvenWhenOneMacroExceedsBudget() {
        TimelineUndoHistory history = new TimelineUndoHistory(100, 1);
        MacroFlow flow = flow();
        for (int x = 2; x <= 4; x++) { var before = history.capture(flow, null); action(flow).setX(x); history.record(before, flow); }
        assertEquals(3, action(history.restore(history.peek())).getX()); history.removeLast(); assertNull(history.peek());
    }
    private static MacroFlow flow() { return MacroFlowFactory.fromActions(List.of(MacroAction.mouseMove(1, 1, 0))); }
    private static MacroAction action(MacroFlow flow) { return MacroFlowFactory.extractActions(flow).get(0); }
}
