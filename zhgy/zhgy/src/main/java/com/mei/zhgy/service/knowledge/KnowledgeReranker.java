package com.mei.zhgy.service.knowledge;

import java.util.List;

public interface KnowledgeReranker {
    List<RerankResult> rerank(String query, List<String> documents, int topK);

    String provider();

    String model();

    boolean configured();

    final class RerankResult {
        private final int index;
        private final double score;

        public RerankResult(int index, double score) { this.index = index; this.score = score; }
        public int getIndex() { return index; }
        public double getScore() { return score; }
    }
}
