package com.mei.zhgy.service.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.mei.zhgy.service.agriculture.FieldFacts;
import com.mei.zhgy.service.field.FieldContext;
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
    /** Canonical display name resolved by the server-owned crop catalog. */
    private String cropLabel;
    private String variety;
    private String phenologyStage;
    private String phenologyStageLabel;
    private String dateDerivedPhenologyStage;
    private String dateDerivedPhenologyStageLabel;
    private String registeredPhenologyStage;
    private String registeredPhenologyStageLabel;
    private String previousPhenologyStage;
    private String previousPhenologyStageLabel;
    private String nextPhenologyStage;
    private String nextPhenologyStageLabel;
    private Long daysUntilNextPhenologyStage;
    private String fieldFactsStatus;
    private String fieldFactsDate;
    private String phenologyCycleType;
    private boolean phenologyStageMismatch;
    private boolean phenologyCycleEnded;
    @Builder.Default private List<String> orderedPhenologyStages = new ArrayList<>();
    @Builder.Default private Map<String, String> factSources = new LinkedHashMap<>();
    private String location;
    private Double latitude;
    private Double longitude;
    private String cameraId;
    /** Canonical farm map point associated with the selected camera. */
    private String pointId;
    /** Stable Diagnosis business identity; runId remains only in the legacy diagnosis map. */
    private String diagnosisId;
    /** Explicit Recommendation source; null means the run is Diagnosis-level. */
    private String sourceRecommendationId;
    @Builder.Default private List<String> imageUrls = new ArrayList<>();
    @Builder.Default private Map<String, Object> weather = new LinkedHashMap<>();
    @Builder.Default private Map<String, Object> diagnosis = new LinkedHashMap<>();
    @Builder.Default private Map<String, Object> sourceModes = new LinkedHashMap<>();
    private String page;

    public int imageCount() { return imageUrls == null ? 0 : imageUrls.size(); }
    public Object weatherValue(String key) { return weather == null ? null : weather.get(key); }

    /** Applies the server-owned field facts, including deterministic phenology neighbours. */
    public void applyFieldFacts(FieldFacts facts) {
        if (facts == null) return;
        setCrop(facts.cropCode()); setCropLabel(facts.cropLabel());
        setPhenologyStage(facts.currentGrowthStage()); setPhenologyStageLabel(facts.currentGrowthStageLabel());
        setDateDerivedPhenologyStage(facts.dateDerivedGrowthStage()); setDateDerivedPhenologyStageLabel(facts.dateDerivedGrowthStageLabel());
        setRegisteredPhenologyStage(facts.registeredGrowthStage()); setRegisteredPhenologyStageLabel(facts.registeredGrowthStageLabel());
        setPreviousPhenologyStage(facts.previousGrowthStage()); setPreviousPhenologyStageLabel(facts.previousGrowthStageLabel());
        setNextPhenologyStage(facts.nextGrowthStage()); setNextPhenologyStageLabel(facts.nextGrowthStageLabel());
        setDaysUntilNextPhenologyStage(facts.daysUntilNext()); setFieldFactsStatus(facts.status()); setFieldFactsDate(facts.currentDate());
        setPhenologyCycleType(facts.phenologyCycleType()); setPhenologyStageMismatch(facts.stageMismatch()); setPhenologyCycleEnded(facts.cycleEnded());
        setOrderedPhenologyStages(facts.orderedGrowthStages() == null ? new ArrayList<>() : new ArrayList<>(facts.orderedGrowthStages()));
        setFactSources(facts.sources() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(facts.sources()));
        FieldContext trusted = facts.trustedContext();
        if (trusted == null) return;
        Map<String, Object> farm = trusted.getFarm(); Map<String, Object> field = trusted.getField(); Map<String, Object> location = trusted.getLocation();
        if (farm != null && farm.get("id") != null) setFarmId(String.valueOf(farm.get("id")));
        if (farm != null && farm.get("name") != null) setFarmName(String.valueOf(farm.get("name")));
        if (field != null && field.get("fieldId") != null) setFieldId(String.valueOf(field.get("fieldId")));
        if (field != null && field.get("name") != null) setFieldName(String.valueOf(field.get("name")));
        if (location != null && location.get("latitude") instanceof Number value) setLatitude(value.doubleValue());
        if (location != null && location.get("longitude") instanceof Number value) setLongitude(value.doubleValue());
        if (location != null) setLocation(String.valueOf(location.getOrDefault("scope", "FIELD")));
        setWeather(trusted.getWeather() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(trusted.getWeather()));
        Map<String, Object> sources = new LinkedHashMap<>();
        sources.put("field", trusted.getSourceMode()); sources.put("weather", trusted.getWeather() == null ? "UNAVAILABLE" : trusted.getWeather().getOrDefault("sourceMode", "UNAVAILABLE"));
        sources.put("gis", trusted.getGis() == null ? "UNAVAILABLE" : trusted.getGis().getOrDefault("sourceMode", "UNAVAILABLE"));
        sources.put("camera", "UNAVAILABLE"); sources.put("knowledge", "UNAVAILABLE"); sources.put("historical", "UNAVAILABLE");
        setSourceModes(sources);
    }
}
