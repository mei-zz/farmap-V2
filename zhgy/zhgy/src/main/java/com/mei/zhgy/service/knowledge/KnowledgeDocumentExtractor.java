package com.mei.zhgy.service.knowledge;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Strict server-side extraction for the Phase D supported formats. */
public final class KnowledgeDocumentExtractor {
    private KnowledgeDocumentExtractor() { }

    public static String extract(String fileName, byte[] bytes) throws IOException {
        if (fileName == null || bytes == null || bytes.length == 0) throw new IllegalArgumentException("file is empty");
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return normalize(new String(bytes, StandardCharsets.UTF_8));
        }
        if (lower.endsWith(".pdf")) {
            try (PDDocument document = PDDocument.load(new ByteArrayInputStream(bytes))) {
                return normalize(new PDFTextStripper().getText(document));
            }
        }
        if (lower.endsWith(".docx")) {
            try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
                StringBuilder text = new StringBuilder();
                for (IBodyElement element : document.getBodyElements()) {
                    if (element instanceof XWPFParagraph paragraph) {
                        appendLine(text, paragraph.getText());
                    } else if (element instanceof XWPFTable table) {
                        for (XWPFTableRow row : table.getRows()) {
                            for (XWPFTableCell cell : row.getTableCells()) appendLine(text, cell.getText());
                        }
                    }
                }
                return normalize(text.toString());
            } catch (IOException error) {
                throw new IllegalArgumentException("DOCX 文档解析失败", error);
            }
        }
        throw new IllegalArgumentException("只支持 PDF、TXT、Markdown、DOCX 文件");
    }

    public static String contentType(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "text/markdown";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        return "text/plain";
    }

    private static void appendLine(StringBuilder text, String value) {
        if (value == null || value.isBlank()) return;
        if (text.length() > 0) text.append('\n');
        text.append(value.trim());
    }

    private static String normalize(String value) {
        return value.replace("\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n').trim();
    }
}
