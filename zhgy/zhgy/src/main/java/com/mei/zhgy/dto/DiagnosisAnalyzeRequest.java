package com.mei.zhgy.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Multimodal RAG 诊断请求。
 *
 * <p>weather 是当前页面已经掌握的结构化上下文，不会替换旧天气接口；
 * 后续可由服务端 WeatherRetriever 改为直接读取实时数据。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DiagnosisAnalyzeRequest {
    private String fieldId;
    private Integer farmId;
    private String crop;
    private String growthStage;
    private List<String> imageUrls = new ArrayList<>();
    private WeatherContext weather;
    private String query;
    private Integer topK = 5;
    /** 研究消融开关；为空时启用所有已注册 Retriever。 */
    private List<String> enabledModalities = new ArrayList<>();
    /** default / force_qwen36 / force_vl32 / force_vl235。 */
    private String modelPolicy = "default";
    private Map<String, Object> context;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WeatherContext {
        private Double rainfall14d;
        private String summary;
        private String temperature;
        private String observedAt;
    }
}
