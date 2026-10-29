package com.macro.ui;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.flow.*;
import com.macro.model.MacroAction;
import com.macro.model.TargetWindow;
import com.macro.player.MacroPlayer;
import com.macro.storage.MacroStorage;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** 独立剪贴板、合成节点和隐藏组件；不发送真实键鼠，不改系统剪贴板。 */
class TimelineNodeCopyPasteTest {
    @Test void ordinaryNodePastesAfterAnchorWithNewIdAndOneUndoStep() throws Exception { edt(() -> {
        Fixture f = new Fixture(); String before = json(f.panel); MacroNode source = node(f.panel,"a");
        AtomicInteger changes = new AtomicInteger(); f.panel.setOnFlowChanged(changes::incrementAndGet);
        assertTrue(f.panel.copyNode(source)); assertEquals(before,json(f.panel)); assertFalse(f.panel.canUndo());
        source.getAction().setX(999);
        assertTrue(f.panel.pasteNode(node(f.panel,"b"))); MacroNode pasted=f.panel.getSelectedMacroNode();
        assertNotEquals("a",pasted.getId()); assertEquals(12,pasted.getAction().getX());
        assertEquals(List.of("a","b",pasted.getId(),"c"),ids(f.panel));
        assertEquals(10,pasted.getAction().getDelay()); assertEquals(1,changes.get());
        assertTrue(f.panel.undo()); assertEquals(List.of("a","b","c"),ids(f.panel)); assertEquals(2,changes.get());
        assertEquals(999,node(f.panel,"a").getAction().getX()); assertFalse(f.panel.canUndo());
    }); }
    @Test void repeatedPasteAndSourceDeletionKeepTheOriginalCopyIndependent() throws Exception { edt(() -> {
        Fixture f=new Fixture(); MacroNode original=node(f.panel,"a"); f.panel.copyNode(original);
        f.panel.deleteAction(original,false);
        assertTrue(f.panel.pasteNode(null)); MacroNode first=f.panel.getSelectedMacroNode(); first.getAction().setX(500);
        assertTrue(f.panel.pasteNode(first)); MacroNode second=f.panel.getSelectedMacroNode();
        assertNotEquals(first.getId(),second.getId()); assertEquals(12,second.getAction().getX());
        assertEquals(500,first.getAction().getX()); assertNotSame(first.getAction(),second.getAction());
        assertTrue(f.panel.undo()); assertFalse(f.panel.getFlow().getNodeMap().containsKey(second.getId()));
        assertEquals(3,f.panel.getActions().size());
    }); }
    @Test void pasteIntoEmptyOrUnselectedTimelineAppendsAndSelectsCopy() throws Exception { edt(() -> {
        Fixture f=new Fixture(); f.panel.copyNode(node(f.panel,"a"));
        f.panel.setFlow(MacroFlowFactory.fromActions(List.of())); assertTrue(f.panel.pasteNode(null));
        String pasted=f.panel.getSelectedMacroNode().getId(); assertEquals(pasted,f.panel.getFlow().getStartId());
        assertTrue(f.panel.undo()); assertEquals(0,f.panel.getActions().size());
        f.panel.setFlow(flow()); assertTrue(f.panel.pasteNode(null));
        assertEquals(f.panel.getSelectedMacroNode().getId(),ids(f.panel).get(3));
    }); }
    @Test void imageCopyPreservesConfigurationAndJumpWhileRetryTargetsTheNewNode() throws Exception { edt(() -> {
        Fixture f=new Fixture(); MacroNode image=image();
        f.panel.setFlow(MacroFlowFactory.rebuildLinear(List.of(action("a",10),image,action("c",30)),"end"));
        f.panel.copyNode(image); f.panel.moveNode(node(f.panel,"c"),0);
        assertTrue(f.panel.pasteNode(node(f.panel,"a"))); MacroNode pasted=f.panel.getSelectedMacroNode();
        assertEquals(image.getImageCheckConfig(),pasted.getImageCheckConfig()); assertNotSame(image.getImageCheckConfig(),pasted.getImageCheckConfig());
        assertEquals("c",MacroFlowFactory.branchFor(pasted,BranchCondition.FOUND).getTargetId());
        assertEquals(17,MacroFlowFactory.branchFor(pasted,BranchCondition.FOUND).getDelay());
        assertEquals(pasted.getId(),MacroFlowFactory.branchFor(pasted,BranchCondition.NOT_FOUND).getTargetId());
        assertEquals(321,MacroFlowFactory.branchFor(pasted,BranchCondition.NOT_FOUND).getDelay());
        Path file=directory().resolve("pasted.mr"); MacroStorage storage=new MacroStorage();
        storage.save(f.panel.getActions(),null,f.panel.getFlow(),file.toString());
        assertEquals(f.panel.getFlow(),storage.load(file.toString()).getFlow());
    }); }
    @Test void missingJumpTargetRejectsPasteWithoutChangingFlowClipboardOrHistory() throws Exception { edt(() -> {
        Fixture f=new Fixture(); MacroNode image=image();
        f.panel.setFlow(MacroFlowFactory.rebuildLinear(List.of(image,action("c",30)),"end")); f.panel.copyNode(image);
        f.panel.setFlow(MacroFlowFactory.rebuildLinear(List.of(action("other",0)),"new-end"));
        String before=json(f.panel); byte[] copied=clipboardBytes(f.clipboard);
        assertThrows(IllegalArgumentException.class,()->f.panel.pasteNode(null));
        assertEquals(before,json(f.panel)); assertFalse(f.panel.canUndo()); assertArrayEquals(copied,clipboardBytes(f.clipboard));
    }); }
    @Test void crossMacroImagePasteKeepsOriginalTemplateDespiteSameNameInDestination() throws Exception { edt(() -> {
        Clipboard clipboard=new Clipboard("cross-macro");
        Path root=directory(),source=Files.createDirectories(root.resolve("来源")),dest=Files.createDirectories(root.resolve("目标"));
        Path template=source.resolve("template.png"); Files.write(template,new byte[]{1,2,3}); Files.write(dest.resolve("template.png"),new byte[]{4,5,6});
        ActionTimelinePanel from=new ActionTimelinePanel(clipboard),to=new ActionTimelinePanel(clipboard);
        from.setTemplateBaseDirectorySupplier(source::toFile); to.setTemplateBaseDirectorySupplier(dest::toFile);
        MacroNode image=image(); image.getImageCheckConfig().setSuccessBehavior(ImageCheckBehavior.EXECUTE);
        from.setFlow(MacroFlowFactory.rebuildLinear(List.of(image),"source-end")); assertTrue(from.copyNode(image));
        assertTrue(to.pasteNode(null)); MacroNode pasted=to.getSelectedMacroNode();
        assertEquals(template.toString(),pasted.getImageCheckConfig().getImagePath());
        assertEquals("template.png",image.getImageCheckConfig().getImagePath());
        assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(template)); assertArrayEquals(new byte[]{4,5,6},Files.readAllBytes(dest.resolve("template.png")));
    }); }
    @Test void readOnlyCopyIsAllowedButPasteIsBlockedEvenThroughOldMenu() throws Exception { edt(() -> {
        Fixture f=new Fixture(); MacroNode original=node(f.panel,"a"); f.panel.copyNode(original);
        JPopupMenu menu=menu(f.panel,original); JMenuItem paste=item(menu,"粘贴"); assertTrue(paste.isEnabled());
        AtomicBoolean allowed=new AtomicBoolean(false); f.panel.setEditPermission(allowed::get);
        String before=json(f.panel); assertTrue(f.panel.copyNode(original)); assertFalse(f.panel.pasteNode(original)); assertFalse(f.panel.canPasteNode());
        for(var listener:paste.getActionListeners())listener.actionPerformed(new ActionEvent(paste,0,"forced"));
        assertEquals(before,json(f.panel)); assertFalse(f.panel.canUndo());
        JPopupMenu readOnly=menu(f.panel,original); assertTrue(item(readOnly,"复制节点").isEnabled()); assertFalse(item(readOnly,"粘贴").isEnabled());
        allowed.set(true); assertTrue(f.panel.pasteNode(original));
    }); }
    @Test void ordinaryClipboardReplacementAndMalformedNodeNeverMutateFlow() throws Exception { edt(() -> {
        Fixture f=new Fixture(); f.panel.copyNode(node(f.panel,"a")); JMenuItem paste=item(menu(f.panel,node(f.panel,"b")),"粘贴");
        f.clipboard.setContents(new StringSelection("普通文字"),null); String before=json(f.panel);
        assertFalse(f.panel.canPasteNode()); paste.doClick(); assertEquals(before,json(f.panel)); assertFalse(f.panel.canUndo());
        f.clipboard.setContents(TimelineNodeClipboardTest.contents("bad json"),null);
        assertThrows(java.io.IOException.class,()->f.panel.pasteNode(null)); assertEquals(before,json(f.panel)); assertFalse(f.panel.canUndo());
    }); }
    @Test void staleAnchorsAndOldMenuCannotPasteIntoReplacedFlow() throws Exception { edt(() -> {
        Fixture f=new Fixture(); MacroNode old=node(f.panel,"a"); f.panel.copyNode(old); JMenuItem paste=item(menu(f.panel,old),"粘贴");
        f.panel.setFlow(flow()); String before=json(f.panel);
        assertFalse(f.panel.pasteNode(old)); assertFalse(f.panel.copyNode(old));
        for(var listener:paste.getActionListeners())listener.actionPerformed(new ActionEvent(paste,0,"late"));
        assertEquals(before,json(f.panel)); assertFalse(f.panel.canUndo()); assertTrue(f.panel.pasteNode(node(f.panel,"a")));
    }); }
    @Test void ctrlCAndVFollowSelectionAndLeaveTextEditingAlone() throws Exception { edt(() -> {
        Fixture f=new Fixture(); select(f.panel,node(f.panel,"b"));
        Action c=keyAction(f.panel,KeyEvent.VK_C),v=keyAction(f.panel,KeyEvent.VK_V);
        KeyboardFocusManager previous=KeyboardFocusManager.getCurrentKeyboardFocusManager(); AtomicReference<Component> focus=new AtomicReference<>(new JTextArea());
        f.clipboard.setContents(new StringSelection("文字剪贴板"),null);
        try {
            KeyboardFocusManager.setCurrentKeyboardFocusManager(new DefaultKeyboardFocusManager(){@Override public Component getFocusOwner(){return focus.get();}});
            c.actionPerformed(new ActionEvent(f.panel,0,"Ctrl+C")); v.actionPerformed(new ActionEvent(f.panel,0,"Ctrl+V"));
            assertEquals("文字剪贴板",f.clipboard.getData(DataFlavor.stringFlavor)); assertEquals(3,f.panel.getActions().size());
            focus.set(f.panel); c.actionPerformed(new ActionEvent(f.panel,0,"Ctrl+C")); v.actionPerformed(new ActionEvent(f.panel,0,"Ctrl+V"));
            MacroNode pasted=f.panel.getSelectedMacroNode(); assertEquals(20,pasted.getAction().getDelay()); assertEquals(pasted.getId(),ids(f.panel).get(2));
        } finally {KeyboardFocusManager.setCurrentKeyboardFocusManager(previous);}
    }); }
    @Test void menuPasteAndUndoRemainDirectMenuElementsAndCopyDoesNotConsumeHistory() throws Exception { edt(() -> {
        Fixture f=new Fixture(); MacroNode source=node(f.panel,"a");
        JMenuItem copy=item(menu(f.panel,source),"复制节点"); assertFalse(f.panel.canPasteNode()); copy.doClick(); assertFalse(f.panel.canUndo());
        JPopupMenu menu=menu(f.panel,node(f.panel,"b")); assertEquals(6,menu.getSubElements().length);
        item(menu,"粘贴").doClick(); assertEquals(4,f.panel.getActions().size());
        item(menu(f.panel,f.panel.getSelectedMacroNode()),"撤销").doClick(); assertEquals(3,f.panel.getActions().size());
        assertFalse(f.panel.canUndo()); assertTrue(f.panel.canPasteNode());
    }); }
    @Test void pastedNodePlaysAsIndependentActionAndUndoRemovesOnlyTheCopy() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        AtomicReference<MacroFlow> pasted=new AtomicReference<>(),restored=new AtomicReference<>();
        edt(()->{Fixture f=new Fixture(); f.panel.setActions(List.of(MacroAction.keyRelease(65,"A",0),MacroAction.keyRelease(66,"B",0)));
            var nodes=MacroFlowFactory.timelineNodes(f.panel.getFlow()); f.panel.copyNode(nodes.get(0)); f.panel.pasteNode(nodes.get(1));
            pasted.set(f.panel.getFlow()); f.panel.undo(); restored.set(f.panel.getFlow());});
        MacroPlayer player=new MacroPlayer(); RecordingRobot robot=new RecordingRobot();
        var field=MacroPlayer.class.getDeclaredField("robot");field.setAccessible(true);field.set(player,robot);
        player.play(pasted.get(),1,(node,context)->{throw new AssertionError("不应识图");});assertEquals(List.of(65,66,65),robot.keys);
        robot.keys.clear();player.play(restored.get(),1,(node,context)->{throw new AssertionError("不应识图");});assertEquals(List.of(65,66),robot.keys);
    }
    private static MacroNode image(){
        ImageCheckConfig config=new ImageCheckConfig("template.png",.93);config.setSuccessBehavior(ImageCheckBehavior.JUMP);config.setRetryDelay(321L);
        config.setScaleMatchingEnabled(true);config.setClickMode(ImageClickMode.RANDOM);config.setFailureClickX(-10);config.setFailureClickY(0);
        config.setSearchScope(ImageSearchScope.WINDOW);config.setSearchWindow(TargetWindow.create().setProcessName("fixture.exe").setWindowClass("Fixture").setWindowTitle("测试窗口"));
        return new MacroNode("image",NodeType.IMAGE_CHECK,null,config,new ArrayList<>(List.of(new MacroBranch(BranchCondition.FOUND,"c",17))));
    }
    private static MacroNode action(String id,long delay){return new MacroNode(id,NodeType.ACTION,MacroAction.mouseMove(12,34,delay),new ArrayList<>());}
    private static MacroFlow flow(){return MacroFlowFactory.rebuildLinear(List.of(action("a",10),action("b",20),action("c",30)),"end");}
    private static MacroNode node(ActionTimelinePanel p,String id){return p.getFlow().getNodeMap().get(id);}
    private static List<String> ids(ActionTimelinePanel p){return MacroFlowFactory.timelineNodes(p.getFlow()).stream().map(MacroNode::getId).toList();}
    private static String json(ActionTimelinePanel p)throws Exception{return new ObjectMapper().writeValueAsString(p.getFlow());}
    private static void select(ActionTimelinePanel p,MacroNode n)throws Exception{var m=p.getClass().getDeclaredMethod("selectMacroNode",MacroNode.class);m.setAccessible(true);m.invoke(p,n);}
    private static JPopupMenu menu(ActionTimelinePanel p,MacroNode n)throws Exception{var m=p.getClass().getDeclaredMethod("createContextMenu",MacroNode.class);m.setAccessible(true);return (JPopupMenu)m.invoke(p,n);}
    private static JMenuItem item(JPopupMenu menu,String text){for(Component c:menu.getComponents())if(c instanceof JMenuItem item&&text.equals(item.getText()))return item;throw new AssertionError(text);}
    private static Action keyAction(ActionTimelinePanel p,int key){KeyStroke stroke=KeyStroke.getKeyStroke(key,KeyEvent.CTRL_DOWN_MASK);Object name=p.getInputMap(JComponent.WHEN_FOCUSED).get(stroke);assertEquals(name,p.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(stroke));return p.getActionMap().get(name);}
    private static Path directory()throws Exception{Path root=Files.createDirectories(Path.of(System.getProperty("macro.test.sandboxRoot","target/test-sandboxes")));return Files.createTempDirectory(root,"node-paste-").toAbsolutePath();}
    private static byte[] clipboardBytes(Clipboard clipboard)throws Exception{try(var input=(java.io.InputStream)clipboard.getData(TimelineNodeClipboard.NODE_FLAVOR)){return input.readAllBytes();}}
    private static void edt(Checked c)throws Exception{SwingUtilities.invokeAndWait(()->{try{c.run();}catch(Exception e){throw new AssertionError(e);}});}
    @FunctionalInterface private interface Checked{void run()throws Exception;}
    private static class Fixture{final Clipboard clipboard=new Clipboard("timeline-test");final ActionTimelinePanel panel=new ActionTimelinePanel(clipboard);Fixture(){panel.setFlow(flow());}}
    private static class RecordingRobot extends Robot{final List<Integer> keys=new ArrayList<>();RecordingRobot()throws AWTException{}@Override public synchronized void keyRelease(int key){keys.add(key);}}
}
