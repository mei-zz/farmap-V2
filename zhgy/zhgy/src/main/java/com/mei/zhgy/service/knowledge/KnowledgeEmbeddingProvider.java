package com.mei.zhgy.service.knowledge;

import com.mei.zhgy.service.ai.EmbeddingProvider;

import java.util.List;

/** Text embedding boundary owned by the knowledge index generation. */
public interface KnowledgeEmbeddingProvider {
    EmbeddingProvider.EmbeddingResult embedText(String text);

    List<EmbeddingProvider.EmbeddingResult> embedTexts(List<String> texts);

    String provider();

    String model();

    int dimension();

    boolean configured();
}
