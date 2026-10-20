package com.macro.testing;

import com.macro.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证错误不会被适配入口吞掉，且 AppConfig 和临时文件确实落在隔离目录。 */
class LegacyMainRunnerTest {
    @Test
    void legacyAssertionFailureIsPropagated() {
        AssertionError failure = assertThrows(AssertionError.class,
                () -> LegacyMainRunner.run(FailingMain.class, true));
        assertTrue(failure.getMessage().contains("退出码：1"));
        assertTrue(failure.getMessage().contains("预期的机制验证失败"));
    }

    @Test
    void applicationAndTemporaryFilesAreIsolated() throws Exception {
        Path directory = LegacyMainRunner.run(IsolationMain.class, true);
        assertTrue(Files.isRegularFile(directory.resolve("classes/config.properties")));
        assertEquals(directory.toString(), Files.readString(directory.resolve("classes/isolation-marker.txt")));
        assertTrue(Files.isRegularFile(directory.resolve("relative-marker.txt")));
    }

    public static final class FailingMain {
        public static void main(String[] args) {
            throw new AssertionError("预期的机制验证失败");
        }
    }

    public static final class IsolationMain {
        public static void main(String[] args) throws Exception {
            Path directory = Path.of("").toAbsolutePath().normalize();
            AppConfig config = new AppConfig();
            Path application = config.getApplicationDirectory().toPath().toRealPath();
            if (!application.equals(directory.resolve("classes"))) {
                throw new AssertionError("AppConfig 没有使用隔离应用目录");
            }
            Path temporaryFile = Files.createTempFile("isolation-", ".tmp");
            if (!temporaryFile.startsWith(directory.resolve("tmp"))) {
                throw new AssertionError("临时文件逃离测试目录");
            }
            Files.writeString(application.resolve("isolation-marker.txt"), directory.toString());
            Files.writeString(directory.resolve("relative-marker.txt"), "相对路径仍处于测试目录");
        }
    }
}
