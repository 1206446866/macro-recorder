package com.macro.ui;

import com.macro.flow.*;
import com.macro.model.MacroAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@EnabledOnOs(OS.WINDOWS)
class ImageJumpEditorTest {
    @Test void targetEnabledStatesFollowOnlyTheirOwnJumpBehavior() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            ActionEditorDialog dialog = ActionEditorDialog.create(null, n -> fail("must not save"),
                    (parent, options) -> Optional.empty());
            try {
                dialog.setAvailableBranchTargets(List.of(action("a"), action("b")));
                JComboBox<?> type = components(dialog).stream().filter(c -> c instanceof JComboBox<?>)
                        .map(c -> (JComboBox<?>) c).findFirst().orElseThrow();
                for (int i = 0; i < type.getItemCount(); i++)
                    if (type.getItemAt(i).toString().equals("IMAGE_CHECK")) type.setSelectedIndex(i);
                List<JComboBox<?>> behaviors = behaviors(dialog);
                JComboBox<?> success = target(dialog, "successTarget"), failure = target(dialog, "failureTarget");
                success.setSelectedIndex(1);
                failure.setSelectedIndex(0);
                for (ImageCheckBehavior successBehavior : ImageCheckBehavior.values()) {
                    behaviors.get(0).setSelectedItem(successBehavior);
                    for (ImageCheckBehavior failureBehavior : ImageCheckBehavior.values()) {
                        behaviors.get(1).setSelectedItem(failureBehavior);
                        assertEquals(successBehavior == ImageCheckBehavior.JUMP, success.isEnabled());
                        assertEquals(failureBehavior == ImageCheckBehavior.JUMP, failure.isEnabled());
                        assertEquals(1, success.getSelectedIndex(), "切换启用状态不能改写成功目标");
                        assertEquals(0, failure.getSelectedIndex(), "切换启用状态不能改写失败目标");
                    }
                }
            } finally { dialog.dispose(); }
        });
    }

    @Test void editorSelectsIndependentTargetsWithoutTypingIdsAndPreservesThemOnEdit() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        Path root=Files.createDirectories(Path.of(System.getProperty("macro.test.sandboxRoot","target/test-sandboxes")));
        Path template=Files.createTempDirectory(root,"jump-editor-").resolve("template.png");
        ImageIO.write(new BufferedImage(8,8,BufferedImage.TYPE_INT_RGB),"png",template.toFile());
        SwingUtilities.invokeAndWait(()->{
            MacroNode a=action("a"),b=action("b");
            AtomicReference<MacroNode> saved=new AtomicReference<>();
            ActionEditorDialog dialog=ActionEditorDialog.create(null,saved::set,(parent,options)->Optional.of(template.toFile()));
            try {
                dialog.setAvailableBranchTargets(List.of(a,b));
                JComboBox<?> type=components(dialog).stream().filter(c->c instanceof JComboBox<?>).map(c->(JComboBox<?>)c).findFirst().orElseThrow();
                for(int i=0;i<type.getItemCount();i++)if(type.getItemAt(i).toString().equals("IMAGE_CHECK"))type.setSelectedIndex(i);
                List<JComboBox<?>> behaviors=behaviors(dialog);
                behaviors.get(0).setSelectedItem(ImageCheckBehavior.JUMP);behaviors.get(1).setSelectedItem(ImageCheckBehavior.JUMP);
                JComboBox<?> success=target(dialog,"successTarget"),failure=target(dialog,"failureTarget");
                assertTrue(success.isEnabled()&&failure.isEnabled());
                assertEquals(2,success.getItemCount());success.setSelectedIndex(1);failure.setSelectedIndex(0);
                button(dialog,"选择模板 PNG").doClick();
                button(dialog,"插入").doClick();
                assertNotNull(saved.get());
                assertEquals("b",MacroFlowFactory.branchFor(saved.get(),BranchCondition.FOUND).getTargetId());
                assertEquals("a",MacroFlowFactory.branchFor(saved.get(),BranchCondition.NOT_FOUND).getTargetId());
                saved.get().setId("image");
            }finally{dialog.dispose();}
            AtomicReference<MacroNode> edited=new AtomicReference<>();
            ActionEditorDialog edit=ActionEditorDialog.edit(null,saved.get(),edited::set,ignored->{});
            try {
                edit.setAvailableBranchTargets(List.of(a,new MacroNode("end",NodeType.END,null,new ArrayList<>()),saved.get(),b));
                assertEquals(2,target(edit,"successTarget").getItemCount(),"当前节点应使用重试，不能作为指定目标");
                assertTrue(target(edit,"successTarget").getSelectedItem().toString().contains("03 ·"));
                assertTrue(target(edit,"failureTarget").getSelectedItem().toString().contains("01 ·"));
                layout(edit);
                Rectangle bounds=SwingUtilities.convertRectangle(target(edit,"failureTarget").getParent(),target(edit,"failureTarget").getBounds(),edit.getContentPane());
                assertTrue(edit.getContentPane().getBounds().height>bounds.y+bounds.height,"目标选择行被裁切");
                // 两个结果也允许选择相同目标。
                target(edit,"failureTarget").setSelectedIndex(1);
                button(edit,"保存").doClick();
                assertEquals("b",MacroFlowFactory.branchFor(edited.get(),BranchCondition.FOUND).getTargetId());
                assertEquals("b",MacroFlowFactory.branchFor(edited.get(),BranchCondition.NOT_FOUND).getTargetId());
            }finally{edit.dispose();}
        });
    }

    @Test void deletedTargetIsNotSilentlyReplacedAndCancelDoesNotModifySource() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(()->{
            ImageCheckConfig config=new ImageCheckConfig("synthetic.png",.9);config.setSuccessBehavior(ImageCheckBehavior.JUMP);
            MacroNode image=new MacroNode("image",NodeType.IMAGE_CHECK,null,config,List.of(new MacroBranch(BranchCondition.FOUND,"deleted",0)));
            ActionEditorDialog dialog=ActionEditorDialog.edit(null,image,n->fail("cancel must not save"),ignored->{});
            try{
                dialog.setAvailableBranchTargets(List.of(image,action("other")));
                assertTrue(target(dialog,"successTarget").getSelectedItem().toString().contains("不存在"));
                behaviors(dialog).get(0).setSelectedItem(ImageCheckBehavior.RETRY);
                assertFalse(target(dialog,"successTarget").isEnabled());
            }finally{dialog.dispose();}
            assertEquals(ImageCheckBehavior.JUMP,config.getSuccessBehavior());
            assertEquals("deleted",image.getChildren().get(0).getTargetId());
        });
    }
    static MacroNode action(String id){return new MacroNode(id,NodeType.ACTION,MacroAction.mouseMove(1,2,0),new ArrayList<>());}
    static List<Component> components(Container root){List<Component> all=new ArrayList<>();for(Component c:root.getComponents()){all.add(c);if(c instanceof Container nested)all.addAll(components(nested));}return all;}
    static List<JComboBox<?>> behaviors(Container root){return components(root).stream().filter(c->c instanceof JComboBox<?> combo&&combo.getItemCount()>0&&combo.getItemAt(0) instanceof ImageCheckBehavior).map(c->(JComboBox<?>)c).collect(java.util.stream.Collectors.toList());}
    static JComboBox<?> target(Container root,String name){return (JComboBox<?>)components(root).stream().filter(c->name.equals(c.getName())).findFirst().orElseThrow();}
    static JButton button(Container root,String name){return (JButton)components(root).stream().filter(c->c instanceof JButton b&&name.equals(b.getText())).findFirst().orElseThrow();}
    static void layout(Container c){c.doLayout();for(Component child:c.getComponents())if(child instanceof Container nested)layout(nested);}
}
