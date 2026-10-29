package com.macro.ui;

import com.macro.flow.*;
import com.macro.model.TargetWindow;
import com.macro.ui.dialog.ImageRecognitionFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** 不显示窗口、不枚举外部窗口、不截图；用保存配置和反射入口验证编辑/预览状态。 */
@EnabledOnOs(OS.WINDOWS)
class WindowRecognitionEditorTest {
    @Test void oldNodeDefaultsDesktopAndChangingScopeOnlyEnablesWindowChoice()throws Exception{
        assumeFalse(GraphicsEnvironment.isHeadless());MacroNode node=node();AtomicReference<MacroNode> saved=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->{
            ActionEditorDialog edit=ActionEditorDialog.edit(null,node,saved::set,null);
            try{
                Object panel=field(edit,"currentPropertyPanel");JComboBox<?> scope=(JComboBox<?>)field(panel,"searchScope");JButton target=(JButton)field(panel,"searchWindowButton");
                assertEquals(ImageSearchScope.DESKTOP,scope.getSelectedItem());assertFalse(target.isEnabled());assertFalse(target.isVisible());assertFalse(target.getParent().isVisible());
                scope.setSelectedItem(ImageSearchScope.WINDOW);assertTrue(target.isEnabled());assertTrue(target.isVisible());assertTrue(target.getParent().isVisible());
                Method create=panel.getClass().getDeclaredMethod("createImageNode",long.class);create.setAccessible(true);
                InvocationTargetException failure=assertThrows(InvocationTargetException.class,()->create.invoke(panel,1000L));assertInstanceOf(IllegalArgumentException.class,failure.getCause());
                scope.setSelectedItem(ImageSearchScope.DESKTOP);ImageJumpEditorTest.button(edit,"保存").doClick();
                assertEquals(ImageSearchScope.DESKTOP,saved.get().getImageCheckConfig().getSearchScope());
            }catch(ReflectiveOperationException error){throw new AssertionError(error);}finally{edit.dispose();}
        });
    }
    @Test void savedScopeRestoresAndUnsavedPreviewUsesIndependentWindowCopy()throws Exception{
        assumeFalse(GraphicsEnvironment.isHeadless());MacroNode node=node();node.getImageCheckConfig().setSearchScope(ImageSearchScope.WINDOW);node.getImageCheckConfig().setSearchWindow(window());
        SwingUtilities.invokeAndWait(()->{
            ActionEditorDialog edit=ActionEditorDialog.edit(null,node,ignored->fail("不能自动保存"),null);
            try{
                Object panel=field(edit,"currentPropertyPanel");JComboBox<?> scope=(JComboBox<?>)field(panel,"searchScope");assertEquals(ImageSearchScope.WINDOW,scope.getSelectedItem());
                TargetWindow selected=(TargetWindow)field(panel,"searchWindow");selected.setWindowTitle("尚未保存的新标题");
                Method prepare=panel.getClass().getDeclaredMethod("prepareRecognitionTest");prepare.setAccessible(true);
                ImageRecognitionFrame preview=(ImageRecognitionFrame)prepare.invoke(panel);
                try{
                    ImageCheckConfig config=(ImageCheckConfig)field(preview,"scopeConfig");assertEquals(ImageSearchScope.WINDOW,config.getSearchScope());
                    assertEquals("尚未保存的新标题",config.getSearchWindow().getWindowTitle());assertFalse((boolean)field(preview,"recognizing"));
                    selected.setWindowTitle("再次修改");assertEquals("尚未保存的新标题",config.getSearchWindow().getWindowTitle());
                    assertEquals(window(),node.getImageCheckConfig().getSearchWindow());
                }finally{preview.dispose();}
            }catch(ReflectiveOperationException error){throw new AssertionError(error);}finally{edit.dispose();}
        });
    }
    @Test void savingWindowScopePreservesParametersAnd150PercentLayout()throws Exception{
        assumeFalse(GraphicsEnvironment.isHeadless());MacroNode node=node();node.getImageCheckConfig().setSearchScope(ImageSearchScope.WINDOW);node.getImageCheckConfig().setSearchWindow(window());
        node.getImageCheckConfig().setScaleMatchingEnabled(true);node.getImageCheckConfig().setSuccessBehavior(ImageCheckBehavior.CLICK_EXECUTE);node.getImageCheckConfig().setClickMode(ImageClickMode.RANDOM);
        AtomicReference<MacroNode> saved=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->{
            ActionEditorDialog edit=ActionEditorDialog.edit(null,node,saved::set,null);
            try{
                ImageJumpEditorTest.layout(edit);
                for(Component component:ImageJumpEditorTest.components(edit)){
                    if("imageSearchScope".equals(component.getName())||"imageSearchWindow".equals(component.getName())||"imageClickMode".equals(component.getName())){
                        Rectangle bounds=SwingUtilities.convertRectangle(component.getParent(),component.getBounds(),edit.getContentPane());
                        assertTrue(bounds.width>50&&bounds.height>20&&bounds.y>=0&&bounds.x>=0&&bounds.x+bounds.width<=edit.getContentPane().getWidth()&&bounds.y+bounds.height<=edit.getContentPane().getHeight());
                    }
                }
                JRootPane root=edit.getRootPane();BufferedImage preview=new BufferedImage(root.getWidth()*3/2,root.getHeight()*3/2,BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics=preview.createGraphics();graphics.scale(1.5,1.5);root.paint(graphics);graphics.dispose();
                ImageIO.write(preview,"png",directory().resolve("window-editor-150.png").toFile());
                ImageJumpEditorTest.button(edit,"保存").doClick();ImageCheckConfig config=saved.get().getImageCheckConfig();
                assertEquals(ImageSearchScope.WINDOW,config.getSearchScope());assertEquals(window(),config.getSearchWindow());assertTrue(config.isScaleMatchingEnabled());assertEquals(ImageClickMode.RANDOM,config.getClickMode());
            }catch(Exception error){throw new AssertionError(error);}finally{edit.dispose();}
        });
    }
    private static TargetWindow window(){return TargetWindow.create().setProcessName("fixture.exe").setWindowClass("FixtureClass").setWindowTitle("用于检查长标题布局的指定窗口名称");}
    private static MacroNode node()throws Exception{Path template=directory().resolve("template.png");ImageIO.write(new BufferedImage(12,12,BufferedImage.TYPE_INT_RGB),"png",template.toFile());return new MacroNode("image",NodeType.IMAGE_CHECK,null,new ImageCheckConfig(template.toString(),.93),new ArrayList<>());}
    private static Path directory()throws Exception{Path root=Path.of(System.getProperty("macro.test.sandboxRoot","target/test-sandboxes")).toAbsolutePath();Files.createDirectories(root);return Files.createTempDirectory(root,"window-editor-");}
    private static Object field(Object target,String name)throws ReflectiveOperationException{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
}
