package com.mei.zhgy.service.ai;

public interface ImageEmbeddingProvider {
    EmbeddingProvider.EmbeddingResult embedImage(String image);
}
