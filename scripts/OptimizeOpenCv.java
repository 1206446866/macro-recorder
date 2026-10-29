import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** 为 Windows x64 发行包重建 OpenCV 副本，保留类、加载器、许可证及本机库资源路径。 */
public final class OptimizeOpenCv {
    private OptimizeOpenCv() { }

    public static void main(String[] args) throws IOException {
        Path source = Path.of(args[0]);
        Path output = Path.of(args[1]);
        try (ZipFile archive = new ZipFile(source.toFile())) {
            List<? extends ZipEntry> entries = archive.stream().toList();
            if (entries.stream().anyMatch(entry -> entry.getName().matches("(?i)META-INF/.*\\.(SF|RSA|DSA)"))) {
                throw new IOException("OpenCV JAR is signed; trimming requires a packaging review.");
            }
            long windowsLibraries = entries.stream().filter(entry ->
                    entry.getName().matches("nu/pattern/opencv/windows/x86_64/[^/]+\\.dll")).count();
            if (windowsLibraries != 1) {
                throw new IOException("Expected exactly one Windows x64 OpenCV native library.");
            }
            try (ZipOutputStream destination = new ZipOutputStream(Files.newOutputStream(output,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
                destination.setLevel(9);
                for (ZipEntry original : entries) {
                    String name = original.getName();
                    if (name.matches("nu/pattern/opencv/(linux|osx|windows)/.*")
                            && !name.startsWith("nu/pattern/opencv/windows/x86_64/")) continue;
                    ZipEntry entry = new ZipEntry(name);
                    entry.setTime(original.getTime());
                    destination.putNextEntry(entry);
                    try (var input = archive.getInputStream(original)) { input.transferTo(destination); }
                    destination.closeEntry();
                }
            }
        }
    }
}
