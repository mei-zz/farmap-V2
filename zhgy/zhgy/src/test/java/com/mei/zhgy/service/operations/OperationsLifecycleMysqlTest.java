package com.mei.zhgy.service.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.service.agent.AgentContext;
import com.mei.zhgy.service.agent.AgentToolRequest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OperationsLifecycleMysqlTest {
    @Test
    void persistsLifecycleOutcomeUniqueBoundaryAndAuditInMysql() throws Exception {
        Assumptions.assumeTrue("mysql".equalsIgnoreCase(System.getenv().getOrDefault("FARMAP_AGENT_PERSISTENCE", "file")), "run with FARMAP_AGENT_PERSISTENCE=mysql");
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(System.getenv().getOrDefault("SPRING_DATASOURCE_URL", "jdbc:mysql://127.0.0.1:3306/zhgy?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"));
        dataSource.setUsername(System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "root"));
        dataSource.setPassword(System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", ""));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        OperationsTaskService service = new OperationsTaskService(dataSource, new ObjectMapper());
        String actionId = "mysql-lifecycle-" + UUID.randomUUID();
        String concurrentActionId = "mysql-concurrent-" + UUID.randomUUID();
        String legacyActionId = "mysql-legacy-" + UUID.randomUUID();
        List<String> taskIds = new ArrayList<>();
        try {
            Map<String, Object> task = service.createTask(AgentToolRequest.builder().runId("run-mysql").actionId(actionId).approvalGranted(true).ownerUserId(42L).actorId("42")
                    .diagnosisId("diagnosis-mysql").recommendationId("recommendation-mysql")
                    .context(AgentContext.builder().diagnosisId("diagnosis-mysql").sourceRecommendationId("recommendation-mysql").fieldId("field-mysql").build()).build());
            String taskId = String.valueOf(task.get("taskId")); taskIds.add(taskId);
            final String persistedTaskId = taskId;
            service.assignTask(persistedTaskId, "worker-1", 42L);
            service.startTask(persistedTaskId, 42L);
            Map<String, Object> completion = service.completeTask(persistedTaskId, "MySQL execution complete", "evidence-mysql", null, 42L);
            Map<String, Object> replay = service.completeTask(persistedTaskId, "retry must not replace outcome", null, null, 42L);
            assertEquals("COMPLETED", service.getTask(persistedTaskId, 42L).get("status"));
            assertEquals(persistedTaskId, service.getOutcome(persistedTaskId, 42L).get("taskId"));
            assertEquals(((Map<?, ?>) completion.get("outcome")).get("outcomeId"), ((Map<?, ?>) replay.get("outcome")).get("outcomeId"));
            assertEquals(1, service.listEvents(persistedTaskId, 42L).stream().filter(e -> "OUTCOME_CREATED".equals(e.get("eventType"))).count());
            assertEquals(List.of("TASK_CREATED", "TASK_ASSIGNED", "TASK_STARTED", "TASK_COMPLETED", "OUTCOME_CREATED"),
                    service.listEvents(persistedTaskId, 42L).stream().map(event -> event.get("eventType")).toList());
            assertEquals("recommendation-mysql", service.getTask(persistedTaskId, 42L).get("recommendationId"));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_operation_outcomes WHERE task_id=?", Integer.class, persistedTaskId));

            Map<String, Object> concurrent = service.createTask(AgentToolRequest.builder().runId("run-concurrent").actionId(concurrentActionId).approvalGranted(true).ownerUserId(42L).actorId("42")
                    .context(AgentContext.builder().diagnosisId("diagnosis-mysql").fieldId("field-mysql").build()).build());
            String concurrentTaskId = String.valueOf(concurrent.get("taskId")); taskIds.add(concurrentTaskId);
            service.startTask(concurrentTaskId, 42L);
            var pool = Executors.newFixedThreadPool(2);
            try {
                var futures = pool.invokeAll(List.of(
                        (Callable<Map<String, Object>>) () -> service.completeTask(concurrentTaskId, "complete A", null, null, 42L),
                        (Callable<Map<String, Object>>) () -> service.completeTask(concurrentTaskId, "complete B", null, null, 42L)));
                assertEquals(2, futures.stream().filter(f -> { try { f.get(); return true; } catch (Exception e) { return false; } }).count());
            } finally { pool.shutdownNow(); }
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_operation_outcomes WHERE task_id=?", Integer.class, concurrentTaskId));

            Map<String, Object> legacy = service.createTask(AgentToolRequest.builder().runId("run-legacy").actionId(legacyActionId).approvalGranted(true).ownerUserId(42L).actorId("42")
                    .context(AgentContext.builder().diagnosisId("diagnosis-mysql").fieldId("legacy-field").build()).build());
            String legacyTaskId = String.valueOf(legacy.get("taskId")); taskIds.add(legacyTaskId);
            assertEquals("OPEN", service.getTask(legacyTaskId, 42L).get("status"));
            assertEquals(null, service.getTask(legacyTaskId, 42L).get("recommendationId"));
            assertEquals(1, service.listTasks().stream().filter(t -> persistedTaskId.equals(t.get("taskId"))).count());
        } finally {
            for (String taskId : taskIds) {
                try (var c = dataSource.getConnection()) {
                    c.createStatement().executeUpdate("DELETE FROM operations_task_events WHERE task_id='" + taskId.replace("'", "''") + "'");
                    c.createStatement().executeUpdate("DELETE FROM agent_operation_outcomes WHERE task_id='" + taskId.replace("'", "''") + "'");
                    c.createStatement().executeUpdate("DELETE FROM agent_operations_tasks WHERE task_id='" + taskId.replace("'", "''") + "'");
                } catch (Exception ignored) { }
            }
        }
    }
}
