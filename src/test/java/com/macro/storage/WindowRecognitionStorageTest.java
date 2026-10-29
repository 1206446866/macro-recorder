package com.macro.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.macro.flow.*;
import com.macro.model.TargetWindow;
import com.macro.util.ImageTemplatePathUtil;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WindowRecognitionStorageTest {
    @Test void windowDescriptionRoundTripsWithoutRuntimeHandlePidOrCoordinates()throws Exception{
        Path root=directory(),file=root.resolve("window.mr");MacroFlow flow=flow();MacroStorage storage=new MacroStorage();
        storage.save(List.of(),null,flow,file.toString());
        assertEquals(flow,storage.load(file.toString()).getFlow());
        var config=new ObjectMapper().readTree(file.toFile()).path("flow").path("nodeMap").path("image").path("imageCheckConfig");
        assertEquals("WINDOW",config.path("searchScope").asText());
        Set<String> fields=new HashSet<>();config.path("searchWindow").fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("processName","windowTitle","windowClass"),fields);
    }
    @Test void oldOrExplicitNullScopeRemainsDesktopAndWindowDescriptorIsDefensivelyCopied()throws Exception{
        Path root=directory(),file=root.resolve("source.mr");MacroStorage storage=new MacroStorage();storage.save(List.of(),null,flow(),file.toString());
        ObjectMapper mapper=new ObjectMapper();
        for(boolean explicitNull:new boolean[]{false,true}){
            ObjectNode json=(ObjectNode)mapper.readTree(file.toFile());ObjectNode config=(ObjectNode)json.path("flow").path("nodeMap").path("image").path("imageCheckConfig");
            config.remove("searchWindow");if(explicitNull)config.putNull("searchScope");else config.remove("searchScope");
            Path old=root.resolve("old-"+explicitNull+".mr");mapper.writeValue(old.toFile(),json);
            assertEquals(ImageSearchScope.DESKTOP,storage.load(old.toString()).getFlow().getNodeMap().get("image").getImageCheckConfig().getSearchScope());
        }
        ImageCheckConfig config=flow().getNodeMap().get("image").getImageCheckConfig();TargetWindow read=config.getSearchWindow();read.setWindowTitle("changed");
        assertEquals("窗口",config.getSearchWindow().getWindowTitle());
        config.setSearchWindow(read);read.setWindowTitle("changed again");assertEquals("changed",config.getSearchWindow().getWindowTitle());
    }
    @Test void saveAsKeepsWindowScopeAndOriginalConfiguration()throws Exception{
        Path root=directory();MacroFlow original=flow();MacroFlow copy=ImageTemplatePathUtil.forSave(original,root.toFile(),root.resolve("other/macro.mr").toFile());
        ImageCheckConfig before=original.getNodeMap().get("image").getImageCheckConfig(),after=copy.getNodeMap().get("image").getImageCheckConfig();
        assertEquals(ImageSearchScope.WINDOW,after.getSearchScope());assertEquals(before.getSearchWindow(),after.getSearchWindow());
        assertEquals("missing.png",before.getImagePath());assertEquals(root.resolve("missing.png").toString(),after.getImagePath());
        assertTrue(after.isScaleMatchingEnabled());assertEquals(ImageClickMode.RANDOM,after.getClickMode());
    }
    @Test void invalidWindowConfigurationCannotSaveOrLoadAndExistingFileIsPreserved()throws Exception{
        Path root=directory(),file=root.resolve("existing.mr");Files.writeString(file,"existing content");MacroStorage storage=new MacroStorage();MacroFlow flow=flow();
        flow.getNodeMap().get("image").getImageCheckConfig().setSearchWindow(null);
        assertThrows(Exception.class,()->storage.save(List.of(),null,flow,file.toString()));assertEquals("existing content",Files.readString(file));
        Path valid=root.resolve("valid.mr");storage.save(List.of(),null,flow(),valid.toString());ObjectMapper mapper=new ObjectMapper();ObjectNode data=(ObjectNode)mapper.readTree(valid.toFile());
        ((ObjectNode)data.path("flow").path("nodeMap").path("image").path("imageCheckConfig")).putNull("searchWindow");
        Path broken=root.resolve("broken.mr");mapper.writeValue(broken.toFile(),data);assertThrows(Exception.class,()->storage.load(broken.toString()));
    }
    private static MacroFlow flow(){
        ImageCheckConfig config=new ImageCheckConfig("missing.png",.93);config.setSearchScope(ImageSearchScope.WINDOW);
        config.setSearchWindow(TargetWindow.create().setProcessName("fixture.exe").setWindowTitle("窗口").setWindowClass("FixtureClass"));
        config.setScaleMatchingEnabled(true);config.setClickMode(ImageClickMode.RANDOM);
        return MacroFlowFactory.rebuildLinear(List.of(new MacroNode("image",NodeType.IMAGE_CHECK,null,config,new ArrayList<>())),"end");
    }
    private static Path directory()throws Exception{Path root=Path.of(System.getProperty("macro.test.sandboxRoot","target/test-sandboxes")).toAbsolutePath();Files.createDirectories(root);return Files.createTempDirectory(root,"window-storage-");}
}
