package com.mei.zhgy.service.agent;

import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.service.diagnosis.DiagnosisPersistenceService;
import com.mei.zhgy.service.diagnosis.InMemoryDiagnosisRepository;
import com.mei.zhgy.service.operations.OperationsTaskService;
import com.mei.zhgy.vo.DiagnosisAnalysisResponse;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Phase G gate: the approved Agent action must remain traceable through the
 * durable Operations lifecycle and the canonical Outcome response.
 */
class CommercialMvpFullProductLoopEvaluationTest {
    @Test
    void diagnosisRecommendationAgentApprovalTaskAndOutcomeKeepOneProvenanceChain() {
        OperationsTaskService operations = new OperationsTaskService();
        DiagnosisPersistenceService persistence = new DiagnosisPersistenceService(new InMemoryDiagnosisRepository());
        DiagnosisAnalysisResponse persisted = persistence.persist(
                DiagnosisAnalysisResponse.builder()
                        .runId("run-phase-g-source")
                        .diagnosis(DiagnosisAnalysisResponse.Diagnosis.builder().title("真实诊断").summary("服务端快照").build())
                        .evidence(List.of(DiagnosisEvidenceVO.builder().id("evidence-phase-g").modality("knowledge").title("知识证据").summary("已验证证据").build()))
                        .recommendations(List.of(DiagnosisAnalysisResponse.Recommendation.builder()
                                .id("recommendation-phase-g").title("服务端建议").detail("现场复核").evidenceIds(List.of("evidence-phase-g")).build()))
                        .build(),
                request("field-phase-g"), 42L);
        TaskTool taskTool = new TaskTool(operations);
        AgentPlanner planner = new AgentPlanner(mock(com.mei.zhgy.service.ai.ModelGateway.class), false, 2, 2, 120000) {
            @Override public AgentPlan plan(AgentRun run) {
                return AgentPlan.builder()
                        .steps(List.of(AgentPlanStep.builder().id("create-task").toolName("task").readOnly(false).build()))
                        .maxSteps(2).maxToolCalls(2).timeoutMs(120000)
                        .plannerModel("test").schemaVersion("phase-g-v1").build();
            }
        };
        AgentRuntime runtime = new AgentRuntime(
                planner,
                new ToolExecutor(new ToolRegistry(List.of(taskTool))),
                new InMemoryAgentRunRepository(),
                new TraceRecorder(),
                new ApprovalManager(), operations, persistence);

        AgentContext context = AgentContext.builder()
                .fieldId("field-phase-g")
                .diagnosisId(persisted.getDiagnosisId())
                .sourceRecommendationId("recommendation-phase-g")
                .build();
        AgentRun waiting = runtime.execute(runtime.createRun("完成 Phase G 现场复核", context, "real", 42L).getRunId());

        assertEquals(RunStatus.WAITING_FOR_APPROVAL, waiting.getStatus());
        ProposedAction action = waiting.getActions().get(0);
        assertEquals(persisted.getDiagnosisId(), action.getDiagnosisId());
        assertEquals("recommendation-phase-g", action.getRecommendationId());
        assertTrue(action.isApprovalRequired());

        AgentRun approved = runtime.approve(waiting.getRunId(), action.getId(), 42L);
        Map<?, ?> task = (Map<?, ?>) approved.getOutput().get("task");
        assertNotNull(task);
        assertEquals(action.getId(), task.get("actionId"));
        assertEquals(persisted.getDiagnosisId(), task.get("diagnosisId"));
        assertEquals("recommendation-phase-g", task.get("recommendationId"));

        String taskId = String.valueOf(task.get("taskId"));
        operations.assignTask(taskId, "pilot-operator", 42L);
        operations.startTask(taskId, 42L);
        Map<String, Object> completion = operations.completeTask(taskId, "现场复核完成", "operator-confirmed", "Phase G", 42L);
        Map<String, Object> outcome = cast(completion.get("outcome"));
        assertEquals(persisted.getDiagnosisId(), outcome.get("diagnosisId"));
        assertEquals("recommendation-phase-g", outcome.get("recommendationId"));
        assertEquals(waiting.getRunId(), outcome.get("agentRunId"));
        assertEquals(taskId, operations.getOutcome(taskId, 42L).get("taskId"));

        Map<String, Object> replay = operations.completeTask(taskId, "ignored retry", null, null, 42L);
        assertEquals(outcome.get("outcomeId"), cast(replay.get("outcome")).get("outcomeId"));
        assertEquals(1, operations.listEvents(taskId, 42L).stream()
                .filter(event -> "OUTCOME_CREATED".equals(event.get("eventType"))).count());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cast(Object value) { return (Map<String, Object>) value; }

    private DiagnosisAnalyzeRequest request(String fieldId) {
        DiagnosisAnalyzeRequest request = new DiagnosisAnalyzeRequest();
        request.setFieldId(fieldId);
        request.setFarmId(7);
        request.setCrop("柑橘");
        return request;
    }
}
