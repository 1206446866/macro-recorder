# Qodana 正式结果逐项复核附录

对应 [主审查报告](../docs/code-quality-review-2026-10-19.md)。数据来自 2026-10-19 16:45:28 开始的 IntelliJ IDEA 本地 Qodana 运行，使用原有 `qodana.starter`，未上传 Cloud，未修改检查规则或业务代码。

原始 [qodana.sarif.json](../target/audit-2026-10-18/qodana-ide-results/qodana.sarif.json) 共 **37 条结果、13 个检查项**：23 warning（UI High）、14 note（UI Moderate）。SARIF 自报 `exitCode=0`、`executionSuccessful=true`。**这些工具等级不是项目 P1/P2，37 条也不能称为相对旧版新增的 37 个 Bug。**

原始报告 SHA-256：`D13DC13495DF82C6469418116DCA673EF4887AE45F057DC4AFF5C9435D76525F`。下表 Q-001～Q-037 与 `runs[0].results` 的 1～37 序号一致；各条原始 message、properties 和指纹保留于 [issues-index.json](../target/audit-2026-10-18/qodana-ide-results/issues-index.json)。没有修改 SARIF、建立基线或隐藏误报。

## 1. 复核结论

- Q-006、Q-007 的空指针告警：当前不变量及调用链下为误报，高置信。
- Q-031：helper 参数契约精度不足的提示，当前调用均合法，不能认定存在字体错误。
- 其余 34 条主要是 P3 维护、冗余和风格建议；其中 Q-021、Q-022 可归并为一个默认目录准备失败反馈项。
- 没有从这 37 条确认新的 P0/P1/P2 功能缺陷。主报告确认的 11 项问题来自人工审查和定向诊断，仍保留独立编号与证据。
- 以下处置来自 **Qodana 结果＋人工源码核对**，没有把复核当作真实桌面回归测试。所有建议都尚未实施。

## 2. 需明确解释的结果

### QD-01 · 两条 DataFlowIssue：当前调用链误报

**Q-006：** [ImageRecognitionFrame.hideApplicationWindows](../src/main/java/com/macro/ui/dialog/ImageRecognitionFrame.java#L751)，工具提示 `applicationWindow.setVisible(false)` 可能空指针。

- `applicationWindow` 是 final 字段（:52），构造器 :114 赋值；:744 令 `ownerWasVisible = applicationWindow != null && applicationWindow.isVisible()`。
- :750 仅在 `ownerWasVisible` 为 true 时进入，因此此处必定非空，期间没有清空该引用的路径。
- 当前识别按钮走 EDT，未找到反例。**结论：误报，高置信，不分配缺陷 P 级。**
- 建议保留。日后若要让工具更容易理解，可显式重复非空条件，但不是修复已发生的崩溃，不应为此关闭检查。
- 无需修复；可读性澄清为低风险、数分钟。如改动，只需覆盖 null owner、不可见及可见 owner 的状态逻辑，无需实际截屏。

**Q-007：** [ImageRecognitionFrame.selectResult](../src/main/java/com/macro/ui/dialog/ImageRecognitionFrame.java#L637)，工具提示 `result.similarity()` 可能空指针。

- :622 已排除空列表；:629 用 `floorMod(index, size)` 生成合法索引，随后 :630 获取结果。`getSelectedResult():700–705` 只因非法索引而返回 null。
- 列表来自 `RecognitionReport` 的 `List.copyOf(matches)`（[ScreenRecognitionService:310](../src/main/java/com/macro/recognition/ScreenRecognitionService.java#L310)），拒绝 null 元素；选择更新在 EDT，检查与取值间没有事件泵或外部回调。
- **结论：当前不变量下误报，高置信，不分配缺陷 P 级。** 不要求新增空判断掩盖模型契约。
- 若日后澄清，可在该方法使用已知有效的索引直接取列表值，保留其他调用点使用的可空 helper。低风险、数分钟；如改动，测试空列表、单/多结果、负索引及首尾循环。

### QD-02 · P3 · 两处默认目录 mkdirs 结果未反馈

**位置：** Q-021 [ImageCapturePanel.createImageFileOptions:490](../src/main/java/com/macro/ui/page/ImageCapturePanel.java#L490)，Q-022 [MacroFrame.createMacroFileOptions:1704](../src/main/java/com/macro/ui/MacroFrame.java#L1704)，规则均为 `IgnoreResultOfCall`。

**事实与影响：** 两处确实忽略 `mkdirs()` 的返回值。目录已删除、驱动器失效或没有创建权限时，用户可能只看到系统面板改用其他目录，缺少默认目录未生效的解释。源码事实高置信；未进行真实不可写目录下的面板操作，具体系统回退体验未验证。

这不是静默保存成功或数据损坏：[WindowsNativeFileDialogService.configure:153](../src/main/java/com/macro/ui/filedialog/windows/WindowsNativeFileDialogService.java#L153) 仅对有效目录设置初始文件夹；最终 [MacroStorage:68](../src/main/java/com/macro/storage/MacroStorage.java#L68) / [ImageCaptureStorage:53](../src/main/java/com/macro/storage/ImageCaptureStorage.java#L53) 再执行 `Files.createDirectories()`，失败会进入现有 UI 错误反馈。

**局部建议：** 统一处理目录准备失败的轻量反馈，并允许用户继续选择其他目录。不要把 `mkdirs()==false` 一律视为错误（目录并发创建也可能返回 false），应再次验证 `isDirectory()` 或使用明确异常的创建方式。不要因默认目录错误禁止“打开 MR”浏览其他目录。

**风险/工作量/测试：** 低风险，0.1～0.25 天，两处可以一起处理。临时目录中验证正常创建、已存在、同名普通文件和失败；原生服务仅接受有效初始目录的契约测试，加系统面板回退人工验收。可延后，不改变保存格式或文件面板架构。

### QD-03 · P3 · 冗余防御不等于图像结果异常

Q-005 [MacroPlayer.executeNode:317](../src/main/java/com/macro/player/MacroPlayer.java#L317)，规则 `ConstantValue`。

`ImageMatchResult` 是 final 类，私有构造器已拒绝 null status，因此已构造实例的 `getStatus()==null` 恒为 false。告警准确，影响仅为冗余防御，高置信。**`imageMatch==null` 仍有独立价值，不应一并删除。**

可以保持现状；若随相邻修改简化，低风险、数分钟，保留 matcher 返回 null 时的失败及清理测试。没有必要仅为这一条更改播放器。

### QD-04 · 待验证/可接受契约提示 · Font style 参数

Q-031 [ActionTimelinePanel.label:989](../src/main/java/com/macro/ui/ActionTimelinePanel.java#L989)，规则 `MagicConstant`。

helper 的 `int style` 传给 `Font.deriveFont`，工具无法从参数声明证明允许值；当前全部调用使用 `Font.PLAIN` 或 `Font.BOLD`。没有非法样式输入的证据，不能称为已确认字体缺陷。调用核对置信高；未来新增调用的契约风险仅为低影响。

保持当前实现即可；触碰 helper 时可以增强参数表达，但不为一条提示增加注解依赖或万能样式工具。不改无需新增测试；若改，可用 PLAIN/BOLD/ITALIC 的小型组件测试。风险低、数分钟。

## 3. 其他 P3 与可接受取舍

下列各组均保留原始结果，建议允许不修。风险/工作量是“选择修改时”的粗估，不代表安排了重构任务。

| 组号 / 结果 | 检查项与具体依据 | 影响、置信与建议 | 风险 / 工作量 / 是否需要新增测试 |
| --- | --- | --- | --- |
| QD-05 / Q-001～004 | `ClassCanBeRecord`：SelectorIcon、LineIcon、MessageIcon、CloseIcon | P3，高置信。不可变普通类正常，改 record 只减少样板；保留现有图标实现 | 低风险，0.1 天以内；不改无需测试，若改仅检查图标尺寸与绘制 |
| QD-06 / Q-008～010 | `DuplicateBranchesInSwitch`：按键别名的 SPACEBAR/空格、RETURN/回车/返回、BACKSPACE/退格映射 | P3，高置信。相同返回值有意表达别名，合并 case 可选，不是按键编码错误 | 低风险，数分钟；不改无需测试，若改复用现有按键解析用例 |
| QD-07 / Q-011～017 | `ExtractMethodRecommender`：选框控制柄坐标、延迟框、窗口提示图标、COM flags、轨迹桥接步数、坐标区和 Spinner 编辑器样式 | P3，高置信。方法划分建议没有证明功能缺陷；当前不应为了提取方法改变焦点、轨迹、COM 或样式流程 | 低至中风险，约 0.25～0.5 天全部处理；不改无需新增测试，触碰哪项就运行相关现有回归，绘制变化才需人工验证 |
| QD-08 / Q-018～020 | `FieldCanBeLocal`：MacroRecorder.targetWindow、MacroFrame.imageCapturePanel、ScreenSelectionOverlay.screenBounds | P3，高置信。字段局部化收益有限；Recorder 字段带 Lombok Setter，删除可能同时删生成访问入口，不能盲目批量快修 | 低至中风险，0.1～0.25 天；先查生成 API 调用；不改无需测试，若改复用初始化/录制过滤检查 |
| QD-09 / Q-023～024 | `InnerClassMayBeStatic`：SettingsSidebar、NodePanel | P3，高置信。可表达更小依赖，但未发现外部长期持有导致泄漏；Sidebar 回调本身仍连接窗口，static 不等于消除所有父引用 | 低风险，约 0.1 天；不改无需新增测试，若改跑设置导航和时间线组件检查 |
| QD-10 / Q-025～030 | `LombokGetterMayBeUsed`：异常、识别结果、消息类型和 DWM 状态 getter | P3，高置信。手写 getter 正确，保留更清晰；不要扩大自动生成到 `getMatchBounds()`，必须保持防御复制 | 无需修改；若只换简单 getter，低风险、0.1 天；保持矩形防御复制测试，不重新设计 DWM |
| QD-11 / Q-032 | `PointlessBitwiseExpression`：时间戳展开 `~(TICK_WRAP - 1)` | P3，高置信。与 `-TICK_WRAP` 等价，现写法清楚表达清低 32 位；不是过滤错误 | 无需修改；替换低风险、数分钟，复用时间回绕测试 |
| QD-12 / Q-033～035 | `ThrowablePrintStackTrace`：临时文件清理、逐项键鼠释放失败 | P3，高置信。异常有记录，继续其他清理有价值；不应删除日志、改成再次抛出或为此引入日志框架 | 无需修改；如统一诊断约 0.25 天、低至中风险，保持失败时其余输入仍释放的测试 |
| QD-13 / Q-036～037 | `UnusedAssignment`：最后一次 row++、DWORD 初始值随后覆盖 | P3，高置信。旧 row 值仍正确赋给 gridy；DWORD 初始化冗余不是 JNA 大小/布局错误 | 低风险、数分钟；不改无需新增测试，若改保持布局检查和现有内存监控路径，不改结构体顺序或类型 |

## 4. 范围及工具限制

工程模型登记主/测试源码根，排除构建和个人素材目录；SARIF 没有逐文件/逐规则完成记录，37 条都来自主代码，不能从测试目录零告警推导测试覆盖率。报告未提供覆盖率文件，也无可用历史基线。

IDE 日志有 `Security Analysis by Qodana` 对 generated TargetWindow 的非法路径字符异常；SARIF 没有对应 tool error 通知，仍报告运行成功。保留为插件兼容性/覆盖限制，不能用来认定整次扫描失败，也不能保证安全专项完整。未关闭检查来掩盖它。

正式报告 [工程结构](../target/audit-2026-10-18/qodana-ide-results/projectStructure/Modules.json)、[原配置副本](../target/audit-2026-10-18/qodana-ide-results/qodana-config-used.yaml)、[IDE 日志摘录](../target/audit-2026-10-18/qodana-ide-results/ide-log-excerpt.txt) 均在本地审查目录。原始数据未上传，未进行快修。

## 5. 全部 37 条原始位置与处置索引

以下表中的序号与原始报告严格对应。每条的完整 message、指纹和其他 SARIF 属性见上述 JSON 索引；误报仍保留条目，不使用 suppression 隐藏。

| 序号 | 原始规则 / level | 文件与行号 | 人工处置 | 指纹 equalIndicator/v2 |
| --- | --- | --- | --- | --- |
| Q-001 | `ClassCanBeRecord` / note | [ui/WindowSelectorPanel.java:406](../src/main/java/com/macro/ui/WindowSelectorPanel.java#L406) | QD-05：P3，可接受取舍 | `411c622153965d70` |
| Q-002 | `ClassCanBeRecord` / note | [ui/MacroFrame.java:1879](../src/main/java/com/macro/ui/MacroFrame.java#L1879) | QD-05：P3，可接受取舍 | `49b0926da2966966` |
| Q-003 | `ClassCanBeRecord` / note | [ui/dialog/AppMessageDialog.java:541](../src/main/java/com/macro/ui/dialog/AppMessageDialog.java#L541) | QD-05：P3，可接受取舍 | `dc39c72fa0c2b048` |
| Q-004 | `ClassCanBeRecord` / note | [ui/dialog/AppMessageDialog.java:598](../src/main/java/com/macro/ui/dialog/AppMessageDialog.java#L598) | QD-05：P3，可接受取舍 | `484b7da59cfc4faf` |
| Q-005 | `ConstantValue` / warning | [player/MacroPlayer.java:317](../src/main/java/com/macro/player/MacroPlayer.java#L317) | QD-03：P3，冗余防御 | `511181dd42c427fd` |
| Q-006 | `DataFlowIssue` / warning | [ui/dialog/ImageRecognitionFrame.java:751](../src/main/java/com/macro/ui/dialog/ImageRecognitionFrame.java#L751) | QD-01：当前调用链误报 | `0b8f1dede084f5ae` |
| Q-007 | `DataFlowIssue` / warning | [ui/dialog/ImageRecognitionFrame.java:637](../src/main/java/com/macro/ui/dialog/ImageRecognitionFrame.java#L637) | QD-01：当前调用链误报 | `5c7595f02b647126` |
| Q-008 | `DuplicateBranchesInSwitch` / note | [ui/ActionEditorDialog.java:613](../src/main/java/com/macro/ui/ActionEditorDialog.java#L613) | QD-06：P3，按键别名 | `38e65d5b179c5e65` |
| Q-009 | `DuplicateBranchesInSwitch` / note | [ui/ActionEditorDialog.java:603](../src/main/java/com/macro/ui/ActionEditorDialog.java#L603) | QD-06：P3，按键别名 | `caa77fdd8c179172` |
| Q-010 | `DuplicateBranchesInSwitch` / note | [ui/ActionEditorDialog.java:606](../src/main/java/com/macro/ui/ActionEditorDialog.java#L606) | QD-06：P3，按键别名 | `8b94159ac0da0d9a` |
| Q-011 | `ExtractMethodRecommender` / note | [ui/capture/ScreenSelectionOverlay.java:324](../src/main/java/com/macro/ui/capture/ScreenSelectionOverlay.java#L324) | QD-07：P3，可选提取 | `006237b5d2a6a7fb` |
| Q-012 | `ExtractMethodRecommender` / note | [ui/ActionTimelinePanel.java:538](../src/main/java/com/macro/ui/ActionTimelinePanel.java#L538) | QD-07：P3，可选提取 | `4ead66b19e15992a` |
| Q-013 | `ExtractMethodRecommender` / note | [ui/WindowSelectorPanel.java:140](../src/main/java/com/macro/ui/WindowSelectorPanel.java#L140) | QD-07：P3，可选提取 | `354d146c091c9cb5` |
| Q-014 | `ExtractMethodRecommender` / note | [ui/filedialog/windows/WindowsNativeFileDialogService.java:143](../src/main/java/com/macro/ui/filedialog/windows/WindowsNativeFileDialogService.java#L143) | QD-07：P3，可选提取 | `6806ea6b17412215` |
| Q-015 | `ExtractMethodRecommender` / note | [recorder/MacroRecorder.java:460](../src/main/java/com/macro/recorder/MacroRecorder.java#L460) | QD-07：P3，可选提取 | `7486ae728495f786` |
| Q-016 | `ExtractMethodRecommender` / note | [ui/ActionTimelinePanel.java:446](../src/main/java/com/macro/ui/ActionTimelinePanel.java#L446) | QD-07：P3，可选提取 | `d3bf25f1fefbc4d6` |
| Q-017 | `ExtractMethodRecommender` / note | [ui/util/ComponentStyleUtil.java:204](../src/main/java/com/macro/ui/util/ComponentStyleUtil.java#L204) | QD-07：P3，可选提取 | `a46922786908e8ea` |
| Q-018 | `FieldCanBeLocal` / warning | [recorder/MacroRecorder.java:85](../src/main/java/com/macro/recorder/MacroRecorder.java#L85) | QD-08：P3，可选局部化 | `173fd183f094d984` |
| Q-019 | `FieldCanBeLocal` / warning | [ui/MacroFrame.java:101](../src/main/java/com/macro/ui/MacroFrame.java#L101) | QD-08：P3，可选局部化 | `fb71aded6ea3e2f7` |
| Q-020 | `FieldCanBeLocal` / warning | [ui/capture/ScreenSelectionOverlay.java:24](../src/main/java/com/macro/ui/capture/ScreenSelectionOverlay.java#L24) | QD-08：P3，可选局部化 | `2ab21f12ee4185a0` |
| Q-021 | `IgnoreResultOfCall` / warning | [ui/page/ImageCapturePanel.java:490](../src/main/java/com/macro/ui/page/ImageCapturePanel.java#L490) | QD-02：P3，默认目录反馈 | `f077da97488e530c` |
| Q-022 | `IgnoreResultOfCall` / warning | [ui/MacroFrame.java:1704](../src/main/java/com/macro/ui/MacroFrame.java#L1704) | QD-02：P3，默认目录反馈 | `d50ce2659e45d4ef` |
| Q-023 | `InnerClassMayBeStatic` / warning | [ui/SettingsDialog.java:342](../src/main/java/com/macro/ui/SettingsDialog.java#L342) | QD-09：P3，未证实泄漏 | `1f1bb4b20ebc4d29` |
| Q-024 | `InnerClassMayBeStatic` / warning | [ui/ActionTimelinePanel.java:1085](../src/main/java/com/macro/ui/ActionTimelinePanel.java#L1085) | QD-09：P3，未证实泄漏 | `4f99ec18284b69c4` |
| Q-025 | `LombokGetterMayBeUsed` / warning | [exception/MacroFormatException.java:4](../src/main/java/com/macro/exception/MacroFormatException.java#L4) | QD-10：P3，手写 getter 合理 | `f152451a62e8c772` |
| Q-026 | `LombokGetterMayBeUsed` / warning | [flow/ImageMatchResult.java:69](../src/main/java/com/macro/flow/ImageMatchResult.java#L69) | QD-10：P3，手写 getter 合理 | `871e639961404294` |
| Q-027 | `LombokGetterMayBeUsed` / warning | [ui/dialog/MessageType.java:4](../src/main/java/com/macro/ui/dialog/MessageType.java#L4) | QD-10：P3，手写 getter 合理 | `08bb170f8c348b4b` |
| Q-028 | `LombokGetterMayBeUsed` / warning | [ui/window/WindowsRecordingTitleBar.java:208](../src/main/java/com/macro/ui/window/WindowsRecordingTitleBar.java#L208) | QD-10：P3，手写 getter 合理 | `dea1058346408a73` |
| Q-029 | `LombokGetterMayBeUsed` / warning | [ui/window/WindowsRecordingTitleBar.java:217](../src/main/java/com/macro/ui/window/WindowsRecordingTitleBar.java#L217) | QD-10：P3，手写 getter 合理 | `e5f78ca553d273cd` |
| Q-030 | `LombokGetterMayBeUsed` / warning | [flow/ImageMatchResult.java:79](../src/main/java/com/macro/flow/ImageMatchResult.java#L79) | QD-10：P3，手写 getter 合理 | `532136a04b22ac2b` |
| Q-031 | `MagicConstant` / warning | [ui/ActionTimelinePanel.java:989](../src/main/java/com/macro/ui/ActionTimelinePanel.java#L989) | QD-04：参数契约提示，非确认缺陷 | `f17b31b2164a72ba` |
| Q-032 | `PointlessBitwiseExpression` / warning | [recorder/RecordingControlInputFilter.java:156](../src/main/java/com/macro/recorder/RecordingControlInputFilter.java#L156) | QD-11：P3，等价位掩码 | `9242db5d3a8d5e1d` |
| Q-033 | `ThrowablePrintStackTrace` / warning | [storage/MacroStorage.java:111](../src/main/java/com/macro/storage/MacroStorage.java#L111) | QD-12：P3，保留清理诊断 | `4472b10c2b41574f` |
| Q-034 | `ThrowablePrintStackTrace` / warning | [player/MacroPlayer.java:638](../src/main/java/com/macro/player/MacroPlayer.java#L638) | QD-12：P3，保留清理诊断 | `a1ed4c81d30f5553` |
| Q-035 | `ThrowablePrintStackTrace` / warning | [player/MacroPlayer.java:649](../src/main/java/com/macro/player/MacroPlayer.java#L649) | QD-12：P3，保留清理诊断 | `e2e162c33c97b535` |
| Q-036 | `UnusedAssignment` / warning | [ui/MacroFrame.java:315](../src/main/java/com/macro/ui/MacroFrame.java#L315) | QD-13：P3，轻微冗余 | `362357c413ee7ee9` |
| Q-037 | `UnusedAssignment` / warning | [recognition/ProcessMemoryMonitor.java:129](../src/main/java/com/macro/recognition/ProcessMemoryMonitor.java#L129) | QD-13：P3，轻微冗余 | `34eb0e633249ea97` |
