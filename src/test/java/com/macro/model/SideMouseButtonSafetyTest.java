package com.macro.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.flow.MacroFlow;
import com.macro.flow.MacroFlowFactory;
import com.macro.player.MacroPlayer;
import com.macro.player.PlayerState;
import com.macro.storage.MacroFile;
import com.macro.storage.MacroStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.awt.AWTException;
import java.awt.GraphicsEnvironment;
import java.awt.Robot;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class SideMouseButtonSafetyTest {
    @Test void allStorageEntriesRejectSideButtonsWithoutOverwritingOrLeavingTemporaryFiles() throws Exception {
        Path directory=directory(), target=directory.resolve("valid.mr"), unsafe=directory.resolve("unsafe.mr");
        MacroStorage storage=new MacroStorage();
        List<MacroAction> valid=List.of(MacroAction.mouseMove(1,2,0));
        storage.save(valid,null,target.toString());
        byte[] original=Files.readAllBytes(target);
        for(MouseButton button:List.of(MouseButton.X1,MouseButton.X2)) for(boolean press:List.of(true,false)) {
            MacroAction side=action(button,press);
            MacroFlow flow=MacroFlowFactory.fromActions(List.of(side));
            assertThrows(IllegalArgumentException.class,()->storage.save(List.of(side),null,target.toString()));
            assertThrows(IllegalArgumentException.class,()->storage.save(List.of(side),null,null,target.toString()));
            assertThrows(IllegalArgumentException.class,()->storage.save(List.of(),null,flow,target.toString()));
            // 兼容 actions 与合法 flow 同时存在时，也不能通过 actions 字段带入侧键。
            assertThrows(IllegalArgumentException.class,()->storage.save(List.of(side),null,MacroFlowFactory.fromActions(valid),target.toString()));
            for(MacroFile invalid:List.of(new MacroFile().setActions(List.of(side)),new MacroFile().setActions(List.of()).setFlow(flow))) {
                new ObjectMapper().writeValue(unsafe.toFile(),invalid);
                IllegalArgumentException exception=assertThrows(IllegalArgumentException.class,()->storage.load(unsafe.toString()));
                assertTrue(exception.getMessage().contains("侧键"));
            }
            assertArrayEquals(original,Files.readAllBytes(target));
            assertEquals(valid,storage.load(target.toString()).getActions());
            try(var files=Files.list(directory)) {
                assertEquals(Set.of("valid.mr","unsafe.mr"),files.map(p->p.getFileName().toString()).collect(Collectors.toSet()));
            }
        }
    }

    @Test void ordinaryThreeButtonsKeepTheirStorageBehavior() throws Exception {
        MacroStorage storage=new MacroStorage();
        for(MouseButton button:List.of(MouseButton.LEFT,MouseButton.MIDDLE,MouseButton.RIGHT)) {
            assertTrue(button.isSupported());
            MacroFlow flow=MacroFlowFactory.fromActions(List.of(action(button,true),action(button,false)));
            Path target=directory().resolve("ordinary.mr");
            storage.save(MacroFlowFactory.extractActions(flow),null,flow,target.toString());
            assertEquals(flow,storage.load(target.toString()).getFlow());
        }
        assertFalse(MouseButton.X1.isSupported());
        assertFalse(MouseButton.X2.isSupported());
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void playbackRejectsBothLegacyAndFlowSideButtonsBeforeAnyInput() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        MacroPlayer player=new MacroPlayer();
        CountingRobot robot=new CountingRobot();
        Field field=MacroPlayer.class.getDeclaredField("robot");field.setAccessible(true);field.set(player,robot);
        for(MouseButton button:List.of(MouseButton.X1,MouseButton.X2)) for(boolean press:List.of(true,false)) {
            List<MacroAction> actions=List.of(MacroAction.mouseMove(1,2,0),action(button,press));
            assertThrows(IllegalArgumentException.class,()->player.play(actions,1));
            assertThrows(IllegalArgumentException.class,()->player.play(MacroFlowFactory.fromActions(actions),1));
            assertEquals(0,robot.inputs,"预检必须在前面的普通动作之前拒绝侧键");
            assertEquals(PlayerState.IDLE,player.getState());
        }
        player.play(List.of(action(MouseButton.LEFT,true),action(MouseButton.LEFT,false),
                action(MouseButton.MIDDLE,true),action(MouseButton.MIDDLE,false),
                action(MouseButton.RIGHT,true),action(MouseButton.RIGHT,false)),1);
        assertTrue(robot.inputs>=6,"普通三个按键仍可通过模拟执行器播放");
    }

    private static MacroAction action(MouseButton button,boolean press) {
        return press?MacroAction.mousePress(1,2,button,0):MacroAction.mouseRelease(1,2,button,0);
    }
    private static Path directory() throws Exception {
        Path root=Files.createDirectories(Path.of(System.getProperty("macro.test.sandboxRoot","target/test-sandboxes")));
        return Files.createTempDirectory(root,"side-button-safety-");
    }
    private static class CountingRobot extends Robot {
        int inputs;
        CountingRobot() throws AWTException { }
        @Override public void mouseMove(int x,int y){inputs++;}
        @Override public void mousePress(int buttons){inputs++;}
        @Override public void mouseRelease(int buttons){inputs++;}
        @Override public void mouseWheel(int amount){inputs++;}
        @Override public void keyPress(int key){inputs++;}
        @Override public void keyRelease(int key){inputs++;}
    }
}
