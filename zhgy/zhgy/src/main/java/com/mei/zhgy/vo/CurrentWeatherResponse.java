package com.mei.zhgy.vo;

import java.time.Instant;
import java.util.List;

/**
 * Typed current-weather read contract. Seasonal prose and historical
 * temperature are deliberately not represented here. Forecast fields are
 * provider observations/predictions and are safe to pass into Agent context.
 */
public record CurrentWeatherResponse(
        Integer farmId,
        String farmName,
        String status,
        String temperature,
        String condition,
        String humidity,
        String windDirection,
        String windPower,
        String observedAt,
        Instant retrievedAt,
        String provider,
        String source,
        String freshness,
        String reason,
        Double rainfall14d,
        Double rainfallForecast24h,
        Integer rainfallProbability24h,
        Boolean rainfallForecast,
        Integer rainfallForecastDays,
        List<WeatherForecastDay> forecast
) {}
