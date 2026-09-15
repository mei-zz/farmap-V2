package com.mei.zhgy.service.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyFarmMapImportPolicyTest {
    @Test
    void sourceIdentityIsStableAndEntitySpecific() {
        assertEquals("legacy-farmap-crops-15506", LegacyFarmMapImportPolicy.stablePointId("crops", "15506"));
        assertEquals("legacy-farmap-location-15506", LegacyFarmMapImportPolicy.stablePointId("location", "15506"));
        assertThrows(IllegalArgumentException.class, () -> LegacyFarmMapImportPolicy.stablePointId("", "1"));
    }

    @Test
    void coordinatePolicyRejectsSwappedOrZeroCoordinates() {
        assertTrue(LegacyFarmMapImportPolicy.validCoordinate(108.1302D, 31.1706D));
        assertFalse(LegacyFarmMapImportPolicy.validCoordinate(31.16979D, 108.12978D));
        assertFalse(LegacyFarmMapImportPolicy.validCoordinate(0D, 0D));
        assertFalse(LegacyFarmMapImportPolicy.validCoordinate(null, 31.17D));
    }
}
