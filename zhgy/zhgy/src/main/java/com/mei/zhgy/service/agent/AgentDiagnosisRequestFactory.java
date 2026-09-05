package com.mei.zhgy.service.agent;

import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
        wc.setSummary(string(weather.get("summary"))); wc.setTemperature(string(weather.get("temperature"))); wc.setObservedAt(string(weather.get("observedAt")));
        out.setWeather(wc); out.setQuery(request.getGoal()); out.setContext(Map.of("agentRunId", request.getRunId()));
        out.setTopK(5); out.setModelPolicy("default");
        return out;
    }
    private static String string(Object value) { return value == null ? null : String.valueOf(value); }
}
