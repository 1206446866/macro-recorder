package com.macro.config;

import com.macro.constant.ApplicationInfo;
import lombok.Getter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * 程序运行配置。
 * 负责保存和读取应用自身的配置，
 * 不负责 MR 文件内容。
 */
public class AppConfig {

    private static final String CONFIG_FILE_NAME = "config.properties";
    private static final String LAST_MACRO_FILE = "lastMacroFile";
    private static final String DEFAULT_SAVE_DIRECTORY = "defaultSaveDirectory";
    private static final String DEFAULT_SCREEN_CAPTURE_DIRECTORY =
            "defaultScreenCaptureDirectory";
    private static final String LOAD_LAST_MACRO_ON_STARTUP = "loadLastMacroOnStartup";
    private static final String KEY_RECORD_MOUSE_MOVE = "record.mouseMove";
    private static final String KEY_MOUSE_MOVE_INTERVAL = "record.mouseMoveInterval";
    private static final String KEY_MINIMIZE_ON_RECORDING = "record.minimizeWindow";
    private static final String KEY_MINIMIZE_ON_PLAY_START = "minimizeOnPlayStart";
    private static final String KEY_RESTORE_WINDOW_AFTER_PLAY_STOP =
            "restoreWindowAfterPlayStop";

    /**
     * -- 获取方法 --
     *  获取程序所在目录。
     */
    @Getter
    private final File applicationDirectory;
    private final File configFile;
    private final File defaultSaveDirectory;
    private final File defaultScreenCaptureDirectory;
    private final Properties properties = new Properties();

    public AppConfig() {
        applicationDirectory = resolveApplicationDirectory();
        configFile = new File(applicationDirectory, CONFIG_FILE_NAME);
        defaultSaveDirectory = new File(applicationDirectory, "save");
        defaultScreenCaptureDirectory = new File(
                applicationDirectory,
                "ScreenCapture"
        );
        load();
        initializeDefaultSaveDirectories();
    }

    /**
     * 获取程序所在目录。
     * jpackage 打包后使用 exe 所在目录；IDE 运行时使用项目工作目录。
     */
    private File resolveApplicationDirectory() {
        try {
            File location = new File(AppConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI());

            if (location.isFile()) {
                location = location.getParentFile();
            }

            // jpackage 的 jar 位于 MacroRecorder/app/ 下，向上一级才是程序目录。
            if ("app".equalsIgnoreCase(location.getName()) && location.getParentFile() != null) {
                return location.getParentFile();
            }

            // IDE / Maven 运行时通常位于 target/classes，返回项目目录。
            if ("classes".equalsIgnoreCase(location.getName()) && location.getParentFile() != null && "target".equalsIgnoreCase(location.getParentFile().getName()) && location.getParentFile().getParentFile() != null) {
                return location.getParentFile().getParentFile();
            }

            return location;
        } catch (Exception e) {
            return new File(System.getProperty("user.dir"));
        }
    }

    /**
     * 加载配置文件。
     * 配置文件不存在时自动创建。
     */
    private void load() {
        if (!configFile.exists()) {
            save();
            return;
        }

        try (InputStream inputStream = new FileInputStream(configFile)) {
            properties.load(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("读取配置文件失败：" + e.getMessage());
        }
    }

    /**
     * 获取上一次使用的 MR 文件路径。
     */
    public String getLastMacroFile() {
        return properties.getProperty(LAST_MACRO_FILE);
    }

    /**
     * 保存上一次使用的 MR 文件路径。
     */
    public void setLastMacroFile(File file) {
        if (file == null) {
            properties.remove(LAST_MACRO_FILE);
        } else {
            properties.setProperty(LAST_MACRO_FILE, file.getAbsolutePath());
        }

        save();
    }

    /**
     * 获取默认 MR 保存目录。
     * 未配置时默认使用程序目录下的 save 文件夹。
     */
    public File getDefaultSaveDirectory() {
        String configuredDirectory = properties.getProperty(DEFAULT_SAVE_DIRECTORY);

        if (configuredDirectory == null || configuredDirectory.isBlank()) {
            return defaultSaveDirectory;
        }

        return new File(configuredDirectory);
    }

    /**
     * 设置默认 MR 保存目录。
     */
    public void setDefaultSaveDirectory(File directory) {
        if (directory == null) {
            properties.remove(DEFAULT_SAVE_DIRECTORY);
        } else {
            properties.setProperty(DEFAULT_SAVE_DIRECTORY, directory.getAbsolutePath());
        }

        save();
    }

    /**
     * 获取默认截图保存目录。
     * 未配置时使用程序目录下的 ScreenCapture 文件夹。
     */
    public File getDefaultScreenCaptureDirectory() {
        String configuredDirectory = properties.getProperty(
                DEFAULT_SCREEN_CAPTURE_DIRECTORY
        );
        if (configuredDirectory == null || configuredDirectory.isBlank()) {
            return defaultScreenCaptureDirectory;
        }
        return new File(configuredDirectory);
    }

    /** 设置默认截图保存目录。 */
    public void setDefaultScreenCaptureDirectory(File directory) {
        if (directory == null) {
            properties.remove(DEFAULT_SCREEN_CAPTURE_DIRECTORY);
        } else {
            properties.setProperty(
                    DEFAULT_SCREEN_CAPTURE_DIRECTORY,
                    directory.getAbsolutePath()
            );
        }
        save();
    }

    /**
     * 初始化默认 MR 与截图保存目录。
     */
    private void initializeDefaultSaveDirectories() {
        createDefaultDirectory(defaultSaveDirectory);
        createDefaultDirectory(defaultScreenCaptureDirectory);
    }

    /** 创建尚不存在的默认目录。 */
    private void createDefaultDirectory(File directory) {
        if (!directory.exists() && !directory.mkdirs()) {
            System.err.println("创建默认保存目录失败：" + directory.getAbsolutePath());
        }
    }

    /**
     * 保存配置文件。
     */
    private boolean save() {
        File parent = configFile.getParentFile();

        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            System.err.println("创建配置目录失败：" + parent.getAbsolutePath());
            return false;
        }

        try (OutputStream outputStream = new FileOutputStream(configFile);
             OutputStreamWriter writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8)) {
            properties.store(writer, ApplicationInfo.APP_NAME + " Configuration");
            writer.flush();
            return true;
        } catch (IOException e) {
            System.err.println("保存配置文件失败：" + e.getMessage());
            return false;
        }
    }

    /**
     * 一次性更新设置窗口中的全部配置，避免逐项写入配置文件。
     * 保存失败时恢复内存中的旧配置。
     *
     * @return 配置是否成功保存
     */
    public synchronized boolean updateSettings(
            File saveDirectory,
            File screenCaptureDirectory,
            boolean loadLastMacroOnStartup,
            boolean recordMouseMove,
            int mouseMoveInterval,
            boolean minimizeOnRecording,
            boolean minimizeOnPlayStart,
            boolean restoreWindowAfterPlayStop
    ) {
        Properties previous = new Properties();
        previous.putAll(properties);

        if (saveDirectory == null) {
            properties.remove(DEFAULT_SAVE_DIRECTORY);
        } else {
            properties.setProperty(DEFAULT_SAVE_DIRECTORY, saveDirectory.getAbsolutePath());
        }
        if (screenCaptureDirectory == null) {
            properties.remove(DEFAULT_SCREEN_CAPTURE_DIRECTORY);
        } else {
            properties.setProperty(
                    DEFAULT_SCREEN_CAPTURE_DIRECTORY,
                    screenCaptureDirectory.getAbsolutePath()
            );
        }
        properties.setProperty(LOAD_LAST_MACRO_ON_STARTUP, Boolean.toString(loadLastMacroOnStartup));
        properties.setProperty(KEY_RECORD_MOUSE_MOVE, Boolean.toString(recordMouseMove));
        properties.setProperty(KEY_MOUSE_MOVE_INTERVAL, Integer.toString(mouseMoveInterval));
        properties.setProperty(KEY_MINIMIZE_ON_RECORDING, Boolean.toString(minimizeOnRecording));
        properties.setProperty(KEY_MINIMIZE_ON_PLAY_START, Boolean.toString(minimizeOnPlayStart));
        properties.setProperty(
                KEY_RESTORE_WINDOW_AFTER_PLAY_STOP,
                Boolean.toString(restoreWindowAfterPlayStop)
        );

        if (save()) {
            return true;
        }

        properties.clear();
        properties.putAll(previous);
        return false;
    }

    /**
     * 获取启动时是否自动加载上一次使用的 MR 文件。
     * 默认开启。
     */
    public boolean isLoadLastMacroOnStartup() {
        return Boolean.parseBoolean(
                properties.getProperty(LOAD_LAST_MACRO_ON_STARTUP, "true")
        );
    }

    /**
     * 设置启动时是否自动加载上一次使用的 MR 文件。
     */
    public void setLoadLastMacroOnStartup(boolean enabled) {
        properties.setProperty(
                LOAD_LAST_MACRO_ON_STARTUP,
                Boolean.toString(enabled)
        );
        save();
    }

    /**
     * 是否记录鼠标移动动作。
     *
     * @return true 表示记录鼠标移动，false 表示不记录
     */
    public boolean isRecordMouseMove() {
        return Boolean.parseBoolean(properties.getProperty(KEY_RECORD_MOUSE_MOVE, "true"));
    }

    /**
     * 设置是否记录鼠标移动动作。
     *
     * @param recordMouseMove 是否记录鼠标移动
     */
    public void setRecordMouseMove(boolean recordMouseMove) {
        properties.setProperty(KEY_RECORD_MOUSE_MOVE, String.valueOf(recordMouseMove));
        save();
    }

    /**
     * 获取鼠标轨迹记录间隔。
     *
     * @return 鼠标移动记录的最小时间间隔，单位为毫秒；0 表示不限制
     */
    public int getMouseMoveInterval() {
        String value = properties.getProperty(KEY_MOUSE_MOVE_INTERVAL, "20");
        try {
            int interval = Integer.parseInt(value);
            return interval >= 0 ? interval : 20;
        } catch (NumberFormatException e) {
            System.err.println("鼠标轨迹记录间隔配置无效，使用默认值 20 ms。");
            return 20;
        }
    }

    /**
     * 设置鼠标轨迹记录间隔。
     *
     * @param mouseMoveInterval 鼠标移动记录的最小时间间隔，单位为毫秒；0 表示不限制
     */
    public void setMouseMoveInterval(int mouseMoveInterval) {
        properties.setProperty(
                KEY_MOUSE_MOVE_INTERVAL,
                String.valueOf(mouseMoveInterval)
        );
        save();
    }
    /**
     * 录制开始时是否最小化主窗口。默认开启，保持原有行为。
     */
    public boolean isMinimizeOnRecording() {
        return Boolean.parseBoolean(
                properties.getProperty(KEY_MINIMIZE_ON_RECORDING, "true")
        );
    }

    /**
     * 设置录制开始时是否最小化主窗口。
     *
     * @param minimize 是否最小化主窗口
     */
    public void setMinimizeOnRecording(boolean minimize) {
        properties.setProperty(
                KEY_MINIMIZE_ON_RECORDING,
                Boolean.toString(minimize)
        );
        save();
    }

    /**
     * 播放开始时是否最小化主窗口。默认关闭，保持现有播放行为。
     */
    public boolean isMinimizeOnPlayStart() {
        return Boolean.parseBoolean(
                properties.getProperty(KEY_MINIMIZE_ON_PLAY_START, "false")
        );
    }

    /**
     * 设置播放开始时是否最小化主窗口。
     *
     * @param minimize 是否最小化主窗口
     */
    public void setMinimizeOnPlayStart(boolean minimize) {
        properties.setProperty(
                KEY_MINIMIZE_ON_PLAY_START,
                Boolean.toString(minimize)
        );
        save();
    }

    /**
     * 播放停止后是否恢复由应用最小化的主窗口。默认开启。
     */
    public boolean isRestoreWindowAfterPlayStop() {
        return Boolean.parseBoolean(
                properties.getProperty(KEY_RESTORE_WINDOW_AFTER_PLAY_STOP, "true")
        );
    }

    /**
     * 设置播放停止后是否恢复由应用最小化的主窗口。
     *
     * @param restore 是否恢复主窗口
     */
    public void setRestoreWindowAfterPlayStop(boolean restore) {
        properties.setProperty(
                KEY_RESTORE_WINDOW_AFTER_PLAY_STOP,
                Boolean.toString(restore)
        );
        save();
    }

}
