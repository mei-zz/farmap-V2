package com.mei.zhgy.service.agent;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentOperationsIntegrationTest {
    @Test void approvedTaskToolUsesOperationsBoundary() {
        TaskTool tool = new TaskTool();
        ToolResult result = tool.execute(AgentToolRequest.builder().runId("agent-1").goal("巡检").approvalGranted(true).context(AgentContext.builder().fieldId("A-12").build()).build());
        assertEquals(ToolStatus.COMPLETED, result.getStatus());
        assertEquals("operations", result.getMetadata().get("system"));
    }
}
