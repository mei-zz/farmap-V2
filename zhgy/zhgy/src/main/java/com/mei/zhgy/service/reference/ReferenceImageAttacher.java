package com.mei.zhgy.service.reference;

import com.mei.zhgy.service.copilot.CopilotQueryNormalizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Chooses atlas images for a chat/diagnosis request without semantic vector search. */
@Component
public class ReferenceImageAttacher {
    private static final List<String> PHENOLOGY_SIGNALS = List.of(
            "现在", "当前", "目前", "今天", "今日", "物候", "生育期", "这个阶段", "这一阶段",
            "上一个", "上一期", "下一阶段", "下一个", "下一期", "阶段");
    private static final Map<String, String> STAGE_ALIASES = Map.ofEntries(
            Map.entry("萌芽期", "germination"),
            Map.entry("萌芽", "germination"),
            Map.entry("萌动期", "germination"),
            Map.entry("花蕾期", "budding"),
            Map.entry("露白期", "budding"),
            Map.entry("花期", "flowering"),
            Map.entry("开花期", "flowering"),
            Map.entry("坐果期", "fruiting"),
            Map.entry("谢花期", "fruiting"),
            Map.entry("幼果期", "fruiting"),
            Map.entry("果实发育期", "fruit_development"),
            Map.entry("果实膨大期", "fruit_development"),
            Map.entry("膨果期", "fruit_development"),
            Map.entry("膨大期", "fruit_development"),
            Map.entry("膨果", "fruit_development"),
            Map.entry("成熟期", "ripening"),
            Map.entry("休眠期", "dormancy"));

    private final DiseaseCatalog diseaseCatalog;
    private final ReferenceImageStore store;
    private final ReferenceImageProperties properties;

    @Autowired
    public ReferenceImageAttacher(ReferenceImageStore store, ReferenceImageProperties properties) {
        this(new DiseaseCatalog(), store, properties);
    }

    public ReferenceImageAttacher(DiseaseCatalog diseaseCatalog, ReferenceImageStore store,
                                  ReferenceImageProperties properties) {
        this.diseaseCatalog = diseaseCatalog;
        this.store = store;
        this.properties = properties;
    }

    public ReferenceImageAttacher(DiseaseCatalog diseaseCatalog, ReferenceImageStore store) {
        this(diseaseCatalog, store, new ReferenceImageProperties());
    }

    public List<ReferenceImage> forChat(String rawQuery, String cropCode, String stageCode) {
        return forChatResult(rawQuery, cropCode, stageCode).images();
    }

    public ReferenceImageLookup forChatResult(String rawQuery, String cropCode, String stageCode) {
        if (properties == null || !properties.isEnabled() || diseaseCatalog == null || store == null
                || !"plum".equalsIgnoreCase(safe(cropCode))) return ReferenceImageLookup.unavailable("invalid-context");
        try {
            String diseaseKey = diseaseCatalog.resolve(rawQuery);
            String requestedStage = resolveRequestedStage(rawQuery);
            ReferenceImageLookup lookup = diseaseKey != null
                    ? store.byDiseaseResult("plum", diseaseKey)
                    : !safe(requestedStage).isBlank()
                    ? store.byStageResult("plum", requestedStage)
                    : !safe(stageCode).isBlank() && asksStageOrPhenology(rawQuery)
                    ? store.byStageResult("plum", stageCode) : ReferenceImageLookup.noMatch("no-atlas-signal", 0, 0);
            Map<String, ReferenceImage> unique = new LinkedHashMap<>();
            for (ReferenceImage image : lookup.images()) {
                if (image != null && image.refId() != null && !image.refId().isBlank()) unique.putIfAbsent(image.refId(), image);
            }
            return lookup.withImages(List.copyOf(unique.values()));
        } catch (Exception error) {
            return ReferenceImageLookup.unavailable("lookup-error");
        }
    }

    private boolean asksStageOrPhenology(String text) {
        String query = CopilotQueryNormalizer.compactForMatching(text);
        return PHENOLOGY_SIGNALS.stream().anyMatch(query::contains);
    }

    private String resolveRequestedStage(String text) {
        String query = CopilotQueryNormalizer.compactForMatching(text);
        return STAGE_ALIASES.entrySet().stream()
                .filter(entry -> query.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("");
    }

    private String safe(String value) { return value == null ? "" : value.trim(); }
}
