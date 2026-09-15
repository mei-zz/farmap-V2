package com.mei.zhgy.service.agent;

import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EvidenceSufficiencyEvaluatorTest {
    private final EvidenceSufficiencyEvaluator evaluator = new EvidenceSufficiencyEvaluator();

    @Test void noEvidenceBlocksWrite() {
        EvidenceSufficiencyDecision decision = evaluator.evaluate(true, List.of(), null, List.of(), List.of(), true);
        assertEquals(EvidenceSufficiencyStatus.INSUFFICIENT, decision.getStatus());
        assertFalse(decision.isAllowsWrite());
        assertTrue(decision.getReasonCodes().contains(EvidenceSufficiencyEvaluator.NO_TRUSTED_EVIDENCE));
        assertTrue(decision.getReasonCodes().contains(EvidenceSufficiencyEvaluator.EMPTY_RESULT));
    }

    @Test void currentEvidenceAllowsDiagnosisLevelWrite() {
        EvidenceSufficiencyDecision decision = evaluator.evaluate(true, evidence("e1"), null, List.of(), List.of(), false);
        assertEquals(EvidenceSufficiencyStatus.SUFFICIENT, decision.getStatus());
        assertTrue(decision.isAllowsWrite());
        assertEquals(List.of("e1"), decision.getAvailableEvidenceIds());
    }

    @Test void recommendationMustIntersectByEvidenceId() {
        EvidenceSufficiencyDecision supported = evaluator.evaluate(true, evidence("e1"), "r1", List.of("e1"), List.of(), false);
        EvidenceSufficiencyDecision unsupported = evaluator.evaluate(true, evidence("e1"), "r1", List.of("e2"), List.of(), false);
        assertTrue(supported.isAllowsWrite());
        assertEquals(EvidenceSufficiencyStatus.INSUFFICIENT, unsupported.getStatus());
        assertEquals(List.of(EvidenceSufficiencyEvaluator.RECOMMENDATION_EVIDENCE_MISSING), unsupported.getReasonCodes());
    }

    @Test void recommendationWithoutSnapshotEvidenceBlocksEvenWithOtherEvidence() {
        EvidenceSufficiencyDecision decision = evaluator.evaluate(true, evidence("e1"), "r1", List.of(), List.of(), false);
        assertEquals(EvidenceSufficiencyStatus.INSUFFICIENT, decision.getStatus());
        assertFalse(decision.isAllowsWrite());
        assertTrue(decision.getReasonCodes().contains(EvidenceSufficiencyEvaluator.RECOMMENDATION_EVIDENCE_MISSING));
    }

    @Test void invalidRequestedEvidenceIdIsRejected() {
        EvidenceSufficiencyDecision decision = evaluator.evaluate(true, evidence("e1"), null, List.of(), List.of("e2"), List.of(), false);
        assertEquals(EvidenceSufficiencyStatus.INSUFFICIENT, decision.getStatus());
        assertTrue(decision.getReasonCodes().contains(EvidenceSufficiencyEvaluator.EVIDENCE_PROVENANCE_INVALID));
    }

    @Test void unavailableDependencyDegradesAndDoesNotWrite() {
        EvidenceSufficiencyDecision decision = evaluator.evaluate(true, evidence("e1"), null, List.of(), List.of("camera"), false);
        assertEquals(EvidenceSufficiencyStatus.DEGRADED, decision.getStatus());
        assertFalse(decision.isAllowsWrite());
    }

    @Test void emptyAndUnavailableRemainDistinctDecisionFacts() {
        EvidenceSufficiencyDecision empty = evaluator.evaluate(true, List.of(), null, List.of(), List.of(), true);
        EvidenceSufficiencyDecision unavailable = evaluator.evaluate(true, List.of(), null, List.of(), List.of("camera"), false);
        assertTrue(empty.getReasonCodes().contains(EvidenceSufficiencyEvaluator.EMPTY_RESULT));
        assertFalse(unavailable.getReasonCodes().contains(EvidenceSufficiencyEvaluator.EMPTY_RESULT));
        assertTrue(unavailable.getReasonCodes().contains(EvidenceSufficiencyEvaluator.UNAVAILABLE_DEPENDENCY));
    }

    private List<DiagnosisEvidenceVO> evidence(String id) {
        return List.of(DiagnosisEvidenceVO.builder().id(id).modality("camera").title("图像").summary("可验证观察").score(.9).build());
    }
}
