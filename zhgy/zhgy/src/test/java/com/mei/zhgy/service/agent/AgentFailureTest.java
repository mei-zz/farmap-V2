package com.mei.zhgy.service.agent;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AgentFailureTest {
    @Test void unavailableToolIsRepresentedAndDoesNotBecomeSilentSuccess() {
        ToolResult result = new ToolExecutor(new ToolRegistry(List.of())).execute("missing", AgentToolRequest.builder().runId("r").build());
        assertEquals(ToolStatus.UNAVAILABLE, result.getStatus()); assertEquals("TOOL_NOT_REGISTERED", result.getErrorCode());
    }
}
