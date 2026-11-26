package com.mei.zhgy.service.rag;

import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.service.field.FieldContext;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EvidenceFusionCalibrationTest {
    private final EvidenceFusionService service = new EvidenceFusionService();

    @Test
    void topReciprocalRankEvidenceCanBeatNativeVisualScore() {
        double maxRrf = 2D / 61;
        var knowledge = evidence("knowledge", maxRrf, Map.of("rrfScore", maxRrf, "rrfUpperBound", maxRrf));
        var visual = evidence("camera", .90, Map.of());
        var lowRerank = evidence("knowledge", maxRrf,
                Map.of("rrfScore", maxRrf, "rrfUpperBound", maxRrf, "rerankScore", .52));
        assertEquals(List.of(knowledge, visual, lowRerank), service.fuse(List.of(visual, lowRerank, knowledge)));
        assertEquals(.95D, ((Number) knowledge.getMetadata().get("normalizedScore")).doubleValue(), .00001);
        assertEquals(.52D, ((Number) lowRerank.getMetadata().get("normalizedScore")).doubleValue(), .00001);
        assertEquals(maxRrf, knowledge.getScore());
    }

    @Test
    void environmentWeightsAndVectorThresholdAreAuditable() {
        var satellite = evidence("satellite", .1, Map.of("ndvi", .55, "captureTime", Instant.now().toString()));
        var thermal = evidence("accumulated_temperature", .1, Map.of("date", "2026-11-27", "temperature", 13, "accumulatedTemp", 1000));
        var low = evidence("camera", .42, Map.of("retrievalType", "VISUAL_SIMILARITY", "similarity", .42));
        service.fuse(List.of(low, satellite, thermal));
        assertEquals(0D, low.getMetadata().get("normalizedScore"));
        assertWeight(.85D, satellite);
        assertWeight(.92D, thermal);
    }

    @Test
    void observedPhenologyConflictAndRainAlertAreSeparateSignals() {
        var request = new DiagnosisAnalyzeRequest();
        request.setCrop("plum");
        request.setQuery("今天能喷药吗");
        request.setTrustedFieldContext(FieldContext.builder().field(Map.of("growthStage", "fruiting"))
                .weather(Map.of("status", "READY", "rainfallProbability24h", 80)).build());
        var observation = new VisualObservation("脆李", List.of("果面转色"), true, "成熟期", List.of(), null);
        var visual = evidence("camera", .90, Map.of("visualObservation", observation));
        var weather = evidence("weather", .8, Map.of());
        DiagnosisEvidenceConflicts.inspect(request, List.of(visual, weather));
        assertEquals("VISUAL_REGISTERED_STAGE_MISMATCH", visual.getMetadata().get("conflictReason"));
        assertEquals("RAIN_SPRAY_CONFLICT", weather.getMetadata().get("conflictReason"));
        assertTrue(DiagnosisEvidenceConflicts.flagged(List.of(visual, weather)));
    }

    private void assertWeight(double weight, DiagnosisEvidenceVO item) {
        double normalized = ((Number) item.getMetadata().get("normalizedScore")).doubleValue();
        assertTrue(normalized >= .75D && normalized <= .90D);
        assertEquals(normalized * weight, ((Number) item.getMetadata().get("fusedScore")).doubleValue(), .00001);
    }

    private DiagnosisEvidenceVO evidence(String modality, double score, Map<String, Object> metadata) {
        return DiagnosisEvidenceVO.builder().modality(modality).score(score).metadata(metadata).build();
    }
}
