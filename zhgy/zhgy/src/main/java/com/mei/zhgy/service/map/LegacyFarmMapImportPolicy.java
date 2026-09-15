package com.mei.zhgy.service.map;

/** Pure policy shared by migration tests and any future importer adapter. */
public final class LegacyFarmMapImportPolicy {
    private LegacyFarmMapImportPolicy() {}

    public static String stablePointId(String sourceEntityType, String sourceLegacyId) {
        if (sourceEntityType == null || sourceEntityType.isBlank() || sourceLegacyId == null || sourceLegacyId.isBlank()) {
            throw new IllegalArgumentException("source identity is required");
        }
        return "legacy-farmap-" + sourceEntityType + "-" + sourceLegacyId;
    }

    public static boolean validCoordinate(Double longitude, Double latitude) {
        return longitude != null && latitude != null
                && longitude >= -180D && longitude <= 180D
                && latitude >= -90D && latitude <= 90D
                && !(longitude == 0D && latitude == 0D);
    }
}
