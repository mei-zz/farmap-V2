package com.mei.zhgy.service.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.service.agent.AgentContext;
import com.mei.zhgy.service.agent.AgentToolRequest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OperationsSchedulingMysqlTest {
    @Test
    void batchScheduleRollsBackOnStaleVersionThenCommitsAndRetriesIdempotently() {
        Assumptions.assumeTrue("mysql".equalsIgnoreCase(System.getenv().getOrDefault("FARMAP_AGENT_PERSISTENCE", "file")),
                "run with FARMAP_AGENT_PERSISTENCE=mysql");
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(System.getenv().getOrDefault("SPRING_DATASOURCE_URL",
                "jdbc:mysql://127.0.0.1:3306/zhgy?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"));
        dataSource.setUsername(System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "root"));
        dataSource.setPassword(System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", ""));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<Map<String, Object>> farms = jdbc.queryForList("SELECT id,user_id FROM farm WHERE id=1");
        Assumptions.assumeFalse(farms.isEmpty(), "farm id 1 is required for MySQL integration coverage");
        Long farmOwner = farms.get(0).get("user_id") == null ? null : ((Number) farms.get(0).get("user_id")).longValue();
        String fieldId = "schedule-field-" + UUID.randomUUID();
        String firstAction = "schedule-mysql-a-" + UUID.randomUUID();
        String secondAction = "schedule-mysql-b-" + UUID.randomUUID();
        OperationsTaskService service = new OperationsTaskService(dataSource, new ObjectMapper());
        String firstId = null;
        String secondId = null;
        try {
            jdbc.update("INSERT INTO farm_fields(field_id,farm_id,name,status,source_mode,owner_user_id,created_at,updated_at) "
                            + "VALUES (?,?,?,'ACTIVE','MYSQL',?,NOW(3),NOW(3))",
                    fieldId, 1, "schedule-" + fieldId, farmOwner);
            firstId = create(service, firstAction, fieldId, farmOwner);
            secondId = create(service, secondAction, fieldId, farmOwner);

            String firstTaskId = firstId;
            String secondTaskId = secondId;
            assertThrows(OperationsWriteException.class, () -> service.scheduleBatch(1, List.of(
                    new OperationsTaskService.ScheduleItem(firstTaskId, "2026-12-11", 0L),
                    new OperationsTaskService.ScheduleItem(secondTaskId, "2026-12-12", 99L)), farmOwner));
            assertNull(service.getTask(firstTaskId, farmOwner).get("scheduledFor"));

            Map<String, Object> committed = service.scheduleBatch(1, List.of(
                    new OperationsTaskService.ScheduleItem(firstTaskId, "2026-12-11", 0L),
                    new OperationsTaskService.ScheduleItem(secondTaskId, "2026-12-12", 0L)), farmOwner);
            assertEquals(2, committed.get("updated"));
            assertEquals("2026-12-11", service.getTask(firstTaskId, farmOwner).get("scheduledFor"));
            assertEquals("2026-12-12", service.getTask(secondTaskId, farmOwner).get("scheduledFor"));

            Map<String, Object> retry = service.scheduleBatch(1, List.of(
                    new OperationsTaskService.ScheduleItem(firstTaskId, "2026-12-11", 0L),
                    new OperationsTaskService.ScheduleItem(secondTaskId, "2026-12-12", 0L)), farmOwner);
            assertEquals(0, retry.get("updated"));
            assertEquals(2, retry.get("unchanged"));
        } finally {
            for (String taskId : new String[]{firstId, secondId}) {
                if (taskId == null) continue;
                jdbc.update("DELETE FROM operations_task_events WHERE task_id=?", taskId);
                jdbc.update("DELETE FROM agent_operation_outcomes WHERE task_id=?", taskId);
                jdbc.update("DELETE FROM agent_operations_tasks WHERE task_id=?", taskId);
            }
            jdbc.update("DELETE FROM farm_fields WHERE field_id=?", fieldId);
        }
    }

    private String create(OperationsTaskService service, String actionId, String fieldId, Long ownerUserId) {
        AgentToolRequest request = AgentToolRequest.builder().runId("run-" + actionId).actionId(actionId)
                .ownerUserId(ownerUserId).actorId(ownerUserId == null ? null : String.valueOf(ownerUserId))
                .approvalGranted(true).context(AgentContext.builder().fieldId(fieldId).build()).build();
        return String.valueOf(service.createTask(request).get("taskId"));
    }
}
