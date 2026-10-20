# Maven 自动回归测试

本次仅解决 TEST-01：现有独立检查未被 Maven 实际执行。没有修改录制、播放、图像识别、持久化或配置的生产逻辑，原有 16 个测试主程序也保留原样。

## 运行

在项目根目录、JDK 17 和 Maven 环境中执行：

```powershell
mvn test
mvn test '-Djava.awt.headless=true'
```

当前提供 22 个标准 JUnit 测试入口：8 个旧检查适配入口（原审查中的七个，加时间线无窗口检查）、1 个录制坐标回归入口、2 个播放期间时间线保护入口、1 个流程预检入口、2 个配置安全入口、1 个播放延迟边界入口、1 个暂停快捷键去重入口、1 个 OpenCV 满分阈值入口、1 个 Java 回退结果入口、2 个模板路径入口及 2 个运行机制检查。Windows 正常桌面环境应能运行全部 22 个；无桌面时图像分支、实际播放器桌面检查、计数 Robot 流程预检、播放延迟边界、暂停快捷键及模板编辑器/实际播放器检查明确跳过，非 Windows 平台还会跳过对应原生检查。OpenCV 满分检查强制 headless，但需要依赖中可加载的本机 OpenCV 运行库；Java 回退及模板路径无窗口检查同样强制 headless，不加载原生匹配库。实际数量以该次 Surefire 报告为准，不把 skipped 当成通过。

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

## 全部 16 个现有测试的代码盘点

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
