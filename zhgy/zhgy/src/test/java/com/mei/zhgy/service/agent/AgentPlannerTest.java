package com.mei.zhgy.service.agent;

import com.mei.zhgy.service.ai.ModelGateway;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AgentPlannerTest {
    @Test void createsTypedBoundedPlan() {
        AgentPlanner planner = new AgentPlanner(mock(ModelGateway.class), false, 8, 12, 120000);
        AgentRun run = AgentRun.builder().runId("r1").goal("分析叶片黄化").context(AgentContext.builder().imageUrls(java.util.List.of("data:image/png;base64,x")).weather(Map.of("rainfall14d", 58)).build()).build();
        AgentPlan plan = planner.plan(run);
        assertEquals("agent-plan-v1", plan.getSchemaVersion());
        assertTrue(plan.getSteps().stream().allMatch(s -> s.getToolName() != null));
    }
}
