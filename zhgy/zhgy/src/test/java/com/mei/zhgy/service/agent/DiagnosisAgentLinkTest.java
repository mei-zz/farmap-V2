package com.mei.zhgy.service.agent;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DiagnosisAgentLinkTest {
    @Test void diagnosisIdAndEvidenceAreAvailableToAgent() {
        AgentContext context = AgentContext.builder().fieldId("A-12").diagnosisId("diagnosis-a12-1").diagnosis(Map.of("id", "rag-legacy", "confidence", 0.88)).build();
        assertEquals("diagnosis-a12-1", context.getDiagnosisId());
        assertEquals("rag-legacy", context.getDiagnosis().get("id"));
        assertEquals("A-12", context.getFieldId());
    }
}
