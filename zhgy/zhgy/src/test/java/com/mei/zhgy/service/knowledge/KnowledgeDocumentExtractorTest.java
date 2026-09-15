package com.mei.zhgy.service.knowledge;

import org.junit.jupiter.api.Test;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeDocumentExtractorTest {
    @Test
    void supportsTxtAndMarkdownWithNormalizedText() throws Exception {
        assertEquals("a\nb", KnowledgeDocumentExtractor.extract("guide.txt", "a\r\nb".getBytes(StandardCharsets.UTF_8)));
        assertEquals("# title", KnowledgeDocumentExtractor.extract("guide.md", "\uFEFF# title".getBytes(StandardCharsets.UTF_8)));
        assertEquals("text/plain", KnowledgeDocumentExtractor.contentType("guide.txt"));
        assertEquals("text/markdown", KnowledgeDocumentExtractor.contentType("guide.md"));
    }

    @Test
    void rejectsUnsupportedFormats() {
        assertThrows(IllegalArgumentException.class, () -> KnowledgeDocumentExtractor.extract("guide.docx", new byte[]{1}));
    }

    @Test
    void extractsDocxParagraphsAndTablesWithoutChangingTheSource() throws Exception {
        byte[] source;
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("柑橘膨大期应及时排水");
            document.createTable(1, 1).getRow(0).getCell(0).setText("根区保持通气");
            document.write(output);
            source = output.toByteArray();
        }

        String extracted = KnowledgeDocumentExtractor.extract("guide.docx", source);
        assertTrue(extracted.contains("柑橘膨大期应及时排水"));
        assertTrue(extracted.contains("根区保持通气"));
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                KnowledgeDocumentExtractor.contentType("guide.docx"));
    }

    @Test
    void emptyTextRemainsEmptyForTheIngestionServiceToReject() throws Exception {
        assertEquals("", KnowledgeDocumentExtractor.extract("empty.txt", "\r\n  \n".getBytes(StandardCharsets.UTF_8)));
    }
}
