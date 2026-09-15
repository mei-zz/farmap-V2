package com.mei.zhgy.service.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeBm25Test {
    @Test
    void ChineseAgriculturalTermsAreRankedBeforeSemanticallyBroadText() {
        List<KnowledgeBm25.ScoredDocument> ranked = new KnowledgeBm25().rank(List.of(
                new KnowledgeBm25.Document("citrus", "柑橘果实膨大期连续降雨后检查排水和病斑。"),
                new KnowledgeBm25.Document("plum", "脆李采后恢复期检查秋梢和树势。"),
                new KnowledgeBm25.Document("generic", "果树管理要结合天气和树势。")
        ), "柑橘 连续降雨 排水", 3);

        assertFalse(ranked.isEmpty());
        assertEquals("citrus", ranked.get(0).document().id());
    }

    @Test
    void cachedCorpusKeepsLegacyScoresAndOrder() {
        KnowledgeBm25 scorer = new KnowledgeBm25();
        List<KnowledgeBm25.Document> documents = List.of(
                new KnowledgeBm25.Document("a", "脆李采后恢复期检查秋梢和树势。"),
                new KnowledgeBm25.Document("b", "脆李采后恢复期检查秋梢。"),
                new KnowledgeBm25.Document("c", "果树管理要结合天气。"));

        List<KnowledgeBm25.ScoredDocument> legacy = scorer.rank(documents, "脆李 秋梢", 3);
        List<KnowledgeBm25.ScoredDocument> cached = scorer.buildCorpus(documents).scoreAgainst("脆李 秋梢", 3);

        assertIterableEquals(legacy.stream().map(item -> item.document().id()).toList(),
                cached.stream().map(item -> item.document().id()).toList());
        for (int i = 0; i < legacy.size(); i++) assertEquals(legacy.get(i).score(), cached.get(i).score(), 1e-12);
    }

    @Test
    void actorScopedBm25CacheHitsOnlyForSameFingerprintAndActor() {
        QwenKnowledgeVectorStore.Bm25IndexCache cache = new QwenKnowledgeVectorStore.Bm25IndexCache(2);
        KnowledgeBm25.Bm25Corpus corpus = new KnowledgeBm25().buildCorpus(List.of(
                new KnowledgeBm25.Document("a", "脆李采后恢复期")));
        AtomicInteger builds = new AtomicInteger();

        cache.getOrBuild(7L, "v1", () -> {
            builds.incrementAndGet();
            return new QwenKnowledgeVectorStore.CachedBm25Corpus("v1", corpus, Map.of());
        });
        cache.getOrBuild(7L, "v1", () -> {
            builds.incrementAndGet();
            return new QwenKnowledgeVectorStore.CachedBm25Corpus("v1", corpus, Map.of());
        });
        cache.getOrBuild(7L, "v2", () -> {
            builds.incrementAndGet();
            return new QwenKnowledgeVectorStore.CachedBm25Corpus("v2", corpus, Map.of());
        });
        cache.getOrBuild(8L, "v1", () -> {
            builds.incrementAndGet();
            return new QwenKnowledgeVectorStore.CachedBm25Corpus("v1", corpus, Map.of());
        });

        assertEquals(3, builds.get());
        assertEquals(1, cache.hitCount());
        assertEquals(3, cache.rebuildCount());
        assertTrue(cache.size() <= 2);
    }
}
