package com.mei.zhgy.service.agriculture;

import com.mei.zhgy.service.field.FieldAccessException;
import com.mei.zhgy.service.field.FieldContext;
import com.mei.zhgy.service.field.FarmFieldService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One trusted assembly point for date, crop, phenology and field provenance. */
@Service
public class FieldFactsService {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", java.util.Locale.SIMPLIFIED_CHINESE);
    private final FarmFieldService fields;
    private final CropResolver crops;
    private final PhenologyService phenology;

    public FieldFactsService(FarmFieldService fields, CropResolver crops, PhenologyService phenology) {
        this.fields = fields; this.crops = crops; this.phenology = phenology;
    }

    public FieldFacts forField(String fieldId, Long userId) {
        LocalDate today = LocalDate.now(ZONE);
        String formatted = today.format(DATE);
        if (fieldId == null || fieldId.isBlank()) return empty("NO_FIELD", today, formatted);
        if (fields == null) return empty("UNAVAILABLE", today, formatted);
        try {
            FieldContext trusted = fields.context(fieldId, userId);
            String rawCrop = text(trusted.getCrop(), "name");
            CropResolver.ResolvedCrop crop = crops.resolve(rawCrop);
            if (!crop.ready()) {
                return new FieldFacts(
                        "UNKNOWN_CROP", today, formatted, fieldId, number(trusted.getFarm(), "id"), rawCrop,
                        null, null, text(trusted.getField(), "growthStage"), null,
                        null, null, null, null, null, null, null, null,
                        null, null, List.<String>of(), false, false,
                        sources("serverDate", "SERVER_ASIA_SHANGHAI", "crop", "UNKNOWN"), trusted);
            }
            String registered = text(trusted.getField(), "growthStage");
            PhenologyResult result = phenology.calculate(crop.code(), today, registered);
            String registeredLabel = result.registeredStage() == null ? null : result.registeredStage().name();
            Map<String, String> sources = sources("serverDate", "SERVER_ASIA_SHANGHAI", "crop", "MYSQL_FIELD_CROP", "dateDerivedStage", "SERVER_RULE", "currentStage", registered == null ? "SERVER_RULE" : "MYSQL_FIELD");
            PhenologyCalendar calendar = phenology.calendarForCrop(crop.code());
            return new FieldFacts("READY", today, formatted, fieldId, number(trusted.getFarm(), "id"), rawCrop,
                    crop.code(), crop.name(), registered, registeredLabel, result.dateDerivedCode(), result.dateDerivedName(),
                    result.currentCode(), result.currentName(), result.previousCode(), result.previousName(), result.nextCode(), result.nextName(), result.daysUntilNext(),
                    calendar == null ? null : calendar.cycleType(), calendar == null ? List.of() : calendar.stages().stream().map(PhenologyStage::name).toList(),
                    result.mismatch(), result.cycleEnded(), sources, trusted);
        } catch (FieldAccessException error) {
            throw error;
        } catch (RuntimeException error) {
            return empty("UNAVAILABLE", today, formatted);
        }
    }

    private FieldFacts empty(String status, LocalDate date, String formatted) {
        return new FieldFacts(status, date, formatted, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, List.<String>of(), false, false,
                sources("serverDate", "SERVER_ASIA_SHANGHAI", "field", status), null);
    }

    private Map<String, String> sources(String... values) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) result.put(values[i], values[i + 1]);
        return result;
    }

    private String text(Map<String, Object> value, String key) {
        if (value == null || value.get(key) == null || String.valueOf(value.get(key)).isBlank()) return null;
        return String.valueOf(value.get(key)).trim();
    }

    private Integer number(Map<String, Object> value, String key) {
        Object item = value == null ? null : value.get(key);
        return item instanceof Number ? ((Number) item).intValue() : null;
    }
}
