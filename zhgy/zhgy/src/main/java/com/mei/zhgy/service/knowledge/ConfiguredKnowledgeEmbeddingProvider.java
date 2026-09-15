package com.mei.zhgy.service.knowledge;

import com.mei.zhgy.service.ai.EmbeddingProvider;
import com.mei.zhgy.service.ai.LocalBgeEmbeddingProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

/** Selects one complete knowledge index generation; it never mixes providers. */
@Primary
@Component
public class ConfiguredKnowledgeEmbeddingProvider implements KnowledgeEmbeddingProvider {
    private final KnowledgeEmbeddingProvider selected;

    public ConfiguredKnowledgeEmbeddingProvider(
            BailianKnowledgeEmbeddingProvider bailian,
            LocalBgeEmbeddingProvider localBge,
            @Value("${farmap.knowledge.embedding.provider:local_bge}") String provider) {
        this.selected = "bailian".equalsIgnoreCase(provider) ? bailian : new LocalBgeKnowledgeEmbeddingProvider(localBge);
    }

    @Override public EmbeddingProvider.EmbeddingResult embedText(String text) { return selected.embedText(text); }
    @Override public List<EmbeddingProvider.EmbeddingResult> embedTexts(List<String> texts) { return selected.embedTexts(texts); }
    @Override public String provider() { return selected.provider(); }
    @Override public String model() { return selected.model(); }
    @Override public int dimension() { return selected.dimension(); }
    @Override public boolean configured() { return selected.configured(); }

    private static final class LocalBgeKnowledgeEmbeddingProvider implements KnowledgeEmbeddingProvider {
        private final LocalBgeEmbeddingProvider delegate;
        private LocalBgeKnowledgeEmbeddingProvider(LocalBgeEmbeddingProvider delegate) { this.delegate = delegate; }
        @Override public EmbeddingProvider.EmbeddingResult embedText(String text) { return delegate.embedText(text); }
        @Override public List<EmbeddingProvider.EmbeddingResult> embedTexts(List<String> texts) { return delegate.embedTexts(texts); }
        @Override public String provider() { return "LOCAL"; }
        @Override public String model() { return "BAAI/bge-small-zh-v1.5"; }
        @Override public int dimension() { return 512; }
        @Override public boolean configured() { return true; }
    }
}
