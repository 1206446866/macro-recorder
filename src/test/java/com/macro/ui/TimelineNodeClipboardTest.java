package com.macro.ui;
import com.macro.flow.*;
import com.macro.model.*;
import org.junit.jupiter.api.Test;
import java.awt.datatransfer.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 独立 JVM 内剪贴板，不读取或覆盖系统剪贴板。 */
class TimelineNodeClipboardTest {
    @Test void supportsEveryActionTypeAndProvidesReadableText() throws Exception {
        assertTrue(TimelineNodeClipboard.NODE_FLAVOR.isRepresentationClassInputStream());
        assertFalse(TimelineNodeClipboard.NODE_FLAVOR.isFlavorSerializedObjectType());
        Clipboard clipboard = new Clipboard("nodes"); var service = new TimelineNodeClipboard(() -> clipboard);
        List<MacroAction> actions = List.of(MacroAction.mouseMove(-1, 2, 3), MacroAction.mousePress(4, 5, MouseButton.LEFT, 6),
                MacroAction.mouseRelease(7, 8, MouseButton.RIGHT, 9), MacroAction.mouseWheel(1, 2, -3, 4),
                MacroAction.keyPress(65, "A", KeySide.LEFT, 5), MacroAction.keyRelease(66, "B", KeySide.RIGHT, 6));
        for (MacroAction action : actions) {
            var node = new MacroNode("original", NodeType.ACTION, action, new ArrayList<>());
            service.copy(node, null, "中文节点信息");
            assertTrue(service.hasNode()); assertEquals(node, service.read(null));
            assertEquals("中文节点信息", clipboard.getData(DataFlavor.stringFlavor));
        }
    }
    @Test void copyAndRepeatedReadsNeverShareMutableObjects() throws Exception {
        Clipboard clipboard = new Clipboard("nodes"); var service = new TimelineNodeClipboard(() -> clipboard);
        var config = new ImageCheckConfig("template.png", .93); config.setFailureClickX(0); config.setFailureClickY(-30);
        config.setFailureBehavior(ImageCheckBehavior.CLICK_TERMINATE); config.setScaleMatchingEnabled(true);
        config.setSearchScope(ImageSearchScope.WINDOW);
        config.setSearchWindow(TargetWindow.create().setProcessName("fixture.exe").setWindowClass("Fixture").setWindowTitle("测试窗口"));
        MacroNode original = new MacroNode("original", NodeType.IMAGE_CHECK, null, config,
                new ArrayList<>(List.of(new MacroBranch(BranchCondition.FOUND, "target", 17))));
        service.copy(original, null, "image"); config.setThreshold(.1); original.getChildren().get(0).setTargetId("changed");
        MacroNode first = service.read(null), second = service.read(null);
        assertEquals(.93, first.getImageCheckConfig().getThreshold()); assertEquals("target", first.getChildren().get(0).getTargetId());
        assertEquals(first, second); assertNotSame(first, second); assertNotSame(first.getImageCheckConfig(), second.getImageCheckConfig());
        first.getImageCheckConfig().setFailureClickX(999); first.getChildren().clear();
        assertEquals(0, second.getImageCheckConfig().getFailureClickX()); assertEquals(1, second.getChildren().size());
    }
    @Test void relativeTemplateKeepsItsBaseAcrossMacrosWithoutReadingFiles() throws Exception {
        Clipboard clipboard = new Clipboard("nodes"); var service = new TimelineNodeClipboard(() -> clipboard);
        Path source = Path.of("target", "来源目录").toAbsolutePath(), destination = Path.of("target", "其他目录").toAbsolutePath();
        MacroNode node = image("sub/../模板.png"); service.copy(node, source.toFile(), "image");
        assertEquals("sub/../模板.png", service.read(source.toFile()).getImageCheckConfig().getImagePath());
        assertEquals(source.resolve("模板.png").toString(), service.read(destination.toFile()).getImageCheckConfig().getImagePath());
        assertEquals("sub/../模板.png", node.getImageCheckConfig().getImagePath());
        String absolute = source.resolve("abs.png").toString(); service.copy(image(absolute), source.toFile(), "image");
        assertEquals(absolute, service.read(destination.toFile()).getImageCheckConfig().getImagePath());
    }
    @Test void missingAndEmptyTemplateReferencesArePreserved() throws Exception {
        Clipboard clipboard = new Clipboard("nodes"); var service = new TimelineNodeClipboard(() -> clipboard);
        for (String path : new String[]{null, "", "missing.png"}) {
            service.copy(image(path), null, "image"); assertEquals(path, service.read(null).getImageCheckConfig().getImagePath());
        }
    }
    @Test void ordinaryTextAndTextualJsonNeverBecomeNodes() throws Exception {
        Clipboard clipboard = new Clipboard("nodes"); var service = new TimelineNodeClipboard(() -> clipboard);
        service.copy(image("template.png"), null, "image");
        String nodeJson;
        try (var input = (java.io.InputStream) clipboard.getData(TimelineNodeClipboard.NODE_FLAVOR)) {
            nodeJson = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        for (String plain : List.of("普通文字", nodeJson)) {
            clipboard.setContents(new StringSelection(plain), null);
            assertFalse(service.hasNode()); assertNull(service.read(null));
        }
    }
    @Test void malformedUnsupportedAndOversizedClipboardContentsAreRejected() throws Exception {
        Clipboard clipboard = new Clipboard("nodes"); var service = new TimelineNodeClipboard(() -> clipboard);
        for (String text : List.of("bad json", "null", "{\"version\":2,\"baseDirectory\":\".\"}",
                "{\"version\":1,\"baseDirectory\":\".\",\"node\":{\"type\":\"END\"}}", "x".repeat(1024*1024+1))) {
            clipboard.setContents(contents(text), null); assertThrows(IOException.class, () -> service.read(null));
        }
        clipboard.setContents(new StringSelection("保留原内容"), null);
        assertThrows(IOException.class, () -> service.copy(image("x".repeat(1024*1024)), null, "image"));
        assertEquals("保留原内容", clipboard.getData(DataFlavor.stringFlavor));
    }
    @Test void invalidActionsAndEndNodeAreRejectedBeforeReplacingClipboard() throws Exception {
        Clipboard clipboard = new Clipboard("nodes"); var service = new TimelineNodeClipboard(() -> clipboard);
        clipboard.setContents(new StringSelection("preserve"), null);
        for (MacroNode invalid : List.of(new MacroNode("end", NodeType.END, null, new ArrayList<>()),
                new MacroNode("bad", NodeType.ACTION, null, new ArrayList<>()),
                new MacroNode("bad-key", NodeType.ACTION, MacroAction.keyPress(0, "", 0), new ArrayList<>())))
            assertThrows(IOException.class, () -> service.copy(invalid, null, "bad"));
        assertEquals("preserve", clipboard.getData(DataFlavor.stringFlavor));
    }
    @Test void busyClipboardIsUnavailableWithoutProducingStaleData() {
        var service = new TimelineNodeClipboard(() -> { throw new IllegalStateException("busy"); });
        assertFalse(service.hasNode()); assertThrows(IllegalStateException.class, () -> service.read(null));
        assertThrows(IllegalStateException.class, () -> service.copy(image("template.png"), null, "image"));
    }
    static Transferable contents(String value) {
        return new Transferable() {
            public DataFlavor[] getTransferDataFlavors(){return new DataFlavor[]{TimelineNodeClipboard.NODE_FLAVOR};}
            public boolean isDataFlavorSupported(DataFlavor flavor){return TimelineNodeClipboard.NODE_FLAVOR.equals(flavor);}
            public Object getTransferData(DataFlavor flavor)throws UnsupportedFlavorException{if(!isDataFlavorSupported(flavor))throw new UnsupportedFlavorException(flavor);return new java.io.ByteArrayInputStream(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        };
    }
    private static MacroNode image(String path) {
        return new MacroNode("image", NodeType.IMAGE_CHECK, null, new ImageCheckConfig(path,.9), new ArrayList<>());
    }
}
