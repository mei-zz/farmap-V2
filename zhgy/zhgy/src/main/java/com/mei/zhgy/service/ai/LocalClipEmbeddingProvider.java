package com.mei.zhgy.service.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class LocalClipEmbeddingProvider implements ImageEmbeddingProvider {
    private final IntelligenceGateway gateway;
    private final Map<String, EmbeddingProvider.EmbeddingResult> cache = new ConcurrentHashMap<>();
    @org.springframework.beans.factory.annotation.Autowired
    public LocalClipEmbeddingProvider(IntelligenceGateway gateway) { this.gateway = gateway; }

    /** Compatibility constructor for existing tests and legacy callers. */
    public LocalClipEmbeddingProvider(LocalAiClient client) { this(new IntelligenceGateway(client, "http://127.0.0.1:5000/v1/vision/analyze", new org.springframework.web.client.RestTemplate())); }

    @Override @SuppressWarnings("unchecked")
    public EmbeddingProvider.EmbeddingResult embedImage(String image) {
        if (image != null && cache.containsKey(image)) return cache.get(image);
        try {
            Map<String, Object> result = gateway.embedImage(image, null, null);
            List<Number> raw = (List<Number>) result.get("embedding");
            List<Float> vector = new ArrayList<>();
            for (Number value : raw) vector.add(value.floatValue());
            EmbeddingProvider.EmbeddingResult value = new EmbeddingProvider.EmbeddingResult(vector, "LOCAL", String.valueOf(result.get("model")),
                    ((Number) result.getOrDefault("dimension", 0)).intValue(),
                    ((Number) result.getOrDefault("latencyMs", 0)).longValue());
            if (image != null) cache.put(image, value);
            return value;
        } catch (Exception error) {
            if (!gateway.isLegacyCompatibility()) {
                if (error instanceof RuntimeException) throw (RuntimeException) error;
                throw new IntelligenceGatewayException("AI_EMBEDDING_FAILED", "image embedding failed", null, true, error);
            }
            log.warn("Local CLIP unavailable: {}", error.getClass().getSimpleName());
            return new EmbeddingProvider.EmbeddingResult(Collections.emptyList(), "UNAVAILABLE", "clip-image-encoder.onnx", 0, 0);
        }
    }
}
