package com.mei.zhgy.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.service.operations.OperationsTaskService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class ProductionWriteSafetyMysqlTest {
    @Test
    void mysqlDecisionAndTaskRemainIdempotentAcrossStoreInstances() throws Exception {
        Assumptions.assumeTrue("mysql".equalsIgnoreCase(System.getenv().getOrDefault("FARMAP_AGENT_PERSISTENCE", "file")), "run with FARMAP_AGENT_PERSISTENCE=mysql");
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(System.getenv().getOrDefault("SPRING_DATASOURCE_URL", "jdbc:mysql://127.0.0.1:3306/zhgy?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"));
        dataSource.setUsername(System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "root"));
        dataSource.setPassword(System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", ""));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String runId = "safety-test-" + UUID.randomUUID();
        String actionId = "safety-action-" + UUID.randomUUID();
        try {
            JdbcApprovalStore firstStore = new JdbcApprovalStore(dataSource);
            ApprovalStore.Decision first = firstStore.decide(runId, actionId, ApprovalState.APPROVED, "test", java.time.Instant.now());
            ApprovalStore.Decision retry = new JdbcApprovalStore(dataSource).decide(runId, actionId, ApprovalState.APPROVED, "test", java.time.Instant.now());
            assertTrue(first.created());
            assertFalse(retry.created());
            assertTrue(firstStore.claimExecution(runId, actionId));
            assertFalse(new JdbcApprovalStore(dataSource).claimExecution(runId, actionId));

            OperationsTaskService tasks = new OperationsTaskService(dataSource, new ObjectMapper());
            AgentToolRequest request = AgentToolRequest.builder().runId(runId).actionId(actionId).goal("安全测试").approvalGranted(true)
                    .context(AgentContext.builder().fieldId("safety-field").build()).attributes(Map.of("evidenceIds", List.of("e-1"))).build();
            Map<String, Object> task = tasks.createTask(request);
            Map<String, Object> duplicate = tasks.createTask(request);
            assertEquals(task.get("taskId"), duplicate.get("taskId"));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_operations_tasks WHERE action_id=?", Integer.class, actionId));
            firstStore.completeExecution(runId, actionId, String.valueOf(task.get("taskId")));
            assertEquals("COMPLETED", new JdbcApprovalStore(dataSource).find(runId, actionId).getExecutionState().name());

            String parallelRunId = "parallel-safety-test-" + UUID.randomUUID();
            String parallelActionId = "parallel-safety-action-" + UUID.randomUUID();
            OperationsTaskService parallelTasks = new OperationsTaskService(dataSource, new ObjectMapper());
            AgentToolRequest parallelRequest = AgentToolRequest.builder().runId(parallelRunId).actionId(parallelActionId)
                    .goal("并发批准安全测试").approvalGranted(true).ownerUserId(42L).actorId("42")
                    .context(AgentContext.builder().fieldId("parallel-safety-field").build()).build();
            var pool = Executors.newFixedThreadPool(2);
            try {
                List<Boolean> claims = pool.invokeAll(List.of(
                        (Callable<Boolean>) () -> {
                            JdbcApprovalStore store = new JdbcApprovalStore(dataSource);
                            ApprovalStore.Decision decision = store.decide(parallelRunId, parallelActionId, ApprovalState.APPROVED, "parallel", java.time.Instant.now());
                            if (!store.claimExecution(parallelRunId, parallelActionId)) return false;
                            parallelTasks.createTask(parallelRequest);
                            return decision.created();
                        },
                        (Callable<Boolean>) () -> {
                            JdbcApprovalStore store = new JdbcApprovalStore(dataSource);
                            store.decide(parallelRunId, parallelActionId, ApprovalState.APPROVED, "parallel", java.time.Instant.now());
                            if (!store.claimExecution(parallelRunId, parallelActionId)) return false;
                            parallelTasks.createTask(parallelRequest);
                            return true;
                        })).stream().map(future -> {
                    try { return future.get(); } catch (Exception error) { throw new AssertionError(error); }
                }).toList();
                assertEquals(1, claims.stream().filter(Boolean::booleanValue).count(), "exactly one approval execution winner");
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_approvals WHERE run_id=? AND action_id=?", Integer.class, parallelRunId, parallelActionId));
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_operations_tasks WHERE action_id=?", Integer.class, parallelActionId));
            } finally {
                pool.shutdownNow();
                jdbc.update("DELETE FROM agent_approvals WHERE run_id=? AND action_id=?", parallelRunId, parallelActionId);
                jdbc.update("DELETE FROM agent_operations_tasks WHERE action_id=?", parallelActionId);
            }
        } finally {
            jdbc.update("DELETE FROM agent_approvals WHERE run_id=? AND action_id=?", runId, actionId);
            jdbc.update("DELETE FROM agent_operations_tasks WHERE action_id=?", actionId);
        }
    }
}
