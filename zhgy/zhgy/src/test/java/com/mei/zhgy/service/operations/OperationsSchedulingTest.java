package com.mei.zhgy.service.operations;

import com.mei.zhgy.service.agent.AgentContext;
import com.mei.zhgy.service.agent.AgentToolRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationsSchedulingTest {
    @Test
    void schedulesWithOptimisticVersionAndMakesSameScheduleRetryIdempotent() {
        OperationsTaskService service = new OperationsTaskService();
        String taskId = task(service, "schedule-idempotent", 7L);

        Map<String, Object> first = service.scheduleTask(taskId, "2026-12-01", 0L, 7L);
        assertEquals("2026-12-01", first.get("scheduledFor"));
        assertEquals(1L, ((Number) first.get("version")).longValue());

        Map<String, Object> retry = service.scheduleTask(taskId, "2026-12-01", 0L, 7L);
        assertEquals(1L, ((Number) retry.get("version")).longValue());
        List<Map<String, Object>> events = service.listEvents(taskId, 7L);
        assertEquals(1, events.stream().filter(event -> "TASK_SCHEDULED".equals(event.get("eventType"))).count());
        String metadata = String.valueOf(events.stream()
                .filter(event -> "TASK_SCHEDULED".equals(event.get("eventType"))).findFirst().orElseThrow().get("metadata"));
        assertTrue(metadata.contains("\"scheduledFor\":\"2026-12-01\""));
    }

    @Test
    void rejectsInvalidDatesStaleVersionsOtherOwnersAndNonPendingTasks() {
        OperationsTaskService service = new OperationsTaskService();
        String taskId = task(service, "schedule-guards", 7L);
        service.scheduleTask(taskId, "2026-12-01", 0L, 7L);

        OperationsWriteException stale = assertThrows(OperationsWriteException.class,
                () -> service.scheduleTask(taskId, "2026-12-02", 0L, 7L));
        assertEquals("TASK_CONFLICT", stale.getCode());
        assertThrows(OperationsWriteException.class, () -> service.scheduleTask(taskId, "2026-13-01", 1L, 7L));
        OperationsWriteException owner = assertThrows(OperationsWriteException.class,
                () -> service.scheduleTask(taskId, "2026-12-02", 1L, 8L));
        assertEquals("FORBIDDEN", owner.getCode());

        service.startTask(taskId, 7L);
        OperationsWriteException status = assertThrows(OperationsWriteException.class,
                () -> service.scheduleTask(taskId, "2026-12-02", 2L, 7L));
        assertEquals("INVALID_TASK_TRANSITION", status.getCode());
    }

    @Test
    void batchScheduleIsAllOrNothingWhenAnyItemFails() {
        OperationsTaskService service = new OperationsTaskService();
        String firstId = task(service, "schedule-batch-first", 7L);
        String secondId = task(service, "schedule-batch-second", 7L);
        service.startTask(secondId, 7L);

        assertThrows(OperationsWriteException.class, () -> service.scheduleBatch(1,
                List.of(new OperationsTaskService.ScheduleItem(firstId, "2026-12-03", 0L),
                        new OperationsTaskService.ScheduleItem(secondId, "2026-12-04", 1L)), 7L));

        assertNull(service.getTask(firstId, 7L).get("scheduledFor"));
        assertEquals(0, service.listEvents(firstId, 7L).stream()
                .filter(event -> "TASK_SCHEDULED".equals(event.get("eventType"))).count());
    }

    @Test
    void batchScheduleCommitsAllValidChangesAndRejectsDuplicateTaskIds() {
        OperationsTaskService service = new OperationsTaskService();
        String firstId = task(service, "schedule-batch-commit-a", 7L);
        String secondId = task(service, "schedule-batch-commit-b", 7L);

        Map<String, Object> body = service.scheduleBatch(1,
                List.of(new OperationsTaskService.ScheduleItem(firstId, "2026-12-05", 0L),
                        new OperationsTaskService.ScheduleItem(secondId, "2026-12-06", 0L)), 7L);
        assertEquals(2, body.get("updated"));
        assertEquals("2026-12-05", service.getTask(firstId, 7L).get("scheduledFor"));
        assertEquals("2026-12-06", service.getTask(secondId, 7L).get("scheduledFor"));

        assertThrows(OperationsWriteException.class, () -> service.scheduleBatch(1,
                List.of(new OperationsTaskService.ScheduleItem(firstId, "2026-12-07", 1L),
                        new OperationsTaskService.ScheduleItem(firstId, "2026-12-08", 1L)), 7L));
    }

    private String task(OperationsTaskService service, String actionId, Long ownerUserId) {
        return String.valueOf(service.createTask(AgentToolRequest.builder().runId("run-" + actionId).actionId(actionId)
                .ownerUserId(ownerUserId).actorId(String.valueOf(ownerUserId)).approvalGranted(true)
                .context(AgentContext.builder().fieldId("field-1").build()).build()).get("taskId"));
    }
}
