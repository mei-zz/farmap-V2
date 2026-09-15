package com.mei.zhgy.service.expert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.service.agent.AgentContext;
import com.mei.zhgy.service.agent.AgentToolRequest;
import com.mei.zhgy.service.diagnosis.DiagnosisPersistenceService;
import com.mei.zhgy.service.historical.HistoricalCaseCandidateService;
import com.mei.zhgy.service.operations.OperationsTaskService;
import com.mei.zhgy.vo.DiagnosisAnalysisResponse;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExpertReviewV2MysqlTest {
    @Test
    void persistsReviewCandidateAndConcurrencyBoundariesInMysql() throws Exception {
        Assumptions.assumeTrue("mysql".equalsIgnoreCase(System.getenv().getOrDefault("FARMAP_AGENT_PERSISTENCE", "file")), "run with FARMAP_AGENT_PERSISTENCE=mysql");
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(System.getenv().getOrDefault("SPRING_DATASOURCE_URL", "jdbc:mysql://127.0.0.1:3306/zhgy?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"));
        dataSource.setUsername(System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "root"));
        dataSource.setPassword(System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", ""));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        DiagnosisPersistenceService diagnosisPersistence = new DiagnosisPersistenceService(dataSource, mapper);
        OperationsTaskService operations = new OperationsTaskService(dataSource, mapper);
        ExpertReviewV2Service reviews = new ExpertReviewV2Service(dataSource, mapper, diagnosisPersistence, operations, false);
        HistoricalCaseCandidateService candidates = new HistoricalCaseCandidateService(dataSource, mapper, reviews, operations, false);
        String token = UUID.randomUUID().toString();
        String diagnosisId = null, taskId = null, outcomeId = null, reviewId = null, candidateId = null;
        try {
            DiagnosisAnalysisResponse response = DiagnosisAnalysisResponse.builder().runId("source-" + token)
                    .diagnosis(DiagnosisAnalysisResponse.Diagnosis.builder().title("Drainage risk").riskLevel("high").confidence(0.82).summary("snapshot").build())
                    .recommendations(List.of(DiagnosisAnalysisResponse.Recommendation.builder().id("recommendation-mysql").title("Check drainage").build())).build();
            DiagnosisAnalyzeRequest request = new DiagnosisAnalyzeRequest(); request.setFieldId("field-mysql-v2"); request.setFarmId(1); request.setCrop("grape");
            diagnosisId = diagnosisPersistence.persist(response, request, 42L).getDiagnosisId();
            AgentContext context = AgentContext.builder().fieldId("field-mysql-v2").diagnosisId(diagnosisId).sourceRecommendationId("recommendation-mysql").build();
            AgentToolRequest taskRequest = AgentToolRequest.builder().runId("run-" + token).actionId("action-" + token).diagnosisId(diagnosisId).recommendationId("recommendation-mysql").ownerUserId(42L).actorId("42").approvalGranted(true).context(context).build();
            taskId = String.valueOf(operations.createTask(taskRequest).get("taskId")); operations.startTask(taskId, 42L); operations.completeTask(taskId, "observed", null, null, 42L); outcomeId = String.valueOf(operations.getOutcome(taskId, 42L).get("outcomeId"));

            Map<String, Object> review = reviews.create(Map.of("diagnosisId", diagnosisId, "taskId", taskId, "outcomeId", outcomeId, "recommendationId", "recommendation-mysql", "diagnosisJudgment", "INCORRECT", "recommendationJudgment", "INAPPROPRIATE", "outcomeJudgment", "UNSUCCESSFUL", "expertConfidence", 0.95, "diagnosisCorrection", "现场诊断为排水沟堵塞"), 42L);
            reviewId = String.valueOf(review.get("reviewId")); final String persistedReviewId = reviewId; assertEquals(0L, review.get("version"));
            Map<String, Object> duplicateReview = reviews.create(Map.of("diagnosisId", diagnosisId, "taskId", taskId, "outcomeId", outcomeId, "recommendationId", "recommendation-mysql", "diagnosisJudgment", "INCORRECT", "recommendationJudgment", "INAPPROPRIATE", "outcomeJudgment", "UNSUCCESSFUL", "expertConfidence", 0.95), 42L);
            assertEquals(persistedReviewId, duplicateReview.get("reviewId"));
            Map<String, Object> reloaded = new ExpertReviewV2Service(dataSource, mapper, diagnosisPersistence, operations, false).get(persistedReviewId, 42L);
            assertEquals(persistedReviewId, reloaded.get("reviewId")); assertEquals(diagnosisId, reloaded.get("diagnosisId")); assertEquals(outcomeId, reloaded.get("outcomeId"));
            assertThrows(ExpertReviewWriteException.class, () -> reviews.get(persistedReviewId, 99L));
            Map<String, Object> updated = reviews.update(persistedReviewId, Map.of("version", 0, "notes", "verified by agronomist"), 42L); assertEquals(1L, updated.get("version"));
            assertThrows(ExpertReviewWriteException.class, () -> reviews.update(persistedReviewId, Map.of("version", 0, "notes", "stale"), 42L));

            var pool = Executors.newFixedThreadPool(2);
            try {
                List<java.util.concurrent.Future<Map<String, Object>>> futures = pool.invokeAll(List.of(
                        (Callable<Map<String, Object>>) () -> candidates.evaluateForCandidate(persistedReviewId, 42L),
                        (Callable<Map<String, Object>>) () -> candidates.evaluateForCandidate(persistedReviewId, 42L)));
                List<Map<String, Object>> values = new ArrayList<>(); for (var future : futures) values.add(future.get());
                candidateId = String.valueOf(values.get(0).get("candidateId")); assertEquals(candidateId, values.get(1).get("candidateId")); assertEquals("CORRECTION_CASE", values.get(0).get("learningValue"));
            } finally { pool.shutdownNow(); }
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM historical_case_candidates WHERE review_id=?", Integer.class, persistedReviewId));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM expert_review_events WHERE review_id=? AND event_type='EXPERT_REVIEW_CREATED'", Integer.class, persistedReviewId));
        } finally {
            if (reviewId != null) { jdbc.update("DELETE FROM expert_review_events WHERE review_id=?", reviewId); jdbc.update("DELETE FROM historical_case_candidates WHERE review_id=?", reviewId); jdbc.update("DELETE FROM agent_expert_reviews_v2 WHERE review_id=?", reviewId); }
            if (taskId != null) { jdbc.update("DELETE FROM operations_task_events WHERE task_id=?", taskId); jdbc.update("DELETE FROM agent_operation_outcomes WHERE task_id=?", taskId); jdbc.update("DELETE FROM agent_operations_tasks WHERE task_id=?", taskId); }
            if (diagnosisId != null) jdbc.update("DELETE FROM agent_diagnoses WHERE diagnosis_id=?", diagnosisId);
        }
    }
}
