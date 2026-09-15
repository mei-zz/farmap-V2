package com.mei.zhgy.service.historical;

import com.mei.zhgy.entity.Case;
import com.mei.zhgy.service.CaseStorageService;
import com.mei.zhgy.service.ai.EmbeddingProvider;
import com.mei.zhgy.service.ai.ImageEmbeddingProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only historical-case retrieval for Copilot and the Agent workspace.
 * The production index is image/CLIP based, so a current field image is
 * required. This service deliberately returns a status instead of inventing
 * text-only cases when that evidence is absent.
 */
@Service
public class HistoricalCaseSearchService {
    private final CaseStorageService cases;
    private final ImageEmbeddingProvider imageEmbeddingProvider;

    public HistoricalCaseSearchService(CaseStorageService cases, ImageEmbeddingProvider imageEmbeddingProvider) {
        this.cases = cases;
        this.imageEmbeddingProvider = imageEmbeddingProvider;
    }

    public SearchResult search(List<String> imageUrls, Integer requestedTopK) {
        String imageUrl = firstImage(imageUrls);
        if (imageUrl == null) {
            return SearchResult.imageRequired();
        }

        int topK = requestedTopK == null ? 3 : Math.max(1, Math.min(requestedTopK, 10));
        try {
            EmbeddingProvider.EmbeddingResult embedding = imageEmbeddingProvider.embedImage(imageUrl);
            if (embedding == null || embedding.getVector() == null || embedding.getVector().isEmpty()) {
                return SearchResult.unavailable("现场图片向量暂未生成，历史病例检索不可用。", embedding == null ? null : embedding.getProvider());
            }

            List<CaseStorageService.SimilarCaseWithScore> matches = cases.searchSimilarCasesWithScore(embedding.getVector(), topK);
            List<Map<String, Object>> items = new ArrayList<>();
            for (CaseStorageService.SimilarCaseWithScore match : matches) {
                Case item = match.getCaseEntity();
                if (item == null) continue;
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("caseId", item.getRequestId() != null ? item.getRequestId() : item.getId() == null ? null : item.getId().toHexString());
                result.put("similarityScore", roundScore(match.getSimilarityScore()));
                result.put("vectorDistance", match.getVectorDistance());
                result.put("treeSpecies", item.getTreeSpecies());
                result.put("diseaseType", item.getDiseaseType());
                result.put("pestType", item.getPestType());
                result.put("nutritionStatus", item.getNutritionStatus());
                result.put("updatedAt", item.getUpdateTime());
                result.put("expertStatus", item.getFinalJson() == null ? "EXPERT_REVIEWED" : "EXPERT_CONFIRMED");
                items.add(result);
            }
            return items.isEmpty()
                    ? SearchResult.empty(embedding.getProvider(), embedding.getModel())
                    : SearchResult.ready(items, embedding.getProvider(), embedding.getModel());
        } catch (RuntimeException error) {
            return SearchResult.unavailable("历史病例向量检索服务暂不可用，请稍后重试。", "UNAVAILABLE");
        }
    }

    private String firstImage(List<String> imageUrls) {
        if (imageUrls == null) return null;
        return imageUrls.stream().filter(value -> value != null && !value.isBlank()).map(String::trim).findFirst().orElse(null);
    }

    private double roundScore(double score) {
        if (!Double.isFinite(score)) return 0D;
        return Math.round(Math.max(0D, Math.min(100D, score)) * 10D) / 10D;
    }

    public record SearchResult(String status, String reason, String provider, String model, List<Map<String, Object>> items) {
        static SearchResult imageRequired() { return new SearchResult("IMAGE_REQUIRED", "当前历史病例索引基于现场图片，需要先提供一张现场图片。", null, null, List.of()); }
        static SearchResult unavailable(String reason, String provider) { return new SearchResult("UNAVAILABLE", reason, provider, null, List.of()); }
        static SearchResult empty(String provider, String model) { return new SearchResult("EMPTY", "当前专家历史病例索引没有返回可验证的相似病例。", provider, model, List.of()); }
        static SearchResult ready(List<Map<String, Object>> items, String provider, String model) { return new SearchResult("READY", null, provider, model, items); }

        public Map<String, Object> asMap() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", status);
            body.put("reason", reason);
            body.put("provider", provider);
            body.put("model", model);
            body.put("count", items.size());
            body.put("items", items);
            return body;
        }
    }
}
