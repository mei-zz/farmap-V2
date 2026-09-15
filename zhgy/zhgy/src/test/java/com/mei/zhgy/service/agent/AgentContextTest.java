package com.mei.zhgy.service.agent;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AgentContextTest {
    @Test void preservesStructuredContextAndSourceModes() {
        AgentContext context = AgentContext.builder().fieldId("A-12").crop("柑橘").imageUrls(List.of("img")).sourceModes(java.util.Map.of("camera", "LOCAL")).build();
        assertEquals("A-12", context.getFieldId()); assertEquals(1, context.imageCount()); assertEquals("LOCAL", context.getSourceModes().get("camera"));
    }
}
