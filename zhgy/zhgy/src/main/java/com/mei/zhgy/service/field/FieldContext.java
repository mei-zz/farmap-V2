package com.mei.zhgy.service.field;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Server assembled, provenance-aware context. Clients may request it, never author it. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FieldContext {
    @Builder.Default private Map<String, Object> farm = new LinkedHashMap<>();
    @Builder.Default private Map<String, Object> field = new LinkedHashMap<>();
    @Builder.Default private Map<String, Object> crop = new LinkedHashMap<>();
    @Builder.Default private Map<String, Object> location = new LinkedHashMap<>();
    @Builder.Default private Map<String, Object> weather = new LinkedHashMap<>();
    @Builder.Default private Map<String, Object> accumulatedTemperature = new LinkedHashMap<>();
    @Builder.Default private Map<String, Object> images = new LinkedHashMap<>();
    @Builder.Default private Map<String, Object> soil = new LinkedHashMap<>();
    @Builder.Default private Map<String, Object> gis = new LinkedHashMap<>();
    @Builder.Default private Map<String, Object> satellite = new LinkedHashMap<>();
    private String status;
    private String sourceMode;
    private String scope;
    private Instant freshness;
}
