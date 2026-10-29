# 安装包构建资源

`MacroRecorder.iss` 封装已通过基础检查的 Windows x64 应用目录。请从项目根目录运行 `build-installer.bat`，不需要手工填写脚本参数。

- `ChineseSimplified.isl`：Inno Setup 官方简体中文语言文件，保留原作者署名及说明。来源：[官方仓库](https://github.com/jrsoftware/issrc/blob/main/Files/Languages/ChineseSimplified.isl)，通过[官方翻译索引](https://jrsoftware.org/files/istrans/)取得，下载于 2026-10-25。
- `INNO-LICENSE.txt`：随本机构建工具附带的 Inno Setup 许可证，保留原文。

安装标识 `AppId` 在后续版本中保持不变。安装器复用应用当前的 `Local\MacroRecorder.SingleInstance.<用户SID>.Main` 互斥量；更改应用单实例命名规则时，需要同步检查安装脚本。升级前提示关闭程序，不强制终止录制或播放。

安装器只管理程序 EXE、图标、`app` 和 `runtime`。不要添加清空整个安装目录的升级或卸载规则，`save`、`ScreenCapture`、`config.properties` 及其他用户文件须保留。

构建成功及基础检查不能替代真实安装、升级、卸载和数据保留验收。当前未配置代码签名。
