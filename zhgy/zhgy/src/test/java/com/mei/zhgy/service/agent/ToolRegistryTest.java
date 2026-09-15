package com.mei.zhgy.service.agent;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryTest {
    @Test void registersToolsByStableName() {
        AgentTool tool = new FieldContextTool();
        ToolRegistry registry = new ToolRegistry(List.of(tool));
        assertTrue(registry.contains("field-context"));
        assertEquals(tool, registry.get("field-context"));
    }
}
