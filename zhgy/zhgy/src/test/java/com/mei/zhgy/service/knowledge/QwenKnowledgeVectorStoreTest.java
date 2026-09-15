package com.mei.zhgy.service.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

class QwenKnowledgeVectorStoreTest {
    @Test
    void usesAnIsolatedQwenGenerationContract() {
        QwenKnowledgeVectorStore store = new QwenKnowledgeVectorStore(null, null, null, null,
                1024, 128, 20, 5, 0.45d);

        assertEquals("farmap_knowledge_chunks_qwen3_v1", QwenKnowledgeVectorStore.COLLECTION);
        assertEquals("qwen3.7-text-embedding-flash", QwenKnowledgeVectorStore.MODEL);
        assertEquals("UNAVAILABLE", store.health().get("status"));
        assertEquals("rrf", store.health().get("fusionMode"));
    }

    @Test
    void rrfUsesRanksWeightsAndAddsBothRouteContributions() {
        QwenKnowledgeVectorStore.KnowledgeVectorHit denseA = hit("a", 0.95D, 0D);
        QwenKnowledgeVectorStore.KnowledgeVectorHit denseB = hit("b", 0.90D, 0D);
        QwenKnowledgeVectorStore.KnowledgeVectorHit lexicalB = hit("b", 0D, 8D);
        QwenKnowledgeVectorStore.KnowledgeVectorHit lexicalC = hit("c", 0D, 7D);

        List<QwenKnowledgeVectorStore.KnowledgeVectorHit> result = QwenKnowledgeVectorStore.rrfFuse(
                List.of(denseA, denseB), List.of(lexicalB, lexicalC), 20, 60D, 2D, 0.5D);

        QwenKnowledgeVectorStore.KnowledgeVectorHit b = result.stream().filter(item -> "b".equals(item.chunkId)).findFirst().orElseThrow();
        assertEquals(2, b.denseRank);
        assertEquals(1, b.bm25Rank);
        assertEquals(2D / 62D + 0.5D / 61D, b.rrfScore, 1e-12);
        assertEquals(b.rrfScore, b.hybridScore, 1e-12);
    }

    @Test
    void rrfHandlesSingleRouteAndStableTies() {
        List<QwenKnowledgeVectorStore.KnowledgeVectorHit> denseOnly = QwenKnowledgeVectorStore.rrfFuse(
                List.of(hit("dense", 0.9D, 0D)), List.of(), 20, 60D, 1D, 1D);
        List<QwenKnowledgeVectorStore.KnowledgeVectorHit> lexicalOnly = QwenKnowledgeVectorStore.rrfFuse(
                List.of(), List.of(hit("lexical", 0D, 4D)), 20, 60D, 1D, 1D);
        assertEquals("dense", denseOnly.get(0).chunkId);
        assertEquals(1, denseOnly.get(0).denseRank);
        assertEquals(0, denseOnly.get(0).bm25Rank);
        assertEquals("lexical", lexicalOnly.get(0).chunkId);
        assertEquals(0, lexicalOnly.get(0).denseRank);
        assertEquals(1, lexicalOnly.get(0).bm25Rank);

        List<QwenKnowledgeVectorStore.KnowledgeVectorHit> first = QwenKnowledgeVectorStore.rrfFuse(
                List.of(hit("b", 0.8D, 0D), hit("a", 0.8D, 0D)),
                List.of(hit("b", 2D, 2D), hit("a", 2D, 2D)), 20, 60D, 1D, 1D);
        List<QwenKnowledgeVectorStore.KnowledgeVectorHit> second = QwenKnowledgeVectorStore.rrfFuse(
                List.of(hit("b", 0.8D, 0D), hit("a", 0.8D, 0D)),
                List.of(hit("b", 2D, 2D), hit("a", 2D, 2D)), 20, 60D, 1D, 1D);
        assertIterableEquals(List.of("a", "b"), first.stream().map(item -> item.chunkId).toList());
        assertIterableEquals(first.stream().map(item -> item.chunkId).toList(), second.stream().map(item -> item.chunkId).toList());
    }

    @Test
    void weightedModeRetainsLegacyLinearFormula() {
        List<QwenKnowledgeVectorStore.KnowledgeVectorHit> result = QwenKnowledgeVectorStore.weightedFuse(
                List.of(hit("a", 0.8D, 0D), hit("b", 0.4D, 0D)),
                List.of(hit("b", 0D, 10D), hit("c", 0D, 5D)), 20);

        assertIterableEquals(List.of("b", "a", "c"), result.stream().map(item -> item.chunkId).toList());
        assertEquals(0.55D * 0.5D + 0.45D, result.get(0).hybridScore, 1e-12);
    }

    private QwenKnowledgeVectorStore.KnowledgeVectorHit hit(String id, double vectorScore, double bm25Score) {
        QwenKnowledgeVectorStore.KnowledgeVectorHit hit = new QwenKnowledgeVectorStore.KnowledgeVectorHit(
                id, "source", "source", "document", "title", "path", "version", "content", "hash", "classification");
        hit.vectorScore = vectorScore;
        hit.bm25Score = bm25Score;
        return hit;
    }
}
