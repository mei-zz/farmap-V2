package com.mei.zhgy.service.rag;

import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.service.agriculture.CropResolver;
import com.mei.zhgy.service.agriculture.PhenologyService;
import com.mei.zhgy.vo.DiagnosisAnalysisResponse;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Context conflicts requiring review; a model observation never overwrites registered facts. */
public final class DiagnosisEvidenceConflicts {
    private static final CropResolver CROPS = new CropResolver();
    private static final PhenologyService PHENOLOGY = new PhenologyService(CROPS);

    private DiagnosisEvidenceConflicts() {}

    public static void inspect(DiagnosisAnalyzeRequest request, List<DiagnosisEvidenceVO> evidence) {
        var field = request.getTrustedFieldContext();
        if (field == null) return;
        var crop = CROPS.resolve(request.getCrop());
        Object raw = field.getField() == null ? null : field.getField().get("growthStage");
        var registered = crop.ready() && raw instanceof String stage ? PHENOLOGY.resolveStage(crop.code(), stage) : null;
        for (var item : evidence) {
            if (item == null) continue;
            Object observation = item.getMetadata() == null ? null : item.getMetadata().get("visualObservation");
            if (registered != null && observation instanceof VisualObservation visible && Boolean.TRUE.equals(visible.imageClear())) {
                var observed = PHENOLOGY.resolveStage(crop.code(), visible.observedPhenologyStage());
                if (observed != null && !registered.code().equals(observed.code())) {
                    mark(item, "VISUAL_REGISTERED_STAGE_MISMATCH");
                    item.getMetadata().put("registeredGrowthStage", registered.code());
                    item.getMetadata().put("observedGrowthStage", observed.code());
                }
            }
            if ("weather".equals(item.getModality()) && rainExpected(request) && concernsSpray(request.getQuery())) {
                mark(item, "RAIN_SPRAY_CONFLICT");
            }
        }
    }

    public static boolean inspectRecommendations(DiagnosisAnalyzeRequest request, List<DiagnosisEvidenceVO> evidence,
                                                 List<DiagnosisAnalysisResponse.Recommendation> recommendations) {
        if (!rainExpected(request) || recommendations == null) return false;
        boolean conflict = recommendations.stream().filter(java.util.Objects::nonNull)
                .anyMatch(item -> concernsSpray(item.getTitle()) || concernsSpray(item.getDetail()));
        if (conflict) evidence.stream().filter(item -> item != null && "weather".equals(item.getModality()))
                .forEach(item -> mark(item, "RAIN_SPRAY_CONFLICT"));
        return conflict;
    }

    public static boolean flagged(List<DiagnosisEvidenceVO> evidence) {
        return evidence.stream().anyMatch(item -> item != null && item.getMetadata() != null
                && Boolean.TRUE.equals(item.getMetadata().get("evidenceConflict")));
    }

    private static boolean rainExpected(DiagnosisAnalyzeRequest request) {
        Map<String, Object> weather = request.getTrustedFieldContext() == null ? null : request.getTrustedFieldContext().getWeather();
        if (weather == null || !"READY".equals(weather.get("status"))) return false;
        return positive(weather.get("rainfallForecast24h"), 0.1) || positive(weather.get("rainfallProbability24h"), 50);
    }

    private static boolean positive(Object value, double minimum) {
        return value instanceof Number number && Double.isFinite(number.doubleValue()) && number.doubleValue() >= minimum;
    }

    static boolean concernsSpray(String value) {
        return value != null && !value.matches("(?s).*(暂缓|推迟|暂停|不要|不宜|改期|停止|避免).{0,12}(喷雾|喷药|施药|打药|喷施).*")
                && value.matches("(?s).*(喷雾|喷药|施药|打药|喷施).*");
    }

    private static void mark(DiagnosisEvidenceVO item, String reason) {
        var metadata = item.getMetadata() == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<>(item.getMetadata());
        metadata.put("evidenceConflict", true);
        metadata.put("conflictReason", reason);
        item.setMetadata(metadata);
    }
}
