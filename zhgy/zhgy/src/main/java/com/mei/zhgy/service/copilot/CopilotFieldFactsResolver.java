package com.mei.zhgy.service.copilot;

import com.mei.zhgy.service.copilot.CopilotApplicationService.CopilotChatRequest;
import com.mei.zhgy.service.agriculture.CropResolver;
import com.mei.zhgy.service.agriculture.FieldFactsService;
import com.mei.zhgy.service.agriculture.PhenologyResult;
import com.mei.zhgy.service.agriculture.PhenologyService;
import com.mei.zhgy.service.field.FarmField;
import com.mei.zhgy.service.field.FarmFieldService;
import com.mei.zhgy.service.field.FieldAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;

/** Resolves crop and phenology from the authorized scope before considering public question context. */
final class CopilotFieldFactsResolver {
    private static final Logger log = LoggerFactory.getLogger(CopilotFieldFactsResolver.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.SIMPLIFIED_CHINESE);
    private final FarmFieldService fields;
    private final FieldFactsService factsService;
    private final CropResolver crops;
    private final PhenologyService phenology;
    private final BiFunction<CopilotStableContext, Long, Map<String, Object>> orchardProfile;
    private final BiFunction<CopilotChatRequest, Long, String> recentConversation;

    CopilotFieldFactsResolver(FarmFieldService fields, FieldFactsService factsService, CropResolver crops,
                              PhenologyService phenology,
                              BiFunction<CopilotStableContext, Long, Map<String, Object>> orchardProfile,
                              BiFunction<CopilotChatRequest, Long, String> recentConversation) {
        this.fields = fields;
        this.factsService = factsService;
        this.crops = crops;
        this.phenology = phenology;
        this.orchardProfile = orchardProfile;
        this.recentConversation = recentConversation;
    }

    CopilotFacts scopeOnly(CopilotChatRequest request, CopilotRouteResult route, Long actor) {
        String currentDate = LocalDate.now(FieldFactsService.ZONE).format(DATE);
        String fieldId = boundField(route);
        if (fieldId != null) {
            if (route.getResolutionStatus() != null && route.getResolutionStatus() != CopilotResolutionStatus.RESOLVED) {
                return CopilotFacts.empty(currentDate, "UNAVAILABLE");
            }
            if (fields == null) return resolve(request, route, actor);
            FarmField field = fields.get(fieldId, actor);
            if (field == null) return CopilotFacts.empty(currentDate, "UNAVAILABLE");
            var crop = crops.resolve(field.getCrop());
            if (!crop.ready()) return CopilotFacts.empty(currentDate, "UNKNOWN_CROP");
            var stage = phenology.resolveStage(crop.code(), field.getGrowthStage());
            return CopilotFacts.scopeOnly(currentDate, crop, stage == null ? null : stage.code());
        }
        var crop = crops.resolve(request.getMessage());
        return CopilotFacts.scopeOnly(currentDate, crop.ready() ? crop : null, null);
    }

    CopilotFacts resolve(CopilotChatRequest request, CopilotRouteResult route, Long actor) {
        LocalDate today = LocalDate.now(FieldFactsService.ZONE);
        String currentDate = today.format(DATE);
        String fieldId = boundField(route);
        if (fieldId == null) {
            var farm = farmFacts(request, route, actor, today, currentDate);
            if (farm != null) return farm;
            var crop = crops.resolve(request == null ? null : request.getMessage());
            if (!crop.ready() && !crop.unknown() && request != null) crop = crops.resolve(recentConversation.apply(request, actor));
            var generic = publicFacts(crop, today, currentDate);
            return generic == null ? CopilotFacts.empty(currentDate, "NO_FIELD") : generic;
        }
        try {
            if (factsService != null) {
                var facts = factsService.forField(fieldId, actor);
                return facts == null ? CopilotFacts.empty(currentDate, "UNAVAILABLE") : CopilotFacts.from(facts);
            }
            if (fields == null) return CopilotFacts.empty(currentDate, "UNAVAILABLE");
            var context = fields.context(fieldId, actor);
            if (context == null) return CopilotFacts.empty(currentDate, "UNAVAILABLE");
            var crop = crops.resolve(text(context.getCrop(), "name"));
            if (!crop.ready()) return CopilotFacts.empty(currentDate, "UNKNOWN_CROP");
            String registered = text(context.getField(), "growthStage");
            var stages = phenology.calculate(crop.code(), today, registered);
            return CopilotFacts.from(currentDate, crop, stages, phenology.orderedStageNames(crop.code()), phenology.cycleType(crop.code()),
                    Map.of("serverDate", "SERVER_ASIA_SHANGHAI", "currentStage", registered == null ? "SERVER_RULE" : "MYSQL_FIELD"));
        } catch (FieldAccessException denied) {
            throw denied;
        } catch (RuntimeException unavailable) {
            log.warn("COPILOT_FIELD_FACTS_DEGRADED userId={}", actor, unavailable);
            return CopilotFacts.empty(currentDate, "UNAVAILABLE");
        }
    }

    CopilotFacts publicFacts(String question, LocalDate today, String currentDate) {
        return publicFacts(crops.resolve(question), today, currentDate);
    }

    private CopilotFacts publicFacts(CropResolver.ResolvedCrop crop, LocalDate today, String currentDate) {
        if (!crop.ready()) return null;
        var stages = phenology.calculate(crop.code(), today, null);
        return CopilotFacts.from(currentDate, crop, stages, phenology.orderedStageNames(crop.code()), phenology.cycleType(crop.code()),
                Map.of("serverDate", "SERVER_ASIA_SHANGHAI", "crop", "PUBLIC_CROP_QUERY", "currentStage", "SERVER_RULE"), "PUBLIC_GENERIC");
    }

    private CopilotFacts farmFacts(CopilotChatRequest request, CopilotRouteResult route, Long actor,
                                   LocalDate today, String currentDate) {
        if (fields == null) return null;
        CopilotStableContext scope = route == null ? null : route.getResolvedContext();
        String rawFarm = scope == null ? null : scope.getFarmId();
        if ((rawFarm == null || rawFarm.isBlank()) && request != null && request.getPageContext() != null) {
            rawFarm = request.getPageContext().getFarmId();
        }
        Integer farmId;
        try { farmId = rawFarm == null ? null : Integer.valueOf(rawFarm.trim()); }
        catch (NumberFormatException invalid) { return null; }
        if (farmId == null) return null;
        try {
            List<FarmField> farmFields = fields.list(farmId, actor);
            if (farmFields == null || farmFields.isEmpty()) return null;
            var supported = new LinkedHashMap<String, CropResolver.ResolvedCrop>();
            for (FarmField field : farmFields) {
                var crop = crops.resolve(field.getCrop());
                if (crop.ready()) supported.putIfAbsent(crop.code(), crop);
            }
            if (supported.size() != 1) return null;
            var crop = supported.values().iterator().next();
            PhenologyResult stages = phenology.calculate(crop.code(), today, null);
            var sources = new LinkedHashMap<>(Map.of("serverDate", "SERVER_ASIA_SHANGHAI",
                    "crop", "MYSQL_FARM_FIELDS", "currentStage", "SERVER_RULE"));
            if ("plum".equals(crop.code())) {
                var profile = orchardProfile.apply(CopilotStableContext.builder().farmId(farmId.toString()).build(), actor);
                Double elevation = profile.get("elevationMeters") instanceof Number number ? number.doubleValue() : null;
                String cultivar = text(profile, "plum_variety");
                if (elevation != null || cultivar != null) {
                    var assessment = phenology.calculate(crop.code(), today, null, elevation, cultivar, null);
                    stages = assessment.result();
                    sources.put("phenologyEffectiveDate", assessment.effectiveDate().toString());
                    sources.put("phenologyBasis", assessment.calendarExplanation() + " · 使用已确认的农场自报画像");
                }
            }
            return CopilotFacts.from(currentDate, crop, stages, phenology.orderedStageNames(crop.code()),
                    phenology.cycleType(crop.code()), sources, "FARM_CONTEXT");
        } catch (RuntimeException unavailable) {
            log.warn("COPILOT_FARM_FACTS_DEGRADED userId={} farmId={} errorType={}", actor, farmId, unavailable.getClass().getSimpleName());
            return null;
        }
    }

    private String boundField(CopilotRouteResult route) {
        var scope = route == null ? null : route.getResolvedContext();
        String field = scope == null ? null : scope.getFieldId();
        return field == null || field.isBlank() ? null : field;
    }

    private String text(Map<String, Object> values, String key) {
        Object value = values == null ? null : values.get(key);
        return value == null || value.toString().isBlank() ? null : value.toString();
    }
}
