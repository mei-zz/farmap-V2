package com.mei.zhgy.service.rag;

import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Optional satellite adapter; it is active only when real GIS/satellite context is supplied. */
@Component
public class SatelliteEvidenceRetriever implements DiagnosisRetriever {
    @Override
    public String modality() {
        return "satellite";
    }

    @Override
    public List<DiagnosisEvidenceVO> retrieve(DiagnosisAnalyzeRequest request) {
        if (request.getContext() == null || !(request.getContext().get("satellite") instanceof Map<?, ?> satellite)) {
            return Collections.emptyList();
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("retrievalType", "remote_sensing_query");
        metadata.put("sourceMode", "provided-context");
        metadata.put("ndvi", satellite.get("ndvi"));
        metadata.put("captureTime", satellite.get("captureTime"));
        return Collections.singletonList(DiagnosisEvidenceVO.builder()
                .id("satellite_1")
                .modality("satellite")
                .title(text(satellite.get("title"), "卫星影像上下文"))
                .summary(text(satellite.get("summary"), "卫星影像上下文已提供，待结合地块时序变化分析。"))
                .score(number(satellite.get("score"), 0.7D))
                .source(DiagnosisEvidenceVO.Source.builder()
                        .type("satellite")
                        .name(text(satellite.get("source"), "FarMap satellite layer"))
                        .timestamp(text(satellite.get("captureTime"), null))
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
