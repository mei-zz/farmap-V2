package com.mei.zhgy.service.agent;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PageContextAdapterTest {
    @Test void contextContractCarriesAllDecisionInputs() {
        AgentContext context = AgentContext.builder().farmId("1").fieldId("A-12").crop("柑橘").page("/overview").diagnosis(Map.of("id", "diag-1")).build();
        assertEquals("A-12", context.getFieldId());
        assertEquals("diag-1", context.getDiagnosis().get("id"));
        assertEquals("/overview", context.getPage());
    }
}
