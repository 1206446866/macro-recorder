package com.macro.testing;

import com.macro.flow.ImageCheckBehaviorTest;
import com.macro.flow.FlowPreflightValidationTest;
import com.macro.config.AppConfigSafetyTest;
import com.macro.player.PlaybackDelayTest;
import com.macro.player.StopKeyListenerTest;
import com.macro.recognition.OpenCvPerfectThresholdTest;
import com.macro.recognition.TemplateMatcherFallbackTest;
import com.macro.recorder.RecordingControlInputFilterTest;
import com.macro.recorder.RecordingMouseCoordinatesTest;
import com.macro.recorder.RecordingPauseTest;
import com.macro.storage.MacroStorageFailureTest;
import com.macro.ui.TimelineEditingTest;
import com.macro.ui.TimelinePlaybackProtectionTest;
import com.macro.ui.TemplatePathIntegrationTest;
import com.macro.ui.filedialog.FileDialogServiceTest;
import com.macro.ui.filedialog.WindowsNativeFileDialogContractTest;
import com.macro.ui.util.NumericInputUtilTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.awt.GraphicsEnvironment;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** 为已审查的独立回归检查提供标准入口，不启动其他桌面测试。 */
class LegacyRegressionTest {
    @Test
    void templatePathResolution() throws Exception {
        LegacyMainRunner.run(TemplatePathIntegrationTest.class, true);
    }

    @Test
    @EnabledOnOs(value = OS.WINDOWS, disabledReason = "实际编辑器、主窗口和计数 Robot 需要 Windows 桌面环境")
    void templatePathPlaybackAndEditor() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "构造未显示窗口与计数 Robot 需要桌面；不发送真实输入");
        LegacyMainRunner.run(TemplatePathIntegrationTest.Desktop.class, false);
    }

    @Test
    void javaFallbackResults() throws Exception {
        LegacyMainRunner.run(TemplateMatcherFallbackTest.class, true);
    }

    @Test
    void openCvPerfectThreshold() throws Exception {
        LegacyMainRunner.run(OpenCvPerfectThresholdTest.class, true);
    }

    @Test
    @EnabledOnOs(value = OS.WINDOWS, disabledReason = "录制器与计数 Robot 检查需要 Windows 桌面环境")
    void pauseHotkeyRepeat() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "构造计数 Robot 需要桌面；不发送真实输入");
        LegacyMainRunner.run(StopKeyListenerTest.class, false);
    }

    @Test
    @EnabledOnOs(value = OS.WINDOWS, disabledReason = "计数 Robot 构造使用 Windows 桌面环境")
    void playbackDelayBounds() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "构造计数 Robot 需要桌面；不发送真实输入");
        LegacyMainRunner.run(PlaybackDelayTest.class, false);
    }

    @Test
    void configurationWriteSafety() throws Exception {
        LegacyMainRunner.run(AppConfigSafetyTest.Write.class, true);
    }

    @Test
    void configurationParseSafety() throws Exception {
        LegacyMainRunner.run(AppConfigSafetyTest.Parse.class, true);
    }

    @Test
    void recordingControlInputFilter() throws Exception {
        LegacyMainRunner.run(RecordingControlInputFilterTest.class, true);
    }

    @Test
    @EnabledOnOs(value = OS.WINDOWS, disabledReason = "录制控件过滤器使用 Windows 原生计时")
    void recordingMouseCoordinates() throws Exception {
        LegacyMainRunner.run(RecordingMouseCoordinatesTest.class, true);
    }

    @Test
    void macroStorageFailure() throws Exception {
        LegacyMainRunner.run(MacroStorageFailureTest.class, true);
    }

    @Test
    void numericInputValidation() throws Exception {
        LegacyMainRunner.run(NumericInputUtilTest.class, true);
    }

    @Test
    void fileDialogServiceContract() throws Exception {
        LegacyMainRunner.run(FileDialogServiceTest.class, true);
    }

    @Test
    void timelineHeadlessEditing() throws Exception {
        LegacyMainRunner.run(TimelineEditingTest.class, true);
    }

    @Test
    void timelinePlaybackProtection() throws Exception {
        LegacyMainRunner.run(TimelinePlaybackProtectionTest.class, true);
    }

    @Test
    @EnabledOnOs(value = OS.WINDOWS, disabledReason = "实际播放器及编辑器构造需要 Windows 桌面")
    void timelineLivePlaybackProtection() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "构造 Robot 和未显示的编辑器需要桌面；不发送真实输入");
        LegacyMainRunner.run(TimelinePlaybackProtectionTest.Desktop.class, false);
    }

    @Test
    @EnabledOnOs(value = OS.WINDOWS, disabledReason = "录制器依赖 Windows DPI、指针查询及原生计时")
    void recordingPause() throws Exception {
        LegacyMainRunner.run(RecordingPauseTest.class, true);
    }

    @Test
    @EnabledOnOs(value = OS.WINDOWS, disabledReason = "原生文件面板契约需要 Windows COM")
    void windowsNativeFileDialogContract() throws Exception {
        LegacyMainRunner.run(WindowsNativeFileDialogContractTest.class, true);
    }

    @Test
    @EnabledOnOs(value = OS.WINDOWS, disabledReason = "现有图像分支主程序在 Windows 桌面环境验证")
    void imageCheckBehaviors() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "旧式分支测试会构造 Robot，需要桌面；不发送真实输入");
        LegacyMainRunner.run(ImageCheckBehaviorTest.class, false);
    }

    @Test
    @EnabledOnOs(value = OS.WINDOWS, disabledReason = "计数 Robot 构造使用 Windows 桌面环境")
    void flowPreflightValidation() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "构造计数 Robot 需要桌面；不发送真实输入");
        LegacyMainRunner.run(FlowPreflightValidationTest.class, false);
    }
}
