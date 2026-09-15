package com.mei.zhgy.service.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeChunkerTest {
    @Test
    void paragraphChunkingIsDeterministicAndBounded() {
        String input = "第一段农业建议。\r\n\r\n第二段。";
        assertEquals(KnowledgeChunker.chunk(input), KnowledgeChunker.chunk(input.replace("\r\n", "\n")));
        assertEquals(List.of("第一段农业建议。", "第二段。"), KnowledgeChunker.chunk(input));
    }

    @Test
    void longParagraphUsesStableBoundedPieces() {
        String input = "x ".repeat(900);
        var chunks = KnowledgeChunker.chunk(input);
        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(value -> value.length() <= KnowledgeChunker.MAX_CHARS));
    }
}
