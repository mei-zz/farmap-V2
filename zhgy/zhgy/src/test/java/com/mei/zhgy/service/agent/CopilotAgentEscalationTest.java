package com.mei.zhgy.service.agent;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CopilotAgentEscalationTest {
    @Test void complexGoalsAreRepresentedAsAgentRuns() {
        AgentRun run = AgentRun.builder().runId("agent-1").goal("综合分析并制定计划").context(new AgentContext()).build();
        assertTrue(run.getGoal().contains("综合分析"));
    }
}
