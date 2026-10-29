# 离线文字提取（1.2.0）

在“采集图像”页框选、粘贴或导入图片后，点击“提取文字”。弹窗打开时不自动识别；选择语言后点击提取。支持简体中文、繁体中文、英语、日语、韩语，可多选，默认简体中文和英语。只选择图片实际包含的语言通常更快，也有助于减少误识别；单行图片可勾选“单行文字”。

提取结果可编辑、复制或保存为 UTF-8 TXT。保存使用原有 Windows 文件面板，覆盖现有文件需确认；取消选择不写文件。OCR 不修改原图片或 MR，不注册键鼠监听，不上传内容，也不进行语义推理或自动执行文字判断、点击。

图像动作的独立识别测试窗口另有右侧“识别文字”：窗口显示后自动提取当前模板图片，默认同时使用上述五种语言，不扫描屏幕文字。没有文字显示“无信息”，有结果可复制；失败可重新提取。文字区只读，与采集页的可编辑提取弹窗分别使用。隐藏的编辑预览准备阶段不启动 OCR；更换或清除模板、关闭窗口会取消旧请求，旧原生调用收尾后只处理最新模板，过时结果不回填。

## 运行与缓存

目前支持 Windows x64，复用现有 JNA，直接调用 Tesseract 5.3.4 和 Leptonica 1.84.1 的本机接口，未引入完整 Tess4J、PDF 或其他平台依赖。五种 `tessdata_fast` 模型随程序一起提供，运行时无联网下载。

资源保存在 `src/main/resources/ocr/`，分发后在应用 JAR 中；首次使用按清单校验后展开到**程序目录的 `ocr-cache/runtime-<清单指纹>/`**。本项目开发与测试的模型、本机库和隔离缓存都位于 D 盘；程序移动到其他磁盘后，默认缓存跟随程序目录。它不使用系统临时目录作为 OCR 默认缓存。开发诊断可用 `-Dmacro.ocr.cacheDirectory=<目录>` 指定缓存位置。

缓存复用前核对大小与 SHA-256，损坏或不完整缓存会保留，改用同一父目录下的新目录，不覆盖原文件。程序目录需有写权限；模型路径还需可由 Windows 当前系统代码页或可用的短路径表示。当前仅打包已验证的 Windows x64 本机组件，其他平台明确报不支持。

本次资源测量为原始 **20,403,592 字节（约 19.5 MiB，含来源说明）**，其中五种模型 13,097,561 字节；对资源目录单独 ZIP 压缩为 **11,542,133 字节（约 11.0 MiB）**。首次展开还需约 19.5 MiB；这些是资源测量，未重新生成 ZIP/EXE，不能视为完整安装包增量。

## 停止与识别边界

后台提取不阻塞界面，全程序最多同时运行一项 OCR。取消和关闭窗口会发出停止信号，过时结果不再写回；原生引擎退出前不允许再次开始。原生识别设置 30 秒截止时间，并使用进度回调响应取消；引擎加载、初始化及原生函数内部仍不能保证立即中断，截止时间也不是整个操作的硬性上限。

小字、低对比度、透明内容、复杂背景、旋转文字、遮挡和特殊字体可能影响结果，必要时重新框选更清晰的小范围。自动布局不保证保留原版式，语言模型可能识别少量其他字符，但不承诺五种模型之外语言的准确率。此工具只提取当前图片，与播放器的 OpenCV 模板匹配、位置缓存和扫描竞速独立。

## 来源与许可

各文件的固定来源、大小、SHA-256 与 Git blob 记录在 [`manifest.json`](../src/main/resources/ocr/manifest.json)，分发包含 [`licenses/`](../src/main/resources/ocr/licenses/) 下的许可正文。

| 内容 | 来源 | 许可 |
|---|---|---|
| 五种语言模型 | [tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast/tree/87416418657359cb625c412a48b6e1d6d41c29bd) | Apache-2.0 |
| Tesseract 5.3.4 Windows DLL | [Tess4J 固定版本资源](https://github.com/nguyenq/tess4j/tree/6d4179d3b0553a2572d97806282f91bdaf8fc2dd/src/main/resources/win32-x86-64)，[Tesseract](https://github.com/tesseract-ocr/tesseract) | Apache-2.0 |
| Leptonica 1.84.1 Windows DLL | [Lept4J 固定版本资源](https://github.com/nguyenq/lept4j/tree/0f5174c9c74fdbc7d092ceafa958cfc68fd0bac8/src/main/resources/win32-x86-64)，[Leptonica](https://github.com/DanBloomberg/leptonica) | Leptonica BSD 许可；分发来源另附 Apache-2.0 |

两份 Windows DLL 均为上游分发的未修改二进制，语言模型未修改。测试结果、离屏预览和本机组件诊断见 [自动测试记录](automated-tests.md)。
