package com.mei.zhgy.service.agriculture;

import com.mei.zhgy.service.knowledge.KnowledgeQueryRewriter;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
