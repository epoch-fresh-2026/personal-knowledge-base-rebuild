package com.ithwx.personalknowledgebase.library.infrastructure;

import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.TextContentRenderer;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

@Component
public class DocumentFileParser {

    private static final Set<String> SUPPORTED_TYPES =
            Set.of("txt", "md", "markdown", "pdf", "doc", "docx", "ppt", "pptx",
                    "xls", "xlsx", "csv", "tsv", "html", "htm", "rtf",
                    "odt", "ods", "odp", "epub");

    private final AutoDetectParser parser = new AutoDetectParser();
    private final Parser markdownParser = Parser.builder().build();
    private final TextContentRenderer markdownRenderer = TextContentRenderer.builder().build();

    public static String fileType(String filename) {
        int dot = filename == null ? -1 : filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            throw new IllegalArgumentException("无法识别文件格式");
        }
        String type = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!SUPPORTED_TYPES.contains(type)) {
            throw new IllegalArgumentException("不支持的文件格式：" + type);
        }
        return type;
    }

    public String parse(String filename, byte[] bytes) throws IOException {
        String content = switch (fileType(filename)) {
            case "md", "markdown" -> parseMarkdown(bytes);
            default -> parseDocument(filename, bytes);
        };
        if (content.isBlank()) {
            throw new IllegalArgumentException("未提取到文字，请使用含正文的文档；暂不支持扫描件或图片 OCR");
        }
        return content.strip();
    }

    private String parseMarkdown(byte[] bytes) {
        String markdown = new String(bytes, StandardCharsets.UTF_8);
        return markdownRenderer.render(markdownParser.parse(markdown));
    }

    private String parseDocument(String filename, byte[] bytes) throws IOException {
        Metadata metadata = new Metadata();//Tika 的元数据对象，存放文件信息
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
        BodyContentHandler handler = new BodyContentHandler(-1);//接收解析出来的文本内容
        ParseContext context = new ParseContext();//解析上下文，用来给特定解析器传配置参数
        PDFParserConfig pdf = new PDFParserConfig();//PDF 专用解析配置
        pdf.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
        context.set(PDFParserConfig.class, pdf);
        TesseractOCRConfig ocr = new TesseractOCRConfig();
        ocr.setSkipOcr(true);
        context.set(TesseractOCRConfig.class, ocr);
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
                        //输入流、文本处理器、元数据、解析配置上下文
            parser.parse(input, handler, metadata, context);
            return handler.toString();
        } catch (TikaException | SAXException exception) {
            throw new IOException("文件解析失败，请检查文件是否损坏或加密", exception);
        }
    }
}
