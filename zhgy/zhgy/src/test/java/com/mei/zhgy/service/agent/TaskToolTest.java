package com.mei.zhgy.service.agent;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TaskToolTest {
    @Test void createsOperationTaskOnlyWhenInvokedAfterApproval() {
        TaskTool tool = new TaskTool();
        assertEquals(0, tool.createdTaskCount());
        assertEquals(ToolStatus.UNAVAILABLE, tool.execute(AgentToolRequest.builder().runId("r").goal("巡检").context(AgentContext.builder().fieldId("A-12").build()).build()).getStatus());
        ToolResult result = tool.execute(AgentToolRequest.builder().runId("r").goal("巡检").approvalGranted(true).context(AgentContext.builder().fieldId("A-12").build()).build());
        assertEquals(ToolStatus.COMPLETED, result.getStatus());
        assertEquals(1, tool.createdTaskCount());
    }
}
