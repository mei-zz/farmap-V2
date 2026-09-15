package com.mei.zhgy.service.agent;

import com.mei.zhgy.service.ai.ModelGateway;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ProductionWriteSafetyTest {
    @Test
    void firstApproveCreatesOneTaskAndSequentialRetryReturnsSameTask() {
        Harness harness = harness();
        AgentRun waiting = pending(harness.runtime);
        String actionId = waiting.getActions().get(0).getId();

        AgentRun first = harness.runtime.approve(waiting.getRunId(), actionId);
        String taskId = taskId(first);
        AgentRun retry = harness.runtime.approve(waiting.getRunId(), actionId);

        assertEquals(RunStatus.COMPLETED, first.getStatus());
        assertEquals(taskId, taskId(retry));
        assertEquals(1, harness.taskTool.createdTaskCount());
        assertEquals(1, retry.getToolCalls().size(), "重复批准不能再次调用 TaskTool");
    }

    @Test
    void parallelApproveHasOneExecutionClaimAndOneTask() throws Exception {
        Harness harness = harness();
        AgentRun waiting = pending(harness.runtime);
        String actionId = waiting.getActions().get(0).getId();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Callable<AgentRun>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) calls.add(() -> harness.runtime.approve(waiting.getRunId(), actionId));
        pool.invokeAll(calls);
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(1, harness.taskTool.createdTaskCount());
        assertEquals(RunStatus.COMPLETED, harness.runtime.require(waiting.getRunId()).getStatus());
        assertEquals(1, harness.runtime.require(waiting.getRunId()).getToolCalls().size(), "并发重复批准只能有一次工具调用");
    }

    @Test
    void approveAndRejectRaceHasOneDurableWinner() throws Exception {
        InMemoryApprovalStore store = new InMemoryApprovalStore();
        ApprovalManager manager = new ApprovalManager(store);
        ProposedAction action = ProposedAction.builder().id("race-action").approvalState(ApprovalState.REQUIRED).build();
        AgentRun run = AgentRun.builder().runId("race-run").status(RunStatus.WAITING_FOR_APPROVAL).actions(new ArrayList<>(List.of(action))).build();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger approved = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        pool.submit(() -> { try { manager.approve(run, "race-action"); approved.incrementAndGet(); } catch (ApprovalException ignored) { } });
        pool.submit(() -> { try { manager.reject(run, "race-action"); rejected.incrementAndGet(); } catch (ApprovalException ignored) { } });
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(1, approved.get() + rejected.get());
        assertNotEquals(ApprovalState.REQUIRED, action.getApprovalState());
    }

    @Test
    void rejectThenApproveDoesNotCreateTask() {
        InMemoryApprovalStore store = new InMemoryApprovalStore();
        ApprovalManager manager = new ApprovalManager(store);
        ProposedAction action = ProposedAction.builder().id("reject-action").approvalState(ApprovalState.REQUIRED).build();
        AgentRun run = AgentRun.builder().runId("reject-run").status(RunStatus.WAITING_FOR_APPROVAL).actions(new ArrayList<>(List.of(action))).build();
        manager.reject(run, "reject-action");
        assertThrows(ApprovalException.class, () -> manager.approve(run, "reject-action"));
        assertEquals(ApprovalState.REJECTED, action.getApprovalState());
    }

    @Test
    void directTaskDuplicateActionIdReturnsSameTask() {
        TaskTool tool = new TaskTool();
        AgentToolRequest request = AgentToolRequest.builder().runId("direct-run").actionId("direct-action").goal("巡检").approvalGranted(true)
                .context(AgentContext.builder().fieldId("A-12").build()).build();
        String first = taskId(tool.execute(request));
        String second = taskId(tool.execute(request));
        assertEquals(first, second);
        assertEquals(1, tool.createdTaskCount());
    }

    @Test
    void productionApprovalWithoutDatasourceFailsClosed() {
        ApprovalManager manager = new ApprovalManager((DataSource) null);
        ProposedAction action = ProposedAction.builder().id("db-action").approvalState(ApprovalState.REQUIRED).build();
        AgentRun run = AgentRun.builder().runId("db-run").status(RunStatus.WAITING_FOR_APPROVAL).actions(new ArrayList<>(List.of(action))).build();
        ApprovalException error = assertThrows(ApprovalException.class, () -> manager.approve(run, "db-action"));
        assertEquals("PERSISTENCE_UNAVAILABLE", error.getCode());
        assertEquals(ApprovalState.REQUIRED, action.getApprovalState());
    }

    @Test
    void completedApprovalStoreSurvivesRuntimeObjectReplacement() {
        InMemoryApprovalStore store = new InMemoryApprovalStore();
        ApprovalManager first = new ApprovalManager(store);
        ProposedAction action = ProposedAction.builder().id("restart-action").approvalState(ApprovalState.REQUIRED).build();
        AgentRun run = AgentRun.builder().runId("restart-run").status(RunStatus.WAITING_FOR_APPROVAL).actions(new ArrayList<>(List.of(action))).build();
        first.approve(run, "restart-action");
        assertTrue(first.claimExecution(run.getRunId(), action.getId()));
        first.completeExecution(run.getRunId(), action.getId(), "task-restart");

        ApprovalManager restarted = new ApprovalManager(store);
        ApprovalManager.Decision retry = restarted.approveDecision(run, action.getId());
        assertFalse(retry.shouldAttemptExecution());
        assertEquals("task-restart", restarted.find(run.getRunId(), action.getId()).getTaskId());
    }

    private Harness harness() {
        TaskTool taskTool = new TaskTool();
        AgentPlanner planner = new AgentPlanner(mock(ModelGateway.class), false, 2, 2, 120000) {
            @Override public AgentPlan plan(AgentRun run) {
                return AgentPlan.builder().steps(List.of(AgentPlanStep.builder().id("task").toolName("task").readOnly(false).build()))
                        .maxSteps(2).maxToolCalls(2).timeoutMs(120000).plannerModel("test").schemaVersion("test").build();
            }
        };
        InMemoryAgentRunRepository repository = new InMemoryAgentRunRepository();
        return new Harness(new AgentRuntime(planner, new ToolExecutor(new ToolRegistry(List.of(taskTool))), repository, new TraceRecorder(), new ApprovalManager()), taskTool);
    }

    private AgentRun pending(AgentRuntime runtime) {
        AgentRun run = runtime.createRun("生成现场复核任务", AgentContext.builder().fieldId("A-12").build(), "real");
        // The write-safety fixture represents a supported action and therefore
        // supplies one current server-owned evidence item for the Phase 1.1 gate.
        run.setEvidence(new ArrayList<>(List.of(DiagnosisEvidenceVO.builder().id("safety-evidence").modality("fixture").title("fixture").summary("fixture").build())));
        AgentRun waiting = runtime.execute(run.getRunId());
        assertEquals(RunStatus.WAITING_FOR_APPROVAL, waiting.getStatus());
        return waiting;
    }

    private String taskId(AgentRun run) {
        Object value = run.getOutput().get("task");
        return value instanceof Map<?, ?> map && map.get("taskId") != null ? String.valueOf(map.get("taskId")) : null;
    }

    private String taskId(ToolResult result) {
        return result.getOutput() == null || result.getOutput().get("taskId") == null ? null : String.valueOf(result.getOutput().get("taskId"));
    }

    private record Harness(AgentRuntime runtime, TaskTool taskTool) { }
}
