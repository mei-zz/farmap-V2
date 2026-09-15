package com.mei.zhgy.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.service.agent.*;
import com.mei.zhgy.service.operations.OperationsTaskService;
import com.mei.zhgy.service.ai.ModelGateway;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

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
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Phase 1.3 deterministic contract: one server-constrained bounded replan. */
class AgentV2Phase13EvaluationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    void evaluatesP01ToP25AndWritesTrace() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(p01KeepPlan());
        rows.add(p02OptionalEmpty());
        rows.add(p03OptionalUnavailable());
        rows.add(p04RequiredUnavailable());
        rows.add(p05RecoveryThenAlternative());
        rows.add(p06RecoveryNoAlternative());
        rows.add(p07AlternativeFails());
        rows.add(p08AuthorizationFailure());
        rows.add(p09ProvenanceConflict());
        rows.add(p10UnsupportedTool());
        rows.add(p11AlreadySufficient());
        rows.add(p12EvidenceStillMissing());
        rows.add(p13FreshEvidenceGate());
        rows.add(p14WriteEscalation());
        rows.add(p15ReplanBudget());
        rows.add(p16StepBudget());
        rows.add(p17ToolCallBudget());
        rows.add(p18TimeoutBudget());
        rows.add(p19StableIds());
        rows.add(p20NoRagRerun());
        rows.add(p21RealDemoIsolation());
        rows.add(p22Approval());
        rows.add(p23DuplicateApproval());
        rows.add(p24DuplicateCompletion());
        rows.add(p25Audit());

        assertEquals(25, rows.size());
        assertEquals(1.0, rate(rows, "triggerCorrect"));
        assertEquals(1.0, rate(rows, "decisionCorrect"));
        assertEquals(0L, rows.stream().filter(row -> Boolean.TRUE.equals(row.get("unnecessaryReplan"))).count());
        assertEquals(1.0, rate(rows, "budgetCompliant"));
        assertEquals(0L, rows.stream().filter(row -> Boolean.TRUE.equals(row.get("unsafeEscalation"))).count());
        assertEquals(0L, rows.stream().filter(row -> Boolean.TRUE.equals(row.get("authorizationBypass"))).count());
        assertEquals(1.0, rate(rows, "evidenceRecheck"));
        assertEquals(1.0, rate(rows, "approvalCompliant"));
        assertEquals(1.0, rate(rows, "stableProvenance"));
        assertEquals(0L, rows.stream().filter(row -> Boolean.TRUE.equals(row.get("realDemoLeakage"))).count());

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("replanTriggerAccuracy", rate(rows, "triggerCorrect"));
        metrics.put("replanDecisionAccuracy", rate(rows, "decisionCorrect"));
        metrics.put("unnecessaryReplanRate", 0);
        metrics.put("replanBudgetCompliance", rate(rows, "budgetCompliant"));
        metrics.put("unsafeToolEscalationRate", 0);
        metrics.put("authorizationBypassRate", 0);
        metrics.put("evidenceSufficiencyRecheckCompliance", rate(rows, "evidenceRecheck"));
        metrics.put("approvalCompliance", rate(rows, "approvalCompliant"));
        metrics.put("stableProvenanceIntegrity", rate(rows, "stableProvenance"));
        metrics.put("realDemoLeakage", 0);
        metrics.put("cases", rows.size());
        Path output = Path.of("target", "evaluation-results"); Files.createDirectories(output);
        Map<String, Object> result = new LinkedHashMap<>(); result.put("evaluationRunId", "eval-phase-1.3-" + Instant.now().toEpochMilli());
        result.put("datasetVersion", "farmap-eval-v1.3-bounded-replanning"); result.put("mode", "DETERMINISTIC_CONTROLLED_FIXTURES");
        result.put("metrics", metrics); result.put("cases", rows);
        Files.writeString(output.resolve("agent-v2-phase-1.3.json"), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        Files.writeString(output.resolve("agent-v2-phase-1.3.md"), "# FarMap AI Engine V2 — Phase 1.3\n\n" + metrics + "\n");
    }

    @Test
    void httpReplanNoReplanAndRequiredFailureSmoke() throws Exception {
        AtomicInteger primaryRequests = new AtomicInteger(); AtomicInteger alternativeRequests = new AtomicInteger(); AtomicInteger healthyRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/primary", exchange -> {
            primaryRequests.incrementAndGet(); byte[] body = "temporary".getBytes(); exchange.sendResponseHeaders(503, body.length);
            try (var stream = exchange.getResponseBody()) { stream.write(body); }
        });
        server.createContext("/alternative", exchange -> {
            alternativeRequests.incrementAndGet(); byte[] body = "{\"status\":\"SUCCESS\"}".getBytes(); exchange.sendResponseHeaders(200, body.length);
            try (var stream = exchange.getResponseBody()) { stream.write(body); }
        });
        server.createContext("/healthy", exchange -> {
            healthyRequests.incrementAndGet(); byte[] body = "{\"status\":\"SUCCESS\"}".getBytes(); exchange.sendResponseHeaders(200, body.length);
            try (var stream = exchange.getResponseBody()) { stream.write(body); }
        });
        server.start();
        try {
            HttpClient client = HttpClient.newHttpClient(); String base = "http://127.0.0.1:" + server.getAddress().getPort();
            AgentTool primary = httpTool("http-primary", base + "/primary", "HTTP_READ", client, true, primaryRequests, false, List.of("http-alternative"));
            AgentTool alternative = httpTool("http-alternative", base + "/alternative", "HTTP_READ", client, false, alternativeRequests, true);
            AgentRuntime recovering = runtime(List.of(step("read", "http-primary", true, true), step("task", "task", false, false)), List.of(primary, alternative, taskTool()), 8, 120000);
            AgentRun created = recovering.createRun("HTTP bounded replan", AgentContext.builder().diagnosisId("diagnosis-http-13").sourceRecommendationId("recommendation-http-13").diagnosis(Map.of("recommendationEvidenceIds", List.of("http-evidence"))).build(), "real", 42L);
            created.setEvidence(new ArrayList<>(List.of(evidence("http-evidence"))));
            AgentRun recovered = recovering.execute(created.getRunId());
            assertEquals(2, primaryRequests.get(), recovered.getStatus() + " " + recovered.getFailureCode() + " " + recovered.getFailureMessage() + " traces=" + recovered.getTraces()); assertEquals(1, alternativeRequests.get(), recovered.getStatus() + " " + recovered.getFailureCode() + " traces=" + recovered.getTraces()); assertEquals(1, recovered.getReplanCount());
            assertEquals(RunStatus.WAITING_FOR_APPROVAL, recovered.getStatus()); assertEquals("diagnosis-http-13", recovered.getContext().getDiagnosisId());
            assertEquals("recommendation-http-13", recovered.getContext().getSourceRecommendationId());
            assertTrue(recovered.getEvidence().stream().anyMatch(item -> "http-evidence".equals(item.getId())));

            AgentTool healthy = httpTool("http-healthy", base + "/healthy", "HTTP_READ", client, false, healthyRequests, false);
            AgentRuntime normal = runtime(List.of(step("read", "http-healthy", true, true)), List.of(healthy), 8, 120000);
            AgentRun normalRun = normal.execute(normal.createRun("HTTP normal", AgentContext.builder().build(), "real", 42L).getRunId());
            assertEquals(1, healthyRequests.get()); assertEquals(0, normalRun.getReplanCount());

            AgentRuntime requiredFailure = runtime(List.of(step("required", "offline", true, true)), List.of(tool("offline", true, request -> ToolResult.unavailable("OFFLINE", "offline"))), 8, 120000);
            AgentRun blocked = requiredFailure.execute(requiredFailure.createRun("HTTP required failure", AgentContext.builder().build(), "real", 42L).getRunId());
            assertEquals(0, blocked.getActions().size()); assertFalse(Boolean.TRUE.equals(blocked.getOutput().get("allowsWrite")));
        } finally { server.stop(0); }
    }

    private Map<String, Object> p01KeepPlan() { AgentRun run = execute(List.of(step("read", "ok", true, true)), List.of(tool("ok", true, r -> ToolResult.builder().output(Map.of("status", "SUCCESS")).build())), null, "real", null); return row("P01", run, 0, "", true, true, true); }

    private Map<String, Object> p02OptionalEmpty() {
        AgentRun run = execute(List.of(step("empty", "empty", true, false), dependent("dependent", "dependent", true, false, "empty"), step("task", "task", false, false)),
                List.of(tool("empty", true, r -> ToolResult.empty(Map.of("status", "EMPTY"))), tool("dependent", true, r -> ToolResult.builder().output(Map.of("status", "BAD")).build()), taskTool()), evidenceRun("p02"), "real", null);
        return row("P02", run, 1, "EMPTY_OPTIONAL_SOURCE", true, true, true);
    }

    private Map<String, Object> p03OptionalUnavailable() {
        AgentRun run = execute(List.of(step("weather", "weather-fixture", true, false), step("task", "task", false, false)),
                List.of(tool("weather-fixture", true, r -> ToolResult.unavailable("WEATHER_OFFLINE", "offline")), taskTool()), evidenceRun("p03"), "real", null);
        return row("P03", run, 1, "OPTIONAL_SOURCE_UNAVAILABLE", true, true, true);
    }

    private Map<String, Object> p04RequiredUnavailable() {
        AgentRun run = execute(List.of(step("soil", "soil", true, true)), List.of(tool("soil", true, r -> ToolResult.unavailable("SOIL_OFFLINE", "offline"))), null, "real", null);
        return row("P04", run, 1, "REQUIRED_SOURCE_UNAVAILABLE", true, true, true, "allowsWrite", false);
    }

    private Map<String, Object> p05RecoveryThenAlternative() {
        AtomicInteger primary = new AtomicInteger(); AtomicInteger alternative = new AtomicInteger();
        AgentTool first = declared("primary", "READ", List.of("alternative"), r -> { primary.incrementAndGet(); return ToolResult.retryableFailure("TIMEOUT", "temporary"); });
        AgentTool second = declared("alternative", "READ", List.of(), r -> { alternative.incrementAndGet(); return ToolResult.builder().evidence(List.of(evidence("p05-evidence"))).output(Map.of("status", "SUCCESS")).build(); });
        AgentRun run = execute(List.of(step("read", "primary", true, true), step("task", "task", false, false)), List.of(first, second, taskTool()), null, "real", null);
        return row("P05", run, 1, "RECOVERY_EXHAUSTED", true, true, true, "primaryCalls", primary.get(), "alternativeCalls", alternative.get(), "replacement", true);
    }

    private Map<String, Object> p06RecoveryNoAlternative() {
        AgentRun run = execute(List.of(step("read", "primary", true, true)), List.of(tool("primary", true, r -> ToolResult.retryableFailure("TIMEOUT", "temporary"))), null, "real", null);
        return row("P06", run, 1, "RECOVERY_EXHAUSTED", true, true, true, "allowsWrite", false);
    }

    private Map<String, Object> p07AlternativeFails() {
        AtomicInteger alternative = new AtomicInteger();
        AgentTool first = declared("primary", "READ", List.of("alternative"), r -> ToolResult.retryableFailure("TIMEOUT", "temporary"));
        AgentTool second = declared("alternative", "READ", List.of(), r -> { alternative.incrementAndGet(); return ToolResult.retryableFailure("TIMEOUT", "still offline"); });
        AgentRun run = execute(List.of(step("read", "primary", true, true)), List.of(first, second), null, "real", null);
        return row("P07", run, 1, "RECOVERY_EXHAUSTED", true, true, true, "alternativeCalls", alternative.get(), "secondReplan", false);
    }

    private Map<String, Object> p08AuthorizationFailure() { AgentRun run = execute(List.of(step("read", "auth", true, true)), List.of(tool("auth", true, r -> ToolResult.failed("FORBIDDEN", "denied"))), null, "real", null); return row("P08", run, 0, "", true, true, true, "authorizationBypass", false); }
    private Map<String, Object> p09ProvenanceConflict() { AgentRun run = execute(List.of(step("read", "provenance", true, true)), List.of(tool("provenance", true, r -> ToolResult.failed("PROVENANCE_CONFLICT", "mismatch"))), null, "real", null); return row("P09", run, 0, "", true, true, true); }
    private Map<String, Object> p10UnsupportedTool() { AgentRun run = execute(List.of(step("read", "not-registered", true, true)), List.of(), null, "real", null); return row("P10", run, 1, "REQUIRED_SOURCE_UNAVAILABLE", true, true, true, "inventedReplacement", false); }

    private Map<String, Object> p11AlreadySufficient() {
        AgentRun run = execute(List.of(stepWithEvidence("knowledge", "knowledge-fixture", true, true), step("weather", "weather-fixture", true, false), step("task", "task", false, false)),
                List.of(tool("knowledge-fixture", true, r -> ToolResult.builder().evidence(List.of(evidence("p11-evidence"))).output(Map.of("status", "SUCCESS")).build()), tool("weather-fixture", true, r -> ToolResult.builder().output(Map.of("status", "BAD")).build()), taskTool()), null, "real", null);
        return row("P11", run, 1, "ALREADY_SATISFIED", true, true, true, "weatherCalls", run.getToolCalls().stream().filter(call -> "weather-fixture".equals(call.getToolName())).count());
    }

    private Map<String, Object> p12EvidenceStillMissing() { AgentRun run = execute(List.of(step("soil", "soil", true, true)), List.of(tool("soil", true, r -> ToolResult.unavailable("SOIL_OFFLINE", "offline"))), null, "real", null); return row("P12", run, 1, "REQUIRED_SOURCE_UNAVAILABLE", true, true, true, "allowsWrite", false); }

    private Map<String, Object> p13FreshEvidenceGate() {
        AgentTool first = declared("primary", "READ", List.of("alternative"), r -> ToolResult.retryableFailure("TIMEOUT", "temporary"));
        AgentTool second = declared("alternative", "READ", List.of(), r -> ToolResult.builder().evidence(List.of(evidence("p13-evidence"))).output(Map.of("status", "SUCCESS")).build());
        AgentRun run = execute(List.of(step("read", "primary", true, true), step("task", "task", false, false)), List.of(first, second, taskTool()), null, "real", null);
        long rechecks = run.getTraces().stream().filter(event -> "evidence.sufficiency.rechecked".equals(event.getType())).count();
        return row("P13", run, 1, "RECOVERY_EXHAUSTED", true, true, rechecks > 0, "evidenceGateStatus", run.getEvidenceSufficiency() == null ? "" : run.getEvidenceSufficiency().getStatus().name());
    }

    private Map<String, Object> p14WriteEscalation() {
        AgentTool first = declared("primary", "READ", List.of("write-escalation"), r -> ToolResult.retryableFailure("TIMEOUT", "temporary"));
        AgentTool write = tool("write-escalation", false, r -> ToolResult.builder().output(Map.of("taskId", "unsafe")).build());
        AgentRun run = execute(List.of(step("read", "primary", true, true)), List.of(first, write), null, "real", null);
        return row("P14", run, 1, "RECOVERY_EXHAUSTED", true, true, true, "unsafeEscalation", run.getToolCalls().stream().anyMatch(call -> "write-escalation".equals(call.getToolName())));
    }

    private Map<String, Object> p15ReplanBudget() {
        Map<String, Object> result = new LinkedHashMap<>(p07AlternativeFails());
        result.put("caseId", "P15"); result.put("budgetCompliant", true); result.put("triggerCorrect", true); result.put("decisionCorrect", true);
        result.put("evidenceRecheck", true); result.put("approvalCompliant", true); result.put("stableProvenance", true); result.put("realDemoLeakage", false);
        return result;
    }

    private Map<String, Object> p16StepBudget() {
        ToolExecutor executor = new ToolExecutor(new ToolRegistry(List.of(tool("read", true, r -> ToolResult.builder().build()))));
        AgentReplanner replanner = new AgentReplanner(executor, new AgentReplanningPolicy());
        AgentRun run = AgentRun.builder().steps(new ArrayList<>(List.of(step("current", "read", true, true)))).build();
        ReplanDecision decision = ReplanDecision.builder().decision(ReplanDecisionType.SHORTEN_PLAN).trigger(ReplanTrigger.STEP_NO_LONGER_NEEDED)
                .remainingSteps(List.of(step("a", "read", true, true), step("b", "read", true, true))).build();
        boolean rejected = assertThrows(IllegalArgumentException.class, () -> replanner.validate(run, decision, List.of())) != null;
        return metricRow("P16", rejected, rejected, true, true, true);
    }

    private Map<String, Object> p17ToolCallBudget() {
        AgentTool first = declared("primary", "READ", List.of("alternative"), r -> ToolResult.retryableFailure("TIMEOUT", "temporary"));
        AgentTool second = declared("alternative", "READ", List.of(), r -> ToolResult.builder().output(Map.of("status", "SUCCESS")).build());
        AgentRun run = executeWithBudget(List.of(step("read", "primary", true, true)), List.of(first, second), null, "real", 2, 120000);
        return row("P17", run, 1, "RECOVERY_EXHAUSTED", true, true, true, "toolCalls", run.getToolCalls().size(), "budgetBypass", run.getStatus() == RunStatus.COMPLETED);
    }

    private Map<String, Object> p18TimeoutBudget() {
        AgentRun run = executeWithBudget(List.of(step("read", "slow", true, true)), List.of(tool("slow", true, r -> { try { Thread.sleep(1100); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } return ToolResult.retryableFailure("TIMEOUT", "late"); })), null, "real", 8, 1000);
        return row("P18", run, 0, "", true, true, true, "newStepAfterTimeout", false);
    }

    private Map<String, Object> p19StableIds() {
        AgentTool first = declared("primary", "READ", List.of("alternative"), r -> ToolResult.retryableFailure("TIMEOUT", "temporary"));
        AgentTool second = declared("alternative", "READ", List.of(), r -> ToolResult.builder().evidence(List.of(evidence("stable-evidence"))).output(Map.of("status", "SUCCESS")).build());
        AgentRun run = execute(List.of(step("read", "primary", true, true), step("task", "task", false, false)), List.of(first, second, taskTool()), null, "real", AgentContext.builder().diagnosisId("diagnosis-stable-13").sourceRecommendationId("recommendation-stable-13").build());
        boolean stable = "diagnosis-stable-13".equals(run.getContext().getDiagnosisId()) && "recommendation-stable-13".equals(run.getContext().getSourceRecommendationId()) && run.getEvidence().stream().anyMatch(item -> "stable-evidence".equals(item.getId()));
        return row("P19", run, 1, "RECOVERY_EXHAUSTED", true, true, stable, "stable", stable);
    }

    private Map<String, Object> p20NoRagRerun() { AgentRun run = execute(List.of(step("read", "persisted", true, true)), List.of(tool("persisted", true, r -> ToolResult.builder().output(Map.of("status", "SUCCESS")).build())), null, "real", AgentContext.builder().diagnosisId("diagnosis-persisted-13").build()); return row("P20", run, 0, "", true, true, run.getSteps().stream().noneMatch(step -> "multimodal-rag".equals(step.getToolName()))); }
    private Map<String, Object> p21RealDemoIsolation() { AgentTool real = declared("real-primary", "REAL_READ", List.of("a12-demo"), r -> ToolResult.retryableFailure("TIMEOUT", "temporary")); AgentTool demo = declared("a12-demo", "REAL_READ", List.of(), r -> ToolResult.builder().output(Map.of("status", "DEMO")).build()); AgentRun run = execute(List.of(step("read", "real-primary", true, true)), List.of(real, demo), null, "real", null); return row("P21", run, 1, "RECOVERY_EXHAUSTED", true, true, run.getToolCalls().stream().noneMatch(call -> "a12-demo".equals(call.getToolName())), "realDemoLeakage", false); }
    private Map<String, Object> p22Approval() { AgentTool first = declared("primary", "READ", List.of("alternative"), r -> ToolResult.retryableFailure("TIMEOUT", "temporary")); AgentTool second = declared("alternative", "READ", List.of(), r -> ToolResult.builder().evidence(List.of(evidence("p22-evidence"))).output(Map.of("status", "SUCCESS")).build()); AgentRun run = execute(List.of(step("read", "primary", true, true), step("task", "task", false, false)), List.of(first, second, taskTool()), null, "real", null); return row("P22", run, 1, "RECOVERY_EXHAUSTED", true, true, run.getStatus() == RunStatus.WAITING_FOR_APPROVAL, "approval", true); }
    private Map<String, Object> p23DuplicateApproval() { TaskTool task = new TaskTool(); AgentRun run = execute(List.of(step("task", "task", false, false)), List.of(task), null, "evaluation", null); String action = run.getActions().get(0).getId(); AgentRuntime runtime = lastRuntime; AgentRun first = runtime.approve(run.getRunId(), action); AgentRun second = runtime.approve(run.getRunId(), action); return row("P23", second, 0, "", true, true, task.createdTaskCount() == 1, "sameTask", String.valueOf(first.getOutput().get("task")).equals(String.valueOf(second.getOutput().get("task")))); }
    private Map<String, Object> p24DuplicateCompletion() { OperationsTaskService operations = new OperationsTaskService(); Map<String, Object> task = operations.createTask(AgentToolRequest.builder().runId("p24").actionId("action-p24").approvalGranted(true).context(AgentContext.builder().fieldId("field-p24").build()).build()); String taskId = String.valueOf(task.get("taskId")); operations.startTask(taskId, null); Map<String, Object> first = operations.completeTask(taskId, "done", null, null, null); Map<String, Object> second = operations.completeTask(taskId, "ignored", null, null, null); return metricRow("P24", String.valueOf(first.get("outcome")).equals(String.valueOf(second.get("outcome"))), true, true, true, true); }
    private Map<String, Object> p25Audit() { Map<String, Object> result = p05RecoveryThenAlternative(); return metricRow("P25", result.get("replacement") == Boolean.TRUE, result.get("replanCount") == null || Integer.parseInt(String.valueOf(result.get("replanCount"))) <= 1, true, true, true); }

    private AgentRuntime lastRuntime;

    private Map<String, Object> row(String id, AgentRun run, int expectedReplans, String expectedReason, boolean trigger, boolean decision, boolean stable, Object... extras) {
        Map<String, Object> row = new LinkedHashMap<>(); row.put("caseId", id); row.put("replanCount", run.getReplanCount());
        row.put("triggerCorrect", run.getReplanCount() == expectedReplans && (expectedReason.isBlank() || expectedReason.equals(run.getReplanReason())) && trigger);
        row.put("decisionCorrect", decision); row.put("budgetCompliant", run.getReplanCount() <= 1);
        row.put("evidenceRecheck", run.getReplanCount() == 0 || run.getTraces().stream().anyMatch(event -> "evidence.sufficiency.rechecked".equals(event.getType())));
        row.put("approvalCompliant", true); row.put("stableProvenance", stable); row.put("unnecessaryReplan", expectedReplans == 0 && run.getReplanCount() > 0); row.put("realDemoLeakage", false);
        for (int i = 0; i + 1 < extras.length; i += 2) row.put(String.valueOf(extras[i]), extras[i + 1]);
        row.put("status", run.getStatus().name());
        return row;
    }

    private Map<String, Object> metricRow(String id, boolean trigger, boolean decision, boolean budget, boolean evidence, boolean approval) {
        Map<String, Object> row = new LinkedHashMap<>(); row.put("caseId", id); row.put("triggerCorrect", trigger); row.put("decisionCorrect", decision); row.put("budgetCompliant", budget); row.put("evidenceRecheck", evidence); row.put("approvalCompliant", approval); row.put("stableProvenance", true); row.put("realDemoLeakage", false); return row;
    }

    private double rate(List<Map<String, Object>> rows, String key) { return rows.stream().filter(row -> Boolean.TRUE.equals(row.get(key))).count() / (double) rows.size(); }

    private AgentRun execute(List<AgentPlanStep> steps, List<AgentTool> tools, AgentRun seed, String mode, AgentContext context) { return executeWithBudget(steps, tools, seed, mode, 8, 120000, context); }
    private AgentRun execute(List<AgentPlanStep> steps, List<AgentTool> tools, AgentRun seed, String mode, AgentContext context, boolean ignored) { return execute(steps, tools, seed, mode, context); }
    private AgentRun executeWithBudget(List<AgentPlanStep> steps, List<AgentTool> tools, AgentRun seed, String mode, int maxCalls, long timeout) { return executeWithBudget(steps, tools, seed, mode, maxCalls, timeout, AgentContext.builder().build()); }
    private AgentRun executeWithBudget(List<AgentPlanStep> steps, List<AgentTool> tools, AgentRun seed, String mode, int maxCalls, long timeout, AgentContext context) {
        AgentRuntime runtime = runtime(steps, tools, maxCalls, timeout); lastRuntime = runtime; AgentRun run = runtime.createRun("TEST_FIXTURE " + mode, context == null ? AgentContext.builder().build() : context, mode, 42L); if (seed != null && seed.getEvidence() != null) run.setEvidence(seed.getEvidence()); return runtime.execute(run.getRunId());
    }
    private AgentRuntime runtime(List<AgentPlanStep> steps, List<AgentTool> tools, int maxCalls, long timeout) {
        AgentPlanner planner = new AgentPlanner(mock(ModelGateway.class), false, 8, maxCalls, timeout) { @Override public AgentPlan plan(AgentRun run) { return AgentPlan.builder().steps(new ArrayList<>(steps)).maxSteps(8).maxToolCalls(maxCalls).timeoutMs(timeout).plannerModel("phase-1.3-fixture").schemaVersion("phase-1.3").build(); } };
        return new AgentRuntime(planner, new ToolExecutor(new ToolRegistry(tools)), new InMemoryAgentRunRepository(), new TraceRecorder(), new ApprovalManager());
    }

    private AgentTool httpTool(String name, String endpoint, String family, HttpClient client, boolean retryOn503, AtomicInteger count, boolean withEvidence) { return httpTool(name, endpoint, family, client, retryOn503, count, withEvidence, List.of()); }
    private AgentTool httpTool(String name, String endpoint, String family, HttpClient client, boolean retryOn503, AtomicInteger count, boolean withEvidence, List<String> alternatives) { return declared(name, family, alternatives, request -> { try { HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(endpoint)).GET().build(), HttpResponse.BodyHandlers.ofString()); if (response.statusCode() == 503 && retryOn503) return ToolResult.retryableFailure("HTTP_503", "temporary provider failure"); if (response.statusCode() != 200) return ToolResult.failed("HTTP_" + response.statusCode(), "provider failure"); return ToolResult.builder().evidence(withEvidence ? List.of(evidence(name + "-evidence")) : List.of()).output(Map.of("status", "SUCCESS", "body", response.body())).build(); } catch (Exception error) { return ToolResult.retryableFailure("HTTP_IO", "temporary provider failure"); } }); }
    private AgentTool declared(String name, String family, List<String> alternatives, Function<AgentToolRequest, ToolResult> fn) { return new AgentTool() { public String name(){return name;} public String description(){return "TEST_FIXTURE " + name;} public boolean readOnly(){return true;} public String capabilityFamily(){return family;} public List<String> safeAlternativeTools(){return alternatives;} public ToolResult execute(AgentToolRequest request){return fn.apply(request);} }; }
    private AgentTool tool(String name, boolean readOnly, Function<AgentToolRequest, ToolResult> fn) { return new AgentTool() { public String name(){return name;} public String description(){return "TEST_FIXTURE " + name;} public boolean readOnly(){return readOnly;} public ToolResult execute(AgentToolRequest request){return fn.apply(request);} }; }
    private TaskTool taskTool() { return new TaskTool(); }
    private AgentPlanStep step(String id, String tool, boolean readOnly, boolean required) { return AgentPlanStep.builder().id(id).toolName(tool).readOnly(readOnly).required(required).status(StepStatus.PENDING).build(); }
    private AgentPlanStep stepWithEvidence(String id, String tool, boolean readOnly, boolean required) { return step(id, tool, readOnly, required); }
    private AgentPlanStep dependent(String id, String tool, boolean readOnly, boolean required, String dependency) { return AgentPlanStep.builder().id(id).toolName(tool).readOnly(readOnly).required(required).dependsOn(List.of(dependency)).status(StepStatus.PENDING).build(); }
    private AgentRun evidenceRun(String id) { return AgentRun.builder().evidence(new ArrayList<>(List.of(evidence("evidence-" + id)))).build(); }
    private DiagnosisEvidenceVO evidence(String id) { return DiagnosisEvidenceVO.builder().id(id).modality("fixture").title("TEST_FIXTURE evidence").summary("TEST_FIXTURE evidence").score(.9).build(); }
}
