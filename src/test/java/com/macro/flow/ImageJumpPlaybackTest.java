package com.macro.flow;

import com.macro.model.MacroAction;
import com.macro.player.MacroPlayer;
import com.macro.player.PlayerState;
import com.macro.storage.MacroStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@EnabledOnOs(OS.WINDOWS)
@Timeout(15)
class ImageJumpPlaybackTest {
    static final ImageMatchResult FOUND = ImageMatchResult.found(new Rectangle(1, 2, 3, 4));

    @Test void successSkipsMiddleAndRoundTripsWithoutLosingTargetOnRebuild() throws Exception {
        MacroFlow flow = flow();
        image(flow).getImageCheckConfig().setSuccessBehavior(ImageCheckBehavior.JUMP);
        MacroFlowFactory.branchFor(image(flow), BranchCondition.FOUND).setTargetId("last");
        flow = MacroFlowFactory.rebuildLinear(MacroFlowFactory.timelineNodes(flow), "end");
        Path root = Files.createDirectories(Path.of(System.getProperty("macro.test.sandboxRoot", "target/test-sandboxes")));
        Path file = Files.createTempDirectory(root, "image-jump-").resolve("jump.mr");
        MacroStorage storage = new MacroStorage();
        storage.save(MacroFlowFactory.extractActions(flow), null, flow, file.toString());
        MacroFlow loaded = storage.load(file.toString()).getFlow();
        assertEquals("last", MacroFlowFactory.branchFor(image(loaded), BranchCondition.FOUND).getTargetId());
        Fixture fixture = new Fixture();
        fixture.player.play(loaded, 1, (n,c) -> FOUND);
        assertEquals(List.of(65, 67), fixture.robot.keys);
    }

    @Test void failureGoesBackwardThenFollowsGraphAndCanExitOnSuccess() throws Exception {
        MacroFlow flow = flow();
        image(flow).getImageCheckConfig().setFailureBehavior(ImageCheckBehavior.JUMP);
        MacroFlowFactory.branchFor(image(flow), BranchCondition.NOT_FOUND).setTargetId("first");
        AtomicInteger attempts = new AtomicInteger();
        Fixture fixture = new Fixture();
        fixture.player.play(flow, 1, (n,c) -> attempts.incrementAndGet() == 1 ? ImageMatchResult.notFound() : FOUND);
        assertEquals(List.of(65, 65, 66, 67), fixture.robot.keys);
        assertEquals(2, attempts.get());
    }

    @Test void independentAndSharedTargetsFollowActualResult() throws Exception {
        for (boolean same : new boolean[]{false,true}) for (boolean found : new boolean[]{false,true}) {
            MacroFlow flow = flow();
            image(flow).getImageCheckConfig().setSuccessBehavior(ImageCheckBehavior.JUMP);
            image(flow).getImageCheckConfig().setFailureBehavior(ImageCheckBehavior.JUMP);
            MacroFlowFactory.branchFor(image(flow), BranchCondition.FOUND).setTargetId("last");
            MacroFlowFactory.branchFor(image(flow), BranchCondition.NOT_FOUND).setTargetId(same ? "last" : "middle");
            Fixture fixture = new Fixture();
            fixture.player.play(flow, 1, (n,c) -> found ? FOUND : ImageMatchResult.notFound());
            assertEquals(same || found ? List.of(65,67) : List.of(65,66,67), fixture.robot.keys);
        }
    }

    @Test void missingDeletedSelfAndClosedJumpAreRejectedBeforeInputsOrMatching() throws Exception {
        for (String target : List.of("missing", "check", "first", "middle")) {
            MacroFlow flow = flow();
            image(flow).getImageCheckConfig().setSuccessBehavior(ImageCheckBehavior.JUMP);
            image(flow).getImageCheckConfig().setFailureBehavior(ImageCheckBehavior.JUMP);
            MacroFlowFactory.branchFor(image(flow), BranchCondition.FOUND).setTargetId(target);
            MacroFlowFactory.branchFor(image(flow), BranchCondition.NOT_FOUND).setTargetId(target);
            if (target.equals("middle")) flow.getNodeMap().remove(target);
            Fixture fixture = new Fixture();
            AtomicInteger matches = new AtomicInteger();
            assertThrows(IllegalArgumentException.class, () -> fixture.player.play(flow, 1, (n,c) -> { matches.incrementAndGet(); return FOUND; }));
            assertTrue(fixture.robot.keys.isEmpty());
            assertEquals(0,matches.get());
            assertEquals(PlayerState.IDLE,fixture.player.getState());
        }
    }

    @Test void explicitRetryDefaultAndTerminationKeepTheirSemantics() throws Exception {
        MacroFlow flow = flow();
        image(flow).getImageCheckConfig().setRetryDelay(0L);
        AtomicInteger attempts = new AtomicInteger();
        Fixture fixture = new Fixture();
        fixture.player.play(flow,1,(n,c)-> attempts.incrementAndGet()<3 ? ImageMatchResult.notFound() : FOUND);
        assertEquals(3,attempts.get());
        assertEquals(List.of(65,66,67),fixture.robot.keys);
        image(flow).getImageCheckConfig().setSuccessBehavior(ImageCheckBehavior.TERMINATE);
        fixture = new Fixture();
        fixture.player.play(flow,-1,(n,c)->FOUND);
        assertEquals(List.of(65),fixture.robot.keys);
    }

    static MacroNode image(MacroFlow flow) { return flow.getNodeMap().get("check"); }
    static MacroFlow flow() {
        ImageCheckConfig config = new ImageCheckConfig("synthetic.png",.9);
        MacroNode image = new MacroNode("check",NodeType.IMAGE_CHECK,null,config,new ArrayList<>());
        return MacroFlowFactory.rebuildLinear(List.of(action("first",65),image,action("middle",66),action("last",67)),"end");
    }
    static MacroNode action(String id,int code) {
        return new MacroNode(id,NodeType.ACTION,MacroAction.keyRelease(code,KeyEvent.getKeyText(code),0),new ArrayList<>());
    }
    static class Fixture {
        final MacroPlayer player; final CountingRobot robot;
        Fixture() throws Exception {
            assumeFalse(GraphicsEnvironment.isHeadless(),"计数 Robot 需要桌面构造环境");
            robot = new CountingRobot(); player = new MacroPlayer();
            Field field = MacroPlayer.class.getDeclaredField("robot"); field.setAccessible(true); field.set(player,robot);
        }
    }
    static class CountingRobot extends Robot {
        final List<Integer> keys = new ArrayList<>();
        CountingRobot() throws AWTException { }
        @Override public void keyRelease(int key) { keys.add(key); }
        @Override public void keyPress(int key) { throw new AssertionError("unexpected press"); }
        @Override public void mouseMove(int x,int y) { throw new AssertionError("unexpected move"); }
    }
}
