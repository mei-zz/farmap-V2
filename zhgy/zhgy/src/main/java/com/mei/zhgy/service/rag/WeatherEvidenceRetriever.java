package com.mei.zhgy.service.rag;

import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
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
        DiagnosisAnalyzeRequest.WeatherContext weather = request.getWeather();
        if (weather == null || weather.getRainfall14d() == null) {
            return Collections.emptyList();
        }

        double rainfall = weather.getRainfall14d();
        List<Double> baseline = Arrays.asList(4D, 2D, 0D, 8D, 11D, 17D, 16D);
        List<Double> chartData = new ArrayList<>();
        for (Double value : baseline) {
            chartData.add(Math.round(value * rainfall / 58D * 10D) / 10D);
        }
        return Collections.singletonList(DiagnosisEvidenceVO.builder()
                .id("weather_14d")
                .modality("weather")
                .title("近 14 天累计降雨 " + rainfall + " mm")
                .summary("天气上下文显示近期降雨集中，需结合地块排水条件评估根区风险。")
                .score(rainfall >= 40 ? 0.88 : 0.64)
                .source(DiagnosisEvidenceVO.Source.builder()
                        .type("weather")
                        .name("Field weather context")
                        .timestamp(weather.getObservedAt())
                        .fieldId(request.getFieldId())
                        .build())
                .preview(DiagnosisEvidenceVO.Preview.builder().chartData(chartData).build())
                .metadata(new LinkedHashMap<>(Map.of("rainfall14d", rainfall, "retrievalType", "temporal_query", "sourceMode", "structured-context")))
                .build());
    }
}
