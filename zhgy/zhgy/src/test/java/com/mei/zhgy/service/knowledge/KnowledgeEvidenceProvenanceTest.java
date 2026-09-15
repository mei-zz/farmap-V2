package com.mei.zhgy.service.knowledge;

import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.service.rag.KnowledgeEvidenceRetriever;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeEvidenceProvenanceTest {
    @Test
    void retrievalCarriesStableSourceDocumentAndVersionIdentity() {
        KnowledgeBackendService backend = new KnowledgeBackendService();
        backend.ensurePackagedKnowledge("classpath:test.txt", "叶片黄化\n\n检查排水沟");
        KnowledgeEvidenceRetriever retriever = new KnowledgeEvidenceRetriever(null, backend);
        DiagnosisAnalyzeRequest request = new DiagnosisAnalyzeRequest();
        request.setFieldId("F-1"); request.setCrop("柑橘"); request.setQuery("叶片黄化"); request.setTopK(3);
        KnowledgeEvidenceRetriever.RetrievalResult result = retriever.retrieveWithStatus(request, 7L);
        assertEquals("READY", result.getStatus());
        DiagnosisEvidenceVO evidence = result.getEvidence().get(0);
        assertNotNull(evidence.getSource().getSourceId());
        assertNotNull(evidence.getSource().getDocumentId());
        assertNotNull(evidence.getSource().getVersionId());
        assertTrue(evidence.getId().contains(evidence.getSource().getVersionId()));
        assertEquals(evidence.getSource().getVersionId(), evidence.getMetadata().get("versionId"));
    }
}
