# Maven 自动回归测试

本文最初记录 TEST-01：让已有独立检查由 Maven 实际执行；该次接入未修改生产业务逻辑。后续新增覆盖、夹具调整和真实结果按轮次保留在下文，不能把历史测试数量当作当前总数。当前项目概况见 [文档索引](README.md)。

## 2026-10-25：1.1.0 提交前复核

在维护者将 `ApplicationInfo.APP_VERSION` 设为 1.1.0 后，Maven 数字版本已同步。提交前重新核对累计源码、测试、删除项和新增文件；当前 README 同步版本依据及识别入口说明，更新日志按 `1.1.0` 归组。1.0.0 已提交的分支跳转、保存校验及侧键保护不重复计为本轮新增功能。

- 定向执行 `mvn '-Dsurefire.timeout=180' '-Dtest=MainTitleBarTest,SideMouseButtonEditorTest,ImageJumpEditorTest,ImageRecognitionFrameTest,RecognitionWindowVisibilityTest,ScreenCaptureControllerTest,LegacyRegressionTest#popupTitleBarContract+mainTitleBarContract+recordingTitleBarContract+recordingBarNativeResize+modelessActionEditor+aboutSettings+shortcutsSettings+standbyHeadSettings+numericInputValidation' test`：26 项，失败 0、错误 0、跳过 0，退出码 0，15:52:08 完成。
- 完整执行 `mvn '-Dsurefire.timeout=180' test`：114 项，失败 0、错误 0、跳过 0，退出码 0，15:55:11 完成。
- 执行 `mvn compile`：退出码 0，15:58:51 完成；生产类已在上述测试运行中重新编译。

日志位于 `target/push-1.1.0-20261025/` 的 `directed.log`、`full-test.log`、`compile.log`。没有执行 `mvn clean`，没有重新运行 Qodana；测试使用既有隔离及模拟机制，真实 Windows 11、150% DPI 的窗口/截图行为仍按各功能人工清单验收。提交和远程同步结果以实际 Git 记录为准；按项目约定，推送 GitHub 即视为当前版本发布，安装包产物和桌面验收仍以实际执行记录为准。随后发布状态及约定的修正仅涉及文档，未重复运行上述测试。

## 2026-10-25：统一应用弹窗标题栏

`MainTitleBar` 复用到设置、动作编辑、图像识别测试及消息/确认弹窗，统一字体、分隔线和按钮反馈；对话框仅显示关闭，独立识别窗口保留最小化、最大化/还原和关闭。`WindowTitleBarSupport` 管理弹窗的安装与卸载；既有 DWM 适配层扩展为处理 JFrame/JDialog，仍保存和转发各自原 AWT 过程。模态、编辑器编号和替换规则、识别取消/窗口恢复沿用原流程。提示窗改用 DWM 外框及不透明主题底板，移除旧正文关闭按钮和旧外层透明圆角，避免重复按钮和浅色边角。全屏截图框选层及 Windows 原生文件面板不加标题栏。

新增 `MainTitleBarTest` 的对话框检查，验证只有关闭、关闭事件和标题更新不改变模态；新增隔离 `PopupTitleBarContractTest`，验证四类真实 HWND 的标题栏、按钮数量/右对齐、系统按钮隐藏、原生拖动及按钮命中、回调安装和销毁卸载，以及关闭不保存编辑/设置、不返回确认、默认确认仍返回确认、识别关闭进入原取消清理。导出 `target/dialog-titlebars-20261025/*-preview.png`，已核对实际组件的 150% 离屏预览；不是桌面截图。

最终定向执行 `mvn '-Dsurefire.timeout=180' '-Dtest=MainTitleBarTest,SideMouseButtonEditorTest,ImageJumpEditorTest,ImageRecognitionFrameTest,RecognitionWindowVisibilityTest,ScreenCaptureControllerTest,LegacyRegressionTest#popupTitleBarContract+mainTitleBarContract+recordingTitleBarContract+recordingBarNativeResize+modelessActionEditor+aboutSettings+shortcutsSettings+standbyHeadSettings+numericInputValidation' test`：26 项，失败 0、错误 0、跳过 0，退出码 0，14:27:28 完成，日志 `directed-release.log`。前两次定向因新增测试入口的符号/导入遗漏而编译失败，未执行测试；修正后第一轮 26 项通过。随后在预览中发现提示窗旧底板浅色边角，修正底板并补充原生命中及默认确认检查，再得到最终上述结果；相关日志均保留。

完整执行 `mvn '-Dsurefire.timeout=180' test`：114 项，失败 0、错误 0、跳过 0，退出码 0，14:29:36 完成，日志 `full-test.log`。初次生产编译成功；最终编译记录见同目录 `compile.log`。没有执行 `mvn clean`，不删除已有诊断产物，未提交或推送。

Windows 11、150% DPI 仍需人工确认四类弹窗的顶部、悬停、拖动和关闭命中，设置/Esc 取消与保存、编辑器替换及编号、提示窗确认/取消及 Alt+F4，识别窗口最大化/还原、最小化恢复，以及截图/识别采集时隐藏和取消后的恢复。隐藏 HWND、合成消息和离屏预览不等于真实桌面行为已经验收；最大化悬停贴靠菜单仍沿用主页标题栏的既有限制。

## 2026-10-25：清理无调用方法

以当前源码为准，使用 JDK 17 语义分析遍历 146 个源码文件、943 个生产方法，解析重载调用及方法引用；结合文本检索复核反射、资源和原生调用。旧 IDEA 报告仅作为线索：当前已有调用的确认弹窗和主题切换方法均保留，不将旧报告的“未使用”结论直接用于删除。

删除 `AppConfig` 的四个无调用单项 setter 和旧八参数 `updateSettings` 重载；删除 `MacroRecorder.start()` 无参转发入口；删除无调用的 `TodoUtil.show()` 及仅承载此入口的工具类；移除 `WindowsRecordingTitleBar` 无人读取的高度字段、生成 getter 及对应赋值，共 8 个闲置方法。保留设置统一保存入口、旧配置键读取、`start(TargetWindow)` 和所有原生消息行为。未调整 MR 格式或运行逻辑，也未删除测试。

删除后再次语义分析：145 个源码文件、936 个显式生产方法，编译分析错误 0，同类无引用方法候选为空。数据、序列化、继承契约、原生映射及生成的方法不因普通文本调用少而删除；这不是重新执行全项目 IDEA 检查或对动态使用的全面证明。临时工具与前后分析结果均位于忽略目录 `target/unused-methods-20261025/`。

- 定向执行 `mvn '-Dsurefire.timeout=180' '-Dtest=RecordingTitleBarRefreshTest,PlaybackThemeTest,LegacyRegressionTest#configurationWriteSafety+configurationParseSafety+standbyHeadSettings+recordingPause+recordingMouseCoordinates+recordingTitleBarContract+recordingBarNativeResize+mainTitleBarContract' test`：15 项，失败 0、错误 0、跳过 0，退出码 0，13:00:48 完成，日志 `directed.log`。
- 完整执行 `mvn '-Dsurefire.timeout=180' test`：112 项，失败 0、错误 0、跳过 0，退出码 0，13:02:23 完成，日志 `full-test.log`。
- 执行 `mvn compile`：退出码 0，日志 `compile.log`；生产类已在定向测试时重新编译。

本轮未增加新的行为，不为删除方法编写仅断言“方法不存在”的测试；沿用现有配置安全、录制及窗口生命周期回归。未进行真实桌面验收，未执行 `mvn clean`，未提交、未推送；保留其他已有修改和诊断产物。

## 2026-10-25：主页自绘标题栏

新增 `MainTitleBar`，使用现有主题绘制标题与最小化、最大化/还原、关闭按钮；窗口操作复用 `WindowManager` 和原有关闭事件。普通主页复用 DWM 适配层，保留原 AWT 过程及双向缩放；进入紧凑录制时先卸载主页实例，停止后恢复。业务内容面板、窗口句柄及紧凑栏尺寸、置顶、位置记忆不变。

新增 `MainTitleBarTest` 2 项，验证按钮状态/回调、长标题在窄宽布局中的按钮固定位置及主题悬停色；离屏预览位于 `target/main-titlebar-20261025/preview.png`。新增隔离的 `MainTitleBarContractTest`，验证真实 HWND 的样式、顶部/按钮命中、主页不受固定高度约束、两轮模式切换中回调不叠加和窗口销毁卸载。既有 `RecordingBarNativeResizeTest` 的观察层改为直接接原 AWT 过程，动态布局继续开启，原转发及尺寸断言保留。

定向执行 `mvn '-Dsurefire.timeout=180' '-Dtest=MainTitleBarTest,RecordingTitleBarRefreshTest,RecordingControlBarLayoutTest,RecordingBarGeometryTest,LegacyRegressionTest#mainTitleBarContract+recordingTitleBarContract+recordingBarNativeResize+playbackWindowRestore+playbackTimelineWindow+modelessActionEditor' test`：18 项，失败 0、错误 0、跳过 0，退出码 0，12:51:24 完成，日志 `target/main-titlebar-20261025/directed-final.log`。

首轮定向 18 项中有 2 项失败：合成 `WM_GETMINMAXINFO` 没有提供 Windows 通常预填的系统跟踪上限；原缩放夹具的观察回调先装在主页回调之上，导致生产逻辑正确拒绝越过其他回调卸载。补齐消息输入和观察层安装顺序后，先单独重跑这两项通过，再完成上述定向验证；原日志 `directed.log`、复核日志 `contracts-verified.log` 保留，没有修改断言来忽略失败。

完整执行 `mvn '-Dsurefire.timeout=180' test`：112 项，失败 0、错误 0、跳过 0，退出码 0，12:53:08 完成；`mvn compile` 退出码 0，12:53:31 完成。日志为 `target/main-titlebar-20261025/full-test.log`、`compile.log`；没有执行 `mvn clean`。

测试使用隐藏窗口、合成原生消息及离屏绘制，按钮状态测试使用模拟扩展状态，不等同于真实 Windows 桌面验收。Windows 11、150% DPI 仍需人工检查：按钮点击/悬停、拖动/双击最大化、四边四角缩放、最大化工作区与还原位置、任务栏最小化恢复；录制栏切换/置顶、停止恢复、播放自动最小化及 F12 恢复、截图隐藏和取消恢复。Windows 11 最大化按钮悬停的贴靠菜单尚未单独接入；现有自绘按钮返回客户区命中，不宣称实现了微软要求的 [HTMAXBUTTON 悬停协议](https://learn.microsoft.com/windows/apps/desktop/modernize/apply-snap-layout-menu)。未提交、未推送。

## 2026-10-25：卡片蓝青渐变与主题配色补齐

已播放节点及头部待机卡使用蓝到青的对角渐变，叠加柔和内边缘；当前播放边框仍使用强调色，未执行节点不点亮。不改变组件、尺寸、文字布局、播放语义或进度刷新时机，不增加动画任务。`ThemePalette` 增加播放渐变两端、强调底色文字、普通悬停、高光和阴影六个角色，默认值集中在 `DarkTheme`。

核对主源码中的固定颜色后，将主窗口、窗口选择页、动作编辑/设置按钮、提示窗、输入选中文字和勾选图标纳入对应主题角色。时间线临时输入框也明确绑定选中背景与文字，普通输入框补齐禁用文字色。保留提示窗 `new Color(0, 0, 0, 0)` 的纯透明背景；其余 `new Color` 均用于已有主题色/组件前景色的透明度或混色，不是独立固定 RGB。沿用现有创建窗口前应用调色板的机制，本轮没有实现实时切换整套窗口。

新增 `PlaybackThemeTest` 3 项：用反差明显的自定义调色板确认文字选择、按钮、勾选图标及渐变两端读取主题；验证内光不溢出、当前边框保留、未执行不点亮；实际卡片的后代组件位置、首选尺寸、字体与文字颜色保持不变。导出 `target/playback-gradient-20261025/preview.png` 并核对四种播放状态；它来自实际组件的离屏绘制，不是桌面截图。

定向执行 `mvn '-Dsurefire.timeout=180' '-Dtest=PlaybackThemeTest,TimelinePlaybackProgressTest,TimelineNumberingTest,TimelineJumpLayoutTest,DarkComboBoxStyleTest,StandbyHeadTimelineTest,ImageJumpEditorTest,ImageRecognitionFrameTest,LegacyRegressionTest#aboutSettings+shortcutsSettings+numericInputValidation+timelineHeadlessEditing+timelinePlaybackProtection+modelessActionEditor+recordingTitleBarContract' test`：35 项，失败 0、错误 0、跳过 0，退出码 0，12:31:44 完成；日志 `target/playback-gradient-20261025/directed.log`。

随后完整执行 `mvn '-Dsurefire.timeout=180' test`：109 项，失败 0、错误 0、跳过 0，退出码 0，12:33:07 完成；`mvn compile` 构建成功，退出码 0。日志为 `target/playback-gradient-20261025/full-test.log`、`compile.log`。

Windows 11、150% DPI 仍需人工确认渐变柔和程度，卡片文字/参数、选中态和当前播放边框的辨识度，输入选中文字及按钮/勾选图标是否清晰；自动绘制不等于原生桌面验收。未执行 `mvn clean`，未提交、未推送。

## 2026-10-25：设置「关于」页

设置侧栏新增独立「关于」页，沿用现有深色卡片和导航图标绘制，展示 `ApplicationInfo.APP_NAME`、`APP_VERSION`、软件用途及 Windows 平台说明；没有作者、许可证等未经确认的信息，也不新增配置项或修改保存流程。

新增隔离入口 `LegacyRegressionTest#aboutSettings`，只构造隐藏窗口，验证侧栏选中态、软件常量、只读主题、820/920 两种宽度下文字与导航不裁切、实际离屏绘制、从长快捷键页切回后恢复高度，以及浏览/取消后配置字节不变。原快捷键检查同步为六个分类，其他断言保留。

执行 `mvn '-Dsurefire.timeout=180' '-Dtest=LegacyRegressionTest#aboutSettings+shortcutsSettings+standbyHeadSettings' test`：3 项，失败 0、错误 0、跳过 0，退出码 0，12:20:55 完成；日志 `target/settings-about-20261025/directed-verified.log`。首轮 3 项中关于页失败 1 项；单独复核确认 BoxLayout 把版本文字宽度取整为 60、所需宽度为 61。将本页说明标签设为可填满卡片宽度，保持原断言后得到最终通过结果；初次及诊断日志 `directed.log`、`layout-check.log` 保留。

随后完整执行 `mvn '-Dsurefire.timeout=180' test`：106 项，失败 0、错误 0、跳过 0，退出码 0，12:22:26 完成；`mvn compile` 成功，退出码 0。日志为 `target/settings-about-20261025/full-test.log`、`compile.log`。

Windows 11、150% DPI 仍需人工确认「关于」入口、图标和文字显示，窗口缩放、切页及取消正常。隐藏窗口测试不代表真实桌面验收；未执行 `mvn clean`，未提交、未推送。

## 2026-10-25：全项目 IDEA 警告检查与处理

使用本机 IntelliJ IDEA 2026.2.3（IU-262.10968.63）的离线检查入口，读取现有 `Project Default` 配置，对当前 82 个生产源文件、59 个测试源文件及 pom.xml 执行检查。源码副本、独立配置/缓存、报告均位于 `target/idea-project-audit-20261025/`，Java 17 与依赖按 Maven 类路径配置，不改原项目 IDEA 检查规则。首次启动在沙箱中因本地回环连接被拒绝，放行该离线入口后检查成功；未运行 Qodana 或上传报告。

| 检查等级 | 修改前 | 修改后 |
| --- | --- | --- |
| 错误 | 1 | 0 |
| 警告 | 163 | 120 |
| 弱警告 | 35 | 33 |
| 拼写提示 | 70 | 70 |
| 调试建议 | 67 | 67 |

两次 IDEA 检查均退出码 0。原始 XML 位于 `results-before/`、`results-after/`，机器可读汇总为 `findings-before.json`、`findings-after.json`。检查结果依赖 IDE 版本、插件和推断状态，不是对实时「问题」面板每一项提示的清零承诺。

实际修改包括：删除恒定条件、未使用导入和冗余转换；合并按键名称的等价分支；保持 JNA 字段可写而改用现有 DWORD 实例设置长度；局部化仅供构造使用的私有字段；默认目录创建失败留下警告日志并允许继续选择其他目录；清理失败改用 JDK 日志并保留异常堆栈；测试补充非空与等待超时断言。没有删除 JNA 字段、变更原生窗口消息处理或改写播放流程。

IDEA 还发现 Jackson 2.22.3 的 CVE-2026-110879。核对 [FasterXML 官方公告](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-m8xf-2cr7-77ph) 后，更新为同系列补丁版 2.22.4；实际解析的 jackson-databind、jackson-core 均为 2.22.4。MR 字段、格式版本和 ObjectMapper 配置未改；这条依赖告警不代表已经证明本应用可被利用。

保留项：JNA/COM 字段及原生返回值通过映射使用，不能按「未使用」删除；配置、序列化和扩展入口不因当前调用次数少而移除；明确命名的布尔查询及原生函数不为消除反向调用建议而改名；测试轮询、进程等待、失败堆栈和反射属于夹具用途。record/getter、重复片段、提取方法、固定实参等建议未据此扩大重构；HTTP 字符串用于识别用户已有链接，专业术语不是拼写错误。仍有 4 条测试取值的保守空值提示及其他风格提示，不声称所有警告已修复。

实际验证：

- 定向执行 `mvn '-Dsurefire.timeout=180' '-Dtest=MacroStorageSaveValidationTest,StandbyHeadStorageTest,ImageJumpPlaybackTest,ImageJumpEditorTest,SideMouseButtonSafetyTest,SideMouseButtonEditorTest,RecordingBarGeometryTest,PlaybackProgressBufferTest,StandbyHeadTimelineTest,TimelinePlaybackProgressTest,TimelineJumpLayoutTest,ScreenCaptureControllerTest,ScreenRecognitionServiceTest,ImageRecognitionFrameTest,LegacyRegressionTest#timelinePlaybackProtection+timelineLivePlaybackProtection+recordingMouseCoordinates+recordingPause+macroStorageFailure+imageCheckBehaviors+flowPreflightValidation+recordingBarNativeResize+recordingTitleBarContract+modelessActionEditor+numericInputValidation' test`：67 项，失败 0、错误 0、跳过 0，退出码 0，03:40:42 完成；日志 `directed-verified.log`。
- 完整执行 `mvn '-Dsurefire.timeout=180' test`：105 项，失败 0、错误 0、跳过 0，退出码 0，03:42:48 完成；日志 `full-test.log`。
- 随后执行 `mvn compile`：构建成功，退出码 0；日志 `compile.log`，生产类已在定向运行中重新编译。

上述日志均在 `target/idea-project-audit-20261025/`。首轮定向因测试内原生偏移量误用了 long 而编译失败，退出码 1、未执行测试；修正后得到上述最终结果，保留 `directed.log`。不将编译失败或仅参与编译的人工诊断入口计为测试通过。本轮没有运行 `mvn clean`、提交或推送，也没有把自动回归当作真实 Windows 11、150% DPI 桌面验收。

## 2026-10-25：时间线 IDEA 警告与文档清理

根据用户提供的 IDEA 提示，仅调整 `ActionTimelinePanel`：将待机开关的当前状态单独命名；把总被取反调用的行内编辑检查改为直接表达阻止条件；字体标签辅助方法只接收是否加粗，内部使用 `Font.BOLD` / `Font.PLAIN` 常量，不增加注解依赖或屏蔽检查。保留原有校验、副作用顺序、字号和字重。

执行 `mvn '-Dsurefire.timeout=180' '-Dtest=StandbyHeadTimelineTest,TimelineNumberingTest,TimelineJumpLayoutTest,TimelinePlaybackProgressTest,LegacyRegressionTest#timelineHeadlessEditing+timelinePlaybackProtection+numericInputValidation' test`：**21 项，失败 0、错误 0、跳过 0，退出码 0**，03:28:58 完成；日志 `target/idea-warnings-20261025-directed.log`。覆盖待机开关及延迟编辑、坐标和普通延迟编辑、无效文本与编辑权限、编号布局、分支连线及播放标记。定向运行实际重新编译了 82 个生产源文件和 59 个测试源文件。

随后执行 `mvn compile`：构建成功，退出码 0；日志 `target/idea-warnings-20261025-compile.log`。生产类已在上述定向运行中重新编译，此次确认是最新。

按用户要求删除两份过时的 10 月 19 日审查报告；其历史内容可从 Git 查看。保留功能规则、测试历史和正式版记录，并修正文档入口、当前 Git 状态、识别阈值范围及编辑器非模态说明。没有重新运行完整测试、Qodana 或 IDEA 全项目离线检查；未直接读取 IDEA 的实时问题面板，不能据此声称全部 IDE 警告已清零。没有执行 `mvn clean`、提交或推送。

## 2026-10-24：非模态动作编辑与当前模板识别

动作编辑/插入窗口改为非模态；主窗口跟踪单个编辑器，后开窗口直接替换旧窗口并沿用位置，不询问旧修改。旧窗口释放后保存/删除入口失效，迟到的关闭通知不能清除新窗口；播放/录制锁定时仍关闭编辑器。右上角复用 `TimelineNodeNumbers` 显示节点编号，插入/删除后更新；目标被删或换宏时关闭过期编辑器，编号不写入节点 ID。

主页移除识别按钮，图像动作模板旁新增「识别」，使用当前模板及阈值准备预览，打开预览不自动截图、不保存宏。相对模板路径仍复用 MR 目录解析；识别窗口阈值范围与动作编辑器一致为 1%～100%。关闭/替换编辑器或切换动作类型时同时释放识别窗口。

识别采集改为自动遮蔽并隐藏所有本程序可见窗口，先记录窗口列表，再隐藏，避免父窗口连带隐藏子窗口导致遗漏。复用 `WindowsCaptureSupport` 的 DWM 遮蔽/恢复与后台同步，保留既有 180ms 非阻塞准备定时器；采集完成后恢复原可见窗口和扩展状态，匹配继续在后台运行。原来隐藏、已经销毁的窗口不主动显示；取消或释放时同步清理，旧 worker/迟到定时器不再恢复已关闭窗口。DWM 同步不代表桌面合成已完成，实际无残影仍需人工确认。

新增隔离入口 `LegacyRegressionTest#modelessActionEditor`（运行 `ModelessActionEditorTest`）检查非模态、替换/旧回调、动态编号、删目标/换宏/锁定后的关闭、主页入口移除、模板和阈值预载、预览复用及释放；配置与合成模板均在 `target/test-sandboxes/`。新增 `RecognitionWindowVisibilityTest` 2 项，使用真实隐藏句柄和模拟可见状态检查父子窗口完整快照、原隐藏窗口保持隐藏、扩展状态恢复、幂等恢复、已销毁编辑器不重开及关闭后的迟到事件。没有真实显示窗口、截图或发送输入；无桌面时相关入口明确跳过。

实际命令与结果：

- `mvn '-Dsurefire.timeout=180' '-Dtest=RecognitionWindowVisibilityTest,ImageRecognitionFrameTest,ImageJumpEditorTest,ImageCheckEditorTest,NumericEditorIntegrationTest,ScreenRecognitionServiceTest,TimelineNumberingTest,LegacyRegressionTest#modelessActionEditor+timelineLivePlaybackProtection+templatePathPlaybackAndEditor+recordingTitleBarContract' test`：18 项，失败 0、错误 0、跳过 0，退出码 0，23:42:55 完成；日志 `target/modeless-editor-directed-verified.log`。其中 `ImageCheckEditorTest`、`NumericEditorIntegrationTest` 是旧式 main，未因该选择器单独执行；实际 18 项来自其余 JUnit 入口。
- `mvn '-Dsurefire.timeout=180' test`：105 项，失败 0、错误 0、跳过 0，退出码 0，23:44:36 完成；日志 `target/modeless-editor-full-test.log`。
- `mvn compile`：退出码 0，23:45:23 完成；日志 `target/modeless-editor-compile.log`。此前初次编译也成功，日志 `target/modeless-editor-initial-compile.log`；最终生产类已在定向测试中重新编译。

首轮定向 18 项中 3 项失败：模拟窗口未创建实际句柄，JNA 无法取得绘图表面；预览复用断言也发生在创建句柄之前。夹具改为仅创建隐藏句柄、保持不显示，保留原断言目标后执行上述最终验证。初次日志 `target/modeless-editor-directed.log` 保留。

Windows 11、150% DPI 人工验收：编辑时主界面可操作，连续打开动作直接替换且无提示；右上角编号随时间线变化；模板旁入口位置及当前图片/阈值载入；识别画面不含主窗口、编辑器、识别窗口或残影；完成、失败、关闭期间窗口正确恢复；不抢录制/播放输入焦点，录制/播放启动仍锁住编辑。未执行 `mvn clean`，未提交、未推送。

## 2026-10-24：移除 Java 识别，仅保留 OpenCV

删除 `TemplateMatcher`、`TemplateMatcherFallbackTest` 及其标准入口 `LegacyRegressionTest#javaFallbackResults`。`ScreenRecognitionService` 移除引擎参数、枚举及报告中的引擎字段，仅使用已有 OpenCV 匹配；播放匹配器与诊断检查同步调用新入口。MR 配置、模板路径、阈值与分支语义不变。识别测试窗口只保留「识别当前屏幕」按钮，移除结果中的引擎信息，保留匹配位置、内存统计和原有隐藏/恢复流程。

新增 `ScreenRecognitionServiceTest` 4 项：以真实 OpenCV 和合成画面检查跨屏匹配、150% 坐标换算和负坐标、阈值及最佳候选、空屏幕列表、跨屏结果上限和中断取消。新增 `ImageRecognitionFrameTest` 2 项：隐藏窗口检查单一识别按钮、缺少模板/忙碌/清空状态、命中与未命中报告以及重置后没有引擎显示。没有采集真实桌面或发送输入；隐藏窗口检查无桌面时明确跳过。

本轮由 97 项减去 Java 实现专属的 1 项、增加 6 项，完整执行 102 项。实际命令与结果：

- `mvn '-Dsurefire.timeout=180' '-Dtest=ScreenRecognitionServiceTest,ImageRecognitionFrameTest,LegacyRegressionTest#openCvPerfectThreshold+imageCheckBehaviors+flowPreflightValidation+templatePathPlaybackAndEditor' test`：10 项，失败 0、错误 0、跳过 0，退出码 0，23:20:37 完成；日志 `target/opencv-only-directed.log`。
- `mvn '-Dsurefire.timeout=180' test`：102 项，失败 0、错误 0、跳过 0，退出码 0，23:22:15 完成；日志 `target/opencv-only-full-test.log`。
- `mvn compile`：退出码 0，23:22:46 完成；日志 `target/opencv-only-compile.log`。生产类已在定向测试中重新编译。

`ImageRecognitionDiagnosticsTest` 的实际桌面截图入口仅同步 API 并参与编译，本轮没有运行该人工诊断入口。Windows 11、150% DPI 仍需人工检查单一按钮的布局、实际识别及阈值、多结果切换、识别时隐藏应用窗口和完成/异常/关闭恢复。历史 Java 回退测试记录保留，仅代表删除前的历史结果。本轮未执行 `mvn clean`，未提交、未推送。

## 2026-10-24：设置中的只读快捷键页

设置侧栏新增「快捷键」，只显示现有按键、作用和生效条件，不注册新快捷键、不增加配置键、不提供改绑控件。复用现有深色卡片；滚动高度按当前页面计算，避免长列表拉长其他设置页。

新增隔离入口 `LegacyRegressionTest#shortcutsSettings`，运行隐藏的 `ShortcutsSettingsTest`：检查独立导航、F10/F11/F12、Delete、Enter/Esc、Alt+F4、Ctrl+V 及各自场景，页面不含可编辑控件；在 820/920 两种窗口宽度下检查说明完整、纵向滚动及离屏绘制；切回原页面恢复滚动高度和顶部；查看与取消后配置字节保持不变。配置仅写入本轮 `target/test-sandboxes/` 副本，不注册 Hook、不发送输入、不读写剪贴板。

实际命令与结果：

- `mvn '-Dsurefire.timeout=180' '-Dtest=LegacyRegressionTest#shortcutsSettings+standbyHeadSettings+pauseHotkeyRepeat+recordingPause+timelineHeadlessEditing+recordingControlInputFilter' test`：6 项，失败 0、错误 0、跳过 0，退出码 0，23:09:12 完成；日志 `target/shortcuts-settings-directed-verified.log`。
- `mvn '-Dsurefire.timeout=180' test`：97 项，失败 0、错误 0、跳过 0，退出码 0，23:10:39 完成；日志 `target/shortcuts-settings-full-test.log`。
- `mvn compile`：退出码 0，23:10:50 完成；日志 `target/shortcuts-settings-compile.log`。生产类在定向测试时已重新编译，本次 compile 确认是最新。

最初定向 6 项中新增布局检查失败 1 项，复核时单独执行仍失败；说明标签在 BoxLayout 下宽度不足。改为填满说明区域的双行布局后，执行上述最终定向及完整验证。初次日志 `target/shortcuts-settings-directed.log`、`target/shortcuts-settings-layout-check.log` 保留。

隐藏窗口与离屏绘制不等同于真实桌面验收；Windows 11、150% DPI 仍需确认侧栏入口、按键说明的清晰度、列表滚动、切页及只读观感。未执行 `mvn clean`，未提交、未推送。

## 2026-10-24：暂停查看时间线与实际执行标记

本轮在 86 项基础上增加 10 个标准入口，最终完整执行 96 项。新增 `PlaybackProgressBufferTest` 3 项和 `TimelinePlaybackProgressTest` 5 项，检查大量重复进度仅安排一次待处理 EDT 刷新、按节点去重保留完成身份、每轮重置、旧待处理刷新清空、当前/完成/未执行卡片的实际像素区别、卡片对象与布局保持、只读选择和编辑限制、重复状态不请求重绘/布局，以及头部待机状态。

两个隔离入口 `LegacyRegressionTest#actualPlaybackProgress`、`#playbackTimelineWindow` 分别运行 `PlaybackProgressTest`、`PlaybackTimelineWindowTest`。前者使用计数 Robot 与模拟匹配器，验证前跳跳过动作、回跳、重试、每轮顺序、终止、取消与识别异常不误报完成、识图完成但暂停时的标记、输入尚未结束时不确认暂停、暂停确认回调重入继续不丢唤醒、F11 分流去重/F12 直接停止及观察者异常隔离。后者使用隐藏主窗口和模拟扩展状态/目标准备，检查暂停信号与安全暂停点分离、只读时间线自动显示、页面切换保留标记、继续前准备目标、准备失败保持暂停、最小化设置、迟到通知及停止收尾。测试不注册 Hook、不发送真实键鼠输入、不激活真实目标窗口、不写系统剪贴板；这两个入口在非 Windows 或无桌面时明确跳过。

最终实际命令与结果：

- `mvn '-Dsurefire.timeout=180' '-Dtest=PlaybackProgressBufferTest,TimelinePlaybackProgressTest,StandbyHeadTimelineTest,TimelineNumberingTest,TimelineJumpLayoutTest,LegacyRegressionTest#actualPlaybackProgress+playbackTimelineWindow+pauseHotkeyRepeat+playbackWindowRestore+timelinePlaybackProtection+standbyHeadPlayback' test`：27 项，失败 0、错误 0、跳过 0，退出码 0；日志 `target/playback-timeline-directed-verified.log`。
- `mvn '-Dsurefire.timeout=180' test`：96 项，失败 0、错误 0、跳过 0，退出码 0；2026-10-24 22:28:54 完成，日志 `target/playback-timeline-full-test-verified.log`。180 秒仅为测试进程的故障超时，未跳过或降低任何断言。
- `mvn compile`：退出码 0；日志 `target/playback-timeline-compile.log`。本轮生产源码已在定向运行前重新编译。

早期 `target/playback-timeline-directed.log` 因新增测试未处理辅助方法的受检异常而编译失败，退出码 1；修正后 `target/playback-timeline-directed-v2.log` 的 22 项通过。首次完整回归 `target/playback-timeline-full-test.log` 停在此前待机夹具通过回车提交非法值产生的模态警告，主动中断、退出码 1，不能算通过。随后保留同样的非法数值、模型不写入与错误提示断言，改用失焦校验路径，避免自动测试等待用户确认；没有修改产品的回车警告行为。安全暂停点修正后的 `target/playback-timeline-directed-final.log` 27 项通过；测试夹具最后调整后再执行上述最终定向、完整测试与编译，所有旧日志保留。

实现边界：`MacroPlayer` 上报真实节点开始/完成与每轮状态，完成光晕表示本轮执行过，不表示图像匹配成功；跳过及未执行完的动作不标完成。静态内光晕复用主题 success 色，当前卡复用 accent 边框，不增加布局占位、动画计时器或高频日志。`PlaybackProgressBuffer` 合并更新，时间线只重绘变化的卡片。每轮/新播放清空，暂停保留，停止清除当前标记并保留本轮已完成结果；换宏清空，返回同一时间线保留。进度仅存内存，不写 MR、不改变节点 ID 或分支目标。

F11 仍立即设置暂停状态；等播放线程完成在途输入/识别并进入暂停等待后，主窗口才前置到只读时间线。继续按钮与 F11 继续共用目标准备入口，先按配置最小化/激活目标，再解除暂停。没有指定目标窗口的宏若无法确定要恢复的应用，会保持暂停并提示先切回应用再按 F11。F12 始终直接停止，不等待 EDT 处理继续请求。原播放结束恢复配置及原窗口状态记录保留。

`target/playback-timeline-preview/progress-150-final.png` 是真实 Swing 组件的 1.5 倍离屏绘制，用于检查视觉，不是真实 Windows DPI/前台验收。Windows 11、150% DPI 仍需人工检查：F11 暂停后窗口前置与定位、选择/滚动/复制且不能编辑、继续按钮/F11 的目标焦点与最小化设置、F12 停止恢复、图像识别期间暂停、真实前跳/回跳/重试/循环的光晕标记，以及文字清晰度。未执行 `mvn clean`，未提交、未推送。

## 2026-10-24：头部待机增量回归

本轮在 75 项基础上新增 11 个标准入口，完整运行 86 项。`StandbyHeadTimelineTest` 的 5 项检查覆盖默认 1000ms、只在线上编辑、非法数值与旧回调保护、开关/编辑权限、插入删除与重载、原卡片布局/编号/分支引用及动作数量保持；`StandbyHeadStorageTest` 的 4 项使用工作区隔离目录，检查等待值与旧文件往返、零/超大等待、负值保存和加载拒绝、原文件与临时文件保全，以及模板路径转换仍保留待机和跳转。

两个 `LegacyRegressionTest` 入口 `standbyHeadSettings`、`standbyHeadPlayback` 使用隔离应用副本：前者构造隐藏设置/主窗口，验证设置保存与取消、配置失败回滚、快捷开关同步、新录制默认和录制收尾；后者注入计数 Robot 和模拟匹配器，验证每轮等待、原动作延迟、暂停冻结、停止、负值预检，以及识图耗时不抵扣后继动作延迟。不显示窗口、不注册 Hook、不发送真实键鼠输入；这两个入口在非 Windows 或无桌面时明确跳过。

实际命令与结果：

- `mvn '-Dtest=StandbyHeadTimelineTest,StandbyHeadStorageTest,TimelineNumberingTest,TimelineJumpLayoutTest,LegacyRegressionTest#standbyHeadSettings+standbyHeadPlayback+timelineHeadlessEditing+timelinePlaybackProtection+imageCheckBehaviors+playbackDelayBounds' test`：23 项，失败 0、错误 0、跳过 0，退出码 0；日志 `target/standby-head-directed-verified.log`。
- `mvn test`：86 项，失败 0、错误 0、跳过 0，退出码 0；2026-10-24 19:46:38 完成，日志 `target/standby-head-full-test.log`。
- `mvn compile`：退出码 0；日志 `target/standby-head-compile.log`。生产源码已在本轮定向运行前重新编译。

首轮定向测试 13 项中 4 项存储检查在系统临时目录替换文件时遇到 AccessDenied，退出码 1，日志 `target/standby-head-directed.log` 保留。新增存储夹具随后改用项目现有的工作区隔离目录，断言和实际保存逻辑保持不变；上述最终定向及完整测试均已实际重跑通过。

使用约定：设置页与时间线共用「增加待机头节点」开关，修改时作用于当前宏并记作新录制默认选择；打开已有宏以文件内保存状态为准。开启时默认额外等待 1000ms，不提供默认时长设置项；连线只修改本宏的待机值，原首动作自身延迟仍保留。每轮从流程入口开始前等待，分支回跳不再次执行头部等待。不新增尾部待机、真实动作类型或跳转目标；MR 新增可选 `flow.headWaitMillis`，旧文件缺省表示关闭，关闭时不写此字段，格式版本号未变。

Windows 11、150% DPI 仍需人工检查两个开关的实际操作、连线时间编辑、只读待机卡、原有节点布局/跳转、新录制与保存重开，以及等待期间的暂停、恢复和 F12 停止。自动计数和程序化控件检查不等于真实桌面验收。面向使用者的简明变化见 [更新日志](../CHANGELOG.md)。本轮未执行 `mvn clean`，未提交、未推送。

## 2026-10-24：Windows 单实例启动增量回归

本轮新增 `WindowsSingleInstanceTest` 的 5 项标准测试入口，在此前 70 项（含 F12 播放窗口恢复回归）基础上，完整测试实际执行 75 项。新增测试使用独立实例标识、真实 Windows 互斥量与 message-only 窗口及独立 JVM，检查跨进程重复启动通知、同时启动唯一主实例、关闭后重新获取、异常终止后立即重启、回调异常后的通知与互斥量保全；不启动应用 UI、不读个人配置、不发送真实键鼠输入。非 Windows 平台明确跳过这 5 项。

最终命令与结果：

- `mvn '-Dtest=WindowsSingleInstanceTest,LegacyRegressionTest#playbackWindowRestore+recordingTitleBarContract+pauseHotkeyRepeat' test`：8 项，失败 0、错误 0、跳过 0，退出码 0；日志 `target/single-instance-directed-verified.log`。
- `mvn test`：75 项，失败 0、错误 0、跳过 0，退出码 0；2026-10-24 18:05:42 完成，日志 `target/single-instance-full-test.log`。
- `mvn compile`：退出码 0；2026-10-24 18:06:10 完成，日志 `target/single-instance-compile.log`。生产代码已在定向运行时重新编译。

初次编译缺少 JNA 未声明的 `WM_APP` 常量、首轮原生测试中的 64 位 `HWND_MESSAGE` 指针值，以及异常终止后立即启动的资源回收竞争均已修正。失败日志保留在 `target/single-instance-initial-compile.log`、`target/single-instance-directed.log`、`target/single-instance-native-tests.log` 和 `target/single-instance-native-tests-final.log`，不将这些尝试算作通过。回收竞争修复后的单项检查为 1 项通过，日志 `target/single-instance-crash-retry-test.log`；随后已执行上述最终定向、完整测试与编译。

真实可见窗口前置、最小化恢复、录制/播放时重复启动及 150% DPI 尚未人工验收，见 [单实例行为与人工清单](single-instance.md)。未执行 `mvn clean`，未提交、未推送。

## 1.0.0 收尾及历史记录

1.0.0 正式版收尾已在统一版本后重新验证：2026-10-21 定向 51 项（12:12:41 完成）、完整 69 项（12:13:56 完成），失败/错误/跳过均为 0、退出码 0；12:14:10 完成的 `mvn compile` 退出码 0。原始日志为 `target/release-1.0.0-directed.log`、`target/release-1.0.0-full-test.log` 和 `target/release-1.0.0-compile.log`。本地收尾提交为 `5c2d464c29c488c90b381edc2422c775f332dadf`，不包含推送、标签或安装包发布。原收尾过程文档已删除，历史内容可从 Git 查看；本段及下文历史结果不代替文首最新运行结果。

## 运行

在项目根目录、JDK 17 和 Maven 环境中执行：

```powershell
mvn test
mvn test '-Djava.awt.headless=true'
```

截至 2026-10-21，当前提供 69 个标准 JUnit 测试入口；最近 Windows 桌面环境完整运行 69 项，失败/错误/跳过均为 0。此前 51 项之外，新增深色禁用样式与独立目标切换 3 项、编号与多线布局 4 项、保存前校验 6 项、侧键保护 5 项。无桌面时图像分支、实际播放器桌面检查、计数 Robot 流程预检、播放延迟边界、暂停快捷键、模板编辑器/实际播放器、原生标题栏契约、原生改宽、刷新去重、跳转播放/编辑器、侧键编辑器/计数 Robot 及截图窗口检查明确跳过，非 Windows 平台还会跳过对应原生检查。OpenCV 满分检查强制 headless，但需要依赖中可加载的本机 OpenCV 运行库；Java 回退、模板路径和几何计算无窗口检查不加载原生匹配库。实际数量以该次 Surefire 报告为准，不把 skipped 当成通过。最新命令和结果见下方“2026-10-21 当前增量回归记录”。

Maven Surefire 固定为原环境已使用的 3.1.2，并增加 test scope 的 JUnit Jupiter 5.10.5。没有升级应用运行时依赖，JUnit 不进入现有 runtime 依赖复制范围。标准测试入口使用正常的 `*Test` 命名及 `@Test`，没有建立只允许固定文件名的 Surefire includes，后续标准测试仍可按默认规则发现。

`failIfNoTests=true` 和 `failIfNoSpecifiedTests=true` 防止零测试或错误的 `-Dtest` 选择静默返回成功。显式的 `-DskipTests` 等 Maven 跳过选项仍按 Maven 原有语义生效。Surefire 测试 JVM 及旧检查子 JVM 使用 UTF-8。

## 为什么保留子 JVM 隔离

单纯将旧 `main()` 包在 `@Test` 中仍不安全：`AppConfig` 根据生产类 CodeSource 判断应用目录。即使更改工作目录，从 `target/classes` 加载时仍会定位到项目根目录。

`LegacyMainRunner` 为每个已审核检查创建独立目录：

```text
target/test-sandboxes/<检查类名>-<随机值>/
├── classes/       生产类和正式资源副本；AppConfig 在这里新建配置与默认目录
├── target/        旧测试使用相对 target 路径时的隔离输出
├── tmp/           java.io.tmpdir，旧测试临时 MR 等文件也在这里
└── output.log     完整标准输出与异常输出
```

子进程使用当前 JDK、绝对 classpath 和独立工作目录；原生产类目录在其 classpath 中被副本替代，不继承构建目录中可能残留的根级 `config.properties`。调用旧入口前再次核验 AppConfig 类来源，未隔离则直接失败，不先构造配置对象。

旧入口的原始断言保持不变，反射调用时解包 `InvocationTargetException`，非零退出转为 JUnit 失败。每个检查有 60 秒期限；超时/中断时终止该次创建的测试进程，避免后台线程挂住构建。完整日志保留，失败摘要仅附日志末尾，避免测试输出过大。不会枚举并运行任意 main，也不会自动启动人工测试。

两个机制检查分别验证：旧式 `AssertionError` 能传回标准测试入口；AppConfig、相对文件和系统临时文件确实落入新建隔离目录。它们不是新增业务功能测试。

RP-01 新增的 `RecordingMouseCoordinatesTest` 也通过该隔离入口运行：模拟事件位置与当前指针位置不同，检查按下/释放、移动/滚轮、目标窗口物理坐标、控件过滤及配对、100%/150% 转换、负坐标、原采样规则和截图边界的即时查询。检查使用合成事件及模拟窗口查询，不注册钩子、不发送真实输入、不显示窗口；过滤器仍依赖 Windows 原生计时，非 Windows 环境明确跳过。真实高速输入、跨屏及 DPI 桌面行为仍需人工验收。

FS-05 新增 `TimelinePlaybackProtectionTest`：无窗口入口验证 PLAYING/PAUSED/STOPPING 下所有时间线修改入口、坐标/普通延迟/重试延迟旧编辑器、旧弹窗及确认回调，并检查空闲后恢复编辑。嵌套的 `Desktop` 入口使用实际 `MacroPlayer` 和仅含图像节点的流程，模拟匹配并暂停/恢复，验证运行图不变、预先创建但不显示的真实编辑器不能保存、播放结束后能够插入。该入口需要 Robot 和 JDialog 的构造环境，但不执行输入动作、不截图、不显示窗口。实际可见弹窗关闭、鼠标导航及滚动/复制仍需人工验收。

FS-02 新增 `FlowPreflightValidationTest`：检查 ACTION 缺少 ALWAYS 后继、非法入口/节点/引用，以及 IMAGE_CHECK 在 EXECUTE 下缺少精确或 ALWAYS 后备分支。非法图在前面放置合法输入节点，断言拒绝时计数 Robot、匹配器和播放状态回调均未被调用。合法检查覆盖精确条件优先、ALWAYS 后备、仅单侧执行、旧默认行为、自回路、无普通后继的重试及整任务终止。沿用已有计数 Robot 注入方式并覆盖全部输入方法，不显示窗口、不截图、不发送真实输入；构造 Robot 需要桌面环境，无桌面时通过 JUnit 明确跳过。

FS-03/FS-04 新增 `AppConfigSafetyTest.Write` 和 `AppConfigSafetyTest.Parse` 两个隔离入口，均强制 headless。Write 实际创建、原子保存并重载配置，再模拟部分写入、替换失败及 `AtomicMoveNotSupportedException`，验证返回 false、内存回滚、正式文件内容/时间不变、新临时文件清理及此前已有同前缀文件保全；不支持原子替换时不得非原子回退。Parse 验证 UTF-8、合法 Unicode/普通转义、续行、旧配置及空配置，并对非法 Unicode 与目录读取失败分别检查诊断、默认值、无部分值残留及原文件保全。仅使用各自隔离 classes 目录，不读取个人配置或显示窗口。当前 Windows 目录的正常原子替换已实际测试；其他文件系统及真实锁文件/权限故障不作为已验收。

RP-02 新增 `PlaybackDelayTest`，经 `LegacyRegressionTest#playbackDelayBounds` 在隔离子 JVM 运行。检查零/普通延迟，以及最大安全毫秒值 `9223372036854`、首个溢出值 `9223372036855`、原乘法绕回小正数的 `18446744073710` 和 `Long.MAX_VALUE`；大值分别覆盖旧动作列表、流程 ACTION、分支延迟及图像重试。短时间观察仍在等待、未执行动作或跳转，再用合成 F12 停止；同时检查暂停保留剩余时间、暂停中停止及线程中断后的 IDLE 恢复。计数 Robot 覆盖所有输入方法，图像匹配为模拟结果，不注册 Hook、不截图、不显示窗口、不发送真实输入。Robot 构造需要 Windows 桌面，无环境时明确跳过。不会实际等待超大时长，也未模拟一个完整的数百年等待段；分段保留总毫秒数由源码核对，真实 F11/F12 和窗口联动仍需人工验收。2026-10-20 定向 2 项、完整 `mvn test` 17 项均通过（失败/错误/跳过均为 0），`mvn -q -DskipTests compile` 退出码为 0；日志见 `target/rp-02-2026-10-19/`。

RP-03 新增 `StopKeyListenerTest`，经 `LegacyRegressionTest#pauseHotkeyRepeat` 在隔离子 JVM 运行。用实际播放器、仅含 IMAGE_CHECK 的重试流程、模拟匹配及计数 Robot 检查首次/重复 F11 按下、暂停时释放后重按、其他键释放不解除去重、按钮恢复播放时继续按住 F11、跨空闲/播放的持续按下与迟到释放，以及未收到释放便结束后的新监听器。F12 分别在播放和暂停中停止整个任务，重复 F12 不改变空闲状态。直接调用录制回调验证 F11 仍作为普通按键录入，录制暂停/继续及 F12 保持原语义；不注册 Hook、不显示窗口、不截图、不发送真实输入，配置仅在隔离目录创建。构造 Robot 和录制器需要 Windows 桌面环境，不可用时明确跳过。2026-10-20 定向 4 项、完整 `mvn test` 18 项均通过（失败/错误/跳过均为 0），编译退出码 0，日志见 `target/rp-03-2026-10-20/`。真实长按、焦点切换、最小化/恢复期间的全局事件投递仍需人工验收，不能将合成事件视为真实桌面操作已通过。

RN-01 新增 `OpenCvPerfectThresholdTest`，经 `LegacyRegressionTest#openCvPerfectThreshold` 在隔离、强制 headless 的子 JVM 运行。真实 OpenCV 检查 8/31/64/127/256 五种尺寸与三个种子的15组精确复制；另向结果收集路径注入 `CV_32F` 的非零、零和负舍入误差，确定性验证完全相同的模板被接受、单通道相差一个单位仍被100%拒绝。补充明显不同图像、普通阈值评分及通过/拒绝、1×1小图、220透明掩码边界、全部半透明时的既有掩码规则、全透明模板，以及多个匹配位置、最佳结果、排序和截断。只创建内存图像并加载真实本机 OpenCV，测试所建 Mat 均释放，不截图、不读取个人素材、不发送键鼠输入。2026-10-20 定向 1 项和完整 `mvn test` 19 项均通过（失败/错误/跳过均为0），`mvn -q -DskipTests compile` 退出码0；日志及退出状态见 `target/rn-01-2026-10-20/`。跨 DPI 的真实屏幕与大图性能未在本次新增检查中实测。

RN-02 新增 `TemplateMatcherFallbackTest`，经 `LegacyRegressionTest#javaFallbackResults` 在隔离、强制 headless 的子 JVM 运行。沿用审查的合成素材：反转中层采样网格点，使中层筛选失败而细节评分仍达标，验证64/100/127尺寸的回退候选、偏移坐标、原阈值边界、未匹配及全透明无候选；三个不同评分的回退位置检查最佳选择、排序和限额替换，普通扫描与回退的同一位置检查去重。修复前单独执行该入口得到预期失败：`matched=true`、相似度 `0.9683523539624183`、位置 `(2,3)`，但列表为空（1项失败、退出码1）。修复后与 OpenCV 满分回归一起定向执行2项通过，完整 `mvn test` 20项通过，两次失败/错误/跳过均为0、退出码0；编译退出码0。日志及退出状态见 `target/rn-02-2026-10-20/`。新增检查只创建内存图像，不访问文件素材、不截图、不发送真实输入；OpenCV 实现及 RN-01 测试保持原样，真实界面交互未在本轮人工验收。

FS-01 新增 `TemplatePathIntegrationTest`（强制 headless）和嵌套 `Desktop` 两个隔离入口，分别由 `templatePathResolution` 和 `templatePathPlaybackAndEditor` 运行。无窗口检查验证绝对/相对路径、未保存时工作目录基准、历史 MR 相对引用、跨目录存在同名 PNG 时仍引用旧图片、同目录不改相对路径、缺失模板仍可保存/加载及旧 MR/PNG 字节保全。Desktop 需要 Windows 桌面，仅构造不显示的编辑器和主窗口：实际调用编辑保存与 `saveMacroToFile`，验证当前文件/时间线同步、模拟存储失败不改原基准；计数 Robot 与模拟匹配器委托正式 OpenCV 实现的资源预检，检查缺失/空/目录引用及不可达图像节点在 PLAYING/输入/匹配之前拒绝，合法文件正常执行。正式文件型匹配器覆盖 `ImageMatcher.validate`；原有内存匹配器/测试替身保持默认无需外部资源，不改变旧算法测试。

FS-01 首轮定向执行2项、1项因桌面子 JVM 完成断言后未退出而超时（退出码1），保留原日志；随后只为新建的独立桌面夹具补充显式退出码，异常退出1、全部检查完成退出0，没有修改应用退出逻辑。最终定向2项、完整 `mvn test` 22项均通过（失败/错误/跳过均为0、退出码0），编译退出码0，日志和状态位于 `target/fs-01-2026-10-20/`。测试仅使用隔离配置和临时合成 PNG/MR，不注册 Hook、不截图、不显示原生文件面板、不发送真实键鼠输入；真实鼠标另存为、可见错误提示及150% DPI界面交互仍需人工验收。

隔离目录保留用于诊断，未在测试结束时递归清理；未来 `mvn clean` 会删除 target，也会删除此前审查原始产物，需要长期保留时先另行归档。

2026-10-20 紧凑录制栏增加 `RecordingControlBarLayoutTest` 两个 JUnit 检查，直接构造 Swing 面板：覆盖全局/缺失目标、纯文本标题、双行顺序、长文本省略、固定按钮、首次空标签刷新及不同字体尺寸。`LegacyRegressionTest#recordingTitleBarContract` 使用 `LegacyMainRunner` 运行 `RecordingTitleBarContractTest`，在隔离配置下构造隐藏 MacroFrame，检查目标状态接入、四按钮命中、拖动命中、DPI 原生最小拖动尺寸、自绘最小化请求、系统按钮样式隐藏、样式/窗口过程恢复和重复进入。不注册 Hook、不截图、不发送真实输入；隐藏窗口的状态与命中断言不等于可见桌面的实点验收。

本次最终定向 3 项、完整 `mvn test` 25 项均通过，失败/错误/跳过均为 0、退出码 0；`mvn compile` 退出码 0。最终日志为 `target/recording-titlebar-test-final.log`、`target/recording-titlebar-full-test-final.log`、`target/recording-titlebar-compile-final.log`。早期测试编译错误和尺寸断言失败已修复，失败日志保留；详情及人工验收清单见 `docs/recording-control-bar.md` 的 2026-10-20 记录。

同日后续置顶修复扩展上述隐藏窗口契约，检查录制/暂停/恢复置顶、同一 HWND、最小化恢复后的置顶属性、原置顶值恢复及重入/重复退出，保持原焦点能力并观察隐藏窗口切换不改变前台。沿用同一 JUnit 入口，标准入口总数仍为 25。本次仅运行 `mvn '-Dtest=RecordingControlBarLayoutTest,LegacyRegressionTest#recordingTitleBarContract+recordingPause+recordingControlInputFilter' test`：5 项，失败/错误/跳过均为 0，退出码 0；`mvn compile` 退出码 0。日志为 `target/recording-topmost-tests.log`、`target/recording-topmost-compile.log`。未重新运行完整测试；真实目标应用焦点与可见窗口层级仍需人工验收。

同日后续水平缩放及位置记忆新增 `RecordingBarGeometryTest` 的 4 个 JUnit 检查和 `LegacyRegressionTest#recordingBarPlacement` 隔离入口（运行 `RecordingBarPlacementTest`），总数增至 30。纯几何检查包括负坐标、多屏、移除显示器/分辨率变化、极值、固定高度、100%/150% 约束与命中映射。配置检查使用隔离 classes 下的配置，实际保存重载独立 X/Y/宽度，验证无高度、非法值回退、同值不写盘、其他配置保全及原子替换失败回滚。既有隐藏窗口契约增加原生 `WM_GETMINMAXINFO / WM_SIZING / WM_WINDOWPOSCHANGING / WM_NCHITTEST` 消息检查，以及改宽后退出保存、再次进入恢复、最小化时退出保存、主页尺寸/位置/置顶恢复。原专项可见界面夹具的顶边命中断言同步改为禁止纵向缩放，本轮未执行该可见夹具。

本次实际定向命令为 `mvn '-Dtest=RecordingBarGeometryTest,RecordingControlBarLayoutTest,LegacyRegressionTest#recordingTitleBarContract+recordingBarPlacement+configurationWriteSafety+configurationParseSafety' test`：10 项通过；完整 `mvn test`：30 项通过。两次失败/错误/跳过均为 0，退出码 0；`mvn compile` 退出码 0。日志为 `target/recording-geometry-tests-verified.log`、`target/recording-geometry-full-test.log`、`target/recording-geometry-compile.log`。扩展夹具时的类型编译错误已修复，失败日志保留在 `target/recording-geometry-tests-final.log`，该失败构建未执行测试。所有新检查不注册 Hook、不显示窗口、不截图、不发送真实输入；真实 Windows 原生拖边缩放和跨屏 DPI 行为待人工验收，清单见 `docs/recording-control-bar.md`。

同日后续内容铺满修复增加 `LegacyRegressionTest#recordingBarNativeResize`，通过隔离子 JVM 执行 `RecordingBarNativeResizeTest`。此入口使用屏幕外已显示的实际 MacroFrame（禁止自动焦点请求），直接从 Win32 改宽，覆盖较窄/默认/较宽及缩回、尺寸移动阶段消息、旧尺寸根面板的刷新恢复，比较原生客户区与各级 Swing 宽度、两组按钮右对齐，并以纯内存绘制检查右侧背景和分隔线；不截图、不注册 Hook、不发送真实输入。它不属于原先“不显示窗口”的隐藏契约入口。普通探针在修复前已能同步，未直接复现用户截图；本轮测试验证的是新增的显式布局同步保障，不作为原截图现场验收结论。

实际命令 `mvn '-Dtest=RecordingControlBarLayoutTest,RecordingBarGeometryTest,LegacyRegressionTest#recordingBarNativeResize+recordingTitleBarContract+recordingBarPlacement' test`：9 项通过；完整 `mvn test`：31 项通过。两次失败/错误/跳过均为 0、退出码 0；`mvn compile` 退出码 0。日志为 `target/recording-resize-tests.log`、`target/recording-resize-full-test.log`、`target/recording-resize-compile.log`。真实 Windows 11、150% DPI 下三种宽度及连续拖边的画面仍待人工复核。

同日录制截图准备优化增加 `ScreenCaptureControllerTest` 的 5 个 JUnit 检查：录制路径无固定 Timer、同步/采集不在 EDT、取消与重复取消/迟到工作结果、模拟确认与采集失败恢复、原应用遮蔽状态保留、普通截图旧 Timer 不变、框选层不继承所属窗口临时遮蔽。只使用合成图像及屏幕外窗口，不进行真实桌面捕获、不发送键鼠输入，不读写个人配置。`CaptureTimingProbe` 和 `CaptureDesktopAcceptance` 是显式诊断/人工夹具，不被 Maven 自动运行。

最终定向 `mvn '-Dtest=ScreenCaptureControllerTest,LegacyRegressionTest#recordingPause+recordingControlInputFilter+recordingTitleBarContract' test`：8 项通过；完整 `mvn test`：36 项通过。两次失败/错误/跳过均为 0、退出码 0；`mvn compile` 退出码 0。日志见 `target/capture-flow-tests-final.log`、`target/capture-flow-full-test-final.log`、`target/capture-flow-compile-final.log`。

显式桌面像素基准在本机 150% DPI 的自建纯色背景小区域执行，正式接口前后各 20 次，隐藏到图像取得中位为 171.32/14.57 ms，残影均 0/20，日志 `target/capture-timing-production.log`。不是全屏框选端到端延迟保证。Computer Use 实点验收因应用授权超时未完成，夹具已退出，不能报告真实框选和取消已通过；调用顺序、原生 API 边界及人工清单见 `docs/recording-control-bar.md` 的截图优化记录。

同日修复真实拖边路径的 `WM_SIZING` 转发：原实现约束 RECT 后直接返回，未进入 AWT 动态布局/绘图表面更新。生产分支现在先约束，再经保存的 `originalProc` 调用 `CallWindowProc`，最后返回 TRUE 表示已处理矩形，不新增 validate/repaint。`RecordingBarNativeResizeTest` 改为开启并断言动态布局有效，在原 AWT 前接入有强引用保护的测试观察层；合成 WM_SIZING 检查约束后单次转发、返回 TRUE、原 AWT 尺寸事件、八个方向的定高/最小宽度及锚点，随后用原生改宽检查容器和按钮布局，退出时检查两层消息链恢复。仍为屏幕外窗口和合成原生消息，不是真实鼠标拖动或 GPU 桌面像素验收，不增加 JUnit 入口数量。

修复前定向 1 项得到预期失败（退出码 1）：动态布局为 true，但 WM_SIZING 未转发；日志 `target/native-sizing-before.log`。首次修复后定向 9 项中 1 项因新夹具使用初次显示高度进行 DPI 换算而失败，日志 `target/native-sizing-directed.log`；夹具改为读取 WM_GETMINMAXINFO 的实际固定高度，并直接比较原生物理像素，不放宽生产约束。最终定向 9 项通过（失败/错误/跳过均为 0、退出码 0），日志 `target/native-sizing-directed-final.log`。

本次最终执行 `mvn '-Dtest=LegacyRegressionTest#recordingBarNativeResize+recordingTitleBarContract+recordingBarPlacement,RecordingBarGeometryTest,RecordingControlBarLayoutTest' test`：9 项；`mvn test`：36 项。两次均全部通过，失败/错误/跳过均为 0、退出码 0。`mvn compile` 退出码 0。完整测试及编译日志为 `target/native-sizing-full-test.log`、`target/native-sizing-compile.log`。真实鼠标拖边、D3D/OGL 桌面画面、最小化恢复及跨模式状态恢复仍需按操作栏文档清单人工验收。

同日刷新去重优化新增 `RecordingTitleBarRefreshTest` 的 4 个 JUnit 检查。保持并断言动态布局开启，仅构造屏幕外独立 JFrame，不构造 AppConfig、不注册 Hook、不截图、不发送输入。覆盖重复公开刷新及合并调度不额外 validate/整栏 repaint、有效但尺寸错误的根面板/内容容器修复、无改宽时真实布局失效修复、原生改宽后容器与按钮快照同步，以及 WM_EXITSIZEMOVE 的最终修复/重绘、最终标记不污染普通刷新。去重检查直接进入自定义调度，原 AWT 对原生消息的合法布局不计为冗余；原消息链继续由原生改宽和标题栏契约检查保护。

最终命令 `mvn '-Dtest=RecordingTitleBarRefreshTest,RecordingControlBarLayoutTest,RecordingBarGeometryTest,LegacyRegressionTest#recordingBarNativeResize+recordingTitleBarContract+recordingBarPlacement' test`：13 项通过；`mvn test`：40 项通过；失败/错误/跳过均为 0、退出码 0。`mvn compile` 退出码 0。日志分别为 `target/resize-refresh-directed-final.log`、`target/resize-refresh-full-test.log`、`target/resize-refresh-compile.log`。首轮定向 13 项中 2 项因夹具混入原 AWT 合法布局/错误假定父容器有效状态而失败；第二轮夹具诊断 4 项中 1 项失败；修正测试隔离后得到上述最终结果，原日志 `target/resize-refresh-directed.log`、`target/resize-refresh-fixture-diagnosis.log` 保留。没有关闭动态布局或放宽尺寸/刷新要求。

2026-10-20 指定图像分支跳转新增11项标准检查：`ImageJumpPlaybackTest` 5项使用计数Robot、合成匹配结果和新建的工作区隔离MR目录，检查成功跳过中间动作、失败回跳后继续、不同/相同目标、保存重载与重建保留引用、重试/默认/终止、删除/缺失/自身引用与无出口闭环在输入及匹配前拒绝。`ImageJumpEditorTest` 2项只构造不显示的编辑器，模拟模板选择、独立/相同目标、编辑回显、删除目标提示和取消不写回。`TimelineJumpLayoutTest` 4项独立计算并断言相邻/远距离双向侧边水平入箭头、独立轨道/端口、标签避开竖线、插入/更改目标/删除后重布局、与重试和默认主链共存；绘制仅在内存进行，不是真实屏幕截图。显式 `-Dmacro.jump.preview=true` 可生成自建场景绘制诊断图至 `target/image-jump-preview/timeline.png`，不作为真实桌面验收。

旧 `ImageCheckBehaviorTest` 和专项 `ImageCheckEditorTest` 原有九种组合显式保留 RETRY/EXECUTE/TERMINATE 三种旧行为集合，全部旧断言保留；新JUMP依赖有效目标，由上述专门检查覆盖，不把缺少目标的JUMP伪造为第四种旧组合。未跳过既有检查或降低零测试保护。

最终定向命令 `mvn '-Dmacro.jump.preview=true' '-Dtest=ImageJumpPlaybackTest,TimelineJumpLayoutTest,ImageJumpEditorTest,LegacyRegressionTest#imageCheckBehaviors+flowPreflightValidation+timelineHeadlessEditing+timelinePlaybackProtection' test`：15项全部通过，失败/错误/跳过0、退出码0，日志 `target/image-jump-directed-verified.log`。`mvn test`：51项全部通过，失败/错误/跳过0、退出码0，日志 `target/image-jump-full-test.log`；`mvn compile` 退出码0，日志 `target/image-jump-compile-final.log`。首轮定向11项中1项因系统虚拟临时目录原子移动被拒绝而报错，原日志 `target/image-jump-directed-initial.log` 保留；改用新建工作区隔离目录，不修改生产存储的原子写入语义。新增共存绘图和标签避障前的14/15项通过日志也是中间记录，最终以上述 verified 为准。功能与人工验收边界见 `docs/image-branch-jumps.md`。

## 2026-10-21 当前增量回归记录

本节是后续工作区变更的实际记录；上文 22/40/51 等数量保留其当时含义，不覆盖历史结论。本次文档同步没有再次运行下列命令。

| 范围 | 标准检查 | 当前数量与内容 |
| --- | --- | --- |
| 目标菜单禁用样式 | DarkComboBoxStyleTest、ImageJumpEditorTest | 样式 2 项，编辑器共 3 项；覆盖默认/自定义渲染器、FlatLaf、浅色禁用默认值覆盖、恢复启用、两种行为独立切换及保留选择 |
| 编号与远距离路径 | TimelineNumberingTest、TimelineJumpLayoutTest | 编号 3 项，路径共 5 项；卡片 158×176、所有原有子组件/连接器几何不变，插入/删除/重排后编号更新且 ID 稳定；两端同高同长、端口位于顺序线下方/重试端口上方、圆角、多线及标签避障 |
| 保存前结构校验 | MacroStorageSaveValidationTest | 6 项；成功/失败目标删除后拒绝覆盖、原文件可重载且无临时文件、不创建目标目录、有效前跳/回跳、非空缺失模板路径、null flow 与旧保存重载 |
| 侧键入口保护 | SideMouseButtonSafetyTest、SideMouseButtonEditorTest | 3+2 项；编辑菜单只含左中右、不静默替换旧侧键；动作列表/flow 的保存加载拒绝，播放前零输入拒绝；正常三键及消息框左键拖动保留 |

`RecordingMouseCoordinatesTest` 的既有隔离入口还扩展了合成 BUTTON4/BUTTON5 事件：检查侧键不进入动作、不改时间戳、不污染控制栏过滤，不影响移动、暂停/恢复/停止及普通按键配对。没有注册真实 Hook 或发送输入。`MacroStorageFailureTest` 改用序列化读取坐标时抛错的动作，避免新增保存前遍历让原夹具提前失败，继续覆盖临时文件写入失败后的原文件保护及清理。

实际执行记录（最终各次均失败 0、错误 0、跳过 0，退出码 0）：

| 范围 | 定向 / 完整 | 日志前缀（均在 target/ 下） |
| --- | --- | --- |
| 禁用样式与独立状态 | 5 项；该轮未另跑完整测试 | combo-style-directed.log |
| 编号与连线美化 | 21 / 58 项，compile 成功 | timeline-ui-directed.log、timeline-ui-full-test.log、timeline-ui-compile.log |
| 端口位置微调 | 8 / 58 项，compile 成功 | timeline-port-position-test.log、timeline-port-full-test.log、timeline-port-compile.log |
| 保存校验 | 14 / 64 项，compile 成功 | storage-validation-directed.log、storage-validation-full-test.log、storage-validation-compile.log |
| 侧键保护（最新） | 15 / 69 项，compile 成功 | side-buttons-directed-final.log、side-buttons-full-test.log、side-buttons-compile.log |

关键定向命令及最新完整命令：

```powershell
mvn '-Dtest=DarkComboBoxStyleTest,ImageJumpEditorTest' test
mvn '-Dtest=TimelineNumberingTest,TimelineJumpLayoutTest,ImageJumpEditorTest,ImageJumpPlaybackTest,DarkComboBoxStyleTest,LegacyRegressionTest#timelineHeadlessEditing+timelinePlaybackProtection+imageCheckBehaviors' '-Dmacro.jump.preview=true' test
mvn '-Dtest=TimelineJumpLayoutTest,TimelineNumberingTest' '-Dmacro.jump.preview=true' test
mvn '-Dtest=MacroStorageSaveValidationTest,ImageJumpPlaybackTest,LegacyRegressionTest#macroStorageFailure+templatePathResolution+imageCheckBehaviors' test
mvn '-Dtest=SideMouseButtonSafetyTest,SideMouseButtonEditorTest,MacroStorageSaveValidationTest,LegacyRegressionTest#recordingMouseCoordinates+recordingPause+macroStorageFailure+flowPreflightValidation' test
mvn test
mvn compile
```

最新完整测试于 2026-10-21 11:57:42 完成；compile 于 11:58:07 完成，报告生产类已是最新。无需 mvn clean，不删除现有报告或诊断图。Surefire 中的旧 XML 不能累加当作本次执行数量，应读取本次构建汇总。

失败记录保留：`combo-style-before.log` 中 2 项预期失败复现白底；`storage-validation-before.log` 中 6 项有 2 项预期失败复现非法保存；首轮侧键定向 15 项中 1 项因消息框测试夹具的枚举类名错误失败，修正后得到上述最终 15 项通过结果，原日志 `side-buttons-directed.log` 保留。未通过跳过或降低生产检查换取通过。

存储新测试使用 target/test-sandboxes 下新目录，不构造 AppConfig，不访问个人 MR/模板。UI 使用不显示的编辑器/消息框和合成事件；播放用计数 Robot。真实 Windows 11 / 150% DPI 的硬件侧键、可见窗口交互、线条辨识度、连续原生缩放及截图合成仍需人工验收。

## 最初 16 个独立测试的代码盘点（历史基线）

所有现有类入口均为 `public static void main(String[] args)`，检查使用显式 `AssertionError`，部分还在 EDT/工作线程执行。以下判断根据实际调用及文件操作，不仅依据类名。

| 现有测试 | 实际依赖/副作用 | 默认自动运行 |
| --- | --- | --- |
| RecordingControlInputFilterTest | 注入时钟、合成 NativeInputEvent、矩形快照及配对检查；不注册 Hook、不移动鼠标、不写文件 | 是；子 JVM 强制 headless |
| RecordingPauseTest | 构造 AppConfig 和 MacroRecorder；Windows DPI/当前指针/计时查询；直接调用合成事件，暂停/F12/采集回调；不调用正常 start 注册 Hook | 是，仅 Windows；应用配置及默认目录隔离，强制 headless |
| MacroStorageFailureTest | 临时 MR、模拟序列化失败、原文件保全及临时文件检查；原主程序会留下自己的测试目录 | 是；系统临时目录指向隔离 tmp |
| NumericInputUtilTest | EDT 上的 JTextField/JSpinner、文档过滤、合成焦点、局部 StringSelection 导入；不访问系统剪贴板或顶层窗口 | 是；强制 headless |
| FileDialogServiceTest | 平台工厂、不可变 options、文件名/扩展名字符串；不创建目录、不调用 Windows 面板 Show | 是；强制 headless |
| WindowsNativeFileDialogContractTest | STA/COM 创建并释放真实 IFileDialog，读取配置、过滤器、中文路径；临时目录；不 Show | 是，仅 Windows；强制 headless，临时目录在隔离 target |
| ImageCheckBehaviorTest | 图像结果用模拟 matcher；临时 MR 保存重载、重试/终止/暂停；MacroPlayer 与 CountingRobot 的构造需要桌面；动作检查由计数 Robot 覆盖，不发送真实输入、不截屏 | 是，仅 Windows 且非 headless；无环境时通过 JUnit 明确 skipped，不用成功返回冒充通过 |
| TimelineEditingTest | EDT 上节点插入、行内提交、重试模型；原 main 在有桌面时还会显示 JFrame 检查空白点击/隐藏 | 是，但子 JVM 强制 headless，故仅无窗口部分；原测试自行打印未运行的桌面分支 |
| SettingsPreferencesTest | 明确要求 settings-preferences-tests/classes 的 CodeSource；会写/重载配置、创建 SettingsDialog 和程序化点击 | 否；桌面集成检查，原有目录保护保持不变 |
| ImageCheckEditorTest | 创建 ActionEditorDialog、模拟文件面板、临时 PNG、程序化编辑及布局 PNG 输出，统一 dispose 窗口 | 否；需要图形环境，保留人工/专项入口 |
| NumericEditorIntegrationTest | 创建动作/设置 Dialog、文件读写、MR 保存重载、合成焦点及提示窗口守卫；部分配置检查依赖工作目录已有 config 文件，构造 AppConfig 后才反射重定向 | 否；避免在默认自动测试中读取或初始化个人配置，也不把有条件未执行的部分算通过 |
| HomeRecordingControlsUiTest | 固定隔离 CodeSource 防护、真实 MacroFrame 布局/Win32 区域、程序化按钮；mock 文件面板，不注册 Hook；最后 System.exit | 否；需要 Windows 桌面及固定夹具目录，保留专项入口 |
| RecordingControlBarUiTest | 真实窗口及 DWM 系统消息、最小化/最大化、真实屏幕截图、截图流程与 PNG 保存；System.exit | 否；有屏幕和窗口副作用，不因名称含 Test 自动启动 main |
| RecordingControlBarLiveTest | MacroFrame、真实 NativeHook 监听与人工输入、周期写状态、等待用户操作，超时后 System.exit | 否；人工夹具，不能无人值守批量运行 |
| ImageRecognitionDiagnosticsTest | 顶置 JFrame、实际全屏捕获、OpenCV、诊断图片/日志与系统属性临时修改；会清理自身生成文件 | 否；涉及真实屏幕内容，保留专项人工运行 |
| NativeFileDialogSmokeTest | 显示 JFrame 和多个原生文件面板，等待用户选择，创建自己的 MR/PNG 夹具；仅选择路径不执行被选文件保存 | 否；依赖真实桌面和人工选择 |

不自动运行的 8 个 main 没有被删除，也没有标记成已经验收。需要运行时先逐项准备其代码要求的生产类副本、工作目录和可交互桌面，不要直接把全部类名交给批量 main 执行器。

## classpath、资源与报告

- classpath 来自 Surefire 已解析的测试依赖，不要求提前 package，也不依赖可能陈旧的 `target/lib/*`。
- 编译后的生产类及正式资源一起复制，Windows 中文目录和带空格路径交给 ProcessBuilder 参数列表处理，不拼接 shell 命令。
- 默认运行不会设置诊断截图开关、打开文件面板或扫描图像素材目录。测试只读取/写入自己的临时 MR、配置和模拟图片数据。
- 标准 XML/文本结果位于 `target/surefire-reports/`；子进程日志位于对应的 `target/test-sandboxes/`。
- Surefire 不必自动删除旧报告。之前 16 个零测试套件文件可能仍在该目录，当前构建汇总以及新的 LegacyRegressionTest/LegacyMainRunnerTest 报告才代表本次执行结果；本轮没有清理历史审查产物。
- 这项接入提供回归运行基础，不意味着现有检查覆盖全部业务，也不把组件级合成事件或 mock 输入当成真实桌面验收。

依赖与测试生命周期设计参考 [JUnit 5 文档](https://docs.junit.org/5.10.5/user-guide/) 和 [Surefire 的 JUnit Platform 支持](https://maven.apache.org/surefire-archives/surefire-3.1.2/maven-surefire-plugin/examples/junit-platform.html)。
