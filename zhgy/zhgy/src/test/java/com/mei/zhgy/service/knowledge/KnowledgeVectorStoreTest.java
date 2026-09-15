package com.mei.zhgy.service.knowledge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KnowledgeVectorStoreTest {
    @Test
    void knowledgeStoreHasAnIndependentCosineContract() {
        KnowledgeVectorStore store = new KnowledgeVectorStore(null, null, null, 128);

        assertEquals("farmap_knowledge_chunks_v1", KnowledgeVectorStore.COLLECTION);
        assertEquals(512, KnowledgeVectorStore.DIMENSION);
        assertEquals("UNAVAILABLE", store.health().get("status"));
    }
}
