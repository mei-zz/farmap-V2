package com.mei.zhgy.service.reference;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReferenceImageAttacherTest {
    @Test
    void explicitStageQuestionUsesTheRequestedStage() {
        ReferenceImageStore store = mock(ReferenceImageStore.class);
        ReferenceImage image = new ReferenceImage(
                "plum-pheno-germination-01", "PHENOLOGY", "germination", "", "萌芽期",
                "https://example.com/germination.jpg", "mid", "direct");
        when(store.byStageResult("plum", "germination"))
                .thenReturn(ReferenceImageLookup.ready(List.of(image), 6, 6, "direct"));

        ReferenceImageAttacher attacher = new ReferenceImageAttacher(
                new DiseaseCatalog(), store, new ReferenceImageProperties());

        ReferenceImageLookup result = attacher.forChatResult("脆李萌芽期图片", "plum", "ripening");

        assertEquals("READY", result.status());
        assertEquals("plum-pheno-germination-01", result.images().get(0).refId());
        verify(store).byStageResult("plum", "germination");
        verify(store, never()).byStageResult("plum", "ripening");
    }

    @Test
    void fruitEnlargementAliasUsesTheFruitDevelopmentAtlas() {
        ReferenceImageStore store = mock(ReferenceImageStore.class);
        ReferenceImage image = new ReferenceImage(
                "plum-pheno-fruit_development-01", "PHENOLOGY", "fruit_development", "", "果实发育期",
                "https://example.com/fruit-development.jpg", "mid", "direct");
        when(store.byStageResult("plum", "fruit_development"))
                .thenReturn(ReferenceImageLookup.ready(List.of(image), 6, 6, "direct"));

        ReferenceImageAttacher attacher = new ReferenceImageAttacher(
                new DiseaseCatalog(), store, new ReferenceImageProperties());

        ReferenceImageLookup result = attacher.forChatResult("给我一张果实膨大期的照片", "plum", null);

        assertEquals("READY", result.status());
        assertEquals("plum-pheno-fruit_development-01", result.images().get(0).refId());
        verify(store).byStageResult("plum", "fruit_development");
    }

    @Test
    void voiceSlipForFruitEnlargementStillUsesTheFruitDevelopmentAtlas() {
        ReferenceImageStore store = mock(ReferenceImageStore.class);
        ReferenceImage image = new ReferenceImage(
                "plum-pheno-fruit_development-voice-slip", "PHENOLOGY", "fruit_development", "", "果实发育期",
                "https://example.com/fruit-development.jpg", "mid", "direct");
        when(store.byStageResult("plum", "fruit_development"))
                .thenReturn(ReferenceImageLookup.ready(List.of(image), 1, 1, "direct"));

        ReferenceImageAttacher attacher = new ReferenceImageAttacher(
                new DiseaseCatalog(), store, new ReferenceImageProperties());

        ReferenceImageLookup result = attacher.forChatResult("给我一张果实棚大期的照片", "plum", null);

        assertEquals("READY", result.status());
        assertEquals("plum-pheno-fruit_development-voice-slip", result.images().get(0).refId());
        verify(store).byStageResult("plum", "fruit_development");
    }
}
