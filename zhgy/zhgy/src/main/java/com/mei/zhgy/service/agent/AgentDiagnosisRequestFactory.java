package com.mei.zhgy.service.agent;

import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AgentDiagnosisRequestFactory {
    private AgentDiagnosisRequestFactory() { }
    static DiagnosisAnalyzeRequest create(AgentToolRequest request) {
        AgentContext c = request.getContext() == null ? new AgentContext() : request.getContext();
        DiagnosisAnalyzeRequest out = new DiagnosisAnalyzeRequest();
        out.setFieldId(c.getFieldId() == null ? "unknown-field" : c.getFieldId());
        try { out.setFarmId(c.getFarmId() == null ? null : Integer.valueOf(c.getFarmId())); } catch (NumberFormatException ignored) { }
        out.setCrop(c.getCrop()); out.setGrowthStage(c.getPhenologyStage());
        out.setImageUrls(c.getImageUrls() == null ? new ArrayList<>() : new ArrayList<>(c.getImageUrls()));
        Map<String, Object> weather = c.getWeather() == null ? new LinkedHashMap<>() : c.getWeather();
        DiagnosisAnalyzeRequest.WeatherContext wc = new DiagnosisAnalyzeRequest.WeatherContext();
        Object rainfall = weather.get("rainfall14d");
        if (rainfall instanceof Number n) wc.setRainfall14d(n.doubleValue());
        else if (rainfall != null) try { wc.setRainfall14d(Double.valueOf(String.valueOf(rainfall))); } catch (NumberFormatException ignored) { }
        Object rainfall24h = weather.get("rainfallForecast24h");
        if (rainfall24h instanceof Number n) wc.setRainfallForecast24h(n.doubleValue());
        else if (rainfall24h != null) try { wc.setRainfallForecast24h(Double.valueOf(String.valueOf(rainfall24h))); } catch (NumberFormatException ignored) { }
        Object probability24h = weather.get("rainfallProbability24h");
        if (probability24h instanceof Number n) wc.setRainfallProbability24h(n.intValue());
        else if (probability24h != null) try { wc.setRainfallProbability24h(Integer.valueOf(String.valueOf(probability24h))); } catch (NumberFormatException ignored) { }
        wc.setRainfallForecast(Boolean.TRUE.equals(weather.get("rainfallForecast")));
        Object forecastDays = weather.get("rainfallForecastDays");
        if (forecastDays instanceof Number n) wc.setRainfallForecastDays(n.intValue());
        else if (forecastDays != null) try { wc.setRainfallForecastDays(Integer.valueOf(String.valueOf(forecastDays))); } catch (NumberFormatException ignored) { }
        Object forecast = weather.get("forecast");
        if (forecast instanceof List<?> items) {
            wc.setForecast(items.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> {
                        Map<String, Object> copy = new LinkedHashMap<>();
                        copy.putAll((Map<String, Object>) item);
                        return copy;
                    })
                    .toList());
        }
        wc.setSummary(string(weather.get("summary"))); wc.setTemperature(string(weather.get("temperature"))); wc.setObservedAt(string(weather.get("observedAt")));
        out.setWeather(wc); out.setQuery(request.getGoal());
        Map<String, Object> requestContext = new LinkedHashMap<>();
        requestContext.put("agentRunId", request.getRunId());
        if (c.getDiagnosisId() != null && !c.getDiagnosisId().isBlank()) requestContext.put("diagnosisId", c.getDiagnosisId());
        if (c.getSourceRecommendationId() != null && !c.getSourceRecommendationId().isBlank()) requestContext.put("sourceRecommendationId", c.getSourceRecommendationId());
        requestContext.put("fieldFactsStatus", c.getFieldFactsStatus());
        requestContext.put("serverDate", c.getFieldFactsDate());
        requestContext.put("crop", c.getCrop()); requestContext.put("cropLabel", c.getCropLabel());
        requestContext.put("currentPhenologyStage", c.getPhenologyStage()); requestContext.put("currentPhenologyStageLabel", c.getPhenologyStageLabel());
        requestContext.put("registeredPhenologyStage", c.getRegisteredPhenologyStage()); requestContext.put("registeredPhenologyStageLabel", c.getRegisteredPhenologyStageLabel());
        requestContext.put("dateDerivedPhenologyStage", c.getDateDerivedPhenologyStage()); requestContext.put("dateDerivedPhenologyStageLabel", c.getDateDerivedPhenologyStageLabel());
        requestContext.put("previousPhenologyStage", c.getPreviousPhenologyStage()); requestContext.put("previousPhenologyStageLabel", c.getPreviousPhenologyStageLabel());
        requestContext.put("nextPhenologyStage", c.getNextPhenologyStage()); requestContext.put("nextPhenologyStageLabel", c.getNextPhenologyStageLabel());
        requestContext.put("daysUntilNextPhenologyStage", c.getDaysUntilNextPhenologyStage());
        requestContext.put("phenologyStageMismatch", c.isPhenologyStageMismatch()); requestContext.put("phenologyCycleEnded", c.isPhenologyCycleEnded());
        requestContext.put("phenologyCycleType", c.getPhenologyCycleType()); requestContext.put("orderedPhenologyStages", c.getOrderedPhenologyStages());
        requestContext.put("factSources", c.getFactSources());
        out.setContext(requestContext);
        out.setTopK(null); out.setModelPolicy("default");
        return out;
    }
    private static String string(Object value) { return value == null ? null : String.valueOf(value); }
}
