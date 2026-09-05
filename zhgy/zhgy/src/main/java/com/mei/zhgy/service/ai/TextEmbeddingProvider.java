package com.mei.zhgy.service.ai;

import java.util.List;

public interface TextEmbeddingProvider {
    EmbeddingProvider.EmbeddingResult embedText(String text);
    List<EmbeddingProvider.EmbeddingResult> embedTexts(List<String> texts);
}
