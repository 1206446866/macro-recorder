package com.macro.ui;

import com.macro.flow.*;
import com.macro.model.MacroAction;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.util.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TimelineJumpLayoutTest {
    @Test void remoteLinesCoexistWithRetryAndDefaultConnectorsWithoutAddingNodes() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            try {
                MacroNode left=action("left"),middle=action("middle"),right=action("right");
                ImageCheckConfig retry=new ImageCheckConfig("template.png",.9);
                retry.setSuccessBehavior(ImageCheckBehavior.JUMP);
                MacroNode a=new MacroNode("a",NodeType.IMAGE_CHECK,null,retry,List.of(new MacroBranch(BranchCondition.FOUND,"right",0)));
                ImageCheckConfig back=new ImageCheckConfig("template.png",.9);
                back.setFailureBehavior(ImageCheckBehavior.JUMP);
                MacroNode b=new MacroNode("b",NodeType.IMAGE_CHECK,null,back,List.of(new MacroBranch(BranchCondition.NOT_FOUND,"left",0)));
                ActionTimelinePanel timeline=new ActionTimelinePanel();
                timeline.setFlow(MacroFlowFactory.rebuildLinear(List.of(left,a,middle,b,right),"end"));
                JPanel track=track(timeline);layoutTrack(track);
                assertEquals(2,routes(track).size());assertTargets(track,routes(track));
                int sequenceY = Integer.MIN_VALUE;
                for(Component connector:track.getComponents())if(connector instanceof Container container)
                    for(Component child:container.getComponents())if(child.getClass().getSimpleName().equals("ArrowComponent"))
                        sequenceY=Math.max(sequenceY,SwingUtilities.convertPoint(child,0,child.getHeight()/2,track).y);
                for(var route:routes(track)){
                    int retryY=Arrays.stream(track.getComponents()).filter(c->c.getClass().getSimpleName().equals("NodePanel"))
                            .mapToInt(c->c.getY()+c.getHeight()-42).min().orElseThrow();
                    assertTrue(route.points().get(0).y>=sequenceY+8,"发线必须在真实顺序箭头下方");
                    assertTrue(route.points().get(0).y<=retryY-8,"发线必须在重试端口上方");
                    assertEquals(route.points().get(0).y,route.points().get(5).y);
                }
                assertEquals(6,timeline.getFlow().getNodeMap().size());
                assertEquals("a",MacroFlowFactory.branchFor(a,BranchCondition.NOT_FOUND).getTargetId());
                assertEquals("right",MacroFlowFactory.branchFor(b,BranchCondition.FOUND).getTargetId());
                BufferedImage canvas=new BufferedImage(track.getWidth(),track.getHeight(),BufferedImage.TYPE_INT_ARGB);
                Graphics2D g=canvas.createGraphics();
                try{g.setColor(com.macro.ui.theme.ThemeManager.getPalette().card());g.fillRect(0,0,canvas.getWidth(),canvas.getHeight());track.paint(g);}finally{g.dispose();}
                if(Boolean.getBoolean("macro.jump.preview")) {
                    java.nio.file.Path root=java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/image-jump-preview"));
                    javax.imageio.ImageIO.write(canvas,"png",root.resolve("timeline.png").toFile());
                }
            }catch(Exception exception){throw new AssertionError(exception);}
        });
    }

    @Test void horizontalSideEntryForAdjacentAndDistantTargetsInBothDirections() {
        for (int distance : new int[]{230,920}) for (boolean forward : new boolean[]{false,true}) {
            Rectangle source = new Rectangle(1000,10,158,176);
            Rectangle target = new Rectangle(1000+(forward?distance:-distance),10,158,176);
            var route = TimelineBranchLayout.routes(Map.of("source",source,"target",target),
                    List.of(new TimelineBranchLayout.Edge("source","target","success"))).get(0);
            Point end = route.points().get(5), previous = route.points().get(4);
            assertEquals(end.y,previous.y);
            assertEquals(route.points().get(0).y, end.y, "发线与收线必须同高");
            assertEquals(route.points().get(0).y, route.points().get(1).y);
            assertEquals(Math.abs(route.points().get(1).x-route.points().get(0).x),
                    Math.abs(previous.x-end.x), "两端水平引线长度保持一致");
            assertEquals(forward?target.x:target.x+target.width-1,end.x);
            assertTrue(forward?previous.x<end.x:previous.x>end.x);
            assertTrue(end.y>target.y && end.y<target.y+target.height);
            assertTrue(route.laneY()>source.y+source.height+80);
            assertRoundedHorizontalEndpoints(route);
        }
    }

    @Test void mixedDirectionBranchesHaveDistinctPortsLanesAndReadableLabels() {
        Map<String,Rectangle> cards=new LinkedHashMap<>();
        for(int i=0;i<6;i++)cards.put("n"+i,new Rectangle(50+i*266,10,158,176));
        var routes=TimelineBranchLayout.routes(cards,List.of(
                new TimelineBranchLayout.Edge("n1","n5","成功 · 前跳 06"),
                new TimelineBranchLayout.Edge("n1","n0","未找到 · 回跳 01"),
                new TimelineBranchLayout.Edge("n3","n5","成功 · 前跳 06"),
                new TimelineBranchLayout.Edge("n3","n0","未找到 · 回跳 01"),
                new TimelineBranchLayout.Edge("n4","n0","成功 · 回跳 01"),
                new TimelineBranchLayout.Edge("n4","n5","未找到 · 前跳 06")));
        List<Rectangle> labels=new ArrayList<>();
        for(var route:routes){
            assertEquals(route.points().get(0).y,route.points().get(5).y);
            assertRoundedHorizontalEndpoints(route);
            Rectangle label=TimelineBranchLayout.labelBounds(route,routes,100,1600);
            assertTrue(label.x>=0 && label.x+label.width<=1600);
            for(Rectangle other:labels){
                assertFalse(label.intersects(other));
                assertTrue(Math.abs(label.y-other.y)>=32,"标签之间应留出轨道间距");
            }
            labels.add(label);
            for(var other:routes)for(int i=1;i<other.points().size();i++){
                Point a=other.points().get(i-1),b=other.points().get(i);
                if(a.x==b.x && Math.min(a.y,b.y)<=label.y+label.height && Math.max(a.y,b.y)>=label.y)
                    assertFalse(a.x>=label.x && a.x<=label.x+label.width,"标签不可盖住竖向路径");
            }
        }
        // 窄端口带内按数量均分间距；三个入端口仍保持至少 5px，竖段不共线。
        for(int target:new int[]{0,5}){
            var incoming=routes.stream().filter(r->r.edge().targetId().equals("n"+target)).toList();
            for(int i=1;i<incoming.size();i++){
                assertTrue(Math.abs(incoming.get(i).points().get(5).y-incoming.get(i-1).points().get(5).y)>=5);
                assertNotEquals(incoming.get(i).points().get(4).x,incoming.get(i-1).points().get(4).x);
            }
        }
    }

    private static void assertRoundedHorizontalEndpoints(TimelineBranchLayout.Route route) {
        java.awt.geom.PathIterator iterator=TimelineBranchLayout.path(route).getPathIterator(null);
        double[] coordinates=new double[6];
        Point start=route.points().get(0),end=route.points().get(5);
        double previousX=start.x,previousY=start.y;
        int curves=0,segments=0;
        while(!iterator.isDone()){
            int type=iterator.currentSegment(coordinates);
            if(segments==1){
                assertEquals(java.awt.geom.PathIterator.SEG_LINETO,type);
                assertEquals(start.y,coordinates[1]);
                assertTrue(route.forward()?coordinates[0]>start.x:coordinates[0]<start.x);
            }
            if(type==java.awt.geom.PathIterator.SEG_QUADTO){curves++;previousX=coordinates[2];previousY=coordinates[3];}
            else{
                if(coordinates[0]==end.x && coordinates[1]==end.y){
                    assertEquals(java.awt.geom.PathIterator.SEG_LINETO,type);
                    assertEquals(end.y,previousY);
                    assertTrue(route.forward()?previousX<end.x:previousX>end.x);
                }
                previousX=coordinates[0];previousY=coordinates[1];
            }
            segments++;iterator.next();
        }
        assertEquals(4,curves,"只圆滑中间四个折角");
    }

    @Test void separateLanesAndPortsAndNoDanglingConnections() {
        Map<String,Rectangle> cards=Map.of("s",new Rectangle(400,10,158,176),"t",new Rectangle(900,10,158,176));
        var routes=TimelineBranchLayout.routes(cards,List.of(
                new TimelineBranchLayout.Edge("s","t","success"),new TimelineBranchLayout.Edge("s","t","failure"),
                new TimelineBranchLayout.Edge("s","missing","bad")));
        assertEquals(2,routes.size());
        assertTrue(Math.abs(routes.get(0).laneY()-routes.get(1).laneY())>=32);
        assertNotEquals(routes.get(0).points().get(5),routes.get(1).points().get(5));
        assertNotEquals(routes.get(0).points().get(0),routes.get(1).points().get(0));
        for(var route:routes){
            Rectangle label=TimelineBranchLayout.labelBounds(route,routes,90,1200);
            for(var other:routes)for(int i=1;i<other.points().size();i++){
                Point a=other.points().get(i-1),b=other.points().get(i);
                if(a.x==b.x && Math.min(a.y,b.y)<=route.laneY()+11 && Math.max(a.y,b.y)>=route.laneY()-11)
                    assertFalse(a.x>=label.x && a.x<=label.x+label.width,"标签覆盖其他分支的竖线");
            }
        }
        assertTrue(TimelineBranchLayout.routes(Map.of("s",cards.get("s")),List.of(routes.get(0).edge())).isEmpty());
    }

    @Test void actualTimelineTracksIdsAfterInsertionTargetEditAndDeletion() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            try {
                MacroNode first=action("first"), middle=action("middle"), last=action("last");
                ImageCheckConfig config=new ImageCheckConfig("synthetic.png",.9);
                config.setSuccessBehavior(ImageCheckBehavior.JUMP);config.setFailureBehavior(ImageCheckBehavior.JUMP);
                MacroNode image=new MacroNode("image",NodeType.IMAGE_CHECK,null,config,new ArrayList<>(List.of(
                        new MacroBranch(BranchCondition.FOUND,"last",0),new MacroBranch(BranchCondition.NOT_FOUND,"first",0))));
                ActionTimelinePanel timeline=new ActionTimelinePanel();
                timeline.setFlow(MacroFlowFactory.rebuildLinear(List.of(first,image,middle,last),"end"));
                JPanel track=track(timeline); layoutTrack(track);
                var before=routes(track); assertEquals(2,before.size());
                assertTargets(track,before);
                assertTrue(timeline.insertActionBefore(last,action("inserted")));
                layoutTrack(track);
                var moved=routes(track);assertTargets(track,moved);
                assertNotEquals(before.get(0).points().get(5).x,moved.get(0).points().get(5).x);
                MacroFlowFactory.branchFor(image,BranchCondition.FOUND).setTargetId("middle");
                timeline.refreshTimeline(); layoutTrack(track);
                assertEquals("middle",routes(track).get(0).edge().targetId());
                assertTargets(track,routes(track));
                assertTrue(timeline.deleteAction(first,false));layoutTrack(track);
                assertEquals(1,routes(track).size());
                assertTrue(routes(track).stream().noneMatch(r->r.edge().targetId().equals("first")));
                assertThrows(IllegalArgumentException.class,()->MacroFlowFactory.validateImageJumps(timeline.getFlow()));
                // 实际 Swing 绘制冒烟；仍不代表 Windows 150% DPI 人工验收。
                BufferedImage canvas=new BufferedImage(track.getWidth(),track.getHeight(),BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics=canvas.createGraphics();try{track.paint(graphics);}finally{graphics.dispose();}
            }catch(Exception e){throw new AssertionError(e);}
        });
    }

    static MacroNode action(String id){return new MacroNode(id,NodeType.ACTION,MacroAction.mouseMove(1,2,0),new ArrayList<>());}
    static JPanel track(ActionTimelinePanel timeline)throws Exception{
        Field field=ActionTimelinePanel.class.getDeclaredField("timelinePanel");field.setAccessible(true);return (JPanel)field.get(timeline);
    }
    static void layoutTrack(JPanel track){track.setSize(track.getPreferredSize());layout(track);}
    static void layout(Container c){c.doLayout();for(Component child:c.getComponents())if(child instanceof Container nested)layout(nested);}
    @SuppressWarnings("unchecked") static List<TimelineBranchLayout.Route> routes(JPanel track)throws Exception{
        Method method=track.getClass().getDeclaredMethod("branchRoutes");method.setAccessible(true);return (List<TimelineBranchLayout.Route>)method.invoke(track);
    }
    static void assertTargets(JPanel track,List<TimelineBranchLayout.Route> routes)throws Exception{
        Map<String,Rectangle> bounds=new HashMap<>();
        for(Component child:track.getComponents())if(child.getClass().getSimpleName().equals("NodePanel")){
            Field field=child.getClass().getDeclaredField("macroNode");field.setAccessible(true);
            bounds.put(((MacroNode)field.get(child)).getId(),child.getBounds());
        }
        for(var route:routes){
            Rectangle target=bounds.get(route.edge().targetId());Point end=route.points().get(5);
            assertEquals(route.forward()?target.x:target.x+target.width-1,end.x);
            assertEquals(end.y,route.points().get(4).y);
        }
    }
}
