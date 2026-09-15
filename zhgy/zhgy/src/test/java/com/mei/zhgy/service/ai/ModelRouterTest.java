package com.mei.zhgy.service.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelRouterTest {
    private final ModelRouter router = new ModelRouter(
            new ModelRoutingPolicy(),
            new ModelEscalationPolicy(),
            new ModelCapabilityRegistry());

    @Test
    void routesTasksToConfiguredModelRoles() {
        assertEquals(ModelCapabilityRegistry.QWEN_PLUS_DATED,
                router.route(AiTaskType.COPILOT, ModelRoutingContext.builder().build()).getRequestedModel());
        assertEquals(ModelCapabilityRegistry.QWEN_VL_32B,
                router.route(AiTaskType.MULTIMODAL_DIAGNOSIS,
                        ModelRoutingContext.builder().imageCount(2).build()).getRequestedModel());
        assertEquals(ModelCapabilityRegistry.QWEN_VL_32B,
                router.route(AiTaskType.HARD_DIAGNOSIS,
                        ModelRoutingContext.builder().build()).getRequestedModel());
    }

    @Test
    void routesConversationalQuestionsToFastModelWithSafeFallbacks() {
        ModelRoutingDecision decision = router.route(AiTaskType.COPILOT,
                ModelRoutingContext.builder().modelPolicy("force_fast").build());

        assertEquals(ModelCapabilityRegistry.DEEPSEEK_R1_DISTILL_QWEN_7B, decision.getRequestedModel());
        assertEquals("fast_conversational_policy", decision.getRoutingReason());
        assertTrue(decision.getFallbackModels().contains(ModelCapabilityRegistry.QWEN_PLUS_DATED));
    }

    @Test
    void escalatesLowConfidenceMultimodalDiagnosisWithFiniteFallback() {
        ModelRoutingDecision decision = router.route(AiTaskType.MULTIMODAL_DIAGNOSIS,
                ModelRoutingContext.builder().imageCount(2).diagnosisConfidence(0.4D).build());

        assertTrue(decision.isEscalated());
        assertEquals("low_confidence", decision.getRoutingReason());
        assertEquals(ModelCapabilityRegistry.QWEN_VL_32B, decision.getRequestedModel());
        assertTrue(decision.getFallbackModels().contains(ModelCapabilityRegistry.QWEN_PLUS_DATED));
        assertFalse(decision.getFallbackModels().contains(decision.getRequestedModel()));
    }
}
