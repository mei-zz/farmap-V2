package com.mei.zhgy.service.ai;

import com.mei.zhgy.vo.DiagnosisAnalysisResponse;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EvidenceReferenceValidatorTest {
    private final EvidenceReferenceValidator validator = new EvidenceReferenceValidator();

    @Test
    void acceptsOnlyRetrieverEvidenceIds() {
        DiagnosisEvidenceVO evidence = DiagnosisEvidenceVO.builder().id("camera_1").build();
        DiagnosisAnalysisResponse response = responseWithIds(Collections.singletonList("camera_1"));
        assertDoesNotThrow(() -> validator.validateOrThrow(response, Collections.singletonList(evidence)));
    }

    @Test
    void rejectsUnknownEvidenceIds() {
        DiagnosisEvidenceVO evidence = DiagnosisEvidenceVO.builder().id("camera_1").build();
        DiagnosisAnalysisResponse response = responseWithIds(Collections.singletonList("invented_source"));
        assertThrows(EvidenceReferenceValidator.InvalidEvidenceReferenceException.class,
                () -> validator.validateOrThrow(response, Collections.singletonList(evidence)));
    }

    private DiagnosisAnalysisResponse responseWithIds(List<String> ids) {
        DiagnosisAnalysisResponse.Claim claim = DiagnosisAnalysisResponse.Claim.builder()
                .id("claim_1").text("grounded claim").evidenceIds(ids).build();
        DiagnosisAnalysisResponse.Recommendation recommendation = DiagnosisAnalysisResponse.Recommendation.builder()
                .id("recommendation_1").title("inspect").priority("medium").detail("inspect").evidenceIds(ids).build();
        return DiagnosisAnalysisResponse.builder()
                .claims(Collections.singletonList(claim))
                .recommendations(Collections.singletonList(recommendation))
                .build();
    }
}
