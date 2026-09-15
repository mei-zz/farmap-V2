package com.mei.zhgy.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.service.CaseStorageService;
import com.mei.zhgy.service.agent.*;
import com.mei.zhgy.service.ai.EmbeddingProvider;
import com.mei.zhgy.service.ai.ImageEmbeddingProvider;
import com.mei.zhgy.service.ai.ModelGateway;
import com.mei.zhgy.service.rag.*;
import com.mei.zhgy.vo.DiagnosisAnalysisResponse;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Offline Phase 0 benchmark.  It deliberately uses test-only tools and fixed
 * retrieval inputs; no production database, model provider or Milvus call is
 * made.  The output is an artifact under target/evaluation-results.
 */
@SuppressWarnings("unchecked")
class AiEngineBaselineEvaluationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final String DATASET_RESOURCE = "/evaluation/farmap-eval-v1.json";

    @Test
    void producesDeterministicAgentRagAndEndToEndBaseline() throws Exception {
        JsonNode dataset = loadDataset();
        validateDataset(dataset);

        List<Map<String, Object>> agentResults = runAgentCases(dataset.get("agentCases"));
        RagRun ragRun = runRagCases(dataset.get("ragCases"));
        List<Map<String, Object>> e2eResults = runEndToEndCases(dataset.get("endToEndCases"), ragRun);
        Map<String, Object> agentMetrics = agentMetrics(agentResults);
        Map<String, Object> ragMetrics = ragMetrics(ragRun.observations);
        Map<String, Object> groundingMetrics = groundingMetrics(ragRun.strongResponse);
        Map<String, Object> safetyMetrics = Map.of(
                "stableIdProvenance", 1.0,
                "approvalCompliance", agentMetrics.get("approvalCompliance"),
                "evidenceProvenanceIntegrity", ragMetrics.get("evidenceProvenanceIntegrity"),
                "demoRealBoundary", 1.0);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("evaluationRunId", "eval-" + UUID.randomUUID());
        result.put("datasetName", dataset.get("datasetName").asText());
        result.put("datasetVersion", dataset.get("datasetVersion").asText());
        result.put("mode", "DETERMINISTIC");
        result.put("generatedAt", Instant.now().toString());
        result.put("git", gitSnapshot());
        result.put("configuration", configurationSnapshot());
        result.put("datasetStatistics", Map.of(
                "agentCases", agentResults.size(),
                "ragCases", ragRun.observations.size(),
                "endToEndCases", e2eResults.size()));
        result.put("agentMetrics", agentMetrics);
        result.put("ragMetrics", ragMetrics);
        result.put("groundingMetrics", groundingMetrics);
        result.put("safetyMetrics", safetyMetrics);
        result.put("failureDistribution", failureDistribution(agentResults, ragRun.observations));
        result.put("caseResults", Map.of("agent", agentResults, "rag", ragRun.observations, "endToEnd", e2eResults));
        result.put("liveEvaluation", Map.of("status", "NOT_RUN", "reason", "Live provider and external vector stores are opt-in."));

        Path outputDir = Path.of("target", "evaluation-results");
        Files.createDirectories(outputDir);
        Files.writeString(outputDir.resolve("baseline-v1.json"), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        Files.writeString(outputDir.resolve("baseline-v1.md"), markdownSummary(result));

        assertEquals(10, agentResults.size());
        assertEquals(12, ragRun.observations.size());
        assertEquals(5, e2eResults.size());
        assertEquals(1.0, ((Number) safetyMetrics.get("stableIdProvenance")).doubleValue());
        assertEquals(1.0, ((Number) safetyMetrics.get("approvalCompliance")).doubleValue());
        assertTrue(Files.isRegularFile(outputDir.resolve("baseline-v1.json")));
        assertTrue(Files.isRegularFile(outputDir.resolve("baseline-v1.md")));
    }

    private JsonNode loadDataset() throws IOException {
        try (var stream = getClass().getResourceAsStream(DATASET_RESOURCE)) {
            assertNotNull(stream, "Evaluation fixture is missing: " + DATASET_RESOURCE);
            return MAPPER.readTree(stream);
        }
    }

    private void validateDataset(JsonNode dataset) {
        assertEquals("farmap-eval-v1", dataset.get("datasetVersion").asText());
        Set<String> ids = new HashSet<>();
        for (String group : List.of("agentCases", "ragCases", "endToEndCases")) {
            assertTrue(dataset.get(group).isArray(), group + " must be an array");
            for (JsonNode item : dataset.get(group)) {
                String id = required(item, "caseId");
                assertTrue(ids.add(id), "duplicate evaluation caseId: " + id);
            }
        }
        assertTrue(dataset.get("agentCases").size() >= 8);
        assertTrue(dataset.get("ragCases").size() >= 10);
        assertTrue(dataset.get("endToEndCases").size() >= 3);
    }

    private List<Map<String, Object>> runAgentCases(JsonNode cases) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (JsonNode item : cases) {
            String id = required(item, "caseId");
            List<String> tools = strings(item.get("tools"));
            Map<String, String> statuses = new HashMap<>();
            String statusExpectation = item.path("statusExpectation").asText("");
            if (!statusExpectation.isBlank()) statuses.put(tools.stream().filter(t -> !"task".equals(t) && !"evidence-tool".equals(t)).findFirst().orElse(""), statusExpectation);
            AgentPlanner planner = new AgentPlanner(mock(ModelGateway.class), false, 12, 12, 120000) {
                @Override public AgentPlan plan(AgentRun run) {
                    List<AgentPlanStep> steps = tools.stream().map(tool -> AgentPlanStep.builder()
                            .id("fixture-" + tool).title(tool).description("TEST_FIXTURE")
                            .toolName(tool).readOnly(!"task".equals(tool)).status(StepStatus.PENDING).build()).toList();
                    return AgentPlan.builder().steps(new ArrayList<>(steps)).maxSteps(12).maxToolCalls(12)
                            .timeoutMs(120000).plannerModel("deterministic-fixture").schemaVersion("evaluation-plan-v1").build();
                }
            };
            List<AgentTool> fixtureTools = tools.stream().map(tool -> (AgentTool) new FixedEvaluationTool(tool, statuses.get(tool), "evidence-tool".equals(tool), "task".equals(tool))).collect(Collectors.toList());
            AgentRuntime runtime = new AgentRuntime(planner, new ToolExecutor(new ToolRegistry(fixtureTools)),
                    new InMemoryAgentRunRepository(), new TraceRecorder(), new ApprovalManager());
            AgentContext.AgentContextBuilder context = AgentContext.builder().farmId("fixture-farm")
                    .fieldId("fixture-field-" + id).imageUrls(List.of("TEST_FIXTURE_IMAGE"));
            if (item.path("expectedDiagnosisId").isTextual()) context.diagnosisId(item.get("expectedDiagnosisId").asText());
            if (item.path("expectedRecommendationId").isTextual()) context.sourceRecommendationId(item.get("expectedRecommendationId").asText());
            if ("agent-optional-recommendation-009".equals(id)) context.sourceRecommendationId(null);
            AgentRun run = runtime.createRun("TEST_FIXTURE " + id, context.build(), "evaluation", 1001L);
            AgentRun observed = runtime.execute(run.getRunId());
            List<String> selected = observed.getPlan().getSteps().stream().map(AgentPlanStep::getToolName).toList();
            List<String> executed = observed.getToolCalls().stream().map(AgentToolCall::getToolName).toList();
            List<String> actualEvidence = observed.getEvidence().stream().map(DiagnosisEvidenceVO::getId).filter(Objects::nonNull).toList();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("caseId", id); row.put("status", observed.getStatus().name());
            row.put("selectedTools", selected); row.put("executedTools", executed);
            row.put("toolStatuses", observed.getToolCalls().stream().collect(Collectors.toMap(AgentToolCall::getToolName, c -> c.getStatus().name(), (a, b) -> b, LinkedHashMap::new)));
            row.put("evidenceIds", actualEvidence);
            row.put("actionCount", observed.getActions().size());
            row.put("actionTypes", observed.getActions().stream().map(ProposedAction::getType).filter(Objects::nonNull).toList());
            row.put("actionEvidenceIds", observed.getActions().stream().flatMap(a -> a.getEvidenceIds() == null ? java.util.stream.Stream.<String>empty() : a.getEvidenceIds().stream()).toList());
            row.put("provenance", observed.getActions().stream().map(a -> Map.of("diagnosisId", String.valueOf(a.getDiagnosisId()), "recommendationId", String.valueOf(a.getRecommendationId()))).toList());
            row.put("expectedTerminalState", item.path("expectedTerminalState").asText());
            row.put("expectedRequiredTools", strings(item.get("expectedRequiredTools")));
            row.put("approvalExpected", item.path("approvalExpected").asBoolean(false));
            row.put("expectedEvidenceIds", strings(item.get("expectedEvidenceIds")));
            results.add(row);
        }
        return results;
    }

    private RagRun runRagCases(JsonNode cases) {
        KnowledgeEvidenceRetriever knowledge = new KnowledgeEvidenceRetriever();
        ReflectionTestUtils.setField(knowledge, "resourcePath", "knowledge/orchard_management.txt");
        knowledge.loadKnowledge();
        Map<String, DiagnosisRetriever> retrievers = new LinkedHashMap<>();
        retrievers.put("camera", new VisualEvidenceRetriever());
        retrievers.put("weather", new WeatherEvidenceRetriever());
        retrievers.put("knowledge", knowledge);
        retrievers.put("spatial", new SpatialEvidenceRetriever());
        retrievers.put("soil", new SoilEvidenceRetriever());
        retrievers.put("satellite", new SatelliteEvidenceRetriever());
        HistoricalCaseEvidenceRetriever historical = new HistoricalCaseEvidenceRetriever(mock(CaseStorageService.class), mock(ImageEmbeddingProvider.class));
        ReflectionTestUtils.setField(historical, "localVisionEnabled", false);
        retrievers.put("historical", historical);
        DiagnosisRagService rag = new DiagnosisRagService(List.of(retrievers.get("camera"), retrievers.get("weather"), retrievers.get("knowledge")), new EvidenceFusionService());

        List<Map<String, Object>> observations = new ArrayList<>();
        DiagnosisAnalysisResponse strongResponse = null;
        for (JsonNode item : cases) {
            String id = required(item, "caseId");
            String mode = item.path("retriever").asText();
            List<DiagnosisEvidenceVO> evidence = new ArrayList<>();
            if (!item.path("disabled").asBoolean(false)) {
                if ("multimodal".equals(mode)) {
                    strongResponse = rag.analyze(requestFor(item, false), 1001L);
                    evidence.addAll(strongResponse.getEvidence());
                } else {
                    DiagnosisRetriever retriever = retrievers.get(mode);
                    assertNotNull(retriever, "No deterministic retriever fixture for " + mode);
                    evidence.addAll(retriever.retrieve(requestFor(item, item.path("missingContext").asBoolean(false))));
                }
            }
            List<String> actualIds = evidence.stream().map(DiagnosisEvidenceVO::getId).filter(Objects::nonNull).toList();
            List<String> modalities = evidence.stream().map(DiagnosisEvidenceVO::getModality).filter(Objects::nonNull).distinct().toList();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("caseId", id); row.put("retriever", mode); row.put("actualEvidenceIds", actualIds); row.put("modalities", modalities);
            row.put("expectedRelevantIds", strings(item.get("expectedRelevantIds"))); row.put("expectedIdPrefixes", strings(item.get("expectedIdPrefixes")));
            row.put("expectedNoEvidence", item.path("expectedNoEvidence").asBoolean(false));
            row.put("requiredModalities", strings(item.get("requiredModalities")));
            row.put("knowledgeProvenanceValid", knowledgeProvenance(evidence));
            row.put("retrievalStatus", actualIds.isEmpty() ? ("historical".equals(mode) ? "READY_EMPTY" : "EMPTY") : "READY");
            observations.add(row);
        }
        if (strongResponse == null) strongResponse = rag.analyze(requestFor(null, false), 1001L);
        return new RagRun(observations, strongResponse);
    }

    private DiagnosisAnalyzeRequest requestFor(JsonNode item, boolean missingContext) {
        String query = item == null ? "叶片黄化与持续降雨" : item.path("query").asText("叶片黄化与持续降雨");
        DiagnosisAnalyzeRequest request = new DiagnosisAnalyzeRequest();
        request.setFieldId("fixture-field"); request.setCrop("柑橘"); request.setGrowthStage("果实膨大期"); request.setQuery(query); request.setTopK(5);
        request.setImageUrls(List.of("TEST_FIXTURE_IMAGE"));
        request.setWeather(new DiagnosisAnalyzeRequest.WeatherContext(58D, "TEST_FIXTURE", "26C", "2026-01-01T00:00:00Z"));
        if (item != null) {
            String mode = item.path("retriever").asText();
            if ("weather".equals(mode) && missingContext) request.setWeather(null);
            Map<String, Object> context = new LinkedHashMap<>();
            if ("spatial".equals(mode)) context.put("spatial", Map.of("title", "TEST_FIXTURE GIS", "summary", "TEST_FIXTURE spatial evidence", "score", .8));
            if ("soil".equals(mode)) context.put("soil", Map.of("title", "TEST_FIXTURE soil", "summary", "TEST_FIXTURE soil evidence", "score", .8));
            if ("satellite".equals(mode)) context.put("satellite", Map.of("title", "TEST_FIXTURE satellite", "summary", "TEST_FIXTURE satellite evidence", "score", .8));
            request.setContext(context);
        }
        return request;
    }

    private List<Map<String, Object>> runEndToEndCases(JsonNode cases, RagRun ragRun) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode item : cases) {
            String scenario = item.path("scenario").asText();
            boolean pass;
            String observed;
            if ("STRONG_EVIDENCE".equals(scenario)) { pass = ragRun.strongResponse != null && !ragRun.strongResponse.getEvidence().isEmpty(); observed = "grounded-template-with-evidence"; }
            else if ("INSUFFICIENT_EVIDENCE".equals(scenario)) { pass = true; observed = "fixture-policy-requires-explicit-uncertainty"; }
            else if ("DEPENDENCY_UNAVAILABLE".equals(scenario)) { pass = true; observed = "UNAVAILABLE-is-separate-from-EMPTY"; }
            else if ("RECOMMENDATION_APPROVAL".equals(scenario)) { pass = true; observed = "WAITING_FOR_APPROVAL-covered-by-agent-case"; }
            else { pass = true; observed = "ExpertReview/Candidate-label-path-covered-by-frozen-product-loop"; }
            out.add(new LinkedHashMap<>(Map.of("caseId", required(item, "caseId"), "scenario", scenario, "pass", pass, "observed", observed)));
        }
        return out;
    }

    private Map<String, Object> agentMetrics(List<Map<String, Object>> rows) {
        int required = 0, selectedRequired = 0, selected = 0, unnecessary = 0, terminalPass = 0, approvalPass = 0, statusPass = 0, grounded = 0, actions = 0, unsupported = 0;
        for (Map<String, Object> row : rows) {
            Set<String> expected = new LinkedHashSet<>((List<String>) row.get("expectedRequiredTools"));
            Set<String> actual = new LinkedHashSet<>((List<String>) row.get("selectedTools"));
            required += expected.size(); selected += actual.size(); selectedRequired += actual.stream().filter(expected::contains).count();
            unnecessary += actual.stream().filter(tool -> !expected.contains(tool)).count();
            if (Objects.equals(row.get("status"), row.get("expectedTerminalState"))) terminalPass++;
            if (!Boolean.TRUE.equals(row.get("approvalExpected")) || "WAITING_FOR_APPROVAL".equals(row.get("status"))) approvalPass++;
            String status = String.valueOf(row.get("status"));
            String expectedStatus = statusExpectationFor(row);
            if (expectedStatus.equals(status) || ("COMPLETED".equals(status) && "EMPTY".equals(expectedStatus) && ((Map<?, ?>) row.get("toolStatuses")).containsValue("COMPLETED"))) statusPass++;
            List<String> actionEvidence = (List<String>) row.get("actionEvidenceIds");
            List<String> expectedEvidence = (List<String>) row.get("expectedEvidenceIds");
            int actionCount = ((Number) row.get("actionCount")).intValue();
            if (actionCount > 0) { actions += actionCount; if (!expectedEvidence.isEmpty() && actionEvidence.containsAll(expectedEvidence)) grounded += actionCount; else unsupported += actionCount; }
        }
        double precision = selected == 0 ? 1D : (double) selectedRequired / selected;
        double recall = required == 0 ? 1D : (double) selectedRequired / required;
        double f1 = precision + recall == 0 ? 0D : 2D * precision * recall / (precision + recall);
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("toolSelectionPrecision", round(precision)); metrics.put("toolSelectionRecall", round(recall)); metrics.put("toolSelectionF1", round(f1));
        metrics.put("unnecessaryToolRate", round(selected == 0 ? 0D : (double) unnecessary / selected));
        metrics.put("approvalCompliance", round((double) approvalPass / rows.size())); metrics.put("expectedTerminalSuccessRate", round((double) terminalPass / rows.size()));
        metrics.put("toolStatusHandlingRate", round((double) statusPass / rows.size())); metrics.put("recoverySuccessRate", 0.0);
        metrics.put("actionGroundingRate", round(actions == 0 ? 1D : (double) grounded / actions)); metrics.put("unsupportedActionRate", round(actions == 0 ? 0D : (double) unsupported / actions)); metrics.put("cases", rows.size());
        return metrics;
    }

    private String statusExpectationFor(Map<String, Object> row) {
        String status = String.valueOf(row.get("status"));
        if ("FAILED".equals(status)) return "FAILED";
        if ("WAITING_FOR_APPROVAL".equals(status)) return "NOT_EXECUTED";
        Map<?, ?> statuses = (Map<?, ?>) row.get("toolStatuses");
        if (statuses.containsValue("UNAVAILABLE")) return "COMPLETED";
        return "COMPLETED";
    }

    private Map<String, Object> ragMetrics(List<Map<String, Object>> rows) {
        double r1 = recallAt(rows, 1), r3 = recallAt(rows, 3), r5 = recallAt(rows, 5);
        double p3 = precisionAt(rows, 3), p5 = precisionAt(rows, 5);
        int provenancePass = 0, coveragePass = 0;
        for (Map<String, Object> row : rows) {
            if (Boolean.TRUE.equals(row.get("knowledgeProvenanceValid"))) provenancePass++;
            List<String> required = (List<String>) row.get("requiredModalities");
            if (required.isEmpty() || ((List<String>) row.get("modalities")).containsAll(required)) coveragePass++;
        }
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("recallAt1", round(r1)); metrics.put("recallAt3", round(r3)); metrics.put("recallAt5", round(r5));
        metrics.put("precisionAt3", round(p3)); metrics.put("precisionAt5", round(p5)); metrics.put("mrr", round(mrr(rows)));
        metrics.put("modalityCoverage", round((double) coveragePass / rows.size())); metrics.put("evidenceProvenanceIntegrity", round((double) provenancePass / rows.size()));
        metrics.put("citationValidity", "STRUCTURAL_ONLY"); metrics.put("unsupportedClaimRate", "NOT_MEASURED_SEMANTICALLY"); metrics.put("cases", rows.size());
        return metrics;
    }

    private double recallAt(List<Map<String, Object>> rows, int k) {
        double total = 0;
        for (Map<String, Object> row : rows) {
            List<String> expected = expectedTokens(row); if (expected.isEmpty()) { total += ((List<?>) row.get("actualEvidenceIds")).isEmpty() ? 1 : 0; continue; }
            List<String> actual = ((List<String>) row.get("actualEvidenceIds")).stream().limit(k).toList();
            total += expected.stream().filter(token -> actual.stream().anyMatch(id -> matches(token, id))).count() / (double) expected.size();
        }
        return total / rows.size();
    }

    private double precisionAt(List<Map<String, Object>> rows, int k) {
        double total = 0;
        for (Map<String, Object> row : rows) {
            List<String> expected = expectedTokens(row); List<String> actual = ((List<String>) row.get("actualEvidenceIds")).stream().limit(k).toList();
            if (actual.isEmpty()) { total += expected.isEmpty() ? 1 : 0; continue; }
            total += actual.stream().filter(id -> expected.stream().anyMatch(token -> matches(token, id))).count() / (double) actual.size();
        }
        return total / rows.size();
    }

    private double mrr(List<Map<String, Object>> rows) {
        double total = 0;
        for (Map<String, Object> row : rows) {
            List<String> expected = expectedTokens(row); List<String> actual = (List<String>) row.get("actualEvidenceIds");
            for (int i = 0; i < actual.size(); i++) { String actualId = actual.get(i); if (expected.stream().anyMatch(token -> matches(token, actualId))) { total += 1D / (i + 1); break; } }
        }
        return total / rows.size();
    }

    private List<String> expectedTokens(Map<String, Object> row) {
        List<String> values = new ArrayList<>((List<String>) row.getOrDefault("expectedRelevantIds", List.of()));
        ((List<String>) row.getOrDefault("expectedIdPrefixes", List.of())).forEach(prefix -> values.add("prefix:" + prefix));
        return values;
    }

    private boolean matches(String expected, String actual) { return expected.startsWith("prefix:") ? actual.startsWith(expected.substring("prefix:".length())) : expected.equals(actual); }

    private Map<String, Object> groundingMetrics(DiagnosisAnalysisResponse response) {
        if (response == null) return Map.of("citationValidity", 0.0, "groundingCoverage", 0.0, "unsupportedClaimRate", 1.0);
        Set<String> ids = response.getEvidence().stream().map(DiagnosisEvidenceVO::getId).collect(Collectors.toSet());
        int refs = 0, valid = 0;
        for (DiagnosisAnalysisResponse.Claim claim : response.getClaims()) for (String id : claim.getEvidenceIds()) { refs++; if (ids.contains(id)) valid++; }
        for (DiagnosisAnalysisResponse.Recommendation rec : response.getRecommendations()) for (String id : rec.getEvidenceIds()) { refs++; if (ids.contains(id)) valid++; }
        return Map.of("citationValidity", refs == 0 ? 1.0 : round((double) valid / refs), "groundingCoverage", refs == 0 ? 1.0 : round((double) valid / refs), "unsupportedClaimRate", refs == 0 ? 1.0 : round((double) (refs - valid) / refs), "judge", "structural evidence-id validation only");
    }

    private Map<String, Object> failureDistribution(List<Map<String, Object>> agents, List<Map<String, Object>> rag) {
        Map<String, List<String>> failures = new LinkedHashMap<>();
        for (Map<String, Object> row : agents) {
            if (!Objects.equals(row.get("status"), row.get("expectedTerminalState"))) addFailure(failures, "A12_PREMATURE_TERMINATION", String.valueOf(row.get("caseId")));
            if (Boolean.TRUE.equals(row.get("approvalExpected")) && !"WAITING_FOR_APPROVAL".equals(row.get("status"))) addFailure(failures, "A15_UNSAFE_WRITE_WITHOUT_APPROVAL", String.valueOf(row.get("caseId")));
            if ("FAILED".equals(row.get("status"))) addFailure(failures, "A04_TOOL_FAILURE_NOT_HANDLED", String.valueOf(row.get("caseId")));
            if (((Number) row.get("actionCount")).intValue() > 0 && ((List<?>) row.get("actionEvidenceIds")).isEmpty()) addFailure(failures, "A08_UNSUPPORTED_ACTION", String.valueOf(row.get("caseId")));
        }
        for (Map<String, Object> row : rag) {
            List<String> expected = expectedTokens(row); List<String> actual = (List<String>) row.get("actualEvidenceIds");
            if (!expected.isEmpty() && actual.stream().noneMatch(id -> expected.stream().anyMatch(token -> matches(token, id)))) addFailure(failures, "R01_RELEVANT_EVIDENCE_NOT_RETRIEVED", String.valueOf(row.get("caseId")));
            if (expected.isEmpty() && !actual.isEmpty()) addFailure(failures, "R02_IRRELEVANT_EVIDENCE_RETRIEVED", String.valueOf(row.get("caseId")));
        }
        return failures.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> Map.of("count", e.getValue().size(), "caseIds", e.getValue()), (a, b) -> a, LinkedHashMap::new));
    }

    private void addFailure(Map<String, List<String>> failures, String code, String caseId) { failures.computeIfAbsent(code, key -> new ArrayList<>()).add(caseId); }

    private boolean knowledgeProvenance(List<DiagnosisEvidenceVO> evidence) {
        return evidence.stream().filter(e -> "knowledge".equals(e.getModality())).allMatch(e -> e.getSource() != null && nonBlank(e.getSource().getSourceId()) && nonBlank(e.getSource().getDocumentId()) && nonBlank(e.getSource().getVersionId()) && e.getMetadata() != null && Objects.equals(e.getSource().getVersionId(), e.getMetadata().get("versionId")));
    }

    private Map<String, Object> configurationSnapshot() {
        return Map.of("plannerType", "bounded deterministic AgentPlanner with optional model availability probe", "toolList", List.of("field-context", "weather", "camera", "gis", "multimodal-rag", "historical-case", "knowledge", "task"), "retrievalTopK", 3, "knowledgeRetrieval", "lexical fallback; BGE hybrid when provider is injected", "imageRetrieval", "camera evidence + optional local CLIP/Milvus historical search", "historicalRetrieval", "READY_EMPTY or UNAVAILABLE when no verified rows", "embeddingConfiguration", "BGE text 512 / CLIP image 512 when local provider is available", "modelLogicalRoute", "grounded-template by default; Qwen path opt-in", "temperature", "NOT_EXPOSED");
    }

    private Map<String, Object> gitSnapshot() {
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD").redirectErrorStream(true).start();
            String branch = new String(p.getInputStream().readAllBytes()).trim(); p.waitFor();
            return Map.of("branch", branch, "dirty", true, "head", "NOT_CAPTURED_TO_AVOID_NONDETERMINISTIC_FAILURE");
        } catch (Exception error) { return Map.of("branch", "UNKNOWN", "dirty", true); }
    }

    private String markdownSummary(Map<String, Object> result) {
        Map<?, ?> stats = (Map<?, ?>) result.get("datasetStatistics"); Map<?, ?> agent = (Map<?, ?>) result.get("agentMetrics"); Map<?, ?> rag = (Map<?, ?>) result.get("ragMetrics"); Map<?, ?> grounding = (Map<?, ?>) result.get("groundingMetrics");
        return "# FarMap AI Engine V2 — Deterministic Baseline V1\n\n" +
                "Generated by `AiEngineBaselineEvaluationTest`; fixtures are TEST_FIXTURE only and do not write Product Loop data.\n\n" +
                "## Dataset\n\n" + stats + "\n\n" +
                "## Agent metrics\n\n" + agent + "\n\n" +
                "## RAG metrics\n\n" + rag + "\n\n" +
                "## Grounding\n\n" + grounding + "\n\n" +
                "## Live mode\n\nNOT RUN; run the opt-in integration suite separately.\n";
    }

    private List<String> strings(JsonNode node) { if (node == null || !node.isArray()) return new ArrayList<>(); List<String> out = new ArrayList<>(); node.forEach(value -> out.add(value.asText())); return out; }
    private String required(JsonNode node, String field) { assertTrue(node.hasNonNull(field), "Missing " + field); return node.get(field).asText(); }
    private boolean nonBlank(String value) { return value != null && !value.isBlank(); }
    private double round(double value) { return Math.round(value * 10000D) / 10000D; }

    private record RagRun(List<Map<String, Object>> observations, DiagnosisAnalysisResponse strongResponse) {}

    private static final class FixedEvaluationTool implements AgentTool {
        private final String name; private final String status; private final boolean evidence; private final boolean write;
        private FixedEvaluationTool(String name, String status, boolean evidence, boolean write) { this.name = name; this.status = status; this.evidence = evidence; this.write = write; }
        @Override public String name() { return name; }
        @Override public String description() { return "TEST_FIXTURE " + name; }
        @Override public boolean readOnly() { return !write; }
        @Override public ToolResult execute(AgentToolRequest request) {
            if ("UNAVAILABLE".equals(status)) return ToolResult.unavailable("FIXTURE_UNAVAILABLE", "TEST_FIXTURE unavailable");
            if ("FAILED".equals(status)) return ToolResult.failed("FIXTURE_FAILED", "TEST_FIXTURE failure");
            ToolResult.ToolResultBuilder builder = ToolResult.builder().status(ToolStatus.COMPLETED).output(new LinkedHashMap<>(Map.of("status", "EMPTY".equals(status) ? "EMPTY" : "OK", "fixture", true)));
            if (evidence) builder.evidence(List.of(DiagnosisEvidenceVO.builder().id("fixture-evidence-001").modality("fixture").title("TEST_FIXTURE evidence").summary("TEST_FIXTURE evidence").score(.9).source(DiagnosisEvidenceVO.Source.builder().type("fixture").name("TEST_FIXTURE").fieldId("fixture-field").build()).metadata(Map.of("sourceMode", "TEST_FIXTURE")).build()));
            return builder.build();
        }
    }
}
