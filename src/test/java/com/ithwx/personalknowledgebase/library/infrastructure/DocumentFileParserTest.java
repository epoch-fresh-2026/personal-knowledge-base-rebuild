package com.ithwx.personalknowledgebase.library.infrastructure;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextBox;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentFileParserTest {

    private final DocumentFileParser parser = new DocumentFileParser();

    @Test
    void shouldParseTxtAndMarkdown() throws Exception {
        assertEquals("中文笔记", parser.parse("note.txt", bytes("中文笔记")));
        assertEquals("Markdown 标题", parser.parse("README.md", bytes("# Markdown 标题")));
    }

    @Test
    void shouldRemoveMarkdownFormatting() throws Exception {
        String result = parser.parse(
                "note.markdown",
                bytes("# 标题\n\n这是 **重点内容**，参考[官网](https://example.com)。")
        );

        assertTrue(result.contains("标题"));
        assertTrue(result.contains("重点内容"));
        assertTrue(result.contains("官网"));
        assertFalse(result.contains("#"));
        assertFalse(result.contains("**"));
        assertFalse(result.contains("]("));
    }

    @Test
    void shouldParsePdfAndDocx() throws Exception {
        assertTrue(parser.parse("note.pdf", createPdf("PDF content")).contains("PDF content"));
        assertTrue(parser.parse("note.docx", createDocx("DOCX content")).contains("DOCX content"));
    }

    @Test
    void shouldReadLegacyWordSample() throws Exception {
        try (var input = getClass().getResourceAsStream("/documents/simple.doc")) {
            String text = parser.parse("lecture.doc", input.readAllBytes());
            assertTrue(text.contains("This is a simple file created with Word 97-SR2"));
        }
    }

    @Test
    void shouldReadDocxParagraphsAndTablesInOrder() throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("表格之前");
            document.createTable(1, 2).getRow(0).getCell(0).setText("表格关键知识点");
            document.createParagraph().createRun().setText("表格之后");
            document.write(output);
            String text = parser.parse("table.docx", output.toByteArray());
            assertTrue(text.indexOf("表格之前") < text.indexOf("表格关键知识点"));
            assertTrue(text.indexOf("表格关键知识点") < text.indexOf("表格之后"));
        }
    }

    @Test
    void shouldReadBothPowerPointFormats() throws Exception {
        try (XMLSlideShow slides = new XMLSlideShow();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            slides.createSlide().createTextBox().setText("第一页：数据库事务");
            slides.createSlide().createTextBox().setText("最后一页：行锁");
            slides.write(output);
            String text = parser.parse("lecture.pptx", output.toByteArray());
            assertTrue(text.contains("数据库事务"));
            assertTrue(text.contains("行锁"));
        }
        try (HSLFSlideShow slides = new HSLFSlideShow();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            HSLFTextBox box = new HSLFTextBox();
            box.setText("旧版 PPT 知识点");
            slides.createSlide().addShape(box);
            slides.write(output);
            assertTrue(parser.parse("lecture.ppt", output.toByteArray()).contains("旧版 PPT 知识点"));
        }
    }

    @Test
    void shouldReadBothExcelFormatsAndAllSheets() throws Exception {
        assertExcel(new HSSFWorkbook(), "table.xls");
        assertExcel(new XSSFWorkbook(), "table.xlsx");
    }

    @Test
    void shouldReadHtmlRtfCsvAndTsv() throws Exception {
        String html = parser.parse("page.html", bytes("<html><body><h1>课程标题</h1><p>知识点</p><script>alert('hidden')</script></body></html>"));
        assertTrue(html.contains("课程标题"));
        assertTrue(html.contains("知识点"));
        assertFalse(html.contains("alert"));
        assertTrue(parser.parse("page.htm", bytes("<p>HTML text</p>")).contains("HTML text"));
        assertTrue(parser.parse("note.rtf", bytes("{\\rtf1\\ansi RTF content\\par}")).contains("RTF content"));
        assertTrue(parser.parse("table.csv", bytes("主题,内容\n事务,原子性")).contains("原子性"));
        assertTrue(parser.parse("table.tsv", bytes("主题\t内容\n行锁\t并发")).contains("并发"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"odt", "ods", "odp"})
    void shouldReadOpenDocumentFormats(String type) throws Exception {
        String body = switch (type) {
            case "ods" -> "<office:spreadsheet><table:table table:name=\"知识\"><table:table-row><table:table-cell office:value-type=\"string\"><text:p>ODF知识点</text:p></table:table-cell></table:table-row></table:table></office:spreadsheet>";
            case "odp" -> "<office:presentation><draw:page draw:name=\"第一页\"><draw:frame><draw:text-box><text:p>ODF知识点</text:p></draw:text-box></draw:frame></draw:page></office:presentation>";
            default -> "<office:text><text:p>ODF知识点</text:p></office:text>";
        };
        String mime = "application/vnd.oasis.opendocument." + switch (type) {
            case "ods" -> "spreadsheet";
            case "odp" -> "presentation";
            default -> "text";
        };
        byte[] document = zip(Map.of(
                "mimetype", mime,
                "content.xml", "<office:document-content xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\" xmlns:draw=\"urn:oasis:names:tc:opendocument:xmlns:drawing:1.0\"><office:body>" + body + "</office:body></office:document-content>"
        ));
        assertTrue(parser.parse("lecture." + type, document).contains("ODF知识点"));
    }

    @Test
    void shouldReadEpub() throws Exception {
        byte[] book = zip(Map.of(
                "mimetype", "application/epub+zip",
                "META-INF/container.xml", "<container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\" version=\"1.0\"><rootfiles><rootfile full-path=\"content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>",
                "content.opf", "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"2.0\" unique-identifier=\"id\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>课程电子书</dc:title><dc:identifier id=\"id\">test-book</dc:identifier><dc:language>zh</dc:language></metadata><manifest><item id=\"chapter\" href=\"chapter.xhtml\" media-type=\"application/xhtml+xml\"/></manifest><spine><itemref idref=\"chapter\"/></spine></package>",
                "chapter.xhtml", "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>课程</title></head><body><p>EPUB知识点</p></body></html>"
        ));
        assertTrue(parser.parse("lecture.epub", book).contains("EPUB知识点"));
    }

    @Test
    void shouldReportPdfWithoutTextRatherThanClaimingOcrSupport() throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            var error = assertThrows(IllegalArgumentException.class,
                    () -> parser.parse("scan.pdf", output.toByteArray()));
            assertTrue(error.getMessage().contains("暂不支持扫描件或图片 OCR"));
        }
    }

    @Test
    void shouldRecognizeUppercaseExtension() throws Exception {
        assertEquals("大写扩展名", parser.parse("note.TXT", bytes("大写扩展名")));
    }

    @Test
    void shouldRejectUnsupportedOrEmptyFile() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse("image.jpg", bytes("image"))
        );
        assertEquals("不支持的文件格式：jpg", exception.getMessage());
        assertThrows(IOException.class, () -> parser.parse("empty.txt", new byte[0]));
    }

    private void assertExcel(Workbook workbook, String filename) throws Exception {
        try (workbook; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.createSheet("第一章").createRow(0).createCell(0).setCellValue("表格知识点");
            workbook.createSheet("最后一章").createRow(0).createCell(0).setCellValue("最后一张表");
            workbook.write(output);
            String text = parser.parse(filename, output.toByteArray());
            assertTrue(text.contains("表格知识点"));
            assertTrue(text.contains("最后一张表"));
        }
    }

    private byte[] zip(Map<String, String> entries) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(output)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(bytes(entry.getValue()));
                zip.closeEntry();
            }
            zip.finish();
            return output.toByteArray();
        }
    }

    private byte[] bytes(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] createPdf(String text) throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText(text);
                content.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private byte[] createDocx(String text) throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText(text);
            document.write(output);
            return output.toByteArray();
        }
    }
}
