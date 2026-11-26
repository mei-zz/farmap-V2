package com.mei.zhgy.service.copilot;

import com.mei.zhgy.service.copilot.PlumAgronomyRuleEngine.CertificationStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PlumAgronomyEvidenceCatalogTest {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 26);

    @Test
    void fieldCalibrationRequiresCorrectScopeAndFreshConsistentThermalMetric() {
        var catalog = catalog();
        var match = catalog.correction(7, "east", "plum", DAY, 1025D, DAY.minusDays(1));
        assertEquals(1, match.observations().size());
        assertEquals("fruit_set", match.windows().get(0).stageCode());
        assertTrue(catalog.correction(7, "east", "plum", DAY, 1025D, DAY.minusDays(3)).windows().isEmpty());
        assertTrue(catalog.correction(7, "east", "plum", DAY, 1990D, DAY).windows().isEmpty());
        assertTrue(catalog.correction(99, "east", "plum", DAY, 1025D, DAY).observations().isEmpty());
    }

    @Test
    void registrationInputsKeepVerifiedLabelAndScopedCertificationSeparate() {
        var catalog = catalog();
        var inputs = catalog.spray(7, "east", "plum", "fixture-product", DAY);
        assertEquals(14, inputs.label().preHarvestIntervalDays());
        assertTrue(inputs.label().provenance().publicSource());
        assertEquals(CertificationStatus.ORGANIC, inputs.certification().status());
        assertNull(catalog.spray(7, "west", "plum", "fixture-product", DAY).certification());
        assertNull(catalog.spray(7, "east", "plum", "unregistered-product", DAY).label());
        assertNull(catalog.spray(7, "east", "plum", "fixture-product", DAY.plusYears(1)).label());
    }

    private PlumAgronomyEvidenceCatalog catalog() {
        var calibration = new PlumAgronomyEvidenceCatalog.Calibration("测试品种", 750D, 950D, 1000D, 1100D,
                "FARM_DAILY_TEMP_SUM", "fruit_set", DAY.minusDays(5), DAY.plusDays(5), "calibration-fixture", "v1");
        var observation = new PlumAgronomyEvidenceCatalog.Observation("fruit_set", DAY.minusDays(1), "review-fixture", "v1");
        var orchard = new PlumAgronomyEvidenceCatalog.Orchard(7, "east", "plum", "测试品种", 850D, DAY.plusDays(25),
                CertificationStatus.ORGANIC, "certificate-fixture", "v1", DAY.plusDays(100),
                Set.of(), Set.of(), Set.of(), Set.of(), List.of(observation), List.of(calibration));
        var label = new PlumAgronomyEvidenceCatalog.Label("plum", "fixture-product", "测试药剂", "测试成分", 14,
                true, false, "https://example.test/registration/fixture", "v1", DAY.minusDays(15), DAY.plusDays(15));
        return new PlumAgronomyEvidenceCatalog(new PlumAgronomyEvidenceCatalog.Document(List.of(label), List.of(orchard)));
    }
}
