package com.mei.zhgy.service.knowledge;

import com.mei.zhgy.service.ai.BailianModelClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class BailianKnowledgeReranker implements KnowledgeReranker {
    private final BailianModelClient client;
    private final String model;

    public BailianKnowledgeReranker(
            BailianModelClient client,
            @Value("${farmap.knowledge.rerank.model:gte-rerank-v2}") String model) {
        this.client = client;
        this.model = model;
    }

    @Override public List<RerankResult> rerank(String query, List<String> documents, int topK) {
        return client.rerank(model, query, documents, topK).stream()
                .map(value -> new RerankResult(value.getIndex(), value.getScore()))
                .toList();
    }
    @Override public String provider() { return "BAILIAN"; }
    @Override public String model() { return model; }
    @Override public boolean configured() { return client.isConfigured(); }
}
