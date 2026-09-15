package com.mei.zhgy.service.map;

public record FarmMapBoundaryPoint(
        String pointId,
        Integer id,
        Integer sequence,
        Double longitude,
        Double latitude,
        String sourceSystem,
        String sourceEntityType,
        String coordinateReferenceSystem
) {}
