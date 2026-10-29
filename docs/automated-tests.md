# Maven 自动回归测试

本文最初记录 TEST-01：让已有独立检查由 Maven 实际执行；该次接入未修改生产业务逻辑。后续新增覆盖、夹具调整和真实结果按轮次保留在下文，不能把历史测试数量当作当前总数。当前项目概况见 [文档索引](README.md)。

最近一次验证为 2026-10-28 的错误报告：新增 **14 项标准入口**，相关 **39 项**及完整 **382 项**全部通过，失败/错误/跳过均为 0；编译及运行模块分析通过。此前录制截图前释放鼠标、节点查找、多选、复制粘贴、菜单、撤销和其他轮次的数量按原轮次保留，尚未重新生成发行包。

## 2026-10-28：1.2.0 错误报告

新增统一报告写入器、设备信息采集器及未捕获异常处理器。统一错误弹窗先记录原异常，再沿用弹窗流程；状态栏中的 MR/配置及文字提取失败、已捕获的部分输入释放和窗口恢复错误也接入报告。普通提示、无异常对象的警告和正常取消不写报告；原本使用错误弹窗的消息仍记录并明确区分调用位置与真实异常堆栈。每次在应用根目录 `log/` 原子创建唯一命名的 UTF-8 文件，不覆盖旧报告；信息采集及日志失败不递归产生弹窗。

新增 **14 项标准入口**：

- `ErrorReportWriterTest` **8 项**：中文路径及内容、版本/时区/线程、原因链及 suppressed 异常、消息调用位置、同一毫秒 48 次并发及旧文件保全、设备接口缺失、日志目录被文件阻挡、目录访问失败后恢复、递归保护及配置/宏/截图数据保全。
- `UncaughtErrorHandlerTest` **4 项**：先落盘后通知、原线程及原处理器保留、日志/原处理器失败仍通知原错误、通知失败仅额外记录、处理器重入及后续错误恢复。
- `DeviceInformationTest` **1 项**：实际采集操作系统、Java、CPU、进程、内存及显示器字段，不整份输出环境变量或个人属性。
- `LegacyRegressionTest#errorReporting` **1 项隔离入口**：真实调用统一错误/警告入口，并实际触发后台线程和 EDT 的未捕获异常；检查默认应用根目录、单次报告、不重复安装、事件线程继续处理、无图形环境、日志目录写入失败及恢复，不显示窗口。旧配置安全检查保留文件/内存/临时文件断言，另检查新日志目录及详细报告。

相关定向共 **39 项，失败 0、错误 0、跳过 0，退出码 0**，20:35:19 完成，包含上述检查及配置读写、弹窗标题栏、单实例、截图控制器、识别测试页和 OCR 窗口回归。完整 `mvn test` 于 20:37:59 完成：**382 项，失败 0、错误 0、跳过 0，退出码 0**。日志在 `target/error-reporting-20261028/`：`directed-first.log`（新增 14 项首次通过）、`directed-verified.log`、`full-final.log`、`compile-first.log` 及 `compile-final.log`。运行模块分析 `runtime-modules.log` 确认新引用的 `jdk.management` 被打包脚本使用的静态分析识别，未新增外部依赖；`git diff --check` 通过。

Surefire 显式设置 `macro.errorReport.applicationDirectory`，常规检查触发的报告位于 `target/test-error-reports/log/`；独立 JVM 不继承该参数，在各自新建的类来源目录下验证默认 `log/` 位置。全部旧诊断产物保留，没有向项目根目录的个人日志中注入模拟错误。实际样本已核对 Windows 构建、CPU、内存，以及 2560×1600 / 150% 显示信息；内存为采样，不是原生分配追踪。未重建发行包，发行包内实际弹窗、连续异常体验及安装目录权限仍待人工验收。

## 2026-10-28：1.2.0 录制截图前释放鼠标

录制截图请求先保留原有宏内配对释放并挂起事件录制，主窗口在隐藏/冻结画面前调用按会话校验的准备方法。新 `CaptureMouseRelease` 仅检查当前物理左/右/中键的按下高位，发送一个只含所需 UP 标记的原生输入；无按下不发送，不移动、不发送 DOWN、键盘或侧键，也不重新按住。发送失败取消截图并提示；旧会话和重复准备不重复发送。过滤器持续忽略自动及随后重复释放，直到有效的新按下；挂起/控件内的旧输入不能解除保护，普通鼠标移动不被该标记持续阻止。原暂停、取消、时间补偿和普通采集页保留。

新增 **11 项标准入口**：

- `CaptureMouseReleaseTest` **10 项**：无按下与低位误报、三种按钮及对应 UP、组合输入及发送前过滤、真实 JNA INPUT 结构往返（不发送）、禁止移动/DOWN/键盘/侧键标记、发送失败/过滤失败、重复 UP、恢复后迟到的挂起按下、控件按下保护及新录制重置。状态和发送接口全部注入替身，过滤时间注入模拟时钟。
- `LegacyRegressionTest#recordingCaptureRelease` **1 项隔离入口**，运行 `RecordingCaptureReleaseTest` 内的 **8 个场景**：F10/按钮请求后释放先于模拟截图、宏内动作顺序及配对坐标、自动/真实重复释放和新点击、取消不重新按下、暂停时未录入的按下仍释放并保留暂停、控件按下、三键及原键盘规则、重复/旧会话/停止、失败取消及重试、普通暂停/无回调不发送。八场景按方法组织，不重复计入 JUnit 总数。生产类和 AppConfig 副本位于新建隔离目录，不读取个人配置、不注册 Hook、不截图、不发送真实输入。

相关定向 `CaptureMouseReleaseTest,ScreenCaptureControllerTest,LegacyRegressionTest#recordingCaptureRelease+recordingPause+recordingMouseCoordinates+recordingControlInputFilter`：**20 项，失败 0、错误 0、跳过 0，退出码 0**，20:11:05 完成。完整 `mvn test`：**368 项，失败 0、错误 0、跳过 0，退出码 0**，20:14:44 完成；独立 `mvn compile` 于 20:16:42 成功。日志在 `target/recording-capture-release-20261028/`：`directed-second.log`、`full-final.log`、`compile-final.log`；`git diff --check` 通过。

首轮定向 14 项中 1 项失败：新夹具所谓“控件外点击”仍使用旧控件区域内坐标，且发生在同一个 Windows 计时边界，触发既有保护。将该点击改为新旧区域都在外部的真实测试坐标，保留原配对及零污染断言，得到上述通过结果；保留 `directed-first.log`。独立单元首轮 10 项已通过，日志 `unit-first.log`。没有放宽区域/同毫秒过滤或跳过检查。

实际鼠标拖拽在 F10 前后是否结束、左右键交换、高权限目标、全局 Hook 投递和真实截图画面仍需 Windows 11 / 150% DPI 人工验收。此次没有操作用户真实输入或重建发行包。当前调用顺序、原生接口依据及人工检查见[录制控制栏](recording-control-bar.md#截图前释放鼠标)。

## 2026-10-28：1.2.0 节点查找

新增 `TimelineNodeSearch`，查找栏仅处理当前可见节点参数，不读取图片、不执行识别、不修改流程或选择。支持带 #/前导零/全角的精确编号、中文动作及英文类型、按键名称/左右侧、模板文件名，以及空格分隔的组合条件。单个英文字母按独立名称匹配，避免 A 命中 RELEASE/IMAGE 等类型中的字符。Ctrl+F 打开，Enter/F3 和 Shift+Enter/Shift+F3 循环前后切换；金色外框与原选择/播放标记共存，结果位置在布局后滚动，过时请求丢弃。隐藏时不查找，模型重建仅更新结果不抢滚动。

新增两类共 **18 项**：

- `TimelineNodeSearchTest` **7 项**：编号精确定位/范围/超长输入、空查询和内部 END 排除、全部普通动作、中文及英文类型、多个条件、鼠标按钮、按键本地化与常量别名/左右侧、单字母精确名称、模板文件名及字面特殊字符；父目录和隐藏 ID 不参与匹配。
- `TimelineNodeSearchUiTest` **11 项**：按钮及局部快捷键、前后循环与空结果、多选/历史/分支保全、播放只读与进度共存、关闭重开及换宏、插入/删除/移动/撤销/模板更名后刷新、文字输入框隔离、旧行内编辑及拖动取消、视口外结果完整进入视口、关闭/换宏丢弃排队滚动、金色外框与多选共存、820/1000 宽度及 150% 离屏预览。使用隐藏组件和独立 Clipboard，不读写系统剪贴板或个人文件，不发送输入。

实际结果：扩展单字母检查前相关定向 **70 项**全部通过，日志 `target/timeline-node-search-20261028/directed-final.log`；最终完整 `mvn test` **357 项，失败 0、错误 0、跳过 0，退出码 0**，19:23:32 完成，日志 `full-final.log`，其中新增检查为 7+11 项。独立编译于 19:25:08 通过，日志 `compile-final.log`；`git diff --check` 通过。Windows 11 / 150% DPI 的真实按键、焦点、滚动与连续操作仍需人工验收，未重新打包。

开发记录保留：`regression-first.log` 的 50 项有 1 项因旧测试从整个面板取首个文本框而误取隐藏查找框；将取值范围限定在时间线轨道，原待机延迟/撤销断言保留。`new-tests-first.log` 的 17 项有 12 项失败，修复首次查找时对 null 当前 ID 的不可变集合查询，并补齐与系统语言无关的按键别名。`new-tests-second.log` 的 30 项有 2 项夹具失败：重复添加 FOUND 分支导致检查读到旧目标、把卡片文字的彩色抗锯齿像素算作金色外框；分别改为修改原分支、仅统计外边框，继续断言目标不变及关闭后外框消失。没有通过跳过或放宽业务要求取得最终通过。

## 2026-10-28：1.2.0 多选与批量编辑

时间线以节点 ID 集合保存选择，并保留主选中节点及 Shift 锚点。Ctrl 点选、Shift 连选、Ctrl+Shift 追加区间、Ctrl+A 全选、Esc/空白取消；坐标区域的修饰键点击只选择，普通点击保留行内编辑。多选时显示数量和批量操作栏，右键宫格使用批量编辑/复制所选/删除所选；播放查看可多选和复制，修改均受编辑权限保护。

批量编辑只应用勾选字段，分别设置普通动作延迟与具有 RETRY 行为的图像节点重试间隔，使用既有非负 long 输入校验。非连续节点整组拖动后保持相对顺序，预览不写模型，影像有数量标记；提交保留 ID、指定跳转和动作延迟。批量删除不转移延迟，原失效指定引用保留给预检报告。整组复制使用剪贴板版本 2，内部 JUMP 映射到对应副本，外部目标缺失时整组拒绝；单节点版本 1 及原目标语义保持。每批操作只记一步撤销和一次通知，撤销恢复选择及锚点。

新增 `TimelineMultiSelectionTest` **20 项**，覆盖修饰键/坐标选择、空白与全选/取消、刷新/换宏、输入框和批量按钮焦点、混合节点批量修改、0/Long.MAX_VALUE/无效输入、无变化与旧对象零写入、播放权限、非连续首尾移动、真实组件拖动预览和取消、150% 离屏图、删除引用及全部节点、MR 保存重载、整组复制的内部/外部跳转和来源删除、缺失外部目标整组拒绝、旧菜单保护，以及实际 MacroPlayer 配合计数 Robot 检查移动后与撤销后的执行序列。剪贴板使用独立 Clipboard，不读写系统内容；不发送真实键鼠输入，不访问个人宏或配置。

本轮完整 `mvn test`：**339 项，失败 0、错误 0、跳过 0，退出码 0**，16:23:26 完成；之后补齐批量按钮获得焦点时的 Delete/Esc 及输入框保护，相关 **75 项**于 16:26:29 复测通过，失败/错误/跳过 0、退出码 0。`mvn compile` 退出码 0。日志目录 `target/timeline-multi-select-20261028/`：`full-final.log`、`directed-focus-verified.log`、`compile-final.log`，`git diff --check` 通过。

首轮相关 102 项中 1 项失败：新增的组合快捷键说明在 820 宽度下被裁切。拆成 Ctrl/Shift 两行后，布局专项及完整/最终相关回归通过；保留 `directed-final.log` 和 `layout-fixed.log`，没有放宽原布局断言。150% 离屏图已核对，路径由新增测试打印在日志中；真实 Windows 11 鼠标修饰键、连续拖动与焦点体验仍需人工验收。没有重新打包。

## 2026-10-28：1.2.0 节点复制／粘贴及 Ctrl+C／Ctrl+V

`TimelineNodeClipboard` 将单节点和来源 MR 目录保存为带版本的 UTF-8 快照，使用独立 InputStream 格式传输；另提供可读 String 摘要，保留普通文字粘贴用途。仅接受节点专用格式，普通文本/图片或文本形式的 JSON 不作为节点；读取和写入限制为 1 MiB，拒绝无效类型/内容，不解析 Java 对象序列化。复制不读取、复制或覆盖模板文件。

粘贴重新解析独立对象，经原插入入口分配新 ID，固定放在选中节点后，无锚点时追加；不使用“插入动作”的前/后默认方向。JUMP 保留目标 ID 及分支延迟，目标缺失或指向 END 拒绝；RETRY 接副本自身，EXECUTE 接新后继，TERMINATE 接本宏 END。同目录模板路径不改，跨目录转换为来源 MR 下的原图片绝对路径，缺失图片引用仍按原规则保留。一次粘贴对应一步撤销，并同步主窗口使用的流程；复制不占历史。只读查看可复制，粘贴复用编辑权限及迟到回调保护，Ctrl+C／Ctrl+V 不抢占文字输入框。

新增两类共 **20 项**：

- `TimelineNodeClipboardTest` **8 项**：全部六种普通动作、文字摘要、对象与分支独立性、图像/窗口配置、同目录与跨目录路径、缺失/空模板引用、普通文字/JSON 不误认、坏数据/格式版本/容量拒绝、无效动作及 END 拒绝、剪贴板忙碌处理。
- `TimelineNodeCopyPasteTest` **12 项**：新 ID 与插入位置、重复粘贴及源删除、空宏追加、完整图像配置/JUMP/RETRY 和 MR 往返、缺失目标零写入、跨宏同名图片路径及原文件保全、只读复制/禁止粘贴、剪贴板替换/坏内容、旧菜单/旧锚点保护、Ctrl+C／Ctrl+V 及输入框隔离、菜单粘贴/撤销，以及实际播放器配合计数 Robot 分别执行副本和撤销后的流程，不发送真实按键。

原生表示修正后的定向 `TimelineNodeClipboardTest,TimelineNodeCopyPasteTest,TimelineUndoTest`：**33 项，失败 0、错误 0、跳过 0，退出码 0**，15:50:27 完成，日志 `target/timeline-node-clipboard-20261028-153855/transport-tests.log`。最终完整 `mvn test`：**319 项，失败 0、错误 0、跳过 0，退出码 0**，15:53:37 完成，日志 `full-final.log`。更早相关 83 项也通过，属于原生格式修正前，见 `directed-final.log`；初次新增夹具的 var 复合声明编译错误修正后，33 项通过，前两轮日志也分别保留。独立编译及 `git diff --check` 通过。

仅独立 Clipboard 单测不能证明 Windows 原生格式能保留：初版文本格式被映射为普通文字，独立节点格式丢失。改用专用 UTF-8 InputStream 格式后，`NodeClipboardNativeProbe.java` 将独立剪贴板的实际 Transferable 经当前 Windows JDK 原生编解码往返，核对私有节点字节与 Unicode 中文摘要各自一致，且私有内容不经过对象序列化。最终日志 `native-probe-stream.log`；早期失败及诊断编译日志均保留。该临时探针使用的 JDK 内部格式接口只用于诊断，生产代码仅使用公开 AWT Clipboard API，不新增应用 JVM 参数。

`NodeClipboardPreview.java` 使用独立剪贴板和真实 JMenuItem 离屏检查四宫格、底部粘贴/撤销、禁用状态及无裁切，输出 `normal-100.png`、`normal-150.png`、`paste-hover-150.png`、`read-only-150.png`、`no-node-clipboard-150.png`、`empty-space-150.png`。已核对普通、只读及空白轨道 150% 图，日志 `preview.log`。本轮没有读写系统剪贴板、显示真实菜单、截图或发送键鼠输入；真实 Windows 桌面焦点和按键/系统剪贴板仍需人工验收。文档和快捷键设置同步，未打包、提交、推送或升版，既有数据与诊断保留。

## 2026-10-28：1.2.0 四宫格右键菜单

节点菜单使用直属 `JMenuItem` 配合两列布局：编辑/插入在上排，复制/删除在下排，底部整行撤销和 Ctrl+Z 提示；空白轨道仅显示插入和撤销两项。使用现有主题和自绘图标统一圆角、间距、文字、悬停、删除红色及禁用态，不改动作、确认和回调保护。图标按菜单项创建时缓存，不在每次重绘时建立新组件。

更新既有 `TimelinePlaybackProtectionTest`，按菜单文字与操作识别权限，避免依赖旧列表下标；播放查看仍允许复制，其余操作即使绕过禁用状态直接调用监听也不能修改流程。没有新增仅镜像外观的 JUnit 检查。

首轮 `TimelineUndoTest,TimelineUndoHistoryTest,TimelineNodeDragTest,LegacyRegressionTest`：**63 项**通过，15:23:37 完成。图标缓存调整后，最终 `TimelineUndoTest,TimelineNodeDragTest,LegacyRegressionTest#timelinePlaybackProtection+shortcutsSettings+modelessActionEditor`：**31 项，失败 0、错误 0、跳过 0，退出码 0**，15:25:01 完成并包含实际编译。日志分别为 `target/timeline-grid-menu-20261028-152059/directed-first.log` 和 `directed-final.log`；`git diff --check` 通过。本轮仅改菜单外观及对应既有夹具，没有重复完整回归。

同目录 `GridMenuPreview.java` 核对 2×2 几何、底部撤销跨度、直属 MenuElement 和只读权限，生成 100%/150% 普通、编辑悬停、删除悬停、禁用和空白轨道预览，日志 `preview-first.log`。已核对 `normal-150.png`、`delete-hover-150.png`、`read-only-150.png`、`empty-space-150.png`；全部为离屏绘制，未弹出真实菜单、不写系统剪贴板或发送键鼠输入。实际 Windows 桌面的弹出位置、边缘和键盘交互仍需人工复核。未打包、提交、推送或升版。

## 2026-10-28：1.2.0 Ctrl+Z 时间线撤销

`ActionTimelinePanel` 在确认插入、删除、替换、拖动、坐标/延迟/重试行内提交及待机开关时保存独立流程快照，撤销恢复实际节点 ID、顺序、选择、END、分支与数值，不重建或猜测跳转目标。快照不包含图片或个人文件，最多 100 条并以 32 MiB 序列化字节预算淘汰旧记录；单个超大宏仍保留最近一步。取消、非法输入和无实际变化的操作不入栈。仅加入撤销，不建立重做历史。

Ctrl+Z 绑定限定在时间线及其子控件，文字输入框不触发流程撤销；右键菜单提供同一入口。录制、播放、暂停与停止收尾均复用编辑权限保护，旧菜单/弹窗/行内会话不能迟到写回。撤销通过原回调同步主窗口播放/保存数据，关闭旧对象的编辑器。加载新宏、录制新流程及跨目录另存为清空历史；同目录保存保留历史，撤销不自动修改磁盘。跨目录保存成功后同时关闭旧路径基准下的编辑窗口；失败不提前清空历史或改变基准。

- 新增 `TimelineUndoHistoryTest` **3 项**：未变更不记录、深拷贝/重复恢复独立性、条数上限、字节预算及单个超大快照的最近一步保留、清空。
- 新增 `TimelineUndoTest` **13 项**：删除目标与转移延迟还原、跳转关系与 MR 往返、拖动后编号/路由恢复、插入/替换逐步撤销、完整图像配置及窗口描述隔离、坐标/普通延迟/重试/待机延迟、无效/取消/无变化过滤、待机偏好写入拒绝、运行状态禁用、旧回调失效、换宏清空与同图刷新保留、Ctrl+Z 输入框保护且无 Ctrl+Y、空时间线菜单撤销及过时菜单保护。
- 扩展 `ModelessActionEditorTest` 的既有隔离入口：恢复主窗口权威流程与动作、关闭过期编辑器、同目录保存保留历史、撤销不自动写盘、无图片时跨目录另存为也清空历史并关闭旧创建窗口、加载实际磁盘内容后清空。
- 扩展 `TimelineNodeDragTest` 的既有计数 Robot 播放检查：拖动后及撤销后分别运行真实播放器，确认原目标及原始默认后继恢复，不发送真实按键。快捷键设置检查同步 Ctrl+Z 文案和窄宽布局。

最终相关定向 `TimelineUndoTest,TimelineUndoHistoryTest,TimelineNodeDragTest,LegacyRegressionTest`：**63 项，失败 0、错误 0、跳过 0，退出码 0**，15:11:54 完成，日志 `target/timeline-undo-20261028-150310/directed-final.log`。完整 `mvn test`：**299 项，失败 0、错误 0、跳过 0，退出码 0**，15:16:32 完成，日志 `full-verified.log`，包含收尾补充的跨目录编辑窗口关闭断言。此前 47 项既有定向、16 项新增定向以及首轮完整 299 项均通过，日志独立保留。编译及 `git diff --check` 通过，见该目录的编译和差异检查日志。

`UndoPreview.java` 使用合成流程、隐藏面板和未弹出的右键菜单生成 `undo-menu-150.png`、`restored-timeline-150.png`，已核对“撤销 / Ctrl+Z”入口与恢复后的原目标指向；日志 `preview.log`。没有显示真实窗口、注册新全局监听或发送键鼠输入；真实 Windows 11、150% DPI 下焦点切换、按键和原生菜单交互仍需人工确认。未打包、提交、推送或升版，原未提交修改、个人数据和诊断产物保留。

## 2026-10-28：1.2.0 识别文字框外观

文字区复用主题圆角面板和深色滚动条，统一内边距、选择文字及选中文字颜色；无内容时使用独立居中标签，有内容时保留左上排版、选择和滚动。清除、换图、提取中及空结果都切回“无信息”，不改变 OCR 或匹配逻辑。

更新既有异步结果检查，验证无文字显示居中标签、有文字隐藏标签、清除后恢复空状态；未新增仅镜像外观的测试。定向 `ImageRecognitionFrameTest,ImageRecognitionTestOptionsTest,RecognitionWindowVisibilityTest`：**10 项，失败 0、错误 0、跳过 0，退出码 0**，10:59:41 完成并包含实际编译。日志 `target/recognition-text-style-20261028-105912/directed.log`；`git diff --check` 通过。

同目录 `TextBoxPreview.java` 使用合成文字与隐藏窗口，检查空状态切换并生成 `empty-150.png`、`text-150.png`，已核对居中、圆角边框和长文字滚动条；日志 `preview.log`。不显示真实窗口、不调用 OCR、不截图或写剪贴板。未重跑完整测试，未打包、提交或推送，版本保持 1.2.0。

## 2026-10-28：1.2.0 识别测试页与模板文字

窗口最小尺寸固定为 1080×640 逻辑像素，仍可拉大；移除正文顶部标题/说明，保留识别范围。右侧文字区后台读取当前模板，默认五种内置语言，无文字显示“无信息”，有结果可复制；提取失败单独提示并允许重试。隐藏预览准备不启动 OCR，换图/清除/关闭通过独立停止信号丢弃旧结果；同一窗口只保留一个在途原生任务和最新待处理模板，不将过早的 Future 取消回调当作原生调用已结束。屏幕匹配不等待模板文字提取。

阈值右侧新增缩放开关与独立上下限：下限 1%～100%，上限 100%～400%，最多三位小数，默认精确 2/3～1.5。严格格式化器同时覆盖 Enter、箭头提交及识别前的文本校验，默认显示舍入不改变实际默认比例。新增 `TemplateScaleRange` 按本次测试请求传递给现有匹配器和内存估算，原入口仍使用默认值；原尺寸优先、最多十个粗查/四个细查、多目标合并和取消保持原语义。测试修改不写回动作或 MR，不改变播放器范围。

- 新增 `TemplateScaleRangeTest` **4 项**：非法范围拒绝、默认候选顺序不变、范围内有限候选及端点、真实 OpenCV 合成画面中的 50%/200% 命中与范围外排除、原尺寸仍优先。
- 新增 `ImageRecognitionTestOptionsTest` **6 项**：最小尺寸和范围保留、控件忙碌状态、独立小数/完整文本校验、隐藏预览不自动提取、空结果/重试/复制启用、快速换图只处理最新模板、清除与关闭丢弃迟到结果、失败提示与恢复。使用可控文字引擎，不截图、不写剪贴板。

定向 `ImageRecognitionTestOptionsTest,TemplateScaleRangeTest,ImageRecognitionFrameTest,RecognitionWindowVisibilityTest,ScaleMatchingTest,ScaleMatchingParallelTest,ScreenRecognitionServiceTest,RecognitionSessionTest,ScaleMatchingEditorTest,WindowRecognitionEditorTest,OcrServiceTest`：首轮 **47 项**通过，10:45:30 完成。随后加强缩放格式化器，完整 `mvn test`：**283 项，失败 0、错误 0、跳过 0，退出码 0**，10:48:14 完成。最后仅加宽缩放输入框避免六位显示截断，再次定向 **47 项全部通过**，10:51:29 完成。日志位于 `target/recognition-test-20261028-103904/`，依次为 `directed-first.log`、`full-first.log`、`directed-final.log`；独立编译也通过，见 `compile-first.log`、`compile-final.log`。`git diff --check` 通过。

任务目录内 `RecognitionTestPreview.java` 通过真实 OCR 引擎与五种语言从合成模板读出“测试文字 OCR 123”，随后检查按钮在最小窗口内可用、清除无旧文字、缩小请求受最小尺寸约束。最终日志 `preview-default-theme.log`、图片 `final-template-text-150.png` 和 `final-empty-150.png` 沿用实际启动入口的默认 Swing 外观及自绘组件，离屏绘制并核对；临时 FlatLaf 探针也保留，但不作为应用真实启动外观。首次预览夹具误把隐藏滚动条的内部按钮视为可见控件，诊断失败日志保留为 `preview-first.log`；修正夹具后通过，未为此修改生产代码。OCR 缓存、合成模板、源码和输出均留在 D 盘任务目录。

未显示真实窗口、采集桌面、发送键鼠输入或写系统剪贴板；实际 Windows 11、150% DPI 下拖动窗口边框、焦点/输入法和复制仍需人工验收。未打包、提交、推送或升版，旧产物与个人数据保留。

## 2026-10-27：1.2.0 图像编辑字段按需显示

生产修改仅在 `ActionEditorDialog`：图像成功/失败选择跳转才显示对应目标，成功点击才显示点击方式，失败点击才显示 X/Y，任一行为重试才显示重试延迟，指定窗口范围才显示窗口按钮。隐藏时控件与标签一起收起，不移除控件或清空当前编辑值；整排隐藏后收拢后项，保留成功/失败及 X/Y 的左右配对，窗口继续固定 580。普通动作表单沿用原配对位置。

不使用重试时，隐藏延迟框的临时空值/非法文本不阻止保存，沿用此前合法值；切回重试后仍校验非负 long。切换回普通动作重置共用延迟框的可见/启用状态。没有修改播放器、存储或 MR 格式。更新既有编辑器检查的可见性与布局断言，未新增只镜像界面的测试类或测试数量。

`mvn '-Dtest=ImageJumpEditorTest,ImageClickEditorTest,ScaleMatchingEditorTest,WindowRecognitionEditorTest,DarkComboBoxStyleTest,LegacyRegressionTest' test`：**46 项，失败 0、错误 0、跳过 0，退出码 0**，17:51:18 完成，日志 `target/editor-conditional-20261027-174345/directed-final.log`。覆盖编辑器保存/取消、目标身份、点击坐标、窗口预览、数值、普通动作、非模态和播放编辑保护；首轮同 46 项也通过，配对布局微调后复测。`mvn compile` 退出 0，17:53:08 完成，日志 `compile-final.log`；`git diff --check` 通过。

任务目录的 `ConditionalEditorPreview.java` 使用合成模板/窗口描述和实际 Swing 组件，核对 **6×6×2 = 72 种成功/失败/范围组合**的字段及标签可见性、边界和不重叠；另验证切换后目标、点击方式、失败坐标保留，隐藏重试框沿用合法 250ms、重新显示空值被拒绝、源 333ms 配置未污染，以及切回普通动作恢复可见且可用的 0ms 延迟。诊断不计入 JUnit 项目数，日志 `preview-final.log`。

最终离屏图保存在该目录的 `final/`，包括默认、成功跳转、双方点击、指定窗口点击及普通动作；已核对默认、双方点击和指定窗口点击的 150% 图。初始线性重排图片保留，最终改为整排收拢、左右配对，避免拆开 X/Y 或错列成功/失败字段。窗口保持不可见，不枚举外部目标、不截图、不发送输入；真实桌面焦点、Tab 顺序与菜单展开仍需人工确认。未打包、提交、推送或升版，现有产物、未提交修改和个人数据保留。

## 2026-10-27：1.2.0 失败点击与图像连线标注

根据用户确认，成功和失败行为提供同一组选项，JUMP 固定最后。成功点击仍使用本次命中范围及中心/随机选点；失败点击新增独立 `failureClickX/failureClickY` 屏幕逻辑坐标，默认空、0 和负值有效，不使用上次命中或窗口相对坐标。失败点击时必须两项完整，保存/加载/播放预检校验；普通失败行为保持原语义，识别异常不转为失败点击。播放共用原来的暂停/停止、完整左键点击与最终输入释放；失败点击后执行也预检后继，避免先点击再发现缺失分支。MR 格式不变，路径另存为保留两项坐标。编辑器增加两项控件，高度从 540 调整为 580。

连线标注仅保留在图像出向及进入图像节点的连线上，普通动作之间恢复只显示延迟与箭头。进入图像的真实分支等待与下方重试回路时间分开，未改动 delay 语义。

在既有三类测试中新增 **9 项**，并更新旧的成功/失败菜单差异断言：

- `ImageClickPlaybackTest` 从 11 项增为 **17 项**：新增失败固定坐标与后项间隔、失败点击终止无限循环、成功/失败坐标隔离及旧命中不复用、缺失任一坐标/后继在此前输入前拒绝、不同阶段停止与释放、暂停后恢复及按下/释放异常清理；使用计数 Robot，无真实键鼠输入。
- `ImageClickStorageTest` 从 3 项增为 **5 项**：新增两种失败点击的 MR 往返与另存为、0/负坐标、非点击时保留配置、部分缺失拒绝且既有文件不变；旧宏无坐标继续兼容。
- `ImageClickEditorTest` 从 3 项增为 **4 项**：补充菜单相同且跳转置末、坐标与成功点击方式独立启用、保存/回显/取消隔离、空值/负号/int 溢出拒绝、0 与 int 最小值可用、不启用的无效坐标不阻止保存、150% 新控件边界。`ImageClickModeTest` 保持 4 项，更新两种结果菜单一致检查。

最终定向：`mvn '-Dtest=ImageClickModeTest,ImageClickEditorTest,ImageClickStorageTest,ImageClickPlaybackTest,ImageJumpPlaybackTest,WindowRecognitionPlaybackTest,ScaleMatchingStorageTest,TimelineNodeDragTest,TimelineJumpLayoutTest,WindowRecognitionEditorTest' '-Dmacro.image.click.preview=true' '-Dmacro.image.click.previewDirectory=target/failure-image-click-20261027-165541' test`，**62 项，失败 0、错误 0、跳过 0，退出码 0**，17:04:53 完成，日志 `target/failure-image-click-20261027-165541/directed-new.log`。

完整 `mvn test`：**273 项，失败 0、错误 0、跳过 0，退出码 0**，17:07:35 完成，日志 `full-final.log`。`mvn compile` 退出 0，17:09:52 完成，日志 `compile-final.log`；`git diff --check` 通过。图像标注收窄单独定向 31 项的日志保留在 `target/timeline-image-labels-20261027-165137/`，此后失败点击首轮定向 39 项也通过，均不替代最终 62 项/273 项。

已核对任务目录 `click-editor-150.png` 与 `connector-150.png`：前者显示启用的失败 X/Y，后者普通动作连线只有 50ms、图像相关仍有成功/执行/未找到条件；由合成节点离屏绘制，未显示窗口、激活目标或发送真实输入。真实 Windows 11、150% DPI 的固定屏幕坐标、跨屏、窗口移动、暂停/停止及目标应用响应仍需人工验收。源码未打包、提交或推送，版本仍为 1.2.0，既有诊断及个人数据保留。

## 2026-10-27：1.2.0 连线行为与延迟显示

普通顺序连接器上方补充“执行”，进入图像节点的“检查”占位改为真实入向分支等待，当前工厂生成的分支为 `0 ms`。重试间隔仍显示在图像下方回路；图像默认分支保留成功/失败条件。旧流程非零等待或两个结果不同等待时也按实际值显示，悬浮提示区分条件。没有新增图像进入延迟配置、修改播放器或改写 MR。

`mvn '-Dtest=TimelineNodeDragTest,TimelineJumpLayoutTest,TimelineNumberingTest,TimelineTemplateWarningTest,StandbyHeadTimelineTest,TimelinePlaybackProgressTest,ImageClickEditorTest' test`：**39 项，失败 0、错误 0、跳过 0，退出码 0**，16:41:14 完成，日志 `target/timeline-connector-20261027-163932/directed.log`；该命令包含实际编译，`git diff --check` 通过。未新增只检查文案的测试，复用原有拖动、连线路径/端口、编号和编辑回归。

用任务目录内 `ConnectorPreview.java` 构造与反馈截图对应的 5 个合成节点，不显示窗口或发送输入，离屏绘制 `connector-150.png` 并实际核对。连接器文本依次为“识别成功 / 50 ms”“执行 / 50 ms”“执行 / 0 ms”“未找到 / 20 ms”，两处重试回路均保持 100ms；临时源码、输出图及 `preview.log` 均保留。真实桌面效果仍需人工确认，未重打包、提交或推送。

## 2026-10-27：1.2.0 时间线拖动排序

普通动作和图像卡片支持左键拖动，保留按下偏移的半透明虚化影像、插入标记与占位预览，边缘自动横向滚动。预览只改变组件排列，不修改流程；松手后按最终位置移动原节点对象，保留 ID、动作延迟、指定跳转目标、头部等待和 END 身份，重接普通默认后继，刷新编号和连线。预览的前跳/回跳文字也随临时位置更新。MR 格式不变，主窗口和已打开的节点编辑器沿用已有流程变更回调同步。

新增 `TimelineNodeDragTest` **15 项**，使用组件事件、合成流程和工作区临时文件，覆盖：

- 第 5 项移至第 7 项后，成功/失败及多个图像来源的引用仍指向原目标，显示编号及箭头端点一起更新；移动图像源节点保留出向跳转、重试、点击参数及默认后继。
- 首尾移动、动作延迟、头部待机、END、MR 保存重载；实际播放器配合计数 Robot 验证只执行原跳转目标，跳过原位置现在的节点，不发送真实键鼠输入。
- 预览不写模型、释放仅提交一次、小范围移动仍是选择、双击保留；子标签起拖、坐标编辑不触发拖动，真实组件事件分发在卡片重新排列后仍可完成释放。
- Esc、轨道外释放、窗口失去激活、播放锁定、外部权限变化、重建/换宏、移除均取消；监听移除、定时器停止、影像释放；非法位置和原位不通知修改。
- 头部固定、重复移动不抖动、左右边缘滚到视口之外及首尾，150% 离屏拖动前/中/后绘制与预览连线方向。

最终定向命令：`mvn '-Dtest=TimelineNodeDragTest,TimelineNumberingTest,TimelineJumpLayoutTest,TimelineTemplateWarningTest,ImageJumpEditorTest,ImageJumpPlaybackTest,ImageClickPlaybackTest,MacroStorageSaveValidationTest' test`，**51 项，失败 0、错误 0、跳过 0，退出码 0**，16:30:06 完成；日志 `target/timeline-drag-20261027-161406/directed-final.log`。之前包含 `LegacyRegressionTest` 的首轮定向 **80 项**也通过，日志 `directed-first.log`；当时新增拖动检查为 12 项，此后补到 15 项，不混算总数。

最终完整 `mvn test`：**264 项，失败 0、错误 0、跳过 0，退出码 0**，16:32:49 完成，日志 `target/timeline-drag-20261027-161406/full-verified.log`；包含预览方向文字的最后修正。此前 16:28:28 的完整 264 项也通过，日志 `full-final.log`。本轮未修改单实例模块，不将通过结果视为修复此前偶发 Windows 1400。

`mvn compile` **退出码 0**，16:32:48 完成，日志 `compile-final.log`。`git diff --check` 通过。开发中的命令参数和测试夹具修正日志按原路径保留，没有清理旧诊断产物。

已查看 `target/test-sandboxes/timeline-drag-*/timeline-before-150.png`、`timeline-drag-150.png` 与 `timeline-after-150.png` 的离屏绘制结果，最终预览方向修正后的拖动图也已核对。新增测试不显示或激活窗口，不使用真实桌面截图、真实键鼠或个人 MR。真实 Windows 11、150% DPI 的鼠标拖动手感、跨显示器/缩放、滚动及取消仍需人工确认。未重新打包、提交或推送，版本仍为 1.2.0。

## 2026-10-27：1.2.0 窗口内识别

图像动作新增 `ImageSearchScope.DESKTOP/WINDOW` 与独立窗口描述，默认/缺省/null 仍为桌面。窗口模式持久化进程名、标题和窗口类，防御性复制，保存与跨目录另存为保留；不写入 HWND、PID 或坐标。无效配置在保存、加载及播放预检拒绝，预检不定位窗口或截图。

实际执行到节点才唯一定位窗口，先匹配进程和窗口类，再优先唯一标题；标题变化时只接受唯一候选，不使用最大窗口猜测。同一次播放绑定运行时身份，关闭后不自动改绑其他窗口，开始下一次播放重新定位。窗口需在前台且未最小化，识别不激活、不恢复目标；不可用、无法唯一定位或捕获/匹配前后发现窗口位置/大小变化时按运行错误处理，不回退桌面，也不走普通未找到分支。有效窗口内没有目标图像时仍执行原失败行为。

窗口模式的局扫、扩大局扫和竞速完整搜索均限制在当前客户区，窗口移动/调整大小时重读范围并使旧位置提示按范围变化失效；桌面录制提示不混入窗口模式。跨屏按每屏 DPI 分别捕获可见部分，不拼接屏幕接缝。Robot 对原点和尺寸分别舍入，捕获前进一步向内约束，避免局扫起点变化造成越界；结果换算的逻辑点击框也限制在截图内，修正右/下边缘可能多出一个像素的问题。原竞速取消与后台收尾继续保留，不增加跨识别等待或修改 delay。未运行新的性能基准，不据此承诺提速比例。

编辑器增加识别范围及窗口按钮，保存/加载/取消和未保存预览隔离；图像编辑器高度由 500 调整为 540。预览展示范围并按同一配置识别，窗口模式保持本程序隐藏直至匹配完成，避免恢复界面抢走前台；桌面预览保持原恢复时机。预览不发送输入。使用规则见 [窗口内识别](window-recognition.md)。

新增 4 类共 **23 项**：

- `WindowRecognitionTest` **14 项**：真实 OpenCV 配合假窗口/合成画面，覆盖窗口外同图排除、窗口内未命中不扫桌面、窗口移动/调整大小、局扫与竞速边界、近整窗与缩放模板、标题优先/变化/多候选、同次播放绑定与再次播放重绑、窗口不可用、采集中/匹配中移动拒绝、预检与取消不扫描、模式切换缓存隔离、负坐标跨屏不同 DPI、1,200 组舍入范围，以及靠边和极小像素结果的逻辑点击框不越界。
- `WindowRecognitionStorageTest` **4 项**：窗口描述往返、字段集合无运行时标识、旧/null 范围默认桌面、独立副本、另存为保全、非法配置拒绝及既有文件保护。
- `WindowRecognitionEditorTest` **3 项**：默认桌面、范围切换、未选窗口拒绝、保存回显、未保存预览与源节点隔离、长标题和 150% 离屏布局，兼容缩放及随机点击参数。
- `WindowRecognitionPlaybackTest` **2 项**：无效范围在任何输入前拒绝、每次播放重置一次窗口绑定、窗口错误不点击/不执行下一动作并恢复 IDLE。使用计数 Robot，不发送实际输入。

最终定向命令：`mvn '-Dtest=WindowRecognitionTest,WindowRecognitionStorageTest,WindowRecognitionEditorTest,WindowRecognitionPlaybackTest,AdaptiveImageSearchTest,ScreenRegionCaptureTest,ScaleMatchingTest,ScaleMatchingParallelTest,ScreenSearchRaceTest,RecognitionSessionTest,ScaleMatchingStorageTest,ImageClickPlaybackTest,ScaleMatchingEditorTest,ImageClickEditorTest,CaptureWindowDialogTest' test`，**84 项，失败 0、错误 0、跳过 0，退出码 0**，15:48:07 完成；日志 `target/window-recognition-20261027-151948/directed-bounds-final.log`。

最终完整 `mvn test`：**249 项，失败 0、错误 0、跳过 0，退出码 0**，15:49:33 完成，日志 `full-bounds-final.log`。前一轮完整 248 项曾在既有 `WindowsSingleInstanceTest.crashedProcessLeavesNoStaleLock` 出现 Windows 1400，日志 `full-final.log`；未修改该模块，单独复测 5 项通过，随后 248 项完整复测也通过，日志分别为 `single-instance-recheck.log` 和 `full-verified.log`。最后补充 DPI 点击边界检查后再验证 249 项；不将复测通过视为已修复该偶发问题。

已核对 `target/test-sandboxes/window-editor-*/window-editor-150.png` 和任务目录 `window-preview-150.png`；后者由独立离屏绘制程序生成，只有合成模板和假窗口描述，不截图真实桌面或枚举个人窗口。所有测试临时文件位于新建的 `target/test-sandboxes/` 目录，当前源码均未提交、推送或重新打包，版本仍为 1.2.0。

仍需 Windows 11、150% DPI 实际确认窗口定位/前台要求、置顶遮挡、窗口移动/缩放、跨屏、贴边模板、预览隐藏/恢复、点击位置及停止恢复。非整数 DPI 向内取整可能少量裁边，贴边或整窗模板建议留边；后台窗口识别、跨屏模板拼接和相对窗口坐标的命中提示推移未实现。

## 2026-10-27：1.2.0 指定窗口截图

采集页新增独立窗口列表、当前目标提示与“使用屏幕”切换。选择不激活目标，点击截图才隐藏本程序可见窗口、尝试恢复/前置所选目标，再进入冻结框选；客户区高亮，窗口外不能开始，拖出边界自动截断，不限制真实鼠标。框选与裁剪继续复用原流程，成功、取消、失败均恢复本程序，失效或无法前置目标不回退到其他窗口，截图前后客户区变化拒绝交付。本轮不改 MR 格式、录制目标、F10、播放搜索或 delay，也没有新增跨识别等待。

Win32 查询临时切到 Per-Monitor V2 线程 DPI 上下文，再原样恢复；物理客户区按目标屏幕和实际 Robot 高分辨率像素换算并向内取整，处理非整数 DPI 边缘舍入。沿用单屏冻结框选，跨屏窗口只选可见客户区面积最大的屏幕部分，界面明确提示，未实现跨屏拼接或后台窗口渲染。使用说明见 [指定窗口截图](window-capture.md)。

新增 6 类共 **18 项**：

- `WindowCaptureGeometryTest` 3 项：150% 缩放、负坐标、窗口部分在屏幕外、无交集、非法尺寸和 1,000 组合成范围向内取整边界。
- `WindowSelectionOverlayTest` 3 项：窗口外不能开始、越界拖动截断、已有选框保留、双向拖动、小选区拒绝、150% 合成像素裁剪及取消只回调一次。
- `CaptureWindowServiceTest` 3 项：真实 Win32 查询不显示的自建窗口客户区，成功/失败后线程 DPI 上下文恢复，真实显示器原点/物理尺寸，非法目标在激活前被拒绝；不枚举或激活个人窗口。
- `WindowCaptureControllerTest` 5 项：隐藏所有可见应用窗口后才请求前置、激活失败恢复、目标屏幕/客户区限制传递、捕获后窗口移动拒绝、目标不可用不截图、取消后晚返回不显示框选层。使用假目标与合成捕获源；原生目标激活被替身接管。
- `CaptureWindowDialogTest` 3 项：后台加载、不自动激活或接受选择、明确确认、刷新失败清除旧候选及恢复、关闭后丢弃晚返回列表和 150% 离屏布局。
- `WindowCapturePageTest` 1 项：独立 JVM 下验证默认屏幕、指定目标回调、切回屏幕、与 OCR/预览并存、目标不进入个人配置及 150% 采集页布局。AppConfig 只写该轮新建隔离应用目录，不访问项目个人配置。

定向命令：`mvn '-Dtest=WindowCaptureGeometryTest,WindowSelectionOverlayTest,CaptureWindowServiceTest,WindowCaptureControllerTest,CaptureWindowDialogTest,WindowCapturePageTest,ScreenCaptureControllerTest' test`，包括原有截图控制器 6 项，最终 **24 项，失败 0、错误 0、跳过 0，退出码 0**，01:07:10 完成；日志 `target/window-capture-20261027-010000/directed-verified.log`。

完整 `mvn test`：**226 项，失败 0、错误 0、跳过 0，退出码 0**，01:10:11 完成，日志同目录 `full-final.log`。未出现既有单实例的 Windows 1400 偶发错误，不视为该问题已被修复。开发过程中的 Win32 接口适配、64 位 DPI 伪句柄常量和测试捕获源比例舍入问题均已修正；中间编译/测试日志原样保留，未覆盖最终结果或清理旧诊断产物。

最终 `mvn compile` 退出码 0，01:12:03 完成，日志 `compile-final.log`；`git diff --check` 退出码 0。

已人工查看离屏预览 `target/test-sandboxes/Probe-*/classes/capture-page-150.png` 与 `target/test-sandboxes/window-picker-*/picker-150.png`；对应源码用合成图片和假窗口标题绘制，没有个人窗口截图。仍需 Windows 11、150% DPI 实际确认列表、最小化恢复/前置、框选内容边界、Esc/右键、目标关闭/移动、置顶遮挡、不同 DPI 跨屏和恢复原界面。原生截图内部仍不能立即中断，但取消后的过时回调不被接收。

版本保持 1.2.0，未重新打包、提交、推送或创建标签；所有既有数据与输出目录保留。

## 2026-10-26：1.2.0 离线 OCR 与扫描竞速

OCR 只提取当前图片文字，通过已有 JNA 直接调用 Windows x64 的 Tesseract 5.3.4 / Leptonica 1.84.1。五种语言模型与本机库随程序提供，运行时离线；简繁中文、英文、日文和韩文均做了真实原生提取检查。默认选简体中文、英文，支持单行模式、后台提取、协作取消、可编辑结果、复制和 UTF-8 TXT 保存。打开弹窗不自动提取，关闭和取消丢弃过时结果；原生退出前不重复开始，全程序最多一个 OCR 引擎。资源 SHA-256 校验后在程序目录展开，隔离测试与本轮下载均位于 D 盘；损坏缓存保留且不覆盖。使用入口与许可见 [离线 OCR](offline-ocr.md)。

播放搜索改为先局扫，失败后竞速扩大局扫、全扫，先命中的阶段返回；先未命中仍等待另一方，两边未命中保留全扫候选。较慢原生任务协作停止并后台释放，不阻塞获胜结果返回。全程序最多两个范围竞速任务，前次未收尾时回退串行，避免积压；扩大任务比例串行，全扫保持原有大图双比例能力。一次识别仅启动一份进程内存采样，后台原生任务持有引用，最后退出后才关闭。报告数值为生成报告时的采样快照，仍不能当成精确原生分配或最终收尾峰值。

新增 26 项：`OcrServiceTest` 8 项（真实五种语言提取、组合语言、空白/透明图、原图保全、输入校验、取消/中断、引擎忙碌及释放、原生进度回调取消后可恢复）；`OcrResourcesTest` 4 项（清单校验、缓存复用与损坏保全、默认目录、UTF-8 保存）；`OcrTextDialogTest` 5 项（无自动执行、后台线程、结果编辑、取消/关闭后不回写、等待真实退出、文件面板保存）；`ScreenSearchRaceTest` 6 项（两种获胜顺序、先未命中、失败处理、配额上限、停止与中断、后台资源收尾）；`RecognitionSessionTest` 3 项（多阶段单采样、局扫命中和引用幂等释放）。既有 14 项 `AdaptiveImageSearchTest` 保留缓存、区域、跨屏/DPI、停止及不点击旧坐标的断言，合成捕获源改为线程安全，并允许竞争任务因取消而少截取一次。

实际结果：

- 完整 `mvn test`：**208 项，失败 0、错误 0、跳过 0，退出码 0**，22:04:52 完成，日志 `target/ocr-race-20261026-211718/full-final.log`。
- 最终定向 `mvn '-Dtest=OcrServiceTest,OcrResourcesTest,OcrTextDialogTest,ScreenSearchRaceTest,RecognitionSessionTest,AdaptiveImageSearchTest,ScaleMatchingTest,ScaleMatchingParallelTest,ScreenRegionCaptureTest,ImageClickPlaybackTest,ScreenCaptureControllerTest' test`：**74 项，失败 0、错误 0、跳过 0，退出码 0**，22:13:37 完成，日志同目录 `directed-final.log`。完整回归后的微调为缩进、缓存错误提示及清除上次错误悬浮提示，最终定向已覆盖。
- `mvn compile`：退出码 0，22:13:39 完成，日志 `compile-final.log`。
- 扩充后的 `PackagingSmokeTest` 在当前完整 JDK 17 与当前编译类/依赖上 **9 项通过**，包括实际加载五种 OCR 语言并提取合成文字；隔离缓存/日志位于 `smoke-current-jdk/`。这不是发行包自带精简 Java 的验收，本轮未重新打包。

初次原生联调的中文路径编码失败及随后新版本 DLL 的内存访问错误均保留日志；最终改用系统 Windows 代码页传递模型路径和上述已验证的兼容组件组合，原生测试通过。未宣称修复上游 DLL 或既有单实例偶发 Windows 1400 问题。诊断文件 `ocr-initial.log`、`ocr-path-fix.log`、`ocr-compatible-runtime.log` 和 `incompatible-native/` 保留，不清理或混入正式资源。

已核对 150% 离屏 OCR 弹窗：语言行、可编辑结果、状态和四按钮可见；预览在 `target/test-sandboxes/ocr-ui-*/ocr-dialog-150.png`。测试不截真实桌面、不发送输入、不访问真实剪贴板或文件面板，仍需 Windows 11、150% DPI 人工验收真实截图文字、复杂字体、剪贴板、保存、播放竞速、多目标选择、停止恢复及连续播放的资源占用。

### 新旧策略重复测量

保留本轮修改前的编译类作为串行对照，同一驱动分别运行两版，每版 **5 个独立 JVM**；每轮每场景预热 5 次再计时 20 次，每策略/场景 **100 个样本**，共 **1,400 个计时样本**。实现顺序逐轮交替、场景顺序轮换，不删异常值、不强制 GC。Java 17.0.14、16 个逻辑处理器、256 MiB 初始/1 GiB 最大堆，1280×720 合成画面，60×48 模板（缩放目标为 72×58），阈值 0.98。

下表为响应耗时均值 ± 样本标准差，单位 ms。计时结束后另等后台资源收尾，避免更换画面影响前一次任务；因此是隔离请求的响应测量，不是连续播放吞吐。包含正式截图复制和识别，不包含真实桌面捕获、首次原生加载、点击与动作等待。

| 场景 | 原串行平均 ± SD | 当前竞速平均 ± SD | 竞速 P95 |
|---|---:|---:|---:|
| 原尺寸局扫命中 | 1.63 ± 0.80 | 1.72 ± 0.97 | 3.39 |
| 缩放局扫命中 | 8.65 ± 3.12 | 8.49 ± 2.69 | 13.79 |
| 扩大局扫命中 | 5.73 ± 1.46 | 5.85 ± 1.86 | 9.60 |
| 原尺寸远距离，全扫命中 | 53.56 ± 3.35 | 55.85 ± 18.45 | 110.87 |
| 原尺寸未出现 | 59.40 ± 17.39 | 59.42 ± 20.90 | 105.30 |
| 缩放远距离，全扫命中 | 440.16 ± 141.18 | 420.59 ± 142.60 | 704.57 |
| 缩放未出现 | 366.95 ± 110.57 | 343.35 ± 100.73 | 617.92 |

没有稳定的全面加速证据。竞速第 4 轮和串行第 5 轮有明显整轮耗时上升，原因未确定，全部保留；不能仅用均值宣称缩放回退改善已可靠复现。扩大局扫命中后，竞速后台平均还需 **6.29 ms** 收尾，也会增加捕获和瞬时计算。竞速解决的是同时启动两条回退路径、先命中返回，不保证所有场景更快或捕捉到短暂状态。

完整报告、全部原始 CSV、逐轮均值、响应及收尾耗时、捕获次数/像素位于 `target/ocr-race-20261026-211718/benchmark-measured/`；驱动与汇总脚本在同级 `benchmark/`。首轮驱动曾因相对模板路径重复拼接失败，原日志保留，修正为绝对路径后另建 `benchmark-measured/` 完成正式测量，不混合失败数据。OCR 资源体积测量 ZIP 保留在任务目录；它们不是应用发行包。版本仍为 1.2.0，未提交或推送。

## 2026-10-26：1.2.0 局扫、扩大局扫与全扫

正式播放器在执行到对应 IMAGE_CHECK 节点时，按内存提示依次局扫、扩大局扫、全扫。录制确认的选区经截图控制器传递屏幕逻辑矩形，PNG 保存且录制节点提交成功后登记初始提示；加载 MR 和开始播放时的资源预检不截屏。没有提示的节点首次执行全扫，命中后按实际逻辑矩形更新；新录制或成功加载 MR 时清空。位置不进入 ImageCheckConfig 或 MR，两个元数据缓存各最多 128 项，不持有屏幕像素或原生矩阵。

局扫每侧边距为 max(32, ceil(对应边长 × 0.25))，扩大阶段为 max(96, ceil(对应边长 × 0.75))，均从原命中框计算。捕获源只接收各显示器与搜索区域的交集，并保留高分辨率图像和逻辑坐标映射。每阶段重新捕获，扩大后完整检查旧区域和新增区域；空范围、相同范围或已经等于全屏的局部阶段会跳过，避免重复工作。阶段内仍先原尺寸、后可选缩放；首个命中阶段取最高相似度，测试窗口保持全屏多结果且不读写播放位置缓存。

节点 ID、规范化模板路径、文件修改时间/大小、阈值及缩放开关组成位置键，显示器身份、布局与 DPI 变化也使提示失效。未命中返回无坐标，不把旧位置当成当前命中；旧提示仍可用于下一轮重试。每次识别仍检查外部模板，文件移走时报错。阶段之间及每次捕获前后检查停止，中断保留标志；单次截图和 OpenCV 原生调用内部仍不能强制中断。诊断增加 LOCAL/EXPANDED/GLOBAL、搜索区域及包含采集和各阶段的总耗时，显式开启诊断截图时各阶段分开保存。

新增 `AdaptiveImageSearchTest` 14 项、`ScreenRegionCaptureTest` 4 项，并在 `ScreenCaptureControllerTest` 增加 1 项。覆盖首次全扫、录制提示、逐节点独立缓存、小幅移动、扩大后命中、远距离/跨屏回退、刷新旧区域、未命中无旧坐标、参数/模板/布局/DPI 失效、缓存容量、整屏覆盖去重、停止/中断、加载/预检不扫描、MR 内容不受缓存影响、文件移走、150% DPI 和负坐标，以及关闭框选层前传出逻辑选区。使用真实 OpenCV 和合成捕获源；播放器测试替换输入 Robot，选区测试不显示窗口，不采集真实桌面或发送输入。

- 定向：`mvn '-Dtest=AdaptiveImageSearchTest,ScreenRegionCaptureTest,ScreenCaptureControllerTest,ScreenRecognitionServiceTest,ScaleMatchingTest,ScaleMatchingParallelTest,ImageClickPlaybackTest' test`，**52 项，失败 0、错误 0、跳过 0，退出码 0**，19:59:58 完成，日志 `directed-final.log`。
- 完整：`mvn '-Dsurefire.timeout=180' test`，**182 项，失败 0、错误 0、跳过 0，退出码 0**，20:01:47 完成，日志 `full-test.log`。
- 编译：`mvn compile`，退出码 0，20:01:35 完成，日志 `compile.log`；源码注释核对后的最终编译也退出 0，20:08:50 完成，日志 `compile-final.log`。中途测试夹具构造参数及未布局画布的断言问题已修正，原始日志 `directed-initial.log`、`directed-second.log` 保留；`directed-third.log` 为补齐最终两个测试前的 50 项通过记录。

同机合成测量使用 1280×720 图像、60×48 模板、阈值 0.98，每组预热 3 次、测 7 次。对照路径每次清空位置缓存后强制全扫；新路径在计时前设置相应位置提示，两者均经过正式匹配器、文件元数据检查、截图复制和结果转换。捕获源只从合成图像取区域，**不包含真实桌面截图开销**，不作为实际应用延迟保证。中位耗时如下：

| 场景 | 强制全扫 | 位置优先 |
|---|---|---|
| 原尺寸局扫命中 | 56.12 ms | 2.20 ms |
| 1.2 倍目标局扫命中，开启缩放 | 337.56 ms | 7.36 ms |
| 偏移后扩大局扫命中 | 49.99 ms | 5.11 ms |
| 远距离移动，最终全扫命中 | 50.92 ms | 55.35 ms |
| 目标未出现，最终全扫未命中 | 50.92 ms | 55.41 ms |

原尺寸局扫采集 13,888 像素，对照全扫 921,600；扩大后命中累计采集 74,368 像素；回退全扫累计 995,968 像素、共三次捕获，失败阶段有额外开销。程序、最终测量及全部本轮日志位于 `target/local-search-20261026-195506/`，分别为 `LocalSearchBenchmark.java`、`benchmark-final.log`；初次测量夹具的相对路径错误记录在 `benchmark.log`，不用于性能结论。

### 同日重复测量：5 个独立进程、每项 100 个样本

用户要求增加次数并计算平均值后，另外启动 5 个独立 JVM 依次测量。每个进程、每种场景预热 5 对、计时 20 对，每对分别执行强制全扫和位置优先，AB/BA 各 10 对；5 轮轮换场景顺序。保持相同合成输入（1280×720、60×48 模板、阈值 0.98、固定随机种子 581），缩放目标 72×58。每种场景、每种策略合计 **100 个有效样本**，共 **1000 个计时样本**，另有 250 次预热调用。Java 17.0.14、JVM 可见处理器 16 个；各轮环境记录于日志。

全部调用逐次检查命中状态、位置、捕获次数与像素数；五轮均通过。均值直接由原始纳秒计算，未剔除慢样本或强制 GC；标准差为样本标准差（n−1），P95 取升序第 ceil(0.95n) 项。下表单位 ms，数值四舍五入至两位；全部中位数、最小/最大值及各轮均值另存于完整报告。

| 场景 | 强制全扫平均 ± SD | 位置优先平均 ± SD | 位置优先 P95 |
|---|---|---|---|
| 原尺寸局扫命中 | 49.14 ± 3.33 | 1.58 ± 0.37 | 2.39 |
| 缩放目标局扫命中 | 329.99 ± 8.64 | 7.01 ± 0.52 | 8.10 |
| 扩大局扫命中 | 47.63 ± 1.00 | 4.78 ± 0.51 | 5.54 |
| 远距离移动，全扫命中 | 48.11 ± 0.95 | 52.59 ± 1.15 | 54.51 |
| 目标未出现，全扫未命中 | 48.03 ± 1.00 | 52.46 ± 1.39 | 54.26 |

5 轮原尺寸局扫的各轮均值为 1.298～2.070 ms，全扫对照为 47.500～52.759 ms；扩大命中为 4.187～5.306 ms。远距离回退、目标未出现分别平均多 4.480/4.423 ms，约增加 9.3%/9.2%，证实局扫失败后的额外开销。该结果和此前 7 样本中位数是不同轮次，不混合计算。

本轮依旧使用合成捕获源，包含正式截图复制和识别路径，位置准备在计时前完成；**不包含真实桌面捕获、首次引擎加载、点击或动作等待**，不能据此宣称真实 Windows 11 / 150% DPI 的端到端延迟。仅新增测量驱动和文档，5 个识别相关生产源码文件的 SHA-256 前后相同，未重跑 Maven 功能回归或重新打包。

全部文件位于 `target/local-search-repeat-20261026-201407/`：`report.md` 为完整报告；`run-01.csv`～`run-05.csv` 保留全部原始样本和顺序，`run-01.log`～`run-05.log` 保留环境、校验与 GC 数据；`summary.csv`、`summary.json`、`per-run-summary.csv`、`summary.log` 为汇总；`RepeatedLocalSearchBenchmark.java` 和 `Summarize-Benchmark.ps1` 为驱动和统计脚本。此前测量与测试产物均保留。

版本仍为 1.2.0；未提交、推送或重新生成发行包。真实 Windows 11、150% DPI 的录制坐标、目标移动/跨屏、局部截图耗时、缩放、多目标选择以及暂停/停止仍需人工验收。

## 2026-10-26：1.2.0 识别后点击行为

成功行为新增 CLICK_EXECUTE（点击后执行）和 CLICK_TERMINATE（点击后终止），左键单击本次最佳命中的逻辑坐标区域后，分别继续后继或结束整个任务（包括无限循环）。点击方式 CENTER/RANDOM 保存到 MR，缺省或 null 为中心点击，另存为转换路径时保留。行为列表统一稳定排序，JUMP 无论在枚举何处声明都位于最后；失败没有可靠的命中区域，菜单不提供点击行为，存储及播放在任何输入前拒绝非法失败点击配置。

点击前检查暂停/停止，移动后再次检查，按下后先释放再响应暂停/停止；异常沿用最终输入释放。使用当前结果而不是上次匹配区域，支持负坐标及缩放后的实际命中范围。点击后完整保留后续动作间隔；测试识别窗口不发送点击。流程重建、必需出口校验、默认连线、终止标记和跳转闭环检查均保留相应执行/终止语义。

新增 4 类共 21 项：`ImageClickModeTest` 4 项检查中心/随机坐标、负坐标、1×1 范围、无效范围和菜单排序；`ImageClickPlaybackTest` 11 项用计数 Robot 检查完整单击顺序、原延迟、无限循环终止、随机范围、不点击旧结果、重试至命中才点击、匹配后及移动后停止、按下后停止仍释放、暂停后继续、按下/释放失败清理、非法配置/缺少出口预检及无效命中范围；`ImageClickStorageTest` 3 项检查行为/点击方式往返、旧/null 字段、另存为保全、非法配置加载/保存拒绝及原文件保护；`ImageClickEditorTest` 3 项检查跳转固定最后、成功点击启用选点、失败菜单排除点击、独立跳转状态、保存回显/取消保全、控件布局和主链/终止语义。测试只写新建 `target/test-sandboxes/click-*` 合成 MR/图片，不发送真实输入、截图或显示窗口。

既有 `ImageJumpEditorTest` 的失败组合及 `TimelineEditingTest` 的失败重试组合改按合法失败选项枚举，成功组合仍包含新行为，原断言全部保留；旧九种识别分支行为检查未放宽。新增检查首轮 20 项通过，补充“失败重试后只点击一次”后，最终定向命令为：`mvn '-Dtest=ImageClickModeTest,ImageClickPlaybackTest,ImageClickStorageTest,ImageClickEditorTest,ImageJumpPlaybackTest,ImageJumpEditorTest,TimelineJumpLayoutTest,ScaleMatchingStorageTest,LegacyRegressionTest#imageCheckBehaviors+flowPreflightValidation+timelineHeadlessEditing' '-Dmacro.image.click.preview=true' '-Dmacro.image.click.previewDirectory=target/image-click-behaviors-20261026/preview-final' test`，**39 项，失败 0、错误 0、跳过 0，退出码 0**，16:47:46 完成。

完整执行 `mvn '-Dsurefire.timeout=180' test`：**163 项，失败 0、错误 0、跳过 0，退出码 0**，16:49:49 完成；`mvn compile` 退出码 0，16:51:50 完成。日志为同目录 `full-test.log`、`compile.log`；`git diff --check` 退出码 0。本轮未出现测试失败。

日志和预览位于 `target/image-click-behaviors-20261026/`：`existing-directed.log` 为既有 18 项，`new-tests-initial.log` 为新增首轮 20 项，`directed-final.log` 为最终 39 项；已核对 `preview-initial/click-editor-150.png` 的 150% 离屏布局，最终同场景预览另存 `preview-final/`。没有删除或覆盖旧日志/预览。Windows 11、150% DPI 的真实识别后单击位置、目标窗口响应、随机范围、F11/F12 和停止恢复仍待人工验收。版本保持 1.2.0，不执行 clean、不重打包、不提交或推送。

## 2026-10-26：1.2.0 时间线模板质量提醒

图像动作卡片右上角叠加绘制黄色感叹号三角，保持左上角编号、158×176 卡片尺寸、内容及连接器布局不变。仅悬浮图标区域展示深色提示，提示给出风险原因和截取建议；选择、双击编辑、右键菜单及播放只读浏览仍沿用原事件。不新增录制、保存或播放限制，缺失模板的播放前校验仍由原播放器执行，MR 不保存提示状态。

`TemplateQualityAnalyzer` 只分析所引用的图片，不截图或执行匹配。启发式检查包括：任一边小于 12 像素、参与匹配的像素不足 256 个、颜色方差低、明显边缘少，以及 2～16 像素周期的明显重复纹理。透明度规则与匹配器一致：存在 alpha≥220 的像素时只统计这些像素，否则使用 alpha≥1 的像素；全透明单独提示。重复检测使用有限探针，不保证检测出所有重复，也不能判断清晰模板在真实屏幕中是否唯一。提示阈值不作为识别阈值或禁止条件。

模板读取和分析由 SwingWorker 在后台执行，按当前 MR 目录解析相对路径；只缓存最多 128 个路径及文件修改时间、大小和文本结果，不持有解码图片或原生 Mat。载入、编辑、重建或重新显示时间线时检查文件元数据，模板更换后更新或移除警告；旧任务取消及代次保护防止迟到结果更新新卡片。文件不存在、路径无效、无法解码时显示读取问题；不持续监控磁盘文件。

新增 `TemplateQualityAnalyzerTest` 5 项，覆盖小图、纯色、平滑纹理、周期纹理、正常纹理、不透明/半透明/全透明、稀疏有效像素、中断及原图片保全。新增 `TimelineTemplateWarningTest` 3 项，覆盖实际黄色绘制、仅图标悬浮提示、编号与全部子组件几何保全、双击编辑和只读浏览、相对路径、同路径图片更新、同 ID 节点切换的旧任务保护、缺失/损坏模板仍可保存、普通动作无质量图标。测试只读写新建的 `target/test-sandboxes/template-warning-*` 合成图片与 MR，不显示窗口、不启动真实录制或发送输入。

最终定向命令：`mvn '-Dtest=TemplateQualityAnalyzerTest,TimelineTemplateWarningTest,TimelineNumberingTest,TimelinePlaybackProgressTest,TimelineJumpLayoutTest,ScaleMatchingEditorTest,LegacyRegressionTest#templatePathResolution+templatePathPlaybackAndEditor' '-Dmacro.template.warning.preview=true' '-Dmacro.template.warning.previewDirectory=target/template-quality-warning-20261026/final-preview' test`：**25 项，失败 0、错误 0、跳过 0，退出码 0**，16:06:28 完成，日志 `target/template-quality-warning-20261026/directed-final.log`。中间 21/24 项的通过记录也保留，最终 25 项包含真实未显示主窗口、编辑器和路径保存的既有集成检查。

完整执行 `mvn '-Dsurefire.timeout=180' test`：**142 项，失败 0、错误 0、跳过 0，退出码 0**，16:08:48 完成；`mvn compile` 退出码 0，16:10:12 完成。日志为同目录 `full-test.log`、`compile.log`；`git diff --check` 退出码 0。本轮未出现测试失败。

150% 离屏预览位于上述 `final-preview/` 的 `warning-card-150.png` 和 `warning-tooltip-150.png`，已核对右上角三角、编号和深色悬浮提示的布局；这些不是真实桌面截图，仍需 Windows 11、150% DPI 人工检查实际鼠标悬浮、提示出现/消失、连续录制及播放界面交互。不执行 clean，不重打包，不提交或推送，版本保持 1.2.0。

## 2026-10-26：1.2.0 缩放匹配性能优化

本轮在用户明确恢复性能工作后实施。原尺寸优先、候选比例、细查选择、透明掩码、满分像素复核、跨比例去重及多目标结果保留规则均沿用。结果扫描跳过既不命中也不能改善最佳候选的位置，避免重复计算坐标和相似度；源图至少 65,536 像素且 JVM 可用处理器多于一个时，缩放搜索每批最多执行两次原生匹配。两个任务只读共享屏幕 Mat，各自持有模板及误差矩阵，候选收集和停止回调仍在调用线程按原比例顺序执行；小图及单核环境保持串行。

每批最多额外创建一个短生命周期工作线程；批次结束即等待并释放全部矩阵，取消、中断或一侧原生异常也执行收尾，中断标志保留。没有常驻线程池或原生 Mat 缓存。缩放模板仍按需生成、保留像素缓存上限仍为 32 MiB，识别报告增加两比例批次的保守内存估算。代价是同时保留两份原生误差/模板矩阵；单次原生调用仍不能强制中断，停止需等待当前批次退出，不再启动后续批次。

新增 `ScaleMatchingParallelTest` 4 项：真实 OpenCV 对照串行和双比例批次的完整结果记录，包括普通/满分阈值、结果上限、排序、220/127 透明度和全透明模板；并检查取消、中断标志、两侧异常传播、工作线程退出及下一次识别恢复。既有细查和不同大小双目标夹具扩大到能进入并行路径，断言保留。全部使用内存合成图像，不截图或发送输入。

- 定向：`mvn '-Dtest=ScaleMatchingTest,ScaleMatchingParallelTest,ScaleMatchingStorageTest,ScaleMatchingEditorTest,ScaleMatchingPlaybackTest,LegacyRegressionTest#openCvPerfectThreshold' test`，21 项，失败 0、错误 0、跳过 0，退出码 0，13:41:44 完成。
- 完整：`mvn '-Dsurefire.timeout=180' test`，134 项，失败 0、错误 0、跳过 0，退出码 0，13:43:48 完成。
- 编译：`mvn compile`，退出码 0，13:45:22 完成，日志 `compile.log`。

修改前的实际工作区识别类先独立编译到 `baseline/` 保留，同一测量程序 `PerformanceBenchmark.java` 分别加载该基线及最终 Maven 编译类。条件为 1280×720 合成画面、60×48 模板、阈值 0.98，每组预热 3 次、测 7 次，先跑基线、再跑最终实现，未包含桌面截图耗时。最终同机复测中位数如下（日志 `baseline-repeat.log`、`final.log`）：

| 场景 | 修改前 | 修改后 |
| --- | --- | --- |
| 原尺寸命中，关闭缩放 | 45 ms | 42 ms |
| 原尺寸命中，开启缩放 | 45 ms | 42 ms |
| 缩放目标，关闭缩放，未命中 | 45 ms | 43 ms |
| 1.2 倍目标，开启缩放，命中 | 551 ms | 318 ms |
| 0.8 和 1.25 倍双目标，均命中 | 558 ms | 331 ms |
| 无命中，开启缩放 | 511 ms | 289 ms |

以上三种完整缩放搜索中位耗时降低约 41%～43%，全部命中框和双目标数量一致。独立阶段测量（预热 4 次、取 20 次均值）中，原生匹配约 36.90/37.19 ms，Java 结果扫描约 4.51/1.42 ms；主要整体收益来自两比例并行，单次原生调用本身没有变快。首次基线、扫描优化中间版及首轮并行版的结果分别保留在 `baseline.log`、`scan.log`、`parallel.log`，不混用各轮数据计算最终提升。这些数值不能作为真实应用、高分辨率、多屏或低核数机器的耗时保证。

本轮测试与测量文件集中保留在 `target/scale-performance-20261026/`，其中 `directed-initial.log` 是新增并行专项检查前的 17 项通过记录，`directed-parallel.log` 为最终 21 项，`full-test.log` 为完整回归。原有日志和诊断产物保留，不执行 clean、不重新打包、不提交或推送。Windows 11、150% DPI 真实桌面识别、可见界面和 F12 停止恢复仍需人工验收。

## 2026-10-25：1.2.0 可选缩放匹配

时间线图像动作编辑增加默认关闭的缩放开关，测试与播放共用配置，保存、加载和另存为保留，旧 MR 缺少字段时关闭。所有显示器原尺寸均未命中时，才进行约 66.7%～150% 的有限搜索：最多十个粗查和四个细查比例，按实际像素尺寸去重，屏幕矩阵复用，缩放模板缓存像素上限 32 MiB。沿用原评分、透明掩码和重叠框合并，不增加区域选择或 OCR。

新增 `ScaleMatchingTest`、`ScaleMatchingStorageTest`、`ScaleMatchingEditorTest`、`ScaleMatchingPlaybackTest` 共 16 项，覆盖常见 DPI 比值及细查、模板缩小适配、跨比例去重、错误目标和透明模板、缓存复用及容量、阶段间取消、旧 MR、另存为、编辑和未保存的识别预览参数、合成 F12 及识别后保留完整动作延迟。`OpenCvPerfectThresholdTest` 仅同步内部评分方法新增的停止检查参数，原有满分及浮点边界断言保留。

- 定向：`mvn '-Dsurefire.timeout=180' '-Dtest=ScaleMatchingTest,ScaleMatchingStorageTest,ScaleMatchingEditorTest,ScaleMatchingPlaybackTest,ScreenRecognitionServiceTest,ImageJumpEditorTest,LegacyRegressionTest#openCvPerfectThreshold' test`，24 项，失败 0、错误 0、跳过 0，退出码 0，22:53:41 完成。日志 `target/scale-matching-directed-verified.log`。
- 完整：`mvn '-Dsurefire.timeout=180' test`，130 项，失败 0、错误 0、跳过 0，退出码 0，22:57:44 完成。日志 `target/scale-matching-full-final.log`。
- 编译：`mvn compile`，退出码 0，22:58:27 完成。日志 `target/scale-matching-compile.log`。

早期验证记录保留：新增测试曾因键盘动作参数顺序写错而编译失败、因按钮名称写错而出错；首次完整回归因旧满分测试的反射签名未同步失败，均已修正。第二次完整回归出现现有 `WindowsSingleInstanceTest.crashedProcessLeavesNoStaleLock` 的 Windows 1400 错误，未修改该模块，单独复测 5 项和最后完整回归均通过。单独日志 `target/scale-matching-single-instance-recheck.log`；该偶发问题不视为本轮已修复。

性能测量只使用 1280×720 合成画面和 60×48 模板，每组预热一次后测三次：原尺寸命中时开关关闭/开启的中位耗时为 51/52 ms；目标为 1.2 倍时，关闭开关未命中、中位 50 ms，开启后命中、中位 624 ms（570～626 ms）。临时测量代码与结果在 `target/scale-matching-benchmark/`，没有新增常驻性能日志，不能当作真实应用或更高分辨率的耗时保证。

未执行 `mvn clean`、未重打 ZIP 或安装包，现有 dist 产物早于本功能。仍需 Windows 11、150% DPI 人工检查控件显示、100%/125%/150% 目标缩放的命中和误匹配、预览与播放一致性、保存重开、F12 及停止后的窗口恢复。单轮原生 OpenCV 调用不能强制中断，停止在调用返回后的 Java 阶段及比例之间检查。

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
