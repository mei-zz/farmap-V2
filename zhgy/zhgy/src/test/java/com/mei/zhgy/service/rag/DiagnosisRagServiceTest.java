package com.mei.zhgy.service.rag;

import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.vo.DiagnosisAnalysisResponse;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosisRagServiceTest {
    @Test
    void a12MvpUsesCameraWeatherAndKnowledgeEvidence() {
        KnowledgeEvidenceRetriever knowledgeRetriever = new KnowledgeEvidenceRetriever();
        ReflectionTestUtils.setField(knowledgeRetriever, "resourcePath", "knowledge/orchard_management.txt");
        knowledgeRetriever.loadKnowledge();

        DiagnosisRagService service = new DiagnosisRagService(
                Arrays.asList(
                        new VisualEvidenceRetriever(),
                        new WeatherEvidenceRetriever(),
                        knowledgeRetriever),
                new EvidenceFusionService());

        DiagnosisAnalyzeRequest request = new DiagnosisAnalyzeRequest();
        request.setFieldId("A-12");
        request.setFarmId(9001);
        request.setCrop("柑橘");
        request.setGrowthStage("果实膨大期");
        request.setImageUrls(List.of("https://example.test/a12-leaf.png"));
        request.setWeather(new DiagnosisAnalyzeRequest.WeatherContext(58D, "多云", "24°C", "2026-09-04T08:00:00Z"));
        request.setQuery("叶片黄化与持续降雨");
        request.setTopK(5);

        DiagnosisAnalysisResponse response = service.analyze(request, 7L);

        Set<String> modalities = response.getEvidence().stream()
                .map(DiagnosisEvidenceVO::getModality)
                .collect(Collectors.toSet());
        Set<String> evidenceIds = response.getEvidence().stream()
                .map(DiagnosisEvidenceVO::getId)
                .collect(Collectors.toCollection(HashSet::new));

        assertEquals(Set.of("camera", "weather", "knowledge"), modalities);
        assertFalse(response.getClaims().isEmpty());
        assertTrue(response.getClaims().stream()
                .flatMap(claim -> claim.getEvidenceIds().stream())
                .allMatch(evidenceIds::contains));
        assertEquals("high", response.getDiagnosis().getRiskLevel());
        assertEquals("real", response.getMetadata().get("mode"));
        assertTrue(response.getMetadata().containsKey("retrievalLatencyMs"));
    }
}

