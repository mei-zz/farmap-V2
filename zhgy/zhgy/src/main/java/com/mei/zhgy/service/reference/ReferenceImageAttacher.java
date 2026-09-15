package com.mei.zhgy.service.reference;

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
        if (properties == null || !properties.isEnabled() || diseaseCatalog == null || store == null
                || !"plum".equalsIgnoreCase(safe(cropCode))) return List.of();
        try {
            String diseaseKey = diseaseCatalog.resolve(rawQuery);
            List<ReferenceImage> images = diseaseKey != null
                    ? store.byDisease("plum", diseaseKey)
                    : !safe(stageCode).isBlank() && asksStageOrPhenology(rawQuery)
                    ? store.byStage("plum", stageCode) : List.of();
            Map<String, ReferenceImage> unique = new LinkedHashMap<>();
            for (ReferenceImage image : images) {
                if (image != null && image.refId() != null && !image.refId().isBlank()) unique.putIfAbsent(image.refId(), image);
            }
            return List.copyOf(unique.values());
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private boolean asksStageOrPhenology(String text) {
        String query = safe(text);
        return PHENOLOGY_SIGNALS.stream().anyMatch(query::contains);
    }

    private String safe(String value) { return value == null ? "" : value.trim(); }
}
