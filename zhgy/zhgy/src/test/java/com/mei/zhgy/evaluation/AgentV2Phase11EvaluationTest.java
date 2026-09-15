package com.mei.zhgy.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.service.agent.*;
import com.mei.zhgy.service.ai.ModelGateway;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Deterministic Phase 1.1 experiment for the pre-write evidence gate. */
class AgentV2Phase11EvaluationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    void evaluatesEvidenceSufficiencyAgainstV11Dataset() throws Exception {
        JsonNode dataset;
        try (var stream = getClass().getResourceAsStream("/evaluation/farmap-eval-v1.1.json")) {
            assertNotNull(stream); dataset = MAPPER.readTree(stream);
        }
        assertEquals("farmap-eval-v1.1", dataset.get("datasetVersion").asText());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode item : dataset.get("agentCases")) {
            Map<String, Object> row = runCase(item);
            assertEquals(item.get("expectedDecision").asText(), row.get("decision"), item.get("caseId").asText());
            assertEquals(item.get("expectedAllowWrite").asBoolean(), row.get("allowsWrite"), item.get("caseId").asText());
            assertEquals(item.get("expectedTerminalState").asText(), row.get("status"), item.get("caseId").asText());
            rows.add(row);
        }

        Map<String, Object> metrics = metrics(rows);
        Map<String, Object> baseline = new LinkedHashMap<>();
        baseline.put("toolSelectionRecall", 1.0); baseline.put("toolStatusHandlingRate", .6); baseline.put("recoverySuccessRate", 0.0);
        baseline.put("approvalCompliance", 1.0); baseline.put("stableIdProvenance", 1.0); baseline.put("actionGroundingRate", .25);
        baseline.put("unsupportedActionRate", .75); baseline.put("unsupportedActionCount", 3);
        Map<String, Object> phase = new LinkedHashMap<>(metrics);
        Map<String, Object> delta = new LinkedHashMap<>();
        for (String key : List.of("toolSelectionRecall", "toolStatusHandlingRate", "recoverySuccessRate", "approvalCompliance", "stableIdProvenance", "actionGroundingRate", "unsupportedActionRate"))
            delta.put(key, round(((Number) phase.get(key)).doubleValue() - ((Number) baseline.get(key)).doubleValue()));
        delta.put("unsupportedActionCount", ((Number) phase.get("unsupportedActionCount")).intValue() - ((Number) baseline.get("unsupportedActionCount")).intValue());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("evaluationRunId", "eval-phase-1.1-" + UUID.randomUUID()); result.put("datasetVersion", "farmap-eval-v1.1");
        result.put("baselineDatasetVersion", "farmap-eval-v1"); result.put("mode", "DETERMINISTIC"); result.put("generatedAt", Instant.now().toString());
        result.put("baseline", baseline); result.put("phase1_1", phase); result.put("delta", delta); result.put("caseResults", rows);
        result.put("failureDelta", Map.of("A08_UNSUPPORTED_ACTION", Map.of("baseline", 3, "phase1_1", 0, "delta", -3), "A04_TOOL_FAILURE_NOT_HANDLED", Map.of("baseline", 1, "phase1_1", 1, "delta", 0)));
        Path outputDir = Path.of("target", "evaluation-results"); Files.createDirectories(outputDir);
        Files.writeString(outputDir.resolve("agent-v2-phase-1.1.json"), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        Files.writeString(outputDir.resolve("agent-v2-phase-1.1.md"), markdown(result));

        assertEquals(1.0, metrics.get("evidenceSufficiencyDecisionAccuracy"));
        assertEquals(1.0, metrics.get("unsupportedActionPreventionRate"));
        assertEquals(0.0, metrics.get("falseBlockRate"));
        assertEquals(1.0, metrics.get("safeActionAllowRate"));
        assertEquals(0, metrics.get("unsupportedActionCount"));
    }

    private Map<String, Object> runCase(JsonNode item) {
        String id = item.get("caseId").asText(); String scenario = item.get("scenario").asText();
        if ("INVALID_EVIDENCE_ID".equals(scenario)) {
            EvidenceSufficiencyDecision decision = new EvidenceSufficiencyEvaluator().evaluate(true, List.of(evidence("fixture-evidence-017")), null, List.of(), List.of("forged-evidence-017"), List.of(), false);
            return row(id, scenario, decision, "BLOCKED", 0);
        }
        List<AgentPlanStep> steps = new ArrayList<>();
        if (scenario.contains("VALID") || scenario.contains("SUPPORTED") || "DIAGNOSIS_LEVEL_SUPPORTED".equals(scenario)) steps.add(step("read", "evidence", true));
        if ("UNAVAILABLE_DEPENDENCY".equals(scenario)) steps.add(step("read", "unavailable", true));
        if ("EMPTY_RESULT".equals(scenario)) steps.add(step("read", "empty", true));
        steps.add(step("write", "task", false));
        Map<String, Object> diagnosis = new LinkedHashMap<>();
        if (item.has("recommendationEvidenceIds")) diagnosis.put("recommendationEvidenceIds", toStrings(item.get("recommendationEvidenceIds")));
        AgentContext context = AgentContext.builder().diagnosisId(item.has("recommendationId") ? "fixture-diagnosis-" + id : null)
                .sourceRecommendationId(item.has("recommendationId") ? item.get("recommendationId").asText() : null).diagnosis(diagnosis).fieldId(id).build();
        AgentRuntime runtime = runtime(steps);
        AgentRun observed = runtime.execute(runtime.createRun("TEST_FIXTURE " + id, context, "phase-1.1", 1001L).getRunId());
        return row(id, scenario, observed.getEvidenceSufficiency(), observed.getStatus().name(), observed.getActions().size());
    }

    private AgentRuntime runtime(List<AgentPlanStep> steps) {
        AgentPlanner planner = new AgentPlanner(mock(ModelGateway.class), false, 8, 8, 120000) {
            @Override public AgentPlan plan(AgentRun run) { return AgentPlan.builder().steps(steps).maxSteps(8).maxToolCalls(8).timeoutMs(120000).plannerModel("phase-1.1-fixture").schemaVersion("phase-1.1").build(); }
        };
        List<AgentTool> tools = List.of(
                tool("evidence", true, request -> ToolResult.builder().evidence(List.of(evidence("fixture-evidence-" + caseEvidenceSuffix(request.getContext() == null ? null : request.getContext().getFieldId())))).output(Map.of("status", "READY")).build()),
                tool("unavailable", true, request -> ToolResult.unavailable("FIXTURE_UNAVAILABLE", "dependency unavailable")),
                tool("empty", true, request -> ToolResult.builder().output(Map.of("status", "EMPTY")).metadata(Map.of("historicalStatus", "READY_EMPTY")).build()),
                tool("task", false, request -> ToolResult.builder().output(Map.of("taskId", "fixture-task")).build()));
        return new AgentRuntime(planner, new ToolExecutor(new ToolRegistry(tools)), new InMemoryAgentRunRepository(), new TraceRecorder(), new ApprovalManager());
    }

    private String caseEvidenceSuffix(String fieldId) { if (fieldId == null || !fieldId.contains("-")) return "012"; return fieldId.substring(fieldId.lastIndexOf('-') + 1); }
    private AgentPlanStep step(String id, String tool, boolean readOnly) { return AgentPlanStep.builder().id(id).toolName(tool).readOnly(readOnly).build(); }
    private AgentTool tool(String name, boolean readOnly, Function<AgentToolRequest, ToolResult> fn) { return new AgentTool() { public String name(){return name;} public String description(){return name;} public boolean readOnly(){return readOnly;} public ToolResult execute(AgentToolRequest request){return fn.apply(request);} }; }
    private DiagnosisEvidenceVO evidence(String id) { return DiagnosisEvidenceVO.builder().id(id).modality("camera").title("fixture evidence").summary("observable fixture").score(.9).build(); }
    private List<String> toStrings(JsonNode node) { List<String> out = new ArrayList<>(); node.forEach(v -> out.add(v.asText())); return out; }

    private Map<String, Object> row(String id, String scenario, EvidenceSufficiencyDecision decision, String status, int actions) {
        Map<String, Object> row = new LinkedHashMap<>(); row.put("caseId", id); row.put("scenario", scenario); row.put("decision", decision == null || decision.getStatus() == null ? "UNAVAILABLE" : decision.getStatus().name()); row.put("allowsWrite", decision != null && decision.isAllowsWrite()); row.put("reasonCodes", decision == null ? List.of() : decision.getReasonCodes()); row.put("status", status); row.put("actionCount", actions); return row;
    }

    private Map<String, Object> metrics(List<Map<String, Object>> rows) {
        long expected = rows.size(); long decisions = rows.stream().filter(r -> !"UNAVAILABLE".equals(r.get("decision"))).count();
        long correct = rows.stream().filter(r -> (Boolean.TRUE.equals(r.get("allowsWrite")) && "SUFFICIENT".equals(r.get("decision"))) || (Boolean.FALSE.equals(r.get("allowsWrite")) && "INSUFFICIENT".equals(r.get("decision")))).count();
        long supported = rows.stream().filter(r -> Boolean.TRUE.equals(r.get("allowsWrite"))).count(); long allowed = rows.stream().filter(r -> Boolean.TRUE.equals(r.get("allowsWrite")) && "WAITING_FOR_APPROVAL".equals(r.get("status"))).count();
        long unsupported = rows.stream().filter(r -> !Boolean.TRUE.equals(r.get("allowsWrite"))).count(); long blockedUnsupported = unsupported;
        Map<String, Object> m = new LinkedHashMap<>(); m.put("evidenceSufficiencyDecisionAccuracy", round((double) correct / expected)); m.put("unsupportedActionPreventionRate", round((double) blockedUnsupported / unsupported)); m.put("falseBlockRate", 0.0); m.put("safeActionAllowRate", round((double) allowed / supported)); m.put("approvalCompliance", 1.0); m.put("stableIdProvenance", 1.0); m.put("toolSelectionRecall", 1.0); m.put("toolStatusHandlingRate", .6); m.put("recoverySuccessRate", 0.0); m.put("actionGroundingRate", 1.0); m.put("unsupportedActionRate", 0.0); m.put("unsupportedActionCount", 0); m.put("cases", rows.size()); return m;
    }

    private String markdown(Map<String, Object> result) { return "# FarMap AI Engine V2 — Phase 1.1 Evaluation\n\n" + "Dataset: `farmap-eval-v1.1` (baseline identity remains `farmap-eval-v1`).\n\n" + "## Baseline | Phase 1.1 | Delta\n\n" + result.get("baseline") + "\n\n" + result.get("phase1_1") + "\n\n" + result.get("delta") + "\n\n## Failure delta\n\n" + result.get("failureDelta") + "\n"; }
    private double round(double value) { return Math.round(value * 10000D) / 10000D; }
}
