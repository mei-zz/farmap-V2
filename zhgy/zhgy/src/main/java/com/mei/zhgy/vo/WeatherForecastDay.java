package com.mei.zhgy.vo;

/** Daily forecast facts exposed to the UI and the agent context. */
public record WeatherForecastDay(
        String date,
        String condition,
        String temperatureMax,
        String temperatureMin,
        Double precipitationMm,
        Integer precipitationProbability,
        Integer weatherCode
) {}
