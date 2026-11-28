package com.mei.zhgy.service.agriculture;

import com.mei.zhgy.service.copilot.CopilotMemoryProposalService;
import com.mei.zhgy.service.copilot.PlumAgronomyEvidenceCatalog;
import com.mei.zhgy.service.copilot.PlumAgronomyRuleEngine;
import com.mei.zhgy.service.field.FarmFieldService;
import com.mei.zhgy.service.field.FieldContext;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FieldFactsPhenologyIntegrationTest {
    @Test
    void authorizedProfileShiftsTheCalendarAndUsesTrustedThermalObservation() {
        LocalDate today = LocalDate.now(FieldFactsService.ZONE);
        var fields = mock(FarmFieldService.class);
        var memory = mock(CopilotMemoryProposalService.class);
        var calendar = new PhenologyService();
        when(fields.context("east", 41L)).thenReturn(field(today));
        when(memory.profileMap(41L, "42", "east")).thenReturn(Map.of("elevationMeters", 850D, "plum_variety", "青脆李"));
        var service = new FieldFactsService(fields, new CropResolver(), calendar);
        ReflectionTestUtils.setField(service, "memory", memory);

        var facts = service.forField("east", 41L);
        assertTrue(facts.ready());
        assertEquals(today.minusDays(8).toString(), facts.sources().get("phenologyEffectiveDate"));
        assertEquals(calendar.calculate("plum", today.minusDays(8), null).currentCode(), facts.dateDerivedGrowthStage());
        assertTrue(facts.sources().get("phenologyBasis").contains("海拔850m推迟8天"));
        assertTrue(facts.sources().get("phenologyBasis").contains("用户自报"));
        assertEquals("600.0", facts.sources().get("accumulatedTemperature"));
        verify(fields, never()).latestAccumulatedTemperature(any(), any());
    }

    @Test
    void validatedThermalCalibrationCanCorrectThePersonalizedCalendar() {
        LocalDate today = LocalDate.now(FieldFactsService.ZONE);
        var fields = mock(FarmFieldService.class);
        var catalog = mock(PlumAgronomyEvidenceCatalog.class);
        var calendar = new PhenologyService();
        when(fields.context("east", 41L)).thenReturn(field(today));
        when(catalog.calendarProfile(42, "east", "plum", today))
                .thenReturn(new PlumAgronomyEvidenceCatalog.CalendarProfile("青脆李", 850D));
        when(catalog.hasField(42, "east", "plum", today)).thenReturn(true);
        String baseline = calendar.calculate("plum", today.minusDays(8), null).currentCode();
        String expected = "budding".equals(baseline) ? "flowering" : "budding";
        var provenance = new PlumAgronomyRuleEngine.Provenance(PlumAgronomyRuleEngine.SourceKind.LOCAL_CALIBRATION,
                "https://example.test/calibration", "local-v1", Instant.now(), true, true);
        when(catalog.correction(42, "east", "plum", today, 600D, today)).thenReturn(
                new PlumAgronomyEvidenceCatalog.CorrectionInputs(List.of(), List.of(
                        new PlumAgronomyRuleEngine.PhenologyCalibrationWindow(expected, today.minusDays(1), today.plusDays(1), provenance))));
        var service = new FieldFactsService(fields, new CropResolver(), calendar);
        ReflectionTestUtils.setField(service, "agronomyEvidence", catalog);
        ReflectionTestUtils.setField(service, "agronomyRules", new PlumAgronomyRuleEngine());

        var facts = service.forField("east", 41L);
        assertEquals(baseline, facts.dateDerivedGrowthStage());
        assertEquals(expected, facts.currentGrowthStage());
        assertEquals("VERIFIED_PHENOLOGY_CORRECTION", facts.sources().get("currentStage"));
        assertTrue(facts.sources().get("phenologyBasis").contains("积温校准修正"));
    }

    private FieldContext field(LocalDate date) {
        return FieldContext.builder().farm(Map.of("id", 42)).field(Map.of("fieldId", "east"))
                .crop(Map.of("name", "青脆李"))
                .accumulatedTemperature(Map.of("status", "READY", "accumulatedTemp", 600D, "date", date.toString()))
                .build();
    }
}
