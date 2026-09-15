package com.mei.zhgy.service.reference;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Runtime switches and bounded limits for the read-only reference atlas. */
@Component
public class ReferenceImageProperties {
    @Value("${farmap.reference-image.enabled:true}")
    private boolean enabled = true;

    @Value("${farmap.reference-image.collection:farmap_reference_images_v1}")
    private String collection = "farmap_reference_images_v1";

    @Value("${farmap.reference-image.per-stage-limit:3}")
    private int perStageLimit = 3;

    @Value("${farmap.reference-image.per-disease-limit:3}")
    private int perDiseaseLimit = 3;

    public boolean isEnabled() { return enabled; }

    public String getCollection() { return collection; }

    public int getPerStageLimit() { return perStageLimit; }

    public int getPerDiseaseLimit() { return perDiseaseLimit; }
}
