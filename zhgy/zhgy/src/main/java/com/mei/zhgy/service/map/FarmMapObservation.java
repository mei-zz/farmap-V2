package com.mei.zhgy.service.map;

import java.util.Map;

public record FarmMapObservation(
        String pointId,
        Integer id,
        Double longitude,
        Double latitude,
        String observedDate,
        String crop,
        String sourceSystem,
        String sourceEntityType,
        String coordinateReferenceSystem,
        Map<String, Object> info
) {}
