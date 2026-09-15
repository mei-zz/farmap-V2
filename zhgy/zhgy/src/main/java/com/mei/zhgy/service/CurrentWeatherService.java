package com.mei.zhgy.service;

import com.mei.zhgy.service.field.FarmFieldService;
import com.mei.zhgy.vo.CurrentWeatherResponse;
import com.mei.zhgy.vo.UserLoginResponseVO;
import org.springframework.stereotype.Service;

import java.time.Instant;

/** Owner-scoped live weather adapter with a free keyless fallback provider. */
@Service
public class CurrentWeatherService {
    private final FarmFieldService fields;
    private final WeatherApiService weatherApi;

    public CurrentWeatherService(FarmFieldService fields, WeatherApiService weatherApi) {
        this.fields = fields;
        this.weatherApi = weatherApi;
    }

    public CurrentWeatherResponse get(Integer farmId, Long actorId) {
        UserLoginResponseVO.FarmVO farm = fields.requireFarm(farmId, actorId);
        Instant retrievedAt = Instant.now();
        if (!weatherApi.isConfigured()) {
            return unavailable(farm, "NOT_CONFIGURED", "legacy provider configuration is missing", retrievedAt);
        }

        double[] center = parseCenter(farm.getCenter());
        if (center == null) {
            return unavailable(farm, "CONFIGURED_UNAVAILABLE", "farm center is not configured for provider lookup", retrievedAt);
        }

        WeatherApiService.WeatherSnapshot snapshot = weatherApi.getWeather(center[0], center[1]);
        if (snapshot == null) {
            return unavailable(farm, "CONFIGURED_UNAVAILABLE", "weather provider returned no current observation", retrievedAt);
        }
        return new CurrentWeatherResponse(
                farm.getId(), farm.getName(), "READY", snapshot.temperature(), snapshot.condition(),
                snapshot.humidity(), snapshot.windDirection(), snapshot.windPower(), snapshot.observedAt(),
                retrievedAt, snapshot.provider(), snapshot.source(), snapshot.freshness(), null,
                snapshot.rainfall14d(), snapshot.rainfallForecast24h(), snapshot.rainfallProbability24h(),
                snapshot.rainfallForecast(), snapshot.rainfallForecastDays(), snapshot.forecast());
    }

    private CurrentWeatherResponse unavailable(UserLoginResponseVO.FarmVO farm, String status,
                                               String reason, Instant retrievedAt) {
        return new CurrentWeatherResponse(farm.getId(), farm.getName(), status, null, null, null, null, null,
                null, retrievedAt, "OPEN_METEO", "OPEN_METEO_FORECAST", "no current observation", reason,
                null, null, null, null, null, java.util.List.of());
    }

    private double[] parseCenter(String center) {
        if (center == null || center.isBlank()) return null;
        try {
            String[] parts = center.split(",");
            if (parts.length < 2) return null;
            double longitude = Double.parseDouble(parts[0].trim());
            double latitude = Double.parseDouble(parts[1].trim());
            return Double.isFinite(longitude) && Double.isFinite(latitude) ? new double[]{longitude, latitude} : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
