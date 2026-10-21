# Macro Recorder 1.0.0 正式版收尾记录

日期：2026-10-21；收尾前基准提交为 `8c3483ccebb8c88b4683403a9ea81d02ed7a7443`，本地分支 `main`。这是本地源码收尾与提交记录，不包含推送、标签或 Windows 安装包发布。

## 版本与范围

Maven 项目版本由工作区中的 `1.2.0-beta` 统一为 `1.0.0`；`ApplicationInfo.APP_VERSION` 原本就是 `1.0.0`，本轮无需修改。MR 格式版本、字段及原子替换机制不变，历史审查文档中的版本属于当时快照，不作批量替换。

本次纳入此前完成但尚未提交的以下相关工作；不将基准提交已包含的 TEST-01、RP/FS/RN 修复重新宣称为本次新增：

| 范围 | 实际变更及主要文件 |
| --- | --- |
| 紧凑录制栏 | RecordingControlBar、MacroFrame、WindowsRecordingTitleBar；双行、自绘最小化、目标信息、置顶恢复、横向缩放/定高、原 AWT 消息转发及刷新去重 |
| 独立几何记忆 | AppConfig、RecordingBarGeometry；X/Y/宽度独立保存与屏幕范围修正，不改主页几何 |
| 录制截图 | ScreenCaptureController、WindowsCaptureSupport；临时遮蔽、后台同步及原有冻结框选/恢复，不阻塞 EDT，不等待不必要的隐藏过渡 |
| 图像跳转与时间线 | ImageCheckBehavior、MacroFlowFactory、ActionEditorDialog、ActionTimelinePanel、TimelineBranchLayout、TimelineNodeNumbers、MacroPlayer；独立目标、ID 保持、预检、徽标编号和水平入卡连线 |
| 保存完整性 | MacroStorage；非空 flow 写入前复用加载校验，拒绝失效引用，不创建临时文件或覆盖旧 MR；非空缺失模板路径及 null flow 旧入口兼容 |
| 侧键与深色控件 | MouseButton、MacroRecorder、MacroStorage、MacroPlayer、编辑器/时间线、AppMessageDialog、ComponentStyleUtil；X1/X2 入口隐藏和边界拒绝、左键拖动、目标菜单深色禁用样式 |
| 回归及文档 | 对应 JUnit/隔离 main 检查、显式截图诊断夹具、README 与 docs；手工诊断夹具不自动执行，真实桌面结果不以自动测试替代 |

没有修改图像匹配算法或重构播放器架构，没有把个人配置、MR、模板、截图、日志、各级 target 或 dist 纳入提交。

## 本次验证

版本已统一并核对 Maven 与应用常量均为 `1.0.0`。在该版本配置下重新执行以下检查；没有跳过测试或运行 mvn clean：

| 检查 | 实际结果 | 退出码 | 本地日志 |
| --- | --- | --- | --- |
| 正式版定向回归，2026-10-21 12:12:41 完成 | 51 项，失败 0、错误 0、跳过 0 | 0 | target/release-1.0.0-directed.log |
| 完整 mvn test，12:13:56 完成 | 69 项，失败 0、错误 0、跳过 0 | 0 | target/release-1.0.0-full-test.log |
| mvn compile，12:14:10 完成 | 构建成功，生产类已是最新 | 0 | target/release-1.0.0-compile.log |

实际命令：

```powershell
mvn '-Dtest=ImageJumpPlaybackTest,ImageJumpEditorTest,TimelineJumpLayoutTest,TimelineNumberingTest,DarkComboBoxStyleTest,MacroStorageSaveValidationTest,SideMouseButtonSafetyTest,SideMouseButtonEditorTest,RecordingControlBarLayoutTest,RecordingBarGeometryTest,RecordingTitleBarRefreshTest,ScreenCaptureControllerTest,LegacyRegressionTest#recordingMouseCoordinates+recordingPause+macroStorageFailure+flowPreflightValidation+recordingBarNativeResize+recordingTitleBarContract+recordingBarPlacement' test
mvn test
mvn compile
```

定向覆盖图像跳转、旧流程预检、编号/连线、目标菜单禁用样式、保存文件保护、侧键、录制暂停、窗口几何/置顶/原 AWT 过程与刷新、截图取消及异常恢复。输入由计数 Robot 或合成事件替代，个人配置和宏使用隔离目录；显式桌面验收/耗时夹具没有自动执行。

审查清单共 51 个项目文件：19 个生产 Java、23 个测试/显式诊断 Java、pom.xml、根 README 及 7 个功能/验证文档。它们既包括本轮版本与正式版记录调整，也包括此前已实现但尚未提交的上述功能；未把基准提交已有成果重复纳入。

## Git 保护与提交方式

- 逐项审查既有暂存、未暂存及未跟踪文件；按明确文件清单暂存和提交，不使用 `git add .` / `git add -A`。
- `img.png` 和 `src (2).zip` 的两项 AD 暂存记录不纳入提交，磁盘缺失状态不恢复、不删除或重新暂存。
- 中文标题使用“完成 Macro Recorder 1.0.0 正式版收尾”；只有验证成功且清单明确后才创建本地提交。不推送，不打版本标签。
- 不执行 mvn clean 或 Git reset/clean/stash 等操作，不清理已有诊断产物。

## 验收边界

代码收尾和自动回归不代表所有真实桌面场景已验收。Windows 11、150% DPI 下的连续拖边流畅度、焦点/置顶、截图残影与取消恢复、多分支连线辨识度、真实鼠标侧键及跨屏变化仍按对应功能文档人工复核。已有 DWM 方案的历史验收结论保留，不因本轮发版准备重置，也不扩展为新增功能的验收证明。

本次不运行 jpackage、不生成或覆盖 dist，不将已有 Windows 打包产物称为 1.0.0 新构建。源码与自动测试满足收尾要求后可完成本地提交，实际发行包发布及剩余桌面确认仍是独立步骤。
