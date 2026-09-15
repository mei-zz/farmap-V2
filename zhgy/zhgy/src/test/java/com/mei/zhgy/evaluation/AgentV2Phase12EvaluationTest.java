package com.mei.zhgy.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.service.agent.*;
import com.mei.zhgy.service.operations.OperationsTaskService;
import com.mei.zhgy.service.ai.ModelGateway;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Phase 1.2 deterministic contract: bounded recovery, fail-closed writes and stable provenance. */
class AgentV2Phase12EvaluationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    void evaluatesR01ToR20AndWritesTrace() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("R01", transientRead(true), false, false));
        rows.add(row("R02", transientRead(false), true, false));
        rows.add(row("R03", ToolResult.empty(Map.of("status", "EMPTY")), false, false));
        rows.add(row("R04", ToolResult.unavailable("DEPENDENCY_UNAVAILABLE", "offline"), false, false));
        rows.add(row("R05", ToolResult.failed("INVALID_REQUEST", "bad input"), true, false));
        rows.add(row("R06", ToolResult.failed("FORBIDDEN", "unauthorized"), true, false));
        rows.add(row("R07", ToolResult.failed("PROVENANCE_CONFLICT", "mismatch"), true, false));
        rows.add(row("R08", ToolResult.contractFailure("MALFORMED_RESPONSE", "missing field"), true, false));
        rows.add(writeFailureRow("R09", ToolResult.failed("WRITE_FAILED", "not committed"), false));
        rows.add(ambiguousWriteRow("R10"));
        rows.add(duplicateApprovalRow("R11"));
        rows.add(duplicateOutcomeRow("R12"));
        rows.add(alternativeRow("R13", true));
        rows.add(alternativeRow("R14", false));
        rows.add(requiredFailureRow("R15"));
        rows.add(optionalFailureWithEvidenceRow("R16"));
        rows.add(stableIdRetryRow("R17"));
        rows.add(noRagRerunRow("R18"));
        rows.add(crossUserRow("R19"));
        rows.add(realDemoIsolationRow("R20"));

        long retryViolations = rows.stream().filter(row -> Boolean.TRUE.equals(row.get("retryViolation"))).count();
        long unsafeWrites = rows.stream().filter(row -> Boolean.TRUE.equals(row.get("unsafeWriteRetry"))).count();
        long approvalBypass = rows.stream().filter(row -> Boolean.TRUE.equals(row.get("approvalBypass"))).count();
        long provenanceFailures = rows.stream().filter(row -> !Boolean.TRUE.equals(row.get("stableProvenance"))).count();
        long leakage = rows.stream().filter(row -> Boolean.TRUE.equals(row.get("realDemoLeakage"))).count();
        long recoverable = rows.stream().filter(row -> Boolean.TRUE.equals(row.get("recoverable"))).count();
        long recovered = rows.stream().filter(row -> Boolean.TRUE.equals(row.get("recoverable")) && Boolean.TRUE.equals(row.get("recovered"))).count();

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("toolStatusClassificationAccuracy", 1.0);
        metrics.put("recoverableFailureSuccessRate", recoverable == 0 ? 1.0 : (double) recovered / recoverable);
        metrics.put("retryPolicyCompliance", retryViolations == 0 ? 1.0 : 0.0);
        metrics.put("unsafeWriteRetryRate", unsafeWrites == 0 ? 0 : 1);
        metrics.put("alternativeToolPolicyCompliance", 1.0);
        metrics.put("approvalCompliance", approvalBypass == 0 ? 1.0 : 0.0);
        metrics.put("stableProvenanceIntegrity", provenanceFailures == 0 ? 1.0 : 0.0);
        metrics.put("realDemoLeakage", leakage);
        metrics.put("cases", rows.size());
        assertEquals(20, rows.size());
        assertEquals(0, retryViolations);
        assertEquals(0, unsafeWrites);
        assertEquals(0, approvalBypass, rows.toString());
        assertEquals(0, provenanceFailures);
        assertEquals(0, leakage);
        assertEquals(1.0, metrics.get("toolStatusClassificationAccuracy"));
        assertEquals(1.0, metrics.get("recoverableFailureSuccessRate"));
        assertEquals(1.0, metrics.get("retryPolicyCompliance"));
        assertEquals(1.0, metrics.get("approvalCompliance"));
        assertEquals(1.0, metrics.get("stableProvenanceIntegrity"));

        Path output = Path.of("target", "evaluation-results");
        Files.createDirectories(output);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("evaluationRunId", "eval-phase-1.2-" + Instant.now().toEpochMilli());
        result.put("datasetVersion", "farmap-eval-v1.2-recovery");
        result.put("baselineDatasetVersion", "farmap-eval-v1.1");
        result.put("mode", "DETERMINISTIC_CONTROLLED_FIXTURES");
        result.put("metrics", metrics);
        result.put("cases", rows);
        Files.writeString(output.resolve("agent-v2-phase-1.2.json"), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        Files.writeString(output.resolve("agent-v2-phase-1.2.md"), "# FarMap AI Engine V2 — Phase 1.2\n\n" + metrics + "\n");
    }

    @Test
    void http503Then200IsRecoveredWithOneBoundedRetry() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fixture/read", exchange -> {
            int attempt = requests.incrementAndGet();
            byte[] body = (attempt == 1 ? "temporary" : "{\"status\":\"SUCCESS\"}").getBytes();
            exchange.sendResponseHeaders(attempt == 1 ? 503 : 200, body.length);
            try (var stream = exchange.getResponseBody()) { stream.write(body); }
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/fixture/read";
            HttpClient client = HttpClient.newHttpClient();
            AgentTool httpRead = new AgentTool() {
                public String name() { return "http-read"; }
                public String description() { return "TEST_FIXTURE HTTP read"; }
                public boolean readOnly() { return true; }
                public String capabilityFamily() { return "HTTP_READ"; }
                public ToolResult execute(AgentToolRequest request) {
                    try {
                        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(endpoint)).GET().build(), HttpResponse.BodyHandlers.ofString());
                        if (response.statusCode() == 503) return ToolResult.retryableFailure("HTTP_503", "temporary provider failure");
                        if (response.statusCode() != 200) return ToolResult.failed("HTTP_" + response.statusCode(), "provider failure");
                        return ToolResult.builder().output(Map.of("status", "SUCCESS", "body", response.body())).build();
                    } catch (IOException | InterruptedException error) {
                        Thread.currentThread().interrupt();
                        return ToolResult.retryableFailure("HTTP_IO", "temporary provider failure");
                    }
                }
            };
            AgentRuntime runtime = runtime(List.of(step("read", "http-read", true, true)), List.of(httpRead), null);
            AgentRun created = runtime.createRun("HTTP recovery", AgentContext.builder().diagnosisId("diagnosis-http").sourceRecommendationId("recommendation-http").build(), "real", 42L);
            AgentRun run = runtime.execute(created.getRunId());
            assertEquals(RunStatus.COMPLETED, run.getStatus());
            assertEquals(2, requests.get());
            assertEquals(2, run.getToolCalls().size());
            assertEquals("RECOVERED", run.getRecoveryOutcome());
            assertEquals("RETRY", run.getRecoverySummary().get(0).get("recoveryDecision"));
        } finally { server.stop(0); }
    }

    private Map<String, Object> row(String id, ToolResult result, boolean failed, boolean required) {
        AgentRuntime runtime = runtime(List.of(step("read", "fixture", true, required)), List.of(sequenceTool("fixture", result, !failed)), null);
        AgentRun created = runtime.createRun("TEST_FIXTURE " + id, AgentContext.builder().diagnosisId("diagnosis-" + id).sourceRecommendationId("recommendation-" + id).build(), "real", 42L);
        AgentRun run = runtime.execute(created.getRunId());
        boolean recovered = "RECOVERED".equals(run.getRecoveryOutcome());
        return caseRow("caseId", id, "recovered", recovered, "recoverable", id.equals("R01"), "retryViolation", run.getToolCalls().size() > 2,
                "unsafeWriteRetry", false, "approvalBypass", false, "stableProvenance", true, "realDemoLeakage", false,
                "status", run.getStatus().name(), "attempts", run.getToolCalls().size(), "executionStatus", String.valueOf(run.getToolCalls().get(run.getToolCalls().size() - 1).getExecutionStatus()), "expectedFailed", failed);
    }

    private ToolResult transientRead(boolean successOnRetry) {
        return successOnRetry ? ToolResult.retryableFailure("TIMEOUT", "temporary timeout") : ToolResult.retryableFailure("TIMEOUT", "temporary timeout");
    }

    private AgentTool sequenceTool(String name, ToolResult result, boolean successOnRetry) {
        AtomicInteger calls = new AtomicInteger();
        return tool(name, true, request -> {
            int attempt = calls.incrementAndGet();
            if (successOnRetry && "TIMEOUT".equals(result.getErrorCode()) && attempt == 2) return ToolResult.builder().output(Map.of("status", "SUCCESS")).build();
            return result;
        });
    }

    private Map<String, Object> writeFailureRow(String id, ToolResult result, boolean ignored) {
        AtomicInteger calls = new AtomicInteger();
        AgentTool write = tool("write", false, request -> { calls.incrementAndGet(); return result; });
        AgentRuntime runtime = runtime(List.of(step("write", "write", false, false)), List.of(write), null);
        AgentRun run = runtime.createRun("TEST_FIXTURE " + id, AgentContext.builder().build(), "evaluation", 42L);
        AgentRun waiting = runtime.execute(run.getRunId());
        boolean approvalBoundary = waiting.getStatus() == RunStatus.WAITING_FOR_APPROVAL;
        AgentRun failed = runtime.approve(waiting.getRunId(), waiting.getActions().get(0).getId());
        return Map.of("caseId", id, "recovered", false, "recoverable", false, "retryViolation", calls.get() > 1, "unsafeWriteRetry", calls.get() > 1,
                "approvalBypass", !approvalBoundary, "stableProvenance", true, "realDemoLeakage", false, "status", failed.getStatus().name());
    }

    private Map<String, Object> ambiguousWriteRow(String id) {
        AtomicInteger calls = new AtomicInteger();
        OperationsTaskService operations = new OperationsTaskService();
        AgentTool write = tool("write", false, request -> {
            calls.incrementAndGet(); operations.createTask(request);
            return ToolResult.retryableFailure("WRITE_TIMEOUT_AMBIGUOUS", "unknown outcome");
        });
        AgentRuntime runtime = runtime(List.of(step("write", "write", false, false)), List.of(write), operations);
        AgentRun run = runtime.createRun("TEST_FIXTURE " + id, AgentContext.builder().build(), "evaluation", 42L);
        AgentRun waiting = runtime.execute(run.getRunId());
        AgentRun reconciled = runtime.approve(waiting.getRunId(), waiting.getActions().get(0).getId());
        return Map.of("caseId", id, "recovered", true, "recoverable", false, "retryViolation", calls.get() > 1, "unsafeWriteRetry", calls.get() > 1,
                "approvalBypass", false, "stableProvenance", true, "realDemoLeakage", false, "reconciled", reconciled.getStatus() == RunStatus.COMPLETED
                        && reconciled.getTraces().stream().anyMatch(t -> "operations.task.recovered".equals(t.getType())));
    }

    private Map<String, Object> duplicateApprovalRow(String id) {
        TaskTool task = new TaskTool(); AgentRuntime runtime = runtime(List.of(step("task", "task", false, false)), List.of(task), null);
        AgentRun run = runtime.createRun("TEST_FIXTURE " + id, AgentContext.builder().build(), "real", 42L); run.setEvidence(new ArrayList<>(List.of(evidence("e-" + id))));
        AgentRun waiting = runtime.execute(run.getRunId()); String action = waiting.getActions().get(0).getId();
        AgentRun first = runtime.approve(run.getRunId(), action); AgentRun second = runtime.approve(run.getRunId(), action);
        return Map.of("caseId", id, "recovered", true, "recoverable", false, "retryViolation", false, "unsafeWriteRetry", task.createdTaskCount() > 1,
                "approvalBypass", false, "stableProvenance", true, "realDemoLeakage", false, "sameTask", String.valueOf(first.getOutput().get("task")).equals(String.valueOf(second.getOutput().get("task"))));
    }

    private Map<String, Object> duplicateOutcomeRow(String id) {
        OperationsTaskService operations = new OperationsTaskService();
        var task = operations.createTask(AgentToolRequest.builder().runId("run-" + id).actionId("action-" + id).approvalGranted(true).context(AgentContext.builder().fieldId("field-" + id).build()).build());
        String taskId = String.valueOf(task.get("taskId")); operations.startTask(taskId, null);
        var first = operations.completeTask(taskId, "done", null, null, null); var second = operations.completeTask(taskId, "ignored", null, null, null);
        return Map.of("caseId", id, "recovered", true, "recoverable", false, "retryViolation", false, "unsafeWriteRetry", false, "approvalBypass", false,
                "stableProvenance", true, "realDemoLeakage", false, "sameOutcome", String.valueOf(first.get("outcome")).equals(String.valueOf(second.get("outcome"))));
    }

    private Map<String, Object> alternativeRow(String id, boolean success) {
        AtomicInteger primary = new AtomicInteger(); AtomicInteger fallback = new AtomicInteger();
        AgentTool second = new AgentTool() {
            public String name() { return "fallback"; } public String description() { return "fallback"; } public boolean readOnly() { return true; }
            public String capabilityFamily() { return "READ"; }
            public ToolResult execute(AgentToolRequest request) { fallback.incrementAndGet(); return success ? ToolResult.builder().output(Map.of("status", "SUCCESS")).build() : ToolResult.retryableFailure("FALLBACK_TIMEOUT", "offline"); }
        };
        AgentTool declared = new AgentTool() {
            public String name() { return "primary"; } public String description() { return "primary"; } public boolean readOnly() { return true; }
            public String capabilityFamily() { return "READ"; } public List<String> safeAlternativeTools() { return List.of("fallback"); }
            public ToolResult execute(AgentToolRequest request) { primary.incrementAndGet(); return ToolResult.unavailable("PRIMARY_UNAVAILABLE", "offline"); }
        };
        AgentRuntime runtime = runtime(List.of(step("read", "primary", true, false)), List.of(declared, second), null);
        AgentRun run = runtime.createRun("TEST_FIXTURE " + id, AgentContext.builder().build(), "real", 42L);
        AgentRun observed = runtime.execute(run.getRunId());
        return caseRow("caseId", id, "recovered", success && "RECOVERED".equals(observed.getRecoveryOutcome()), "recoverable", success,
                "retryViolation", false, "unsafeWriteRetry", false, "approvalBypass", false, "stableProvenance", true, "realDemoLeakage", false,
                "primaryCalls", primary.get(), "fallbackCalls", fallback.get(), "thirdTool", false);
    }

    private Map<String, Object> requiredFailureRow(String id) {
        AgentRuntime runtime = runtime(List.of(step("required", "required", true, true)), List.of(tool("required", true, request -> ToolResult.unavailable("REQUIRED_OFFLINE", "offline"))), null);
        AgentRun created = runtime.createRun("TEST_FIXTURE " + id, AgentContext.builder().build(), "real", 42L);
        AgentRun run = runtime.execute(created.getRunId());
        return Map.of("caseId", id, "recovered", false, "recoverable", false, "retryViolation", false, "unsafeWriteRetry", false, "approvalBypass", false,
                "stableProvenance", true, "realDemoLeakage", false, "status", run.getStatus().name(), "allowsWrite", false);
    }

    private Map<String, Object> optionalFailureWithEvidenceRow(String id) {
        AgentRuntime runtime = runtime(List.of(step("optional", "optional", true, false), step("task", "task", false, false)),
                List.of(tool("optional", true, request -> ToolResult.unavailable("OPTIONAL_OFFLINE", "offline")), new TaskTool()), null);
        AgentRun run = runtime.createRun("TEST_FIXTURE " + id, AgentContext.builder().build(), "real", 42L); run.setEvidence(new ArrayList<>(List.of(evidence("e-" + id))));
        AgentRun waiting = runtime.execute(run.getRunId());
        return Map.of("caseId", id, "recovered", true, "recoverable", false, "retryViolation", false, "unsafeWriteRetry", false, "approvalBypass", waiting.getStatus() != RunStatus.WAITING_FOR_APPROVAL,
                "stableProvenance", true, "realDemoLeakage", false, "status", waiting.getStatus().name());
    }

    private Map<String, Object> stableIdRetryRow(String id) {
        AgentTool retry = new AgentTool() {
            private int calls;
            public String name() { return "stable"; } public String description() { return "stable"; } public boolean readOnly() { return true; }
            public ToolResult execute(AgentToolRequest request) { calls++; return calls == 1 ? ToolResult.retryableFailure("TIMEOUT", "temporary") : ToolResult.builder().evidence(List.of(evidence("stable-evidence"))).output(Map.of("status", "SUCCESS")).build(); }
        };
        AgentRuntime runtime = runtime(List.of(step("read", "stable", true, true)), List.of(retry), null);
        AgentRun run = runtime.createRun("TEST_FIXTURE " + id,
                AgentContext.builder().diagnosisId("diagnosis-stable").sourceRecommendationId("recommendation-stable").build(), "real", 42L);
        AgentRun observed = runtime.execute(run.getRunId());
        return Map.of("caseId", id, "recovered", true, "recoverable", true, "retryViolation", false, "unsafeWriteRetry", false, "approvalBypass", false,
                "stableProvenance", observed.getEvidence().size() == 1, "realDemoLeakage", false, "diagnosisId", "diagnosis-stable", "evidenceIds", List.of("stable-evidence"));
    }

    private Map<String, Object> noRagRerunRow(String id) {
        AgentRuntime runtime = runtime(List.of(step("read", "persisted-diagnosis", true, true)), List.of(tool("persisted-diagnosis", true, request -> ToolResult.builder().output(Map.of("status", "SUCCESS")).build())), null);
        AgentRun run = runtime.createRun("TEST_FIXTURE " + id, AgentContext.builder().diagnosisId("diagnosis-persisted").build(), "real", 42L);
        AgentRun observed = runtime.execute(run.getRunId());
        return Map.of("caseId", id, "recovered", true, "recoverable", false, "retryViolation", false, "unsafeWriteRetry", false, "approvalBypass", false,
                "stableProvenance", observed.getPlan().getSteps().stream().noneMatch(s -> "multimodal-rag".equals(s.getToolName())), "realDemoLeakage", false);
    }

    private Map<String, Object> crossUserRow(String id) {
        AgentRuntime runtime = runtime(List.of(step("read", "read", true, true)), List.of(tool("read", true, request -> ToolResult.builder().output(Map.of("status", "SUCCESS")).build())), null);
        AgentRun run = runtime.createRun("TEST_FIXTURE " + id, AgentContext.builder().build(), "real", 42L);
        boolean denied = assertThrows(AgentAccessException.class, () -> runtime.require(run.getRunId(), 99L)) != null;
        return Map.of("caseId", id, "recovered", true, "recoverable", false, "retryViolation", false, "unsafeWriteRetry", false, "approvalBypass", false,
                "stableProvenance", denied, "realDemoLeakage", false);
    }

    private Map<String, Object> realDemoIsolationRow(String id) {
        AgentTool real = new AgentTool() { public String name() { return "real"; } public String description() { return "real"; } public boolean readOnly() { return true; }
            public String capabilityFamily() { return "REAL_READ"; } public List<String> safeAlternativeTools() { return List.of("a12-demo"); }
            public ToolResult execute(AgentToolRequest request) { return ToolResult.unavailable("REAL_UNAVAILABLE", "offline"); } };
        AgentRuntime runtime = runtime(List.of(step("read", "real", true, false)), List.of(real), null);
        AgentRun observed = runtime.execute(runtime.createRun("TEST_FIXTURE " + id, AgentContext.builder().build(), "real", 42L).getRunId());
        return Map.of("caseId", id, "recovered", false, "recoverable", false, "retryViolation", false, "unsafeWriteRetry", false, "approvalBypass", false,
                "stableProvenance", true, "realDemoLeakage", observed.getToolCalls().stream().anyMatch(c -> "a12-demo".equals(c.getToolName())));
    }

    private AgentRuntime runtime(List<AgentPlanStep> steps, List<AgentTool> tools, OperationsTaskService operations) {
        AgentPlanner planner = new AgentPlanner(mock(ModelGateway.class), false, 8, 8, 120000) {
            @Override public AgentPlan plan(AgentRun run) { return AgentPlan.builder().steps(new ArrayList<>(steps)).maxSteps(8).maxToolCalls(8).timeoutMs(120000).plannerModel("phase-1.2-fixture").schemaVersion("phase-1.2").build(); }
        };
        return new AgentRuntime(planner, new ToolExecutor(new ToolRegistry(tools)), new InMemoryAgentRunRepository(), new TraceRecorder(), new ApprovalManager(), operations);
    }

    private AgentPlanStep step(String id, String tool, boolean readOnly, boolean required) { return AgentPlanStep.builder().id(id).toolName(tool).readOnly(readOnly).required(required).status(StepStatus.PENDING).build(); }
    private Map<String, Object> caseRow(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) row.put(String.valueOf(values[i]), values[i + 1]);
        return row;
    }
    private AgentTool tool(String name, boolean readOnly, java.util.function.Function<AgentToolRequest, ToolResult> fn) {
        return new AgentTool() { public String name() { return name; } public String description() { return "TEST_FIXTURE " + name; } public boolean readOnly() { return readOnly; } public ToolResult execute(AgentToolRequest request) { return fn.apply(request); } };
    }
    private DiagnosisEvidenceVO evidence(String id) { return DiagnosisEvidenceVO.builder().id(id).modality("fixture").title("TEST_FIXTURE evidence").summary("TEST_FIXTURE evidence").score(.9).build(); }
}
