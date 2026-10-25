package com.macro.ui.util;

import javax.imageio.ImageIO;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.util.Base64;

/** 解析浏览器拖出的图片地址；拖放数据在事件回调内读取，网络图片在后台加载。 */
public final class ImageTransferUtil {
    private static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;
    private static final int MAX_TEXT_LENGTH = 1024 * 1024;

    /** 图片传输格式解析通过静态方法提供，无需创建实例。 */
    private ImageTransferUtil() {
    }

    /** 判断传输格式是否可能携带图片地址，不在此处访问网络或读取图片。 */
    public static boolean supportsAddress(DataFlavor flavor) {
        return flavor.isFlavorTextType() || "text".equalsIgnoreCase(flavor.getPrimaryType())
                || flavor.getRepresentationClass() == URL.class;
    }

    /** 优先从图片标签取地址，避免误用包裹图片的网页链接。 */
    public static String readImageAddress(Transferable transferable) {
        DataFlavor[] flavors = transferable.getTransferDataFlavors();
        for (int pass = 0; pass < 2; pass++) {
            for (DataFlavor flavor : flavors) {
                boolean html = flavor.isMimeTypeEqual("text/html");
                if (!supportsAddress(flavor) || html != (pass == 0)) {
                    continue;
                }
                try {
                    if (flavor.getRepresentationClass() == URL.class) {
                        return transferable.getTransferData(flavor).toString();
                    }
                    String text;
                    try (Reader reader = flavor.getRepresentationClass() == String.class
                            ? new StringReader((String) transferable.getTransferData(flavor))
                            : flavor.getReaderForText(transferable)) {
                        StringBuilder buffer = new StringBuilder();
                        char[] chunk = new char[4096];
                        int count;
                        while ((count = reader.read(chunk)) != -1) {
                            if (buffer.length() + count > MAX_TEXT_LENGTH) {
                                throw new IOException("拖放文本过长");
                            }
                            buffer.append(chunk, 0, count);
                        }
                        text = buffer.toString();
                    }
                    if (html) {
                        String[] src = new String[1];
                        new ParserDelegator().parse(new StringReader(text), new HTMLEditorKit.ParserCallback() {
                            /** 从 HTML 的首个图片标签提取来源地址，忽略后续重复图片。 */
                            @Override
                            public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attrs, int pos) {
                                if (tag == HTML.Tag.IMG && src[0] == null) {
                                    Object value = attrs.getAttribute(HTML.Attribute.SRC);
                                    if (value != null) src[0] = value.toString();
                                }
                            }
                        }, true);
                        if (src[0] != null && isImageAddress(src[0])) return src[0];
                    } else {
                        // URI 列表和 Firefox 的地址加标题格式都将地址放在单独一行。
                        for (String line : text.split("[\\r\\n\\u0000]+")) {
                            String address = line.trim();
                            if (isImageAddress(address)) return address;
                        }
                    }
                } catch (Exception exception) {
                    // 某个声明格式没有实际数据时，继续尝试其余格式。
                }
            }
        }
        return null;
    }

    /** 判断文本是否使用受识别的图片地址协议，供传输内容解析使用。 */
    private static boolean isImageAddress(String text) {
        return text.startsWith("https://") || text.startsWith("http://")
                || text.startsWith("file:/") || text.startsWith("data:image/")
                || text.startsWith("blob:");
    }

    /** 只接受能解码为图片的内容，不将网页文本导入预览。 */
    public static BufferedImage loadImage(String address) throws IOException {
        if (!isImageAddress(address)) throw new IOException("不支持的图片地址协议");
        if (address.startsWith("blob:")) {
            throw new IOException("该图片是来源应用内部的临时图片，无法通过外部地址读取。请复制图片后粘贴。");
        }
        try {
            InputStream input;
            if (address.startsWith("data:image/")) {
                int comma = address.indexOf(',');
                if (comma < 0 || !address.substring(0, comma).endsWith(";base64")) {
                    throw new IOException("不支持的内嵌图片格式");
                }
                input = new ByteArrayInputStream(Base64.getDecoder().decode(address.substring(comma + 1)));
            } else {
                URLConnection connection = URI.create(address).toURL().openConnection();
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(10000);
                input = connection.getInputStream();
            }
            try (InputStream stream = input) {
                byte[] bytes = stream.readNBytes(MAX_IMAGE_BYTES + 1);
                if (bytes.length > MAX_IMAGE_BYTES) throw new IOException("图片文件超过 20 MB");
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
                if (image == null) throw new IOException("拖入的地址没有返回可识别的图片");
                return image;
            }
        } catch (IllegalArgumentException exception) {
            throw new IOException("图片地址无效", exception);
        }
    }
}
