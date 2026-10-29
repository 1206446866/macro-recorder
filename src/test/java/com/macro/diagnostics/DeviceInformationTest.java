package com.macro.diagnostics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeviceInformationTest {
    @Test void collectsEnvironmentMemoryAndScreensWithoutDumpingPrivateProperties() {
        String info = DeviceInformation.collect();
        for (String expected : new String[]{"[操作系统]", "os.name=", "os.arch=", "java.runtime.version=",
                "[CPU 型号]", "[Windows 版本]", "[进程]", "可用处理器=", "JVM heap used=", "[显示器]"})
            assertTrue(info.contains(expected), expected);
        for (String excluded : new String[]{"user.name=", "user.home=", "java.class.path=", "PATH=", "lastMacroFile="})
            assertFalse(info.contains(excluded), excluded);
    }
}
