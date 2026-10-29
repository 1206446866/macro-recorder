package com.macro.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.config.AppConfig;
import com.sun.jna.*;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.win32.StdCallLibrary;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** 延迟展开经过校验的离线资源，复用已有 JNA，不引入 PDF 或其他平台依赖。 */
final class OcrNativeRuntime {
    private static RuntimeBundle loaded;
    private static final List<NativeLibrary> NATIVE_LIBRARIES = new ArrayList<>();

    static synchronized RuntimeBundle load(BooleanSupplier cancelled) throws IOException {
        checkCancelled(cancelled);
        if (loaded != null) return loaded;
        if (!Platform.isWindows() || Native.POINTER_SIZE != 8
                || !System.getProperty("os.arch", "").matches("(?i)amd64|x86_64")) {
            throw new IOException("文字提取目前支持 Windows x64");
        }
        Path parent = cacheParent();
        Files.createDirectories(parent);
        String bundleId;
        try (InputStream manifest = resource("manifest.json")) {
            bundleId = HexFormat.of().formatHex(sha256().digest(manifest.readAllBytes())).substring(0, 16);
        }
        Path directory = parent.resolve("runtime-" + bundleId);
        Files.createDirectories(directory);
        try {
            unpack(directory, cancelled);
        } catch (IOException invalidCache) {
            // 不覆盖已经存在但不完整或损坏的缓存，保留原文件并使用独立目录。
            directory = Files.createTempDirectory(parent, "runtime-" + bundleId + "-");
            unpack(directory, cancelled);
        }
        try {
            // jlink/JDK 自带这些运行库，显式加载也适用于 jpackage 启动器。
            for (String name : List.of("vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll")) {
                Path library = Path.of(System.getProperty("java.home"), "bin", name);
                if (Files.isRegularFile(library)) NATIVE_LIBRARIES.add(NativeLibrary.getInstance(library.toAbsolutePath().toString()));
            }
            NATIVE_LIBRARIES.add(NativeLibrary.getInstance(directory.resolve("native/libleptonica1841.dll").toString()));
            // 此 Windows 构建的 C 文件接口使用系统 ANSI 代码页；不能跟随 Java 的 UTF-8 默认编码。
            int codePage = WindowsCodePage.INSTANCE.GetACP();
            Charset paths = Charset.forName(codePage == 65001 ? "UTF-8" : "cp" + codePage);
            Path tessdata = directory.resolve("tessdata");
            String dataPath = tessdata.toString();
            if (!paths.newEncoder().canEncode(dataPath)) {
                char[] shortPath = new char[32768];
                int length = Kernel32.INSTANCE.GetShortPathName(dataPath, shortPath, shortPath.length);
                if (length > 0 && length < shortPath.length) dataPath = new String(shortPath, 0, length);
                if (!paths.newEncoder().canEncode(dataPath)) {
                    throw new IOException("文字提取组件无法读取当前缓存目录，请将程序放在系统代码页支持的路径后重试");
                }
            }
            TesseractApi api = Native.load(directory.resolve("native/libtesseract534.dll").toString(), TesseractApi.class,
                    Map.of(Library.OPTION_STRING_ENCODING, paths.name()));
            loaded = new RuntimeBundle(tessdata, dataPath, api);
            return loaded;
        } catch (LinkageError | RuntimeException error) {
            throw new IOException("无法加载文字提取组件，请确认使用 Windows x64 完整运行环境", error);
        }
    }

    static void unpack(Path directory, BooleanSupplier cancelled) throws IOException {
        JsonNode entries;
        try (InputStream manifest = resource("manifest.json")) {
            entries = new ObjectMapper().readTree(manifest);
        }
        if (!entries.isArray()) throw new IOException("OCR 资源清单无效");
        for (JsonNode entry : entries) {
            checkCancelled(cancelled);
            String name = entry.path("path").asText();
            if (!name.matches("(?:native/[A-Za-z0-9_]+\\.dll|tessdata/[a-z_]+\\.traineddata|licenses/[A-Za-z0-9_.-]+\\.txt)")) {
                throw new IOException("OCR 资源路径无效：" + name);
            }
            Path file = directory.resolve(name);
            Files.createDirectories(file.getParent());
            if (Files.exists(file)) {
                if (!validFile(file, entry, cancelled)) throw new IOException("OCR 缓存校验失败：" + name);
                continue;
            }
            MessageDigest digest = sha256();
            long bytes = 0;
            try (InputStream input = resource(name);
                 OutputStream output = Files.newOutputStream(file, java.nio.file.StandardOpenOption.CREATE_NEW)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    checkCancelled(cancelled);
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                    bytes += read;
                }
            }
            if (bytes != entry.path("bytes").asLong(-1)
                    || !HexFormat.of().formatHex(digest.digest()).equals(entry.path("sha256").asText())) {
                throw new IOException("OCR 资源校验失败：" + name);
            }
        }
    }

    static Path cacheParent() {
        return Path.of(System.getProperty("macro.ocr.cacheDirectory",
                AppConfig.resolveApplicationDirectory().toPath().resolve("ocr-cache").toString())).toAbsolutePath().normalize();
    }

    private static boolean validFile(Path file, JsonNode entry, BooleanSupplier cancelled) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) != entry.path("bytes").asLong(-1)) return false;
        MessageDigest digest = sha256();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                checkCancelled(cancelled);
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest()).equals(entry.path("sha256").asText());
    }

    private static InputStream resource(String name) throws IOException {
        InputStream stream = OcrNativeRuntime.class.getResourceAsStream("/ocr/" + name);
        if (stream == null) throw new IOException("缺少 OCR 资源：" + name);
        return stream;
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static void checkCancelled(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
            throw new CancellationException("文字提取已取消");
        }
    }

    record RuntimeBundle(Path tessdata, String nativeDataPath, TesseractApi api) { }

    private interface WindowsCodePage extends StdCallLibrary {
        WindowsCodePage INSTANCE = Native.load("kernel32", WindowsCodePage.class);
        int GetACP();
    }

    public interface TesseractApi extends Library {
        Pointer TessBaseAPICreate();
        int TessBaseAPIInit3(Pointer handle, String datapath, String language);
        void TessBaseAPISetPageSegMode(Pointer handle, int mode);
        void TessBaseAPISetImage(Pointer handle, Pointer image, int width, int height, int bytesPerPixel, int bytesPerLine);
        int TessBaseAPIRecognize(Pointer handle, Pointer monitor);
        Pointer TessBaseAPIGetUTF8Text(Pointer handle);
        void TessDeleteText(Pointer text);
        void TessBaseAPIDelete(Pointer handle);
        Pointer TessMonitorCreate();
        void TessMonitorDelete(Pointer monitor);
        void TessMonitorSetDeadlineMSecs(Pointer monitor, int milliseconds);
        void TessMonitorSetCancelFunc(Pointer monitor, CancelCallback callback);
    }

    public interface CancelCallback extends Callback {
        byte invoke(Pointer context, int words);
    }
}
