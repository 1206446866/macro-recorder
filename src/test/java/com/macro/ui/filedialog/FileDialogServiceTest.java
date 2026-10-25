package com.macro.ui.filedialog;

import com.macro.ui.filedialog.windows.WindowsNativeFileDialogService;
import java.io.File;
import java.util.List;

/** 不打开文件面板，验证平台选择、配置不可变性与不支持平台的明确报错。 */
public final class FileDialogServiceTest {
    public static void main(String[] args) {
        check(FileDialogServiceFactory.forOperatingSystem("Windows 11")
                instanceof WindowsNativeFileDialogService, "Windows 没有使用原生服务");
        for (String operatingSystem : List.of("Darwin", "Mac OS X", "Linux", "未知操作系统")) {
            try {
                FileDialogServiceFactory.forOperatingSystem(operatingSystem).showDialog(null,
                        FileDialogOptions.builder(FileDialogOptions.Mode.OPEN_FILE).build());
                throw new AssertionError("未支持的平台没有报错：" + operatingSystem);
            } catch (FileDialogException expected) {
                check(expected.getMessage().contains(operatingSystem), "错误没有标明未支持的平台");
            }
        }
        File file = new File("target/含 空格的目录/模板.png").getAbsoluteFile();
        var builder = FileDialogOptions.builder(FileDialogOptions.Mode.SAVE_FILE)
                .initialFile(file).fileType("图片", ".PNG", "webp")
                .defaultExtension(".PNG").confirmOverwrite(false);
        var options = builder.build();
        builder.fileType("宏", "mr");
        check(options.fileTypes().size() == 1, "配置被构建器后续修改影响");
        check(options.fileTypes().get(0).extensions().equals(List.of("png", "webp")), "扩展名没有规范化");
        check(options.initialDirectory().equals(file.getParentFile()), "初始目录改变");
        check(options.fileName().equals(file.getName()), "初始文件名改变");
        check(!options.confirmOverwrite(), "覆盖确认策略丢失");
        try {
            options.fileTypes().clear();
            throw new AssertionError("文件类型列表应不可变");
        } catch (UnsupportedOperationException expected) { }
        System.out.println("通过：平台工厂、未支持平台报错、不可变配置、目录/文件名及过滤器");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
