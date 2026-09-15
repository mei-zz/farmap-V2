package com.mei.zhgy.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.mei.zhgy.service.field.FieldContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Multimodal RAG 诊断请求。
 *
 * <p>weather 是经过服务端农场权限校验后注入的结构化天气上下文，包含当前观测与
 * 降雨预测，供 RAG 和 Agent 使用。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DiagnosisAnalyzeRequest {
    private String fieldId;
    private Integer farmId;
    /** Spatial device context selected by the user for this investigation. */
    private String pointId;
    private String cameraId;
    private List<String> imageAssetIds = new ArrayList<>();
    private String crop;
    private String growthStage;
    private List<String> imageUrls = new ArrayList<>();
    private WeatherContext weather;
    private String query;
    /** Null means the knowledge retriever applies its query-type policy. */
    private Integer topK;
    /** 研究消融开关；为空时启用所有已注册 Retriever。 */
    private List<String> enabledModalities = new ArrayList<>();
    /** default / force_qwen36 / force_vl32 / force_vl235。 */
    private String modelPolicy = "default";
    private Map<String, Object> context;

    /**
     * Server-only snapshot.  It is deliberately not deserialized from or
     * serialized to the client request; FieldContext is assembled after
     * authentication and field ownership checks.
     */
    @JsonIgnore
    private transient FieldContext trustedFieldContext;

    /** Compatibility constructor retained for existing Phase 0-4 callers/tests. */
    public DiagnosisAnalyzeRequest(String fieldId, Integer farmId, String crop, String growthStage,
                                   List<String> imageUrls, WeatherContext weather, String query,
                                   Integer topK, List<String> enabledModalities, String modelPolicy,
                                   Map<String, Object> context) {
        this.fieldId = fieldId;
        this.farmId = farmId;
        this.crop = crop;
        this.growthStage = growthStage;
        this.imageUrls = imageUrls;
        this.weather = weather;
        this.query = query;
        this.topK = topK;
        this.enabledModalities = enabledModalities;
        this.modelPolicy = modelPolicy;
        this.context = context;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WeatherContext {
        private Double rainfall14d;
        private Double rainfallForecast24h;
        private Integer rainfallProbability24h;
        private Boolean rainfallForecast;
        private Integer rainfallForecastDays;
        private List<Map<String, Object>> forecast = new ArrayList<>();
        private String summary;
        private String temperature;
        private String observedAt;

        /** Compatibility constructor for legacy diagnosis fixtures and clients. */
        public WeatherContext(Double rainfall14d, String summary, String temperature, String observedAt) {
            this.rainfall14d = rainfall14d;
            this.summary = summary;
            this.temperature = temperature;
            this.observedAt = observedAt;
        }
    }
}
