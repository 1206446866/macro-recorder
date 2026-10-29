package com.macro.ocr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OcrResourcesTest {
    @Test void bundledFilesMatchManifestAndContainOnlyFiveLanguagesAndX64Libraries() throws Exception {
        var manifest = new ObjectMapper().readTree(OcrResourcesTest.class.getResourceAsStream("/ocr/manifest.json"));
        Set<String> models = new HashSet<>(), natives = new HashSet<>();
        for (var entry : manifest) {
            String path = entry.path("path").asText();
            byte[] bytes;
            try (InputStream stream = OcrResourcesTest.class.getResourceAsStream("/ocr/" + path)) {
                assertNotNull(stream, path); bytes = stream.readAllBytes();
            }
            assertEquals(entry.path("bytes").asLong(), bytes.length, path);
            assertEquals(entry.path("sha256").asText(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), path);
            if (path.startsWith("tessdata/")) models.add(path);
            if (path.startsWith("native/")) natives.add(path);
        }
        assertEquals(Set.of("tessdata/chi_sim.traineddata", "tessdata/chi_tra.traineddata", "tessdata/eng.traineddata",
                "tessdata/jpn.traineddata", "tessdata/kor.traineddata"), models);
        assertEquals(Set.of("native/libtesseract534.dll", "native/libleptonica1841.dll"), natives);
    }

    @Test void cacheCanBeReusedAndDamagedFileIsNeverSilentlyOverwritten() throws Exception {
        Path root = directory();
        OcrNativeRuntime.unpack(root, () -> false);
        Path model = root.resolve("tessdata/eng.traineddata");
        var modified = Files.getLastModifiedTime(model);
        OcrNativeRuntime.unpack(root, () -> false);
        assertEquals(modified, Files.getLastModifiedTime(model));
        Files.writeString(model, "damaged fixture");
        assertThrows(java.io.IOException.class, () -> OcrNativeRuntime.unpack(root, () -> false));
        assertEquals("damaged fixture", Files.readString(model));
    }

    @Test void defaultCacheIsInApplicationDirectoryRatherThanSystemTemp() {
        String previous = System.getProperty("macro.ocr.cacheDirectory");
        try {
            System.clearProperty("macro.ocr.cacheDirectory");
            assertEquals(AppConfig.resolveApplicationDirectory().toPath().resolve("ocr-cache").toAbsolutePath().normalize(),
                    OcrNativeRuntime.cacheParent());
        } finally {
            if (previous == null) System.clearProperty("macro.ocr.cacheDirectory");
            else System.setProperty("macro.ocr.cacheDirectory", previous);
        }
    }

    @Test void utf8ExportPreservesTextAndFailureLeavesExistingFilesUntouched() throws Exception {
        Path root = directory();
        Path text = root.resolve("文字.txt");
        OcrTextExport.save(text, "中文\n繁體\n日本語\n한국어\nHELLO");
        assertEquals("中文\n繁體\n日本語\n한국어\nHELLO", Files.readString(text));
        Path obstacle = root.resolve("existing-file"); Files.writeString(obstacle, "original");
        assertThrows(java.io.IOException.class, () -> OcrTextExport.save(obstacle.resolve("child.txt"), "replacement"));
        assertEquals("original", Files.readString(obstacle));
    }

    static Path directory() throws Exception {
        Path root = Path.of(System.getProperty("macro.test.sandboxRoot", "target/test-sandboxes")).toAbsolutePath();
        Files.createDirectories(root); return Files.createTempDirectory(root, "ocr-resources-");
    }
}
