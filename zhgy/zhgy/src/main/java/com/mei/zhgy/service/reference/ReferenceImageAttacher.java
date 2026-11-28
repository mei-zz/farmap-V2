package com.mei.zhgy.service.reference;

import com.mei.zhgy.service.copilot.CopilotQueryNormalizer;
import com.mei.zhgy.service.agriculture.PhenologyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Chooses atlas images for a chat/diagnosis request without semantic vector search. */
@Component
public class ReferenceImageAttacher {
    private static final List<String> PHENOLOGY_SIGNALS = List.of(
            "现在", "当前", "目前", "今天", "今日", "物候", "生育期", "这个阶段", "这一阶段",
            "上一个", "上一期", "下一阶段", "下一个", "下一期", "阶段");
    private static final Map<String, String> STAGE_ALIASES = stageAliases();

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
                .filter(entry -> !entry.getKey().isBlank() && query.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("");
    }

    private static Map<String, String> stageAliases() {
        Map<String, String> aliases = new LinkedHashMap<>();
        PhenologyService.plumAtlasAliases().entrySet().stream()
                .map(entry -> Map.entry(CopilotQueryNormalizer.compactForMatching(entry.getKey()),
                        entry.getValue()))
                .filter(entry -> !entry.getKey().isBlank())
                .sorted(Comparator.comparingInt((Map.Entry<String, String> entry) -> entry.getKey().length())
                        .reversed())
                .forEach(entry -> aliases.putIfAbsent(entry.getKey(), entry.getValue()));
        return Collections.unmodifiableMap(aliases);
    }

    private String safe(String value) { return value == null ? "" : value.trim(); }
}
