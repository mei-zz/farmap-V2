package com.mei.zhgy.service.knowledge;

import java.util.ArrayList;
import java.util.List;

/** Deterministic, language-neutral paragraph chunker used by the ingestion contract. */
public final class KnowledgeChunker {
    public static final int MAX_CHARS = 1200;
    public static final int OVERLAP_CHARS = 120;

    private KnowledgeChunker() { }

    public static List<String> chunk(String normalized) {
        return chunk(normalized, MAX_CHARS, OVERLAP_CHARS);
    }

    /**
     * Chunking is intentionally deterministic.  The configured values are a
     * product policy, not a claim that they are scientifically optimal.
     */
    public static List<String> chunk(String normalized, int maxChars, int overlapChars) {
        if (normalized == null || normalized.isBlank()) return List.of();
        if (maxChars < 1 || overlapChars < 0 || overlapChars >= maxChars) {
            throw new IllegalArgumentException("chunk size/overlap configuration is invalid");
        }
        List<String> paragraphs = new ArrayList<>();
        for (String paragraph : normalized.split("\\n\\s*\\n")) {
            String value = paragraph.replaceAll("[ \\t]+", " ").trim();
            if (!value.isEmpty()) paragraphs.add(value);
        }
        List<String> output = new ArrayList<>();
        for (String paragraph : paragraphs) {
            if (paragraph.length() <= maxChars) { output.add(paragraph); continue; }
            int start = 0;
            while (start < paragraph.length()) {
                int end = Math.min(paragraph.length(), start + maxChars);
                if (end < paragraph.length()) {
                    int boundary = Math.max(paragraph.lastIndexOf(' ', end), paragraph.lastIndexOf('。', end));
                    if (boundary > start + maxChars / 2) end = boundary;
                }
                String piece = paragraph.substring(start, end).trim();
                if (!piece.isEmpty()) output.add(piece);
                if (end >= paragraph.length()) break;
                start = Math.max(end - overlapChars, start + 1);
            }
        }
        return output;
    }
}
