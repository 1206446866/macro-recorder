package com.macro.flow;

import com.macro.model.MacroAction;
import com.macro.model.TargetWindow;
import com.macro.player.MacroPlayer;
import com.macro.player.PlayerState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import java.awt.*;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** 不发送真实输入，仅验证窗口配置预检与每轮播放的绑定生命周期。 */
@EnabledOnOs(OS.WINDOWS)
class WindowRecognitionPlaybackTest {
    @Test void invalidWindowScopeIsRejectedBeforeEarlierKeyboardAction()throws Exception{
        Fixture fixture=new Fixture();ImageCheckConfig config=config();config.setSearchWindow(null);
        MacroNode key=new MacroNode("key",NodeType.ACTION,MacroAction.keyRelease(65,"A",0),null,new ArrayList<>());
        MacroFlow flow=MacroFlowFactory.rebuildLinear(List.of(key,new MacroNode("image",NodeType.IMAGE_CHECK,null,config,new ArrayList<>())),"end");
        assertThrows(IllegalArgumentException.class,()->fixture.player.play(flow,1,(node,context)->{fail("预检失败不能识别");return null;}));
        assertEquals(0,fixture.robot.inputs.get());assertEquals(PlayerState.IDLE,fixture.player.getState());
    }
    @Test void playbackBindingStartsOncePerPlayAndWindowErrorNeverTriggersClickOrNextAction()throws Exception{
        Fixture fixture=new Fixture();ImageCheckConfig config=config();config.setSuccessBehavior(ImageCheckBehavior.CLICK_EXECUTE);
        MacroNode image=new MacroNode("image",NodeType.IMAGE_CHECK,null,config,new ArrayList<>());
        MacroNode key=new MacroNode("key",NodeType.ACTION,MacroAction.keyRelease(65,"A",0),null,new ArrayList<>());
        MacroFlow flow=MacroFlowFactory.rebuildLinear(List.of(image,key),"end");AtomicInteger starts=new AtomicInteger(),matches=new AtomicInteger();
        ImageMatcher matcher=new ImageMatcher(){
            @Override public void beginPlayback(){starts.incrementAndGet();}
            @Override public ImageMatchResult match(MacroNode node,FlowContext context)throws Exception{matches.incrementAndGet();throw new java.io.IOException("指定窗口不可用");}
        };
        for(int attempt=0;attempt<2;attempt++){
            assertThrows(java.io.IOException.class,()->fixture.player.play(flow,3,matcher));assertEquals(PlayerState.IDLE,fixture.player.getState());
        }
        assertEquals(2,starts.get());assertEquals(2,matches.get());assertEquals(0,fixture.robot.inputs.get());
    }
    private static ImageCheckConfig config(){ImageCheckConfig config=new ImageCheckConfig("synthetic.png",.9);config.setSearchScope(ImageSearchScope.WINDOW);
        config.setSearchWindow(TargetWindow.create().setProcessName("fixture.exe").setWindowClass("FixtureClass").setWindowTitle("窗口"));return config;}
    private static final class Fixture{
        final MacroPlayer player;final CountingRobot robot;
        Fixture()throws Exception{assumeFalse(GraphicsEnvironment.isHeadless());player=new MacroPlayer();robot=new CountingRobot();Field field=MacroPlayer.class.getDeclaredField("robot");field.setAccessible(true);field.set(player,robot);}
    }
    private static final class CountingRobot extends Robot{
        final AtomicInteger inputs=new AtomicInteger();CountingRobot()throws AWTException{}
        @Override public void keyPress(int key){inputs.incrementAndGet();}@Override public void keyRelease(int key){inputs.incrementAndGet();}
        @Override public void mouseMove(int x,int y){inputs.incrementAndGet();}@Override public void mousePress(int mask){inputs.incrementAndGet();}@Override public void mouseRelease(int mask){inputs.incrementAndGet();}
    }
}
