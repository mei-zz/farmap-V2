package com.mei.zhgy.service.agent;

import com.mei.zhgy.service.agriculture.CropResolver;
import com.mei.zhgy.service.agriculture.FieldFactsService;
import com.mei.zhgy.service.agriculture.PhenologyService;
import com.mei.zhgy.service.field.FarmFieldService;
import com.mei.zhgy.service.field.FieldAccessException;
import com.mei.zhgy.service.field.FieldContext;
import com.mei.zhgy.service.operations.OperationsTaskService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PiTaskDraftApprovalTest {
    private final FarmFieldService fields = mock(FarmFieldService.class);
    private final AgentPlanner planner = mock(AgentPlanner.class);
    private final OperationsTaskService operations = new OperationsTaskService();
    private final AgentRunRepository runs = mock(AgentRunRepository.class);

    private AgentRuntime approvalEngine() {
        when(fields.context("field-1", 7L)).thenReturn(FieldContext.builder()
                .farm(Map.of("id", 42, "name", "示例农场"))
                .field(Map.of("fieldId", "field-1", "name", "东侧区"))
                .crop(Map.of("name", "plum")).sourceMode("MYSQL").build());
        var crops = new CropResolver();
        var facts = new FieldFactsService(fields, crops, new PhenologyService(crops));
        AtomicReference<AgentRun> saved = new AtomicReference<>();
        when(runs.save(any(AgentRun.class))).thenAnswer(invocation -> {
            AgentRun run = invocation.getArgument(0);
            saved.set(run);
            return run;
        });
        when(runs.find(anyString())).thenAnswer(invocation -> Optional.ofNullable(saved.get())
                .filter(run -> run.getRunId().equals(invocation.getArgument(0))));
        return new AgentRuntime(planner, new ToolExecutor(new ToolRegistry(List.of(new TaskTool(operations)))),
                runs, new TraceRecorder(), new ApprovalManager(),
                operations, null, new EvidenceSufficiencyEvaluator(), new AgentRecoveryPolicy(), new AgentReplanningPolicy(), facts);
    }

    @Test
    void persistedDraftDoesNotRunThePlannerAndOnlyApprovalCreatesOneTask() {
        AgentRuntime runtime = approvalEngine();
        String date = LocalDate.now(FieldFactsService.ZONE).plusDays(1).toString();
        var draft = new AgentRuntime.TaskDraft("检查土壤后滴灌", "测量土壤湿度后安排滴灌。", date);
        try {
            AgentRun pending = runtime.prepareTaskDraft("安排明天的浇水任务",
                    AgentContext.builder().fieldId("field-1").farmId("42").build(), 7L, draft);
            assertEquals(RunStatus.WAITING_FOR_APPROVAL, pending.getStatus());
            assertEquals(0, operations.count());
            assertTrue(pending.getEvidenceSufficiency().isAllowsWrite());
            assertEquals(List.of("task"), pending.getSteps().stream().map(AgentPlanStep::getToolName).toList());
            ProposedAction action = pending.getActions().get(0);
            assertEquals(date, action.getScheduledFor());
            assertEquals(action.getId(), runtime.execute(pending.getRunId(), 7L).getActions().get(0).getId());
            assertThrows(AgentAccessException.class, () -> runtime.approve(pending.getRunId(), action.getId(), 8L));
            assertEquals(0, operations.count());

            AgentRun approved = runtime.approve(pending.getRunId(), action.getId(), 7L);
            assertEquals(RunStatus.COMPLETED, approved.getStatus(), approved.getFailureMessage());
            Map<?, ?> task = (Map<?, ?>) approved.getOutput().get("task");
            assertEquals(draft.title(), task.get("title"));
            assertEquals(draft.detail(), task.get("goal"));
            assertEquals(date, task.get("scheduledFor"));
            assertEquals("agent", task.get("source"));
            assertEquals(1, operations.count());
            runtime.approve(pending.getRunId(), action.getId(), 7L);
            assertEquals(1, operations.count());
            verifyNoInteractions(planner);
        } finally { runtime.shutdownExecutor(); }
    }

    @Test
    void mismatchedFarmAndMalformedDatesNeverProduceAPendingRun() {
        AgentRuntime runtime = approvalEngine();
        try {
            assertThrows(IllegalArgumentException.class, () -> new AgentRuntime.TaskDraft("检查", "巡查", "2026-02-30"));
            var draft = new AgentRuntime.TaskDraft("检查", "巡查", null);
            assertThrows(FieldAccessException.class, () -> runtime.prepareTaskDraft("安排巡查任务",
                    AgentContext.builder().fieldId("field-1").farmId("99").build(), 7L, draft));
            verify(runs, never()).save(any());
            assertEquals(0, operations.count());
        } finally { runtime.shutdownExecutor(); }
    }
}
