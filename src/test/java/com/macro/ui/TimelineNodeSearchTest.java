package com.macro.ui;

import com.macro.flow.*;
import com.macro.model.*;
import org.junit.jupiter.api.Test;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TimelineNodeSearchTest {
    @Test void numbersMatchDisplayPositionExactlyIncludingPaddingAndFullWidth() {
        List<MacroNode> nodes = new ArrayList<>();
        for (int i = 1; i <= 120; i++) nodes.add(action("id-" + i, MacroAction.keyPress(KeyEvent.VK_1, "1", i)));
        assertEquals(List.of("id-1"), TimelineNodeSearch.find(nodes, "1"));
        assertEquals(List.of("id-12"), TimelineNodeSearch.find(nodes, "#0012"));
        assertEquals(List.of("id-120"), TimelineNodeSearch.find(nodes, "　＃１２０　"));
        assertEquals(List.of("id-12"), TimelineNodeSearch.find(nodes, "# 12"));
        for (String query : List.of("0", "121", "999999999999999999999999999999999999999"))
            assertTrue(TimelineNodeSearch.find(nodes, query).isEmpty());
    }

    @Test void blankQueriesAndInvisibleEndNeverProduceHits() {
        List<MacroNode> nodes = Arrays.asList(null, action("first", MacroAction.mouseMove(1, 2, 0)),
                new MacroNode("end", NodeType.END, null, new ArrayList<>()), action("second", MacroAction.mouseMove(3, 4, 0)));
        for (String query : Arrays.asList(null, "", "　 \t\n")) assertTrue(TimelineNodeSearch.find(nodes, query).isEmpty());
        assertEquals(List.of("second"), TimelineNodeSearch.find(nodes, "2"));
        assertTrue(TimelineNodeSearch.find(nodes, "end").isEmpty());
        assertTrue(TimelineNodeSearch.find(nodes, "first").isEmpty(), "内部 ID 不作为可见编号搜索");
    }

    @Test void chineseActionNamesAndEnglishTypesCoverEveryOrdinaryAction() {
        List<MacroNode> nodes = List.of(action("move", MacroAction.mouseMove(0, 0, 0)),
                action("press", MacroAction.mousePress(0, 0, MouseButton.LEFT, 0)),
                action("release", MacroAction.mouseRelease(0, 0, MouseButton.RIGHT, 0)),
                action("wheel", MacroAction.mouseWheel(0, 0, 1, 0)),
                action("down", MacroAction.keyPress(65, "A", 0)), action("up", MacroAction.keyRelease(65, "A", 0)));
        assertEquals(List.of("move", "press", "release", "wheel"), TimelineNodeSearch.find(nodes, "鼠标"));
        assertEquals(List.of("press", "down"), TimelineNodeSearch.find(nodes, "按下"));
        assertEquals(List.of("down", "up"), TimelineNodeSearch.find(nodes, "键盘"));
        assertEquals(List.of("wheel"), TimelineNodeSearch.find(nodes, "MOUSE_WHEEL"));
        assertEquals(List.of("release"), TimelineNodeSearch.find(nodes, "mouse release"));
    }

    @Test void combinedWordsRequireAllConditionsAndMouseButtonsStaySeparate() {
        List<MacroNode> nodes = List.of(action("left", MacroAction.mousePress(0, 0, MouseButton.LEFT, 0)),
                action("middle", MacroAction.mousePress(0, 0, MouseButton.MIDDLE, 0)),
                action("right", MacroAction.mouseRelease(0, 0, MouseButton.RIGHT, 0)));
        assertEquals(List.of("left"), TimelineNodeSearch.find(nodes, " 鼠标　左键 "));
        assertEquals(List.of("middle"), TimelineNodeSearch.find(nodes, "MOUSE middle"));
        assertEquals(List.of("right"), TimelineNodeSearch.find(nodes, "释放 右键"));
        assertTrue(TimelineNodeSearch.find(nodes, "左键 右键").isEmpty());
    }

    @Test void keysUseSavedNamesCanonicalNamesAndSideWithoutInventingNullLabels() {
        List<MacroNode> nodes = List.of(action("escape", MacroAction.keyRelease(KeyEvent.VK_ESCAPE, null, 0)),
                action("left", MacroAction.keyPress(KeyEvent.VK_CONTROL, "Ctrl", KeySide.LEFT, 0)),
                action("right", MacroAction.keyRelease(KeyEvent.VK_CONTROL, "Ctrl", KeySide.RIGHT, 0)));
        assertEquals(List.of("escape"), TimelineNodeSearch.find(nodes, "Escape"));
        assertEquals(List.of("left", "right"), TimelineNodeSearch.find(nodes, "ＣＴＲＬ"));
        assertEquals(List.of("left"), TimelineNodeSearch.find(nodes, "Ctrl 左侧"));
        assertEquals(List.of("right"), TimelineNodeSearch.find(nodes, "ctrl right"));
        assertTrue(TimelineNodeSearch.find(nodes, "null").isEmpty());
    }

    @Test void imageNamesMatchLiterallyInAnyPathWithoutMatchingParentDirectories() {
        List<MacroNode> nodes = List.of(image("win", "D:\\private-login-folder\\Save[1].PNG"),
                image("relative", "images/login_中文.png"), image("other", "/private-login-folder/other.png"), image("empty", null));
        assertEquals(List.of("win"), TimelineNodeSearch.find(nodes, "save[1].png"));
        assertEquals(List.of("relative"), TimelineNodeSearch.find(nodes, "图片 中文"));
        assertEquals(List.of("relative"), TimelineNodeSearch.find(nodes, "LOGIN"));
        assertEquals(List.of("win", "relative", "other", "empty"), TimelineNodeSearch.find(nodes, "图像识别"));
        assertTrue(TimelineNodeSearch.find(nodes, "private-login-folder").isEmpty());
        assertTrue(TimelineNodeSearch.find(nodes, ".*").isEmpty(), "输入不按正则表达式解释");
    }

    @Test void aSingleLetterFindsThatKeyWithoutMatchingOtherEnglishActionNames() {
        List<MacroNode> nodes = List.of(action("a", MacroAction.keyPress(65, "A", 0)),
                action("b", MacroAction.keyRelease(66, "B", 0)), action("escape", MacroAction.keyRelease(KeyEvent.VK_ESCAPE, "Escape", 0)),
                action("mouse", MacroAction.mouseRelease(0, 0, MouseButton.LEFT, 0)), image("image", "Save.png"));
        assertEquals(List.of("a"), TimelineNodeSearch.find(nodes, "A"));
        assertEquals(List.of("a"), TimelineNodeSearch.find(nodes, "键盘 a"));
        assertEquals(List.of("image"), TimelineNodeSearch.find(nodes, "save"));
    }

    private static MacroNode action(String id, MacroAction action) { return new MacroNode(id, NodeType.ACTION, action, new ArrayList<>()); }
    private static MacroNode image(String id, String path) { return new MacroNode(id, NodeType.IMAGE_CHECK, null, new ImageCheckConfig(path, .9), new ArrayList<>()); }
}
