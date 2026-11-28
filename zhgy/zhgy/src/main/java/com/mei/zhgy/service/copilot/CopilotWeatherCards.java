package com.mei.zhgy.service.copilot;

import com.mei.zhgy.vo.CurrentWeatherResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Projects verified weather data to the card contract shared by direct and Pi tool reads. */
final class CopilotWeatherCards {
    private CopilotWeatherCards() {}

    static List<CopilotPresentationCard> from(CurrentWeatherResponse weather, boolean showTrend) {
        String source = switch (weather.provider() == null ? "" : weather.provider().trim().toUpperCase(Locale.ROOT)) {
            case "AMAP", "GAODE" -> "高德气象服务";
            case "OPEN_METEO" -> "Open-Meteo 气象服务";
            default -> "气象服务";
        };
        List<Map<String, Object>> sources = List.of(Map.of("label", source));
        var forecast = weather.forecast() == null ? List.<com.mei.zhgy.vo.WeatherForecastDay>of() : weather.forecast();
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("farmName", weather.farmName()); snapshot.put("observedAt", weather.observedAt());
        snapshot.put("condition", weather.condition()); snapshot.put("temperature", weather.temperature());
        snapshot.put("humidity", weather.humidity()); snapshot.put("windDirection", weather.windDirection());
        snapshot.put("windPower", weather.windPower()); snapshot.put("source", source);
        snapshot.put("forecast", forecast.stream().limit(7).toList());
        var metrics = new ArrayList<Map<String, Object>>();
        add(metrics, "天气", weather.condition()); add(metrics, "温度", weather.temperature());
        add(metrics, "湿度", weather.humidity()); add(metrics, "风向", weather.windDirection()); add(metrics, "风力", weather.windPower());
        var weatherCard = new CopilotPresentationCard("WeatherCard", "当前天气", null, Map.of("metrics", metrics, "weather", snapshot),
                "READY".equals(weather.status()) ? "ready" : "unavailable", sources, "WeatherCard:farm:" + weather.farmId());
        if (!showTrend) return List.of(weatherCard);

        var probabilities = new ArrayList<Map<String, Object>>();
        var temperatures = new ArrayList<Map<String, Object>>();
        var rainfall = new ArrayList<Map<String, Object>>();
        for (var day : forecast) {
            if (day.date() == null) continue;
            if (day.precipitationProbability() != null) probabilities.add(Map.of("label", day.date(), "value", day.precipitationProbability()));
            var temperature = CopilotPiToolEvidence.validNumber(day.temperatureMax());
            if (temperature != null && Double.isFinite(temperature)) temperatures.add(Map.of("label", day.date(), "value", temperature));
            if (day.precipitationMm() != null) rainfall.add(Map.of("label", day.date(), "value", day.precipitationMm()));
        }
        if (!temperatures.isEmpty() && !rainfall.isEmpty()) return List.of(weatherCard,
                new CopilotPresentationCard("EChartsAnalyticsCard", "气温与降水预报", null,
                        Map.of("chartKind", "dual-axis", "series", List.of(
                                CopilotUiBlockValidator.chartSeries("dual-axis", "最高气温", temperatures),
                                CopilotUiBlockValidator.chartSeries("dual-axis", "降水量", rainfall))),
                        "ready", sources, "EChartsAnalyticsCard:weather:" + weather.farmId()));
        if (probabilities.isEmpty()) return List.of(weatherCard);
        return List.of(weatherCard, new CopilotPresentationCard("WeatherTrendChart", "降雨概率预报", "每日预报，单位：%",
                Map.of("series", List.of(Map.of("label", "降雨概率 (%)", "points", probabilities))),
                "ready", sources, "WeatherTrendChart:farm:" + weather.farmId()));
    }

    private static void add(List<Map<String, Object>> metrics, String label, String value) {
        if (value != null && !value.isBlank()) metrics.add(Map.of("label", label, "value", value));
    }
}
