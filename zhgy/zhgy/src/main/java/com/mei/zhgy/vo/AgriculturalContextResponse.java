package com.mei.zhgy.vo;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Typed, read-only commercial context assembled from existing agricultural
 * facts. This is intentionally separate from the legacy Weather/Guidance
 * entities and never represents seasonal prose as live weather.
 */
public record AgriculturalContextResponse(
        Integer farmId,
        SeasonalAgriculturalContext seasonalContext,
        HistoricalTemperatureSeries historicalTemperature,
        FarmGuidanceContext guidance,
        CropObservationSummary cropObservations,
        DiagnosisListContext recentDiagnoses,
        CurrentWeatherResponse currentWeather,
        DataAvailability availability,
        HealthPrerequisiteStatus healthPrerequisites,
        Instant generatedAt
) {
    public record SeasonalAgriculturalContext(
            Integer farmId,
            Integer month,
            String farmType,
            String summary,
            String source,
            String status,
            String provenance,
            List<SeasonalEntry> entries
    ) {}

    public record SeasonalEntry(Integer id, Integer month, String text) {}

    public record HistoricalTemperatureSeries(
            Integer farmId,
            String status,
            LocalDate startDate,
            LocalDate endDate,
            LocalDate latestDate,
            BigDecimal latestTemperature,
            BigDecimal latestAccumulatedTemperature,
            int observationCount,
            int returnedCount,
            int limit,
            String source,
            String provenance,
            List<TemperaturePoint> series
    ) {}

    public record TemperaturePoint(
            LocalDate date,
            BigDecimal temperature,
            BigDecimal accumulatedTemperature
    ) {}

    public record FarmGuidanceContext(
            Integer farmId,
            Integer month,
            String status,
            int recordCount,
            String source,
            String provenance,
            List<GuidanceEntry> entries
    ) {}

    public record GuidanceEntry(
            Integer id,
            Integer month,
            String guidanceType,
            String text,
            Boolean formula
    ) {}

    public record CropObservationSummary(
            Integer farmId,
            String status,
            int totalObservations,
            LocalDate startDate,
            LocalDate endDate,
            LocalDate latestObservationDate,
            List<String> cropTypes,
            Map<String, Integer> phenologyDistribution,
            int validYieldCount,
            int validNutrientCount,
            int diseaseSignalCount,
            int imageCount,
            String source,
            String provenance
    ) {}

    public record DiagnosisListContext(
            Integer farmId,
            String fieldId,
            String status,
            int count,
            int limit,
            String source,
            String provenance,
            List<DiagnosisSummary> items
    ) {}

    public record DiagnosisSummary(
            String diagnosisId,
            String sourceRunId,
            Integer farmId,
            String fieldId,
            String crop,
            String status,
            String riskLevel,
            Double confidence,
            String summary,
            String mode,
            Instant createdAt
    ) {}

    public record DataAvailability(
            String map,
            String field,
            String seasonalContext,
            String historicalTemperature,
            String guidance,
            String cropObservations,
            String diagnosis,
            String operations,
            String realtimeCamera,
            String vision,
            String realtimeWeather,
            String sensor,
            String soil
    ) {}

    /** Status only; this never implies a computed health score. */
    public record HealthPrerequisiteStatus(
            String status,
            List<String> availableSignals,
            List<String> missingSignals,
            String provenance
    ) {}
}
