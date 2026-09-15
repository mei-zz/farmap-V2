package com.mei.zhgy.service.ai;

import java.util.List;

public interface EmbeddingProvider {
    EmbeddingResult embedText(String text);

    EmbeddingResult embedImage(String imageUrl);

    class EmbeddingResult {
        private final List<Float> vector;
        private final String provider;
        private final String model;
        private final int dimension;
        private final long latencyMs;

        public EmbeddingResult(List<Float> vector, String provider, String model, int dimension, long latencyMs) {
            this.vector = vector;
            this.provider = provider;
            this.model = model;
            this.dimension = dimension;
            this.latencyMs = latencyMs;
        }

        public List<Float> getVector() { return vector; }
        public String getProvider() { return provider; }
        public String getModel() { return model; }
        public int getDimension() { return dimension; }
        public long getLatencyMs() { return latencyMs; }
    }
}
