package com.mei.zhgy.service.agent;

import com.mei.zhgy.service.ai.ModelGateway;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AgentRuntimeTest {
    @Test void executesReadOnlyPlanToCompletion() {
        AgentPlanner planner = new AgentPlanner(mock(ModelGateway.class), false, 4, 4, 120000) { @Override public AgentPlan plan(AgentRun run) { return AgentPlan.builder().steps(List.of(AgentPlanStep.builder().id("s").toolName("field-context").readOnly(true).build())).maxSteps(4).maxToolCalls(4).timeoutMs(120000).plannerModel("qwen3.6-plus").schemaVersion("test").build(); } };
        ToolRegistry registry = new ToolRegistry(List.of(new FieldContextTool()));
        AgentRuntime runtime = new AgentRuntime(planner, new ToolExecutor(registry), new InMemoryAgentRunRepository(), new TraceRecorder(), new ApprovalManager());
        AgentRun run = runtime.createRun("查看地块", AgentContext.builder().fieldId("A-12").build(), "real");
        AgentRun completed = runtime.execute(run.getRunId());
        assertEquals(RunStatus.COMPLETED, completed.getStatus()); assertEquals(1, completed.getToolCalls().size());
    }
}
