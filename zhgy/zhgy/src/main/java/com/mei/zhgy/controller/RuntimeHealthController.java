package com.mei.zhgy.controller;

import com.mei.zhgy.result.Result;
import com.mei.zhgy.service.ai.LocalAiClient;
import com.mei.zhgy.service.agent.AgentRuntime;
import com.mei.zhgy.service.operations.OperationsTaskService;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.R;
import io.milvus.param.collection.HasCollectionParam;
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
    private final MilvusServiceClient milvus;
    private final AgentRuntime agentRuntime;
    private final OperationsTaskService operations;
    @Value("${milvus.host:127.0.0.1}") private String milvusHost;
    @Value("${milvus.port:19530}") private int milvusPort;

    @Autowired
    public RuntimeHealthController(LocalAiClient localAi, MilvusServiceClient milvus, AgentRuntime agentRuntime, OperationsTaskService operations) {
        this.localAi = localAi;
        this.milvus = milvus;
        this.agentRuntime = agentRuntime;
        this.operations = operations;
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
            R<Boolean> exists = milvus.hasCollection(HasCollectionParam.newBuilder().withCollectionName("farmap_image_vectors_new").build());
            boolean available = exists.getStatus() == R.Status.Success.getCode() && Boolean.TRUE.equals(exists.getData());
            vector.put("status", "VERIFIED");
            vector.put("collectionExists", available);
            output.put("historical", available ? "REAL" : "BLOCKED");
        } catch (Exception error) {
            vector.put("status", "BLOCKED");
            output.put("historical", "BLOCKED");
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
