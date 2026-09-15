package com.mei.zhgy.service.rag;

import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Emits only the persisted accumulated-temperature observation from FieldContext. */
@Component
public class AccumulatedTemperatureEvidenceRetriever implements DiagnosisRetriever {
    @Override
    public String modality() { return "accumulated_temperature"; }

    @Override
    public List<DiagnosisEvidenceVO> retrieve(DiagnosisAnalyzeRequest request) {
        if (request.getTrustedFieldContext() == null) return Collections.emptyList();
        Map<String, Object> value = request.getTrustedFieldContext().getAccumulatedTemperature();
        if (value == null || !"READY".equals(value.get("status"))) return Collections.emptyList();
        Map<String, Object> metadata = new LinkedHashMap<>(value);
        metadata.put("retrievalType", "structured_query");
        return Collections.singletonList(DiagnosisEvidenceVO.builder()
                .id("accumulated-temperature-" + request.getFieldId())
                .modality(modality())
                .title("地块积温记录")
                .summary("已读取服务端保存的积温观测，可用于判断当前生育阶段。")
                .score(0.78D)
                .source(DiagnosisEvidenceVO.Source.builder()
                        .type("accumulated_temperature")
                        .name("FarMap accumulated temperature")
                        .timestamp(String.valueOf(value.get("date")))
                        .fieldId(request.getFieldId())
                        .build())
                .metadata(metadata)
                .build());
    }
}
