# 主窗口录制控制栏

当前概述同步于 2026-10-25；下方按日期保留的探针、测量和验收是各轮历史记录，不能把其中旧方案当成当前实现。最新项目状态及实际自动测试结果见 [文档索引](README.md) 和 [自动测试](automated-tests.md)。未重新进行真实桌面验收或性能测量。

## 布局与配置

不创建额外的录制浮动窗口，也不调用 `setUndecorated`。选择“控制栏”后，原主窗口使用 DWM 混合标题栏：

```text
Macro Recorder  正在录制  操作数：N                       [自绘最小化]
当前目标窗口
进程名 · 窗口标题（过长省略）                [截图] [暂停 / 继续] [终止]
```

2026-10-20 按已确定的 B 分组型双行布局更新：第一行显示程序名、录制状态、操作数量，最小化固定在右侧；第二行左侧显示目标信息，右侧固定截图、暂停/继续、终止按钮。目标文本只占剩余宽度，由 JLabel 绘制省略号，完整内容保留在工具提示中；窗口标题按纯文本处理。复用现有 Dark 主题、`ComponentStyleUtil` 圆角按钮及主窗口 16px 矢量图标。后续水平缩放任务已将最小宽度与固定高度约束接入原生消息链：保留水平缩放，禁止纵向改高，并独立记住位置和宽度，具体见下方记录。

Windows 保留 DWM 外框、任务栏身份及原生拖动/缩放消息链，顶部内容和唯一的最小化按钮由 Swing 绘制；不使用 `setUndecorated`，不重建窗口，不显示原生最小化、最大化、关闭按钮。自绘最小化调用现有 `WindowManager.minimize`。紧凑模式移除最大化按钮样式，因此不再将双击最大化作为紧凑模式验收要求；停止后恢复原有样式和窗口状态。

目标数据直接取当前录制使用的 `TargetWindow.processName/windowTitle`，来自已有窗口选择或 MR 加载流程，不新增窗口扫描。显示的是已指定目标的状态数据，不轮询目标窗口的实时标题。空目标显示“全局录制 · 未指定目标窗口”；非空目标的缺失字段分别显示“进程名暂不可用”“窗口标题暂不可用”。不改变目标匹配、录制范围或 MR 数据模型。

录制开始前保存完整窗口的尺寸、位置、最小尺寸、扩展状态和可调整大小标记。停止时仍由既有录制停止回调收尾，再恢复完整内容区及上述状态。没有清空录制数据，也没有改动播放窗口行为。

`record.startBehavior` 控制启动行为：`MINIMIZE` 最小化完整主窗口，`CONTROL_BAR` 启用混合标题栏，`NONE` 不主动改变窗口状态。缺少新配置时默认最小化。播放最小化配置独立，本次标题栏扩展不改变这些配置的读取和保存。

## DWM 扩展与恢复

2026-10-25 起，普通主页也复用 `WindowsRecordingTitleBar`：`MainTitleBar` 位于根面板的菜单栏区域，业务内容面板保持原结构，显示主题标题及最小化、最大化/还原、关闭三个自绘按钮。普通模式只隐藏系统按钮区域，保留最小化/最大化能力及双向缩放；关闭沿用原有关闭事件。进入紧凑模式先卸载主页消息过程，再安装紧凑实例；停止后反向恢复，同一 HWND 不重建、两个回调不叠加。紧凑模式的固定高度、水平缩放和置顶生命周期不变。

两个模式都通过 `WM_NCCALCSIZE` 扩展顶部客户区，将没有接管的消息转发给保存的原 AWT 过程。实现沿用[微软的自定义 DWM 窗框机制](https://learn.microsoft.com/en-us/windows/win32/dwm/customframe)。以下样式与命中规则专指紧凑模式：

- 激活时保存并移除 `WS_SYSMENU / WS_MINIMIZEBOX / WS_MAXIMIZEBOX`，停止或激活失败时恢复原值，保留其他窗口样式位。DWM 顶部玻璃边距归零，不再保留系统按钮区域或进行像素透明度清理。
- 四个自绘按钮返回客户区命中，两行其余区域保留拖动命中；左右边缘及四角只返回水平缩放，上下边缘禁止纵向缩放，顶部中间保留拖动。物理坐标和 HWND 的 DPI 用于原生命中，Swing 布局保留逻辑坐标。
- 原生回调不读取 Swing 布局、不等待 EDT；布局通过不可变快照传入，界面刷新回到 EDT。
- 停止录制时恢复原窗口过程、DWM 边距和颜色，再恢复完整页面及窗口尺寸。原生窗口销毁也会清理回调引用。

此前 Windows 11 环境中，直接开启 `DWMWA_ALLOW_NCPAINT` 会造成原生标题栏内容空白，因此正式实现仍不使用该属性。此前混合扩展方案的验收不代表本次自绘双行布局已通过桌面人工验收。

## 原生系统按钮的隐藏限制（历史探针结论）

以下是早期尚未隐藏正式系统按钮时的独立探针记录，不代表当前仍保留系统按钮。探针在同一 Windows 11、144 DPI 的混合标题栏中验证：

| 临时修改（仅探针自己的窗口） | 实际结果 |
| --- | --- |
| 移除 `WS_MAXIMIZEBOX` | 最大化按钮仍显示，系统状态变为不可用，未隐藏 |
| 删除 `SC_CLOSE` 菜单项 | 关闭按钮仍显示，系统状态变为不可用，未隐藏 |
| 禁用 `SC_CLOSE` 菜单项 | 关闭按钮仍显示，系统状态变为不可用，未隐藏 |
| 移除 `WS_SYSMENU` | 最小化、最大化、关闭三个按钮一起消失 |

截图及窗口状态日志位于忽略的 `target/caption-system-hybrid-*.png` 和 `target/caption-system-hybrid.log`。这与[微软对关闭按钮的说明](https://devblogs.microsoft.com/oldnewthing/20260911-00/?p=112691)一致：关闭按钮没有独立的隐藏样式，保留原生最小化按钮依赖系统菜单。因此该阶段保留系统三按钮及原生边框，未以变灰冒充隐藏。

2026-10-20 用户明确要求只保留自绘最小化，正式实现已采用“保留 DWM 边框、移除原生系统菜单并自绘单个最小化”的方案。以上探针及原生三按钮验收保留为历史记录。

## 状态与截图

录制器此前只有录制/图像采集状态，没有普通暂停。现在在同一状态锁内增加 `PAUSED`，共用已有动作列表和时间补偿：

- 暂停不调用停止、不重新注册钩子、不清空动作。
- 恢复时扣除暂停实际耗时；暂停期间嵌套截图不重复扣时。
- 录制状态下截图，结束后继续录制；暂停状态下截图，确认或取消后继续保持暂停。
- 选区确认仍通过原有 PNG 保存流程创建 `IMAGE_CHECK`，没有新增图像动作模型。
- 录制截图启用已有控制器的“隐藏所有应用可见窗口”选项。先保存每个窗口的可见及扩展状态，再隐藏，之后才进行屏幕捕获和冻结遮罩。
- 完成、取消和异常统一释放遮罩并恢复先前可见的窗口；期间已经关闭的窗口不重新打开。
- F12 仍进入既有停止流程，取消选区及未完成的模板保存，旧会话回调不能重新开启录制。
- 采集错误提示期间也暂停普通录制，避免确认弹窗的输入及等待时间写入宏。

## 控制输入过滤

`RecordingControlInputFilter` 同时支持多个独立矩形：紧凑模式由主窗口提供整个紧凑窗口的 **Win32 物理像素矩形** 和 **原生前台状态**，包括标题栏及边框；主页只提供实际显示的“开始录制 / 指定窗口开始”或“停止录制 / 图像操作”按钮区域，不按它们的外接矩形过滤中间空白，也不屏蔽整个主窗口的键盘输入。

主页按钮的客户区逻辑坐标由 EDT 在组件显示、移动、缩放、布局及页面切换时发布，启动录制前也发布一次。NativeHook 只读取快照，并用 `ClientToScreen` 与该 HWND 的 `GetDpiForWindow` 取得当前物理屏幕区域，不跨线程读取 Swing。窗口隐藏或最小化后提供空区域，之前的边界仍保留在事件时间历史中。

过滤器不读取 Swing 布局、不等待 EDT，也不消费或拦截其他程序的系统输入。NativeHook 监听仍按原有方式注册和移除。

1. 在写入动作前，使用原生事件本身的坐标判断控制栏范围，不使用事后移动到的新鼠标位置判定按钮归属。
2. 保存有限的矩形/焦点变化历史和暂停区间；依据原生事件时间处理窗口已隐藏或最小化后才派发的旧输入。启动时保留紧邻启动边界的区域历史，启动之前的排队事件直接忽略，启动按钮及停止按钮的按下/释放仍成对排除。Windows 开机计时的低 32 位回绕和同一计时边界均有测试覆盖。
3. 控制栏上开始的鼠标手势，连同其后移出控制栏的拖动和释放一起排除；控制栏获得前台时，Tab、空格等操作键也不录入。
4. 外部按下并已录入的键鼠保留配对释放。暂停或截图前，闭合尚未释放的录制动作，真实释放到来后不再重复录入，避免宏中留下悬空按键。
5. 截图桥接优先从最后一个宏内鼠标坐标开始；没有有效锚点或结束位置位于控制栏时，不生成带按钮位置的桥接轨迹。

## 录制期间另存为

宏的“另存为”按钮在录制器实际处于录制、暂停或截图状态时禁用；`saveMacroAs()` 本身也检查实际录制状态，防止过期的 UI 标志或其他调用绕过限制。启动成功、停止统一回调、启动失败以及既有动作状态刷新共用同一可用性计算；停止后恢复原有按钮状态，空宏及播放状态继续由入口既有检查阻止文件操作。当前宏另存为只有主窗口按钮，普通保存对未命名宏的委托也经过同一入口。图像采集页的 PNG 另存为属于独立功能，本次不改变。

## 相关模块

- `MacroFrame`：紧凑内容区、按钮联动、轻量数量刷新、原生窗口范围、完整窗口恢复。
- `RecordingControlBar`：双行分组布局、目标信息占位及文本尺寸约束。
- `WindowsRecordingTitleBar`：原生样式隐藏/恢复、DWM 边框、命中及消息链生命周期。
- `MacroRecorder`：必要的暂停状态、时间补偿、按下/释放配对和过滤接入。
- `RecordingControlInputFilter`：区域、焦点、时间边界及输入手势过滤。
- `ScreenCaptureController`：录制截图可选隐藏/恢复本进程全部可见窗口。

没有修改 `MacroPlayer`、识别算法、`MacroAction`、流程数据结构、MR 格式或文件面板。

## 验证范围

- `RecordingControlInputFilterTest`：物理坐标、控制栏和外部区域、拖动与跨区域释放、焦点键盘、显隐队列、同一计时边界和计时回绕。
- `RecordingPauseTest`：直接调用原生事件回调，验证控制栏输入排除、外部输入保留、暂停/继续、键鼠配对、暂停中截图成功/取消、时间补偿、F12 及过期回调。没有注册全局钩子或发送真实桌面输入。
- `HomeRecordingControlsUiTest`：程序化核验真实主页布局、按钮物理区域及间隙、移动/改宽后的区域历史、启动/停止排队事件、合法外部输入和配对释放、另存为实际状态与停止/失败恢复、暂停动态图标。不注册钩子、不发送真实鼠标输入。此测试需显式运行其 `main`，不能把 Maven Surefire 中的零测试记录算作验收通过。
- `RecordingControlBarLayoutTest`：Maven 自动运行；不创建顶层窗口，检查目标字段/全局占位、两行顺序、长文本省略、右侧按钮固定、纯文本及不同字体尺寸。字体尺寸检查不替代 Windows DPI 验收。
- `RecordingTitleBarContractTest`：通过 `LegacyRegressionTest#recordingTitleBarContract` 在隔离子 JVM 运行。创建隐藏的真实 MacroFrame，检查目标数据接入、四按钮客户区命中、文本区拖动命中、自绘最小化状态请求、原生按钮样式隐藏、完整样式与消息链恢复及重复进入。不显示窗口、不注册 Hook、不截图、不发送真实输入。
- `RecordingControlBarUiTest`：保留专项人工夹具，已将系统三按钮断言更新为自绘最小化及新原生样式契约。使用真实可见窗口和截图控制器，包含程序化暂停/截图/停止/恢复、绘制后最小化符号像素检查。此次未运行该可见窗口夹具，不据此报告通过。

界面测试请从 `target` 下的隔离类目录启动，避免读取实际用户配置。例如在 PowerShell 中：

```powershell
mvn -q -DskipTests package
New-Item -ItemType Directory -Path target/recording-control-tests -Force | Out-Null
Copy-Item target/classes -Destination target/recording-control-tests -Recurse -Force
Set-Content target/recording-control-tests/classes/config.properties 'loadLastMacroOnStartup=false'
java -cp "target/test-classes;target/recording-control-tests/classes;target/lib/*" com.macro.ui.RecordingControlBarUiTest
```

`RecordingControlBarLiveTest` 使用同一隔离类目录，提供主窗口及外部输入测试窗口，禁用测试中的鼠标移动采集并默认在三分钟后自动关闭；需要更长观察时间时可传入 `--extended`，在十五分钟后自动关闭。需通过真实鼠标按下“开始录制”，再测试按钮和外部键鼠；状态计数写入忽略的 `target/recording-control-live-state.txt`，不记录按键文本。

2026-10-18 验证结果：Maven 编译、输入过滤、暂停/截图状态及界面回归测试均通过。当前 Windows 的窗口 DPI 为 144（150%），普通、最大化、还原、暂停、反复重绘及截图/最小化恢复后的系统三按钮均通过实际截图像素检查，截图位于忽略的 `target/recording-native-caption*.png`。

主页修正后的 `HomeRecordingControlsUiTest` 也已显式运行通过，实际 144 DPI 下依次验证开始/指定窗口、停止/图像操作两组按钮，以及移窗和改宽后的区域；另存为按钮恢复和入口防护、三个图标及暂停切换通过程序化验证。`mvn -q test` 返回成功，但这些现有测试采用独立 `main`，Surefire 报告为零测试，不能据此声称已运行；本轮另外显式执行了过滤、暂停、主页集成、控制栏界面四个测试主程序。

Computer Use 已实际点击“开始录制”，观察到同一窗口进入紧凑标题栏；此后工具出现截图标识失效、前台进程无法读取及窗口绑定丢失，未能可靠完成后续实点。仍需人工复核标题栏拖动、双击最大化、边缘缩放、系统按钮点击/悬停、真实 JNativeHook 派发时序以及真实框选所得 PNG 中没有控制栏。夹具已超时退出，没有遗留录制测试进程。

未改变用户的 Windows 显示缩放，100% DPI 仍待真实环境验收；修改 Java 的 `uiScale` 不能替代 Windows 的 96 DPI 验证。上述程序化窗口命令及命中测试不能当作这些实点场景已经通过。

## 2026-10-20 自绘双行布局验证

最终执行：

- `mvn '-Dtest=RecordingControlBarLayoutTest,LegacyRegressionTest#recordingTitleBarContract' test`：退出码 0，3 项，失败/错误/跳过均为 0。
- `mvn test`：退出码 0，25 项，失败/错误/跳过均为 0。
- `mvn compile`：退出码 0。完整测试也重新编译了生产和测试源码。

日志为 `target/recording-titlebar-test-final.log`、`target/recording-titlebar-full-test-final.log` 和 `target/recording-titlebar-compile-final.log`。此前定向测试在测试编译阶段发现 JNA 接口调用错误，随后一次完整测试为 25 项、1 项失败（直接复用主页 DPI 最小尺寸补偿导致紧凑尺寸放大）；均已修复，早期日志保留在 `target/recording-titlebar-test.log`、`target/recording-titlebar-full-test.log`。最终采用 Swing 逻辑最小尺寸，并在现有 `WM_GETMINMAXINFO` 消息处理中按 HWND DPI 设置物理最小拖动尺寸。

本轮没有运行可见桌面夹具或进行真实鼠标验收，仍需在 Windows 11、150% 缩放下逐项检查：

- 两行结构与已确定的 B 布局一致；第一行最小化、第二行三个操作按钮均固定在右侧。
- 没有原生最小化、最大化、关闭按钮残留；自绘最小化可从任务栏正常恢复，恢复后录制状态不丢失。
- 标题文字和空白区域能够拖动，按钮区域正常响应点击，不触发拖窗。
- 目标进程名与已指定窗口标题一致；全局/未指定、字段暂不可用显示合理。
- 超长标题省略，按钮不被挤压或遮挡；首次进入、暂停/恢复和操作数量变化时没有裁切或跳动。
- 截图、暂停/恢复、终止及停止后的完整窗口样式/位置/尺寸恢复正常；150% DPI 下字体、边框和按钮命中一致。

不修改 MR 格式、图像节点模型、识别/播放系统或文件面板。用户同时修改的 `pom.xml` 版本与 `ActionTimelinePanel` 方法引用，以及原有两项 AD 暂存记录，均原样保留；未提交或推送。

## 2026-10-20 紧凑操作栏置顶修复

原因：紧凑操作栏一直使用原 `MacroFrame`/HWND，只切换内容面板，没有重建窗口；原代码既未启用置顶，也未保存原有置顶值。`RecordingControlBar` 为普通 JPanel，不能管理顶层窗口层级。`WindowsRecordingTitleBar` 修改普通窗口样式并用 `SWP_NOZORDER | SWP_NOACTIVATE` 刷新边框，没有清除置顶的扩展样式。暂停/恢复只改变录制器状态；截图隐藏/恢复的是同一窗口，不清除置顶属性。

修复仅在 `MacroFrame` 的紧凑生命周期中加入置顶：进入前保存 `isAlwaysOnTop()`，标题栏激活成功后调用 `setAlwaysOnTop(true)`；录制和暂停共用该状态。退出时在标题栏撤销的 finally 中恢复原值，标题栏启用失败也走同一退出路径；重复进入/退出不覆盖原始值。没有在计时刷新中前置窗口或请求焦点，也没有改变窗口原有的可聚焦能力。停止后的既有主页前置行为保持原样。

`RecordingTitleBarContractTest` 扩展隐藏窗口检查：同一 HWND、录制/暂停/恢复置顶、最小化恢复后的属性、原值 false/true/false、重入及重复收尾、原焦点能力保留，并观察隐藏窗口进入紧凑模式时没有改变原生前台窗口。该夹具只模拟录制状态，不调用 start 注册 Hook，不显示窗口、不发送真实输入。

本次定向命令 `mvn '-Dtest=RecordingControlBarLayoutTest,LegacyRegressionTest#recordingTitleBarContract+recordingPause+recordingControlInputFilter' test` 退出码 0，实际 5 项，失败/错误/跳过均为 0；`mvn compile` 退出码 0。日志：`target/recording-topmost-tests.log`、`target/recording-topmost-compile.log`。本次未重新运行完整测试或可见桌面夹具。

真实 Windows 11、150% DPI 仍需验收：录制中反复点击目标应用和 Alt+Tab，操作栏保持在普通应用之上、目标应用能够连续接收键鼠；暂停及恢复后重复上述切换；最小化/任务栏恢复、截图结束恢复后检查置顶与按钮可用；停止/F12 后恢复完整窗口并撤销临时置顶，原先已置顶则继续保持。复核双行布局、DWM 边框和拖动/缩放。自动检查不代表这些桌面场景已经通过。

## 2026-10-20 固定高度、水平缩放及位置记忆

`MacroFrame` 仍为唯一顶层窗口，继续保持 `setResizable(true)` 和 DWM 外框。最小宽度使用当前紧凑双行布局的首选宽度（基础内容宽度 640 逻辑像素，加原生边框）；高度由当前布局和边框计算，不读取历史高度。

`WindowsRecordingTitleBar` 在同一原生消息链中处理：

- `WM_NCHITTEST`：左右边缘返回水平缩放，四角映射到左/右缩放；顶边中部为拖动，底边不启动纵向缩放。四个按钮仍返回客户区命中。
- `WM_GETMINMAXINFO`：以 HWND DPI 将逻辑最小宽度及固定高度换算为物理像素，最小/最大拖动高度相同，并限制系统最大化高度。
- `WM_SIZING`：再次校正候选矩形，固定高度及最小宽度，左侧缩放保留右边界。
- `WM_WINDOWPOSCHANGING`：对非最小化的实际尺寸变更约束高度，保留纯移动、左右改宽、显隐及焦点相关标志。没有通过全局禁用缩放锁定高度。

这些消息的尺寸/命中语义参考[微软 MINMAXINFO 说明](https://learn.microsoft.com/en-us/windows/win32/api/winuser/ns-winuser-minmaxinfo)及[WM_NCHITTEST 说明](https://learn.microsoft.com/en-us/windows/win32/inputdev/wm-nchittest)。原生回调只使用 EDT 发布的尺寸快照，不跨线程读取 Swing。

`AppConfig` 新增独立的 `record.controlBar.x`、`record.controlBar.y`、`record.controlBar.width`，保存 Swing 逻辑坐标，不保存高度，也不读写主页几何配置。移动/调整宽度只缓存正常窗口矩形；自绘最小化前缓存一次，退出紧凑模式（包括停止/F12 共用收尾）保存最新矩形，最小化时使用缓存，避免记住图标位置。启用失败的未完成布局不保存。三项通过既有同目录临时文件和原子替换一次写入；相同值不重复写盘，保存失败回滚内存并沿用旧文件、输出已有错误诊断，不阻止主页恢复。

进入时优先恢复保存值；首次使用或配置不完整/格式错误时使用主页所在位置和默认紧凑宽度。`RecordingBarGeometry` 根据当前所有显示器工作区（扣除任务栏）选择相交最多或最近的区域，把窗口移回可见范围，并约束过宽/过窄的宽度。屏幕变化在每次进入时重新计算，不改动 Windows 缩放设置。若所有屏幕的工作区均小于布局最小尺寸，则保留控件最小尺寸并将标题区锚定在可用区域起点，无法同时保证整栏完整可见；常规屏幕下整栏限制在一个工作区内。

扩展自动验证：`RecordingBarGeometryTest` 覆盖负坐标、多屏/移除屏幕、分辨率变化、极值、固定高度、100%/150% 坐标约束和原生命中映射；`RecordingBarPlacementTest` 在隔离配置副本中检查持久化重载、非法值回退、无高度、未知/主页配置保全、同值不写盘及失败回滚；隐藏窗口契约实际发送尺寸/命中查询消息，检查改宽、退出保存、重新进入恢复、最小化时退出及主页状态保全。不显示窗口、不注册 Hook、不截图、不发送真实输入。

Windows 11、150% DPI 人工验收仍需逐项完成：

- 左右边缘均能改宽，达到最小宽度时按钮和目标文字区域不遮挡；超长目标省略正常。
- 顶/底边缘不能改高，四角只能水平改宽；双行高度稳定，DWM 边框正常。
- 拖动标题文字及空白区域能够改变 X/Y，按钮点击不会拖窗。
- 改宽/移动后停止或 F12，再次进入以及重启应用后恢复位置和宽度；暂停/恢复与截图按钮正常。
- 最小化后停止、截图中停止不记住临时位置；停止后主页尺寸、位置、缩放能力及原置顶状态恢复。
- 修改分辨率/缩放或移除显示器后再次进入，操作栏回到当前可见工作区。
- 切换目标应用时保持置顶，目标应用正常接收键鼠；无持续抢焦点。

自动测试的原生消息检查不能视为真实桌面拖边缩放、跨屏 DPI 或焦点行为已经验收。

本次实际执行：

- `mvn '-Dtest=RecordingBarGeometryTest,RecordingControlBarLayoutTest,LegacyRegressionTest#recordingTitleBarContract+recordingBarPlacement+configurationWriteSafety+configurationParseSafety' test`：10 项通过，失败/错误/跳过均为 0，退出码 0；日志 `target/recording-geometry-tests-verified.log`。
- `mvn test`：30 项通过，失败/错误/跳过均为 0，退出码 0；日志 `target/recording-geometry-full-test.log`。该次包含最终的最小化时退出保存检查。
- `mvn compile`：退出码 0；日志 `target/recording-geometry-compile.log`。

首次基础定向验证 10 项通过；扩展原生契约时曾因新增夹具 `long`/`int` 类型不匹配在测试编译阶段失败，已修正，失败日志保留在 `target/recording-geometry-tests-final.log`，该次没有执行测试。后续定向及完整测试如上，未降低任何检查标准。

## 2026-10-20 改宽后的内容铺满与布局同步

用户桌面截图显示外框变宽而右侧出现黑色空白。检查确认：`RecordingControlBar` 的两级 `BorderLayout` 本来就会分配剩余宽度，最小化及操作按钮组位于各自 `EAST`；640 逻辑像素是首选内容宽度，目标文字的 180 逻辑像素是首选值，都不是实际宽度上限。主页的固定尺寸控件不参与紧凑内容布局。

普通 JFrame、实际 MacroFrame 以及关闭 AWT 动态布局的原生改宽探针均能在当前测试环境同步，未直接复现原截图的具体触发条件。可确认的刷新缺口是：现有原生消息链会合并刷新命中/主题并重绘，但没有显式保证整个 Swing 容器树已按新尺寸完成布局；之前隐藏窗口测试主动调用 validate，也不能覆盖该更新时序。因此本轮补齐该同步保障，不把截图直接归因于固定宽度或原生客户区异常。

最小修改仅在 `WindowsRecordingTitleBar`：`WM_SIZE / WM_WINDOWPOSCHANGED / WM_DPICHANGED` 请求合并的 EDT 布局刷新；先更新 DPI 边距，再使窗口布局失效并通过标准 `validate()` 重新布局根面板、分层面板、内容和双行子容器，最后发布新的按钮/拖动命中快照。没有手动把内容实际宽度写死为窗口宽度，没有新增焦点请求或修改原生高度限制。移除标题内容在 DPI 刷新时写入的整份 preferredSize 快照，保留自然首选尺寸和既有固定高度计算。现有宽度/位置持久化、置顶及主页恢复逻辑不变。

新增 `RecordingBarNativeResizeTest`，经 `LegacyRegressionTest#recordingBarNativeResize` 在隔离子 JVM 运行。使用实际 MacroFrame，但窗口显示在屏幕外并禁止自动焦点请求；直接用 Win32 SetWindowPos 改宽，发送尺寸移动阶段消息，不通过 JFrame.setSize/测试端 validate 来驱动普通改宽布局。比较外框、客户区、Java 窗口、根面板、分层面板、内容宽度和右侧两组按钮位置，并在内存 BufferedImage 中绘制操作栏，检查新增区域背景和分隔线。另构造根面板仍为旧宽度的状态，检查生产刷新入口在 EDT 返回前恢复正确布局。不截图、不发送真实键鼠输入，应用配置位于隔离目录；该入口有已显示的屏幕外窗口，与原先隐藏窗口契约的边界不同。

真实 Windows 11、150% DPI 仍需检查：最小允许宽度、默认宽度和明显加宽三种尺寸下，背景完整铺满、分隔线延伸到右侧内容边距、两行按钮始终贴近右边缘；从宽缩窄后目标标题省略且按钮不重叠；连续拖边、松开鼠标、最小化恢复及重新进入后无黑色空白。同时复核固定高度、位置/宽度记忆、置顶/焦点和停止后的主页恢复。原生消息自动检查不等于这些真实桌面操作已经验收。

本轮实际执行 `mvn '-Dtest=RecordingControlBarLayoutTest,RecordingBarGeometryTest,LegacyRegressionTest#recordingBarNativeResize+recordingTitleBarContract+recordingBarPlacement' test`：9 项通过；`mvn test`：31 项通过。两次失败/错误/跳过均为 0、退出码 0；`mvn compile` 退出码 0。日志为 `target/recording-resize-tests.log`、`target/recording-resize-full-test.log`、`target/recording-resize-compile.log`。前期探针的成功日志也保留；未将其描述为已复现或已通过原截图现场验收。

## 2026-10-20 鼠标水平拖边的原窗口过程转发

后续真实桌面反馈限定为鼠标拖边时黑色区域/按钮缺失。核查发现 `WindowsRecordingTitleBar` 的 WM_SIZING 分支约束 RECT 后直接返回，截断了原 AWT 的动态布局路径；此前原生改宽测试关闭动态布局，使用 SetWindowPos 加合成进入/退出消息，没有覆盖这个分支。上一节通过的布局检查不能作为该真实拖边场景已修复的依据。

本次生产修改仅在尺寸约束后调用已有 `callOriginal`，使用安装时保存的原窗口过程指针，经 CallWindowProc 继续原 AWT 消息链，不调用当前自定义过程或 DefWindowProc。处理后仍返回 TRUE，符合 [WM_SIZING 返回值契约](https://learn.microsoft.com/en-us/windows/win32/winmsg/wm-sizing)。这保留 AWT 的动态布局以及 D3D/OGL 绘图表面更新入口，依据为 [OpenJDK 17 WComponentPeer](https://raw.githubusercontent.com/openjdk/jdk17u/master/src/java.desktop/windows/classes/sun/awt/windows/WComponentPeer.java)。未增加 validate/repaint，未更改固定高度、最小宽度、四角命中、DWM 边框、置顶、几何记忆或主页恢复逻辑。

已复核窗口过程生命周期：activate 保存安装前的指针，安装后采用 SetWindowLongPtr 返回的前任指针；INSTALLED 和实例字段保留回调强引用。deactivate 检查当前消息链仍属于本实例，再恢复原过程；WM_NCDESTROY 继续原处理后延迟释放额外引用。本次没有修改这些机制。

`RecordingBarNativeResizeTest` 开启动态布局，增加原 AWT 前的观察层，验证约束后的 WM_SIZING 单次转发、TRUE 返回值、原 AWT 尺寸通知，以及八个方向的尺寸/锚点保护。读取原生固定高度，比较物理像素；较窄、默认、较宽及缩回检查各层容器、按钮、内存背景/分隔线；退出时检查生产过程与测试观察层依次恢复。测试使用隔离配置、屏幕外窗口，不截图、不注册 Hook、不发送真实输入。消息序列仍为合成，不能替代系统鼠标拖动循环和实际 GPU 绘制验收。

修复前新增回归定向 1 项按预期失败，退出码 1，日志 `target/native-sizing-before.log`。首轮修复后定向 9 项中夹具高度基准有 1 项失败，改为读取原生定高约束而非换算初次显示高度后，最终定向 9 项通过，失败/错误/跳过均为 0、退出码 0，日志 `target/native-sizing-directed-final.log`；首轮日志 `target/native-sizing-directed.log` 保留。

最终命令 `mvn '-Dtest=LegacyRegressionTest#recordingBarNativeResize+recordingTitleBarContract+recordingBarPlacement,RecordingBarGeometryTest,RecordingControlBarLayoutTest' test` 实际 9 项通过；`mvn test` 实际 36 项通过，失败/错误/跳过均为 0、退出码 0；`mvn compile` 退出码 0。完整测试和编译日志为 `target/native-sizing-full-test.log`、`target/native-sizing-compile.log`。未提交或推送，原有修改及两项 AD 状态保持。

Windows 11、150% DPI 人工验收仍需完成：左右边缘连续拖动到较窄、默认、较宽尺寸，检查背景/分隔线/两组右侧按钮完整跟随，松开后无黑区；高度不变且四角仅水平缩放；最小化恢复、重新进入紧凑模式后位置宽度恢复；录制/暂停置顶且目标应用焦点正常；停止后主页位置、尺寸、窗口过程和原置顶状态恢复。

## 2026-10-20 自定义刷新去重

基于水平缩放性能调查，仅调整 `WindowsRecordingTitleBar`：刷新时先核对窗口客户区对应的根面板、分层面板和内容容器尺寸/有效状态，已经同步时不再无条件 invalidate/validate；有效但仍保留旧尺寸的子树会先使根面板失效，再由原布局管理器修复。命中快照、颜色、DPI边距或布局有变化时才额外请求整栏 repaint，文字/按钮自身的 Swing 更新仍保留。每条 WM_SIZING 继续进入原 AWT，不关闭动态布局、不延迟全部内容、不改变 Java2D 管线。

增加 WM_EXITSIZEMOVE 的最终合并刷新：原过程完成后核对布局和命中快照，保留最终整栏绘制；已正确的容器无需为松手消息重新布局。激活、DPI、DWM 合成变化仍保留必要绘制，卸载时清除刷新标记。没有改变固定高度、最小宽度、边缘/四角规则、DWM边框、几何持久化、置顶或主页恢复逻辑。

新增 `RecordingTitleBarRefreshTest` 4项，屏幕外独立 JFrame、动态布局开启，不构造 AppConfig、不注册 Hook、不截图或发送输入。检查无变化的公开刷新及合并调度不重排/不整栏重绘，旧尺寸根面板与内容/失效布局可恢复，原生改宽后快照正确，松手时有最终修复/绘制且标记不会污染后续普通刷新。

本轮定向 `mvn '-Dtest=RecordingTitleBarRefreshTest,RecordingControlBarLayoutTest,RecordingBarGeometryTest,LegacyRegressionTest#recordingBarNativeResize+recordingTitleBarContract+recordingBarPlacement' test`：13项通过；完整 `mvn test`：40项通过，失败/错误/跳过均为0、退出码0；`mvn compile` 退出码0。日志为 `target/resize-refresh-directed-final.log`、`target/resize-refresh-full-test.log`、`target/resize-refresh-compile.log`。首轮夹具的两处断言/隔离边界问题及诊断日志保留，详见自动测试文档。

Windows11、150% DPI 采用相同临时探针，在完整测试结束后串行测优化前后。160次/16ms间隔可见合成改宽，自定义刷新中的 validate 从153次降至2次，累计35.964→4.805ms；240次/8ms间隔从215次降至5次，累计60.297→3.669ms。两组 AWT 动态任务数分别保持160/240，说明没有跳过原链。整体 EDT 探针排队 P95 分别6.482→5.708ms、9.981→9.823ms；最大延迟和绘制耗时有波动，不能宣称整体卡顿消失。初次优化后测量与 Maven 重叠，明确不作公平对比；最终数据来自串行测量日志。

Computer Use 在优化前后均完成一次相同的左边缘短缩窄。基线还存在用户输入介入，两个60秒观测窗口系统循环总时长不同（约1923/42ms），不得直接比较为持续拖动改善比例；优化后自定义刷新内validate为0，但样本只有4条WM_SIZING，无法复现此前高消息量积压。右边缘动作/后续观察有工具输入介入、无确认宽度变化及夹具到期边界，不报告其已完整验收。原始探针、基线副本、串行性能日志及完整边界在 `target/resize-diagnosis-refresh-f340d4bec8444ee1973d2a38b8400dca/comparison.md`。

仍需用户实际录制场景人工验收：连续左右拖动至较窄、默认、较宽尺寸时背景/分隔线/按钮及时跟随；松手后布局和命中一致；定高及最小宽度正常；最小化恢复/重新进入后几何正确；置顶不抢焦点、停止后主页恢复。自动测试和短系统循环不能替代持续拖动的呈现验收。

## 2026-10-20 录制截图跳过隐藏过渡

调用链核查：截图按钮/F10 → `MacroRecorder.requestImageCapture` 暂停普通事件采集并建立会话 → EDT `MacroFrame.startRecordingImageCapture` → `ScreenCaptureController.startCapture` 保存窗口状态并隐藏 → 原先 Toolkit.sync → 固定 150 ms Swing Timer → SwingWorker 中 Robot 捕获 → 冻结画面框选。`MacroFrame`、`RecordingControlBar` 和框选层都没有透明度渐变实现；这里观察到的过渡属于原生窗口隐藏链，150 ms 是应用主动加入的额外等待。

最小修改仅涉及截图控制器和新建的 `WindowsCaptureSupport`。录制专用的 hideAllApplicationWindows 路径先快照全部可见窗口，临时设置每个窗口的 `DWMWA_CLOAK`，再走原有 Swing 隐藏；截图路径不再等待隐藏动画。后台工作线程执行 Toolkit.sync、DwmFlush 和既有 Robot 捕获，不在 EDT 等待合成，也不另加固定等待。任何窗口遮蔽不可用或 DWM 同步失败时保留原有 150 ms 保守等待（在工作线程），不改变普通截图页面的原 Timer 路径。

使用遮蔽而非禁用过渡属性的原因是本机 `DwmGetWindowAttribute(DWMWA_TRANSITIONS_FORCEDISABLED)` 返回 E_INVALIDARG，无法可靠快照其原值；不能盲目写回 false。`DWMWA_CLOAKED` 可读，保存并恢复其应用遮蔽位，不改 shell/owner 遮蔽位及其他动画设置。依据为[微软窗口属性说明](https://learn.microsoft.com/en-us/windows/win32/api/dwmapi/ne-dwmapi-dwmwindowattribute)。[DwmFlush](https://learn.microsoft.com/en-us/windows/win32/api/dwmapi/nf-dwmapi-dwmflush)仅等待本进程已排队的 DirectX 更新，不代表整个桌面会话的合成完成；额外等待是否可去掉依据本机桌面实测，而非仅凭 API 返回成功推断。

取得冻结图像后、创建框选层前解除临时遮蔽，避免所属窗口的遮蔽被框选层继承。此时主窗口仍为 Swing 隐藏，不会回到已经取得的图像。确认、取消、异常及外部取消均走原收尾，恢复可见性、扩展状态和遮蔽原值；单个窗口恢复异常会记录诊断并继续恢复其他窗口。录制暂停/事件过滤、置顶、宽度位置记忆和固定高度不变。`-Dmacro.capture.timing=true` 可显式开启每次截图的一条耗时记录，默认不开启，无常驻高频日志。

`CaptureTimingProbe` 在 Windows 11、150% DPI 桌面使用自建纯色前景/背景，只采样完全位于测试背景内的 200×100 逻辑像素区域，不保存图像、不访问个人素材。正式接口两组各 20 次：

| 隐藏到小区域图像取得 | 最小 | 中位 | 最大 | 残影 |
| --- | --- | --- | --- | --- |
| 原隐藏 + 150 ms | 164.40 ms | 171.32 ms | 182.71 ms | 0/20 |
| 临时遮蔽 + DWM 同步 + 0 ms 额外等待 | 13.77 ms | 14.57 ms | 20.98 ms | 0/20 |

日志 `target/capture-timing-production.log`；前期相同策略探针为 170.30/19.05 ms 中位，日志 `target/capture-timing-cloak.log`。无法读取过渡属性的初始失败日志保留在 `target/capture-timing-before-after.log`。这些是小区域隐藏/合成/采集实测，不是按钮点击到全屏框选层可交互的端到端耗时，也不保证所有负载/显卡/显示器组合的结果。

`ScreenCaptureControllerTest` 的五项自动检查只使用合成图像和屏幕外窗口：录制路径无 150 ms Timer、后台等待时 EDT 仍能处理取消、重复取消和旧工作结果防护、模拟确认与采集异常恢复、原遮蔽值保留、普通页面旧 Timer 保留、框选层不继承遮蔽。不会真正采集桌面或发送输入。

真实鼠标验收曾启动 `CaptureDesktopAcceptance`：隔离配置、模板输出和独立纯色背景进程，录制状态为测试模拟，不注册全局 Hook。Computer Use 读取窗口时返回“Computer Use app approval timed out”；按规则重新定位一次时窗口已关闭。夹具进程退出码 0，未留下专用窗口，没有实际点击截图/框选/取消，不能报告实点通过。仍需在 Windows 11、150% DPI 实测实际截图按钮立即隐藏、冻结图像无操作栏/半透明残影、拖动框选确认及 Esc 取消恢复、正常/暂停录制各自恢复正确状态，以及截图中 F12、错误恢复、置顶/几何状态保全。工具授权恢复后可继续专用夹具验收；自动测试与上述桌面像素基准不能替代该实点清单。

最终执行：`mvn '-Dtest=ScreenCaptureControllerTest,LegacyRegressionTest#recordingPause+recordingControlInputFilter+recordingTitleBarContract' test` 实际 8 项通过；`mvn test` 实际 36 项通过，两次失败/错误/跳过均为 0、退出码 0。`mvn compile` 退出码 0。日志分别为 `target/capture-flow-tests-final.log`、`target/capture-flow-full-test-final.log` 和 `target/capture-flow-compile-final.log`。首次完整测试的 35 项记录属于添加框选层遮蔽继承回归前的中间状态，最终以 36 项报告为准。未提交或推送，原有修改与两项 AD 记录保留。
