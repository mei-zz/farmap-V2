package com.mei.zhgy.service.rag;

import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Soil is a structured query when sensor context is supplied; it is not fabricated. */
@Component
public class SoilEvidenceRetriever implements DiagnosisRetriever {
    @Override
    public String modality() {
        return "soil";
    }

    @Override
    public List<DiagnosisEvidenceVO> retrieve(DiagnosisAnalyzeRequest request) {
        if (request.getContext() == null || !(request.getContext().get("soil") instanceof Map<?, ?> soil)) {
            return Collections.emptyList();
        }
        String summary = text(soil.get("summary"), "土壤传感器上下文已提供，待结合地块排水条件核验。");
        Double score = number(soil.get("score"), 0.78D);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("retrievalType", "structured_query");
        metadata.put("sourceMode", "provided-context");
        metadata.put("sensor", text(soil.get("sensor"), "Soil Sensor"));
        return Collections.singletonList(DiagnosisEvidenceVO.builder()
                .id("soil_1")
                .modality("soil")
                .title(text(soil.get("title"), "土壤传感器数据"))
                .summary(summary)
                .score(score)
                .source(DiagnosisEvidenceVO.Source.builder()
                        .type("soil")
                        .name(text(soil.get("sensor"), "Soil Sensor"))
                        .timestamp(text(soil.get("timestamp"), null))
                        .fieldId(request.getFieldId())
                        .build())
                .metadata(metadata)
                .build());
    }

    private String text(Object value, String fallback) {
        return value == null || String.valueOf(value).trim().isEmpty() ? fallback : String.valueOf(value);
    }

    private Double number(Object value, Double fallback) {
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }
}
