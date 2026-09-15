package com.mei.zhgy.service.agent;

import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.service.diagnosis.DiagnosisPersistenceService;
import com.mei.zhgy.service.diagnosis.InMemoryDiagnosisRepository;
import com.mei.zhgy.service.operations.OperationsTaskService;
import com.mei.zhgy.vo.DiagnosisAnalysisResponse;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import com.mei.zhgy.service.ai.ModelGateway;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Contract tests for the stable Recommendation -> Agent -> Approval -> Task graph. */
class ProductLoopRelationshipTest {
    @Test
    void recommendationIsResolvedOnlyFromThePersistedDiagnosisSnapshot() {
        InMemoryDiagnosisRepository repository = new InMemoryDiagnosisRepository();
        DiagnosisPersistenceService persistence = new DiagnosisPersistenceService(repository);
        DiagnosisAnalysisResponse diagnosisOne = persistence.persist(response("source-one", "recommendation-r1", "同名建议"), request("A-12"), 42L);
        DiagnosisAnalysisResponse diagnosisTwo = persistence.persist(response("source-two", "recommendation-r2", "同名建议"), request("A-13"), 42L);

        assertTrue(persistence.findRecommendation(diagnosisOne.getDiagnosisId(), "recommendation-r1", 42L).isPresent());
        assertTrue(persistence.findRecommendation(diagnosisOne.getDiagnosisId(), "recommendation-r2", 42L).isEmpty(),
                "Recommendation identity 不能跨 Diagnosis 或按标题匹配");
        assertEquals("recommendation-r2", persistence.findRecommendation(diagnosisTwo.getDiagnosisId(), "recommendation-r2", 42L).orElseThrow().getId());
    }

    @Test
    void diagnosisLevelRunMayOmitRecommendationAndExplicitRecommendationRequiresOwnership() {
        InMemoryDiagnosisRepository repository = new InMemoryDiagnosisRepository();
        DiagnosisPersistenceService persistence = new DiagnosisPersistenceService(repository);
        DiagnosisAnalysisResponse diagnosis = persistence.persist(response("source-one", "recommendation-r1", "复核施肥"), request("A-12"), 42L);
        AgentRuntime runtime = runtime(persistence);

        AgentRun diagnosisLevel = runtime.createRun("总结该诊断", AgentContext.builder().diagnosisId(diagnosis.getDiagnosisId()).fieldId("A-12").build(), "real", 42L);
        assertNull(diagnosisLevel.getContext().getSourceRecommendationId());

        AgentRun recommendationLevel = runtime.createRun("执行该建议", AgentContext.builder().diagnosisId(diagnosis.getDiagnosisId())
                .sourceRecommendationId("recommendation-r1").fieldId("A-12").build(), "real", 42L);
        assertEquals("recommendation-r1", recommendationLevel.getContext().getSourceRecommendationId());

        ProductLoopRelationException crossDiagnosis = assertThrows(ProductLoopRelationException.class,
                () -> runtime.createRun("执行跨诊断建议", AgentContext.builder().diagnosisId(diagnosis.getDiagnosisId())
                        .sourceRecommendationId("recommendation-unknown").build(), "real", 42L));
        assertEquals("RECOMMENDATION_NOT_FOUND", crossDiagnosis.getCode());

        ProductLoopRelationException missingDiagnosis = assertThrows(ProductLoopRelationException.class,
                () -> runtime.createRun("缺少诊断来源", AgentContext.builder().sourceRecommendationId("recommendation-r1").build(), "real", 42L));
        assertEquals("VALIDATION_FAILED", missingDiagnosis.getCode());
    }

    @Test
    void actionAndOperationsTaskCarryServerResolvedProvenanceAndRemainIdempotent() {
        InMemoryDiagnosisRepository repository = new InMemoryDiagnosisRepository();
        DiagnosisPersistenceService persistence = new DiagnosisPersistenceService(repository);
        DiagnosisAnalysisResponse diagnosis = persistence.persist(response("source-one", "recommendation-r1", "复核施肥"), request("A-12"), 42L);
        TaskTool taskTool = new TaskTool();
        AgentRuntime runtime = runtime(persistence, taskTool);
        AgentContext context = AgentContext.builder().diagnosisId(diagnosis.getDiagnosisId()).sourceRecommendationId("recommendation-r1").fieldId("A-12").build();

        AgentRun waiting = runtime.execute(runtime.createRun("生成现场复核任务", context, "real", 42L).getRunId());
        assertEquals(RunStatus.WAITING_FOR_APPROVAL, waiting.getStatus());
        ProposedAction action = waiting.getActions().get(0);
        assertEquals(diagnosis.getDiagnosisId(), action.getDiagnosisId());
        assertEquals("recommendation-r1", action.getRecommendationId());

        AgentRun completed = runtime.approve(waiting.getRunId(), action.getId());
        Map<?, ?> task = (Map<?, ?>) completed.getOutput().get("task");
        assertEquals(diagnosis.getDiagnosisId(), task.get("diagnosisId"));
        assertEquals("recommendation-r1", task.get("recommendationId"));
        assertEquals(waiting.getRunId(), task.get("agentRunId"));
        assertEquals(action.getId(), task.get("actionId"));
        assertEquals(RunStatus.COMPLETED, completed.getStatus());

        AgentRun retry = runtime.approve(waiting.getRunId(), action.getId());
        assertEquals(task.get("taskId"), ((Map<?, ?>) retry.getOutput().get("task")).get("taskId"));
        assertEquals(1, taskTool.createdTaskCount());
    }

    @Test
    void taskWriteRejectsClientOrLlmProvenanceOverride() {
        OperationsTaskService operations = new OperationsTaskService();
        AgentContext context = AgentContext.builder().diagnosisId("diagnosis-server").sourceRecommendationId("recommendation-server").fieldId("A-12").build();
        AgentToolRequest mismatchedDiagnosis = AgentToolRequest.builder().runId("run-1").actionId("action-1").approvalGranted(true)
                .diagnosisId("diagnosis-client").context(context).build();
        ProductLoopRelationException diagnosisError = assertThrows(ProductLoopRelationException.class, () -> operations.createTask(mismatchedDiagnosis));
        assertEquals("PROVENANCE_CONFLICT", diagnosisError.getCode());

        AgentToolRequest mismatchedRecommendation = AgentToolRequest.builder().runId("run-1").actionId("action-2").approvalGranted(true)
                .diagnosisId("diagnosis-server").recommendationId("recommendation-client").context(context).build();
        ProductLoopRelationException recommendationError = assertThrows(ProductLoopRelationException.class, () -> operations.createTask(mismatchedRecommendation));
        assertEquals("PROVENANCE_CONFLICT", recommendationError.getCode());
    }

    @Test
    void legacyTaskWithoutRecommendationRemainsReadable() {
        Map<String, Object> task = new OperationsTaskService().createTask(AgentToolRequest.builder().runId("legacy-run").actionId("legacy-action")
                .approvalGranted(true).context(AgentContext.builder().diagnosisId("diagnosis-legacy").fieldId("A-12").build()).build());
        assertEquals("diagnosis-legacy", task.get("diagnosisId"));
        assertNull(task.get("recommendationId"));
    }

    private AgentRuntime runtime(DiagnosisPersistenceService persistence) {
        return runtime(persistence, new TaskTool());
    }

    private AgentRuntime runtime(DiagnosisPersistenceService persistence, TaskTool taskTool) {
        AgentPlanner planner = new AgentPlanner(mock(ModelGateway.class), false, 2, 2, 120000) {
            @Override public AgentPlan plan(AgentRun run) {
                return AgentPlan.builder().steps(List.of(AgentPlanStep.builder().id("task").toolName("task").readOnly(false).build()))
                        .maxSteps(2).maxToolCalls(2).timeoutMs(120000).plannerModel("test").schemaVersion("test").build();
            }
        };
        return new AgentRuntime(planner, new ToolExecutor(new ToolRegistry(List.of(taskTool))), new InMemoryAgentRunRepository(),
                new TraceRecorder(), new ApprovalManager(), null, persistence);
    }

    private DiagnosisAnalyzeRequest request(String fieldId) {
        DiagnosisAnalyzeRequest request = new DiagnosisAnalyzeRequest();
        request.setFieldId(fieldId); request.setFarmId(7); request.setCrop("水稻");
        return request;
    }

    private DiagnosisAnalysisResponse response(String runId, String recommendationId, String title) {
        DiagnosisEvidenceVO evidence = DiagnosisEvidenceVO.builder().id("evidence-1").modality("camera").title("叶片图像")
                .summary("叶片出现黄化").score(0.91).build();
        return DiagnosisAnalysisResponse.builder().runId(runId)
                .diagnosis(DiagnosisAnalysisResponse.Diagnosis.builder().title("叶片黄化").riskLevel("medium").confidence(0.91)
                        .summary("需要进一步复核氮素供给").alternatives(List.of()).build())
                .claims(List.of(DiagnosisAnalysisResponse.Claim.builder().id("claim-1").text("叶片黄化与氮素不足相关").evidenceIds(List.of("evidence-1")).build()))
                .evidence(List.of(evidence))
                .recommendations(List.of(DiagnosisAnalysisResponse.Recommendation.builder().id(recommendationId).title(title).priority("high").detail("现场取样").evidenceIds(List.of("evidence-1")).build()))
                .retrievalSummary(Map.of("evidence_count", 1)).metadata(Map.of("mode", "real")).build();
    }
}
