package com.mei.zhgy.service.copilot;

import com.mei.zhgy.service.WeatherApiService.ForecastHour;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class AgriculturalWeatherAdvisorTest {
    @Test
    void usesRainEndingInTheFollowingSixHoursAndPreservesMissingMeasurements() {
        var firstHour = LocalDateTime.of(2026, 11, 26, 8, 0);
        var retrieval = Instant.parse("2026-11-26T00:00:00Z");
        List<ForecastHour> forecast = IntStream.rangeClosed(0, 6)
                .mapToObj(index -> new ForecastHour(firstHour.plusHours(index), 18D, 65D, 10D,
                        index == 0 ? 100D : 1D, index * 10D)).toList();

        var windows = AgriculturalWeatherAdvisor.windows(forecast, firstHour, retrieval);
        assertEquals(6D, windows.get(0).precipitationMm());
        assertEquals(60D, windows.get(0).precipitationProbabilityPct());
        assertEquals(10D, windows.get(0).windSpeedKph());
        assertNull(windows.get(1).precipitationMm());
        assertNull(windows.get(1).precipitationProbabilityPct());
        assertTrue(windows.get(0).provenance().usable());
    }
}
