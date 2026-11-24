# 第三方许可

Macro Recorder 的项目授权说明见 [README](README.md#项目素材与授权)。下列第三方内容适用各自的许可证，其原有版权声明和许可条件继续有效。

## 随源码仓库提供的资源

| 组件 | 来源及许可 |
| --- | --- |
| OCR Windows DLL、语言模型及声明 | Tesseract、Leptonica、Tess4J、Lept4J、tessdata_fast，以及确认的 libpng、LibTIFF。具体版本、来源、原文及待核实依赖见 [OCR 第三方声明](src/main/resources/ocr/THIRD-PARTY.md) 和 [资源清单](src/main/resources/ocr/manifest.json)。完整原生组件声明仍待核实，见 OCR 第三方声明。 |
| Inno Setup 简体中文语言文件 | 随仓库保留原作者署名、[许可正文](scripts/installer/INNO-LICENSE.txt) 及 [来源说明](scripts/installer/README.md)。 |
| Maven Wrapper 3.3.4 | 本仓库提供启动脚本并保留上游许可页眉、[LICENSE](.mvn/wrapper/LICENSE) 和 [NOTICE](.mvn/wrapper/NOTICE)。Maven 分发由 Wrapper 按配置下载。 |

## 由 Maven 获取的依赖

坐标及版本以 [pom.xml](pom.xml) 为准。表中列出的 JAR 由 Maven 获取。

| 组件 | 用途及许可 |
| --- | --- |
| TwelveMonkeys ImageIO WebP 3.13.0；`imageio-core`、`imageio-metadata`、`common-lang`、`common-io`、`common-image` | 图片读取，BSD 许可证。 |
| JNativeHook 2.2.2 | 全局输入事件。发行 POM 列出 GPL 3.0 和 LGPL 3.0，按实际 JAR 内对应组件的声明区分。 |
| JNA、JNA Platform 5.15.0 | Windows 原生接口，Apache License 2.0 或 LGPL 2.1-or-later。 |
| Jackson Databind 2.22.4；`jackson-core` 2.22.4、`jackson-annotations` 2.22 | JSON，Apache License 2.0。 |
| FlatLaf 3.7.2 | 应用界面，Apache License 2.0。 |
| OpenPnP OpenCV 4.9.0-0 | Java 包装的发行元数据列 BSD；[OpenCV 4.9.0](https://opencv.org/license/) 本体采用 Apache License 2.0。原生库内随附组件按各自声明处理。 |
| Lombok 1.18.34 | 编译期代码生成，MIT。 |
| JUnit Jupiter 5.10.5、JUnit Platform 1.10.5 | 测试依赖，Eclipse Public License 2.0。传递测试依赖 OpenTest4J 1.3.0、API Guardian 1.1.2 采用 Apache License 2.0。 |

## ZIP 与 EXE 分发

打包脚本将运行依赖、OpenCV 原生库及裁剪后的 Java 运行时放入应用包。分发前应核对实际打包内容，提供所需的版权、许可与 NOTICE，核实 JNativeHook 的原生和 Java 组件分别适用的许可及随附源代码或替换机制义务，以及 OpenCV 原生依赖的声明。Java 运行时按实际 JDK 供应商和版本核查再分发条件，保留该运行时要求的法律声明。安装包同时保留 Inno Setup 相关声明。
