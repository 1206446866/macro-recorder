package com.macro.ui;

import com.macro.constant.UiConstants;
import com.macro.constant.ApplicationInfo;

import com.macro.config.AppConfig;
import com.macro.config.RecordingStartBehavior;
import com.macro.config.TimelineInsertPosition;
import com.macro.ui.dialog.AppMessageDialog;
import com.macro.ui.filedialog.FileDialogException;
import com.macro.ui.filedialog.FileDialogOptions;
import com.macro.ui.filedialog.FileDialogService;
import com.macro.ui.filedialog.FileDialogServiceFactory;
import com.macro.ui.theme.ThemeManager;
import com.macro.ui.theme.ThemePalette;
import com.macro.ui.util.ComponentStyleUtil;
import com.macro.ui.window.WindowTitleBarSupport;
import com.macro.ui.util.NumericInputUtil;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import com.macro.ui.util.DarkButtonUI;
import lombok.Getter;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.File;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/** 程序设置窗口。 */
public class SettingsDialog extends JDialog {
    private final WindowTitleBarSupport titleBarSupport;

    /** 设置窗口仍使用原有模态机制，在 HWND 创建后启用统一标题栏。 */
    @Override public void addNotify() {
        super.addNotify();
        if (titleBarSupport != null) titleBarSupport.activate();
    }

    /** 原生句柄销毁前卸载标题栏，不触发设置保存。 */
    @Override public void removeNotify() {
        try { if (titleBarSupport != null) titleBarSupport.deactivate(); }
        finally { super.removeNotify(); }
    }

    private static final ThemePalette THEME = ThemeManager.getPalette();
    private static final Font FONT_NORMAL = new Font(UiConstants.DEFAULT_FONT_FAMILY, Font.PLAIN, 13);
    private static final Font FONT_BOLD = new Font(UiConstants.DEFAULT_FONT_FAMILY, Font.BOLD, 13);
    private static final Font FONT_SECTION = new Font(UiConstants.DEFAULT_FONT_FAMILY, Font.BOLD, 16);
    private static final Font FONT_TITLE = new Font(UiConstants.DEFAULT_FONT_FAMILY, Font.BOLD, 25);

    private final AppConfig appConfig;
    private final FileDialogService fileDialogs = FileDialogServiceFactory.create();
    private final JTextField defaultSaveDirectoryField = new JTextField();
    private final JTextField screenCaptureDirectoryField = new JTextField();
    private final JCheckBox loadLastMacroOnStartupCheckBox =
            new JCheckBox("启动时自动加载上次 MR");
    private final JButton chooseDirectoryButton = new JButton("选择目录");
    private final JButton chooseScreenCaptureDirectoryButton =
            new JButton("选择目录");
    private final JButton cancelButton = new JButton("取消");
    private final JButton saveButton = new JButton("保存");
    private final JCheckBox recordMouseMoveCheckBox = new JCheckBox("录制鼠标移动");
    private final JCheckBox standbyHeadCheckBox = new JCheckBox("增加待机头节点");
    /**
     * -- GETTER --
     * 返回本次设置窗口是否成功保存，供主窗口区分取消与确认。
     */
    @Getter
    private boolean settingsSaved;
    private final JRadioButton recordingMinimizeButton = new JRadioButton("最小化");
    private final JRadioButton recordingControlBarButton = new JRadioButton("控制栏");
    private final JRadioButton recordingUnchangedButton = new JRadioButton("无");
    private final JRadioButton insertBeforeButton = new JRadioButton("向前");
    private final JRadioButton insertAfterButton = new JRadioButton("向后");
    private final JCheckBox minimizeOnPlayStartCheckBox =
            new JCheckBox("开始播放时最小化主窗口");
    private final JCheckBox restoreWindowAfterPlayStopCheckBox =
            new JCheckBox("停止播放后恢复窗口");
    private final JSpinner mouseMoveIntervalSpinner =
            new JSpinner(new SpinnerNumberModel(20, 0, Integer.MAX_VALUE, 1));

    // 第一阶段仅提供界面结构，尚未写入 AppConfig。
    private final JCheckBox restoreWindowAfterRecordingCheckBox =
            new JCheckBox("停止录制后恢复窗口");
    private final JCheckBox rememberTargetWindowCheckBox =
            new JCheckBox("记住上次选择的目标窗口");

    /** 绑定应用配置，组装设置分类和保存入口，再加载当前配置到表单。 */
    public SettingsDialog(Frame owner, AppConfig appConfig) {
        super(owner, "设置", true);
        this.appConfig = appConfig;
        initDialog();
        initLayout();
        initEvents();
        loadSettings();
        titleBarSupport = new WindowTitleBarSupport(this);
    }

    /** 设置对话框大小、最小尺寸和相对父窗口的位置。 */
    private void initDialog() {
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(920, 640);
        setMinimumSize(new Dimension(820, 560));
        setLocationRelativeTo(getOwner());
    }

    /** 组装侧栏、分类内容和底部按钮，并安装默认保存与 Esc 取消行为。 */
    private void initLayout() {
        JPanel rootPanel = new JPanel(new BorderLayout());
        rootPanel.setBackground(THEME.background());

        SettingsContentPanel contentPanel = new SettingsContentPanel();
        SettingsSidebar sidebar = new SettingsSidebar(contentPanel::showSection);

        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.setOpaque(false);
        rightPanel.add(contentPanel, BorderLayout.CENTER);
        rightPanel.add(createFooter(), BorderLayout.SOUTH);

        rootPanel.add(sidebar, BorderLayout.WEST);
        rootPanel.add(rightPanel, BorderLayout.CENTER);
        setContentPane(rootPanel);

        getRootPane().setDefaultButton(saveButton);
        getRootPane().registerKeyboardAction(
                e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW
        );
    }

    /** 创建设置保存与取消按钮区域，使用现有主题和分隔边框。 */
    private JPanel createFooter() {
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 14));
        footer.setBackground(THEME.background());
        footer.setBorder(new CompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, THEME.border()),
                new EmptyBorder(0, 24, 0, 24)
        ));

        styleSecondaryButton(cancelButton);
        stylePrimaryButton(saveButton);
        cancelButton.setIcon(new SettingsIcon(SettingsIconType.CLOSE));
        saveButton.setIcon(new SettingsIcon(SettingsIconType.CHECK));
        cancelButton.setPreferredSize(new Dimension(104, 42));
        saveButton.setPreferredSize(new Dimension(104, 42));
        footer.add(cancelButton);
        footer.add(saveButton);
        return footer;
    }

    /** 将目录选择、保存和取消按钮绑定到统一设置操作。 */
    private void initEvents() {
        chooseDirectoryButton.addActionListener(e -> chooseDefaultSaveDirectory());
        chooseScreenCaptureDirectoryButton.addActionListener(
                e -> chooseScreenCaptureDirectory()
        );
        cancelButton.addActionListener(e -> dispose());
        saveButton.addActionListener(e -> saveSettings());
    }

    /** 读取当前配置并填充各分类控件，尚未开放修改的固定行为保持禁用。 */
    private void loadSettings() {
        File directory = appConfig.getDefaultSaveDirectory();
        defaultSaveDirectoryField.setText(directory.getAbsolutePath());
        screenCaptureDirectoryField.setText(
                appConfig.getDefaultScreenCaptureDirectory().getAbsolutePath()
        );
        loadLastMacroOnStartupCheckBox.setSelected(appConfig.isLoadLastMacroOnStartup());
        recordMouseMoveCheckBox.setSelected(appConfig.isRecordMouseMove());
        mouseMoveIntervalSpinner.setValue(appConfig.getMouseMoveInterval());
        switch (appConfig.getRecordingStartBehavior()) {
            case MINIMIZE -> recordingMinimizeButton.setSelected(true);
            case CONTROL_BAR -> recordingControlBarButton.setSelected(true);
            case NONE -> recordingUnchangedButton.setSelected(true);
        }
        insertBeforeButton.setSelected(appConfig.getTimelineInsertPosition() == TimelineInsertPosition.BEFORE);
        insertAfterButton.setSelected(appConfig.getTimelineInsertPosition() == TimelineInsertPosition.AFTER);
        minimizeOnPlayStartCheckBox.setSelected(appConfig.isMinimizeOnPlayStart());
        restoreWindowAfterPlayStopCheckBox.setSelected(
                appConfig.isRestoreWindowAfterPlayStop()
        );
        standbyHeadCheckBox.setSelected(appConfig.isStandbyHeadEnabled());

        // 这两个选项与现有固定行为一致，第一阶段不持久化。
        restoreWindowAfterRecordingCheckBox.setSelected(true);
        rememberTargetWindowCheckBox.setSelected(true);
    }

    /** 通过现有原生目录面板选择默认宏目录，确认后只更新待保存表单。 */
    private void chooseDefaultSaveDirectory() {
        chooseDirectory(
                "选择默认 MR 保存目录",
                appConfig.getDefaultSaveDirectory(),
                defaultSaveDirectoryField
        );
    }

    /** 选择默认截图保存目录。 */
    private void chooseScreenCaptureDirectory() {
        chooseDirectory(
                "选择默认截图保存目录",
                appConfig.getDefaultScreenCaptureDirectory(),
                screenCaptureDirectoryField
        );
    }

    /** 使用现有目录选择流程更新指定路径输入框。 */
    private void chooseDirectory(
            String dialogTitle,
            File currentDirectory,
            JTextField targetField
    ) {
        try {
            fileDialogs.showDialog(this, FileDialogOptions.builder(FileDialogOptions.Mode.SELECT_DIRECTORY)
                    .title(dialogTitle).initialDirectory(currentDirectory).build())
                    .ifPresent(file -> targetField.setText(file.getAbsolutePath()));
        } catch (FileDialogException exception) {
            AppMessageDialog.showError(this, exception.getMessage(), exception);
        }
    }

    /** 校验目录和数值后统一保存设置，成功才标记已保存并关闭；失败保留窗口与原配置。 */
    private void saveSettings() {
        File directory = validateDirectory(
                defaultSaveDirectoryField,
                "默认 MR 保存目录"
        );
        if (directory == null) {
            return;
        }

        File screenCaptureDirectory = validateDirectory(
                screenCaptureDirectoryField,
                "默认截图保存目录"
        );
        if (screenCaptureDirectory == null) {
            return;
        }

        int mouseMoveInterval;
        try {
            mouseMoveInterval = NumericInputUtil.commitSpinnerInt(mouseMoveIntervalSpinner);
        } catch (IllegalArgumentException exception) {
            showWarning(exception.getMessage());
            return;
        }

        boolean saved = appConfig.updateSettings(
                directory,
                screenCaptureDirectory,
                loadLastMacroOnStartupCheckBox.isSelected(),
                recordMouseMoveCheckBox.isSelected(),
                mouseMoveInterval,
                getSelectedRecordingStartBehavior(),
                insertAfterButton.isSelected() ? TimelineInsertPosition.AFTER : TimelineInsertPosition.BEFORE,
                minimizeOnPlayStartCheckBox.isSelected(),
                restoreWindowAfterPlayStopCheckBox.isSelected(),
                standbyHeadCheckBox.isEnabled() ? standbyHeadCheckBox.isSelected() : appConfig.isStandbyHeadEnabled()
        );
        if (!saved) {
            showError("配置文件保存失败，请检查程序目录是否可写。设置窗口将保持打开。");
            return;
        }
        settingsSaved = true;
        dispose();
    }

    /** 主窗口传入当前宏的状态，保存后再统一应用，取消不会改变宏。 */
    public void setStandbyHeadState(boolean selected, boolean editable) {
        standbyHeadCheckBox.setSelected(selected);
        standbyHeadCheckBox.setEnabled(editable);
    }

    /** 仅在保存时读取互斥选项，取消设置不会修改配置。 */
    private RecordingStartBehavior getSelectedRecordingStartBehavior() {
        if (recordingControlBarButton.isSelected()) {
            return RecordingStartBehavior.CONTROL_BAR;
        }
        return recordingUnchangedButton.isSelected()
                ? RecordingStartBehavior.NONE : RecordingStartBehavior.MINIMIZE;
    }

    /** 校验并按需创建设置中的目录。 */
    private File validateDirectory(JTextField field, String displayName) {
        String path = field.getText().trim();
        if (path.isEmpty()) {
            showWarning(displayName + "不能为空。");
            return null;
        }

        File directory = new File(path);
        if (!directory.exists() && !directory.mkdirs()) {
            showError("无法创建目录：\n" + directory.getAbsolutePath());
            return null;
        }
        if (!directory.isDirectory()) {
            showError("选择的路径不是文件夹：\n" + directory.getAbsolutePath());
            return null;
        }
        return directory;
    }

    /** 在当前设置窗口显示统一的参数或目录警告。 */
    private void showWarning(String message) {
        AppMessageDialog.showWarning(this, message);
    }

    /** 在当前设置窗口显示无法完成保存等错误。 */
    private void showError(String message) {
        AppMessageDialog.showError(this, message);
    }

    /** 承载设置分类页面和标题，滚动范围只按当前显示页面计算。 */
    private class SettingsContentPanel extends JPanel {

        private final CardLayout pageLayout = new CardLayout();
        private final JPanel pages = new JPanel(pageLayout);
        private final JLabel titleLabel = new JLabel();
        private final JLabel descriptionLabel = new JLabel();
        private final JScrollPane scrollPane;

        /** 创建各设置页面及共享滚动区域，初始显示录制设置。 */
        private SettingsContentPanel() {
            setLayout(new BorderLayout(0, 18));
            setOpaque(false);
            setBorder(new EmptyBorder(30, 34, 18, 34));

            titleLabel.setFont(FONT_TITLE);
            titleLabel.setForeground(THEME.textPrimary());
            descriptionLabel.setFont(FONT_NORMAL);
            descriptionLabel.setForeground(THEME.textSecondary());

            JPanel header = new JPanel();
            header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
            header.setOpaque(false);
            header.add(titleLabel);
            header.add(Box.createVerticalStrut(6));
            header.add(descriptionLabel);

            pages.setOpaque(false);
            pages.add(new RecordingSettingsPanel(), SettingsSection.RECORDING.name());
            pages.add(new PlaybackSettingsPanel(), SettingsSection.PLAYBACK.name());
            pages.add(new FileSettingsPanel(), SettingsSection.FILE.name());
            pages.add(new InterfaceSettingsPanel(), SettingsSection.INTERFACE.name());
            pages.add(new ShortcutsSettingsPanel(), SettingsSection.SHORTCUTS.name());
            pages.add(new AboutSettingsPanel(), SettingsSection.ABOUT.name());

            scrollPane = new JScrollPane(
                    pages,
                    JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                    JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            );
            scrollPane.setOpaque(false);
            scrollPane.getViewport().setOpaque(false);
            ComponentStyleUtil.styleScrollPane(scrollPane);
            scrollPane.getVerticalScrollBar().setUnitIncrement(16);
            add(header, BorderLayout.NORTH);
            add(scrollPane, BorderLayout.CENTER);
            showSection(SettingsSection.RECORDING);
        }

        /** 切换分类标题和内容，按当前页恢复滚动高度并回到顶部。 */
        private void showSection(SettingsSection section) {
            titleLabel.setText(section.title);
            descriptionLabel.setText(section.description);
            pageLayout.show(pages, section.name());
            // 快捷键列表较长；仅按当前页面计算滚动高度，避免其他页面也留下大段空白。
            for (Component page : pages.getComponents()) {
                if (page.isVisible()) {
                    pages.setPreferredSize(page.getPreferredSize());
                    break;
                }
            }
            pages.revalidate();
            scrollPane.getViewport().setViewPosition(new Point());
        }
    }

    /** 只展示已经存在的快捷键，不注册新绑定，也不参与配置保存。 */
    private class ShortcutsSettingsPanel extends JPanel {
        /** 按使用场景列出当前固定快捷键，只展示说明，不注册绑定或参与配置保存。 */
        private ShortcutsSettingsPanel() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setOpaque(false);
            addGroup("录制与播放 · 全局",
                    new String[]{"F10", "录制时截图", "录制或录制暂停时可用"},
                    new String[]{"F11", "暂停 / 继续播放", "播放中可暂停，停稳后查看时间线"},
                    new String[]{"F12", "停止录制 / 播放", "录制、播放及其暂停状态下可用"});
            addGroup("时间线编辑",
                    new String[]{"Delete", "删除选中操作", "时间线获得焦点且允许编辑时可用"},
                    new String[]{"Enter", "保存当前输入", "编辑坐标或连线等待时间时可用"},
                    new String[]{"Esc", "取消当前输入", "编辑坐标或连线等待时间时可用"});
            addGroup("截图与图像采集",
                    new String[]{"Enter", "确认截图", "框选出有效区域后可用"},
                    new String[]{"Esc", "取消截图", "屏幕框选期间可用"},
                    new String[]{"Alt + F4", "取消截图", "屏幕框选期间可用"},
                    new String[]{"Ctrl + V", "粘贴图片", "图像采集页可用，文字输入框除外"});
            addGroup("设置与提示窗口",
                    new String[]{"Enter", "保存设置 / 确认提示", "窗口默认按钮可用时生效"},
                    new String[]{"Esc", "关闭设置，不保存修改", "设置窗口中可用"},
                    new String[]{"Esc", "关闭图像识别窗口", "图像识别窗口中可用"},
                    new String[]{"Esc", "关闭提示窗口", "提示窗口中可用"});
        }

        /** 将一组按键、用途和生效条件加入主题卡片，使用只读标签和固定键帽区域。 */
        private void addGroup(String title, String[]... shortcuts) {
            if (getComponentCount() > 0) add(Box.createVerticalStrut(14));
            RoundedPanel card = createSettingsCard(title);
            for (int i = 0; i < shortcuts.length; i++) {
                if (i > 0) card.add(Box.createVerticalStrut(14));
                String[] shortcut = shortcuts[i];
                JPanel row = new JPanel(new BorderLayout(16, 0));
                row.setOpaque(false);
                row.setAlignmentX(Component.LEFT_ALIGNMENT);

                RoundedPanel keyCap = new RoundedPanel(THEME.controlBackground(), 8);
                keyCap.setLayout(new BorderLayout());
                keyCap.setPreferredSize(new Dimension(96, 40));
                JLabel key = createNormalLabel(shortcut[0]);
                key.setFont(FONT_BOLD);
                key.setHorizontalAlignment(SwingConstants.CENTER);
                keyCap.add(key, BorderLayout.CENTER);
                row.add(keyCap, BorderLayout.WEST);

                JPanel explanation = new JPanel(new GridLayout(2, 1, 0, 4));
                explanation.setOpaque(false);
                explanation.add(createNormalLabel(shortcut[1]));
                explanation.add(createSecondaryLabel(shortcut[2]));
                row.add(explanation, BorderLayout.CENTER);
                row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
                card.add(row);
            }
            card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
            add(card);
        }
    }

    /** 只展示软件信息，名称和版本共用应用常量，不参与配置保存。 */
    private class AboutSettingsPanel extends JPanel {
        /** 使用现有主题卡片显示名称、版本、用途及适用平台。 */
        private AboutSettingsPanel() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setOpaque(false);

            RoundedPanel card = createSettingsCard(ApplicationInfo.APP_NAME);
            JLabel version = createSecondaryLabel("版本 " + ApplicationInfo.APP_VERSION);
            card.add(version);
            card.add(Box.createVerticalStrut(20));
            JLabel purpose = createNormalLabel("键盘与鼠标宏录制工具");
            card.add(purpose);
            card.add(Box.createVerticalStrut(8));
            JLabel features = createSecondaryLabel("录制、编辑和播放键鼠操作，支持截图与图像识别。");
            card.add(features);
            card.add(Box.createVerticalStrut(20));
            JLabel platform = createSecondaryLabel("适用平台：Windows");
            card.add(platform);
            for (JLabel label : new JLabel[]{version, purpose, features, platform}) {
                label.setAlignmentX(Component.LEFT_ALIGNMENT);
                label.setMaximumSize(new Dimension(Integer.MAX_VALUE, label.getPreferredSize().height));
            }
            card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
            add(card);
        }
    }

    /** 播放设置页面。 */
    private class PlaybackSettingsPanel extends JPanel {

        /** 组装播放开始最小化和结束恢复窗口的行为选项。 */
        private PlaybackSettingsPanel() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setOpaque(false);

            RoundedPanel behaviorCard = createSettingsCard("播放行为");
            styleCheckBox(minimizeOnPlayStartCheckBox);
            styleCheckBox(restoreWindowAfterPlayStopCheckBox);
            behaviorCard.add(minimizeOnPlayStartCheckBox);
            behaviorCard.add(Box.createVerticalStrut(12));
            behaviorCard.add(restoreWindowAfterPlayStopCheckBox);
            add(behaviorCard);
        }
    }

    /** 提供设置分类导航，并统一维护按钮选中状态。 */
    private static class SettingsSidebar extends JPanel {

        private final Map<SettingsSection, JButton> navigationButtons =
                new EnumMap<>(SettingsSection.class);
        private final Consumer<SettingsSection> onSelectionChanged;

        /** 按现有设置分类创建导航按钮，并默认选中录制页面。 */
        private SettingsSidebar(Consumer<SettingsSection> onSelectionChanged) {
            this.onSelectionChanged = onSelectionChanged;
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBackground(THEME.targetCard());
            setBorder(new EmptyBorder(28, 18, 24, 18));
            setPreferredSize(new Dimension(210, 0));

            JLabel navigationTitle = new JLabel("设置");
            navigationTitle.setFont(new Font(UiConstants.DEFAULT_FONT_FAMILY, Font.BOLD, 18));
            navigationTitle.setForeground(THEME.textPrimary());
            navigationTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
            add(navigationTitle);
            add(Box.createVerticalStrut(24));

            for (SettingsSection section : SettingsSection.values()) {
                JButton button = createNavigationButton(section);
                navigationButtons.put(section, button);
                add(button);
                add(Box.createVerticalStrut(10));
            }
            select(SettingsSection.RECORDING);
        }

        /** 创建左对齐的主题导航按钮，点击时交由侧栏统一切换分类。 */
        private JButton createNavigationButton(SettingsSection section) {
            JButton button = new JButton(section.navigationText);
            button.setFont(new Font(UiConstants.DEFAULT_FONT_FAMILY, Font.BOLD, 14));
            button.setForeground(THEME.textPrimary());
            button.setIcon(new SettingsIcon(section.iconType));
            button.setIconTextGap(13);
            button.setHorizontalAlignment(SwingConstants.LEFT);
            button.setBorder(new EmptyBorder(0, 18, 0, 18));
            button.setOpaque(false);
            button.setContentAreaFilled(false);
            button.setFocusPainted(false);
            button.setBorderPainted(false);
            button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
            button.setPreferredSize(new Dimension(174, 52));
            button.setAlignmentX(Component.LEFT_ALIGNMENT);
            button.setRolloverEnabled(true);
            button.setUI(new NavigationButtonUI());
            button.addActionListener(e -> select(section));
            return button;
        }

        /** 同步所有导航按钮的选中样式，再通知内容区切换页面。 */
        private void select(SettingsSection section) {
            navigationButtons.forEach((item, button) -> {
                button.putClientProperty("settings.selected", item == section);
                button.setForeground(item == section ? THEME.textOnAccent() : THEME.textPrimary());
                button.repaint();
            });
            onSelectionChanged.accept(section);
        }
    }

    /** 组织鼠标采样、录制窗口行为和目标窗口选项。 */
    private class RecordingSettingsPanel extends JPanel {

        /** 按统一卡片间距组装录制相关设置。 */
        private RecordingSettingsPanel() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setOpaque(false);
            add(createMouseRecordingCard());
            add(Box.createVerticalStrut(14));
            add(createRecordingBehaviorCard());
            add(Box.createVerticalStrut(14));
            add(createTargetWindowCard());
        }

        /** 组合鼠标移动录制开关和采样间隔，并安装数值校验。 */
        private JPanel createMouseRecordingCard() {
            RoundedPanel card = createSettingsCard("鼠标录制");
            styleCheckBox(recordMouseMoveCheckBox);
            card.add(recordMouseMoveCheckBox);
            card.add(Box.createVerticalStrut(16));

            JPanel intervalRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            intervalRow.setOpaque(false);
            intervalRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            JLabel intervalLabel = createNormalLabel("鼠标移动采样间隔：");
            JLabel unitLabel = createSecondaryLabel("ms");
            mouseMoveIntervalSpinner.setFont(FONT_NORMAL);
            ComponentStyleUtil.styleSpinner(mouseMoveIntervalSpinner);
            NumericInputUtil.installSpinner(mouseMoveIntervalSpinner, "鼠标移动采样间隔（ms）");
            mouseMoveIntervalSpinner.setPreferredSize(new Dimension(88, 36));
            mouseMoveIntervalSpinner.setMinimumSize(new Dimension(88, 36));
            intervalRow.add(intervalLabel);
            intervalRow.add(Box.createHorizontalStrut(12));
            intervalRow.add(mouseMoveIntervalSpinner);
            intervalRow.add(Box.createHorizontalStrut(10));
            intervalRow.add(unitLabel);
            // 避免该行默认居中对齐的最大宽度将同一卡片内的标签和复选框推向中间。
            intervalRow.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                    intervalRow.getPreferredSize().height));
            card.add(intervalRow);
            return card;
        }

        /** 创建录制启动时的窗口行为选择及停止恢复说明。 */
        private JPanel createRecordingBehaviorCard() {
            RoundedPanel card = createSettingsCard("录制行为");
            styleCheckBox(restoreWindowAfterRecordingCheckBox);
            JLabel label = createNormalLabel("开始录制后");
            label.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(label);
            card.add(Box.createVerticalStrut(8));
            card.add(createRadioRow(recordingMinimizeButton, recordingControlBarButton,
                    recordingUnchangedButton));
            card.add(Box.createVerticalStrut(12));
            card.add(restoreWindowAfterRecordingCheckBox);
            return card;
        }

        /** 展示目标窗口记忆选项及其用途说明。 */
        private JPanel createTargetWindowCard() {
            RoundedPanel card = createSettingsCard("目标窗口");
            styleCheckBox(rememberTargetWindowCheckBox);
            card.add(rememberTargetWindowCheckBox);
            card.add(Box.createVerticalStrut(8));
            JLabel hint = createSecondaryLabel(
                    "下次录制时将自动选择上次使用的目标窗口，提高录制效率。"
            );
            hint.setBorder(new EmptyBorder(0, 28, 0, 0));
            card.add(hint);
            return card;
        }
    }

    /** 界面设置中只增加时间线默认插入方向，不改变显式位置操作。 */
    private class InterfaceSettingsPanel extends JPanel {
        /** 组装时间线头部待机和默认插入方向设置，待机时长仍由时间线编辑。 */
        private InterfaceSettingsPanel() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setOpaque(false);
            RoundedPanel card = createSettingsCard("时间线");
            styleCheckBox(standbyHeadCheckBox);
            card.add(standbyHeadCheckBox);
            card.add(Box.createVerticalStrut(8));
            card.add(createSecondaryLabel("在开头增加待机，等待时间在时间线连线上修改。"));
            card.add(Box.createVerticalStrut(16));
            JLabel label = createNormalLabel("默认插入位置");
            label.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(label);
            card.add(Box.createVerticalStrut(8));
            card.add(createRadioRow(insertBeforeButton, insertAfterButton));
            card.add(Box.createVerticalStrut(8));
            JLabel hint = createSecondaryLabel("向前插入到选中节点之前，向后插入到选中节点之后。");
            hint.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(hint);
            add(card);
        }
    }

    /** 展示宏与截图的默认保存位置，以及启动时加载行为。 */
    private class FileSettingsPanel extends JPanel {

        /** 创建只读目录显示、目录选择入口和启动选项。 */
        private FileSettingsPanel() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setOpaque(false);

            RoundedPanel directoryCard = createSettingsCard("保存位置");
            directoryCard.add(createNormalLabel("默认 MR 保存目录"));
            directoryCard.add(Box.createVerticalStrut(12));
            JPanel directoryRow = new JPanel(new BorderLayout(10, 0));
            directoryRow.setOpaque(false);
            defaultSaveDirectoryField.setEditable(false);
            styleTextField(defaultSaveDirectoryField);
            styleSecondaryButton(chooseDirectoryButton);
            chooseDirectoryButton.setIcon(new SettingsIcon(SettingsIconType.FOLDER));
            directoryRow.add(defaultSaveDirectoryField, BorderLayout.CENTER);
            directoryRow.add(chooseDirectoryButton, BorderLayout.EAST);
            directoryCard.add(directoryRow);

            directoryCard.add(Box.createVerticalStrut(18));
            directoryCard.add(createNormalLabel("默认截图保存目录"));
            directoryCard.add(Box.createVerticalStrut(12));
            JPanel screenCaptureDirectoryRow = new JPanel(
                    new BorderLayout(10, 0)
            );
            screenCaptureDirectoryRow.setOpaque(false);
            screenCaptureDirectoryField.setEditable(false);
            styleTextField(screenCaptureDirectoryField);
            styleSecondaryButton(chooseScreenCaptureDirectoryButton);
            chooseScreenCaptureDirectoryButton.setIcon(
                    new SettingsIcon(SettingsIconType.FOLDER)
            );
            screenCaptureDirectoryRow.add(
                    screenCaptureDirectoryField,
                    BorderLayout.CENTER
            );
            screenCaptureDirectoryRow.add(
                    chooseScreenCaptureDirectoryButton,
                    BorderLayout.EAST
            );
            directoryCard.add(screenCaptureDirectoryRow);

            RoundedPanel startupCard = createSettingsCard("启动行为");
            styleCheckBox(loadLastMacroOnStartupCheckBox);
            startupCard.add(loadLastMacroOnStartupCheckBox);
            add(directoryCard);
            add(Box.createVerticalStrut(14));
            add(startupCard);
        }
    }

    /** 为同一设置项创建左对齐、互斥且使用公共主题的单选行。 */
    private JPanel createRadioRow(JRadioButton... buttons) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        ButtonGroup group = new ButtonGroup();
        for (JRadioButton button : buttons) {
            if (row.getComponentCount() > 0) {
                row.add(Box.createHorizontalStrut(18));
            }
            ComponentStyleUtil.styleRadioButton(button);
            button.setFont(FONT_NORMAL);
            group.add(button);
            row.add(button);
        }
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    /** 创建带标题和统一内边距的圆角设置卡片。 */
    private RoundedPanel createSettingsCard(String title) {
        RoundedPanel card = new RoundedPanel(THEME.card(), 14);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(new EmptyBorder(20, 22, 20, 22));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(createSectionLabel(title));
        card.add(Box.createVerticalStrut(16));
        return card;
    }

    /** 创建设置卡片的主题分区标题。 */
    private JLabel createSectionLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(FONT_SECTION);
        label.setForeground(THEME.textPrimary());
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    /** 创建设置表单的常规文字标签。 */
    private JLabel createNormalLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(FONT_NORMAL);
        label.setForeground(THEME.textPrimary());
        return label;
    }

    /** 创建低对比度的设置辅助说明标签。 */
    private JLabel createSecondaryLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(FONT_NORMAL);
        label.setForeground(THEME.textSecondary());
        return label;
    }

    /** 应用公共复选框主题、字体和左对齐方式。 */
    private void styleCheckBox(JCheckBox checkBox) {
        ComponentStyleUtil.styleCheckBox(checkBox);
        checkBox.setFont(FONT_NORMAL);
        checkBox.setAlignmentX(Component.LEFT_ALIGNMENT);
    }

    /** 为目录等文本字段应用统一字体和深色输入样式。 */
    private void styleTextField(JTextField textField) {
        textField.setFont(FONT_NORMAL);
        ComponentStyleUtil.styleTextField(textField);
    }

    /** 为保存等主要操作应用主题强调背景。 */
    private void stylePrimaryButton(JButton button) {
        styleButton(button, THEME.accent(), THEME.accentHover());
        button.setForeground(THEME.textOnAccent());
    }

    /** 为取消和目录选择应用主题次要操作背景。 */
    private void styleSecondaryButton(JButton button) {
        styleButton(button, THEME.controlBackground(), THEME.card());
    }

    /** 统一按钮字体、间距、鼠标反馈和圆角绘制，不改变按钮业务回调。 */
    private void styleButton(JButton button, Color background, Color hoverBackground) {
        button.setFont(FONT_BOLD);
        button.setForeground(THEME.textPrimary());
        button.setBackground(background);
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setBorder(new EmptyBorder(10, 16, 10, 16));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setIconTextGap(8);
        button.setRolloverEnabled(true);
        button.setUI(new RoundedButtonUI(background, hoverBackground));
    }

    private enum SettingsSection {
        RECORDING("录制", "录制设置", "配置录制相关的行为和选项", SettingsIconType.RECORD),
        PLAYBACK("播放", "播放设置", "配置宏播放相关的行为和选项", SettingsIconType.PLAY),
        FILE("文件", "文件设置", "配置宏文件、截图的保存位置和启动加载行为", SettingsIconType.FILE),
        INTERFACE("界面", "界面设置", "配置界面和时间线编辑行为", SettingsIconType.INTERFACE),
        SHORTCUTS("快捷键", "快捷键", "当前快捷键为固定按键，暂不支持修改。", SettingsIconType.KEYBOARD),
        ABOUT("关于", "关于", "软件信息", SettingsIconType.ABOUT);

        private final String navigationText;
        private final String title;
        private final String description;
        private final SettingsIconType iconType;

        SettingsSection(String navigationText, String title, String description,
                        SettingsIconType iconType) {
            this.navigationText = navigationText;
            this.title = title;
            this.description = description;
            this.iconType = iconType;
        }
    }

    private enum SettingsIconType {
        RECORD, PLAY, FILE, INTERFACE, KEYBOARD, ABOUT, FOLDER, CHECK, CLOSE
    }

    /** 按设置分类或按钮用途绘制轻量主题图标。 */
    private record SettingsIcon(SettingsIconType type) implements Icon {

            private static final int SIZE = 18;

        /** 使用组件当前前景色绘制设置导航或操作图形。 */
        @Override
            public void paintIcon(Component component, Graphics graphics, int x, int y) {
                Graphics2D g2 = (Graphics2D) graphics.create();
                g2.translate(x, y);
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(component.getForeground());
                g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

                switch (type) {
                    case RECORD -> {
                        g2.drawOval(3, 3, 12, 12);
                        g2.fillOval(7, 7, 4, 4);
                    }
                    case PLAY -> g2.fillPolygon(new int[]{5, 15, 5}, new int[]{3, 9, 15}, 3);
                    case FILE -> {
                        g2.drawRoundRect(4, 2, 10, 14, 2, 2);
                        g2.drawLine(7, 7, 12, 7);
                        g2.drawLine(7, 10, 12, 10);
                        g2.drawLine(7, 13, 11, 13);
                    }
                    case INTERFACE -> {
                        g2.drawRoundRect(2, 3, 14, 10, 2, 2);
                        g2.drawLine(9, 13, 9, 16);
                        g2.drawLine(6, 16, 12, 16);
                    }
                    case KEYBOARD -> {
                        g2.drawRoundRect(1, 4, 16, 10, 2, 2);
                        for (int keyX = 4; keyX <= 13; keyX += 3) {
                            g2.drawLine(keyX, 7, keyX, 7);
                        }
                        g2.drawLine(5, 11, 13, 11);
                    }
                    case FOLDER -> {
                        g2.drawRoundRect(2, 5, 14, 10, 3, 3);
                        g2.drawLine(3, 5, 7, 5);
                        g2.drawLine(7, 5, 8, 7);
                    }
                    case ABOUT -> {
                        g2.drawOval(2, 2, 14, 14);
                        g2.fillOval(8, 5, 2, 2);
                        g2.drawLine(9, 9, 9, 13);
                    }
                    case CHECK -> {
                        g2.drawLine(3, 9, 7, 13);
                        g2.drawLine(7, 13, 15, 5);
                    }
                    case CLOSE -> {
                        g2.drawLine(4, 4, 14, 14);
                        g2.drawLine(14, 4, 4, 14);
                    }
                }
                g2.dispose();
            }

            /** 返回设置图标固定的逻辑宽度。 */
            @Override
            public int getIconWidth() {
                return SIZE;
            }

            /** 返回设置图标固定的逻辑高度。 */
            @Override
            public int getIconHeight() {
                return SIZE;
            }
        }

    /** 绘制设置窗口普通操作按钮的圆角背景和状态反馈。 */
    private static class RoundedButtonUI extends DarkButtonUI {

        private final Color background;
        private final Color hoverBackground;

        /** 保存操作按钮默认和悬停背景颜色。 */
        private RoundedButtonUI(Color background, Color hoverBackground) {
            this.background = background;
            this.hoverBackground = hoverBackground;
        }

        /** 按禁用、按下或悬停状态绘制圆角按钮，再绘制文字和图标。 */
        @Override
        public void paint(Graphics graphics, JComponent component) {
            AbstractButton button = (AbstractButton) component;
            ButtonModel model = button.getModel();
            Color fill = background;
            if (!button.isEnabled()) {
                fill = THEME.targetCard();
            } else if (model.isPressed()) {
                fill = THEME.border();
            } else if (model.isRollover()) {
                fill = hoverBackground;
            }

            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int arc = Math.min(14, Math.max(8, component.getHeight() - 2));
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, component.getWidth(), component.getHeight(), arc, arc);
            g2.setColor(THEME.border());
            g2.drawRoundRect(0, 0, component.getWidth() - 1, component.getHeight() - 1, arc, arc);
            g2.dispose();
            super.paint(graphics, component);
        }
    }

    /** 按分类选中和悬停状态绘制侧栏导航按钮。 */
    private static class NavigationButtonUI extends DarkButtonUI {

        /** 读取分类选中标记并绘制对应背景，保持导航文字及图标位置不变。 */
        @Override
        public void paint(Graphics graphics, JComponent component) {
            AbstractButton button = (AbstractButton) component;
            boolean selected = Boolean.TRUE.equals(button.getClientProperty("settings.selected"));
            Color fill = selected ? THEME.accent() : THEME.controlBackground();
            if (button.getModel().isRollover()) {
                fill = selected ? THEME.accentHover() : THEME.card();
            }

            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, component.getWidth(), component.getHeight(), 14, 14);
            g2.setColor(THEME.border());
            g2.drawRoundRect(0, 0, component.getWidth() - 1, component.getHeight() - 1, 14, 14);
            g2.dispose();
            super.paint(graphics, component);
        }
    }

    /** 为设置内容提供统一圆角卡片背景。 */
    private static class RoundedPanel extends JPanel {

        private final Color background;
        private final int radius;

        /** 保存卡片背景色与圆角半径，避免系统填充矩形底色。 */
        private RoundedPanel(Color background, int radius) {
            this.background = background;
            this.radius = radius;
            setOpaque(false);
        }

        /** 按组件实际大小铺满圆角主题背景，再绘制面板内容。 */
        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(background);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);
            g2.dispose();
            super.paintComponent(graphics);
        }
    }

}
