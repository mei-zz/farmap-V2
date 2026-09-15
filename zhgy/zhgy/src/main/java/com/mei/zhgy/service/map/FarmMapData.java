package com.mei.zhgy.service.map;

import java.util.List;

public record FarmMapData(
        Integer farmId,
        String farmName,
        String type,
        String address,
        Integer zoom,
        String center,
        Integer ownerUserId,
        String sourceMode,
        String coordinateReferenceSystem,
        String status,
        List<FarmMapBoundaryPoint> locations,
        List<FarmMapObservation> crops
) {}
