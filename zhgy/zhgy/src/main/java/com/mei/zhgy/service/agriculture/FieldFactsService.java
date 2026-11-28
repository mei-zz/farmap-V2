package com.mei.zhgy.service.agriculture;

import com.mei.zhgy.service.field.FieldAccessException;
import com.mei.zhgy.service.field.FieldContext;
import com.mei.zhgy.service.field.FarmFieldService;
import com.mei.zhgy.service.copilot.CopilotMemoryProposalService;
import com.mei.zhgy.service.copilot.PlumAgronomyEvidenceCatalog;
import com.mei.zhgy.service.copilot.PlumAgronomyRuleEngine;
import org.springframework.beans.factory.annotation.Autowired;
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
    @Autowired(required = false) private CopilotMemoryProposalService memory;
    @Autowired(required = false) private PlumAgronomyEvidenceCatalog agronomyEvidence;
    @Autowired(required = false) private PlumAgronomyRuleEngine agronomyRules;

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
            Integer farmId = number(trusted.getFarm(), "id");
            Map<String, String> sources = sources("serverDate", "SERVER_ASIA_SHANGHAI", "crop", "MYSQL_FIELD_CROP",
                    "dateDerivedStage", "SERVER_RULE", "currentStage", registered == null ? "SERVER_RULE" : "MYSQL_FIELD");
            PhenologyResult result = "plum".equals(crop.code()) && farmId != null
                    ? plumPhenology(farmId, fieldId, userId, today, registered, trusted.getAccumulatedTemperature(), sources)
                    : phenology.calculate(crop.code(), today, registered);
            String registeredLabel = result.registeredStage() == null ? null : result.registeredStage().name();
            PhenologyCalendar calendar = phenology.calendarForCrop(crop.code());
            return new FieldFacts("READY", today, formatted, fieldId, farmId, rawCrop,
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

    private PhenologyResult plumPhenology(Integer farmId, String fieldId, Long actor, LocalDate today, String registered,
                                          Map<String, Object> heat, Map<String, String> sources) {
        var verified = agronomyEvidence == null ? null : agronomyEvidence.calendarProfile(farmId, fieldId, "plum", today);
        Map<String, Object> declared = Map.of();
        if (memory != null) {
            try { declared = memory.profileMap(actor, farmId.toString(), fieldId); }
            catch (RuntimeException unavailable) { /* An unavailable profile does not erase verified field facts. */ }
        }
        Double elevation = verified != null && verified.elevationMeters() != null ? verified.elevationMeters()
                : declared.get("elevationMeters") instanceof Number number ? number.doubleValue() : null;
        String cultivar = verified != null && verified.cultivar() != null ? verified.cultivar()
                : text(declared, "plum_variety");
        if (cultivar == null) cultivar = text(declared, "variety");

        Double thermalSum = heat != null && "READY".equals(heat.get("status"))
                && heat.get("accumulatedTemp") instanceof Number value && Double.isFinite(value.doubleValue())
                ? value.doubleValue() : null;
        LocalDate measuredOn = null;
        if (thermalSum != null && heat.get("date") instanceof String date) {
            try { measuredOn = LocalDate.parse(date); }
            catch (java.time.format.DateTimeParseException invalid) { /* A calibration still requires a verified measurement date. */ }
        }
        sources.put("accumulatedTemperatureStatus", thermalSum == null ? "UNAVAILABLE" : "READY");
        if (thermalSum != null) sources.put("accumulatedTemperature", thermalSum.toString());
        if (measuredOn != null) sources.put("temperatureMeasuredOn", measuredOn.toString());

        var assessment = phenology.calculate("plum", today, registered, elevation, cultivar, thermalSum);
        sources.put("phenologyEffectiveDate", assessment.effectiveDate().toString());
        String basis = assessment.calendarExplanation();
        boolean usedDeclaredElevation = elevation != null && (verified == null || verified.elevationMeters() == null);
        boolean usedDeclaredCultivar = cultivar != null && (verified == null || verified.cultivar() == null);
        if (usedDeclaredElevation || usedDeclaredCultivar) {
            basis += " · 部分画像来源为用户自报";
        }
        sources.put("phenologyBasis", thermalSum == null ? basis : basis + " · 已记录观测积温，等待匹配校准依据");

        PhenologyResult baseline = assessment.result();
        if (agronomyRules == null || agronomyEvidence == null
                || !agronomyEvidence.hasField(farmId, fieldId, "plum", today)) return baseline;
        var inputs = agronomyEvidence.correction(farmId, fieldId, "plum", today, thermalSum, measuredOn);
        var correction = agronomyRules.correctPhenology(new PlumAgronomyRuleEngine.PhenologyCorrectionRequest(
                "plum", today, registered, baseline.dateDerivedCode(),
                new PlumAgronomyRuleEngine.Provenance(PlumAgronomyRuleEngine.SourceKind.ORCHARD_RECORD, "FarMap:" + fieldId,
                        "farm-field-v1", java.time.Instant.now(), false, true), inputs.observations(), inputs.windows()));
        if (correction.requiresReview() || !correction.changed() || correction.effectiveStageCode() == null) return baseline;
        var corrected = phenology.calculate("plum", assessment.effectiveDate(), correction.effectiveStageCode());
        sources.put("currentStage", "VERIFIED_PHENOLOGY_CORRECTION");
        sources.put("phenologyBasis", basis + " · 已按核验的物候观测或积温校准修正");
        return new PhenologyResult(baseline.dateDerivedStage(), baseline.registeredStage(), corrected.currentStage(),
                corrected.previousStage(), corrected.nextStage(), corrected.daysUntilNext(), baseline.mismatch(), corrected.cycleEnded());
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
