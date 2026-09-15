package com.mei.zhgy.service;

import com.mei.zhgy.entity.Guidance;
import com.mei.zhgy.entity.Weather;
import com.mei.zhgy.mapper.GuidanceMapper;
import com.mei.zhgy.mapper.WeatherMapper;
import com.mei.zhgy.service.field.FieldAccessException;
import com.mei.zhgy.service.field.FarmField;
import com.mei.zhgy.service.field.FarmFieldService;
import com.mei.zhgy.vo.AgriculturalContextResponse;
import com.mei.zhgy.vo.CurrentWeatherResponse;
import com.mei.zhgy.vo.UserLoginResponseVO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Actor-scoped read contract for existing agricultural facts. No fallback by
 * farm type is used here: REAL farm data must stay attached to its farm ID.
 */
@Service
public class AgriculturalContextService {
    private final JdbcTemplate jdbc;
    private final FarmFieldService fields;
    private final WeatherMapper weatherMapper;
    private final GuidanceMapper guidanceMapper;
    private final CurrentWeatherService currentWeatherService;

    public AgriculturalContextService(JdbcTemplate jdbc,
                                      FarmFieldService fields,
                                      WeatherMapper weatherMapper,
                                      GuidanceMapper guidanceMapper,
                                      CurrentWeatherService currentWeatherService) {
        this.jdbc = jdbc;
        this.fields = fields;
        this.weatherMapper = weatherMapper;
        this.guidanceMapper = guidanceMapper;
        this.currentWeatherService = currentWeatherService;
    }

    public AgriculturalContextResponse get(Integer farmId, Long actorId, Integer month,
                                            Integer temperatureLimit, Integer diagnosisLimit,
                                            String fieldId) {
        UserLoginResponseVO.FarmVO farm = fields.requireFarm(farmId, actorId);
        validateMonth(month);
        int resolvedMonth = month == null ? LocalDate.now().getMonthValue() - 1 : month;
        int boundedTemperatureLimit = boundLimit(temperatureLimit, 90, 180);
        int boundedDiagnosisLimit = boundLimit(diagnosisLimit, 5, 20);
        validateField(farmId, actorId, fieldId);

        AgriculturalContextResponse.SeasonalAgriculturalContext seasonal = seasonal(farm, resolvedMonth);
        AgriculturalContextResponse.HistoricalTemperatureSeries temperature = historicalTemperature(farmId, boundedTemperatureLimit);
        AgriculturalContextResponse.FarmGuidanceContext guidance = guidance(farmId, resolvedMonth);
        AgriculturalContextResponse.CropObservationSummary crops = cropObservations(farmId);
        AgriculturalContextResponse.DiagnosisListContext diagnoses = diagnoses(farmId, actorId, fieldId, null, boundedDiagnosisLimit);
        CurrentWeatherResponse currentWeather = currentWeatherService.get(farmId, actorId);
        int mapCount = countMapPoints(farmId);
        int fieldCount = fields.list(farmId, actorId).size();
        int operationCount = countOperations(farmId, actorId);
        AgriculturalContextResponse.DataAvailability availability = new AgriculturalContextResponse.DataAvailability(
                mapCount > 0 ? "READY" : "EMPTY",
                fieldCount > 0 ? "READY" : "EMPTY",
                seasonal.status(),
                temperature.status(),
                guidance.status(),
                crops.status(),
                diagnoses.status(),
                operationCount > 0 ? "READY" : "EMPTY",
                "NOT_CONFIGURED",
                "UNAVAILABLE",
                currentWeather.status(),
                "NOT_CONFIGURED",
                "NOT_CONFIGURED"
        );
        List<String> availableSignals = new ArrayList<>();
        if (crops.status().equals("READY")) availableSignals.add("cropObservations");
        if (diagnoses.status().equals("READY")) availableSignals.add("diagnosisList");
        if (temperature.status().equals("READY")) availableSignals.add("historicalTemperature");
        if (guidance.status().equals("READY")) availableSignals.add("guidance");
        if (seasonal.status().equals("READY")) availableSignals.add("seasonalContext");
        if (availability.operations().equals("READY")) availableSignals.add("operations");
        if ("READY".equals(currentWeather.status())) availableSignals.add("realtimeWeather");
        List<String> missingSignals = new ArrayList<>();
        if (!"READY".equals(currentWeather.status())) missingSignals.add("realtimeWeather");
        missingSignals.addAll(List.of("sensor", "soil", "vision"));
        if (fieldCount == 0) missingSignals.add("stableField");
        AgriculturalContextResponse.HealthPrerequisiteStatus healthPrerequisites =
                new AgriculturalContextResponse.HealthPrerequisiteStatus(
                        "NOT_IMPLEMENTED", availableSignals, missingSignals,
                        "Health/risk aggregate is intentionally deferred; these are prerequisite signals only");
        return new AgriculturalContextResponse(farmId, seasonal, temperature, guidance, crops, diagnoses, currentWeather,
                availability, healthPrerequisites, Instant.now());
    }

    public AgriculturalContextResponse.SeasonalAgriculturalContext seasonal(Integer farmId, Long actorId, Integer month) {
        UserLoginResponseVO.FarmVO farm = fields.requireFarm(farmId, actorId);
        validateMonth(month);
        return seasonal(farm, month == null ? LocalDate.now().getMonthValue() - 1 : month);
    }

    public AgriculturalContextResponse.HistoricalTemperatureSeries historicalTemperature(Integer farmId, Long actorId, Integer limit) {
        fields.requireFarm(farmId, actorId);
        return historicalTemperature(farmId, boundLimit(limit, 90, 180));
    }

    public AgriculturalContextResponse.FarmGuidanceContext guidance(Integer farmId, Long actorId, Integer month) {
        fields.requireFarm(farmId, actorId);
        validateMonth(month);
        return guidance(farmId, month == null ? LocalDate.now().getMonthValue() - 1 : month);
    }

    public AgriculturalContextResponse.CropObservationSummary cropObservations(Integer farmId, Long actorId) {
        fields.requireFarm(farmId, actorId);
        return cropObservations(farmId);
    }

    public AgriculturalContextResponse.DiagnosisListContext diagnoses(Integer farmId, Long actorId, String fieldId,
                                                                       String status, Integer limit) {
        fields.requireFarm(farmId, actorId);
        validateField(farmId, actorId, fieldId);
        return diagnoses(farmId, actorId, fieldId, status, boundLimit(limit, 5, 20));
    }

    private AgriculturalContextResponse.SeasonalAgriculturalContext seasonal(UserLoginResponseVO.FarmVO farm, int month) {
        List<Weather> rows = weatherMapper.getByFarmIdAndMonth(farm.getId(), month);
        List<AgriculturalContextResponse.SeasonalEntry> entries = rows == null ? List.of() : rows.stream()
                .filter(row -> row != null && row.getText() != null && !row.getText().isBlank())
                .map(row -> new AgriculturalContextResponse.SeasonalEntry(row.getId(), row.getMonth(), row.getText()))
                .toList();
        String status = entries.isEmpty() ? "EMPTY" : "READY";
        String summary = entries.stream().map(AgriculturalContextResponse.SeasonalEntry::text).reduce((left, right) -> left + "\n\n" + right).orElse(null);
        return new AgriculturalContextResponse.SeasonalAgriculturalContext(
                farm.getId(), month, farm.getType(), summary, "MYSQL", status,
                "weather.month+text; seasonal agronomic/climate context, not live weather",
                entries);
    }

    private AgriculturalContextResponse.HistoricalTemperatureSeries historicalTemperature(Integer farmId, int limit) {
        Map<String, Object> aggregate = jdbc.queryForMap(
                "SELECT COUNT(*) AS observation_count, MIN(date) AS start_date, MAX(date) AS end_date " +
                        "FROM accumulated_temperature WHERE farm_id=?", farmId);
        int count = ((Number) aggregate.getOrDefault("observation_count", 0)).intValue();
        LocalDate start = localDate(aggregate.get("start_date"));
        LocalDate end = localDate(aggregate.get("end_date"));
        List<AgriculturalContextResponse.TemperaturePoint> descending = jdbc.query(
                "SELECT date,temperature,accumulated_temp FROM accumulated_temperature WHERE farm_id=? " +
                        "ORDER BY date DESC LIMIT " + limit,
                (ResultSet rs, int rowNum) -> new AgriculturalContextResponse.TemperaturePoint(
                        rs.getDate("date") == null ? null : rs.getDate("date").toLocalDate(),
                        rs.getBigDecimal("temperature"), rs.getBigDecimal("accumulated_temp")), farmId);
        LocalDate latestDate = descending.isEmpty() ? null : descending.get(0).date();
        BigDecimal latestTemperature = descending.isEmpty() ? null : descending.get(0).temperature();
        BigDecimal latestAccumulated = descending.isEmpty() ? null : descending.get(0).accumulatedTemperature();
        Collections.reverse(descending);
        return new AgriculturalContextResponse.HistoricalTemperatureSeries(
                farmId, count == 0 ? "EMPTY" : "READY", start, end, latestDate,
                latestTemperature, latestAccumulated, count, descending.size(), limit,
                "MYSQL", "accumulated_temperature.date/temperature/accumulated_temp; historical only",
                descending);
    }

    private AgriculturalContextResponse.FarmGuidanceContext guidance(Integer farmId, int month) {
        List<Guidance> rows = guidanceMapper.getByFarmIdAndMonth(farmId, month);
        List<AgriculturalContextResponse.GuidanceEntry> entries = rows == null ? List.of() : rows.stream()
                .filter(row -> row != null && row.getText() != null && !row.getText().isBlank())
                .map(row -> new AgriculturalContextResponse.GuidanceEntry(row.getId(), row.getMonth(), row.getGuidanceType(), row.getText(), row.getIsFormula()))
                .toList();
        return new AgriculturalContextResponse.FarmGuidanceContext(
                farmId, month, entries.isEmpty() ? "EMPTY" : "READY", entries.size(), "MYSQL",
                "guidances.farm_id+month+guidance_type+text+is_formula; no farmType fallback", entries);
    }

    private AgriculturalContextResponse.CropObservationSummary cropObservations(Integer farmId) {
        List<CropObservationRow> rows = jdbc.query(
                "SELECT observed_date,crop,phenology,nitrogen,potassium,phosphorus,yield_value,disease,diseases,severity,image_url " +
                        "FROM farm_map_points WHERE farm_id=? AND point_kind='CROP_OBSERVATION' AND status='ACTIVE' " +
                        "ORDER BY observed_date,source_legacy_id",
                (ResultSet rs, int rowNum) -> new CropObservationRow(
                        rs.getDate("observed_date") == null ? null : rs.getDate("observed_date").toLocalDate(),
                        rs.getString("crop"), rs.getObject("phenology"), rs.getObject("nitrogen"),
                        rs.getObject("potassium"), rs.getObject("phosphorus"), rs.getObject("yield_value"),
                        rs.getObject("disease"), rs.getString("diseases"), rs.getObject("severity"), rs.getString("image_url")), farmId);
        Set<String> cropTypes = new LinkedHashSet<>();
        Map<String, Integer> phenology = new LinkedHashMap<>();
        LocalDate start = null;
        LocalDate end = null;
        LocalDate latest = null;
        int validYield = 0;
        int validNutrients = 0;
        int diseaseSignals = 0;
        int images = 0;
        for (CropObservationRow row : rows) {
            if (meaningful(row.crop())) cropTypes.add(row.crop().trim());
            if (row.observedDate() != null) {
                if (start == null || row.observedDate().isBefore(start)) start = row.observedDate();
                if (end == null || row.observedDate().isAfter(end)) end = row.observedDate();
                if (latest == null || row.observedDate().isAfter(latest)) latest = row.observedDate();
            }
            if (row.phenology() != null) phenology.merge(String.valueOf(row.phenology()), 1, Integer::sum);
            if (positive(row.yieldValue())) validYield++;
            if (positive(row.nitrogen()) || positive(row.potassium()) || positive(row.phosphorus())) validNutrients++;
            if (meaningful(row.disease()) || meaningful(row.diseases()) || meaningful(row.severity())) diseaseSignals++;
            if (meaningful(row.imageUrl())) images++;
        }
        return new AgriculturalContextResponse.CropObservationSummary(
                farmId, rows.isEmpty() ? "EMPTY" : "READY", rows.size(), start, end, latest,
                List.copyOf(cropTypes), phenology, validYield, validNutrients, diseaseSignals, images,
                "MYSQL", "farm_map_points.CROP_OBSERVATION; descriptive summary only, no health inference");
    }

    private AgriculturalContextResponse.DiagnosisListContext diagnoses(Integer farmId, Long actorId, String fieldId,
                                                                        String status, int limit) {
        StringBuilder sql = new StringBuilder("SELECT diagnosis_id,source_run_id,farm_id,field_id,crop,status,risk_level,confidence,summary,mode,created_at FROM agent_diagnoses WHERE user_id=? AND farm_id=?");
        List<Object> params = new ArrayList<>();
        params.add(actorId);
        params.add(farmId);
        if (fieldId != null && !fieldId.isBlank()) {
            sql.append(" AND field_id=?");
            params.add(fieldId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status=?");
            params.add(status.trim().toUpperCase());
        }
        sql.append(" ORDER BY created_at DESC,diagnosis_id DESC LIMIT ").append(limit);
        List<AgriculturalContextResponse.DiagnosisSummary> rows = jdbc.query(sql.toString(), (ResultSet rs, int rowNum) -> {
            Timestamp createdAt = rs.getTimestamp("created_at");
            return new AgriculturalContextResponse.DiagnosisSummary(
                    rs.getString("diagnosis_id"), rs.getString("source_run_id"), rs.getObject("farm_id", Integer.class),
                    rs.getString("field_id"), rs.getString("crop"), rs.getString("status"), rs.getString("risk_level"),
                    (Double) rs.getObject("confidence"), rs.getString("summary"), rs.getString("mode"),
                    createdAt == null ? null : createdAt.toInstant());
        }, params.toArray());
        return new AgriculturalContextResponse.DiagnosisListContext(
                farmId, fieldId, rows.isEmpty() ? "EMPTY" : "READY", rows.size(), limit,
                "MYSQL", "agent_diagnoses.user_id+farm_id+field_id; list is not authoritative selection", rows);
    }

    private void validateField(Integer farmId, Long actorId, String fieldId) {
        if (fieldId == null || fieldId.isBlank()) return;
        FarmField field = fields.get(fieldId, actorId);
        if (!farmId.equals(field.getFarmId())) {
            throw new FieldAccessException(403, "FARM_ACCESS_DENIED", "field does not belong to the requested farm");
        }
    }

    private int countMapPoints(Integer farmId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM farm_map_points WHERE farm_id=? AND status='ACTIVE'", Integer.class, farmId);
        return count == null ? 0 : count;
    }

    private int countOperations(Integer farmId, Long actorId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_operations_tasks t " +
                        "JOIN farm_fields f ON f.field_id=t.field_id " +
                        "WHERE t.owner_user_id=? AND f.farm_id=? AND f.owner_user_id=? AND f.status='ACTIVE'",
                Integer.class, actorId, farmId, actorId);
        return count == null ? 0 : count;
    }

    private void validateMonth(Integer month) {
        if (month != null && (month < 0 || month > 11)) {
            throw new FieldAccessException(400, "MONTH_INVALID", "month must be between 0 and 11");
        }
    }

    private int boundLimit(Integer requested, int defaultValue, int maxValue) {
        int value = requested == null ? defaultValue : requested;
        if (value < 1) throw new FieldAccessException(400, "LIMIT_INVALID", "limit must be positive");
        return Math.min(value, maxValue);
    }

    private static LocalDate localDate(Object value) {
        if (value instanceof java.sql.Date date) return date.toLocalDate();
        if (value instanceof LocalDate date) return date;
        return null;
    }

    private static boolean positive(Object value) {
        return value instanceof Number number && Double.isFinite(number.doubleValue()) && number.doubleValue() > 0;
    }

    private static boolean meaningful(Object value) {
        if (value == null) return false;
        if (value instanceof Number) return positive(value);
        String normalized = String.valueOf(value).trim().toLowerCase();
        return !normalized.isEmpty() && !Set.of("0", "0.0", "0.00", "null", "none", "normal", "false", "无", "未发现").contains(normalized);
    }

    private record CropObservationRow(LocalDate observedDate, String crop, Object phenology, Object nitrogen,
                                      Object potassium, Object phosphorus, Object yieldValue, Object disease,
                                      String diseases, Object severity, String imageUrl) {}
}
