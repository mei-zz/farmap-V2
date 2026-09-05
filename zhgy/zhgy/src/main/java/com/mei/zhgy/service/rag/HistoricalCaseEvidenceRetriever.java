package com.mei.zhgy.service.rag;

import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.entity.Case;
import com.mei.zhgy.service.CaseStorageService;
import com.mei.zhgy.service.ai.EmbeddingProvider;
import com.mei.zhgy.service.ai.ImageEmbeddingProvider;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 历史案例 Retriever 的输入适配层。
 * 优先使用本地 CLIP + 现有 Milvus/Mongo 案例服务；不可用时才读取调用方明确提供的案例上下文。
 * 不会在缺少向量或数据库时制造相似案例。
 */
@Component
public class HistoricalCaseEvidenceRetriever implements DiagnosisRetriever {
    private final CaseStorageService caseStorageService;
    private final ImageEmbeddingProvider embeddingProvider;

    @Value("${farmap.ai.local-vision.enabled:true}")
    private boolean localVisionEnabled;

    public HistoricalCaseEvidenceRetriever(CaseStorageService caseStorageService,
                                           ImageEmbeddingProvider embeddingProvider) {
        this.caseStorageService = caseStorageService;
        this.embeddingProvider = embeddingProvider;
    }

    @Override
    public String modality() {
        return "historical_case";
    }

    @Override
    public List<DiagnosisEvidenceVO> retrieve(DiagnosisAnalyzeRequest request) {
        List<DiagnosisEvidenceVO> localEvidence = retrieveFromLocalCaseStore(request);
        if (!localEvidence.isEmpty()) {
            return localEvidence;
        }
        if (request.getContext() == null || !(request.getContext().get("historicalCases") instanceof List<?> cases)) {
            return Collections.emptyList();
        }

        List<DiagnosisEvidenceVO> evidence = new ArrayList<>();
        int index = 1;
        for (Object item : cases) {
            if (!(item instanceof Map<?, ?> rawCase)) {
                continue;
            }
            String caseId = stringValue(rawCase.get("caseId"), "case_" + index);
            String summary = stringValue(rawCase.get("summary"), "已提供历史案例上下文，待进一步核验。");
            String imageUrl = stringValue(rawCase.get("imageUrl"), null);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("sourceMode", "provided-context");
            metadata.put("expertStatus", stringValue(rawCase.get("expertStatus"), "unknown"));
            metadata.put("caseId", caseId);
            metadata.put("fieldId", rawCase.get("fieldId"));
            metadata.put("crop", rawCase.get("crop"));
            metadata.put("date", rawCase.get("date"));
            metadata.put("diagnosis", rawCase.get("diagnosis"));
            metadata.put("treatment", rawCase.get("treatment"));
            metadata.put("outcome", rawCase.get("outcome"));
            evidence.add(DiagnosisEvidenceVO.builder()
                    .id("historical_" + index)
                    .modality("historical_case")
                    .title(caseId + " 历史专家案例")
                    .summary(summary)
                    .score(numberValue(rawCase.get("score"), 0.7D))
                    .source(DiagnosisEvidenceVO.Source.builder()
                            .type("historical_case")
                            .name("Expert confirmed case")
                            .timestamp(stringValue(rawCase.get("timestamp"), null))
                            .document(caseId)
                            .fieldId(request.getFieldId())
                            .build())
                    .preview(DiagnosisEvidenceVO.Preview.builder().imageUrl(imageUrl).build())
                    .metadata(metadata)
                    .build());
            index++;
        }
        return evidence;
    }

    private List<DiagnosisEvidenceVO> retrieveFromLocalCaseStore(DiagnosisAnalyzeRequest request) {
        if (!localVisionEnabled || request.getImageUrls() == null || request.getImageUrls().isEmpty()) {
            return Collections.emptyList();
        }
        EmbeddingProvider.EmbeddingResult embedding = embeddingProvider.embedImage(request.getImageUrls().get(0));
        if (embedding.getVector().isEmpty()) {
            return Collections.emptyList();
        }
        List<CaseStorageService.SimilarCaseWithScore> cases = caseStorageService.searchSimilarCasesWithScore(
                embedding.getVector(), request.getTopK() == null ? 5 : request.getTopK());
        List<DiagnosisEvidenceVO> evidence = new ArrayList<>();
        int index = 1;
        for (CaseStorageService.SimilarCaseWithScore item : cases) {
            Case caseEntity = item.getCaseEntity();
            String caseId = caseEntity.getRequestId() != null ? caseEntity.getRequestId() : "case_" + index;
            String diagnosis = firstText(caseEntity.getDiseaseType(), caseEntity.getNutritionStatus(), caseEntity.getTreeSpecies());
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("sourceMode", "local-clip-milvus");
            metadata.put("expertStatus", caseEntity.getFinalJson() == null ? "expert_reviewed" : "expert_confirmed");
            metadata.put("caseId", caseId);
            metadata.put("fieldId", request.getFieldId());
            metadata.put("crop", firstText(caseEntity.getTreeSpecies(), request.getCrop()));
            metadata.put("date", caseEntity.getUpdateTime() == null ? null : caseEntity.getUpdateTime().toString());
            metadata.put("diagnosis", diagnosis);
            metadata.put("treatment", null);
            metadata.put("outcome", null);
            metadata.put("embeddingProvider", embedding.getProvider());
            metadata.put("embeddingModel", embedding.getModel());
            metadata.put("embeddingDimension", embedding.getDimension());
            metadata.put("vectorDistance", item.getVectorDistance());
            metadata.put("scoreTransform", "1/(1+distance)");
            metadata.put("rankingScore", item.getRankingScore());
            evidence.add(DiagnosisEvidenceVO.builder()
                    .id("historical_local_" + index)
                    .modality("historical_case")
                    .title(caseId + " 历史专家案例")
                    .summary(diagnosis == null ? "本地 CLIP + Milvus 返回的专家案例，待结合当前证据核验。" : "历史案例诊断：" + diagnosis)
                    .score(item.getRankingScore())
                    .source(DiagnosisEvidenceVO.Source.builder()
                            .type("historical_case")
                            .name("Local CLIP + Milvus case index")
                            .timestamp(caseEntity.getUpdateTime() == null ? null : caseEntity.getUpdateTime().toString())
                            .document(caseId)
                            .fieldId(request.getFieldId())
                            .build())
                    .metadata(metadata)
                    .build());
            index++;
        }
        return evidence;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value;
        }
        return null;
    }

    private String stringValue(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private Double numberValue(Object value, Double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return fallback;
    }
}
