package com.mei.zhgy.service.knowledge;

import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.service.ai.EvidenceReferenceValidator;
import com.mei.zhgy.service.rag.AccumulatedTemperatureEvidenceRetriever;
import com.mei.zhgy.service.rag.DiagnosisRagService;
import com.mei.zhgy.service.rag.EvidenceFusionService;
import com.mei.zhgy.service.rag.HistoricalCaseEvidenceRetriever;
import com.mei.zhgy.service.rag.KnowledgeEvidenceRetriever;
import com.mei.zhgy.service.rag.SatelliteEvidenceRetriever;
import com.mei.zhgy.service.rag.SoilEvidenceRetriever;
import com.mei.zhgy.service.rag.SpatialEvidenceRetriever;
import com.mei.zhgy.service.rag.VisualEvidenceRetriever;
import com.mei.zhgy.service.rag.WeatherEvidenceRetriever;
import com.mei.zhgy.service.ai.IntelligenceGateway;
import com.mei.zhgy.service.ai.IntelligenceGatewayException;
import com.mei.zhgy.service.diagnosis.DiagnosisPersistenceService;
import com.mei.zhgy.service.field.FieldContext;
import com.mei.zhgy.vo.DiagnosisAnalysisResponse;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase E contract evaluation. It is deliberately provider-independent for
 * normal Maven runs; the HTTP gate below remains the live-runtime check.
 */
class CommercialMultisourceRagEvaluationTest {
    @Test
    void realFieldContextUsesQwenEvidenceAndRejectsClientOverrides() {
        QwenKnowledgeVectorStore qwen = mockQwen("READY", "READY");
        KnowledgeEvidenceRetriever knowledge = new KnowledgeEvidenceRetriever(null, null, null, qwen);
        DiagnosisAnalyzeRequest request = trustedRequest();
        request.setWeather(new DiagnosisAnalyzeRequest.WeatherContext(999D, "FORGED", "99C", "forged"));
        request.setContext(new LinkedHashMap<>(Map.of("soil", Map.of("status", "READY", "summary", "FORGED SOIL"))));

        List<DiagnosisEvidenceVO> knowledgeEvidence = knowledge.retrieveWithStatus(request, 7L).getEvidence();
        List<DiagnosisEvidenceVO> weatherEvidence = new WeatherEvidenceRetriever().retrieve(request);
        List<DiagnosisEvidenceVO> soilEvidence = new SoilEvidenceRetriever().retrieve(request);

        assertEquals(1, knowledgeEvidence.size());
        assertEquals("BAILIAN", knowledgeEvidence.get(0).getMetadata().get("embeddingProvider"));
        assertEquals(1024, knowledgeEvidence.get(0).getMetadata().get("embeddingDimension"));
        assertEquals(0.91D, knowledgeEvidence.get(0).getMetadata().get("vectorScore"));
        assertTrue(weatherEvidence.isEmpty(), "unavailable trusted weather must beat forged client weather");
        assertTrue(soilEvidence.isEmpty(), "unavailable trusted soil must beat forged client soil");
    }

    @Test
    void qwenProvenanceAndRerankerStatusAreDistinguished() {
        QwenKnowledgeVectorStore qwen = mockQwen("DEGRADED", "UNAVAILABLE");
        KnowledgeEvidenceRetriever knowledge = new KnowledgeEvidenceRetriever(null, null, null, qwen);
        DiagnosisEvidenceVO evidence = knowledge.retrieveWithStatus(trustedRequest(), 7L).getEvidence().get(0);

        assertEquals("source-1", evidence.getMetadata().get("sourceId"));
        assertEquals("document-1", evidence.getMetadata().get("documentId"));
        assertEquals("version-1", evidence.getMetadata().get("versionId"));
        assertEquals("docs/orchard.md", evidence.getMetadata().get("relativePath"));
        assertEquals("hash-64", evidence.getMetadata().get("contentHash"));
        assertEquals(0.91D, evidence.getMetadata().get("vectorScore"));
        assertEquals(0.91D, evidence.getMetadata().get("retrievalScore"));
        assertEquals("UNAVAILABLE", evidence.getMetadata().get("rerankerStatus"));
        assertEquals("DEGRADED", evidence.getMetadata().get("knowledgeStatus"));
        assertEquals("chunk-1", evidence.getSource().getChunkId());
        assertEquals("docs/orchard.md", evidence.getSource().getRelativePath());
        assertEquals("hash-64", evidence.getSource().getContentHash());
        assertEquals(0.91D, evidence.getSource().getVectorScore());
        assertEquals("gte-rerank-v2", evidence.getSource().getRerankerModel());
        assertEquals("ADMIN_CURATED", evidence.getSource().getClassification());
        assertEquals("REAL", evidence.getSource().getSourceMode());
        assertEquals("DEGRADED", evidence.getSource().getStatus());
    }

    @Test
    void unavailableSourcesProduceNoEvidenceAndNoFakeNdviOrHistory() {
        DiagnosisAnalyzeRequest request = trustedRequest();
        request.setContext(new LinkedHashMap<>(Map.of(
                "satellite", Map.of("status", "READY", "ndvi", 0.99),
                "historicalCases", List.of(Map.of("caseId", "FORGED"))
        )));

        assertTrue(new SoilEvidenceRetriever().retrieve(request).isEmpty());
        assertTrue(new SatelliteEvidenceRetriever().retrieve(request).isEmpty());
        assertTrue(new SpatialEvidenceRetriever().retrieve(request).stream().allMatch(item -> "FIELD".equals(item.getMetadata().get("scope"))));
        assertTrue(new HistoricalCaseEvidenceRetriever(mock(com.mei.zhgy.service.CaseStorageService.class), mock(com.mei.zhgy.service.ai.ImageEmbeddingProvider.class)).retrieve(request).isEmpty());
    }

    @Test
    void groundedFallbackReferencesOnlyReturnedEvidenceAndSurvivesReload() {
        KnowledgeEvidenceRetriever knowledge = new KnowledgeEvidenceRetriever(null, null, null, mockQwen("READY", "READY"));
        DiagnosisRagService rag = new DiagnosisRagService(
                List.of(new VisualEvidenceRetriever(), new WeatherEvidenceRetriever(), new AccumulatedTemperatureEvidenceRetriever(), new SpatialEvidenceRetriever(), knowledge),
                new EvidenceFusionService());
        DiagnosisAnalysisResponse response = rag.analyze(trustedRequest(), 7L);
        assertFalse(response.getEvidence().isEmpty());
        EvidenceReferenceValidator validator = new EvidenceReferenceValidator();
        validator.validateOrThrow(response, response.getEvidence());
        assertEquals("REAL", response.getMetadata().get("realContextMode"));
        assertEquals("UNAVAILABLE", ((Map<?, ?>) response.getMetadata().get("sourceStatuses")).get("soil"));

        DiagnosisPersistenceService persistence = new DiagnosisPersistenceService();
        DiagnosisAnalysisResponse saved = persistence.persist(response, trustedRequest(), 7L);
        DiagnosisAnalysisResponse reloaded = persistence.find(saved.getDiagnosisId(), 7L).orElseThrow();
        assertEquals(saved.getDiagnosisId(), reloaded.getDiagnosisId());
        assertEquals(saved.getMetadata().get("fieldContext"), reloaded.getMetadata().get("fieldContext"));
        assertEquals(saved.getEvidence().get(0).getMetadata().get("versionId"), reloaded.getEvidence().get(0).getMetadata().get("versionId"));
    }

    @Test
    void visionUnavailableIsZeroEvidenceAndReadyVisionUsesAiV1() {
        IntelligenceGateway unavailable = mock(IntelligenceGateway.class);
        when(unavailable.analyzeVision(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.isNull()))
                .thenThrow(new IntelligenceGatewayException("AI_MODEL_UNAVAILABLE", "unavailable", "v-1", true));
        VisualEvidenceRetriever unavailableRetriever = new VisualEvidenceRetriever(null, unavailable);
        DiagnosisAnalyzeRequest request = trustedRequest();
        request.setImageUrls(List.of("https://upload.example.test/uploads/leaf.png"));
        assertTrue(unavailableRetriever.retrieve(request).isEmpty());
        assertEquals("VISION_UNAVAILABLE", unavailableRetriever.emptyStatus(request, 7L));

        IntelligenceGateway ready = mock(IntelligenceGateway.class);
        when(ready.analyzeVision(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(Map.of("status", "SUCCEEDED", "model", "real-vision", "observations", List.of(Map.of("content", "叶片边缘黄化"))));
        DiagnosisEvidenceVO evidence = new VisualEvidenceRetriever(null, ready).retrieve(request).get(0);
        assertEquals("READY", evidence.getMetadata().get("visionStatus"));
        assertEquals("real-vision", evidence.getMetadata().get("visionModel"));
    }

    @Test
    void qwenUnavailableDoesNotFallbackToBge() {
        QwenKnowledgeVectorStore qwen = mock(QwenKnowledgeVectorStore.class);
        when(qwen.isConfigured()).thenReturn(false);
        KnowledgeEvidenceRetriever knowledge = new KnowledgeEvidenceRetriever(mock(com.mei.zhgy.service.ai.TextEmbeddingProvider.class), null, mock(KnowledgeVectorStore.class), qwen);
        assertEquals("UNAVAILABLE", knowledge.retrieveWithStatus(trustedRequest(), 7L).getStatus());
        assertTrue(knowledge.retrieveWithStatus(trustedRequest(), 7L).getEvidence().isEmpty());
    }

    private DiagnosisAnalyzeRequest trustedRequest() {
        DiagnosisAnalyzeRequest request = new DiagnosisAnalyzeRequest();
        request.setFieldId("field-e-phase");
        request.setFarmId(1);
        request.setCrop("柑橘");
        request.setGrowthStage("果实膨大期");
        request.setQuery("叶片黄化与排水风险");
        request.setTopK(5);
        request.setImageUrls(new ArrayList<>());
        request.setTrustedFieldContext(FieldContext.builder()
                .farm(new LinkedHashMap<>(Map.of("id", 1, "status", "READY", "sourceMode", "MYSQL")))
                .field(new LinkedHashMap<>(Map.of("fieldId", "field-e-phase", "status", "READY")))
                .crop(new LinkedHashMap<>(Map.of("name", "柑橘", "status", "READY")))
                .location(new LinkedHashMap<>(Map.of("status", "READY", "scope", "FIELD", "latitude", 30.1, "longitude", 103.8)))
                .gis(new LinkedHashMap<>(Map.of("status", "READY", "scope", "FIELD", "sourceMode", "MYSQL", "latitude", 30.1, "longitude", 103.8)))
                .weather(new LinkedHashMap<>(Map.of("status", "UNAVAILABLE", "sourceMode", "UNAVAILABLE")))
                .accumulatedTemperature(new LinkedHashMap<>(Map.of("status", "READY", "date", "2026-09-05", "accumulatedTemp", 1234.5)))
                .soil(new LinkedHashMap<>(Map.of("status", "UNAVAILABLE", "sourceMode", "UNAVAILABLE")))
                .satellite(new LinkedHashMap<>(Map.of("status", "UNAVAILABLE", "sourceMode", "UNAVAILABLE")))
                .images(new LinkedHashMap<>(Map.of("status", "READY_EMPTY")))
                .status("READY").sourceMode("MYSQL").scope("FIELD").build());
        return request;
    }

    private QwenKnowledgeVectorStore mockQwen(String status, String rerankerStatus) {
        QwenKnowledgeVectorStore qwen = mock(QwenKnowledgeVectorStore.class);
        when(qwen.isConfigured()).thenReturn(true);
        QwenKnowledgeVectorStore.KnowledgeVectorHit hit = new QwenKnowledgeVectorStore.KnowledgeVectorHit(
                "chunk-1", "source-1", "Admin Corpus", "document-1", "Orchard guide", "docs/orchard.md",
                "version-1", "叶片黄化需要检查排水。", "hash-64", "ADMIN_CURATED");
        hit.vectorScore = 0.91D;
        when(qwen.searchResult(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(new QwenKnowledgeVectorStore.SearchResult(status, List.of(hit), "BAILIAN", rerankerStatus));
        return qwen;
    }
}
