package com.mei.zhgy.service.field;

import com.mei.zhgy.mapper.FarmMapper;
import com.mei.zhgy.mapper.UserMapper;
import com.mei.zhgy.mapper.WeatherMapper;
import com.mei.zhgy.service.WeatherApiService;
import com.mei.zhgy.service.agriculture.CropResolver;
import com.mei.zhgy.service.agriculture.PhenologyService;
import com.mei.zhgy.vo.UserLoginResponseVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Minimal field master and trusted context assembler for Phase C. */
@Service
public class FarmFieldService {
    private final JdbcTemplate jdbc;
    private final FarmMapper farmMapper;
    private final WeatherMapper weatherMapper;
    private final UserMapper userMapper;
    private final WeatherApiService weatherApi;
    private final CropResolver cropResolver;
    private final PhenologyService phenologyService;
    private volatile boolean schemaChecked;

    public FarmFieldService(JdbcTemplate jdbc, FarmMapper farmMapper, WeatherMapper weatherMapper, UserMapper userMapper, WeatherApiService weatherApi) {
        this(jdbc, farmMapper, weatherMapper, userMapper, weatherApi, null, null);
    }

    @Autowired
    public FarmFieldService(JdbcTemplate jdbc, FarmMapper farmMapper, WeatherMapper weatherMapper, UserMapper userMapper, WeatherApiService weatherApi, CropResolver cropResolver, PhenologyService phenologyService) {
        this.jdbc = jdbc;
        this.farmMapper = farmMapper;
        this.weatherMapper = weatherMapper;
        this.userMapper = userMapper;
        this.weatherApi = weatherApi;
        this.cropResolver = cropResolver;
        this.phenologyService = phenologyService;
    }

    /** Compatibility constructor retained for focused unit tests. */
    public FarmFieldService(JdbcTemplate jdbc, FarmMapper farmMapper, WeatherMapper weatherMapper, UserMapper userMapper) {
        this(jdbc, farmMapper, weatherMapper, userMapper, null, null, null);
    }

    public List<FarmField> list(Integer farmId, Long actorId) {
        requireFarm(farmId, actorId);
        ensureSchema();
        return jdbc.query("SELECT field_id,farm_id,name,crop,area,growth_stage,latitude,longitude,status,source_mode,owner_user_id,created_at,updated_at FROM farm_fields WHERE farm_id=? ORDER BY name", this::map, farmId);
    }

    public FarmField create(Integer farmId, Long actorId, CreateFieldCommand command) {
        UserLoginResponseVO.FarmVO farm = requireFarm(farmId, actorId);
        if (command == null || command.name() == null || command.name().trim().isEmpty()) {
            throw new FieldAccessException(422, "FIELD_NAME_REQUIRED", "field name is required");
        }
        ensureSchema();
        String fieldId = "field-" + UUID.randomUUID();
        String crop = command.crop();
        if (crop != null && !crop.isBlank() && cropResolver != null) {
            CropResolver.ResolvedCrop resolved = cropResolver.resolve(crop);
            if (!resolved.ready()) throw new FieldAccessException(422, "UNKNOWN_CROP", "当前作物暂不在支持目录中，请使用脆李、柑橘或水稻");
            crop = resolved.code();
        }
        Instant now = Instant.now();
        Long owner = farm.getUserId() == null ? actorId : farm.getUserId().longValue();
        try {
            jdbc.update("INSERT INTO farm_fields(field_id,farm_id,name,crop,area,growth_stage,latitude,longitude,status,source_mode,owner_user_id,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    fieldId, farmId, command.name().trim(), crop, command.area(), command.growthStage(), command.latitude(), command.longitude(), "ACTIVE", "MYSQL", owner, Timestamp.from(now), Timestamp.from(now));
        } catch (RuntimeException error) {
            if (String.valueOf(error.getMessage()).toLowerCase().contains("duplicate")) {
                throw new FieldAccessException(409, "FIELD_NAME_CONFLICT", "field name already exists in this farm");
            }
            throw error;
        }
        return get(fieldId, actorId);
    }

    public FarmField get(String fieldId, Long actorId) {
        ensureSchema();
        List<FarmField> fields = jdbc.query("SELECT field_id,farm_id,name,crop,area,growth_stage,latitude,longitude,status,source_mode,owner_user_id,created_at,updated_at FROM farm_fields WHERE field_id=?", this::map, fieldId);
        if (fields.isEmpty()) throw new FieldAccessException(404, "FIELD_NOT_FOUND", "field not found");
        FarmField field = fields.get(0);
        requireFarm(field.getFarmId(), actorId);
        return field;
    }

    public FieldContext context(String fieldId, Long actorId) {
        FarmField field = get(fieldId, actorId);
        UserLoginResponseVO.FarmVO farm = requireFarm(field.getFarmId(), actorId);
        Instant freshness = field.getUpdatedAt() == null ? Instant.now() : field.getUpdatedAt();
        Map<String, Object> farmMap = mapOf("id", farm.getId(), "name", farm.getName(), "type", farm.getType(), "address", farm.getAddress(), "sourceMode", "MYSQL", "status", "READY");
        Map<String, Object> fieldMap = mapOf("fieldId", field.getFieldId(), "name", field.getName(), "status", field.getStatus(), "sourceMode", field.getSourceMode(), "area", field.getArea(), "crop", field.getCrop(), "growthStage", field.getGrowthStage());
        Map<String, Object> cropMap = mapOf("name", field.getCrop() == null ? farm.getType() : field.getCrop(), "sourceMode", field.getCrop() == null ? "MYSQL_FARM_SCOPE" : "MYSQL", "scope", field.getCrop() == null ? "FARM" : "FIELD", "status", "READY");
        Map<String, Object> location = location(field, farm);
        Map<String, Object> weather = weather(field.getFarmId());
        Map<String, Object> accumulated = accumulated(field.getFarmId());
        Map<String, Object> images = mapOf("status", "READY_EMPTY", "sourceMode", "MYSQL", "scope", "FARM", "count", 0, "reason", "legacy image records have no trusted field relation");
        Map<String, Object> unavailable = mapOf("status", "UNAVAILABLE", "sourceMode", "UNAVAILABLE", "reason", "Phase E provider or field binding is not configured");
        String status = "READY";
        if ("UNAVAILABLE".equals(weather.get("status")) && "UNAVAILABLE".equals(accumulated.get("status"))) status = "READY";
        return FieldContext.builder().farm(farmMap).field(fieldMap).crop(cropMap).location(location).weather(weather)
                .accumulatedTemperature(accumulated).images(images).soil(unavailable).gis(location).satellite(unavailable)
                .status(status).sourceMode("MYSQL").scope("FIELD").freshness(freshness).build();
    }

    public FieldContext applyToDiagnosisRequest(com.mei.zhgy.dto.DiagnosisAnalyzeRequest request, Long actorId) {
        // Restored canonical field IDs predate the "field-" prefix. Ownership
        // is enforced by get()/requireFarm(), so ID shape must not decide
        // whether trusted server context is assembled.
        if (request == null || request.getFieldId() == null || request.getFieldId().isBlank()) return null;
        String requestedPointId = request.getPointId();
        String requestedCameraId = request.getCameraId();
        FieldContext context = context(request.getFieldId(), actorId);
        request.setTrustedFieldContext(context);
        Object crop = context.getCrop().get("name");
        request.setFarmId(((Number) context.getFarm().get("id")).intValue());
        if (crop != null) {
            String rawCrop = String.valueOf(crop);
            CropResolver.ResolvedCrop resolved = cropResolver == null ? null : cropResolver.resolve(rawCrop);
            request.setCrop(resolved != null && resolved.ready() ? resolved.code() : rawCrop);
        }
        Object growthStage = context.getField().get("growthStage");
        String rawStage = growthStage == null ? null : String.valueOf(growthStage);
        if (phenologyService != null && request.getCrop() != null && rawStage != null) {
            var resolvedStage = phenologyService.resolveStage(request.getCrop(), rawStage);
            request.setGrowthStage(resolvedStage == null ? rawStage : resolvedStage.code());
        } else request.setGrowthStage(rawStage);
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("fieldContext", context);
        server.put("source", "SERVER_ASSEMBLED");
        if (requestedPointId != null && !requestedPointId.isBlank()) server.put("pointId", requestedPointId);
        if (requestedCameraId != null && !requestedCameraId.isBlank()) server.put("cameraId", requestedCameraId);
        if (request.getImageAssetIds() != null && !request.getImageAssetIds().isEmpty()) {
            server.put("imageAssetIds", request.getImageAssetIds());
        }
        request.setContext(server);
        Map<String, Object> weather = context.getWeather();
        request.setWeather(null);
        if ("READY".equals(weather.get("status"))) {
            com.mei.zhgy.dto.DiagnosisAnalyzeRequest.WeatherContext wc = new com.mei.zhgy.dto.DiagnosisAnalyzeRequest.WeatherContext();
            Object rainfall = weather.get("rainfall14d");
            wc.setRainfall14d(rainfall instanceof Number ? ((Number) rainfall).doubleValue() : 0D);
            Object rainfall24h = weather.get("rainfallForecast24h");
            wc.setRainfallForecast24h(rainfall24h instanceof Number ? ((Number) rainfall24h).doubleValue() : null);
            Object probability = weather.get("rainfallProbability24h");
            wc.setRainfallProbability24h(probability instanceof Number ? ((Number) probability).intValue() : null);
            wc.setRainfallForecast(Boolean.TRUE.equals(weather.get("rainfallForecast")));
            Object forecastDays = weather.get("rainfallForecastDays");
            wc.setRainfallForecastDays(forecastDays instanceof Number ? ((Number) forecastDays).intValue() : null);
            if (weather.get("forecast") instanceof List<?> forecast) {
                List<Map<String, Object>> safeForecast = forecast.stream().filter(Map.class::isInstance)
                        .map(item -> {
                            Map<String, Object> copy = new LinkedHashMap<>();
                            copy.putAll((Map<String, Object>) item);
                            return copy;
                        }).toList();
                wc.setForecast(safeForecast);
            }
            wc.setSummary(String.valueOf(weather.getOrDefault("summary", "当前天气数据")));
            wc.setTemperature(String.valueOf(weather.getOrDefault("temperature", "")));
            wc.setObservedAt(String.valueOf(weather.getOrDefault("observedAt", "")));
            request.setWeather(wc);
        }
        return context;
    }

    public UserLoginResponseVO.FarmVO requireFarm(Integer farmId, Long actorId) {
        if (farmId == null || actorId == null) throw new FieldAccessException(401, "UNAUTHENTICATED", "authentication required");
        UserLoginResponseVO.FarmVO farm = farmMapper.getFarmById(farmId);
        if (farm == null) throw new FieldAccessException(404, "FARM_NOT_FOUND", "farm not found");
        com.mei.zhgy.entity.User actor = userMapper == null ? null : userMapper.getById(actorId.intValue());
        boolean admin = actor != null && "admin".equalsIgnoreCase(actor.getRole());
        if (!admin && (farm.getUserId() == null || !actorId.equals(farm.getUserId().longValue()))) {
            throw new FieldAccessException(403, "FARM_ACCESS_DENIED", "farm is not owned by the current user");
        }
        return farm;
    }

    private Map<String, Object> location(FarmField field, UserLoginResponseVO.FarmVO farm) {
        Double longitude = field.getLongitude();
        Double latitude = field.getLatitude();
        String scope = "FIELD";
        if (longitude == null || latitude == null) {
            double[] center = parseCenter(farm.getCenter());
            if (center != null) { longitude = center[0]; latitude = center[1]; scope = "FARM"; }
        }
        return mapOf("status", longitude == null || latitude == null ? "UNAVAILABLE" : "READY", "sourceMode", "MYSQL", "scope", scope, "longitude", longitude, "latitude", latitude);
    }

    private Map<String, Object> weather(Integer farmId) {
        UserLoginResponseVO.FarmVO farm = farmMapper.getFarmById(farmId);
        double[] center = farm == null ? null : parseCenter(farm.getCenter());
        if (weatherApi == null || !weatherApi.isConfigured()) {
            return mapOf("status", "UNAVAILABLE", "sourceMode", "UNAVAILABLE", "scope", "FARM", "granularity", "CURRENT_AND_DAILY", "reason", "weather provider is not configured");
        }
        if (center == null) {
            return mapOf("status", "UNAVAILABLE", "sourceMode", "UNAVAILABLE", "scope", "FARM", "granularity", "CURRENT_AND_DAILY", "reason", "farm center is not configured");
        }
        WeatherApiService.WeatherSnapshot snapshot = weatherApi.getWeather(center[0], center[1]);
        if (snapshot == null) {
            return mapOf("status", "CONFIGURED_UNAVAILABLE", "sourceMode", "UNAVAILABLE", "scope", "FARM", "granularity", "CURRENT_AND_DAILY", "reason", "weather provider returned no observation");
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("status", "READY"); value.put("sourceMode", snapshot.source()); value.put("scope", "FARM");
        value.put("granularity", "CURRENT_AND_DAILY"); value.put("dataDate", snapshot.observedAt());
        value.put("observedAt", snapshot.observedAt()); value.put("temperature", snapshot.temperature());
        value.put("summary", snapshot.condition()); value.put("humidity", snapshot.humidity());
        value.put("windDirection", snapshot.windDirection()); value.put("windPower", snapshot.windPower());
        value.put("provider", snapshot.provider()); value.put("freshness", snapshot.freshness());
        value.put("rainfall14d", snapshot.rainfall14d()); value.put("rainfallForecast24h", snapshot.rainfallForecast24h());
        value.put("rainfallProbability24h", snapshot.rainfallProbability24h()); value.put("rainfallForecast", snapshot.rainfallForecast());
        value.put("rainfallForecastDays", snapshot.rainfallForecastDays()); value.put("forecast", snapshot.forecast());
        return value;
    }

    private Map<String, Object> accumulated(Integer farmId) {
        try {
            List<Map<String, Object>> rows = jdbc.query("SELECT date,temperature,accumulated_temp FROM accumulated_temperature WHERE farm_id=? ORDER BY date DESC LIMIT 1", (ResultSet rs, int rowNum) -> mapOf("date", rs.getDate("date").toString(), "temperature", rs.getBigDecimal("temperature"), "accumulatedTemp", rs.getBigDecimal("accumulated_temp")), farmId);
            if (rows.isEmpty()) return mapOf("status", "UNAVAILABLE", "sourceMode", "MYSQL", "scope", "FARM", "reason", "no accumulated temperature row");
            Map<String, Object> value = new LinkedHashMap<>(rows.get(0)); value.put("status", "READY"); value.put("sourceMode", "MYSQL"); value.put("scope", "FARM"); return value;
        } catch (RuntimeException error) {
            return mapOf("status", "UNAVAILABLE", "sourceMode", "MYSQL", "scope", "FARM", "reason", "temperature source unavailable");
        }
    }

    private double[] parseCenter(String center) {
        if (center == null || center.trim().isEmpty()) return null;
        try { String[] parts = center.split(","); return new double[]{Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim())}; }
        catch (Exception ignored) { return null; }
    }

    private FarmField map(ResultSet rs, int rowNum) throws java.sql.SQLException {
        Timestamp created = rs.getTimestamp("created_at"); Timestamp updated = rs.getTimestamp("updated_at");
        return FarmField.builder().fieldId(rs.getString("field_id")).farmId(rs.getInt("farm_id")).name(rs.getString("name")).crop(rs.getString("crop"))
                .area(rs.getBigDecimal("area")).growthStage(rs.getString("growth_stage")).latitude(number(rs.getObject("latitude"))).longitude(number(rs.getObject("longitude")))
                .status(rs.getString("status")).sourceMode(rs.getString("source_mode")).ownerUserId((Long) rs.getObject("owner_user_id"))
                .createdAt(created == null ? null : created.toInstant()).updatedAt(updated == null ? null : updated.toInstant()).build();
    }

    private void ensureSchema() {
        if (schemaChecked) return;
        synchronized (this) {
            if (schemaChecked) return;
            jdbc.execute("CREATE TABLE IF NOT EXISTS farm_fields (field_id VARCHAR(96) NOT NULL,farm_id INT NOT NULL,name VARCHAR(128) NOT NULL,crop VARCHAR(128) NULL,area DECIMAL(12,4) NULL,growth_stage VARCHAR(128) NULL,latitude DECIMAL(10,8) NULL,longitude DECIMAL(11,8) NULL,status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',source_mode VARCHAR(32) NOT NULL DEFAULT 'MYSQL',owner_user_id BIGINT NULL,created_at TIMESTAMP(3) NOT NULL,updated_at TIMESTAMP(3) NOT NULL,PRIMARY KEY(field_id),UNIQUE KEY uk_farm_field_name(farm_id,name),KEY idx_farm_fields_farm(farm_id),KEY idx_farm_fields_owner(owner_user_id))");
            schemaChecked = true;
        }
    }

    private Map<String, Object> mapOf(Object... values) { Map<String, Object> map = new LinkedHashMap<>(); for (int i = 0; i + 1 < values.length; i += 2) map.put(String.valueOf(values[i]), values[i + 1]); return map; }

    private Double number(Object value) { return value instanceof Number ? ((Number) value).doubleValue() : null; }

    public record CreateFieldCommand(String name, String crop, BigDecimal area, String growthStage, Double latitude, Double longitude) {}
}
