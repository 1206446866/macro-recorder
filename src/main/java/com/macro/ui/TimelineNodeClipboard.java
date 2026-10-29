package com.macro.ui;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.flow.MacroNode;
import com.macro.flow.NodeType;
import com.macro.util.ImageTemplatePathUtil;
import java.awt.datatransfer.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.HashSet;
import java.util.function.Supplier;

/** 节点快照与普通文字共用剪贴板；不复制图片文件，不读取或解释普通文本为节点。 */
final class TimelineNodeClipboard {
    static final DataFlavor NODE_FLAVOR = new DataFlavor(
            "application/x-macro-recorder-node;class=java.io.InputStream", "Macro Recorder node");
    private static final int MAXIMUM_PAYLOAD_LENGTH = 1024 * 1024;
    private final ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final Supplier<Clipboard> clipboardSupplier;

    TimelineNodeClipboard(Supplier<Clipboard> clipboardSupplier) { this.clipboardSupplier = clipboardSupplier; }

    boolean hasNode() {
        try { return clipboardSupplier.get().isDataFlavorAvailable(NODE_FLAVOR); }
        catch (IllegalStateException exception) { return false; }
    }

    void copy(MacroNode node, File directory, String summary) throws IOException {
        copy(List.of(node), directory, summary);
    }

    void copy(List<MacroNode> nodes, File directory, String summary) throws IOException {
        if (nodes.isEmpty()) throw new IOException("未选择节点");
        for (MacroNode node : nodes) validate(node);
        byte[] payload = mapper.writeValueAsBytes(nodes.size() == 1
                ? new Payload(1, base(directory).toString(), nodes.get(0), null)
                : new Payload(2, base(directory).toString(), null, nodes));
        if (payload.length > MAXIMUM_PAYLOAD_LENGTH) throw new IOException("节点内容过大，无法复制");
        clipboardSupplier.get().setContents(new Transferable() {
            @Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{NODE_FLAVOR, DataFlavor.stringFlavor}; }
            @Override public boolean isDataFlavorSupported(DataFlavor flavor) {
                return NODE_FLAVOR.equals(flavor) || DataFlavor.stringFlavor.equals(flavor);
            }
            @Override public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
                if (NODE_FLAVOR.equals(flavor)) return new ByteArrayInputStream(payload);
                if (DataFlavor.stringFlavor.equals(flavor)) return summary;
                throw new UnsupportedFlavorException(flavor);
            }
        }, null);
    }

    MacroNode read(File destinationDirectory) throws IOException {
        List<MacroNode> nodes = readNodes(destinationDirectory);
        return nodes.isEmpty() ? null : nodes.get(0);
    }

    List<MacroNode> readNodes(File destinationDirectory) throws IOException {
        Clipboard clipboard = clipboardSupplier.get();
        if (!clipboard.isDataFlavorAvailable(NODE_FLAVOR)) return List.of();
        Object value;
        try { value = clipboard.getData(NODE_FLAVOR); }
        catch (UnsupportedFlavorException exception) { throw new IOException("剪贴板已变化，请重新复制节点", exception); }
        if (!(value instanceof InputStream input))
            throw new IOException("剪贴板节点内容无效");
        byte[] bytes;
        try (input) { bytes = input.readNBytes(MAXIMUM_PAYLOAD_LENGTH + 1); }
        if (bytes.length > MAXIMUM_PAYLOAD_LENGTH) throw new IOException("剪贴板节点内容过大");
        Payload payload = mapper.readValue(bytes, Payload.class);
        if (payload == null || (payload.version() != 1 && payload.version() != 2) || payload.baseDirectory() == null)
            throw new IOException("不支持的剪贴板节点格式");
        List<MacroNode> nodes = payload.version() == 1 ? java.util.Collections.singletonList(payload.node()) : payload.nodes();
        if (nodes == null || nodes.isEmpty()) throw new IOException("剪贴板未包含节点");
        HashSet<String> ids = new HashSet<>();
        for (MacroNode node : nodes) {
            validate(node);
            if (nodes.size() > 1 && (node.getId() == null || node.getId().isBlank() || !ids.add(node.getId())))
                throw new IOException("剪贴板节点标识重复或缺失");
            if (node.getType() == NodeType.IMAGE_CHECK) {
                String path = node.getImageCheckConfig().getImagePath();
                Path sourceDirectory = Path.of(payload.baseDirectory()).toAbsolutePath().normalize();
                if (path != null && !path.isBlank() && !sourceDirectory.equals(base(destinationDirectory)))
                    node.getImageCheckConfig().setImagePath(ImageTemplatePathUtil.resolve(path, sourceDirectory.toFile()).toString());
            }
        }
        return List.copyOf(nodes);
    }

    private static Path base(File directory) {
        return (directory == null ? Path.of("") : directory.toPath()).toAbsolutePath().normalize();
    }

    private static void validate(MacroNode node) throws IOException {
        if (node == null || (node.getType() != NodeType.ACTION && node.getType() != NodeType.IMAGE_CHECK))
            throw new IOException("只能复制普通动作或图像识别节点");
        if (node.getType() == NodeType.ACTION) {
            var action = node.getAction();
            if (action == null || action.getType() == null || action.getDelay() < 0)
                throw new IOException("动作节点内容无效");
            switch (action.getType()) {
                case MOUSE_PRESS, MOUSE_RELEASE -> {
                    if (action.getMouseButton() == null || !action.getMouseButton().isSupported())
                        throw new IOException("鼠标动作缺少支持的按键");
                }
                case KEY_PRESS, KEY_RELEASE -> {
                    if (action.getKeyCode() <= 0) throw new IOException("键盘动作缺少有效按键");
                }
                default -> { }
            }
        } else {
            var config = node.getImageCheckConfig();
            if (config == null || !Double.isFinite(config.getThreshold()) || config.getThreshold() < 0 || config.getThreshold() > 1
                    || (config.getRetryDelay() != null && config.getRetryDelay() < 0))
                throw new IOException("图像识别节点内容无效");
            config.validateSearchScope(); config.validateClickBehaviors();
        }
    }

    private record Payload(int version, String baseDirectory, MacroNode node, List<MacroNode> nodes) { }
}
