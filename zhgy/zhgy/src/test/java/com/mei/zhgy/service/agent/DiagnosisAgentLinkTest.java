package com.mei.zhgy.service.agent;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DiagnosisAgentLinkTest {
    @Test void diagnosisIdAndEvidenceAreAvailableToAgent() {
        AgentContext context = AgentContext.builder().fieldId("A-12").diagnosis(Map.of("id", "a12-demo", "confidence", 0.88)).build();
        assertEquals("a12-demo", context.getDiagnosis().get("id"));
        assertEquals("A-12", context.getFieldId());
    }
}
