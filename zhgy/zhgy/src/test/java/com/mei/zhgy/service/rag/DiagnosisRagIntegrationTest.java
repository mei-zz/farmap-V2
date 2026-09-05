package com.mei.zhgy.service.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.properties.BailianProperties;
import com.mei.zhgy.service.ai.BailianModelClient;
import com.mei.zhgy.service.ai.EvidenceReferenceValidator;
import com.mei.zhgy.service.ai.GroundedDiagnosisGenerator;
import com.mei.zhgy.service.ai.ModelAvailabilityRegistry;
import com.mei.zhgy.service.ai.ModelCapabilityRegistry;
import com.mei.zhgy.service.ai.ModelEscalationPolicy;
import com.mei.zhgy.service.ai.ModelGateway;
import com.mei.zhgy.service.ai.ModelRouter;
import com.mei.zhgy.service.ai.ModelRoutingPolicy;
import com.mei.zhgy.service.ai.ModelUsageRecord;
import com.mei.zhgy.service.ai.ModelUsageRecorder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Manual opt-in test; never spends provider quota during the normal unit-test run. */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "FARMAP_RUN_REAL_INTEGRATION", matches = "true")
class DiagnosisRagIntegrationTest {
    @Test
    void runsRealA12MultimodalRagAndValidatesClaimReferences() throws Exception {
        String apiKey = firstEnvironmentValue("BAILIAN_API_KEY", "DASHSCOPE_API_KEY", "APIKEY");
        assertFalse(apiKey.isBlank(), "Bailian API key is not available in the opt-in process");

        BailianProperties properties = new BailianProperties();
        properties.getApi().setKey(apiKey);
        properties.getApi().setUrl(firstEnvironmentValue(
                "BAILIAN_API_BASE_URL", "BAILIAN_BASE_URL", "DASHSCOPE_BASE_URL",
                "OPENAI_COMPATIBLE_BASE_URL", "OPENAI_BASE_URL", "BASEURL"));
        properties.getApi().setMaxRetries(0);
        ModelCapabilityRegistry capabilities = new ModelCapabilityRegistry();
        ModelAvailabilityRegistry availability = new ModelAvailabilityRegistry(capabilities);
        BailianModelClient client = new BailianModelClient(new ObjectMapper(), properties);
        List<ModelUsageRecord> usageRecords = new ArrayList<>();
        ModelUsageRecorder usageRecorder = new ModelUsageRecorder() {
            @Override
            public void record(ModelUsageRecord record) {
                usageRecords.add(record);
            }
        };
        ModelGateway gateway = new ModelGateway(
                new ModelRouter(new ModelRoutingPolicy(), new ModelEscalationPolicy(), capabilities),
                capabilities,
                availability,
                client,
                usageRecorder);
        GroundedDiagnosisGenerator generator = new GroundedDiagnosisGenerator(
                gateway,
                new EvidenceReferenceValidator(),
                new ModelEscalationPolicy(),
                new ObjectMapper());

        KnowledgeEvidenceRetriever knowledge = new KnowledgeEvidenceRetriever();
        org.springframework.test.util.ReflectionTestUtils.setField(knowledge, "resourcePath", "knowledge/orchard_management.txt");
        knowledge.loadKnowledge();
        List<DiagnosisRetriever> retrievers = Arrays.asList(
                new VisualEvidenceRetriever(),
                new WeatherEvidenceRetriever(),
                knowledge);

        DiagnosisRagService service = new DiagnosisRagService(
                retrievers,
                new EvidenceFusionService(),
                generator);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "generationMode", "qwen");

        Path image = Paths.get("..", "..", "farmap-frontend-dev", "src", "assets", "demo", "diagnosis-camera-chlorosis.png")
                .toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(image), "A-12 Demo camera image is not available");
        String dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(image));

        DiagnosisAnalyzeRequest request = new DiagnosisAnalyzeRequest(
                "A-12",
                null,
                "柑橘",
                "果实膨大期",
                new ArrayList<>(List.of(dataUri)),
                new DiagnosisAnalyzeRequest.WeatherContext(58D, "14 天累计降雨 58 mm", "26°C", "2026-09-05T12:00:00+08:00"),
                "叶片黄化的原因与处置建议",
                5,
                new ArrayList<>(List.of("camera", "weather", "knowledge")),
                "default",
                new LinkedHashMap<>());

        var response = service.analyze(request, null);
        assertNotNull(response.getDiagnosis());
        assertNotNull(response.getDiagnosis().getConfidence());
        assertNotNull(response.getClaims());
        assertFalse(response.getClaims().isEmpty());
        assertNotNull(response.getRecommendations());
        assertFalse(response.getRecommendations().isEmpty());
        assertNotNull(response.getMetadata());
        assertTrue(response.getMetadata().containsKey("selectedModel"),
                "Real model result was not retained; metadata=" + response.getMetadata() + ", usageRecords=" + usageRecords);
        assertTrue(response.getEvidence().stream().anyMatch(item -> "camera".equals(item.getModality())));
        assertTrue(response.getEvidence().stream().anyMatch(item -> "weather".equals(item.getModality())));
        assertTrue(response.getEvidence().stream().anyMatch(item -> "knowledge".equals(item.getModality())));

        java.util.Set<String> evidenceIds = new java.util.HashSet<>();
        response.getEvidence().forEach(item -> evidenceIds.add(item.getId()));
        response.getClaims().forEach(claim -> claim.getEvidenceIds().forEach(id -> assertTrue(evidenceIds.contains(id), "Unknown claim evidence id: " + id)));
        response.getRecommendations().forEach(item -> item.getEvidenceIds().forEach(id -> assertTrue(evidenceIds.contains(id), "Unknown recommendation evidence id: " + id)));

        Object selectedModel = response.getMetadata().get("selectedModel");
        Object totalLatency = response.getMetadata().get("totalLatencyMs");
        Object inputTokens = response.getMetadata().get("inputTokens");
        Object outputTokens = response.getMetadata().get("outputTokens");
        Object thinkingTokens = response.getMetadata().get("thinkingTokens");
        System.out.println("REAL_A12_RAG_OK model=" + selectedModel
                + " evidence=" + response.getEvidence().size()
                + " claims=" + response.getClaims().size()
                + " recommendations=" + response.getRecommendations().size()
                + " totalLatencyMs=" + totalLatency
                + " inputTokens=" + inputTokens
                + " outputTokens=" + outputTokens
                + " thinkingTokens=" + thinkingTokens);
    }

    private String firstEnvironmentValue(String... names) {
        for (String name : names) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }
}
