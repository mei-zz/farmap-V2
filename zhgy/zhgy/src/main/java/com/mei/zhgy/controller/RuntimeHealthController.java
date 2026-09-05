package com.mei.zhgy.controller;

import com.mei.zhgy.result.Result;
import com.mei.zhgy.service.ai.LocalAiClient;
import com.mei.zhgy.service.agent.AgentRuntime;
import com.mei.zhgy.service.operations.OperationsTaskService;
import com.mei.zhgy.service.historical.HistoricalVectorStoreInitializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/runtime")
public class RuntimeHealthController {
    private final LocalAiClient localAi;
    private final AgentRuntime agentRuntime;
    private final OperationsTaskService operations;
    private final HistoricalVectorStoreInitializer historicalVectorStore;
    @Value("${milvus.host:127.0.0.1}") private String milvusHost;
    @Value("${milvus.port:19530}") private int milvusPort;

    @Autowired
    public RuntimeHealthController(LocalAiClient localAi, AgentRuntime agentRuntime, OperationsTaskService operations, HistoricalVectorStoreInitializer historicalVectorStore) {
        this.localAi = localAi;
        this.agentRuntime = agentRuntime;
        this.operations = operations;
        this.historicalVectorStore = historicalVectorStore;
    }

    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("backend", "REAL");
        try {
            Map<String, Object> health = localAi.health();
            output.put("localAi", health.getOrDefault("status", "UNAVAILABLE"));
            output.put("bge", health.get("textEmbedding"));
            output.put("clip", health.get("imageEmbedding"));
        } catch (Exception error) {
            output.put("localAi", "UNAVAILABLE");
        }
        Map<String, Object> vector = new LinkedHashMap<>();
        vector.put("endpoint", milvusHost + ":" + milvusPort);
        vector.put("collection", "farmap_image_vectors_new");
        try {
            Map<String, Object> store = historicalVectorStore.initialize(false);
            vector.putAll(store);
            vector.put("status", "READY".equals(store.get("status")) ? "READY" : store.get("status"));
            output.put("historical", "READY".equals(store.get("status"))
                    ? (Long.valueOf(0L).equals(store.get("rowCount")) ? "EMPTY" : "READY")
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
        output.put("knowledge", "LOCAL");
        output.put("bailian", "API");
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("status", "READY");
        agent.put("runs", agentRuntime.list().size());
        agent.put("persistence", System.getenv().getOrDefault("FARMAP_AGENT_PERSISTENCE", "file"));
        output.put("agent", agent);
        output.put("operations", Map.of("status", "READY", "tasks", operations.count()));
        output.put("expertReview", Map.of("status", "READY", "stateModel", new String[]{"PENDING", "IN_REVIEW", "CONFIRMED", "CORRECTED"}));
        output.put("historicalCase", Map.of("status", "READY", "ingestion", "idempotent", "collection", "farmap_image_vectors_new"));
        return Result.success(output);
    }
}
