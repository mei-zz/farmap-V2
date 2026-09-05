package com.mei.zhgy.service.rag;

import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** GIS remains the source of spatial facts; this adapter only normalizes supplied context. */
@Component
public class SpatialEvidenceRetriever implements DiagnosisRetriever {
    @Override
    public String modality() {
        return "spatial";
    }

    @Override
    public List<DiagnosisEvidenceVO> retrieve(DiagnosisAnalyzeRequest request) {
        if (request.getContext() == null || !(request.getContext().get("spatial") instanceof Map<?, ?> spatial)) {
            return Collections.emptyList();
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("retrievalType", "gis_query");
        metadata.put("sourceMode", "provided-context");
        metadata.put("geometry", spatial.get("geometry"));
        metadata.put("adjacentFields", spatial.get("adjacentFields"));
        metadata.put("elevation", spatial.get("elevation"));
        metadata.put("slope", spatial.get("slope"));
        metadata.put("ndvi", spatial.get("ndvi"));
        return Collections.singletonList(DiagnosisEvidenceVO.builder()
                .id("spatial_1")
                .modality("spatial")
                .title(text(spatial.get("title"), "GIS 地块空间上下文"))
                .summary(text(spatial.get("summary"), "GIS 空间上下文已提供，待结合相邻地块与地形信息分析。"))
                .score(number(spatial.get("score"), 0.72D))
                .source(DiagnosisEvidenceVO.Source.builder()
                        .type("spatial")
                        .name("FarMap GIS")
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
