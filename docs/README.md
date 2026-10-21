# Macro Recorder 当前状态与文档索引

同步日期：2026-10-21，按 1.0.0 正式版收尾更新。当前范围与本次重新验证结果见 [正式版记录](release-1.0.0.md)；未重新扫描 Qodana 或进行真实桌面验收。

## 项目基线

- 工作目录：`..`；当前分支：`main`。
- 收尾前基准提交：`8c3483ccebb8c88b4683403a9ea81d02ed7a7443`，标题 `fix: improve Macro Recorder reliability and test coverage`；正式版收尾提交以 `git log -1` 为准。本次不推送、不创建标签，不把本地提交视为远程同步证明。
- Maven 构建与应用展示版本均为 `1.0.0`；Java 17、Swing/FlatLaf、Maven、JNA、AWT Robot 和 OpenCV；主要环境为 Windows 11、150% DPI。
- 收尾范围纳入此前已完成但尚未提交的操作栏、图像跳转、时间线、保存/侧键保护及对应测试和文档，不将已在基准提交中的修复重复算作新增成果。
- `img.png` 和 `src (2).zip` 当前均为 `AD`：暂存记录保留，磁盘文件缺失。它们不属于正式版提交，不恢复、不清除、不重新暂存。

## 当前实现

| 范围 | 当前行为 | 主要记录 |
| --- | --- | --- |
| 紧凑录制操作栏 | 同一 MacroFrame 的 B 分组双行布局、自绘最小化、目标信息省略和 DWM 外框；录制/暂停置顶并恢复原状态；固定高度、水平缩放、独立位置与宽度记忆 | [操作栏](recording-control-bar.md) |
| 原生缩放刷新 | WM_SIZING 约束后继续转发原 AWT 过程；动态布局开启；自定义布局/重绘去重，拖动结束保留最终刷新 | [操作栏](recording-control-bar.md) |
| 录制截图 | 专用路径临时遮蔽并立即隐藏窗口，后台同步与原有冻结框选；原生准备失败时保留保守等待；确认、取消及异常恢复原状态 | [操作栏](recording-control-bar.md) |
| 图像分支 | 成功/失败独立设置重试、默认后继、终止或 JUMP；只有对应 JUMP 才启用目标菜单，禁用时使用深色主题 | [图像分支](image-branch-jumps.md) |
| 时间线 | 真实节点显示动态 01/02 编号，不改变卡片布局；远距离线两端同高，位于顺序线下方、重试端口上方，水平进入目标侧边 | [图像分支](image-branch-jumps.md) |
| MR 保存 | 非空 flow 在目录/临时文件创建和正式替换之前复用加载的结构校验；失效引用拒绝，不自动重定向；非空缺失模板路径及 null flow 旧入口兼容 | [图像分支](image-branch-jumps.md)、[测试](automated-tests.md) |
| 鼠标侧键 | X1/X2 不提供编辑选项；录制回调在状态更新前忽略；侧键按下/释放动作在保存、加载及播放前均拒绝；枚举仅保留识别旧数据，左/中/右键保留 | [测试](automated-tests.md) |
| 数值与文件面板 | 沿用既有数值校验、FileDialogService 与 Windows 原生面板，无 Swing 文件面板回退 | [数值输入](numeric-input-rules.md)、[文件面板](native-file-dialogs.md) |

MR 格式版本、键鼠 ActionType、图像识别算法和播放器整体架构没有因这些 UI 与保护性修复重新设计。原审查问题的编号及逐项修复记录仍保留在历史审查报告中。

## 最近实际验证

以下是本次正式版收尾前的上一轮结果，保留执行时间与日志；新的正式版验证以 [收尾记录](release-1.0.0.md) 为准。

2026-10-21 11:57:42 完成的 `mvn test`：**69 项，失败 0、错误 0、跳过 0，退出码 0**。同日 11:58:07 完成的 `mvn compile` 退出码 0；生产源码已在此前定向测试中重新编译，此次 compile 报告已是最新。

对应日志为 `target/side-buttons-full-test.log`、`target/side-buttons-compile.log`；最新侧键定向测试为 15 项全部通过，日志 `target/side-buttons-directed-final.log`。完整命令和逐轮记录见 [自动测试文档](automated-tests.md)。日志是本地忽略的诊断产物，不混入提交。

计数 Robot、合成原生事件、隐藏/屏幕外窗口及离屏绘制，不等同于真实桌面验收。仍需 Windows 11、150% DPI 人工确认连续拖边的流畅度、置顶与目标应用焦点、最小化/停止恢复、截图残影与框选取消、多线辨识度及真实侧键操作。以前已验收的 DWM 方案保留原结论，但不能替代新增行为验收。

## 文档用途

- [自动测试](automated-tests.md)：当前测试数量、运行方式、隔离规则和逐轮历史结果。
- [1.0.0 收尾记录](release-1.0.0.md)：提交范围、版本一致性、本次回归和未纳入的个人内容。
- [图像分支与时间线](image-branch-jumps.md)：跳转语义、编号、连线、保存保护及人工清单。
- [录制操作栏](recording-control-bar.md)：当前概述，以及按日期保留的设计、调查、测量和验收记录。
- [数字输入](numeric-input-rules.md)、[原生文件面板](native-file-dialogs.md)：各专项规则和验证边界。
- [2026-10-19 审查报告](code-quality-review-2026-10-19.md)：原始结论、问题编号及后续逐项修复；其中基准提交、环境和代码规模属于审查时快照。
- [2026-10-19 Qodana 附录](qodana-findings-2026-10-19.md)：当次 37 条扫描结果及人工复核；后续没有重扫，不能解释为当前扫描结果或全部问题清零。

个人 `save/`、`ScreenCapture/`、`config.properties` 及生成的 `dist/`、各级 `target/`、日志、诊断图不混入提交。本次不清理个人文件或历史诊断产物。
