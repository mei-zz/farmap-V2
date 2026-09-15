package com.mei.zhgy.service.agent;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ApprovalTaskAuditTest {
    @Test void taskAuditCarriesEvidenceAndRunIdentifiers() {
        AgentContext context = AgentContext.builder().fieldId("A-12").diagnosis(Map.of("id", "diag-1")).build();
        AgentToolRequest request = AgentToolRequest.builder().runId("agent-1").context(context).attributes(Map.of("evidenceIds", java.util.List.of("camera-1"))).build();
        assertEquals("agent-1", request.getRunId());
        assertEquals(java.util.List.of("camera-1"), request.getAttributes().get("evidenceIds"));
    }
}
