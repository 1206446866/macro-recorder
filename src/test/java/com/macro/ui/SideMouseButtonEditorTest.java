package com.macro.ui;

import com.macro.flow.MacroNode;
import com.macro.flow.NodeType;
import com.macro.model.MacroAction;
import com.macro.model.MouseButton;
import com.macro.ui.dialog.AppMessageDialog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@EnabledOnOs(OS.WINDOWS)
class SideMouseButtonEditorTest {
    @Test void pressAndReleaseMenusHideSideButtonsAndNeverSilentlyConvertOldValues() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(()->{
            for(MouseButton side:List.of(MouseButton.X1,MouseButton.X2)) for(boolean press:List.of(true,false)) {
                MacroAction action=press?MacroAction.mousePress(1,2,side,0):MacroAction.mouseRelease(1,2,side,0);
                MacroNode source=new MacroNode("side",NodeType.ACTION,action,new ArrayList<>());
                AtomicReference<MacroNode> saved=new AtomicReference<>();
                ActionEditorDialog dialog=ActionEditorDialog.edit(null,source,saved::set,ignored->{});
                try {
                    JComboBox<?> combo=(JComboBox<?>)ImageJumpEditorTest.components(dialog).stream()
                            .filter(c->c instanceof JComboBox<?> box&&box.getItemCount()>0&&box.getItemAt(0) instanceof MouseButton)
                            .findFirst().orElseThrow();
                    assertEquals(3,combo.getItemCount());
                    assertEquals(List.of(MouseButton.LEFT,MouseButton.MIDDLE,MouseButton.RIGHT),
                            java.util.stream.IntStream.range(0,combo.getItemCount()).mapToObj(combo::getItemAt).toList());
                    assertNull(combo.getSelectedItem(),"旧侧键不能被静默替换成左键");
                    var panelField=ActionEditorDialog.class.getDeclaredField("currentPropertyPanel");panelField.setAccessible(true);
                    Object panel=panelField.get(dialog);
                    var read=panel.getClass().getSuperclass().getDeclaredMethod("getMouseButton");read.setAccessible(true);
                    combo.getModel().setSelectedItem(side);
                    InvocationTargetException exception=assertThrows(InvocationTargetException.class,()->read.invoke(panel));
                    assertInstanceOf(IllegalArgumentException.class,exception.getCause());
                    combo.setSelectedItem(MouseButton.LEFT);
                    ImageJumpEditorTest.button(dialog,"保存").doClick();
                    assertNotNull(saved.get());assertEquals(MouseButton.LEFT,saved.get().getAction().getMouseButton());
                    assertEquals(side,source.getAction().getMouseButton());
                } catch(Exception e){throw new AssertionError(e);} finally{dialog.dispose();}
            }
        });
    }

    @Test void dialogDraggingIgnoresSideButtonsAndPreservesNormalLeftDrag() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(()->{
            AppMessageDialog dialog=null;
            try {
                Class<?> type=Class.forName("com.macro.ui.dialog.MessageType");
                var constructor=AppMessageDialog.class.getDeclaredConstructor(Window.class,type,String.class,String.class,Throwable.class,boolean.class);
                constructor.setAccessible(true);
                dialog=(AppMessageDialog)constructor.newInstance(null,type.getEnumConstants()[0],"fixture","fixture",null,false);
                JPanel surface=new JPanel();
                var install=AppMessageDialog.class.getDeclaredMethod("installWindowDragging",Component.class);install.setAccessible(true);install.invoke(dialog,surface);
                var listener=surface.getMouseListeners()[0];
                var motion=surface.getMouseMotionListeners()[0];
                dialog.setLocation(50,60);
                for(int side:new int[]{4,5}) {
                    listener.mousePressed(event(surface,MouseEvent.MOUSE_PRESSED,side,0,100,100));
                    motion.mouseDragged(event(surface,MouseEvent.MOUSE_DRAGGED,0,0,200,220));
                    listener.mouseReleased(event(surface,MouseEvent.MOUSE_RELEASED,side,0,200,220));
                    assertEquals(new Point(50,60),dialog.getLocation());
                }
                listener.mousePressed(event(surface,MouseEvent.MOUSE_PRESSED,MouseEvent.BUTTON1,MouseEvent.BUTTON1_DOWN_MASK,100,100));
                listener.mousePressed(event(surface,MouseEvent.MOUSE_PRESSED,4,MouseEvent.BUTTON1_DOWN_MASK,150,160));
                listener.mouseReleased(event(surface,MouseEvent.MOUSE_RELEASED,4,MouseEvent.BUTTON1_DOWN_MASK,150,160));
                motion.mouseDragged(event(surface,MouseEvent.MOUSE_DRAGGED,0,MouseEvent.BUTTON1_DOWN_MASK,200,220));
                assertEquals(new Point(195,215),dialog.getLocation(),"侧键不能中断或发起左键拖动");
                listener.mouseReleased(event(surface,MouseEvent.MOUSE_RELEASED,MouseEvent.BUTTON1,0,200,220));
                motion.mouseDragged(event(surface,MouseEvent.MOUSE_DRAGGED,0,MouseEvent.BUTTON1_DOWN_MASK,300,300));
                assertEquals(new Point(195,215),dialog.getLocation(),"左键释放后必须清除拖动状态");
            } catch(Exception e){throw new AssertionError(e);} finally{if(dialog!=null)dialog.dispose();}
        });
    }

    private static MouseEvent event(Component surface,int id,int button,int modifiers,int screenX,int screenY) {
        // 使用模拟按钮编号，避免测试依赖系统报告的物理鼠标按键数量。
        return new MouseEvent(surface,id,0,modifiers,5,5,screenX,screenY,1,false,MouseEvent.NOBUTTON) {
            @Override public int getButton(){return button;}
        };
    }
}
