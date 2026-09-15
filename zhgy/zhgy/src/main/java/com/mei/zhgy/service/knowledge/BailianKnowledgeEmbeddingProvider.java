package com.mei.zhgy.service.knowledge;

import com.mei.zhgy.service.ai.BailianModelClient;
import com.mei.zhgy.service.ai.EmbeddingProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Bailian-only provider. It never falls back to local BGE. */
@Component
public class BailianKnowledgeEmbeddingProvider implements KnowledgeEmbeddingProvider {
    private static final String PROVIDER = "BAILIAN";
    private final BailianModelClient client;
    private final String model;
    private final int dimension;
    private final int batchSize;

    public BailianKnowledgeEmbeddingProvider(
            BailianModelClient client,
            @Value("${farmap.knowledge.embedding.model:qwen3.7-text-embedding-flash}") String model,
            @Value("${farmap.knowledge.embedding.dimension:1024}") int dimension,
            @Value("${farmap.knowledge.embedding.batch-size:10}") int batchSize) {
        this.client = client;
        this.model = model;
        this.dimension = dimension;
        this.batchSize = Math.max(1, Math.min(batchSize, 20));
    }

    @Override
    public EmbeddingProvider.EmbeddingResult embedText(String text) {
        List<EmbeddingProvider.EmbeddingResult> values = embedTexts(Collections.singletonList(text));
        if (values.isEmpty()) throw new KnowledgeBackendException("AI_CONTRACT_INVALID", "Bailian embedding returned no vector");
        return values.get(0);
    }

    @Override
    public List<EmbeddingProvider.EmbeddingResult> embedTexts(List<String> texts) {
        if (texts == null || texts.isEmpty()) return Collections.emptyList();
        List<EmbeddingProvider.EmbeddingResult> output = new ArrayList<>();
        for (int start = 0; start < texts.size(); start += batchSize) {
            List<String> batch = texts.subList(start, Math.min(texts.size(), start + batchSize));
            output.addAll(client.embedTexts(model, batch, dimension));
        }
        if (output.size() != texts.size()) {
            throw new KnowledgeBackendException("AI_CONTRACT_INVALID", "Bailian embedding count does not match input count");
        }
        for (EmbeddingProvider.EmbeddingResult result : output) {
            if (result.getDimension() != dimension || result.getVector().size() != dimension) {
                throw new KnowledgeBackendException("AI_CONTRACT_INVALID", "Bailian embedding dimension mismatch");
            }
        }
        return output;
    }

    @Override public String provider() { return PROVIDER; }
    @Override public String model() { return model; }
    @Override public int dimension() { return dimension; }
    @Override public boolean configured() { return client.isConfigured(); }
}
