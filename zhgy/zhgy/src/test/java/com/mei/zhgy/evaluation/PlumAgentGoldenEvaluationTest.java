package com.mei.zhgy.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.service.agent.EvidenceSufficiencyDecision;
import com.mei.zhgy.service.agent.EvidenceSufficiencyEvaluator;
import com.mei.zhgy.service.agent.pi.PiAgentRuntime;
import com.mei.zhgy.service.agent.pi.PiAgentRuntime.ReadTool;
import com.mei.zhgy.service.agent.pi.PiAgentRuntime.ToolReply;
import com.mei.zhgy.service.agriculture.CropResolver;
import com.mei.zhgy.service.agriculture.PhenologyService;
import com.mei.zhgy.service.ai.EvidenceReferenceValidator;
import com.mei.zhgy.service.copilot.AgriculturalAgentHarness;
import com.mei.zhgy.service.copilot.AgriculturalEvidenceLedger;
import com.mei.zhgy.service.copilot.AgriculturalToolRegistry;
import com.mei.zhgy.service.copilot.CopilotIntent;
import com.mei.zhgy.service.copilot.CopilotResponseMode;
import com.mei.zhgy.service.copilot.CopilotResponsePolicy;
import com.mei.zhgy.service.copilot.CopilotResolutionStatus;
import com.mei.zhgy.service.copilot.CopilotRouteResult;
import com.mei.zhgy.service.copilot.CopilotStableContext;
import com.mei.zhgy.service.copilot.PlumAgronomyRuleEngine;
import com.mei.zhgy.service.copilot.PlumAgronomyRuleEngine.CertificationStatus;
import com.mei.zhgy.service.copilot.PlumAgronomyRuleEngine.Decision;
import com.mei.zhgy.service.copilot.PlumAgronomyRuleEngine.Finding;
import com.mei.zhgy.service.copilot.PlumAgronomyRuleEngine.LabelInput;
import com.mei.zhgy.service.copilot.PlumAgronomyRuleEngine.OrchardCertification;
import com.mei.zhgy.service.copilot.PlumAgronomyRuleEngine.Provenance;
import com.mei.zhgy.service.copilot.PlumAgronomyRuleEngine.SourceKind;
import com.mei.zhgy.service.copilot.PlumAgronomyRuleEngine.SprayAssessment;
import com.mei.zhgy.service.copilot.PlumAgronomyRuleEngine.SprayAssessmentRequest;
import com.mei.zhgy.service.copilot.PlumAgronomyRuleEngine.SprayWeatherWindow;
import com.mei.zhgy.service.field.FarmField;
import com.mei.zhgy.service.field.FarmFieldService;
import com.mei.zhgy.service.maturity.FarmMaturityService;
import com.mei.zhgy.service.operations.OperationsTaskViewService;
import com.mei.zhgy.service.rag.EvidenceFusionService;
import com.mei.zhgy.service.rag.VisualObservation;
import com.mei.zhgy.service.rag.VisualEvidenceRetriever;
import com.mei.zhgy.service.reference.ReferenceImage;
import com.mei.zhgy.service.reference.ReferenceImageAttacher;
import com.mei.zhgy.service.reference.ReferenceImageLookup;
import com.mei.zhgy.vo.DiagnosisAnalysisResponse;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Repeatable golden evaluation for the plum agricultural Agent.
 *
 * <p>The tool-call section uses a local OpenAI-compatible fixture but the real
 * Pi worker, Java runtime, production tool registry and tool handlers. It is a
 * controlled replay, not a live-model score. All other sections call the
 * production validator or policy class named in their labels. The generated
 * report records denominators and per-case observations under target/.</p>
 */
class PlumAgentGoldenEvaluationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final String FIXTURE_RESOURCE = "/evaluation/plum-agent-golden-v1.json";
    private static final String FIXTURE_PATH = "src/test/resources/evaluation/plum-agent-golden-v1.json";
    private static final Path REPORT_PATH = Path.of("target", "evaluation-results", "plum-agent-golden-eval.json");
    private static final String IMAGE_URL = "https://uploads.example.test/golden/plum-leaf.jpg";
    private static final long ACTOR_ID = 1L;

    @Test
    void fixtureManifestIsLabeledAndCoversEveryMetricWithMultipleCases() throws Exception {
        Fixture fixture = loadFixture();
        FixtureShape shape = inspectFixture(fixture);

        assertAll(
                () -> assertEquals("plum-agent-golden-v1", shape.datasetVersion()),
                () -> assertEquals(3, shape.sectionSizes().get("toolCallScenarios")),
                () -> assertTrue(shape.sectionSizes().get("retrievalCases") >= 2),
                () -> assertTrue(shape.sectionSizes().get("visualCases") >= 2),
                () -> assertTrue(shape.sectionSizes().get("faithfulnessCases") >= 2),
                () -> assertTrue(shape.sectionSizes().get("sprayCases") >= 2),
                () -> assertTrue(shape.sectionSizes().get("missingEvidenceCases") >= 2),
                () -> assertTrue(shape.sectionSizes().get("componentCases") >= 2),
                () -> assertTrue(shape.uniqueCaseIds()),
                () -> assertEquals(3, shape.multihopScenarios()),
                () -> assertTrue(shape.verifiedPhiViolations() >= 2),
                () -> assertEquals(0, fixture.root().path("providerPolicy").path("realProvider").path("denominator").asInt(-1))
        );
    }

    @Test
    void evaluatesControlledRuntimeFusionVisionFaithfulnessSafetyAndUiContracts() throws Exception {
        Fixture fixture = loadFixture();
        FixtureShape shape = inspectFixture(fixture);

        Map<String, Object> toolCalls = evaluateToolCalls(fixture);
        Map<String, Object> retrieval = evaluateRetrieval(fixture);
        Map<String, Object> vision = evaluateVision(fixture);
        Map<String, Object> faithfulness = evaluateFaithfulness(fixture);
        Map<String, Object> spraySafety = evaluateSpraySafety(fixture);
        Map<String, Object> missingEvidence = evaluateMissingEvidence(fixture);
        Map<String, Object> components = evaluateComponents(fixture);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", "plum-agent-golden-eval-report/v1");
        report.put("datasetName", fixture.root().path("datasetName").asText());
        report.put("datasetVersion", shape.datasetVersion());
        report.put("fixturePath", FIXTURE_PATH);
        report.put("fixtureSha256", sha256(fixture.bytes()));
        report.put("generatedAt", Instant.now().toString());
        report.put("providerPolicy", providerPolicy(fixture, toolCalls));
        report.put("datasetCaseCounts", shape.sectionSizes());
        report.put("toolCalls", toolCalls);
        report.put("retrieval", retrieval);
        report.put("vision", vision);
        report.put("faithfulness", faithfulness);
        report.put("spraySafety", spraySafety);
        report.put("missingEvidence", missingEvidence);
        report.put("components", components);
        report.put("limitations", List.of(
                "The tool-call section replays a scripted model; it measures runtime dispatch and normalization, not live model tool-selection capability.",
                "Faithfulness checks cited IDs and annotated factual fields; open-ended textual entailment is outside this measure.",
                "Fixture readiness, PHI checks and component contracts are deterministic contract tests; they are not field accuracy experiments.",
                "The real-provider section has a zero denominator and no result is claimed for it."
        ));

        Files.createDirectories(REPORT_PATH.getParent());
        Files.writeString(REPORT_PATH, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8);
        JsonNode written = MAPPER.readTree(REPORT_PATH.toFile());
        assertEquals("plum-agent-golden-eval-report/v1", written.path("schemaVersion").asText());
        assertEquals(shape.sectionSizes().get("toolCallScenarios").intValue(), written.path("toolCalls").path("scenarios").asInt());

        assertAll(
                () -> assertEquals(1.0, metric(toolCalls, "precision"), 0.000001, "controlled replay precision"),
                () -> assertEquals(1.0, metric(toolCalls, "recall"), 0.000001, "controlled replay recall"),
                () -> assertEquals(1.0, metric(toolCalls, "orderedHopAccuracy"), 0.000001, "multihop order"),
                () -> assertEquals(1.0, metric(toolCalls, "parameterFieldAccuracy"), 0.000001, "actual parameter recovery"),
                () -> assertEquals(1.0, metric(toolCalls, "parameterScenarioRecoveryRate"), 0.000001, "parameter recovery by case"),
                () -> assertEquals(1.0, metric(toolCalls, "scriptedRepairSuccessRate"), 0.000001, "error feedback followed by valid parameters"),
                () -> assertEquals(1.0, metric(toolCalls, "contextCarryRate"), 0.000001, "tool results carried to the next hop"),
                () -> assertTrue(metric(retrieval, "fusedRecallAt5") >= metric(retrieval, "uncalibratedRecallAt5")),
                () -> assertTrue(metric(retrieval, "fusedNdcgAt5") > metric(retrieval, "uncalibratedNdcgAt5")),
                () -> assertEquals(1.0, metric(vision, "typedStructureAccuracy"), 0.000001),
                () -> assertEquals(1.0, metric(vision, "symptomPrecision"), 0.000001),
                () -> assertEquals(1.0, metric(vision, "symptomRecall"), 0.000001),
                () -> assertEquals(1.0, metric(faithfulness, "classificationAccuracy"), 0.000001),
                () -> assertEquals(1.0, metric(faithfulness, "invalidEvidenceRejectionRate"), 0.000001),
                () -> assertEquals(1.0, metric(faithfulness, "claimSupportClassificationAccuracy"), 0.000001),
                () -> assertEquals(1.0, metric(spraySafety, "verifiedPhiViolationBlockRate"), 0.000001),
                () -> assertEquals(0.0, metric(spraySafety, "controlFalseBlockRate"), 0.000001),
                () -> assertEquals(1.0, metric(missingEvidence, "refusalAccuracy"), 0.000001),
                () -> assertEquals(1.0, metric(missingEvidence, "clarificationAccuracy"), 0.000001),
                () -> assertEquals(1.0, metric(missingEvidence, "safeOutcomeRate"), 0.000001),
                () -> assertEquals(1.0, metric(components, "firstPassSchemaAccuracy"), 0.000001),
                () -> assertEquals(1.0, metric(components, "componentMatchRate"), 0.000001),
                () -> assertEquals(1.0, metric(components, "invalidRejectionRate"), 0.000001)
        );
    }

    private static Fixture loadFixture() throws IOException {
        try (InputStream stream = PlumAgentGoldenEvaluationTest.class.getResourceAsStream(FIXTURE_RESOURCE)) {
            assertNotNull(stream, "Missing fixture " + FIXTURE_RESOURCE);
            byte[] bytes = stream.readAllBytes();
            return new Fixture(MAPPER.readTree(bytes), bytes);
        }
    }

    private static FixtureShape inspectFixture(Fixture fixture) {
        JsonNode root = fixture.root();
        List<String> sections = List.of("toolCallScenarios", "retrievalCases", "visualCases", "faithfulnessCases", "claimSupportCases",
                "sprayCases", "missingEvidenceCases", "componentCases");
        Map<String, Integer> sizes = new LinkedHashMap<>();
        Set<String> ids = new LinkedHashSet<>();
        boolean unique = true;
        for (String section : sections) {
            JsonNode cases = root.path(section);
            assertTrue(cases.isArray(), section + " must be an array");
            assertTrue(cases.size() >= 2, section + " must contain at least two labeled cases");
            sizes.put(section, cases.size());
            for (JsonNode item : cases) {
                String id = item.path("id").asText();
                assertFalse(id.isBlank(), section + " contains a case without id");
                unique &= ids.add(id);
            }
        }

        int multihop = 0;
        for (JsonNode scenario : root.path("toolCallScenarios")) {
            JsonNode order = scenario.path("expectedToolOrder");
            JsonNode expected = scenario.path("expectedToolCalls");
            JsonNode rounds = scenario.path("replayRounds");
            assertEquals(order.size(), expected.size(), scenario.path("id").asText() + " order/call count");
            assertEquals(order.size(), rounds.size(), scenario.path("id").asText() + " replay round count");
            assertFalse(scenario.path("contextCarryMarker").asText().isBlank());
            for (int index = 0; index < order.size(); index++) {
                assertEquals(order.get(index).asText(), expected.get(index).path("name").asText());
                assertEquals(1, rounds.get(index).size(), "each multihop round has one scripted call");
                assertEquals(order.get(index).asText(), rounds.get(index).get(0).path("name").asText());
            }
            if (order.size() >= 2) multihop++;
        }

        for (JsonNode retrieval : root.path("retrievalCases")) {
            assertTrue(retrieval.path("items").size() >= 2);
            boolean hasRelevant = false;
            for (JsonNode item : retrieval.path("items")) {
                assertFalse(item.path("id").asText().isBlank());
                assertTrue(item.path("relevance").isIntegralNumber());
                hasRelevant |= item.path("relevance").asInt() > 0;
            }
            assertTrue(hasRelevant, retrieval.path("id").asText() + " has no relevant label");
        }
        for (JsonNode visual : root.path("visualCases")) {
            assertFalse(visual.path("content").isMissingNode());
            assertTrue(visual.path("expectedValid").isBoolean());
        }
        for (JsonNode faithful : root.path("faithfulnessCases")) {
            assertTrue(faithful.path("availableEvidenceIds").isArray());
            assertTrue(faithful.path("claims").isArray());
            assertTrue(faithful.path("expectedValid").isBoolean());
        }
        for (JsonNode component : root.path("componentCases")) {
            assertFalse(component.path("arguments").path("componentType").asText().isBlank());
            assertTrue(component.path("expectedValid").isBoolean());
        }

        int verifiedViolations = 0;
        for (JsonNode spray : root.path("sprayCases")) {
            assertFalse(spray.path("expectedDecision").asText().isBlank());
            if ("VERIFIED_PHI_VIOLATION".equals(spray.path("caseClass").asText())) verifiedViolations++;
        }
        for (JsonNode missing : root.path("missingEvidenceCases")) {
            assertTrue(List.of("EVALUATOR", "POLICY").contains(missing.path("kind").asText()));
            assertFalse(missing.path("expectedOutcome").asText().isBlank());
        }
        assertTrue(root.path("providerPolicy").path("controlledReplay").path("measured").asBoolean(false));
        assertEquals("NOT_RUN", root.path("providerPolicy").path("realProvider").path("status").asText());
        return new FixtureShape(root.path("datasetVersion").asText(), Collections.unmodifiableMap(sizes), unique,
                multihop, verifiedViolations);
    }

    private Map<String, Object> evaluateToolCalls(Fixture fixture) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        int expectedSlots = 0;
        int observedSlots = 0;
        int truePositiveSlots = 0;
        int orderedPasses = 0;
        int contextCarries = 0;
        int parameterFields = 0;
        int parameterFieldsCorrect = 0;
        int parameterScenarios = 0;
        int parameterScenariosCorrect = 0;
        int repairCases = 0;
        int repairSuccesses = 0;

        FixtureProvider provider = new FixtureProvider();
        PiAgentRuntime runtime = new PiAgentRuntime();
        provider.start();
        ReflectionTestUtils.setField(runtime, "apiKey", "fixture-key-not-a-secret");
        ReflectionTestUtils.setField(runtime, "baseUrl", "http://127.0.0.1:" + provider.port() + "/v1/chat/completions");
        ReflectionTestUtils.setField(runtime, "model", "fixture-script");
        ReflectionTestUtils.setField(runtime, "fallbackModel", "fixture-script");
        ReflectionTestUtils.setField(runtime, "worker", "pi-runtime/worker.mjs");
        ReflectionTestUtils.setField(runtime, "node", "node");
        try {
            for (JsonNode scenario : fixture.root().path("toolCallScenarios")) {
                String id = scenario.path("id").asText();
                String question = scenario.path("question").asText();
                List<String> expectedOrder = strings(scenario.path("expectedToolOrder"));
                GoldenTools goldenTools = new GoldenTools(question, imageAttachments(scenario));
                List<ReadTool> wrapped = goldenTools.wrappedTools();
                provider.begin(id, scenario.path("replayRounds"), scenario.path("finalAnswer").asText());

                StringBuilder answer = new StringBuilder();
                PiAgentRuntime.Result result = runtime.run(id.toLowerCase(), 1L, "golden-eval", question,
                        AgriculturalAgentHarness.SYSTEM_PROMPT, null, id + "-turn", null, List.of(), wrapped,
                        answer::append, ignored -> { }, ignored -> { });

                List<String> observedOrder = goldenTools.calls().stream().map(ObservedCall::name).toList();
                List<String> remaining = new ArrayList<>(expectedOrder);
                for (String observed : observedOrder) if (remaining.remove(observed)) truePositiveSlots++;
                expectedSlots += expectedOrder.size();
                observedSlots += observedOrder.size();
                boolean ordered = observedOrder.equals(expectedOrder);
                if (ordered) orderedPasses++;

                List<String> bodies = provider.requests();
                boolean contextCarried = bodies.size() >= 2
                        && bodies.subList(1, bodies.size()).stream().anyMatch(body -> body.contains(scenario.path("contextCarryMarker").asText()));
                if (contextCarried) contextCarries++;

                int caseFields = 0;
                int caseFieldsCorrect = 0;
                List<Map<String, Object>> parameterRows = new ArrayList<>();
                for (JsonNode expected : scenario.path("expectedToolCalls")) {
                    String tool = expected.path("name").asText();
                    Map<String, Object> recovered = recoveredParameters(goldenTools, tool);
                    Iterator<String> names = expected.path("expectedParameters").fieldNames();
                    while (names.hasNext()) {
                        String name = names.next();
                        Object expectedValue = jsonValue(expected.path("expectedParameters").get(name));
                        Object actualValue = recovered == null ? null : recovered.get(name);
                        boolean matched = microwaveEquals(expectedValue, actualValue);
                        caseFields++;
                        if (matched) caseFieldsCorrect++;
                        parameterRows.add(row("tool", tool, "parameter", name,
                                "expected", expectedValue, "actual", actualValue, "matched", matched));
                    }
                }
                parameterScenarios++;
                parameterFields += caseFields;
                parameterFieldsCorrect += caseFieldsCorrect;
                if (caseFields > 0 && caseFields == caseFieldsCorrect) parameterScenariosCorrect++;
                String repairError = scenario.path("repairError").asText();
                boolean repaired = !repairError.isBlank()
                        && bodies.stream().skip(1).anyMatch(body -> body.contains(repairError))
                        && "completed".equals(result.status()) && caseFields > 0 && caseFields == caseFieldsCorrect;
                if (!repairError.isBlank()) {
                    repairCases++;
                    if (repaired) repairSuccesses++;
                }

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("caseId", id);
                row.put("providerMode", "CONTROLLED_REPLAY");
                row.put("expectedToolOrder", expectedOrder);
                row.put("observedToolOrder", observedOrder);
                row.put("orderedHopMatch", ordered);
                row.put("providerTurns", bodies.size());
                row.put("expectedProviderTurns", scenario.path("replayRounds").size() + 1);
                row.put("contextCarried", contextCarried);
                row.put("runtimeStatus", result.status());
                row.put("runtimeText", result.text());
                row.put("parameters", parameterRows);
                row.put("parameterFields", caseFields);
                row.put("parameterFieldsCorrect", caseFieldsCorrect);
                if (!repairError.isBlank()) {
                    row.put("expectedRepairFeedback", repairError);
                    row.put("repairSucceeded", repaired);
                }
                rows.add(row);
            }
        } finally {
            runtime.close();
            provider.close();
        }

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("providerMode", "CONTROLLED_REPLAY");
        metrics.put("modelToolSelectionQuality", "NOT_MEASURED");
        metrics.put("scenarios", rows.size());
        metrics.put("expectedToolCallSlots", expectedSlots);
        metrics.put("observedToolCallSlots", observedSlots);
        metrics.put("truePositiveToolCallSlots", truePositiveSlots);
        metrics.put("precision", round(ratio(truePositiveSlots, observedSlots)));
        metrics.put("recall", round(ratio(truePositiveSlots, expectedSlots)));
        metrics.put("orderedHopAccuracy", round(ratio(orderedPasses, rows.size())));
        metrics.put("contextCarryRate", round(ratio(contextCarries, rows.size())));
        metrics.put("parameterFieldsExpected", parameterFields);
        metrics.put("parameterFieldsCorrect", parameterFieldsCorrect);
        metrics.put("parameterFieldAccuracy", round(ratio(parameterFieldsCorrect, parameterFields)));
        metrics.put("parameterScenarios", parameterScenarios);
        metrics.put("parameterScenarioRecoveryRate", round(ratio(parameterScenariosCorrect, parameterScenarios)));
        metrics.put("scriptedRepairCases", repairCases);
        metrics.put("scriptedRepairsSucceeded", repairSuccesses);
        metrics.put("scriptedRepairSuccessRate", round(ratio(repairSuccesses, repairCases)));
        metrics.put("cases", rows);
        return metrics;
    }

    private Map<String, Object> evaluateRetrieval(Fixture fixture) {
        EvidenceFusionService fusion = new EvidenceFusionService();
        List<Map<String, Object>> rows = new ArrayList<>();
        double uncalibratedRecall1 = 0, uncalibratedRecall3 = 0, uncalibratedRecall5 = 0, uncalibratedNdcg5 = 0;
        double fusedRecall1 = 0, fusedRecall3 = 0, fusedRecall5 = 0, fusedNdcg5 = 0;
        int relevantJudgments = 0;

        for (JsonNode testCase : fixture.root().path("retrievalCases")) {
            List<DiagnosisEvidenceVO> items = new ArrayList<>();
            Map<String, Integer> relevance = new LinkedHashMap<>();
            for (JsonNode item : testCase.path("items")) {
                Map<String, Object> metadata = new LinkedHashMap<>();
                JsonNode rawMetadata = item.path("metadata");
                Iterator<String> names = rawMetadata.fieldNames();
                while (names.hasNext()) {
                    String name = names.next();
                    metadata.put(name, jsonValue(rawMetadata.get(name)));
                }
                items.add(DiagnosisEvidenceVO.builder().id(item.path("id").asText())
                        .modality(item.path("modality").asText()).score(item.path("score").asDouble())
                        .metadata(metadata).build());
                relevance.put(item.path("id").asText(), item.path("relevance").asInt());
                if (item.path("relevance").asInt() > 0) relevantJudgments++;
            }
            List<DiagnosisEvidenceVO> fused = fusion.fuse(new ArrayList<>(items));
            List<String> uncalibratedIds = items.stream()
                    .sorted(java.util.Comparator.comparingDouble(this::uncalibratedWeightedScore).reversed())
                    .map(DiagnosisEvidenceVO::getId).toList();
            List<String> fusedIds = fused.stream().map(DiagnosisEvidenceVO::getId).toList();

            double caseRaw1 = recallAt(uncalibratedIds, relevance, 1);
            double caseRaw3 = recallAt(uncalibratedIds, relevance, 3);
            double caseRaw5 = recallAt(uncalibratedIds, relevance, 5);
            double caseRawNdcg = ndcgAt(uncalibratedIds, relevance, 5);
            double caseFused1 = recallAt(fusedIds, relevance, 1);
            double caseFused3 = recallAt(fusedIds, relevance, 3);
            double caseFused5 = recallAt(fusedIds, relevance, 5);
            double caseFusedNdcg = ndcgAt(fusedIds, relevance, 5);
            uncalibratedRecall1 += caseRaw1;
            uncalibratedRecall3 += caseRaw3;
            uncalibratedRecall5 += caseRaw5;
            uncalibratedNdcg5 += caseRawNdcg;
            fusedRecall1 += caseFused1;
            fusedRecall3 += caseFused3;
            fusedRecall5 += caseFused5;
            fusedNdcg5 += caseFusedNdcg;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("caseId", testCase.path("id").asText());
            row.put("query", testCase.path("query").asText());
            row.put("relevanceLabels", relevance);
            row.put("uncalibratedRankedIds", uncalibratedIds);
            row.put("fusedRankedIds", fusedIds);
            row.put("uncalibratedRecallAt5", round(caseRaw5));
            row.put("fusedRecallAt5", round(caseFused5));
            row.put("uncalibratedNdcgAt5", round(caseRawNdcg));
            row.put("fusedNdcgAt5", round(caseFusedNdcg));
            row.put("calibration", fused.stream().collect(Collectors.toMap(DiagnosisEvidenceVO::getId,
                    item -> item.getMetadata().get("scoreCalibration"), (left, right) -> left, LinkedHashMap::new)));
            rows.add(row);
        }

        int count = rows.size();
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("evaluator", "EvidenceFusionService.fuse");
        metrics.put("k", 5);
        metrics.put("cases", count);
        metrics.put("relevantJudgments", relevantJudgments);
        metrics.put("uncalibratedRecallAt1", round(uncalibratedRecall1 / count));
        metrics.put("uncalibratedRecallAt3", round(uncalibratedRecall3 / count));
        metrics.put("uncalibratedRecallAt5", round(uncalibratedRecall5 / count));
        metrics.put("uncalibratedNdcgAt5", round(uncalibratedNdcg5 / count));
        metrics.put("fusedRecallAt1", round(fusedRecall1 / count));
        metrics.put("fusedRecallAt3", round(fusedRecall3 / count));
        metrics.put("fusedRecallAt5", round(fusedRecall5 / count));
        metrics.put("fusedNdcgAt5", round(fusedNdcg5 / count));
        metrics.put("ndcgGainAt5", round((fusedNdcg5 - uncalibratedNdcg5) / count));
        metrics.put("perCase", rows);
        return metrics;
    }

    private Map<String, Object> evaluateVision(Fixture fixture) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        int validCases = 0;
        int structurePasses = 0;
        int expectedSymptoms = 0;
        int actualTruePositiveSymptoms = 0;
        int actualSymptoms = 0;
        int imageClearExpected = 0;
        int imageClearMatches = 0;

        for (JsonNode testCase : fixture.root().path("visualCases")) {
            String id = testCase.path("id").asText();
            boolean expectedValid = testCase.path("expectedValid").asBoolean();
            VisualObservation parsed = parseVisualObservation(testCase.path("content").asText());
            boolean actualValid = parsed != null;
            if (actualValid == expectedValid) structurePasses++;
            List<String> expected = strings(testCase.path("expectedSymptoms"));
            List<String> observed = parsed == null ? List.of() : parsed.visibleSymptoms();
            if (expectedValid) {
                validCases++;
                expectedSymptoms += expected.size();
                actualSymptoms += observed.size();
                actualTruePositiveSymptoms += (int) observed.stream().filter(expected::contains).count();
                Boolean expectedClear = testCase.path("expectedImageClear").isBoolean() ? testCase.path("expectedImageClear").asBoolean() : null;
                if (expectedClear != null) {
                    imageClearExpected++;
                    if (parsed != null && expectedClear.equals(parsed.imageClear())) imageClearMatches++;
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("caseId", id);
            row.put("expectedValid", expectedValid);
            row.put("actualValid", actualValid);
            row.put("expectedSubject", textOrNull(testCase.path("expectedSubject")));
            row.put("actualSubject", parsed == null ? null : parsed.subject());
            row.put("expectedSymptoms", expected);
            row.put("actualSymptoms", observed);
            row.put("expectedImageClear", testCase.path("expectedImageClear").isNull() ? null : testCase.path("expectedImageClear").asBoolean());
            row.put("actualImageClear", parsed == null ? null : parsed.imageClear());
            rows.add(row);
        }

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("validator", "VisualObservation.parse (actual package-private parser via reflection)");
        metrics.put("cases", rows.size());
        metrics.put("expectedValidCases", validCases);
        metrics.put("typedStructureAccuracy", round(ratio(structurePasses, rows.size())));
        metrics.put("symptomLabelsExpected", expectedSymptoms);
        metrics.put("symptomLabelsObserved", actualSymptoms);
        metrics.put("symptomTruePositives", actualTruePositiveSymptoms);
        metrics.put("symptomPrecision", round(ratio(actualTruePositiveSymptoms, actualSymptoms)));
        metrics.put("symptomRecall", round(ratio(actualTruePositiveSymptoms, expectedSymptoms)));
        metrics.put("imageClearJudgments", imageClearExpected);
        metrics.put("imageClearAgreementRate", round(ratio(imageClearMatches, imageClearExpected)));
        metrics.put("perCase", rows);
        return metrics;
    }

    private double uncalibratedWeightedScore(DiagnosisEvidenceVO item) {
        return item.getScore() * ((Number) item.getMetadata().get("modalityWeight")).doubleValue()
                * ((Number) item.getMetadata().get("sourceReliability")).doubleValue();
    }

    private Map<String, Object> evaluateFaithfulness(Fixture fixture) {
        EvidenceReferenceValidator validator = new EvidenceReferenceValidator();
        List<Map<String, Object>> rows = new ArrayList<>();
        int invalidCases = 0;
        int invalidRejected = 0;
        int validCases = 0;
        int validAccepted = 0;
        int classificationPasses = 0;

        for (JsonNode testCase : fixture.root().path("faithfulnessCases")) {
            boolean expectedValid = testCase.path("expectedValid").asBoolean();
            List<DiagnosisEvidenceVO> available = strings(testCase.path("availableEvidenceIds")).stream()
                    .map(id -> DiagnosisEvidenceVO.builder().id(id).build()).toList();
            DiagnosisAnalysisResponse response = response(testCase);
            String rejection = null;
            try {
                validator.validateOrThrow(response, available);
            } catch (EvidenceReferenceValidator.InvalidEvidenceReferenceException rejected) {
                rejection = rejected.getMessage();
            }
            boolean accepted = rejection == null;
            if (expectedValid == accepted) classificationPasses++;
            if (expectedValid) {
                validCases++;
                if (accepted) validAccepted++;
            } else {
                invalidCases++;
                if (!accepted) invalidRejected++;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("caseId", testCase.path("id").asText());
            row.put("expectedValid", expectedValid);
            row.put("accepted", accepted);
            row.put("rejection", rejection);
            rows.add(row);
        }

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("validator", "EvidenceReferenceValidator.validateOrThrow");
        metrics.put("scope", "Citation ID grounding and annotated claim-to-source facts");
        metrics.put("cases", rows.size());
        metrics.put("expectedValidCases", validCases);
        metrics.put("expectedInvalidCases", invalidCases);
        metrics.put("validAcceptanceRate", round(ratio(validAccepted, validCases)));
        metrics.put("invalidEvidenceRejectionRate", round(ratio(invalidRejected, invalidCases)));
        metrics.put("classificationAccuracy", round(ratio(classificationPasses, rows.size())));
        metrics.put("perCase", rows);
        List<Map<String, Object>> claims = new ArrayList<>();
        int supported = 0;
        int correct = 0;
        for (JsonNode testCase : fixture.root().path("claimSupportCases")) {
            var actual = testCase.path("claimedValue");
            boolean hasSupport = strings(testCase.path("citedEvidenceIds")).stream().anyMatch(ref -> {
                var fact = testCase.path("evidenceById").path(ref).path(testCase.path("field").asText());
                if (fact.isMissingNode() || fact.isNull()) return false;
                return fact.isNumber() && actual.isNumber() ? fact.decimalValue().compareTo(actual.decimalValue()) == 0 : fact.equals(actual);
            });
            boolean expected = testCase.path("expectedSupported").asBoolean();
            if (hasSupport) supported++;
            if (expected == hasSupport) correct++;
            claims.add(row("caseId", testCase.path("id").asText(), "claim", testCase.path("claim").asText(),
                    "expectedSupported", expected, "actualSupported", hasSupport,
                    "citedEvidenceIds", strings(testCase.path("citedEvidenceIds"))));
        }
        metrics.put("claimJudgments", claims.size());
        metrics.put("supportedClaims", supported);
        metrics.put("supportedClaimRate", round(ratio(supported, claims.size())));
        metrics.put("claimSupportClassificationAccuracy", round(ratio(correct, claims.size())));
        metrics.put("claims", claims);
        return metrics;
    }

    private Map<String, Object> evaluateSpraySafety(Fixture fixture) {
        PlumAgronomyRuleEngine engine = new PlumAgronomyRuleEngine();
        List<Map<String, Object>> rows = new ArrayList<>();
        int decisionPasses = 0;
        int violations = 0;
        int violationsBlocked = 0;
        int controls = 0;
        int controlFalseBlocks = 0;
        int missing = 0;
        int missingSafe = 0;

        for (JsonNode testCase : fixture.root().path("sprayCases")) {
            SprayAssessment result = engine.assessSpray(sprayRequest(testCase));
            String expectedDecision = testCase.path("expectedDecision").asText();
            String expectedFinding = textOrNull(testCase.path("expectedFinding"));
            boolean decisionMatched = expectedDecision.equals(result.decision().name());
            boolean findingMatched = expectedFinding == null || result.findings().stream()
                    .map(Finding::code).anyMatch(expectedFinding::equals);
            if (decisionMatched && findingMatched) decisionPasses++;
            String caseClass = testCase.path("caseClass").asText();
            if ("VERIFIED_PHI_VIOLATION".equals(caseClass)) {
                violations++;
                if (result.decision() == Decision.BLOCKED && result.findings().stream()
                        .anyMatch(finding -> "PHI_CONFLICT".equals(finding.code()))) violationsBlocked++;
            } else if ("CONTROL_VERIFIED_SAFE".equals(caseClass)) {
                controls++;
                if (result.decision() == Decision.BLOCKED) controlFalseBlocks++;
            } else if ("MISSING_PHI".equals(caseClass)) {
                missing++;
                if (result.decision() != Decision.ELIGIBLE) missingSafe++;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("caseId", testCase.path("id").asText());
            row.put("caseClass", caseClass);
            row.put("expectedDecision", expectedDecision);
            row.put("actualDecision", result.decision().name());
            row.put("expectedFinding", expectedFinding);
            row.put("actualFindings", result.findings().stream().map(Finding::code).toList());
            row.put("hardRestrictionsPassed", result.hardRestrictionsPassed());
            row.put("daysUntilHarvest", result.daysUntilHarvest());
            row.put("phiDays", result.phiDays());
            row.put("labelProvenanceMarker", result.labelProvenance() == null ? null : result.labelProvenance().marker());
            rows.add(row);
        }

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("engine", "PlumAgronomyRuleEngine.assessSpray");
        metrics.put("cases", rows.size());
        metrics.put("decisionAndFindingAccuracy", round(ratio(decisionPasses, rows.size())));
        metrics.put("verifiedPhiViolations", violations);
        metrics.put("verifiedPhiViolationsBlocked", violationsBlocked);
        metrics.put("verifiedPhiViolationBlockRate", round(ratio(violationsBlocked, violations)));
        metrics.put("safeControls", controls);
        metrics.put("controlFalseBlocks", controlFalseBlocks);
        metrics.put("controlFalseBlockRate", round(ratio(controlFalseBlocks, controls)));
        metrics.put("missingPhiCases", missing);
        metrics.put("missingPhiSafeOutcomes", missingSafe);
        metrics.put("missingPhiSafeOutcomeRate", round(ratio(missingSafe, missing)));
        metrics.put("perCase", rows);
        return metrics;
    }

    private Map<String, Object> evaluateMissingEvidence(Fixture fixture) {
        EvidenceSufficiencyEvaluator evaluator = new EvidenceSufficiencyEvaluator();
        List<Map<String, Object>> rows = new ArrayList<>();
        int evaluatorCases = 0;
        int evaluatorPasses = 0;
        int policyCases = 0;
        int policyPasses = 0;
        int safeOutcomes = 0;

        for (JsonNode testCase : fixture.root().path("missingEvidenceCases")) {
            String kind = testCase.path("kind").asText();
            String expectedOutcome = testCase.path("expectedOutcome").asText();
            boolean safe;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("caseId", testCase.path("id").asText());
            row.put("kind", kind);
            row.put("expectedOutcome", expectedOutcome);
            if ("EVALUATOR".equals(kind)) {
                evaluatorCases++;
                List<DiagnosisEvidenceVO> trusted = strings(testCase.path("trustedEvidenceIds")).stream()
                        .map(id -> DiagnosisEvidenceVO.builder().id(id).build()).toList();
                EvidenceSufficiencyDecision decision = evaluator.evaluate(testCase.path("writeAction").asBoolean(true), trusted,
                        textOrNull(testCase.path("recommendationId")), strings(testCase.path("recommendationEvidenceIds")),
                        strings(testCase.path("requestedEvidenceIds")), strings(testCase.path("unavailableDependencies")),
                        testCase.path("emptyObserved").asBoolean(false));
                boolean statusMatch = testCase.path("expectedStatus").asText().equals(decision.getStatus().name());
                boolean writeMatch = testCase.path("expectedAllowsWrite").asBoolean() == decision.isAllowsWrite();
                boolean reasonMatch = decision.getReasonCodes().contains(testCase.path("expectedReasonCode").asText());
                safe = statusMatch && writeMatch && reasonMatch && !decision.isAllowsWrite();
                if (safe) evaluatorPasses++;
                row.put("actualStatus", decision.getStatus().name());
                row.put("allowsWrite", decision.isAllowsWrite());
                row.put("reasonCodes", decision.getReasonCodes());
                row.put("safeOutcome", safe);
            } else {
                policyCases++;
                CopilotRouteResult route = CopilotRouteResult.builder()
                        .intent(CopilotIntent.valueOf(testCase.path("intent").asText()))
                        .resolutionStatus(CopilotResolutionStatus.valueOf(testCase.path("resolutionStatus").asText()))
                        .requiresClarification(testCase.path("requiresClarification").asBoolean(false))
                        .traceId("golden-" + testCase.path("id").asText())
                        .build();
                CopilotResponseMode mode = CopilotResponsePolicy.choose(route, testCase.path("message").asText(),
                        testCase.path("hasImages").asBoolean(false), testCase.path("fieldFactsReady").asBoolean(false));
                safe = expectedOutcome.equals(mode.name());
                if (safe) policyPasses++;
                row.put("actualMode", mode.name());
                row.put("safeOutcome", safe);
            }
            if (safe) safeOutcomes++;
            rows.add(row);
        }

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("evaluatorGate", "EvidenceSufficiencyEvaluator.evaluate");
        metrics.put("clarificationPolicy", "CopilotResponsePolicy.choose");
        metrics.put("cases", rows.size());
        metrics.put("refusalCases", evaluatorCases);
        metrics.put("refusalPasses", evaluatorPasses);
        metrics.put("refusalAccuracy", round(ratio(evaluatorPasses, evaluatorCases)));
        metrics.put("clarificationCases", policyCases);
        metrics.put("clarificationPasses", policyPasses);
        metrics.put("clarificationAccuracy", round(ratio(policyPasses, policyCases)));
        metrics.put("safeOutcomes", safeOutcomes);
        metrics.put("safeOutcomeRate", round(ratio(safeOutcomes, rows.size())));
        metrics.put("perCase", rows);
        return metrics;
    }

    private Map<String, Object> evaluateComponents(Fixture fixture) {
        AgriculturalEvidenceLedger ledger = new AgriculturalEvidenceLedger();
        ledger.add("fixture-task-list", "农事任务", "fixture-tasks-visible", "Operations",
                "query_operations_tasks", 42, "east-1");
        AgriculturalToolRegistry registry = new AgriculturalToolRegistry(null, null, null, null, null, null, null, null);
        ReadTool render = registry.tools(new CopilotStableContext(), ACTOR_ID, "plum", "组件契约评估", List.of(), true, ledger)
                .stream().filter(tool -> "render_ui_block".equals(tool.name())).findFirst().orElseThrow();

        List<Map<String, Object>> rows = new ArrayList<>();
        int validCases = 0;
        int validAccepted = 0;
        int componentMatches = 0;
        int invalidCases = 0;
        int invalidRejected = 0;
        int schemaPasses = 0;

        for (JsonNode testCase : fixture.root().path("componentCases")) {
            boolean expectedValid = testCase.path("expectedValid").asBoolean();
            ToolReply reply = render.execute().apply(testCase.path("arguments"));
            boolean accepted = !reply.isError();
            String actualComponent = reply.uiBlock() == null ? null : String.valueOf(reply.uiBlock().get("componentType"));
            String expectedComponent = textOrNull(testCase.path("expectedComponent"));
            boolean componentMatch = expectedValid && accepted && expectedComponent != null && expectedComponent.equals(actualComponent);
            if (expectedValid == accepted) schemaPasses++;
            if (expectedValid) {
                validCases++;
                if (accepted) validAccepted++;
                if (componentMatch) componentMatches++;
            } else {
                invalidCases++;
                if (!accepted) invalidRejected++;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("caseId", testCase.path("id").asText());
            row.put("expectedValid", expectedValid);
            row.put("firstPassAccepted", accepted);
            row.put("expectedComponent", expectedComponent);
            row.put("actualComponent", actualComponent);
            row.put("componentMatch", componentMatch);
            row.put("replyData", reply.data());
            rows.add(row);
        }

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("seam", "AgriculturalToolRegistry.tools(...).render_ui_block -> CopilotToolArguments + CopilotUiBlockValidator");
        metrics.put("cases", rows.size());
        metrics.put("expectedValidCases", validCases);
        metrics.put("expectedInvalidCases", invalidCases);
        metrics.put("firstPassAcceptedValid", validAccepted);
        metrics.put("firstPassSchemaAccuracy", round(ratio(schemaPasses, rows.size())));
        metrics.put("componentMatches", componentMatches);
        metrics.put("componentMatchRate", round(ratio(componentMatches, validCases)));
        metrics.put("invalidRejected", invalidRejected);
        metrics.put("invalidRejectionRate", round(ratio(invalidRejected, invalidCases)));
        metrics.put("perCase", rows);
        return metrics;
    }

    private static Map<String, Object> providerPolicy(Fixture fixture, Map<String, Object> toolCalls) {
        Map<String, Object> controlled = new LinkedHashMap<>();
        controlled.put("status", "RUN");
        controlled.put("provider", "in-process OpenAI-compatible HTTP fixture");
        controlled.put("model", fixture.root().path("providerPolicy").path("controlledReplay").path("model").asText());
        controlled.put("cases", toolCalls.get("scenarios"));
        controlled.put("denominator", toolCalls.get("scenarios"));
        controlled.put("interpretation", "Runtime dispatch and schema/parameter normalization only; model tool choice is scripted.");
        Map<String, Object> real = new LinkedHashMap<>();
        JsonNode source = fixture.root().path("providerPolicy").path("realProvider");
        real.put("status", source.path("status").asText());
        real.put("denominator", source.path("denominator").asInt(0));
        real.put("reason", source.path("reason").asText());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("controlledReplay", controlled);
        result.put("realProvider", real);
        return result;
    }

    private static List<String> imageAttachments(JsonNode scenario) {
        List<String> attachments = new ArrayList<>();
        for (JsonNode round : scenario.path("replayRounds")) {
            for (JsonNode call : round) {
                if ("analyze_uploaded_images".equals(call.path("name").asText())) {
                    attachments.addAll(strings(call.path("arguments").path("imageUrls")));
                }
            }
        }
        return List.copyOf(attachments);
    }

    private static Map<String, Object> recoveredParameters(GoldenTools tools, String tool) {
        Map<String, Object> recovered = tools.recovered(tool);
        if (recovered != null) return recovered;
        ObservedCall call = tools.calls().stream().filter(item -> tool.equals(item.name()))
                .reduce((first, second) -> second).orElse(null);
        if (call == null || call.arguments() == null || !call.arguments().isObject()) return null;
        Map<String, Object> raw = new LinkedHashMap<>();
        Iterator<String> names = call.arguments().fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            raw.put(name, jsonValue(call.arguments().get(name)));
        }
        return raw;
    }

    private static DiagnosisAnalysisResponse response(JsonNode testCase) {
        List<DiagnosisAnalysisResponse.Claim> claims = new ArrayList<>();
        for (JsonNode claim : testCase.path("claims")) {
            claims.add(DiagnosisAnalysisResponse.Claim.builder().id(claim.path("id").asText())
                    .text("labeled claim").evidenceIds(strings(claim.path("evidenceIds"))).build());
        }
        List<DiagnosisAnalysisResponse.Recommendation> recommendations = new ArrayList<>();
        for (JsonNode recommendation : testCase.path("recommendations")) {
            recommendations.add(DiagnosisAnalysisResponse.Recommendation.builder()
                    .id(recommendation.path("id").asText()).title("labeled recommendation")
                    .priority("medium").detail("labeled detail")
                    .evidenceIds(strings(recommendation.path("evidenceIds"))).build());
        }
        return DiagnosisAnalysisResponse.builder().claims(claims).recommendations(recommendations).build();
    }

    private static SprayAssessmentRequest sprayRequest(JsonNode testCase) {
        JsonNode labelNode = testCase.path("label");
        LabelInput label = new LabelInput(
                textOrNull(labelNode.path("productId")), textOrNull(labelNode.path("productName")),
                textOrNull(labelNode.path("activeIngredient")),
                labelNode.path("phiDays").isNull() ? null : labelNode.path("phiDays").asInt(),
                labelNode.path("syntheticChemical").isNull() ? null : labelNode.path("syntheticChemical").asBoolean(),
                labelNode.path("prohibitedByLabel").asBoolean(false), provenance(labelNode.path("provenance")));
        JsonNode certificationNode = testCase.path("certification");
        OrchardCertification certification = new OrchardCertification(
                CertificationStatus.valueOf(certificationNode.path("status").asText()),
                provenance(certificationNode.path("provenance")),
                stringSet(certificationNode.path("explicitlyAllowedProductIds")),
                stringSet(certificationNode.path("explicitlyAllowedActiveIngredients")),
                stringSet(certificationNode.path("prohibitedProductIds")),
                stringSet(certificationNode.path("prohibitedActiveIngredients")));
        JsonNode weather = testCase.path("weatherWindow");
        SprayWeatherWindow window = new SprayWeatherWindow(weather.path("id").asText(),
                LocalDateTime.parse(weather.path("startsAt").asText()), LocalDateTime.parse(weather.path("endsAt").asText()),
                weather.path("airTemperatureC").asDouble(), weather.path("relativeHumidityPct").asDouble(),
                weather.path("windSpeedKph").isNull() ? null : weather.path("windSpeedKph").asDouble(),
                weather.path("precipitationMm").asDouble(), weather.path("precipitationProbabilityPct").asDouble(),
                provenance(weather.path("provenance")));
        return new SprayAssessmentRequest(LocalDate.parse(testCase.path("plannedSprayDate").asText()),
                LocalDate.parse(testCase.path("expectedHarvestDate").asText()),
                testCase.path("phenologyStage").asText(), label, certification, List.of(window));
    }

    private static Provenance provenance(JsonNode node) {
        return new Provenance(SourceKind.valueOf(node.path("sourceKind").asText("UNKNOWN")),
                textOrNull(node.path("sourceReference")), textOrNull(node.path("documentVersion")),
                Instant.parse(node.path("retrievedAt").asText("2026-06-01T00:00:00Z")),
                node.path("publicSource").asBoolean(false), node.path("verified").asBoolean(false));
    }

    private static Set<String> stringSet(JsonNode node) {
        if (!node.isArray() || node.isEmpty()) return Set.of();
        return new LinkedHashSet<>(strings(node));
    }

    private static VisualObservation parseVisualObservation(String content) throws Exception {
        Method parse = VisualObservation.class.getDeclaredMethod("parse", String.class);
        parse.setAccessible(true);
        try {
            return (VisualObservation) parse.invoke(null, content);
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw wrapped;
        }
    }

    private static double recallAt(List<String> ranked, Map<String, Integer> relevance, int k) {
        Set<String> relevant = relevance.entrySet().stream().filter(entry -> entry.getValue() > 0)
                .map(Map.Entry::getKey).collect(Collectors.toCollection(LinkedHashSet::new));
        if (relevant.isEmpty()) return 1.0;
        long hits = ranked.stream().limit(k).filter(relevant::contains).count();
        return (double) hits / relevant.size();
    }

    private static double ndcgAt(List<String> ranked, Map<String, Integer> relevance, int k) {
        double dcg = 0.0;
        for (int index = 0; index < Math.min(k, ranked.size()); index++) {
            int grade = relevance.getOrDefault(ranked.get(index), 0);
            dcg += (Math.pow(2, grade) - 1) / (Math.log(index + 2) / Math.log(2));
        }
        List<Integer> idealGrades = relevance.values().stream().sorted(Collections.reverseOrder()).toList();
        double idcg = 0.0;
        for (int index = 0; index < Math.min(k, idealGrades.size()); index++) {
            idcg += (Math.pow(2, idealGrades.get(index)) - 1) / (Math.log(index + 2) / Math.log(2));
        }
        return idcg == 0.0 ? 1.0 : dcg / idcg;
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) if (item.isTextual()) values.add(item.asText());
        return List.copyOf(values);
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static Object jsonValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (node.isTextual()) return node.asText();
        if (node.isBoolean()) return node.asBoolean();
        if (node.isIntegralNumber()) return node.canConvertToInt() ? node.asInt() : node.asLong();
        if (node.isFloatingPointNumber()) return node.asDouble();
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            for (JsonNode item : node) values.add(jsonValue(item));
            return values;
        }
        if (node.isObject()) {
            Map<String, Object> values = new LinkedHashMap<>();
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                values.put(name, jsonValue(node.get(name)));
            }
            return values;
        }
        return node.asText();
    }

    private static boolean microwaveEquals(Object expected, Object actual) {
        if (expected instanceof Number left && actual instanceof Number right) {
            return Math.abs(left.doubleValue() - right.doubleValue()) < 0.000001;
        }
        if (expected instanceof Collection<?> left && actual instanceof Collection<?> right) {
            if (left.size() != right.size()) return false;
            Iterator<?> leftIterator = left.iterator();
            Iterator<?> rightIterator = right.iterator();
            while (leftIterator.hasNext()) {
                if (!microwaveEquals(leftIterator.next(), rightIterator.next())) return false;
            }
            return true;
        }
        return Objects.equals(expected, actual);
    }

    private static Map<String, Object> row(Object... entries) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) row.put(String.valueOf(entries[index]), entries[index + 1]);
        return row;
    }

    private static double metric(Map<String, Object> source, String key) {
        Object value = source.get(key);
        assertTrue(value instanceof Number, "missing numeric metric " + key);
        return ((Number) value).doubleValue();
    }

    private static double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    private static double round(double value) {
        return Math.round(value * 1_000_000D) / 1_000_000D;
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder output = new StringBuilder();
            for (byte value : digest) output.append(String.format("%02x", value));
            return output.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private record Fixture(JsonNode root, byte[] bytes) { }

    private record FixtureShape(String datasetVersion, Map<String, Integer> sectionSizes, boolean uniqueCaseIds,
                                int multihopScenarios, int verifiedPhiViolations) { }

    private record ObservedCall(String name, JsonNode arguments) { }

    private static final class GoldenTools {
        private final List<ObservedCall> calls = new CopyOnWriteArrayList<>();
        private final Map<String, Map<String, Object>> recovered = new ConcurrentHashMap<>();
        private final List<ReadTool> tools;

        GoldenTools(String question, List<String> attachments) {
            FarmFieldService fields = mock(FarmFieldService.class);
            OperationsTaskViewService tasks = mock(OperationsTaskViewService.class);
            ReferenceImageAttacher atlas = mock(ReferenceImageAttacher.class);
            VisualEvidenceRetriever visual = mock(VisualEvidenceRetriever.class);
            FarmMaturityService maturity = mock(FarmMaturityService.class);

            FarmField field = FarmField.builder().fieldId("east-1").farmId(42).name("东坡一号地")
                    .crop("plum").build();
            when(fields.get("east-1", ACTOR_ID)).thenReturn(field);
            when(fields.applyToDiagnosisRequest(any(DiagnosisAnalyzeRequest.class), eq(ACTOR_ID))).thenReturn(null);

            when(tasks.view(any(), any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
                Map<String, Object> captured = new LinkedHashMap<>();
                captured.put("farmId", invocation.getArgument(0));
                captured.put("fieldId", invocation.getArgument(1));
                captured.put("from", invocation.getArgument(2));
                captured.put("to", invocation.getArgument(3));
                captured.put("view", invocation.getArgument(4));
                captured.put("status", invocation.getArgument(5));
                recovered.put("query_operations_tasks", captured);
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("items", List.of(Map.of("taskId", "task-1", "title", "fixture-tasks-visible",
                        "fieldName", "东坡一号地", "status", "pending", "scheduledFor", "2026-11-28")));
                response.put("counts", Map.of("total", 1));
                response.put("truncated", false);
                return response;
            });
            when(atlas.forChatResult(anyString(), anyString(), nullable(String.class))).thenAnswer(invocation -> {
                Map<String, Object> captured = new LinkedHashMap<>();
                captured.put("query", invocation.getArgument(0));
                captured.put("diseaseOrPest", invocation.getArgument(0));
                captured.put("crop", invocation.getArgument(1));
                captured.put("growthStage", invocation.getArgument(2));
                recovered.put("lookup_reference_atlas", captured);
                return ReferenceImageLookup.ready(List.of(new ReferenceImage("atlas-rot", "disease", null,
                        "brown_rot", "脆李褐腐病", "https://assets.example.test/atlas/plum.jpg", null, null)), 1, 1, "brown_rot");
            });
            when(visual.retrieve(any(DiagnosisAnalyzeRequest.class))).thenAnswer(invocation -> {
                DiagnosisAnalyzeRequest request = invocation.getArgument(0);
                Map<String, Object> captured = new LinkedHashMap<>();
                captured.put("farmId", request.getFarmId());
                captured.put("fieldId", request.getFieldId());
                captured.put("query", request.getQuery());
                captured.put("imageUrls", request.getImageUrls());
                recovered.put("analyze_uploaded_images", captured);
                VisualObservation observation = new VisualObservation("脆李果实", List.of("果面褐色水渍斑"),
                        true, null, List.of("叶片背面"), "仅覆盖果实近景");
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("visualObservation", observation);
                metadata.put("retrievalType", "REAL_QWEN_MODEL_GATEWAY");
                return List.of(DiagnosisEvidenceVO.builder().id("camera_vision_1").modality("camera")
                        .title("视觉证据").summary("果面褐色水渍斑").score(0.90).metadata(metadata).build());
            });
            when(maturity.analyze(anyInt(), eq(ACTOR_ID), anyList(), any(), anyList(), anyString(), anyString(), any()))
                    .thenAnswer(invocation -> {
                        Map<String, Object> captured = new LinkedHashMap<>();
                        captured.put("farmId", invocation.getArgument(0));
                        captured.put("fieldId", invocation.getArgument(3));
                        captured.put("kind", invocation.getArgument(5));
                        captured.put("mode", invocation.getArgument(6));
                        recovered.put("inspect_camera_maturity", captured);
                        return Map.of("status", "COMPLETED", "runId", "maturity-run-golden",
                                "summary", "golden fixture inspection");
                    });

            AgriculturalEvidenceLedger ledger = new AgriculturalEvidenceLedger();
            ledger.add("fixture-task-list", "农事任务", "fixture-tasks-visible", "Operations",
                    "query_operations_tasks", 42, "east-1");
            CropResolver crops = new CropResolver();
            AgriculturalToolRegistry registry = new AgriculturalToolRegistry(fields, tasks, atlas, null, visual,
                    maturity, crops, new PhenologyService(crops));
            this.tools = registry.tools(CopilotStableContext.builder().farmId("42").fieldId("east-1").build(),
                    ACTOR_ID, "plum", question, attachments, true, ledger);
        }

        List<ReadTool> wrappedTools() {
            List<ReadTool> wrapped = new ArrayList<>();
            for (ReadTool tool : tools) {
                ReadTool source = tool;
                ReadTool wrappedTool;
                if (tool.progressExecutor() != null) {
                    wrappedTool = new ReadTool(tool.name(), tool.description(), tool.parameters(),
                            arguments -> {
                                calls.add(new ObservedCall(source.name(), arguments.deepCopy()));
                                return source.execute().apply(arguments);
                            }, tool.multimodal(),
                            (arguments, progress) -> {
                                calls.add(new ObservedCall(source.name(), arguments.deepCopy()));
                                return source.progressExecutor().execute(arguments, progress);
                            });
                } else {
                    wrappedTool = new ReadTool(tool.name(), tool.description(), tool.parameters(),
                            arguments -> {
                                calls.add(new ObservedCall(source.name(), arguments.deepCopy()));
                                return source.execute().apply(arguments);
                            }, tool.multimodal());
                }
                wrapped.add(wrappedTool);
            }
            return wrapped;
        }

        List<ObservedCall> calls() { return List.copyOf(calls); }
        Map<String, Object> recovered(String tool) { return recovered.get(tool); }
    }

    private static final class FixtureProvider implements AutoCloseable {
        private final ObjectMapper mapper = new ObjectMapper();
        private final List<String> requests = new CopyOnWriteArrayList<>();
        private final AtomicInteger requestIndex = new AtomicInteger();
        private volatile JsonNode rounds = mapper.createArrayNode();
        private volatile String scenarioId = "fixture";
        private volatile String finalAnswer = "完成。";
        private HttpServer server;

        void start() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", this::handle);
            server.start();
        }

        int port() { return server.getAddress().getPort(); }

        synchronized void begin(String id, JsonNode replayRounds, String answer) {
            requests.clear();
            requestIndex.set(0);
            scenarioId = id;
            rounds = replayRounds;
            finalAnswer = answer;
        }

        synchronized List<String> requests() { return List.copyOf(requests); }

        private void handle(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(body);
            int index = requestIndex.getAndIncrement();
            if (index < rounds.size()) {
                JsonNode calls = rounds.get(index);
                List<Map<String, Object>> toolCalls = new ArrayList<>();
                for (int callIndex = 0; callIndex < calls.size(); callIndex++) {
                    JsonNode call = calls.get(callIndex);
                    String callId = "call-" + scenarioId + "-" + index + "-" + callIndex;
                    String arguments = mapper.writeValueAsString(call.path("arguments"));
                    toolCalls.add(Map.of("index", callIndex, "id", callId, "type", "function",
                            "function", Map.of("name", call.path("name").asText(), "arguments", arguments)));
                }
                sendCompletion(exchange, mapper.writeValueAsString(Map.of("tool_calls", toolCalls)), "tool_calls");
            } else {
                sendCompletion(exchange, mapper.writeValueAsString(Map.of("content", finalAnswer)), "stop");
            }
        }

        private void sendCompletion(HttpExchange exchange, String delta, String finish) throws IOException {
            String body = "data: {\"id\":\"fixture\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"fixture-script\",\"choices\":[{\"index\":0,\"delta\":" + delta + ",\"finish_reason\":null}]}\n\n"
                    + "data: {\"id\":\"fixture\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"fixture-script\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"" + finish + "\"}]}\n\ndata: [DONE]\n\n";
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var stream = exchange.getResponseBody()) {
                stream.write(bytes);
            }
        }

        @Override
        public void close() {
            if (server != null) server.stop(0);
        }
    }
}
