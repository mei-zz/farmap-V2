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
    private final IntelligenceGateway gateway;

    @org.springframework.beans.factory.annotation.Autowired
    public LocalBgeEmbeddingProvider(IntelligenceGateway gateway) { this.gateway = gateway; }

    /** Compatibility constructor for existing tests and legacy callers. */
    public LocalBgeEmbeddingProvider(LocalAiClient client) { this(new IntelligenceGateway(client, "http://127.0.0.1:5000/v1/vision/analyze", new org.springframework.web.client.RestTemplate())); }

    @Override public EmbeddingProvider.EmbeddingResult embedText(String text) {
        List<EmbeddingProvider.EmbeddingResult> values = embedTexts(Collections.singletonList(text));
        return values.isEmpty() ? empty() : values.get(0);
    }

    @Override @SuppressWarnings("unchecked")
    public List<EmbeddingProvider.EmbeddingResult> embedTexts(List<String> texts) {
        if (texts == null || texts.isEmpty()) return Collections.emptyList();
        try {
            List<Map<String, Object>> mapped = gateway.embedTexts(texts, null, null);
            if (mapped.isEmpty()) return Collections.emptyList();
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("dimension", mapped.get(0).get("dimension"));
            result.put("model", mapped.get(0).get("model"));
            result.put("latencyMs", mapped.get(0).get("latencyMs"));
            List<List<Number>> vectors = new ArrayList<>();
            for (Map<String, Object> item : mapped) vectors.add((List<Number>) item.get("vector"));
            int dimension = ((Number) result.getOrDefault("dimension", 0)).intValue();
            long latency = ((Number) result.getOrDefault("latencyMs", 0)).longValue();
            List<EmbeddingProvider.EmbeddingResult> output = new ArrayList<>();
            for (List<Number> vector : vectors) output.add(new EmbeddingProvider.EmbeddingResult(
                    floats(vector), "LOCAL", String.valueOf(result.get("model")), dimension, latency));
            return output;
        } catch (Exception error) {
            if (!gateway.isLegacyCompatibility()) {
                if (error instanceof RuntimeException) throw (RuntimeException) error;
                throw new IntelligenceGatewayException("AI_EMBEDDING_FAILED", "text embedding failed", null, true, error);
            }
            log.warn("Local BGE unavailable: {}", error.getClass().getSimpleName());
            return Collections.emptyList();
        }
    }

    private List<Float> floats(List<Number> values) { List<Float> out = new ArrayList<>(); for (Number value : values) out.add(value.floatValue()); return out; }
    private EmbeddingProvider.EmbeddingResult empty() { return new EmbeddingProvider.EmbeddingResult(Collections.emptyList(), "FALLBACK", "BAAI/bge-small-zh-v1.5", 0, 0); }
}
