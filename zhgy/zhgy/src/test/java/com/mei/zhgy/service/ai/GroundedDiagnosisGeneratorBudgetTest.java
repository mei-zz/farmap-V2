package com.mei.zhgy.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class GroundedDiagnosisGeneratorBudgetTest {
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
