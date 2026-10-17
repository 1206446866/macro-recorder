# 原生文件面板接入与验收

文件选择统一调用 `FileDialogService.showDialog(parent, options)`。确认返回 `Optional<File>`，取消返回空结果；平台不支持或原生调用失败抛出 `FileDialogException`，由已有 `AppMessageDialog` 显示。

## 类与边界

新增生产类位于 `src/main/java/com/macro/ui/filedialog/`：

- `FileDialogService`：业务层接口。
- `FileDialogOptions`：不可变配置，包含用途、标题、初始目录、文件名、过滤器、默认扩展名及覆盖提示选项。
- `FileDialogServiceFactory`：读取 `os.name`，Windows 返回原生服务；其他平台在调用时明确报错。
- `FileDialogException`：区分取消与调用失败。
- `windows/WindowsNativeFileDialogService`：唯一的平台实现，封装全部 JNA/COM 调用。

已接入的业务类：`MacroFrame`、`ActionEditorDialog`、`SettingsDialog`、`ImageCapturePanel`、`ImageRecognitionFrame`。

`ComponentStyleUtil` 保留按钮、输入框、下拉框、Spinner、CheckBox 和滚动条通用样式，只移除文件选择器专用代码。已删除没有其他调用者的 `FileChooserFileActions`。生产源码没有 `JFileChooser`、Swing 文件面板备用实现或 `fileDialog.strategy` 配置。

## Windows 调用方式

- 打开文件及选择目录：创建 `CLSID_FileOpenDialog`，请求 `IFileOpenDialog`。
- 保存文件：创建 `CLSID_FileSaveDialog`，请求 `IFileSaveDialog`。
- 目录选择使用 `FOS_PICKFOLDERS`，文件结果限制为真实文件系统路径。
- 每次调用使用专属 STA 线程，先 `CoInitializeEx`，结束后释放 Shell 对象、对话框与返回路径内存，再 `CoUninitialize`。
- 父窗口 HWND 在 Swing EDT 上取得；STA 调用 `Show(HWND)`，EDT 通过嵌套事件循环继续处理重绘。
- `HRESULT_FROM_WIN32(ERROR_CANCELLED)` 转为空结果；其他失败返回明确异常，不回退。
- 不修改工作目录，不启用多选，不引入新依赖。

接口依据：[Microsoft Common Item Dialog](https://learn.microsoft.com/en-us/windows/win32/shell/common-file-dialog)。

## 业务保持与覆盖提示

| 场景 | 保留行为 |
|---|---|
| 打开 MR | 使用默认宏目录及 MR 过滤器，保留原有加载及版本检查 |
| 保存已有 MR | 继续直接保存到 currentFile，不弹选择面板 |
| 保存新 MR / 另存为 | 使用默认目录或 currentFile 所在目录；保留原有 `.mr` 后缀补充；使用原生覆盖提示 |
| PNG 模板 | 保留 PNG 扩展名及 ImageIO 校验 |
| 截图保存 | 默认保存流程不变；另存为保留 PNG 文件名、目录及已有覆盖确认，因此关闭原生重复覆盖提示 |
| 识别测试图片 | 保留 PNG/JPG/JPEG/BMP/GIF/WebP 过滤器及最近目录 |
| 两个默认目录 | 选择结果只更新设置输入框，仍由设置窗口保存配置 |

如果业务层补充 `.mr` 后缀后，最终路径与原生面板返回的路径不同且已存在，会针对最终路径补一次覆盖确认，避免原生面板只确认了另一个文件名。

## 已执行验证

- Maven 干净构建及编译。
- `FileDialogServiceTest`：平台选择、非 Windows 明确报错、配置不可变性、默认路径及过滤器。
- `WindowsNativeFileDialogContractTest`：真实 COM 对象的创建/释放，原生打开/保存/目录选项、覆盖开关、中文目录与文件名往返；不显示或操作桌面窗口。
- `ImageCheckEditorTest`：通过接口注入确认/取消，保留原有插入、九种行为编辑和时间线刷新测试。
- `ImageCheckBehaviorTest`、`MacroStorageFailureTest`：原有播放行为、暂停/停止、持久化及保存失败时原文件保护回归通过。
- 已实际显示原生打开面板，读取到 Shell 文件列表、文件类型和初始文件名；带父窗口时，父窗口显示为禁用状态。

桌面自动化工具点击原生面板时返回 `coordinate input geometry is unavailable`，键盘输入也没有可确认的结果。因此不将真实点击确认、取消或最终磁盘写入列为已通过的自动化验收。

## 需人工复核

1. 主页加载 MR、保存新 MR、保存已有 MR、另存为，检查当前文件状态及实际文件内容。
2. 输入没有扩展名和带其他扩展名的文件名，确认最终 `.mr` / `.png` 文件名与已有规则一致。
3. 同名覆盖的确认、拒绝及取消，截图另存为应只出现一次应用覆盖确认。
4. 模板选择、识别测试图片加载，以及两个默认目录的选择和配置保存。
5. 按取消、Esc、右上角关闭，确认没有后续文件操作，父窗口重新可用。
6. 从模态动作编辑器和设置窗口打开原生面板，检查焦点、层级、关闭后的键盘操作。
7. Windows 11 的 150% 缩放、中文/空格路径、新建目录及切换目录。

保留了 `NativeFileDialogSmokeTest`，可独立展示打开、保存、目录、PNG、覆盖和取消六个面板。该测试只选择路径，不执行被选中文件的保存或覆盖。

## 兼容边界

当前仅实现 Windows。macOS、Linux 会明确提示尚不支持，未来可增加原生实现并由工厂选择。文件面板外观、DPI 与文件管理交互由操作系统负责，不使用项目 Swing 主题绘制。

UNC/网络共享、特别长的路径、受限目录及跨显示器 DPI 切换尚未完成交互验收，需在对应环境复核。
