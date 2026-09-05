package com.mei.zhgy.service.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class LocalBgeEmbeddingProvider implements TextEmbeddingProvider {
    private final LocalAiClient client;
    public LocalBgeEmbeddingProvider(LocalAiClient client) { this.client = client; }

    @Override public EmbeddingProvider.EmbeddingResult embedText(String text) {
        List<EmbeddingProvider.EmbeddingResult> values = embedTexts(Collections.singletonList(text));
        return values.isEmpty() ? empty() : values.get(0);
    }

    @Override @SuppressWarnings("unchecked")
    public List<EmbeddingProvider.EmbeddingResult> embedTexts(List<String> texts) {
        if (texts == null || texts.isEmpty()) return Collections.emptyList();
        try {
            Map<String, Object> result = client.embedTexts(texts);
            List<List<Number>> vectors = (List<List<Number>>) result.get("embeddings");
            int dimension = ((Number) result.getOrDefault("dimension", 0)).intValue();
            long latency = ((Number) result.getOrDefault("latencyMs", 0)).longValue();
            List<EmbeddingProvider.EmbeddingResult> output = new ArrayList<>();
            for (List<Number> vector : vectors) output.add(new EmbeddingProvider.EmbeddingResult(
                    floats(vector), "LOCAL", String.valueOf(result.get("model")), dimension, latency));
            return output;
        } catch (Exception error) {
            log.warn("Local BGE unavailable: {}", error.getClass().getSimpleName());
            return Collections.emptyList();
        }
    }

    private List<Float> floats(List<Number> values) { List<Float> out = new ArrayList<>(); for (Number value : values) out.add(value.floatValue()); return out; }
    private EmbeddingProvider.EmbeddingResult empty() { return new EmbeddingProvider.EmbeddingResult(Collections.emptyList(), "FALLBACK", "BAAI/bge-small-zh-v1.5", 0, 0); }
}
