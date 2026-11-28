package com.mei.zhgy.service.copilot;

import com.mei.zhgy.service.agriculture.CropResolver;
import com.mei.zhgy.service.agriculture.FieldFactsService;
import com.mei.zhgy.service.agriculture.PhenologyService;
import com.mei.zhgy.service.copilot.CopilotApplicationService.CopilotChatRequest;
import com.mei.zhgy.service.field.FarmField;
import com.mei.zhgy.service.field.FarmFieldService;
import com.mei.zhgy.service.field.FieldContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CopilotFieldFactsResolverTest {
    @Test
    void unknownBoundCropDoesNotInheritTheQuestionCropInEitherReadPath() {
        var fields = mock(FarmFieldService.class);
        when(fields.get("field-demo", 7L)).thenReturn(FarmField.builder()
                .fieldId("field-demo").farmId(42).crop("目录外作物").build());
        when(fields.context("field-demo", 7L)).thenReturn(FieldContext.builder()
                .farm(Map.of("id", 42)).field(Map.of("fieldId", "field-demo")).crop(Map.of("name", "目录外作物")).build());
        var crops = new CropResolver();
        var phenology = new PhenologyService(crops);
        var request = new CopilotChatRequest();
        request.setMessage("当前这个地块的脆李是什么物候期？");
        var route = CopilotRouteResult.builder().resolutionStatus(CopilotResolutionStatus.RESOLVED)
                .resolvedContext(CopilotStableContext.builder().farmId("42").fieldId("field-demo").build()).build();

        var resolver = new CopilotFieldFactsResolver(fields, new FieldFactsService(fields, crops, phenology), crops,
                phenology, (scope, actor) -> Map.of(), (chat, actor) -> "脆李");
        var compatibility = new CopilotFieldFactsResolver(fields, null, crops, phenology,
                (scope, actor) -> Map.of(), (chat, actor) -> "脆李");
        for (var actual : java.util.List.of(resolver.scopeOnly(request, route, 7L), resolver.resolve(request, route, 7L),
                compatibility.resolve(request, route, 7L))) {
            assertEquals("UNKNOWN_CROP", actual.status());
            assertNull(actual.crop());
            assertNull(actual.growthStageLabel());
        }

        var unbound = resolver.resolve(request, CopilotRouteResult.builder().build(), 7L);
        assertEquals("PUBLIC_GENERIC", unbound.status());
        assertEquals("plum", unbound.crop());
    }

    @Test
    void continuationGroundingIsNarrowedToOwnerConversationVersionAndTurn() {
        var grounding = new CopilotContinuationGrounding();
        var original = new CopilotContinuationGrounding.Key(7L, "chat-a", 2L, "turn-1");
        grounding.remember(original, new CopilotContinuationGrounding.Evidence(false, false, 2));
        assertTrue(grounding.read(original).grounded());
        assertFalse(grounding.read(new CopilotContinuationGrounding.Key(8L, "chat-a", 2L, "turn-1")).grounded());
        assertFalse(grounding.read(new CopilotContinuationGrounding.Key(7L, "chat-b", 2L, "turn-1")).grounded());
        assertFalse(grounding.read(new CopilotContinuationGrounding.Key(7L, "chat-a", 3L, "turn-1")).grounded());
        assertFalse(grounding.read(new CopilotContinuationGrounding.Key(7L, "chat-a", 2L, "turn-2")).grounded());
        grounding.clear(original);
        assertFalse(grounding.read(original).grounded());
    }
}
