# Macro Recorder 1.1.0

Macro Recorder 是一个基于 Java 的 Windows 键鼠宏录制与播放工具。它支持键鼠动作录制、横向动作时间线、循环播放、暂停与停止，以及基于 OpenCV 的图像识别流程节点。界面使用 Swing 和统一的深色主题，文件与目录选择使用 Windows 原生文件面板。

当前软件版本为 **1.1.0**，以 [`ApplicationInfo.APP_VERSION`](src/main/java/com/macro/constant/ApplicationInfo.java) 为准，由维护者决定升级；[`pom.xml`](pom.xml) 的 Maven 数字版本与其同步，数字版本一致时保留已有后缀。`.mr` 文件格式版本由 `MacroFormat` 独立维护，不随软件版本变更。近期变化见 [更新日志](CHANGELOG.md)，项目状态和文档入口见 [项目状态索引](docs/README.md)。

## 主要功能

- **录制**：鼠标移动、左/中/右键按下/释放、滚轮、键盘按下/释放；支持设置鼠标移动采样间隔及指定目标窗口；X1/X2 侧键不提供编辑入口，录制时忽略。
- **录制控制栏**：主窗口切换为自绘双行操作栏，提供目标信息、截图、暂停/继续、终止和自绘最小化；保留 DWM 外框、水平缩放、固定高度、位置与宽度记忆及录制置顶，停止后恢复完整窗口。原有开始录制自动最小化设置继续生效。
- **播放**：指定循环次数或无限循环，暂停、继续、停止；停止和异常结束时释放已按下的键鼠。
- **时间线**：横向滚动查看动作、选中节点、双击编辑、右键插入/删除、Delete 删除；支持坐标/延迟行内编辑、动态操作编号，以及按真实目标 ID 绘制的远距离水平入卡分支线。
- **动作编辑**：在同一个编辑器中插入或编辑动作类型和属性，键盘代码由按键名称生成且只读。
- **图像采集**：框选截图、取消选区、预览、剪贴板图片粘贴、图片拖入、保存 PNG 及另存为。
- **图像识别**：向时间线插入图像识别节点，配置模板 PNG、匹配阈值、重试延迟，以及成功/失败时的独立行为。
- **识别测试窗口**：从图像识别动作中打开，自动载入当前模板及阈值，使用 OpenCV 观察相似度、位置、多结果、耗时及内存统计。
- **录制中图像操作**：录制时临时框选模板并生成图像识别节点，采集交互不录入普通动作；排除采集耗时，并按录制设置补充鼠标移动桥接。
- **设置与提示**：默认保存目录、采样及窗口最小化/恢复设置，统一深色控件和可展开异常详情的提示。

## 技术栈与环境要求

| 项目 | 使用内容 |
|---|---|
| 操作系统 | Windows 11；当前原生窗口及文件面板实现面向 Windows |
| Java | **JDK 17**，构建与打包需要完整 JDK |
| 构建 | Maven；同时保留 Maven Wrapper |
| 界面 | Swing、自绘组件、统一 `ThemeManager` / `ThemePalette`；项目包含 FlatLaf 依赖 |
| 全局键鼠事件 | JNativeHook |
| Windows 接口 | JNA / JNA Platform |
| 图像匹配 | OpenCV（OpenPnP Java 绑定及本机库） |
| 数据与代码生成 | Jackson、Lombok |
| 图片导入 | Java ImageIO、TwelveMonkeys WebP 支持 |

首次构建需要联网下载 Maven/依赖；首次使用 Wrapper 时还需要下载其指定的 Maven 分发包。IDE 运行时请启用 Lombok 注解处理。

## 构建与启动

以下命令在项目根目录的 PowerShell 中执行。

### 编译与打包

已有 Maven 时：

```powershell
mvn -q -DskipTests compile
mvn -q -DskipTests package
```

也可以使用仓库中的 Wrapper，无需预先安装 Maven：

```powershell
.\mvnw.cmd -q -DskipTests package
```

`mvnw`、`mvnw.cmd` 和 `.mvn/wrapper/maven-wrapper.properties` 是正式构建文件，需一并保留。当前 Wrapper 为 `only-script` 模式，不需要额外提交 Wrapper JAR。

打包生成 `target/recorder-<Maven版本>.jar` 和 `target/lib/` 依赖目录。该 JAR 不是包含全部依赖的单文件发行包。

### 从源码启动

完成 `package` 后：

```powershell
java -cp "target/classes;target/lib/*" com.macro.MacroApplication
```

也可在 IDE 中运行 `com.macro.MacroApplication.main()`。普通启动会在后台预加载 OpenCV。

### Windows 应用打包

环境中需要可用的 `mvn`、JDK 17 的 `jpackage` 和 PowerShell：

```powershell
.\build.bat
# 在终端或自动化环境中取消脚本结束时的暂停：
.\build.bat --no-pause
```

脚本依次读取 Maven 版本、构建项目、生成携带 Java 运行时的应用目录，最后压缩为：

```text
dist/
├── MacroRecorder/
│   └── MacroRecorder.exe
└── MacroRecorder <Maven版本>.zip
```

使用时保留完整应用目录，启动其中的 `MacroRecorder.exe`。打包脚本会重新生成 `dist/MacroRecorder/` 和同版本 ZIP，不要把个人数据存入构建输出目录；请在解压后的独立目录运行发行包。

## 基本使用

1. 点击“开始录制”，或先“指定窗口开始”，再操作目标程序。
2. 录制完成后按 **F12** 停止。
3. 点击当前宏卡片底部的动作/节点数量区域进入时间线，检查或编辑动作。
4. 设置播放循环次数或勾选无限循环，点击“播放”。
5. 播放时可按 **F11** 暂停/继续，按 **F12** 停止。

录制期间按 **F10** 或点击“图像操作”进入冻结截图选区；确认后恢复录制，取消则放弃本次采集。截图选区支持 **Esc** 取消。F12 在采集期间仍用于停止录制。

指定目标窗口用于定位窗口级操作；浏览器标签页不是独立窗口。录制和播放会向真实桌面发送输入，请先用简单动作确认目标及坐标。

## 宏文件与个人配置

- 默认宏目录为程序目录下的 **`save/`**；可在设置中修改。
- “保存 MR”用于保存当前宏，已有文件时沿用当前文件；“另存为”选择新位置。文件名会按现有规则补充 `.mr`。
- 非空流程在任何写入前校验；删除跳转目标后保存会报错，不能覆盖原有效文件，也不会自动改到其他节点。非空但缺失的模板路径仍可存储；侧键按下/释放数据在保存、加载及播放前拒绝。
- “加载 MR”读取普通动作及流程节点；默认可以在启动时加载上次使用的宏，此选项可在设置中关闭。
- `.mr` 使用 JSON，保存动作、目标窗口和可选流程图。格式版本与软件版本分别维护；不支持的格式会提示版本不兼容。
- 图像节点保存**模板文件路径引用和匹配参数**，不内嵌 PNG/Base64。相对模板路径以 MR 文件所在目录解析。移动或分享宏时，需同时保留模板文件及对应路径关系；旧版仅包含普通动作的 MR 仍可加载。
- `config.properties` 保存个人目录、上次宏路径和运行设置，首次启动会自动创建，无需预先提供。可选示例见 [`config.example.properties`](config.example.properties)。
- 默认截图目录为程序目录下的 **`ScreenCapture/`**，也可在设置中修改。

开发运行时，程序目录通常是项目根目录；jpackage 发行包使用 EXE 所在目录。以上个人文件、构建产物与诊断截图均由 `.gitignore` 排除，仓库不包含你的实际配置、宏或截图素材。

## 图像识别流程

### 采集模板

在主页点击“采集图像”，点击“截图”框选目标，确认后保存为 PNG。也可以粘贴剪贴板中的图片或拖入支持的图像；普通文本不会作为图片采集。

### 插入识别节点

1. 进入动作时间线，右键节点选择“插入动作”，新节点插在选中节点之前。
2. 选择“图像识别”，指定有效的模板 PNG，并设置匹配阈值和重试延迟。
3. 分别配置“成功行为”和“失败行为”，保存后播放宏。

| 行为 | 执行结果 |
|---|---|
| 重试 | 等待重试延迟后，重新识别当前节点；支持暂停、继续和停止 |
| 执行 | 继续执行时间线中的下一个节点 |
| 终止 | 结束整个宏播放任务，包括无限循环 |
| 跳转到指定节点 | 从当前时间线选择前方或后方目标；成功/失败独立配置，仅 JUMP 时启用对应目标菜单 |

旧宏缺少行为配置时，默认**成功执行、失败重试**。时间线显示主链、自回路、终止标记及指定跳转连线；操作编号只用于展示，不能代替节点 ID。含 JUMP 的新宏需要支持该行为的应用版本。实现细节见 [图像分支与时间线](docs/image-branch-jumps.md)。

正式播放的 `IMAGE_CHECK` 通过 `OpenCvImageMatcher` 调用现有屏幕捕获和 OpenCV 模板匹配实现，结果保留相似度与匹配矩形。普通键鼠动作仍由 `MacroAction` 执行；识别节点不会被混入底层输入动作类型。

### 独立测试窗口

在动作编辑窗口的“图像识别”属性中点击“识别”，打开独立测试窗口并载入当前模板和阈值。点击“识别当前屏幕”后，程序临时隐藏自身窗口，再使用 OpenCV 识别并恢复窗口；支持观察多结果、位置及性能数据，未达到阈值时显示未匹配提示。

测试窗口可选隐藏应用窗口，实际播放则受播放设置、目标窗口激活和前台遮挡影响。两者参与匹配的桌面画面可能不同，测试命中不保证播放时同样命中。

### 诊断

播放和测试路径保留识别诊断日志。可在 `java` 命令的 `-cp` 之前增加：

```text
-Dmacro.image.diagnostics.enabled=false
```

诊断截图默认关闭；仅排查时开启 `-Dmacro.image.diagnostics.capture=true`。默认输出到系统临时目录的 `MacroRecorder-image-diagnostics/`，可通过 `-Dmacro.image.diagnostics.directory=<目录>` 指定其他位置。诊断图片可能包含屏幕内容，不属于项目正式资源。

## Windows 原生文件面板

所有业务界面通过 `FileDialogService` 选择文件或目录。Windows 实现使用 JNA 接入 Shell Common Item Dialog：

- `IFileOpenDialog`：打开 MR、选择模板和测试图片；目录选择使用 `FOS_PICKFOLDERS`。
- `IFileSaveDialog`：保存新宏、另存为及截图另存为。
- 已有宏的直接保存、截图默认保存继续沿用各自原有逻辑。
- 取消、Esc 或关闭文件面板后，不执行后续文件操作。
- 文件面板外观和文件管理操作由 Windows 提供，不使用 Swing `JFileChooser`，也没有 Swing 回退策略。

接口、线程和人工验收范围见 [`docs/native-file-dialogs.md`](docs/native-file-dialogs.md)。

## 开发验证

安全回归检查已通过轻量 JUnit 入口接入 Maven Surefire。在项目根目录执行：

```powershell
mvn test
# 无桌面环境：图像分支旧测试因 Robot 构造限制明确跳过，其余适用检查继续执行。
mvn test '-Djava.awt.headless=true'
```

默认入口覆盖标准 JUnit 检查及已审查的旧测试主程序；Windows/COM 与桌面依赖会在 JUnit 中明确报告跳过。旧式检查使用独立 JVM 和 `target/test-sandboxes/` 下新建的应用目录，避免 `AppConfig` 读写个人配置；失败返回 Surefire，未发现测试时构建失败。测试报告位于 `target/surefire-reports/`，实际执行结果见 [自动测试记录](docs/automated-tests.md)。

现有独立 `main()` 入口仍然保留。会显示窗口、实际截图、注册全局钩子或等待用户操作的检查不进入默认自动运行，也不能直接在个人配置目录中批量启动。全部测试的副作用盘点、自动范围、隔离原理和人工验证边界见 [`docs/automated-tests.md`](docs/automated-tests.md)。

数字输入规则和范围见 [`docs/numeric-input-rules.md`](docs/numeric-input-rules.md)。
录制控制栏的状态、过滤机制及验证范围见 [`docs/recording-control-bar.md`](docs/recording-control-bar.md)。

## 当前已知限制

- 当前只提供 Windows 原生实现；macOS/Linux 文件面板明确报不支持，没有跨平台备用界面。
- 图像匹配为模板识别，不是 OCR 或语义识别；界面缩放、主题、抗锯齿、遮挡和目标外观变化都可能影响命中。没有提供多比例和搜索区域编辑。
- 纯 Java 与 OpenCV 的评分方式不同，相同百分比阈值不可视为完全等价；纯 Java 对比路径可能更慢且存在误匹配，正式播放使用 OpenCV。
- 极小、纯色、低纹理或重复图案可能产生大量候选；测试窗口限制显示数量。内存数据包含采样和估算，不是精确的原生分配追踪。
- 识别依赖当前可见桌面，不读取被遮挡、最小化或受保护窗口的隐藏画面；多显示器和混合 DPI 仅有基础实现，尚未完整验收。
- 成功匹配会保存匹配区域，但尚未提供“在图像中心/随机位置点击”的完整编辑能力。
- 路径引用的模板不是可自动搬移的资源包；重试没有配置超时或次数上限，应确保能够停止或终止。
- 原生面板的特别长路径、网络共享及跨显示器 DPI 切换仍需实机复核；部分 UI 场景也需在 Windows 11 的 150% 缩放下人工确认。
- 软件版本由维护者修改 `ApplicationInfo.APP_VERSION`，Maven 同步数字版本并保留后缀；本版数字版本均为 `1.1.0`，`.mr` 格式版本另行管理。

仓库保留当前源码和正式资源，不包含旧源码压缩包、个人运行数据或 Windows 打包产物。
