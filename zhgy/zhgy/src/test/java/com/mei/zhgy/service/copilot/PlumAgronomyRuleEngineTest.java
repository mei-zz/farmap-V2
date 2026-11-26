package com.mei.zhgy.service.copilot;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static com.mei.zhgy.service.copilot.PlumAgronomyRuleEngine.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlumAgronomyRuleEngineTest {
    private static final Instant RETRIEVED = Instant.parse("2026-06-01T00:00:00Z");
    private final PlumAgronomyRuleEngine engine = new PlumAgronomyRuleEngine();

    @Test
    void phiConflictAndPublicLabelProvenanceAreHardBlocks() {
        SprayAssessment result = engine.assessSpray(new SprayAssessmentRequest(
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 10),
                "fruit_development",
                new LabelInput("PLUM-DEMO-1", "演示保护剂", "演示有效成分", 10, true, false, publicLabel()),
                noCertification(),
                List.of(goodSprayWindow("w1"))));

        assertEquals(Decision.BLOCKED, result.decision());
        assertFalse(result.hardRestrictionsPassed());
        assertTrue(result.nearHarvest());
        assertEquals(9, result.daysUntilHarvest().intValue());
        assertTrue(result.labelProvenance().verified());
        assertTrue(result.labelProvenance().publicLabel());
        assertEquals("PUBLIC_LABEL", result.labelProvenance().marker());
        assertTrue(hasFinding(result.findings(), "PHI_CONFLICT", Severity.BLOCKER));
    }

    @Test
    void unlistedOrUnverifiedOrganicProductFailsClosed() {
        LabelInput syntheticLabel = new LabelInput(
                "SYNTH-DEMO", "演示化学制剂", "demo-synthetic", 7, true, false, publicLabel());
        OrchardCertification organic = new OrchardCertification(
                CertificationStatus.ORGANIC, orchardRecord(),
                Set.of("ORG-ALLOWED"), Set.of(), Set.of(), Set.of());

        SprayAssessment notAllowed = engine.assessSpray(new SprayAssessmentRequest(
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1), "fruit_development",
                syntheticLabel, organic, List.of(goodSprayWindow("w1"))));

        assertEquals(Decision.BLOCKED, notAllowed.decision());
        assertTrue(hasFinding(notAllowed.findings(), "CERTIFICATION_PRODUCT_NOT_ALLOWED", Severity.BLOCKER));

        OrchardCertification unverified = new OrchardCertification(
                CertificationStatus.ORGANIC, unverifiedProvenance(SourceKind.ORCHARD_RECORD),
                Set.of("SYNTH-DEMO"), Set.of(), Set.of(), Set.of());
        SprayAssessment unknownRestriction = engine.assessSpray(new SprayAssessmentRequest(
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1), "fruit_development",
                syntheticLabel, unverified, List.of(goodSprayWindow("w1"))));

        assertEquals(Decision.INSUFFICIENT_DATA, unknownRestriction.decision());
        assertTrue(hasFinding(unknownRestriction.findings(),
                "CERTIFICATION_PROVENANCE_UNVERIFIED", Severity.DATA_GAP));
    }

    @Test
    void completeVerifiedOrganicInputsCanBeEligible() {
        LabelInput label = new LabelInput(
                "ORG-ALLOWED", "演示有机允许产品", "demo-active", 7, false, false, publicLabel());
        OrchardCertification organic = new OrchardCertification(
                CertificationStatus.ORGANIC, orchardRecord(),
                Set.of("ORG-ALLOWED"), Set.of(), Set.of(), Set.of());

        SprayAssessment result = engine.assessSpray(new SprayAssessmentRequest(
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1), "fruit_development",
                label, organic, List.of(goodSprayWindow("w1"))));

        assertEquals(Decision.ELIGIBLE, result.decision());
        assertTrue(result.hardRestrictionsPassed());
        assertTrue(result.weatherSuitable());
        assertEquals(SprayWindowSuitability.SUITABLE, result.bestWindow().suitability());
        assertFalse(result.nearHarvest());
    }

    @Test
    void missingWindIsInsufficientAndNamed() {
        SprayWeatherWindow missingWind = new SprayWeatherWindow(
                "w-gap", LocalDateTime.of(2026, 6, 1, 8, 0), LocalDateTime.of(2026, 6, 1, 12, 0),
                24D, 65D, null, 0D, 10D, weatherProvenance("w-gap"));

        SprayAssessment result = engine.assessSpray(new SprayAssessmentRequest(
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1), "fruit_development",
                new LabelInput("PLUM-DEMO-2", "演示保护剂", "演示有效成分", 7, true, false, publicLabel()),
                noCertification(), List.of(missingWind)));

        assertEquals(Decision.INSUFFICIENT_DATA, result.decision());
        assertTrue(result.hardRestrictionsPassed());
        assertFalse(result.weatherSuitable());
        assertEquals(SprayWindowSuitability.INSUFFICIENT_DATA, result.bestWindow().suitability());
        assertTrue(result.unavailableData().contains("weatherWindows[w-gap].windSpeedKph"));
        assertTrue(hasFinding(result.findings(), "SPRAY_WEATHER_INCOMPLETE", Severity.DATA_GAP));
    }

    @Test
    void missingHarvestDateLeavesPhiUnverified() {
        SprayAssessment result = engine.assessSpray(new SprayAssessmentRequest(
                LocalDate.of(2026, 6, 1), null, "fruit_development",
                new LabelInput("PLUM-DEMO-3", "演示保护剂", "演示有效成分", 7, true, false, publicLabel()),
                noCertification(), List.of(goodSprayWindow("w1"))));

        assertEquals(Decision.INSUFFICIENT_DATA, result.decision());
        assertFalse(result.hardRestrictionsPassed());
        assertTrue(result.unavailableData().contains("expectedHarvestDate"));
        assertTrue(hasFinding(result.findings(), "EXPECTED_HARVEST_DATE_MISSING", Severity.DATA_GAP));
    }

    @Test
    void harvestMeetsVerifiedMeasuredCriteriaAndSeparateWeatherOutlook() {
        HarvestAssessment result = engine.assessHarvest(new HarvestAssessmentRequest(
                LocalDate.of(2026, 7, 16),
                new HarvestMeasurements(12.5D, 0.8D, 5.0D, 80D, 30,
                        LocalDate.of(2026, 7, 15), measurementProvenance()),
                new HarvestCriteria(12.0D, 1.0D, 4.5D, 70D, 20, calibrationProvenance()),
                List.of(favorableHarvestWindow("h1"))));

        assertEquals(HarvestDecision.READY_FOR_MANUAL_REVIEW, result.decision());
        assertEquals(HarvestMaturityStatus.MEETS_PROVIDED_CRITERIA, result.maturityStatus());
        assertEquals(HarvestWeatherStatus.FAVORABLE, result.weatherStatus());
        assertEquals(4, result.criteria().size());
        assertTrue(result.criteria().stream().allMatch(item -> item.result() == CriterionResult.PASS));
        assertTrue(hasFinding(result.findings(), "HARVEST_SAMPLE_SIZE_VERIFIED", Severity.INFO));
    }

    @Test
    void harvestMissingMeasurementIsInsufficientAndNamed() {
        HarvestAssessment result = engine.assessHarvest(new HarvestAssessmentRequest(
                LocalDate.of(2026, 7, 16),
                new HarvestMeasurements(12.5D, 0.8D, 5.0D, null, 30,
                        LocalDate.of(2026, 7, 15), measurementProvenance()),
                new HarvestCriteria(12.0D, 1.0D, 4.5D, 70D, 20, calibrationProvenance()),
                List.of(favorableHarvestWindow("h1"))));

        assertEquals(HarvestMaturityStatus.INSUFFICIENT_DATA, result.maturityStatus());
        assertEquals(HarvestDecision.INSUFFICIENT_DATA, result.decision());
        assertTrue(result.unavailableData().contains("measurements.colorCoveragePct"));
        assertTrue(result.criteria().stream().anyMatch(item -> item.code().equals("colorCoveragePct")
                && item.result() == CriterionResult.MEASUREMENT_MISSING));
    }

    @Test
    void harvestRejectsUnverifiedCriteriaInsteadOfInventingThresholds() {
        HarvestAssessment result = engine.assessHarvest(new HarvestAssessmentRequest(
                LocalDate.of(2026, 7, 16),
                new HarvestMeasurements(12.5D, 0.8D, 5.0D, 80D, 30,
                        LocalDate.of(2026, 7, 15), measurementProvenance()),
                new HarvestCriteria(12.0D, 1.0D, 4.5D, 70D, 20,
                        unverifiedProvenance(SourceKind.LOCAL_CALIBRATION)),
                List.of(favorableHarvestWindow("h1"))));

        assertEquals(HarvestMaturityStatus.INSUFFICIENT_DATA, result.maturityStatus());
        assertTrue(result.criteria().stream().allMatch(item -> item.result() == CriterionResult.NOT_CONFIGURED));
        assertTrue(result.unavailableData().contains("criteria.provenance"));
        assertTrue(hasFinding(result.findings(), "HARVEST_CRITERIA_PROVENANCE_UNVERIFIED", Severity.DATA_GAP));
    }

    @Test
    void phenologyUsesLatestTrustedObservationAndIgnoresUnverifiedNewerOne() {
        PhenologyCorrection result = engine.correctPhenology(new PhenologyCorrectionRequest(
                "plum", LocalDate.of(2026, 6, 25), "fruit_development", "fruit_development",
                serverBaselineProvenance(),
                List.of(
                        new PhenologyObservation("ripening", LocalDate.of(2026, 6, 20), fieldObservation()),
                        new PhenologyObservation("harvest", LocalDate.of(2026, 6, 24),
                                unverifiedProvenance(SourceKind.FIELD_OBSERVATION))),
                List.of()));

        assertEquals(PhenologyCorrectionStatus.CORRECTED, result.status());
        assertEquals("ripening", result.effectiveStageCode());
        assertTrue(result.changed());
        assertFalse(result.requiresReview());
        assertEquals(SourceKind.FIELD_OBSERVATION, result.appliedEvidenceKind());
        assertEquals("VERIFIED_FIELD_OBSERVATION", result.appliedEvidenceMarker());
        assertTrue(hasFinding(result.findings(), "PHENOLOGY_OBSERVATION_UNTRUSTED", Severity.WARNING));
    }

    @Test
    void conflictingTrustedPhenologyEvidenceFailsClosed() {
        PhenologyCorrection result = engine.correctPhenology(new PhenologyCorrectionRequest(
                "plum", LocalDate.of(2026, 6, 25), "fruit_development", "fruit_development",
                serverBaselineProvenance(),
                List.of(
                        new PhenologyObservation("ripening", LocalDate.of(2026, 6, 24), fieldObservation()),
                        new PhenologyObservation("harvest", LocalDate.of(2026, 6, 24), fieldObservation())),
                List.of()));

        assertEquals(PhenologyCorrectionStatus.CONFLICTING_EVIDENCE, result.status());
        assertNull(result.effectiveStageCode());
        assertNull(result.evidenceStageCode());
        assertTrue(result.requiresReview());
        assertTrue(hasFinding(result.findings(), "PHENOLOGY_EVIDENCE_CONFLICT", Severity.BLOCKER));
    }

    @Test
    void trustedCalibrationWindowCanCorrectWithoutCultivarCoefficients() {
        PhenologyCorrection result = engine.correctPhenology(new PhenologyCorrectionRequest(
                "plum", LocalDate.of(2026, 7, 1), "fruit_development", "fruit_development",
                serverBaselineProvenance(), List.of(),
                List.of(new PhenologyCalibrationWindow(
                        "ripening", LocalDate.of(2026, 6, 20), LocalDate.of(2026, 8, 10),
                        calibrationProvenance()))));

        assertEquals(PhenologyCorrectionStatus.CORRECTED, result.status());
        assertEquals("ripening", result.effectiveStageCode());
        assertEquals(SourceKind.LOCAL_CALIBRATION, result.appliedEvidenceKind());
        assertEquals("VERIFIED_LOCAL_CALIBRATION", result.appliedEvidenceMarker());
        assertTrue(hasFinding(result.findings(), "PHENOLOGY_CORRECTION_SOURCE_CALIBRATION", Severity.INFO));
    }

    @Test
    void noTrustedPhenologyEvidenceKeepsBaselineWithoutGuessing() {
        PhenologyCorrection result = engine.correctPhenology(new PhenologyCorrectionRequest(
                "plum", LocalDate.of(2026, 6, 25), "fruit_development", "fruit_development",
                serverBaselineProvenance(),
                List.of(new PhenologyObservation("harvest", LocalDate.of(2026, 6, 24),
                        unverifiedProvenance(SourceKind.FIELD_OBSERVATION))),
                List.of()));

        assertEquals(PhenologyCorrectionStatus.NO_TRUSTED_EVIDENCE, result.status());
        assertEquals("fruit_development", result.effectiveStageCode());
        assertFalse(result.changed());
        assertTrue(result.unavailableData().contains("trustedPhenologyEvidence"));
    }

    private static boolean hasFinding(List<Finding> findings, String code, Severity severity) {
        return findings.stream().anyMatch(item -> item.code().equals(code) && item.severity() == severity);
    }

    private static Provenance publicLabel() {
        return new Provenance(SourceKind.PUBLIC_LABEL,
                "test-fixture://public-label/PLUM-DEMO-1", "2026-01", RETRIEVED, true, true);
    }

    private static Provenance orchardRecord() {
        return new Provenance(SourceKind.ORCHARD_RECORD,
                "test-fixture://orchard/certification", "v1", RETRIEVED, false, true);
    }

    private static Provenance measurementProvenance() {
        return new Provenance(SourceKind.MEASUREMENT,
                "test-fixture://harvest-measurements/run-1", "v1", RETRIEVED, false, true);
    }

    private static Provenance calibrationProvenance() {
        return new Provenance(SourceKind.LOCAL_CALIBRATION,
                "test-fixture://local-calibration/plum-1", "v1", RETRIEVED, false, true);
    }

    private static Provenance fieldObservation() {
        return new Provenance(SourceKind.FIELD_OBSERVATION,
                "test-fixture://field-observation/obs-1", "v1", RETRIEVED, false, true);
    }

    private static Provenance serverBaselineProvenance() {
        return new Provenance(SourceKind.OTHER,
                "test-fixture://server-phenology-rule/baseline", "v1", RETRIEVED, false, true);
    }

    private static Provenance unverifiedProvenance(SourceKind kind) {
        return new Provenance(kind, "test-fixture://unverified/" + kind.name(), "v1",
                RETRIEVED, false, false);
    }

    private static Provenance weatherProvenance(String windowId) {
        return new Provenance(SourceKind.WEATHER_FORECAST,
                "test-fixture://weather/" + windowId, "v1", RETRIEVED, false, true);
    }

    private static OrchardCertification noCertification() {
        return new OrchardCertification(CertificationStatus.NONE, orchardRecord(),
                Set.of(), Set.of(), Set.of(), Set.of());
    }

    private static SprayWeatherWindow goodSprayWindow(String id) {
        return new SprayWeatherWindow(id,
                LocalDateTime.of(2026, 6, 1, 8, 0), LocalDateTime.of(2026, 6, 1, 12, 0),
                24D, 65D, 6D, 0D, 10D, weatherProvenance(id));
    }

    private static HarvestWeatherWindow favorableHarvestWindow(String id) {
        return new HarvestWeatherWindow(id,
                LocalDateTime.of(2026, 7, 16, 7, 0), LocalDateTime.of(2026, 7, 16, 17, 0),
                0D, 10D, weatherProvenance(id));
    }
}
