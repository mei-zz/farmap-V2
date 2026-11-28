package com.mei.zhgy.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class GroundedDiagnosisGeneratorBudgetTest {
    @Test
    void aFlaggedEvidenceConflictRequestsExpertReviewEvenWithHighInitialConfidence() {
        var gateway = mock(ModelGateway.class);
        var generator = new GroundedDiagnosisGenerator(gateway, mock(EvidenceReferenceValidator.class),
                mock(ModelEscalationPolicy.class), new ObjectMapper());
        when(gateway.complete(any())).thenAnswer(invocation -> {
            ModelCallRequest input = invocation.getArgument(0);
            return ModelCallResult.builder().selectedModel("controlled-model")
                    .content(input.getTaskType() == AiTaskType.HARD_DIAGNOSIS
                            ? "{\"diagnosis\":{\"title\":\"需要现场复核\"}}"
                            : "{\"diagnosis\":{\"title\":\"初步诊断\",\"confidence\":0.97,\"riskLevel\":\"low\"}}")
                    .build();
        });
        var evidence = DiagnosisEvidenceVO.builder().id("visual-1").modality("camera").title("观测")
                .metadata(Map.of("evidenceConflict", true, "conflictReason", "VISUAL_REGISTERED_STAGE_MISMATCH")).build();
        var result = generator.generate(new DiagnosisAnalyzeRequest(), "review-conflict", List.of(evidence));
        assertTrue(result.isEscalated());
        assertEquals("evidence_conflict", result.getEscalationReason());
        assertEquals("需要现场复核", result.getResponse().getDiagnosis().getTitle());
        var requests = org.mockito.ArgumentCaptor.forClass(ModelCallRequest.class);
        verify(gateway, times(2)).complete(requests.capture());
        assertEquals(AiTaskType.HARD_DIAGNOSIS, requests.getAllValues().get(1).getTaskType());
        assertEquals(true, requests.getAllValues().get(1).getContext().get("evidenceConflict"));
    }

    @Test
    void keepsConfiguredDiagnosisBudgetAboveFormer4096Cap() throws Exception {
        GroundedDiagnosisGenerator generator = new GroundedDiagnosisGenerator(
                mock(ModelGateway.class), mock(EvidenceReferenceValidator.class),
                mock(ModelEscalationPolicy.class), new ObjectMapper());
        Field budget = GroundedDiagnosisGenerator.class.getDeclaredField("diagnosisMaxOutputTokens");
        budget.setAccessible(true);
        budget.setInt(generator, 5000);

        Method builder = GroundedDiagnosisGenerator.class.getDeclaredMethod("buildRequest",
                DiagnosisAnalyzeRequest.class, String.class, List.class, AiTaskType.class, String.class);
        builder.setAccessible(true);
        ModelCallRequest request = (ModelCallRequest) builder.invoke(generator,
                new DiagnosisAnalyzeRequest(), "run-1", List.<DiagnosisEvidenceVO>of(),
                AiTaskType.MULTIMODAL_DIAGNOSIS, "test");

        assertEquals(5000, request.getContext().get("maxOutputTokens"));
    }
}
