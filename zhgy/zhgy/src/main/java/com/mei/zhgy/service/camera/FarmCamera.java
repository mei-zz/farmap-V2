package com.mei.zhgy.service.camera;

/**
 * Canonical camera record shared by the devices page, map overlays and Agent
 * context.  A camera may be spatially mapped without belonging to a stable
 * field because the legacy source does not contain a field/plot model.
 */
public record FarmCamera(
        String cameraId,
        String serial,
        String name,
        Integer farmId,
        String farmName,
        String fieldId,
        String fieldName,
        String crop,
        String growthStage,
        Double fieldArea,
        String fieldSourceMode,
        String pointId,
        Double longitude,
        Double latitude,
        Double sourceLongitude,
        Double sourceLatitude,
        Double bindingDistanceMeters,
        String bindingStatus,
        String bindingMethod,
        String status
) {}
