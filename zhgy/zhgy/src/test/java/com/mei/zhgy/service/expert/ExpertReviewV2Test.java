package com.mei.zhgy.service.expert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.service.agent.AgentContext;
import com.mei.zhgy.service.agent.AgentToolRequest;
import com.mei.zhgy.service.historical.HistoricalCaseCandidateService;
import com.mei.zhgy.service.operations.OperationsTaskService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpertReviewV2Test {
    @Test
    void createsStableOutcomeReviewAndEligibleCandidateIdempotently() {
        OperationsTaskService operations = new OperationsTaskService();
        String taskId = createCompletedTask(operations, "diagnosis-v2", "recommendation-v2", 7L);
        String outcomeId = String.valueOf(operations.getOutcome(taskId, 7L).get("outcomeId"));
        ExpertReviewV2Service reviews = new ExpertReviewV2Service(null, new ObjectMapper(), null, operations, true);
        HistoricalCaseCandidateService candidates = new HistoricalCaseCandidateService(null, new ObjectMapper(), reviews, operations, true);

        Map<String, Object> review = reviews.create(Map.of("diagnosisId", "diagnosis-v2", "taskId", taskId, "outcomeId", outcomeId,
                "recommendationId", "recommendation-v2", "diagnosisJudgment", "CORRECT", "recommendationJudgment", "APPROPRIATE",
                "outcomeJudgment", "SUCCESSFUL", "expertConfidence", 0.91, "notes", "现场确认"), 7L);
        assertTrue(String.valueOf(review.get("reviewId")).startsWith("review-"));
        assertEquals("OUTCOME_REVIEW", review.get("scope"));
        assertEquals(0L, review.get("version"));
        Map<String, Object> reloaded = reviews.get(String.valueOf(review.get("reviewId")), 7L);
        assertEquals(review.get("reviewId"), reloaded.get("reviewId"));
        assertEquals(taskId, reloaded.get("taskId"));
        assertEquals(outcomeId, reloaded.get("outcomeId"));

        Map<String, Object> candidate = candidates.evaluateForCandidate(String.valueOf(review.get("reviewId")), 7L);
        Map<String, Object> replay = candidates.evaluateForCandidate(String.valueOf(review.get("reviewId")), 7L);
        assertEquals("ELIGIBLE", candidate.get("status"));
        assertEquals(candidate.get("candidateId"), replay.get("candidateId"));
        assertEquals("SUCCESS_CASE", candidate.get("learningValue"));
    }

    @Test
    void rejectsCrossProvenanceAndCrossUserAccess() {
        OperationsTaskService operations = new OperationsTaskService();
        String firstTask = createCompletedTask(operations, "diagnosis-a", null, 7L);
        String secondTask = createCompletedTask(operations, "diagnosis-b", null, 8L);
        String firstOutcome = String.valueOf(operations.getOutcome(firstTask, 7L).get("outcomeId"));
        String secondOutcome = String.valueOf(operations.getOutcome(secondTask, 8L).get("outcomeId"));
        ExpertReviewV2Service reviews = new ExpertReviewV2Service(null, new ObjectMapper(), null, operations, true);

        assertThrows(ExpertReviewWriteException.class, () -> reviews.create(Map.of("diagnosisId", "diagnosis-a", "taskId", firstTask, "outcomeId", secondOutcome,
                "diagnosisJudgment", "CORRECT", "outcomeJudgment", "SUCCESSFUL", "expertConfidence", 0.8), 7L));
        Map<String, Object> review = reviews.create(Map.of("diagnosisId", "diagnosis-a", "taskId", firstTask, "outcomeId", firstOutcome,
                "diagnosisJudgment", "CORRECT", "outcomeJudgment", "SUCCESSFUL", "expertConfidence", 0.8), 7L);
        assertThrows(ExpertReviewWriteException.class, () -> reviews.get(String.valueOf(review.get("reviewId")), 8L));
    }

    @Test
    void retainsFailureLearningSignalAndRejectsInconclusiveCandidate() {
        OperationsTaskService operations = new OperationsTaskService();
        String failureTask = createCompletedTask(operations, "diagnosis-failure", null, 7L);
        String failureOutcome = String.valueOf(operations.getOutcome(failureTask, 7L).get("outcomeId"));
        String inconclusiveTask = createCompletedTask(operations, "diagnosis-inconclusive", null, 7L);
        String inconclusiveOutcome = String.valueOf(operations.getOutcome(inconclusiveTask, 7L).get("outcomeId"));
        ExpertReviewV2Service reviews = new ExpertReviewV2Service(null, new ObjectMapper(), null, operations, true);
        HistoricalCaseCandidateService candidates = new HistoricalCaseCandidateService(null, new ObjectMapper(), reviews, operations, true);

        Map<String, Object> failure = reviews.create(Map.of("diagnosisId", "diagnosis-failure", "taskId", failureTask, "outcomeId", failureOutcome,
                "diagnosisJudgment", "INCORRECT", "outcomeJudgment", "UNSUCCESSFUL", "expertConfidence", 0.95), 7L);
        Map<String, Object> failureCandidate = candidates.evaluateForCandidate(String.valueOf(failure.get("reviewId")), 7L);
        assertEquals("ELIGIBLE", failureCandidate.get("status"));
        assertEquals("CORRECTION_CASE", failureCandidate.get("learningValue"));

        Map<String, Object> inconclusive = reviews.create(Map.of("diagnosisId", "diagnosis-inconclusive", "taskId", inconclusiveTask, "outcomeId", inconclusiveOutcome,
                "diagnosisJudgment", "UNCERTAIN", "outcomeJudgment", "INCONCLUSIVE", "expertConfidence", 0.95), 7L);
        Map<String, Object> notEligible = candidates.evaluateForCandidate(String.valueOf(inconclusive.get("reviewId")), 7L);
        assertEquals("NOT_ELIGIBLE", notEligible.get("status"));
        assertEquals("OUTCOME_INCONCLUSIVE", notEligible.get("eligibilityReason"));
    }

    @Test
    void updateUsesImmutableIdentityAndOptimisticConcurrency() throws Exception {
        OperationsTaskService operations = new OperationsTaskService();
        String taskId = createCompletedTask(operations, "diagnosis-update", null, 7L);
        String outcomeId = String.valueOf(operations.getOutcome(taskId, 7L).get("outcomeId"));
        ExpertReviewV2Service reviews = new ExpertReviewV2Service(null, new ObjectMapper(), null, operations, true);
        Map<String, Object> review = reviews.create(Map.of("diagnosisId", "diagnosis-update", "taskId", taskId, "outcomeId", outcomeId,
                "diagnosisJudgment", "CORRECT", "outcomeJudgment", "SUCCESSFUL", "expertConfidence", 0.8), 7L);
        String reviewId = String.valueOf(review.get("reviewId"));
        assertThrows(ExpertReviewWriteException.class, () -> reviews.update(reviewId, Map.of("version", 0, "outcomeId", "other-outcome"), 7L));
        var pool = Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<Object>> futures = pool.invokeAll(List.of(
                    (Callable<Object>) () -> command(() -> reviews.update(reviewId, Map.of("version", 0, "notes", "A"), 7L)),
                    (Callable<Object>) () -> command(() -> reviews.update(reviewId, Map.of("version", 0, "notes", "B"), 7L))));
            assertEquals(1, futures.stream().map(this::get).filter(value -> value instanceof Map<?, ?>).count());
            assertEquals(1L, reviews.get(reviewId, 7L).get("version"));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void diagnosisOnlyReviewDoesNotBecomeCandidateAndRecommendationRemainsOptional() {
        ExpertReviewV2Service reviews = new ExpertReviewV2Service();
        HistoricalCaseCandidateService candidates = new HistoricalCaseCandidateService(null, new ObjectMapper(), reviews, new OperationsTaskService(), true);
        Map<String, Object> review = reviews.create(Map.of("diagnosisId", "diagnosis-only", "scope", "DIAGNOSIS_ONLY", "diagnosisJudgment", "CORRECT", "expertConfidence", 0.9), 7L);
        assertEquals(null, review.get("taskId"));
        assertEquals(null, review.get("outcomeId"));
        Map<String, Object> result = candidates.evaluateForCandidate(String.valueOf(review.get("reviewId")), 7L);
        assertEquals("NOT_ELIGIBLE", result.get("status"));
        assertEquals("OUTCOME_REVIEW_REQUIRED", result.get("eligibilityReason"));
    }

    private String createCompletedTask(OperationsTaskService operations, String diagnosisId, String recommendationId, Long owner) {
        AgentContext context = AgentContext.builder().fieldId("field-" + diagnosisId).diagnosisId(diagnosisId).sourceRecommendationId(recommendationId).build();
        AgentToolRequest request = AgentToolRequest.builder().runId("run-" + diagnosisId).actionId("action-" + diagnosisId).diagnosisId(diagnosisId).recommendationId(recommendationId).ownerUserId(owner).actorId(String.valueOf(owner)).approvalGranted(true).context(context).build();
        String taskId = String.valueOf(operations.createTask(request).get("taskId")); operations.startTask(taskId, owner); operations.completeTask(taskId, "execution observed", null, null, owner); return taskId;
    }

    private Object command(Callable<Map<String, Object>> callable) { try { return callable.call(); } catch (Exception error) { return error; } }
    private Object get(java.util.concurrent.Future<Object> future) { try { return future.get(); } catch (Exception error) { return error; } }
}
