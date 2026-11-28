package com.mei.zhgy.service.agriculture;

import com.mei.zhgy.service.knowledge.KnowledgeQueryRewriter;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CropAndPhenologyTest {
    @Test
    void resolvesSupportedAliasesAndRejectsUnknownCrops() {
        CropResolver resolver = new CropResolver();

        assertEquals("plum", resolver.resolve("巫山脆李").code());
        assertEquals("citrus", resolver.resolve("沃柑").code());
        assertEquals("rice", resolver.resolve("水稻").code());
        assertEquals("rice_late", resolver.resolve("晚稻").code());
        assertEquals("plum", resolver.resolve("晚熟脆李").code());
        assertEquals("plum", resolver.resolve("脆里").code());
        assertTrue(resolver.resolve("玉米").unknown());

        assertEquals("dormancy", new PhenologyService(resolver).resolveStage("脆李", "休眠").code());
    }

    @Test
    void derivesDifferentStagesForTheSameDate() {
        PhenologyService service = new PhenologyService();
        LocalDate date = LocalDate.of(2026, 9, 11);

        PhenologyResult plum = service.calculate("plum", date, null);
        PhenologyResult citrus = service.calculate("citrus", date, null);
        assertEquals("post_harvest", plum.currentCode());
        assertEquals("dormancy", plum.nextCode());
        assertEquals("harvest", plum.previousCode());
        assertEquals("fruit_development", citrus.currentCode());
        assertEquals("ripening", citrus.nextCode());
        assertEquals("fruiting", citrus.previousCode());
        assertEquals("post_cycle", service.calculate("rice", date, null).currentCode());
        assertEquals("filling", service.calculate("晚稻", date, null).currentCode());
    }

    @Test
    void registeredStageWinsButMismatchIsVisible() {
        PhenologyResult result = new PhenologyService().calculate("plum", LocalDate.of(2026, 9, 11), "flowering");

        assertEquals("flowering", result.currentCode());
        assertEquals("post_harvest", result.dateDerivedCode());
        assertTrue(result.mismatch());
    }

    @Test
    void publicQueryRewriteResolvesCropWithoutAField() {
        CropResolver crops = new CropResolver();
        assertEquals("plum", crops.resolve("plum current phenology").code());
        KnowledgeQueryRewriter.RewrittenQuery rewritten = new KnowledgeQueryRewriter(crops, new PhenologyService(crops))
                .rewrite("plum current phenology", null, null, Map.of(), null);

        assertEquals("plum", rewritten.cropCode());
        assertEquals("脆李", rewritten.cropLabel());
        assertTrue(rewritten.query().contains("作物：脆李"));

        KnowledgeQueryRewriter.RewrittenQuery followUp = new KnowledgeQueryRewriter(crops, new PhenologyService(crops))
                .rewrite("它 current stage", null, null, Map.of("recentConversation", "user: plum disease control"), null);
        assertEquals("plum", followUp.cropCode());
    }

    @Test
    void plumBaselineCoversEveryDayWithoutGapsOrOverlaps() {
        PhenologyService service = new PhenologyService();
        for (int year = 2024; year <= 2026; year++) {
            LocalDate first = LocalDate.of(year, 1, 1);
            for (int day = 0; day < first.lengthOfYear(); day++) {
                LocalDate date = first.plusDays(day);
                List<String> matches = PLUM_BASELINE.stream()
                        .filter(range -> range.contains(MonthDay.from(date)))
                        .map(ExpectedStage::code)
                        .toList();
                assertEquals(1, matches.size(), "expected exactly one baseline stage on " + date);
                assertEquals(matches.get(0), service.calculate("plum", date, null).currentCode(),
                        "baseline mismatch on " + date);
            }
        }
    }

    @Test
    void plumBoundaryDaysAndRipeningLabelAreExact() {
        PhenologyService service = new PhenologyService();
        List<Map.Entry<LocalDate, String>> boundaries = List.of(
                Map.entry(LocalDate.of(2026, 2, 20), "dormancy"),
                Map.entry(LocalDate.of(2026, 2, 21), "germination"),
                Map.entry(LocalDate.of(2026, 3, 4), "germination"),
                Map.entry(LocalDate.of(2026, 3, 5), "budding"),
                Map.entry(LocalDate.of(2026, 3, 14), "budding"),
                Map.entry(LocalDate.of(2026, 3, 15), "flowering"),
                Map.entry(LocalDate.of(2026, 3, 28), "flowering"),
                Map.entry(LocalDate.of(2026, 3, 29), "leafing"),
                Map.entry(LocalDate.of(2026, 4, 8), "leafing"),
                Map.entry(LocalDate.of(2026, 4, 9), "fruiting"),
                Map.entry(LocalDate.of(2026, 4, 25), "fruiting"),
                Map.entry(LocalDate.of(2026, 4, 26), "fruit_development"),
                Map.entry(LocalDate.of(2026, 6, 20), "fruit_development"),
                Map.entry(LocalDate.of(2026, 6, 21), "ripening"),
                Map.entry(LocalDate.of(2026, 7, 10), "ripening"),
                Map.entry(LocalDate.of(2026, 7, 11), "harvest"),
                Map.entry(LocalDate.of(2026, 8, 10), "harvest"),
                Map.entry(LocalDate.of(2026, 8, 11), "post_harvest"),
                Map.entry(LocalDate.of(2026, 11, 20), "post_harvest"),
                Map.entry(LocalDate.of(2026, 11, 21), "dormancy"),
                Map.entry(LocalDate.of(2026, 12, 31), "dormancy")
        );
        for (Map.Entry<LocalDate, String> boundary : boundaries) {
            assertEquals(boundary.getValue(), service.calculate("plum", boundary.getKey(), null).currentCode(),
                    "boundary mismatch on " + boundary.getKey());
        }
        assertEquals("转色成熟期", service.calculate("plum", LocalDate.of(2026, 7, 1), null).currentName());
    }

    @Test
    void countdownHandlesStageEdgesLeapDayAndYearWrap() {
        PhenologyService service = new PhenologyService();
        assertEquals(Long.valueOf(1), service.calculate("plum", LocalDate.of(2026, 3, 4), null).daysUntilNext());
        assertEquals(Long.valueOf(10), service.calculate("plum", LocalDate.of(2026, 3, 5), null).daysUntilNext());
        assertEquals(Long.valueOf(1), service.calculate("plum", LocalDate.of(2026, 11, 20), null).daysUntilNext());
        assertEquals(Long.valueOf(92), service.calculate("plum", LocalDate.of(2026, 11, 21), null).daysUntilNext());
        assertEquals(Long.valueOf(52), service.calculate("plum", LocalDate.of(2024, 12, 31), null).daysUntilNext());
        assertEquals(Long.valueOf(51), service.calculate("plum", LocalDate.of(2025, 1, 1), null).daysUntilNext());

        PhenologyResult leapDay = service.calculate("plum", LocalDate.of(2024, 2, 29), null);
        assertEquals("germination", leapDay.currentCode());
        assertEquals(Long.valueOf(5), leapDay.daysUntilNext());
        assertEquals("budding", service.calculate("plum", LocalDate.of(2025, 2, 28), null).nextCode());
    }

    @Test
    void altitudeOffsetsUseTheNominalBandAnchorsAndLateHighAltitudeRange() {
        PhenologyService service = new PhenologyService();
        LocalDate date = LocalDate.of(2026, 3, 21);

        assertEquals(-1, offset(service, date, 499D, null));
        assertEquals(0, offset(service, date, 500D, null));
        assertEquals(0, offset(service, date, 550D, null));
        assertEquals(0, offset(service, date, 600D, null));
        assertEquals(2, offset(service, date, 625D, null));
        assertEquals(3, offset(service, date, 650D, null));
        assertEquals(5, offset(service, date, 725D, null));
        assertEquals(7, offset(service, date, 800D, null));
        assertEquals(8, offset(service, date, 850D, null));
        assertEquals(8, offset(service, date, 850D, "晚熟"));
        assertEquals(9, offset(service, date, 900D, "早熟"));
        assertEquals(9, offset(service, date, 900D, "中熟"));
        assertEquals(9, offset(service, date, 900D, "晚熟"));
        assertEquals(9, offset(service, date, 900D, null));
        assertEquals(-12, service.calculate("plum", date, null, 550D, "早熟", null).varietyOffsetDays());
        assertEquals(15, service.calculate("plum", date, null, 800D, "晚熟", null).varietyOffsetDays());
        assertEquals(25, service.calculate("plum", date, null, 1200D, "晚熟", null).varietyOffsetDays());

        PhenologyService.PhenologyAssessment missing = service.calculate(
                "plum", date, null, null, null, null);
        assertEquals(0, missing.elevationAdjustment().offsetDays());
        assertEquals(PhenologyService.ElevationBasis.ASSUMED_NOMINAL_550M,
                missing.elevationAdjustment().basis());
        assertEquals(date, missing.effectiveDate());

        PhenologyService.PhenologyAssessment late = service.calculate(
                "plum", LocalDate.of(2026, 6, 18), null, 900D, "晚熟", null);
        assertEquals(27, late.totalOffsetDays());
        assertEquals(LocalDate.of(2026, 5, 22), late.effectiveDate());
        assertEquals("fruit_development", late.currentCode());

        PhenologyService.PhenologyAssessment early = service.calculate(
                "plum", LocalDate.of(2026, 3, 9), null, 550D, "早熟", null);
        assertEquals(LocalDate.of(2026, 3, 21), early.effectiveDate());
        assertEquals("flowering", early.currentCode());
    }

    @Test
    void altitudeAdjustedStageAndCountdownStayOnTheEffectiveDate() {
        PhenologyService service = new PhenologyService();
        PhenologyService.PhenologyAssessment assessment = service.calculate(
                "plum", LocalDate.of(2026, 3, 7), null, 850D, "青脆李", null);

        assertEquals(LocalDate.of(2026, 2, 27), assessment.effectiveDate());
        assertEquals("germination", assessment.currentCode());
        assertEquals("budding", assessment.nextCode());
        assertEquals(Long.valueOf(6), assessment.daysUntilNext());
        assertEquals(PhenologyService.StageBasis.DATE_PROFILE_ADJUSTED, assessment.basis());
        assertTrue(assessment.calendarExplanation().contains("海拔850m推迟8天"));

        PhenologyService.PhenologyAssessment citrus = service.calculate(
                "citrus", LocalDate.of(2026, 9, 11), null, 1200D, "晚熟", 200D);
        assertEquals(0, citrus.elevationAdjustment().offsetDays());
        assertEquals(PhenologyService.ElevationBasis.NOT_APPLICABLE,
                citrus.elevationAdjustment().basis());
        assertEquals("fruit_development", citrus.currentCode());
    }

    @Test
    void gddIsObservationOnlyAndMissingThresholdIsExplicit() {
        PhenologyService service = new PhenologyService();
        LocalDate date = LocalDate.of(2026, 5, 1);

        PhenologyService.PhenologyAssessment missing = service.calculate(
                "plum", date, null, 550D, null, null);
        assertEquals(PhenologyService.GddStatus.NOT_PROVIDED, missing.gddObservation().status());
        assertNull(missing.gddObservation().threshold());
        assertFalse(missing.gddObservation().applied());
        assertTrue(missing.assumptions().contains(PhenologyService.ASSUMPTION_GDD_THRESHOLD_NOT_CONFIGURED));
        assertTrue(missing.assumptions().contains(PhenologyService.ASSUMPTION_GDD_OBSERVATION_MISSING));

        PhenologyService.PhenologyAssessment observed = service.calculate(
                "plum", date, null, 550D, null, 88.5D);
        assertEquals(88.5D, observed.gddObservation().accumulated());
        assertEquals(PhenologyService.GddStatus.OBSERVED_THRESHOLD_NOT_CONFIGURED,
                observed.gddObservation().status());
        assertNull(observed.gddObservation().threshold());
        assertFalse(observed.gddObservation().applied());
        assertEquals(missing.currentCode(), observed.currentCode());
        assertTrue(observed.assumptions().contains(PhenologyService.ASSUMPTION_GDD_RULE_ENGINE_AUTHORITATIVE));
        assertTrue(observed.assumptions().contains(PhenologyService.ASSUMPTION_GDD_THRESHOLD_NOT_CONFIGURED));

        PhenologyService.PhenologyAssessment invalid = service.calculate(
                "plum", date, null, 550D, null, -1D);
        assertEquals(PhenologyService.GddStatus.INVALID, invalid.gddObservation().status());
        assertNull(invalid.gddObservation().threshold());
        assertTrue(invalid.assumptions().contains(PhenologyService.ASSUMPTION_GDD_OBSERVATION_INVALID));
    }

    @Test
    void registeredStageRemainsAuthoritativeInTheSixArgumentOverload() {
        PhenologyService.PhenologyAssessment assessment = new PhenologyService().calculate(
                "plum", LocalDate.of(2026, 3, 1), "果实膨大期", 850D, "晚熟", 100D);

        assertEquals("fruit_development", assessment.currentCode());
        assertEquals("dormancy", assessment.dateDerivedCode());
        assertEquals(PhenologyService.StageBasis.REGISTERED_STAGE, assessment.basis());
        assertTrue(assessment.mismatch());
    }

    @Test
    void everyPlumAliasResolvesAndAtlasAliasIndexIsInSync() {
        PhenologyService service = new PhenologyService();
        Map<String, String> aliases = PhenologyService.plumAtlasAliases();
        assertFalse(aliases.isEmpty());
        for (Map.Entry<String, String> alias : aliases.entrySet()) {
            PhenologyStage resolved = service.resolveStage("plum", alias.getKey());
            assertEquals(alias.getValue(), resolved == null ? null : resolved.code(),
                    "alias did not resolve: " + alias.getKey());
        }
        for (String required : List.of("果实膨大期", "膨果期", "膨大期", "膨果", "硬核期", "幼果膨大")) {
            assertEquals("fruit_development", aliases.get(required), required);
        }
        assertEquals("fruiting", service.resolveStage("plum", "幼果").code());
        assertEquals("ripening", service.resolveStage("plum", "成熟期").code());
        assertEquals("ripening", service.resolveStage("plum", "转色成熟期").code());
    }

    private static int offset(PhenologyService service, LocalDate date, Double elevation, String variety) {
        return service.calculate("plum", date, null, elevation, variety, null)
                .elevationAdjustment().offsetDays();
    }

    private static final List<ExpectedStage> PLUM_BASELINE = List.of(
            new ExpectedStage("dormancy", MonthDay.of(11, 21), MonthDay.of(2, 20)),
            new ExpectedStage("germination", MonthDay.of(2, 21), MonthDay.of(3, 4)),
            new ExpectedStage("budding", MonthDay.of(3, 5), MonthDay.of(3, 14)),
            new ExpectedStage("flowering", MonthDay.of(3, 15), MonthDay.of(3, 28)),
            new ExpectedStage("leafing", MonthDay.of(3, 29), MonthDay.of(4, 8)),
            new ExpectedStage("fruiting", MonthDay.of(4, 9), MonthDay.of(4, 25)),
            new ExpectedStage("fruit_development", MonthDay.of(4, 26), MonthDay.of(6, 20)),
            new ExpectedStage("ripening", MonthDay.of(6, 21), MonthDay.of(7, 10)),
            new ExpectedStage("harvest", MonthDay.of(7, 11), MonthDay.of(8, 10)),
            new ExpectedStage("post_harvest", MonthDay.of(8, 11), MonthDay.of(11, 20))
    );

    private record ExpectedStage(String code, MonthDay from, MonthDay to) {
        boolean contains(MonthDay value) {
            if (!from.isAfter(to)) return !value.isBefore(from) && !value.isAfter(to);
            return !value.isBefore(from) || !value.isAfter(to);
        }
    }
}
