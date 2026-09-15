package com.mei.zhgy.service.agent;

import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.service.ai.ModelGateway;
import com.mei.zhgy.service.diagnosis.DiagnosisPersistenceService;
import com.mei.zhgy.service.diagnosis.InMemoryDiagnosisRepository;
import com.mei.zhgy.vo.DiagnosisAnalysisResponse;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Phase F product-loop hard gates without changing the Phase 0/1.1 fixtures. */
class CommercialAgentProductLoopEvaluationTest {
    @Test
    void canonicalContextReloadsServerSnapshotAndPreservesStableIdsThroughApproval() {
        DiagnosisPersistenceService persistence = new DiagnosisPersistenceService(new InMemoryDiagnosisRepository());
        DiagnosisAnalysisResponse persisted = persistence.persist(response("recommendation-real", List.of("evidence-real")), request("field-phase-f"), 42L);
        String diagnosisId = persisted.getDiagnosisId();

        AgentContext forged = AgentContext.builder().diagnosisId(diagnosisId).sourceRecommendationId("recommendation-real")
                .diagnosis(Map.of("id", "a12-demo", "recommendation", Map.of("id", "recommendation-real", "evidenceIds", List.of("fake-evidence"))))
                .build();
        AgentContext trusted = persistence.buildAgentContext(diagnosisId, "recommendation-real", 42L, forged);
        assertEquals(diagnosisId, trusted.getDiagnosisId());
        assertEquals("recommendation-real", trusted.getSourceRecommendationId());
        assertEquals("服务端建议", ((Map<?, ?>) trusted.getDiagnosis().get("recommendation")).get("title"));
        assertEquals(List.of("evidence-real"), trusted.getDiagnosis().get("recommendationEvidenceIds"));
        assertEquals("field-phase-f", trusted.getFieldId());
        assertNotEquals("a12-demo", trusted.getDiagnosis().get("id"));

        TaskTool taskTool = new TaskTool();
        AgentRuntime runtime = runtime(persistence, taskTool);
        AgentRun waiting = runtime.execute(runtime.createRun("基于诊断建议准备现场复核", trusted, "real", 42L).getRunId());
        assertEquals(RunStatus.WAITING_FOR_APPROVAL, waiting.getStatus());
        assertEquals(diagnosisId, waiting.getContext().getDiagnosisId());
        ProposedAction action = assertSingle(waiting.getActions());
        assertEquals(diagnosisId, action.getDiagnosisId());
        assertEquals("recommendation-real", action.getRecommendationId());
        assertEquals(List.of("evidence-real"), action.getEvidenceIds());
        assertEquals(ApprovalState.REQUIRED, action.getApprovalState());
        assertTrue(waiting.getTraces().stream().anyMatch(item -> "evidence.sufficiency.evaluated".equals(item.getType())));

        AgentRun approved = runtime.approve(waiting.getRunId(), action.getId());
        assertEquals(RunStatus.COMPLETED, approved.getStatus());
        Map<?, ?> task = (Map<?, ?>) approved.getOutput().get("task");
        assertNotNull(task);
        assertEquals(action.getId(), task.get("actionId"));
        assertEquals(waiting.getRunId(), task.get("agentRunId"));
        assertEquals(diagnosisId, task.get("diagnosisId"));
        assertEquals("recommendation-real", task.get("recommendationId"));
        assertEquals(1, taskTool.createdTaskCount());
        AgentRun retry = runtime.approve(waiting.getRunId(), action.getId());
        assertEquals(task.get("taskId"), ((Map<?, ?>) retry.getOutput().get("task")).get("taskId"));
        assertEquals(1, taskTool.createdTaskCount());
    }

    @Test
    void unknownRecommendationOrEvidenceIsRejectedAndUnsupportedToolCreatesNoAction() {
        DiagnosisPersistenceService persistence = new DiagnosisPersistenceService(new InMemoryDiagnosisRepository());
        DiagnosisAnalysisResponse persisted = persistence.persist(response("recommendation-real", List.of("evidence-real")), request("field-phase-f"), 42L);
        String diagnosisId = persisted.getDiagnosisId();
        assertThrows(ProductLoopRelationException.class, () -> persistence.buildAgentContext(diagnosisId, "recommendation-unknown", 42L, null));

        DiagnosisAnalysisResponse bad = responseWithRecommendationEvidence("recommendation-bad", List.of("evidence-unknown"), List.of("evidence-real"));
        DiagnosisAnalysisResponse badPersisted = persistence.persist(bad, request("field-phase-f-bad"), 42L);
        assertThrows(ProductLoopRelationException.class, () -> persistence.buildAgentContext(badPersisted.getDiagnosisId(), "recommendation-bad", 42L, null));

        AgentPlanner unsupportedPlanner = new AgentPlanner(mock(ModelGateway.class), false, 2, 2, 120000) {
            @Override public AgentPlan plan(AgentRun run) {
                return AgentPlan.builder().steps(List.of(AgentPlanStep.builder().id("unsupported").toolName("unregistered-write").readOnly(false).build()))
                        .maxSteps(2).maxToolCalls(2).timeoutMs(120000).plannerModel("test").schemaVersion("phase-f-v1").build();
            }
        };
        AgentRuntime runtime = new AgentRuntime(unsupportedPlanner, new ToolExecutor(new ToolRegistry(List.of())), new InMemoryAgentRunRepository(), new TraceRecorder(), new ApprovalManager());
        AgentRun blocked = runtime.execute(runtime.createRun("尝试未注册动作", AgentContext.builder().fieldId("field-phase-f").build(), "real").getRunId());
        assertEquals(RunStatus.COMPLETED, blocked.getStatus());
        assertTrue(blocked.getActions().isEmpty());
        assertTrue(blocked.getTraces().stream().anyMatch(item -> "action.blocked.unsupported_tool".equals(item.getType())));
    }

    @Test
    void agentRunOwnershipDeniesAnotherUser() {
        AgentPlanner planner = new AgentPlanner(mock(ModelGateway.class), false, 1, 1, 120000) {
            @Override public AgentPlan plan(AgentRun run) {
                return AgentPlan.builder().steps(List.of(AgentPlanStep.builder().id("read").toolName("field-context").readOnly(true).build()))
                        .maxSteps(1).maxToolCalls(1).timeoutMs(120000).plannerModel("test").schemaVersion("phase-f-v1").build();
            }
        };
        AgentRuntime runtime = new AgentRuntime(planner, new ToolExecutor(new ToolRegistry(List.of(new FieldContextTool()))), new InMemoryAgentRunRepository(), new TraceRecorder(), new ApprovalManager());
        AgentRun run = runtime.createRun("读取地块", AgentContext.builder().fieldId("field-phase-f").build(), "real", 42L);
        assertThrows(AgentAccessException.class, () -> runtime.require(run.getRunId(), 99L));
    }

    private AgentRuntime runtime(DiagnosisPersistenceService persistence, TaskTool taskTool) {
        AgentPlanner planner = new AgentPlanner(mock(ModelGateway.class), false, 2, 2, 120000) {
            @Override public AgentPlan plan(AgentRun run) {
                return AgentPlan.builder().steps(List.of(AgentPlanStep.builder().id("task").toolName("task").readOnly(false).build()))
                        .maxSteps(2).maxToolCalls(2).timeoutMs(120000).plannerModel("test").schemaVersion("phase-f-v1").build();
            }
        };
        return new AgentRuntime(planner, new ToolExecutor(new ToolRegistry(List.of(taskTool))), new InMemoryAgentRunRepository(), new TraceRecorder(), new ApprovalManager(), null, persistence);
    }

    private DiagnosisAnalysisResponse response(String recommendationId, List<String> evidenceIds) {
        return responseWithRecommendationEvidence(recommendationId, evidenceIds, evidenceIds);
    }

    private DiagnosisAnalysisResponse responseWithRecommendationEvidence(String recommendationId, List<String> recommendationEvidenceIds, List<String> evidenceIds) {
        DiagnosisEvidenceVO evidence = DiagnosisEvidenceVO.builder().id(evidenceIds.get(0)).modality("knowledge").title("知识证据").summary("服务端证据").build();
        return DiagnosisAnalysisResponse.builder().runId("source-phase-f")
                .diagnosis(DiagnosisAnalysisResponse.Diagnosis.builder().title("真实诊断").riskLevel("medium").confidence(.88).summary("快照摘要").build())
                .evidence(List.of(evidence))
                .recommendations(List.of(DiagnosisAnalysisResponse.Recommendation.builder().id(recommendationId).title("服务端建议").priority("high").detail("复核根区").evidenceIds(recommendationEvidenceIds).build()))
                .metadata(Map.of("mode", "real")).build();
    }

    private DiagnosisAnalyzeRequest request(String fieldId) {
        DiagnosisAnalyzeRequest request = new DiagnosisAnalyzeRequest(); request.setFieldId(fieldId); request.setFarmId(7); request.setCrop("水稻"); return request;
    }

    private ProposedAction assertSingle(List<ProposedAction> actions) { assertEquals(1, actions.size()); return actions.get(0); }
}
