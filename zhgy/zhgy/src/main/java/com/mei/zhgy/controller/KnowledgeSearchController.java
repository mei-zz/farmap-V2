package com.mei.zhgy.controller;

import com.mei.zhgy.context.BaseContext;
import com.mei.zhgy.result.Result;
import com.mei.zhgy.service.knowledge.KnowledgeBackendException;
import com.mei.zhgy.service.knowledge.KnowledgeQueryRewriter;
import com.mei.zhgy.service.knowledge.KnowledgeVectorStore;
import com.mei.zhgy.service.knowledge.QwenKnowledgeVectorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only semantic search boundary. It never activates or writes knowledge. */
@RestController
@RequestMapping("/api/knowledge/search")
public class KnowledgeSearchController {
    private final KnowledgeVectorStore vectors;
    private final QwenKnowledgeVectorStore qwenVectors;
    private final KnowledgeQueryRewriter queryRewriter;
    public KnowledgeSearchController(KnowledgeVectorStore vectors) { this(vectors, null, new KnowledgeQueryRewriter()); }
    public KnowledgeSearchController(KnowledgeVectorStore vectors, QwenKnowledgeVectorStore qwenVectors) { this(vectors, qwenVectors, new KnowledgeQueryRewriter()); }
    @org.springframework.beans.factory.annotation.Autowired
    public KnowledgeSearchController(KnowledgeVectorStore vectors,
                                     @org.springframework.beans.factory.annotation.Autowired(required = false) QwenKnowledgeVectorStore qwenVectors,
                                     KnowledgeQueryRewriter queryRewriter) { this.vectors = vectors; this.qwenVectors = qwenVectors; this.queryRewriter = queryRewriter == null ? new KnowledgeQueryRewriter() : queryRewriter; }

    @GetMapping
    public Result<Map<String, Object>> search(@RequestParam(required = false) String q,
                                              @RequestParam(required = false) String query,
                                              @RequestParam(required = false, defaultValue = "5") int topK,
                                              @RequestParam(required = false, defaultValue = "true") boolean rerank) {
        return execute(q == null ? query : q, topK, rerank);
    }

    @PostMapping
    public Result<Map<String, Object>> searchPost(@RequestBody(required = false) Map<String, Object> input) {
        String query = input == null ? null : first(input, "query", "q");
        int topK = 5;
        boolean rerank = true;
        if (input != null && input.get("topK") instanceof Number number) topK = number.intValue();
        if (input != null && input.get("rerank") instanceof Boolean value) rerank = value;
        return execute(query, topK, rerank);
    }

    private Result<Map<String, Object>> execute(String query, int topK, boolean rerank) {
        Long actor = BaseContext.getCurrentId(); if (actor == null) throw KnowledgeBackendException.forbidden(null);
        if (query == null || query.isBlank()) throw KnowledgeBackendException.validation("query 不能为空");
        KnowledgeQueryRewriter.RewrittenQuery rewritten = queryRewriter.rewrite(query, null, null, Map.of(), topK);
        query = rewritten.query();
        topK = rewritten.topK();
        List<Map<String, Object>> output = new ArrayList<>();
        Map<String, Object> response = new LinkedHashMap<>();
        if (qwenVectors != null && qwenVectors.isProductionEnabled()) {
            QwenKnowledgeVectorStore.SearchResult result = qwenVectors.searchResult(query, actor, topK, rerank);
            for (QwenKnowledgeVectorStore.KnowledgeVectorHit hit : result.getHits()) {
                Map<String, Object> item = new LinkedHashMap<>(); item.put("chunkId", hit.chunkId); item.put("sourceId", hit.sourceId); item.put("sourceName", hit.sourceName); item.put("documentId", hit.documentId); item.put("documentTitle", hit.documentTitle); item.put("relativePath", hit.relativePath); item.put("versionId", hit.versionId); item.put("score", Double.isFinite(hit.rerankScore) ? hit.rerankScore : hit.hybridScore); item.put("vectorScore", hit.vectorScore); item.put("bm25Score", hit.bm25Score); item.put("hybridScore", hit.hybridScore); item.put("rerankScore", Double.isFinite(hit.rerankScore) ? hit.rerankScore : null); item.put("retrievalScore", hit.hybridScore); item.put("content", hit.content); item.put("contentHash", hit.contentHash); item.put("classification", hit.classification); item.put("embeddingModel", QwenKnowledgeVectorStore.MODEL); item.put("rerankerModel", "gte-rerank-v2"); output.add(item);
            }
            response.put("status", result.getStatus()); response.put("retrievalProvider", result.getRetrievalProvider()); response.put("embeddingModel", QwenKnowledgeVectorStore.MODEL); response.put("rerankerModel", "gte-rerank-v2"); response.put("rerankerStatus", result.getRerankerStatus());
        } else {
            KnowledgeVectorStore.SearchResult result = vectors.searchResult(query, actor, topK);
            for (KnowledgeVectorStore.KnowledgeVectorHit hit : result.getHits()) {
                Map<String, Object> item = new LinkedHashMap<>(); item.put("chunkId", hit.chunkId); item.put("sourceId", hit.sourceId); item.put("sourceName", hit.sourceName); item.put("documentId", hit.documentId); item.put("documentTitle", hit.documentTitle); item.put("versionId", hit.versionId); item.put("score", hit.score); item.put("vectorScore", hit.score); item.put("rerankScore", null); item.put("retrievalScore", hit.score); item.put("content", hit.content); item.put("contentHash", hit.contentHash); item.put("classification", hit.classification); item.put("embeddingModel", "BAAI/bge-small-zh-v1.5"); item.put("rerankerModel", null); output.add(item);
            }
            response.put("status", result.getStatus()); response.put("retrievalProvider", "LOCAL_BGE"); response.put("embeddingModel", "BAAI/bge-small-zh-v1.5"); response.put("rerankerModel", null); response.put("rerankerStatus", "NOT_USED");
        }
        response.put("originalQuery", rewritten.originalQuery()); response.put("rewrittenQuery", rewritten.query()); response.put("queryType", rewritten.queryType()); response.put("dynamicTopK", rewritten.topK()); response.put("referenceExpanded", rewritten.referenceExpanded());
        response.put("results", output); response.put("topK", Math.max(1, Math.min(20, topK)));
        return Result.success(response);
    }

    private String first(Map<String, Object> input, String first, String second) {
        Object value = input.get(first); if (value == null) value = input.get(second);
        return value == null ? null : String.valueOf(value);
    }
}
