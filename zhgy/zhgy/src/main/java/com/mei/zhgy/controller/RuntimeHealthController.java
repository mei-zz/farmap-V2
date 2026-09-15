package com.mei.zhgy.controller;

import com.mei.zhgy.result.Result;
import com.mei.zhgy.service.ai.LocalAiClient;
import com.mei.zhgy.service.ai.IntelligenceGateway;
import com.mei.zhgy.service.agent.AgentRuntime;
import com.mei.zhgy.service.operations.OperationsTaskService;
import com.mei.zhgy.service.historical.HistoricalVectorStoreInitializer;
import com.mei.zhgy.service.knowledge.KnowledgeBackendService;
import com.mei.zhgy.service.knowledge.KnowledgeVectorStore;
import com.mei.zhgy.service.knowledge.QwenKnowledgeVectorStore;
import com.mei.zhgy.service.expert.ExpertReviewV2Service;
import com.mei.zhgy.service.historical.HistoricalCaseCandidateService;
import com.mei.zhgy.service.rag.UploadedImageAssetIndexService;
import com.mei.zhgy.service.migration.MigrationReadinessVerifier;
import com.mei.zhgy.service.ai.BailianModelClient;
import com.mei.zhgy.service.ai.ModelAvailabilityRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/runtime")
public class RuntimeHealthController {
    private final LocalAiClient localAi;
    private final IntelligenceGateway intelligenceGateway;
    private final AgentRuntime agentRuntime;
    private final OperationsTaskService operations;
    private final HistoricalVectorStoreInitializer historicalVectorStore;
    private final KnowledgeBackendService knowledgeBackend;
    private final KnowledgeVectorStore knowledgeVectorStore;
    private final QwenKnowledgeVectorStore qwenKnowledgeVectorStore;
    private final MigrationReadinessVerifier migrationReadiness;
    private final ExpertReviewV2Service expertReviews;
    private final HistoricalCaseCandidateService candidates;
    private final BailianModelClient bailianModelClient;
    private final ModelAvailabilityRegistry modelAvailability;
    private UploadedImageAssetIndexService uploadedImageAssets;
    @Value("${milvus.host:127.0.0.1}") private String milvusHost;
    @Value("${milvus.port:19530}") private int milvusPort;
    @Value("${farmap.ai.router.default-model:qwen-plus-2025-07-28}") private String defaultModel;
    @Value("${farmap.ai.router.multimodal-model:qwen3-vl-32b-thinking}") private String multimodalModel;

    public RuntimeHealthController(LocalAiClient localAi, AgentRuntime agentRuntime, OperationsTaskService operations, HistoricalVectorStoreInitializer historicalVectorStore) {
        this.localAi = localAi; this.intelligenceGateway = null; this.agentRuntime = agentRuntime; this.operations = operations;
        this.historicalVectorStore = historicalVectorStore; this.knowledgeBackend = null; this.knowledgeVectorStore = null;
        this.qwenKnowledgeVectorStore = null; this.migrationReadiness = null; this.expertReviews = null;
        this.candidates = null; this.bailianModelClient = null; this.modelAvailability = null;
    }
    @Autowired
    public RuntimeHealthController(LocalAiClient localAi, IntelligenceGateway intelligenceGateway, AgentRuntime agentRuntime, OperationsTaskService operations, HistoricalVectorStoreInitializer historicalVectorStore, KnowledgeBackendService knowledgeBackend, MigrationReadinessVerifier migrationReadiness, ExpertReviewV2Service expertReviews, HistoricalCaseCandidateService candidates, KnowledgeVectorStore knowledgeVectorStore, @Autowired(required = false) QwenKnowledgeVectorStore qwenKnowledgeVectorStore, BailianModelClient bailianModelClient, ModelAvailabilityRegistry modelAvailability) {
        this.localAi = localAi; this.intelligenceGateway = intelligenceGateway; this.agentRuntime = agentRuntime; this.operations = operations; this.historicalVectorStore = historicalVectorStore; this.knowledgeBackend = knowledgeBackend; this.knowledgeVectorStore = knowledgeVectorStore; this.qwenKnowledgeVectorStore = qwenKnowledgeVectorStore; this.migrationReadiness = migrationReadiness; this.expertReviews = expertReviews; this.candidates = candidates; this.bailianModelClient = bailianModelClient; this.modelAvailability = modelAvailability;
    }

    @Autowired(required = false)
    public void setUploadedImageAssets(UploadedImageAssetIndexService uploadedImageAssets) {
        this.uploadedImageAssets = uploadedImageAssets;
    }

    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("backend", "REAL");
        try {
            Map<String, Object> health = intelligenceGateway == null ? localAi.health() : intelligenceGateway.health();
            if (intelligenceGateway != null) {
                output.put("intelligencePlane", health.getOrDefault("status", "UNAVAILABLE"));
                output.put("contractVersion", health.getOrDefault("contractVersion", "ai.v1"));
                output.put("vision", health.getOrDefault("vision", "UNAVAILABLE"));
                output.put("textEmbedding", health.getOrDefault("textEmbedding", "UNAVAILABLE"));
                output.put("imageEmbedding", health.getOrDefault("imageEmbedding", "UNAVAILABLE"));
            }
            output.put("localAi", health.getOrDefault("status", "UNAVAILABLE"));
            output.put("bge", health.get("textEmbedding"));
            output.put("clip", health.get("imageEmbedding"));
        } catch (Exception error) {
            output.put("localAi", "UNAVAILABLE");
        }
        // Production text and vision calls now go through the verified Qwen
        // Model Studio gateway. Keep the legacy local/Flask adapter details
        // out of the product health contract so an unused Hunyuan adapter
        // cannot make the live model appear unavailable.
        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("provider", "qwen-model-studio");
        llm.put("defaultModel", defaultModel);
        llm.put("multimodalModel", multimodalModel);
        llm.put("status", modelStatus(defaultModel));
        llm.put("verification", modelAvailability == null ? "UNAVAILABLE" : "SEE_AVAILABILITY");
        if (modelAvailability != null) llm.put("models", modelAvailability.snapshot());
        output.put("llm", llm);
        Map<String, Object> vision = new LinkedHashMap<>();
        vision.put("provider", "qwen-model-studio");
        vision.put("model", multimodalModel);
        vision.put("mode", "MODEL_GATEWAY");
        vision.put("status", modelStatus(multimodalModel));
        output.put("vision", vision);
        output.put("intelligencePlane", "READY");
        output.put("bailian", "QWEN_MODEL_STUDIO");
        output.put("modelGateway", bailianModelClient == null
                ? Map.of("status", "UNAVAILABLE")
                : Map.of("status", bailianModelClient.isConfigured(), "endpoint", bailianModelClient.getApiUrl()));
        Map<String, Object> vector = new LinkedHashMap<>();
        vector.put("endpoint", milvusHost + ":" + milvusPort);
        vector.put("collection", "farmap_image_vectors_new");
        try {
            Map<String, Object> store = historicalVectorStore.initialize(false);
            vector.putAll(store);
            vector.put("status", "READY".equals(store.get("status")) ? "READY" : store.get("status"));
            output.put("historical", "READY".equals(store.get("status"))
                    ? (Long.valueOf(0L).equals(store.get("rowCount")) ? "READY_EMPTY" : "READY")
                    : "UNAVAILABLE");
            output.put("historicalVectorStore", store.get("status"));
            output.put("historicalCases", store.getOrDefault("rowCount", 0L));
            output.put("historicalRetriever", "READY".equals(store.get("status"))
                    ? (Long.valueOf(0L).equals(store.get("rowCount")) ? "READY_EMPTY" : "READY")
                    : "UNAVAILABLE");
        } catch (Exception error) {
            vector.put("status", "UNAVAILABLE");
            vector.put("error", error.getMessage());
            output.put("historical", "UNAVAILABLE");
            output.put("historicalVectorStore", "UNAVAILABLE");
            output.put("historicalCases", 0L);
            output.put("historicalRetriever", "UNAVAILABLE");
        }
        output.put("milvus", vector);
        output.put("multimodalImageIndex", uploadedImageAssets == null
                ? Map.of("status", "UNAVAILABLE", "reason", "INDEX_NOT_CONFIGURED")
                : uploadedImageAssets.health());
        Map<String,Object> knowledge = knowledgeBackend == null ? Map.of("status", "UNAVAILABLE") : knowledgeBackend.health();
        output.put("knowledge", knowledge.getOrDefault("status", "UNAVAILABLE"));
        output.put("knowledgeBackend", knowledge);
        output.put("knowledgeIndex", knowledgeVectorStore == null
                ? Map.of("status", "UNAVAILABLE", "reason", "INDEX_NOT_CONFIGURED")
                : knowledgeVectorStore.health());
        output.put("knowledgeQwenIndex", qwenKnowledgeVectorStore == null
                ? Map.of("status", "UNAVAILABLE", "reason", "INDEX_NOT_CONFIGURED")
                : qwenKnowledgeVectorStore.health());
        output.put("bailian", "QWEN_MODEL_STUDIO");
        Map<String, Object> agent = new LinkedHashMap<>();
        try {
            String persistence = System.getenv().getOrDefault("FARMAP_AGENT_PERSISTENCE", "file");
            agent.put("runs", agentRuntime.list().size());
            agent.put("status", "mysql".equalsIgnoreCase(persistence) ? "READY" : "DEMO");
            agent.put("persistence", persistence);
        } catch (Exception error) { agent.put("status", "UNAVAILABLE"); agent.put("runs", 0); }
        if (!agent.containsKey("persistence")) agent.put("persistence", System.getenv().getOrDefault("FARMAP_AGENT_PERSISTENCE", "file"));
        output.put("agent", agent);
        output.put("operations", operations == null ? Map.of("status", "UNAVAILABLE") : operations.health());
        Map<String,Object> reviewHealth = expertReviews == null ? Map.of("status", "UNAVAILABLE") : new LinkedHashMap<>(expertReviews.health());
        reviewHealth.put("stateModel", new String[]{"PENDING", "IN_REVIEW", "CONFIRMED", "CORRECTED"});
        output.put("expertReview", reviewHealth);
        Map<String,Object> candidateHealth = candidates == null ? Map.of("status", "UNAVAILABLE") : candidates.health();
        output.put("historicalCaseCandidate", candidateHealth);
        output.put("historicalCase", Map.of("status", output.getOrDefault("historicalRetriever", "UNAVAILABLE"), "ingestion", "disabled-until-candidate-promotion", "collection", "farmap_image_vectors_new"));
        output.put("migrationReadiness", migrationReadiness == null ? Map.of("status", "NOT_READY", "reason", "VERIFIER_NOT_CONFIGURED") : migrationReadiness.verify());
        return Result.success(output);
    }

    private String modelStatus(String model) {
        if (modelAvailability == null) return "CONFIGURED";
        String status = modelAvailability.status(model);
        if ("available".equals(status)) return "READY";
        if ("unavailable".equals(status)) return "UNAVAILABLE";
        return "CONFIGURED";
    }

    @GetMapping("/migration-readiness")
    public ResponseEntity<Result<Map<String, Object>>> migrationReadiness() {
        Map<String, Object> report = this.migrationReadiness == null
                ? Map.of("status", "NOT_READY", "reason", "VERIFIER_NOT_CONFIGURED")
                : this.migrationReadiness.verify();
        HttpStatus status = "READY".equals(report.get("status")) ? HttpStatus.OK
                : "UNAVAILABLE".equals(report.get("status")) ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.NOT_IMPLEMENTED;
        return ResponseEntity.status(status).body(Result.success(report));
    }
}
