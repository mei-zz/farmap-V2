package com.mei.zhgy.service.operations;

import com.mei.zhgy.service.agent.AgentContext;
import com.mei.zhgy.service.agent.AgentToolRequest;
import com.mei.zhgy.service.field.FarmField;
import com.mei.zhgy.service.field.FarmFieldService;
import com.mei.zhgy.service.field.FieldAccessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OperationsTaskViewServiceTest {
    private final ZoneId zone = ZoneId.of("Asia/Shanghai");
    private OperationsTaskService tasks;
    private FarmFieldService fields;
    private OperationsTaskViewService views;
    private FarmField firstField;
    private FarmField secondField;

    @BeforeEach
    void setUp() {
        tasks = new OperationsTaskService();
        fields = mock(FarmFieldService.class);
        views = new OperationsTaskViewService(tasks, fields);
        firstField = FarmField.builder().fieldId("field-1").farmId(1).name("一号地块").build();
        secondField = FarmField.builder().fieldId("field-2").farmId(1).name("二号地块").build();
        when(fields.list(1, 7L)).thenReturn(List.of(firstField, secondField));
        when(fields.get("field-1", 7L)).thenReturn(firstField);
        when(fields.get("field-2", 7L)).thenReturn(secondField);
    }

    @Test
    void returnsFarmScopedTaskViewsWithCountsAndFieldNames() {
        String scheduled = task("view-scheduled", 7L, "field-1");
        tasks.scheduleTask(scheduled, "2026-12-01", 0L, 7L);
        String assigned = task("view-assigned", 7L, "field-2");
        tasks.assignTask(assigned, "worker", 7L);
        tasks.scheduleTask(assigned, "2026-11-20", 1L, 7L);
        String completed = task("view-completed", 7L, "field-1");
        tasks.startTask(completed, 7L);
        tasks.completeTask(completed, "现场完成", "photo", null, 7L);
        String undated = task("view-undated", 7L, "field-1");
        String otherOwner = task("view-other-owner", 8L, "field-1");
        tasks.scheduleTask(otherOwner, "2026-12-02", 0L, 8L);

        Map<String, Object> all = views.view(1, null, null, null, "tasks", 7L);
        Map<?, ?> counts = (Map<?, ?>) all.get("counts");
        assertEquals(4, counts.get("total"));
        assertEquals(3, counts.get("pending"));
        assertEquals(0, counts.get("inProgress"));
        assertEquals(1, counts.get("completed"));
        assertEquals(false, all.get("truncated"));
        List<?> items = (List<?>) all.get("items");
        assertTrue(items.stream().noneMatch(item -> otherOwner.equals(((Map<?, ?>) item).get("taskId"))));
        assertTrue(items.stream().anyMatch(item -> undated.equals(((Map<?, ?>) item).get("taskId"))));
        Map<?, ?> first = (Map<?, ?>) items.get(0);
        assertEquals("一号地块", first.get("fieldName"));
        assertEquals(scheduled, first.get("taskId"));

        Map<String, Object> ranged = views.view(1, null, "2026-12-01", "2026-12-01", "tasks", 7L);
        assertEquals(1, ((Map<?, ?>) ranged.get("counts")).get("total"));
        assertEquals(scheduled, ((Map<?, ?>) ((List<?>) ranged.get("items")).get(0)).get("taskId"));

        Map<String, Object> fieldOnly = views.view(1, "field-2", null, null, "tasks", 7L);
        assertEquals(assigned, ((Map<?, ?>) ((List<?>) fieldOnly.get("items")).get(0)).get("taskId"));
    }

    @Test
    void recordsIncludeCompletedOutcomeAndCalendarUsesAllLifecycleDates() {
        String completed = task("view-record", 7L, "field-1");
        tasks.startTask(completed, 7L);
        tasks.completeTask(completed, "完成并取证", "photo-1", "notes", 7L);
        String scheduled = task("view-calendar", 7L, "field-2");
        tasks.scheduleTask(scheduled, "2026-12-10", 0L, 7L);

        Map<String, Object> completedTask = tasks.getTask(completed, 7L);
        String completedDate = Instant.parse(String.valueOf(completedTask.get("completedAt")))
                .atZone(zone).toLocalDate().toString();
        String startedDate = Instant.parse(String.valueOf(completedTask.get("startedAt")))
                .atZone(zone).toLocalDate().toString();
        Map<String, Object> records = views.view(1, null, completedDate, completedDate, "records", 7L);
        List<?> recordItems = (List<?>) records.get("items");
        assertEquals(1, recordItems.size());
        Map<?, ?> record = (Map<?, ?>) recordItems.get(0);
        assertEquals(completed, record.get("taskId"));
        assertEquals("完成并取证", ((Map<?, ?>) record.get("outcome")).get("summary"));
        assertEquals(1, ((Map<?, ?>) records.get("counts")).get("completed"));

        Map<String, Object> calendar = views.view(1, null, "2026-12-10", "2026-12-10", "calendar", 7L);
        assertEquals(1, ((Map<?, ?>) calendar.get("counts")).get("total"));
        assertEquals(scheduled, ((Map<?, ?>) ((List<?>) calendar.get("items")).get(0)).get("taskId"));
        assertNotNull(((Map<?, ?>) ((List<?>) calendar.get("items")).get(0)).get("scheduledFor"));

        Map<String, Object> startedCalendar = views.view(1, null, startedDate, startedDate, "calendar", 7L);
        assertTrue(((List<?>) startedCalendar.get("items")).stream()
                .anyMatch(item -> completed.equals(((Map<?, ?>) item).get("taskId"))));
        Map<String, Object> completedCalendar = views.view(1, null, completedDate, completedDate, "calendar", 7L);
        assertTrue(((List<?>) completedCalendar.get("items")).stream()
                .anyMatch(item -> completed.equals(((Map<?, ?>) item).get("taskId"))));
    }

    @Test
    void usesShanghaiDateForLegacyScheduledTimestamps() {
        OperationsTaskService legacyTasks = mock(OperationsTaskService.class);
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("taskId", "legacy-scheduled");
        task.put("fieldId", "field-1");
        task.put("status", "OPEN");
        task.put("scheduledFor", "2026-12-01T23:30:00-05:00");
        task.put("createdAt", "2026-11-20T00:00:00Z");
        task.put("version", 0L);
        when(legacyTasks.listTasks(7L)).thenReturn(List.of(task));

        OperationsTaskViewService legacyViews = new OperationsTaskViewService(legacyTasks, fields);
        Map<String, Object> shanghaiDate = legacyViews.view(1, null, "2026-12-02", "2026-12-02", "tasks", 7L);
        assertEquals(1, ((Map<?, ?>) shanghaiDate.get("counts")).get("total"));
        assertEquals("legacy-scheduled",
                ((Map<?, ?>) ((List<?>) shanghaiDate.get("items")).get(0)).get("taskId"));
        Map<String, Object> utcDate = legacyViews.view(1, null, "2026-12-01", "2026-12-01", "tasks", 7L);
        assertEquals(0, ((Map<?, ?>) utcDate.get("counts")).get("total"));
    }

    @Test
    void validatesDateOrderAndFieldFarmBinding() {
        FieldAccessException order = assertThrows(FieldAccessException.class,
                () -> views.view(1, null, "2026-12-02", "2026-12-01", "tasks", 7L));
        assertEquals("DATE_RANGE_INVALID", order.getCode());
        FarmField foreign = FarmField.builder().fieldId("foreign").farmId(2).name("外部地块").build();
        when(fields.get("foreign", 7L)).thenReturn(foreign);
        FieldAccessException access = assertThrows(FieldAccessException.class,
                () -> views.view(1, "foreign", null, null, "tasks", 7L));
        assertEquals("FARM_ACCESS_DENIED", access.getCode());
        FieldAccessException invalidView = assertThrows(FieldAccessException.class,
                () -> views.view(1, null, null, null, "unknown", 7L));
        assertEquals("VIEW_INVALID", invalidView.getCode());
    }

    @Test
    void truncatesItemsButCountsEveryMatchedTask() {
        for (int index = 0; index < OperationsTaskViewService.RESPONSE_LIMIT + 1; index++) {
            task("view-limit-" + index, 7L, "field-1");
        }
        Map<String, Object> body = views.view(1, null, null, null, "tasks", 7L);
        assertEquals(OperationsTaskViewService.RESPONSE_LIMIT, ((List<?>) body.get("items")).size());
        assertEquals(OperationsTaskViewService.RESPONSE_LIMIT + 1, ((Map<?, ?>) body.get("counts")).get("total"));
        assertEquals(true, body.get("truncated"));
    }

    private String task(String actionId, Long ownerUserId, String fieldId) {
        return String.valueOf(tasks.createTask(AgentToolRequest.builder().runId("run-" + actionId).actionId(actionId)
                .ownerUserId(ownerUserId).actorId(String.valueOf(ownerUserId)).approvalGranted(true)
                .context(AgentContext.builder().fieldId(fieldId).build()).build()).get("taskId"));
    }
}
