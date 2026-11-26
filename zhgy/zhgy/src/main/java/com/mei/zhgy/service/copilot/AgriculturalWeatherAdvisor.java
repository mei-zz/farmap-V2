package com.mei.zhgy.service.copilot;

import com.mei.zhgy.service.CurrentWeatherService;
import com.mei.zhgy.service.WeatherApiService.ForecastHour;
import com.mei.zhgy.service.copilot.PlumAgronomyRuleEngine.Provenance;
import com.mei.zhgy.service.copilot.PlumAgronomyRuleEngine.SourceKind;
import com.mei.zhgy.service.copilot.PlumAgronomyRuleEngine.SprayAssessmentRequest;
import com.mei.zhgy.service.copilot.PlumAgronomyRuleEngine.SprayWeatherWindow;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts validated forecasts to weather-only observations for the agronomy engine. */
@Service
public class AgriculturalWeatherAdvisor {
    static final ZoneId FARM_ZONE = ZoneId.of("Asia/Shanghai");
    private static final long RAIN_LOOKAHEAD_HOURS = 6;
    private final CurrentWeatherService weather;
    private final PlumAgronomyRuleEngine rules;
    private final PlumAgronomyEvidenceCatalog evidence;

    public AgriculturalWeatherAdvisor(CurrentWeatherService weather, PlumAgronomyRuleEngine rules, PlumAgronomyEvidenceCatalog evidence) {
        this.weather = weather;
        this.rules = rules;
        this.evidence = evidence;
    }

    public Map<String, Object> forecast(Integer farmId, Long actor, LocalDate requestedDate,
                                        LocalDate expectedHarvestDate, String phenologyCode,
                                        String fieldId, String cropCode, String productId) {
        Instant now = Instant.now();
        List<SprayWeatherWindow> windows = windows(weather.getHourlyForecast(farmId, actor),
                LocalDateTime.ofInstant(now, FARM_ZONE), now);
        if (requestedDate != null) windows = windows.stream()
                .filter(window -> requestedDate.equals(window.startsAt().toLocalDate())).toList();
        LocalDate plannedDate = requestedDate == null ? now.atZone(FARM_ZONE).toLocalDate() : requestedDate;
        var authoritative = evidence.spray(farmId, fieldId, cropCode, productId, plannedDate);
        LocalDate harvest = authoritative.expectedHarvestDate();
        if (harvest == null || expectedHarvestDate != null && expectedHarvestDate.isBefore(harvest)) harvest = expectedHarvestDate;
        var assessment = rules.assessSpray(new SprayAssessmentRequest(
                plannedDate, harvest, phenologyCode, authoritative.label(), authoritative.certification(), windows));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", windows.isEmpty() ? "HOURLY_FORECAST_UNAVAILABLE" : "READY");
        result.put("source", "Open-Meteo");
        result.put("ruleVersion", assessment.ruleVersion());
        result.put("bestWeatherWindow", assessment.bestWindow());
        result.put("weatherWindows", assessment.windows().stream().filter(item -> item.score() != null)
                .sorted(java.util.Comparator.comparing(PlumAgronomyRuleEngine.SprayWindowAssessment::score).reversed()
                        .thenComparing(PlumAgronomyRuleEngine.SprayWindowAssessment::startsAt)).limit(8).toList());
        result.put("operationWindows", operationWindows(assessment.windows()));
        result.put("operationDecision", assessment.decision());
        result.put("useRestrictionsVerified", assessment.hardRestrictionsPassed());
        result.put("labelProvenance", assessment.labelProvenance());
        result.put("phiDays", assessment.phiDays());
        result.put("certificate", assessment.certificationStatus());
        if (authoritative.label() != null) {
            result.put("verifiedProductId", authoritative.label().productId());
            result.put("verifiedProductName", authoritative.label().productName());
        }
        if (requestedDate != null) result.put("requestedDate", requestedDate.toString());
        result.put("findings", assessment.findings());
        result.put("unavailableData", assessment.unavailableData().stream().limit(14).toList());
        result.put("rainHorizonHours", RAIN_LOOKAHEAD_HOURS);
        return result;
    }

    private static List<Map<String, Object>> operationWindows(List<PlumAgronomyRuleEngine.SprayWindowAssessment> windows) {
        List<Map<String, Object>> samples = new ArrayList<>();
        for (int index = 0; index < windows.size() && samples.size() < 24; index += 3) {
            var window = windows.get(index);
            if (window.startsAt() == null) continue;
            String spray = switch (window.suitability()) {
                case SUITABLE -> "SUITABLE";
                case CAUTION -> "CAUTION";
                case UNSUITABLE -> "UNSUITABLE";
                default -> "UNKNOWN";
            };
            Double temperature = window.airTemperatureC();
            Double probability = window.precipitationProbabilityPct();
            Double rain = window.precipitationMm();
            boolean complete = temperature != null && probability != null && rain != null;
            String fertigation = !complete ? "UNKNOWN" : probability >= 70 || rain >= 5 ? "UNSUITABLE"
                    : temperature >= 30 || probability >= 45 || rain > 0 ? "CAUTION" : "SUITABLE";
            String harvest = !complete ? "UNKNOWN" : rain >= 2 || probability >= 70 ? "UNSUITABLE"
                    : probability >= 40 || rain > 0 ? "CAUTION" : "SUITABLE";
            var entry = new LinkedHashMap<String, Object>();
            entry.put("startsAt", window.startsAt().toString());
            entry.put("spray", spray);
            entry.put("fertigation", fertigation);
            entry.put("harvest", harvest);
            entry.put("airTemperatureC", temperature);
            entry.put("precipitationProbabilityPct", probability);
            entry.put("windSpeedKph", window.windSpeedKph());
            samples.add(entry);
        }
        return List.copyOf(samples);
    }

    static List<SprayWeatherWindow> windows(List<ForecastHour> hours, LocalDateTime from, Instant retrievedAt) {
        Map<LocalDateTime, ForecastHour> byTime = new LinkedHashMap<>();
        for (ForecastHour hour : hours) if (hour != null && hour.time() != null) byTime.putIfAbsent(hour.time(), hour);
        var provenance = new Provenance(SourceKind.WEATHER_FORECAST, "https://open-meteo.com/en/docs",
                "forecast-v1", retrievedAt, true, true);
        return byTime.values().stream().filter(hour -> !hour.time().isBefore(from) && hour.time().isBefore(from.plusHours(72)))
                .map(hour -> window(hour, byTime, provenance)).toList();
    }

    private static SprayWeatherWindow window(ForecastHour start, Map<LocalDateTime, ForecastHour> hours, Provenance provenance) {
        List<ForecastHour> next = new ArrayList<>();
        for (long i = 1; i <= RAIN_LOOKAHEAD_HOURS; i++) next.add(hours.get(start.time().plusHours(i)));
        Double rain = null;
        Double probability = null;
        if (next.stream().allMatch(hour -> hour != null && hour.rainMm() != null && Double.isFinite(hour.rainMm()) && hour.rainMm() >= 0)) {
            rain = next.stream().mapToDouble(ForecastHour::rainMm).sum();
        }
        if (next.stream().allMatch(hour -> hour != null && hour.rainProbabilityPct() != null && Double.isFinite(hour.rainProbabilityPct())
                && hour.rainProbabilityPct() >= 0 && hour.rainProbabilityPct() <= 100)) {
            probability = next.stream().mapToDouble(ForecastHour::rainProbabilityPct).max().orElseThrow();
        }
        return new SprayWeatherWindow(start.time().toString(), start.time(), start.time().plusHours(1),
                start.temperatureC(), start.humidityPct(), start.windKph(), rain, probability, provenance);
    }
}
