package com.mei.zhgy.service.ai;

import com.mei.zhgy.config.AnswerPoolProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

class ModelRouterAnswerPoolTest {
    @Test
    void poolCandidatesPrecedeOriginalFallbackChain() {
        AnswerPoolProperties properties = new AnswerPoolProperties();
        properties.setModels(List.of("pool-a", "pool-b", "pool-c", "pool-d"));
        properties.setSamePoolFallbackN(2);
        AnswerModelPool pool = new AnswerModelPool(properties,
                new ModelAvailabilityRegistry(new ModelCapabilityRegistry()), () -> 0);
        ModelRoutingPolicy routingPolicy = new ModelRoutingPolicy();
        ModelRouter router = new ModelRouter(routingPolicy, new ModelEscalationPolicy(),
                new ModelCapabilityRegistry(), pool);

        ModelRoutingDecision decision = router.route(AiTaskType.COPILOT, ModelRoutingContext.builder().build());

        assertEquals("pool-a", decision.getRequestedModel());
        assertIterableEquals(List.of("pool-c", "pool-d", ModelCapabilityRegistry.QWEN_MAX,
                ModelCapabilityRegistry.QWEN_MT_FLASH, ModelCapabilityRegistry.QWEN36_PLUS_FALLBACK),
                decision.getFallbackModels());
        assertEquals("task_policy;answer_pool:random", decision.getRoutingReason());
    }

    @Test
    void poolDoesNotChangeForcedOrEscalatedRoutes() {
        AnswerPoolProperties properties = new AnswerPoolProperties();
        properties.setModels(List.of("pool-a", "pool-b"));
        AnswerModelPool pool = new AnswerModelPool(properties,
                new ModelAvailabilityRegistry(new ModelCapabilityRegistry()), () -> 0);
        ModelRoutingPolicy routingPolicy = new ModelRoutingPolicy();
        ModelRouter router = new ModelRouter(routingPolicy, new ModelEscalationPolicy(),
                new ModelCapabilityRegistry(), pool);

        ModelRoutingDecision forced = router.route(AiTaskType.COPILOT,
                ModelRoutingContext.builder().modelPolicy("force_fast").build());
        ModelRoutingDecision multimodal = router.route(AiTaskType.MULTIMODAL_DIAGNOSIS,
                ModelRoutingContext.builder().build());
        ModelRoutingDecision hard = router.route(AiTaskType.HARD_DIAGNOSIS,
                ModelRoutingContext.builder().build());

        assertEquals(routingPolicy.getFastModel(), forced.getRequestedModel());
        assertFalse(forced.getRoutingReason().contains("answer_pool"));
        assertEquals(routingPolicy.getMultimodalModel(), multimodal.getRequestedModel());
        assertEquals(routingPolicy.getExpertModel(), hard.getRequestedModel());
    }

    @Test
    void disabledPoolKeepsLegacyDefaultAndFallbacks() {
        AnswerPoolProperties properties = new AnswerPoolProperties();
        properties.setEnabled(false);
        properties.setModels(List.of("pool-a", "pool-b"));
        AnswerModelPool pool = new AnswerModelPool(properties,
                new ModelAvailabilityRegistry(new ModelCapabilityRegistry()), () -> 0);
        ModelRoutingPolicy routingPolicy = new ModelRoutingPolicy();
        ModelRouter router = new ModelRouter(routingPolicy, new ModelEscalationPolicy(),
                new ModelCapabilityRegistry(), pool);

        ModelRoutingDecision decision = router.route(AiTaskType.COPILOT, ModelRoutingContext.builder().build());

        assertEquals(routingPolicy.getDefaultModel(), decision.getRequestedModel());
        assertFalse(decision.getRoutingReason().contains("answer_pool"));
        assertIterableEquals(List.of(ModelCapabilityRegistry.QWEN_MAX,
                ModelCapabilityRegistry.QWEN_MT_FLASH, ModelCapabilityRegistry.QWEN36_PLUS_FALLBACK),
                decision.getFallbackModels());
    }

    @Test
    void allUnavailablePoolKeepsLegacyDefaultAndFallbacks() {
        ModelCapabilityRegistry capabilities = new ModelCapabilityRegistry();
        ModelAvailabilityRegistry availability = new ModelAvailabilityRegistry(capabilities);
        availability.markUnavailable("pool-a", "permission_denied");
        AnswerPoolProperties properties = new AnswerPoolProperties();
        properties.setModels(List.of("pool-a"));
        AnswerModelPool pool = new AnswerModelPool(properties, availability, () -> 0);
        ModelRoutingPolicy routingPolicy = new ModelRoutingPolicy();
        ModelRouter router = new ModelRouter(routingPolicy, new ModelEscalationPolicy(), capabilities, pool);

        ModelRoutingDecision decision = router.route(AiTaskType.COPILOT, ModelRoutingContext.builder().build());

        assertEquals(routingPolicy.getDefaultModel(), decision.getRequestedModel());
        assertIterableEquals(List.of(ModelCapabilityRegistry.QWEN_MAX,
                ModelCapabilityRegistry.QWEN_MT_FLASH, ModelCapabilityRegistry.QWEN36_PLUS_FALLBACK),
                decision.getFallbackModels());
    }
}
