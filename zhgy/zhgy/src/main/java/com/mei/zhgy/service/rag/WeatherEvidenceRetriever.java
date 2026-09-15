package com.mei.zhgy.service.rag;

import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 使用诊断请求携带的天气上下文形成可追溯的时间证据。 */
@Component
public class WeatherEvidenceRetriever implements DiagnosisRetriever {
    @Override
    public String modality() {
        return "weather";
    }

    @Override
    public List<DiagnosisEvidenceVO> retrieve(DiagnosisAnalyzeRequest request) {
        Map<String, Object> trusted = request.getTrustedFieldContext() == null
                ? null : request.getTrustedFieldContext().getWeather();
        DiagnosisAnalyzeRequest.WeatherContext weather = request.getWeather();
        if (trusted != null) {
            if (!"READY".equals(trusted.get("status")) || !(trusted.get("rainfall14d") instanceof Number)) {
                return Collections.emptyList();
            }
            return evidence(request, number(trusted.get("rainfall14d")), text(trusted.get("summary"), "可信天气数据"),
                    text(trusted.get("observedAt"), null), trusted);
        }
        if (weather == null || weather.getRainfall14d() == null) {
            return Collections.emptyList();
        }
        return evidence(request, weather.getRainfall14d(), weather.getSummary(), weather.getObservedAt(), Collections.emptyMap());
    }

    private List<DiagnosisEvidenceVO> evidence(DiagnosisAnalyzeRequest request, double rainfall, String summary,
                                                String observedAt, Map<String, Object> trusted) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        boolean forecast = Boolean.TRUE.equals(trusted.get("rainfallForecast"));
        metadata.put("rainfall14d", rainfall);
        metadata.put("rainfallForecast", forecast);
        metadata.put("retrievalType", "temporal_query");
        metadata.put("sourceMode", trusted.isEmpty() ? "structured-context" : trusted.get("sourceMode"));
        if (!trusted.isEmpty()) {
            metadata.put("scope", trusted.get("scope"));
            metadata.put("dataDate", trusted.get("dataDate"));
            metadata.put("observedAt", trusted.get("observedAt"));
            metadata.put("granularity", trusted.get("granularity"));
        }
        return Collections.singletonList(DiagnosisEvidenceVO.builder()
                .id("weather_14d")
                .modality("weather")
                .title((forecast ? "未来 14 天预计降雨 " : "近 14 天累计降雨 ") + rainfall + " mm")
                .summary(text(summary, "可信天气上下文已纳入诊断。"))
                .score(rainfall >= 40 ? 0.88 : 0.64)
                .source(DiagnosisEvidenceVO.Source.builder()
                        .type("weather")
                        .name(trusted.isEmpty() ? "Field weather context" : text(trusted.get("source"), "Field weather provider"))
                        .timestamp(observedAt)
                        .fieldId(request.getFieldId())
                        .build())
                .metadata(metadata)
                .build());
    }

    private String text(Object value, String fallback) { return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value); }
    private double number(Object value) { return value instanceof Number ? ((Number) value).doubleValue() : 0D; }
}
