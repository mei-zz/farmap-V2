package com.mei.zhgy.service.operations;

import com.mei.zhgy.service.agent.AgentContext;
import com.mei.zhgy.service.agent.AgentToolRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationsLifecycleTest {
    @Test
    void createsIdempotentlyAndRecordsOneCreationEvent() {
        OperationsTaskService service = new OperationsTaskService();
        AgentToolRequest request = request("action-1");

        Map<String, Object> first = service.createTask(request);
        Map<String, Object> second = service.createTask(request);

        assertEquals(first.get("taskId"), second.get("taskId"));
        assertEquals(1, service.listTasks().size());
        assertEquals(1, service.listEvents((String) first.get("taskId"), null).size());
        assertEquals("TASK_CREATED", service.listEvents((String) first.get("taskId"), null).get(0).get("eventType"));
    }

    @Test
    void completesThroughValidatedLifecycleAndCreatesCanonicalOutcome() {
        OperationsTaskService service = new OperationsTaskService();
        String taskId = (String) service.createTask(request("action-basic")).get("taskId");

        service.assignTask(taskId, "worker-1", null);
        service.startTask(taskId, null);
        Map<String, Object> completion = service.completeTask(taskId, "现场复核完成", "photo-1", "根区湿度正常", null);

        Map<String, Object> task = service.getTask(taskId);
        Map<String, Object> outcome = (Map<String, Object>) completion.get("outcome");
        assertEquals("COMPLETED", task.get("status"));
        assertTrue(task.get("startedAt") != null);
        assertTrue(task.get("completedAt") != null);
        assertEquals(taskId, outcome.get("taskId"));
        assertEquals(List.of("TASK_CREATED", "TASK_ASSIGNED", "TASK_STARTED", "TASK_COMPLETED", "OUTCOME_CREATED"),
                service.listEvents(taskId, null).stream().map(event -> event.get("eventType")).toList());
    }

    @Test
    void rejectsTerminalTransitionsAndMakesDuplicateCompletionSideEffectFree() {
        OperationsTaskService service = new OperationsTaskService();
        String taskId = (String) service.createTask(request("action-terminal")).get("taskId");
        service.startTask(taskId, null);
        Map<String, Object> first = service.completeTask(taskId, "完成", null, null, null);
        Map<String, Object> replay = service.completeTask(taskId, "不同重试结果", null, null, null);

        assertEquals(((Map<String, Object>) first.get("outcome")).get("outcomeId"), ((Map<String, Object>) replay.get("outcome")).get("outcomeId"));
        assertThrows(OperationsWriteException.class, () -> service.startTask(taskId, null));
        OperationsWriteException error = assertThrows(OperationsWriteException.class, () -> service.cancelTask(taskId, "late", null));
        assertEquals("INVALID_TASK_TRANSITION", error.getCode());
        assertEquals(1, service.listEvents(taskId, null).stream().filter(e -> "OUTCOME_CREATED".equals(e.get("eventType"))).count());
    }

    @Test
    void requiresExecutionStateBeforeCompletionAndRejectsInvalidTerminalCommands() {
        OperationsTaskService service = new OperationsTaskService();
        String taskId = (String) service.createTask(request("action-invalid")).get("taskId");
        OperationsWriteException openCompletion = assertThrows(OperationsWriteException.class,
                () -> service.completeTask(taskId, "不能跳过开始", null, null, null));
        assertEquals("INVALID_TASK_TRANSITION", openCompletion.getCode());
        service.cancelTask(taskId, "现场条件变化", null);
        OperationsWriteException cancelledCompletion = assertThrows(OperationsWriteException.class,
                () -> service.completeTask(taskId, "取消后不能完成", null, null, null));
        assertEquals("INVALID_TASK_TRANSITION", cancelledCompletion.getCode());
    }

    @Test
    void rejectsMutationByAnotherAuthenticatedOwner() {
        OperationsTaskService service = new OperationsTaskService();
        String taskId = (String) service.createTask(AgentToolRequest.builder().runId("run-owner").actionId("action-owner")
                .ownerUserId(7L).actorId("7").approvalGranted(true).context(AgentContext.builder().fieldId("field-1").build()).build()).get("taskId");

        OperationsWriteException error = assertThrows(OperationsWriteException.class, () -> service.startTask(taskId, 8L));
        assertEquals("FORBIDDEN", error.getCode());
    }

    @Test
    void concurrentCompleteAndCancelHaveOneTerminalWinner() throws Exception {
        OperationsTaskService service = new OperationsTaskService();
        String taskId = (String) service.createTask(request("action-race")).get("taskId");
        service.startTask(taskId, null);
        var pool = Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<Object>> results = pool.invokeAll(List.of(
                    (Callable<Object>) () -> command(() -> service.completeTask(taskId, "完成", null, null, null)),
                    (Callable<Object>) () -> command(() -> service.cancelTask(taskId, "不再需要", null))));
            long failures = results.stream().map(this::get).filter(value -> value instanceof OperationsWriteException).count();
            assertEquals(1, failures);
            String status = (String) service.getTask(taskId).get("status");
            assertTrue("COMPLETED".equals(status) || "CANCELLED".equals(status));
            assertEquals("COMPLETED".equals(status) ? 1 : 0, service.listEvents(taskId, null).stream().filter(e -> "OUTCOME_CREATED".equals(e.get("eventType"))).count());
        } finally { pool.shutdownNow(); }
    }

    @Test
    void concurrentCompleteCreatesOneCanonicalOutcome() throws Exception {
        OperationsTaskService service = new OperationsTaskService();
        String taskId = (String) service.createTask(request("action-double-complete")).get("taskId");
        service.startTask(taskId, null);
        var pool = Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<Object>> results = pool.invokeAll(List.of(
                    (Callable<Object>) () -> command(() -> service.completeTask(taskId, "完成 A", null, null, null)),
                    (Callable<Object>) () -> command(() -> service.completeTask(taskId, "完成 B", null, null, null))));
            long successes = results.stream().map(this::get).filter(value -> value instanceof Map<?, ?>).count();
            assertEquals(2, successes, "并发完成允许幂等重放，但只能创建一个 Outcome");
            assertEquals(1, service.listEvents(taskId, null).stream().filter(e -> "OUTCOME_CREATED".equals(e.get("eventType"))).count());
            assertEquals("COMPLETED", service.getTask(taskId).get("status"));
            assertEquals(taskId, service.getOutcome(taskId, null).get("taskId"));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void preservesStableProductLoopProvenanceAcrossLifecycle() {
        OperationsTaskService service = new OperationsTaskService();
        AgentToolRequest request = AgentToolRequest.builder().runId("run-provenance").actionId("action-provenance")
                .diagnosisId("diagnosis-provenance").recommendationId("recommendation-provenance").ownerUserId(7L).actorId("7")
                .approvalGranted(true).context(AgentContext.builder().fieldId("field-1").diagnosisId("diagnosis-provenance")
                        .sourceRecommendationId("recommendation-provenance").build()).build();
        Map<String, Object> task = service.createTask(request);
        String taskId = (String) task.get("taskId");
        service.startTask(taskId, 7L);
        service.completeTask(taskId, "现场完成", null, null, 7L);
        Map<String, Object> reloaded = service.getTask(taskId, 7L);
        assertEquals("action-provenance", reloaded.get("actionId"));
        assertEquals("run-provenance", reloaded.get("agentRunId"));
        assertEquals("diagnosis-provenance", reloaded.get("diagnosisId"));
        assertEquals("recommendation-provenance", reloaded.get("recommendationId"));
    }

    @Test
    void legacyOwnerlessOpenTaskRemainsReadableButOwnedTasksAreFiltered() {
        OperationsTaskService service = new OperationsTaskService();
        String legacyId = (String) service.createTask(request("action-legacy")).get("taskId");
        String ownedId = (String) service.createTask(AgentToolRequest.builder().runId("run-owned").actionId("action-owned")
                .ownerUserId(7L).approvalGranted(true).context(AgentContext.builder().fieldId("field-1").build()).build()).get("taskId");
        assertEquals("OPEN", service.getTask(legacyId, 8L).get("status"));
        assertThrows(OperationsWriteException.class, () -> service.getTask(ownedId, 8L));
        assertTrue(service.listTasks(8L).stream().noneMatch(task -> ownedId.equals(task.get("taskId"))));
    }

    private AgentToolRequest request(String actionId) {
        return AgentToolRequest.builder().runId("run-" + actionId).actionId(actionId).goal("农事复核").approvalGranted(true)
                .context(AgentContext.builder().fieldId("field-1").build()).build();
    }

    private Object command(Callable<Map<String, Object>> command) {
        try { return command.call(); } catch (Exception error) { return error; }
    }

    private Object get(java.util.concurrent.Future<Object> future) {
        try { return future.get(); } catch (Exception error) { return error; }
    }
}
