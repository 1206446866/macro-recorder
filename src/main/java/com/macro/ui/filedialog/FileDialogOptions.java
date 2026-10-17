package com.macro.ui.filedialog;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** 与平台无关的文件面板配置，不承担文件读写或业务校验。 */
public record FileDialogOptions(Mode mode, String title, File initialDirectory,
        String fileName, List<FileType> fileTypes, String defaultExtension,
        boolean allowAllFiles, boolean confirmOverwrite) {

    public enum Mode { OPEN_FILE, SAVE_FILE, SELECT_DIRECTORY }

    public FileDialogOptions {
        Objects.requireNonNull(mode, "必须指定文件面板用途");
        title = title == null ? "" : title;
        fileName = fileName == null ? "" : fileName;
        fileTypes = List.copyOf(fileTypes);
        defaultExtension = defaultExtension == null || defaultExtension.isBlank()
                ? "" : normalizeExtension(defaultExtension);
    }

    /** 一个文件类型允许多个扩展名，例如同时显示 JPG 与 JPEG。 */
    public record FileType(String description, List<String> extensions) {
        public FileType {
            Objects.requireNonNull(description, "文件类型说明不能为空");
            extensions = extensions.stream().map(FileDialogOptions::normalizeExtension).toList();
            if (extensions.isEmpty()) throw new IllegalArgumentException("文件类型至少需要一个扩展名");
        }
    }

    private static String normalizeExtension(String extension) {
        Objects.requireNonNull(extension, "扩展名不能为空");
        String value = extension.startsWith(".") ? extension.substring(1) : extension;
        if (!value.matches("[A-Za-z0-9]+")) throw new IllegalArgumentException("无效扩展名：" + extension);
        return value.toLowerCase(Locale.ROOT);
    }

    public static Builder builder(Mode mode) { return new Builder(mode); }

    public static final class Builder {
        private final Mode mode;
        private String title = "";
        private File directory;
        private String fileName = "";
        private final List<FileType> types = new ArrayList<>();
        private String extension = "";
        private boolean allFiles;
        private boolean overwrite = true;

        private Builder(Mode mode) { this.mode = mode; }
        public Builder title(String value) { title = value; return this; }
        public Builder initialDirectory(File value) { directory = value; return this; }
        public Builder fileName(String value) { fileName = value; return this; }
        public Builder fileType(String description, String... extensions) {
            types.add(new FileType(description, Arrays.asList(extensions))); return this;
        }
        public Builder defaultExtension(String value) { extension = value; return this; }
        public Builder allowAllFiles(boolean value) { allFiles = value; return this; }
        public Builder confirmOverwrite(boolean value) { overwrite = value; return this; }

        /** 复用当前文件所在目录及文件名，不改变业务层的 currentFile。 */
        public Builder initialFile(File value) {
            if (value != null) {
                File absolute = value.getAbsoluteFile();
                directory = absolute.getParentFile();
                fileName = absolute.getName();
            }
            return this;
        }

        public FileDialogOptions build() {
            return new FileDialogOptions(mode, title, directory, fileName, types,
                    extension, allFiles, overwrite);
        }
    }
}
