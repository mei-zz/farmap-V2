package com.mei.zhgy.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.service.diagnosis.DiagnosisPersistenceService;
import com.mei.zhgy.service.expert.ExpertReviewV2Service;
import com.mei.zhgy.service.historical.HistoricalCaseCandidateService;
import com.mei.zhgy.service.knowledge.KnowledgeBackendService;
import com.mei.zhgy.service.rag.KnowledgeEvidenceRetriever;
import com.mei.zhgy.service.operations.OperationsTaskService;
import com.mei.zhgy.vo.DiagnosisAnalysisResponse;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Final hard gate for the persisted Knowledge→Decision→Outcome→Candidate chain. */
class ProductLoopEndToEndMysqlTest {
    @Test
    void diagnosisLevelLoopPreservesNullRecommendation() {
        Assumptions.assumeTrue("mysql".equalsIgnoreCase(System.getenv().getOrDefault("FARMAP_AGENT_PERSISTENCE", "file")),
                "run with FARMAP_AGENT_PERSISTENCE=mysql");
        DriverManagerDataSource ds = dataSource(); JdbcTemplate jdbc = new JdbcTemplate(ds); ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        DiagnosisPersistenceService diagnoses = new DiagnosisPersistenceService(ds, mapper); OperationsTaskService operations = new OperationsTaskService(ds, mapper);
        ExpertReviewV2Service reviews = new ExpertReviewV2Service(ds, mapper, diagnoses, operations, false); HistoricalCaseCandidateService candidates = new HistoricalCaseCandidateService(ds, mapper, reviews, operations, false);
        String token = UUID.randomUUID().toString(), diagnosisId = null, taskId = null, outcomeId = null, reviewId = null, candidateId = null;
        try {
            DiagnosisAnalyzeRequest request = new DiagnosisAnalyzeRequest(); request.setFieldId("final-null-rec-" + token);
            DiagnosisAnalysisResponse persisted = diagnoses.persist(DiagnosisAnalysisResponse.builder().runId("source-null-rec-" + token).diagnosis(DiagnosisAnalysisResponse.Diagnosis.builder().title("Diagnosis only").summary("snapshot").build()).build(), request, 42L);
            diagnosisId = persisted.getDiagnosisId();
            AgentContext context = AgentContext.builder().fieldId(request.getFieldId()).diagnosisId(diagnosisId).build();
            Map<String,Object> task = operations.createTask(AgentToolRequest.builder().runId("run-null-rec-" + token).actionId("action-null-rec-" + token).diagnosisId(diagnosisId).ownerUserId(42L).actorId("42").approvalGranted(true).context(context).build());
            taskId = String.valueOf(task.get("taskId")); assertNull(task.get("recommendationId")); operations.startTask(taskId, 42L); outcomeId = String.valueOf(((Map<?,?>) operations.completeTask(taskId, "completed", null, null, 42L).get("outcome")).get("outcomeId"));
            Map<String,Object> review = reviews.create(Map.of("diagnosisId", diagnosisId, "taskId", taskId, "outcomeId", outcomeId, "diagnosisJudgment", "CORRECT", "outcomeJudgment", "SUCCESSFUL", "expertConfidence", .9), 42L); reviewId = String.valueOf(review.get("reviewId"));
            assertNull(review.get("recommendationId")); Map<String,Object> candidate = candidates.evaluateForCandidate(reviewId, 42L); candidateId = String.valueOf(candidate.get("candidateId")); assertEquals("ELIGIBLE", candidate.get("status"));
        } finally {
            delete(jdbc, "expert_review_events", "review_id", reviewId); delete(jdbc, "historical_case_candidates", "candidate_id", candidateId); delete(jdbc, "agent_expert_reviews_v2", "review_id", reviewId);
            delete(jdbc, "operations_task_events", "task_id", taskId); delete(jdbc, "agent_operation_outcomes", "task_id", taskId); delete(jdbc, "agent_operations_tasks", "task_id", taskId); delete(jdbc, "agent_diagnoses", "diagnosis_id", diagnosisId);
        }
    }

    @Test
    void fullProductLoopRetainsStableIdsAcrossRepositoryReloadAndKnowledgeVersionChange() {
        Assumptions.assumeTrue("mysql".equalsIgnoreCase(System.getenv().getOrDefault("FARMAP_AGENT_PERSISTENCE", "file")),
                "run with FARMAP_AGENT_PERSISTENCE=mysql");
        DriverManagerDataSource ds = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        KnowledgeBackendService knowledge = new KnowledgeBackendService(ds, mapper, false);
        DiagnosisPersistenceService diagnoses = new DiagnosisPersistenceService(ds, mapper);
        OperationsTaskService operations = new OperationsTaskService(ds, mapper);
        ExpertReviewV2Service reviews = new ExpertReviewV2Service(ds, mapper, diagnoses, operations, false);
        HistoricalCaseCandidateService candidates = new HistoricalCaseCandidateService(ds, mapper, reviews, operations, false);
        List<String> ids = new ArrayList<>();
        String sourceId = null, documentId = null, versionOne = null, versionTwo = null;
        String diagnosisId = null, runId = null, actionId = null, taskId = null, outcomeId = null, reviewId = null, candidateId = null;
        try {
            String token = UUID.randomUUID().toString();
            Map<String,Object> source = knowledge.createSource(Map.of("name", "final-e2e-" + token), 42L); sourceId = String.valueOf(source.get("sourceId"));
            Map<String,Object> document = knowledge.createDocument(sourceId, Map.of("title", "Final E2E Knowledge"), 42L); documentId = String.valueOf(document.get("documentId"));
            Map<String,Object> first = knowledge.createVersion(documentId, Map.of("content", "finale2e " + token + "：排水沟堵塞时检查根区积水并记录叶片黄化。"), 42L); versionOne = String.valueOf(first.get("versionId"));
            knowledge.activateVersion(documentId, versionOne, 42L);
            final String persistedVersionOne = versionOne;

            DiagnosisAnalyzeRequest request = new DiagnosisAnalyzeRequest(); request.setFieldId("final-field-" + token); request.setFarmId(9); request.setCrop("玉米"); request.setQuery("finale2e " + token);
            List<DiagnosisEvidenceVO> evidence = new KnowledgeEvidenceRetriever(null, knowledge).retrieveWithStatus(request, 42L).getEvidence();
            assertFalse(evidence.isEmpty());
            assertTrue(evidence.stream().anyMatch(item -> persistedVersionOne.equals(item.getMetadata().get("versionId"))));
            DiagnosisAnalysisResponse response = DiagnosisAnalysisResponse.builder().runId("source-" + token)
                    .diagnosis(DiagnosisAnalysisResponse.Diagnosis.builder().title("根区排水风险").riskLevel("high").confidence(.88).summary("可验证的 Final E2E snapshot").build())
                    .evidence(evidence)
                    .recommendations(List.of(DiagnosisAnalysisResponse.Recommendation.builder().id("recommendation-" + token).title("检查排水").priority("high").detail("现场检查").evidenceIds(evidence.stream().map(DiagnosisEvidenceVO::getId).toList()).build()))
                    .build();
            DiagnosisAnalysisResponse persisted = diagnoses.persist(response, request, 42L); diagnosisId = persisted.getDiagnosisId();
            assertNotEquals(persisted.getSourceRunId(), diagnosisId);

            AgentContext context = AgentContext.builder().fieldId(request.getFieldId()).crop(request.getCrop()).diagnosisId(diagnosisId)
                    .sourceRecommendationId(persisted.getRecommendations().get(0).getId()).build();
            AgentPlanner planner = new AgentPlanner(null, false, 8, 12, 120_000);
            ToolRegistry registry = new ToolRegistry(readOnlyTools());
            // TaskTool is the only write-capable tool and receives the same durable Operations service.
            registry = withTaskTool(registry, new TaskTool(operations));
            AgentRuntime runtime = new AgentRuntime(planner, new ToolExecutor(registry), new JdbcAgentRunRepository(jdbc), new TraceRecorder(), new ApprovalManager(ds), operations, diagnoses);
            AgentRun waiting = runtime.createRun("复核该地块排水风险", context, "analysis", 42L);
            runId = waiting.getRunId();
            AgentRun pending = runtime.execute(runId);
            assertEquals(RunStatus.WAITING_FOR_APPROVAL, pending.getStatus());
            actionId = pending.getActions().get(0).getId();
            AgentRun approved = runtime.approve(runId, actionId);
            assertEquals(RunStatus.COMPLETED, approved.getStatus());
            Map<String,Object> task = operations.findTaskByActionId(actionId); assertNotNull(task); taskId = String.valueOf(task.get("taskId"));
            assertEquals(diagnosisId, task.get("diagnosisId")); assertEquals(persisted.getRecommendations().get(0).getId(), task.get("recommendationId")); assertEquals(runId, task.get("agentRunId"));
            operations.startTask(taskId, 42L); Map<String,Object> completed = operations.completeTask(taskId, "现场完成排水检查", "照片已归档", null, 42L);
            outcomeId = String.valueOf(((Map<?,?>) completed.get("outcome")).get("outcomeId"));
            Map<String,Object> review = reviews.create(Map.of("diagnosisId", diagnosisId, "taskId", taskId, "outcomeId", outcomeId,
                    "recommendationId", persisted.getRecommendations().get(0).getId(), "diagnosisJudgment", "CORRECT", "recommendationJudgment", "APPROPRIATE", "outcomeJudgment", "SUCCESSFUL", "expertConfidence", .92, "notes", "Final E2E"), 42L);
            reviewId = String.valueOf(review.get("reviewId"));
            Map<String,Object> candidate = candidates.evaluateForCandidate(reviewId, 42L); candidateId = String.valueOf(candidate.get("candidateId"));
            assertEquals("ELIGIBLE", candidate.get("status"));

            Map<String,Object> second = knowledge.createVersion(documentId, Map.of("content", "finale2e-v2 " + token + "：排水沟堵塞时先检查水位，再记录叶片黄化并安排复拍。"), 42L); versionTwo = String.valueOf(second.get("versionId")); knowledge.activateVersion(documentId, versionTwo, 42L);
            final String persistedVersionTwo = versionTwo;
            DiagnosisAnalysisResponse reloadedDiagnosis = diagnoses.find(diagnosisId, 42L).orElseThrow();
            assertTrue(reloadedDiagnosis.getEvidence().stream().anyMatch(item -> persistedVersionOne.equals(item.getMetadata().get("versionId"))));
            assertTrue(new KnowledgeEvidenceRetriever(null, knowledge).retrieveWithStatus(request, 42L).getEvidence().stream().anyMatch(item -> persistedVersionTwo.equals(item.getMetadata().get("versionId"))));

            DiagnosisAnalysisResponse afterRestart = new DiagnosisPersistenceService(ds, mapper).find(diagnosisId, 42L).orElseThrow();
            AgentRun reloadedRun = new JdbcAgentRunRepository(jdbc).find(runId).orElseThrow();
            assertEquals(diagnosisId, afterRestart.getDiagnosisId()); assertEquals(persisted.getSourceRunId(), afterRestart.getSourceRunId()); assertEquals(runId, reloadedRun.getRunId());
            assertEquals(taskId, operations.getTask(taskId, 42L).get("taskId")); assertEquals(outcomeId, operations.getOutcome(taskId, 42L).get("outcomeId"));
            assertEquals(reviewId, new ExpertReviewV2Service(ds, mapper, diagnoses, operations, false).get(reviewId, 42L).get("reviewId"));
            assertEquals(candidateId, new HistoricalCaseCandidateService(ds, mapper, reviews, operations, false).get(candidateId, 42L).get("candidateId"));
        } finally {
            delete(jdbc, "expert_review_events", "review_id", reviewId); delete(jdbc, "historical_case_candidates", "candidate_id", candidateId); delete(jdbc, "agent_expert_reviews_v2", "review_id", reviewId);
            delete(jdbc, "operations_task_events", "task_id", taskId); delete(jdbc, "agent_operation_outcomes", "task_id", taskId); delete(jdbc, "agent_operations_tasks", "task_id", taskId);
            delete(jdbc, "agent_approvals", "run_id", runId); delete(jdbc, "agent_runs", "run_id", runId); delete(jdbc, "agent_diagnoses", "diagnosis_id", diagnosisId);
            delete(jdbc, "knowledge_events", "entity_id", sourceId); delete(jdbc, "knowledge_events", "entity_id", documentId); delete(jdbc, "knowledge_events", "entity_id", versionOne); delete(jdbc, "knowledge_events", "entity_id", versionTwo);
            delete(jdbc, "knowledge_versions", "document_id", documentId); delete(jdbc, "knowledge_documents", "document_id", documentId); delete(jdbc, "knowledge_sources", "source_id", sourceId);
        }
    }

    private DriverManagerDataSource dataSource() { DriverManagerDataSource ds = new DriverManagerDataSource(); ds.setDriverClassName("com.mysql.cj.jdbc.Driver"); ds.setUrl(System.getenv().getOrDefault("SPRING_DATASOURCE_URL", "jdbc:mysql://127.0.0.1:3306/zhgy?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true")); ds.setUsername(System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "root")); ds.setPassword(System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "")); return ds; }
    private List<AgentTool> readOnlyTools() { List<AgentTool> tools = new ArrayList<>(); for (String name : List.of("field-context", "weather", "camera", "gis", "multimodal-rag", "historical-case", "knowledge")) tools.add(new AgentTool() { public String name(){return name;} public String description(){return name;} public boolean readOnly(){return true;} public ToolResult execute(AgentToolRequest r){return ToolResult.builder().output(Map.of("status", "READY", "tool", name)).build();} }); return tools; }
    private ToolRegistry withTaskTool(ToolRegistry base, AgentTool task) { List<AgentTool> all = new ArrayList<>(base.all().values()); all.add(task); return new ToolRegistry(all); }
    private void delete(JdbcTemplate jdbc, String table, String key, String value) { if (value != null && !value.isBlank()) jdbc.update("DELETE FROM " + table + " WHERE " + key + "=?", value); }
}
