package com.mei.zhgy.service.rag;

import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 按 Retriever score 排序并写入可审计的 retrievalRank，不改变 Evidence Contract。 */
@Service
public class EvidenceFusionService {
    @Value("${farmap.ai.retrieval.weights.camera:1.0}")
    private double cameraWeight = 1.0;
    @Value("${farmap.ai.retrieval.weights.weather:0.95}")
    private double weatherWeight = 0.95;
    @Value("${farmap.ai.retrieval.weights.knowledge:1.0}")
    private double knowledgeWeight = 1.0;
    @Value("${farmap.ai.retrieval.weights.historical-case:1.1}")
    private double historicalWeight = 1.1;
    @Value("${farmap.ai.retrieval.weights.soil:1.0}")
    private double soilWeight = 1.0;
    @Value("${farmap.ai.retrieval.weights.spatial:0.9}")
    private double spatialWeight = 0.9;

    public List<DiagnosisEvidenceVO> fuse(List<DiagnosisEvidenceVO> evidence) {
        List<DiagnosisEvidenceVO> ranked = new ArrayList<>(evidence);
        ranked.sort(Comparator.comparing(this::fusedScore, Comparator.nullsLast(Double::compareTo)).reversed());
        for (int index = 0; index < ranked.size(); index++) {
            DiagnosisEvidenceVO item = ranked.get(index);
            Map<String, Object> metadata = item.getMetadata() == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(item.getMetadata());
            metadata.put("retrievalRank", index + 1);
            metadata.put("fusedScore", fusedScore(item));
            metadata.put("sourceReliability", sourceReliability(item));
            item.setMetadata(metadata);
        }
        return ranked;
    }

    private Double fusedScore(DiagnosisEvidenceVO item) {
        if (item == null || item.getScore() == null) return null;
        return item.getScore() * modalityWeight(item.getModality()) * sourceReliability(item);
    }

    private double modalityWeight(String modality) {
        if ("camera".equals(modality)) return cameraWeight;
        if ("weather".equals(modality)) return weatherWeight;
        if ("knowledge".equals(modality)) return knowledgeWeight;
        if ("historical_case".equals(modality)) return historicalWeight;
        if ("soil".equals(modality)) return soilWeight;
        if ("spatial".equals(modality)) return spatialWeight;
        return 1.0;
    }

    private double sourceReliability(DiagnosisEvidenceVO item) {
        Object expertStatus = item.getMetadata() == null ? null : item.getMetadata().get("expertStatus");
        if ("expert-confirmed".equals(expertStatus)) return 1.1;
        if ("reviewed".equals(expertStatus)) return 1.05;
        return 1.0;
    }
}
