package com.mei.zhgy.service.rag;

import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class KnowledgeEvidenceRetrieverTest {
    @Test
    void packagedFallbackUsesBm25WithoutAnEmbeddingProvider() {
        KnowledgeEvidenceRetriever retriever = new KnowledgeEvidenceRetriever();
        retriever.loadKnowledge();
        DiagnosisAnalyzeRequest request = new DiagnosisAnalyzeRequest();
        request.setCrop("柑橘");
        request.setGrowthStage("果实膨大期");
        request.setQuery("叶片黄化与持续降雨");
        request.setTopK(5);
        assertFalse(retriever.retrieve(request).isEmpty());
    }
}
