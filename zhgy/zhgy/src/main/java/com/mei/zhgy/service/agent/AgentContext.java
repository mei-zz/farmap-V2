package com.mei.zhgy.service.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stable context contract shared by web, agent tools and the model gateway. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentContext {
    private String farmId;
    private String farmName;
    private String fieldId;
    private String fieldName;
    private String crop;
    private String variety;
    private String phenologyStage;
    private String location;
    private Double latitude;
    private Double longitude;
    private String cameraId;
    @Builder.Default private List<String> imageUrls = new ArrayList<>();
    @Builder.Default private Map<String, Object> weather = new LinkedHashMap<>();
    @Builder.Default private Map<String, Object> diagnosis = new LinkedHashMap<>();
    @Builder.Default private Map<String, Object> sourceModes = new LinkedHashMap<>();
    private String page;

    public int imageCount() { return imageUrls == null ? 0 : imageUrls.size(); }
    public Object weatherValue(String key) { return weather == null ? null : weather.get(key); }
}
