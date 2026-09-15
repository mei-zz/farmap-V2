package com.mei.zhgy.service.agent;

import com.mei.zhgy.service.ai.ModelGateway;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AgentEvidenceSufficiencyTest {
    @Test void unsupportedWriteIsBlockedBeforeActionCreation() {
        AgentRun run = run(List.of(writeStep()), createContext(null, null, null));
        assertEquals(RunStatus.COMPLETED, run.getStatus());
        assertTrue(run.getActions().isEmpty());
        assertEquals(ApprovalState.NONE, run.getApprovalState());
        assertEquals(EvidenceSufficiencyStatus.INSUFFICIENT, run.getEvidenceSufficiency().getStatus());
        assertTrue(run.getTraces().stream().anyMatch(event -> "action.blocked.insufficient_evidence".equals(event.getType())));
    }

    @Test void supportedWriteStillStopsForApproval() {
        AgentRun run = run(List.of(readEvidenceStep(), writeStep()), createContext(null, null, "supported"));
        assertEquals(RunStatus.WAITING_FOR_APPROVAL, run.getStatus());
        assertEquals(1, run.getActions().size());
        assertEquals(ApprovalState.REQUIRED, run.getApprovalState());
        assertEquals(EvidenceSufficiencyStatus.SUFFICIENT, run.getEvidenceSufficiency().getStatus());
    }

    @Test void recommendationSpecificWriteRequiresSnapshotEvidenceIntersection() {
        AgentRun supported = run(List.of(readEvidenceStep(), writeStep()), createContext("r1", List.of("e1"), "supported"));
        AgentRun unsupported = run(List.of(readEvidenceStep(), writeStep()), createContext("r1", List.of("e2"), "unsupported"));
        assertEquals(RunStatus.WAITING_FOR_APPROVAL, supported.getStatus());
        assertEquals(RunStatus.COMPLETED, unsupported.getStatus());
        assertTrue(unsupported.getActions().isEmpty());
        assertTrue(unsupported.getEvidenceSufficiency().getReasonCodes().contains(EvidenceSufficiencyEvaluator.RECOMMENDATION_EVIDENCE_MISSING));
    }

    @Test void unavailableAndEmptyReadResultsNeverCreateWriteAction() {
        AgentRun unavailable = run(List.of(unavailableStep(), writeStep()), createContext(null, null, "unavailable"));
        AgentRun empty = run(List.of(emptyStep(), writeStep()), createContext(null, null, "empty"));
        assertEquals(RunStatus.COMPLETED, unavailable.getStatus());
        assertEquals(RunStatus.COMPLETED, empty.getStatus());
        assertTrue(unavailable.getActions().isEmpty());
        assertTrue(empty.getActions().isEmpty());
        assertTrue(unavailable.getEvidenceSufficiency().getReasonCodes().contains(EvidenceSufficiencyEvaluator.UNAVAILABLE_DEPENDENCY));
        assertTrue(empty.getEvidenceSufficiency().getReasonCodes().contains(EvidenceSufficiencyEvaluator.EMPTY_RESULT));
    }

    @Test void toolReturnedUnsupportedActionIsFilteredBeforePersistence() {
        AgentRun run = run(List.of(AgentPlanStep.builder().id("suggest").toolName("suggestion").readOnly(true).build()), createContext(null, null, "tool-action"));
        assertEquals(RunStatus.COMPLETED, run.getStatus());
        assertTrue(run.getActions().isEmpty());
        assertTrue(run.getTraces().stream().anyMatch(event -> "action.blocked.insufficient_evidence".equals(event.getType())));
    }

    private AgentRuntime runtime(List<AgentPlanStep> steps) {
        AgentPlanner planner = new AgentPlanner(mock(ModelGateway.class), false, 4, 4, 120000) {
            @Override public AgentPlan plan(AgentRun run) {
                return AgentPlan.builder().steps(steps).maxSteps(4).maxToolCalls(4).timeoutMs(120000).plannerModel("test").schemaVersion("test").build();
            }
        };
        List<AgentTool> tools = new ArrayList<>();
        tools.add(tool("read-evidence", request -> ToolResult.builder().evidence(List.of(evidence("e1"))).output(Map.of("status", "READY")).build()));
        tools.add(tool("unavailable", request -> ToolResult.unavailable("CAMERA_UNAVAILABLE", "camera unavailable")));
        tools.add(tool("empty", request -> ToolResult.builder().output(Map.of("status", "EMPTY")).metadata(Map.of("historicalStatus", "READY_EMPTY")).build()));
        tools.add(tool("suggestion", request -> ToolResult.builder().actions(List.of(ProposedAction.builder().type("operations_task").toolName("task").approvalRequired(true).build())).build()));
        tools.add(tool("task", request -> ToolResult.builder().output(Map.of("taskId", "task-test")).build()));
        return new AgentRuntime(planner, new ToolExecutor(new ToolRegistry(tools)), new InMemoryAgentRunRepository(), new TraceRecorder(), new ApprovalManager());
    }

    private AgentRun run(List<AgentPlanStep> steps, AgentContext context) {
        AgentRuntime runtime = runtime(steps);
        AgentRun created = runtime.createRun("test write", context, "real");
        return runtime.execute(created.getRunId());
    }

    private AgentContext createContext(String recommendationId, List<String> recommendationEvidenceIds, String field) {
        Map<String, Object> diagnosis = new LinkedHashMap<>();
        if (recommendationEvidenceIds != null) diagnosis.put("recommendationEvidenceIds", recommendationEvidenceIds);
        return AgentContext.builder().fieldId(field).diagnosisId(recommendationId == null ? null : "diagnosis-test")
                .sourceRecommendationId(recommendationId).diagnosis(diagnosis).build();
    }

    private AgentTool tool(String name, java.util.function.Function<AgentToolRequest, ToolResult> action) {
        return new AgentTool() {
            public String name() { return name; }
            public String description() { return name; }
            public boolean readOnly() { return !"task".equals(name); }
            public ToolResult execute(AgentToolRequest request) { return action.apply(request); }
        };
    }

    private AgentPlanStep readEvidenceStep() { return AgentPlanStep.builder().id("read").toolName("read-evidence").readOnly(true).build(); }
    private AgentPlanStep unavailableStep() { return AgentPlanStep.builder().id("unavailable").toolName("unavailable").readOnly(true).build(); }
    private AgentPlanStep emptyStep() { return AgentPlanStep.builder().id("empty").toolName("empty").readOnly(true).build(); }
    private AgentPlanStep writeStep() { return AgentPlanStep.builder().id("write").toolName("task").readOnly(false).build(); }
    private DiagnosisEvidenceVO evidence(String id) { return DiagnosisEvidenceVO.builder().id(id).modality("camera").title("图像").summary("可验证观察").score(.9).build(); }
}
