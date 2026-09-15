package com.mei.zhgy.service.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small dependency-free BM25 scorer for the curated agricultural corpus. */
public final class KnowledgeBm25 {
    private static final Pattern TOKEN = Pattern.compile("[a-z0-9]+|[\\u4e00-\\u9fff]+");
    private static final double K1 = 1.2D;
    private static final double B = 0.75D;

    public List<ScoredDocument> rank(List<Document> documents, String query, int limit) {
        if (documents == null || documents.isEmpty() || query == null || query.isBlank()) return List.of();
        List<String> queryTokens = tokens(query);
        if (queryTokens.isEmpty()) return List.of();
        return buildCorpus(documents).scoreAgainstTokens(queryTokens, limit);
    }

    /**
     * Builds the immutable corpus statistics used by repeated query scoring.
     * The tokenization and statistics intentionally match the legacy rank path.
     */
    public Bm25Corpus buildCorpus(List<Document> documents) {
        if (documents == null || documents.isEmpty()) return Bm25Corpus.empty();
        List<Document> safeDocuments = documents.stream()
                .filter(Objects::nonNull)
                .toList();
        if (safeDocuments.isEmpty()) return Bm25Corpus.empty();
        List<List<String>> tokenized = safeDocuments.stream()
                .map(document -> List.copyOf(tokens(document.content())))
                .toList();
        Map<String, Integer> documentFrequency = new HashMap<>();
        for (List<String> terms : tokenized) {
            Set<String> unique = new HashSet<>(terms);
            unique.forEach(term -> documentFrequency.merge(term, 1, Integer::sum));
        }
        double averageLength = tokenized.stream().mapToInt(List::size).average().orElse(1D);
        return new Bm25Corpus(safeDocuments, tokenized, documentFrequency, safeDocuments.size(), averageLength);
    }

    /** Immutable BM25 statistics for one actor-scoped corpus snapshot. */
    public static final class Bm25Corpus {
        private final List<Document> documents;
        private final List<List<String>> tokenizedDocuments;
        private final Map<String, Integer> documentFrequency;
        private final int totalDocuments;
        private final double averageLength;

        private Bm25Corpus(List<Document> documents, List<List<String>> tokenizedDocuments,
                           Map<String, Integer> documentFrequency, int totalDocuments, double averageLength) {
            this.documents = List.copyOf(documents);
            this.tokenizedDocuments = List.copyOf(tokenizedDocuments);
            this.documentFrequency = Map.copyOf(documentFrequency);
            this.totalDocuments = totalDocuments;
            this.averageLength = averageLength;
        }

        private static Bm25Corpus empty() {
            return new Bm25Corpus(List.of(), List.of(), Map.of(), 0, 1D);
        }

        public int totalDocuments() { return totalDocuments; }

        public double averageLength() { return averageLength; }

        public List<ScoredDocument> scoreAgainst(String query, int limit) {
            if (query == null || query.isBlank()) return List.of();
            List<String> queryTokens = tokens(query);
            return queryTokens.isEmpty() ? List.of() : scoreAgainstTokens(queryTokens, limit);
        }

        public List<ScoredDocument> scoreAgainstTokens(List<String> queryTokens, int limit) {
            if (totalDocuments == 0 || queryTokens == null || queryTokens.isEmpty()) return List.of();
            List<ScoredDocument> scored = new ArrayList<>();
            for (int i = 0; i < documents.size(); i++) {
                List<String> terms = tokenizedDocuments.get(i);
                Map<String, Integer> frequencies = new HashMap<>();
                terms.forEach(term -> frequencies.merge(term, 1, Integer::sum));
                double score = 0D;
                for (String term : queryTokens) {
                    int frequency = frequencies.getOrDefault(term, 0);
                    if (frequency == 0) continue;
                    int df = documentFrequency.getOrDefault(term, 0);
                    double idf = Math.log(1D + (totalDocuments - df + 0.5D) / (df + 0.5D));
                    double lengthNorm = 1D - B + B * terms.size() / averageLength;
                    score += idf * frequency * (K1 + 1D) / (frequency + K1 * lengthNorm);
                }
                if (score > 0D) scored.add(new ScoredDocument(documents.get(i), score));
            }
            // TimSort is stable, so equal scores retain the original document order,
            // matching the old rank(List, ...) behavior exactly.
            scored.sort(Comparator.comparingDouble(ScoredDocument::score).reversed());
            if (scored.isEmpty()) return List.of();
            int safeLimit = Math.max(1, Math.min(limit, scored.size()));
            return List.copyOf(scored.subList(0, safeLimit));
        }
    }

    static List<String> tokens(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(text.toLowerCase(java.util.Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            if (token.charAt(0) <= 'z' && token.charAt(0) >= 'a') {
                result.add(token);
                continue;
            }
            if (token.length() > 1) result.add(token);
            for (int width = 2; width <= 3; width++) {
                for (int start = 0; start + width <= token.length(); start++) {
                    result.add(token.substring(start, start + width));
                }
            }
        }
        return result;
    }

    public record Document(String id, String content) { }
    public record ScoredDocument(Document document, double score) { }
}
