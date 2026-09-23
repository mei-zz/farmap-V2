package com.mei.zhgy.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelGatewayTest {
    @Test
    void fallsBackFromExpertToReasoner() {
        ModelCapabilityRegistry capabilities = new ModelCapabilityRegistry();
        ModelAvailabilityRegistry availability = new ModelAvailabilityRegistry(capabilities);
        BailianModelClient fakeClient = new BailianModelClient(new ObjectMapper()) {
            @Override
            public ModelCallResult call(String model, ModelCallRequest request, ModelRole role) {
                if (ModelCapabilityRegistry.QWEN_VL_235B.equals(model)) {
                    throw new ModelCallException("provider_unavailable", "test unavailable");
                }
                return ModelCallResult.builder()
                        .success(true)
                        .selectedModel(model)
                        .modelRole(role)
                        .latencyMs(1L)
                        .usage(ModelUsageMetadata.builder().provider("test").model(model).build())
                        .content("{}")
                        .build();
            }
        };
        ModelRoutingPolicy routingPolicy = new ModelRoutingPolicy();
        // Keep this test explicit: production now defaults to the verified
        // 32B VL model, while this case verifies the legacy 235B -> 32B fallback.
        ReflectionTestUtils.setField(routingPolicy, "expertModel", ModelCapabilityRegistry.QWEN_VL_235B);
        ModelGateway gateway = new ModelGateway(
                new ModelRouter(routingPolicy, new ModelEscalationPolicy(), capabilities),
                capabilities,
                availability,
                fakeClient,
                new ModelUsageRecorder());

        ModelCallResult result = gateway.complete(ModelCallRequest.builder()
                .taskType(AiTaskType.HARD_DIAGNOSIS)
                .userPrompt("test")
                .systemPrompt("test")
                .context(new java.util.LinkedHashMap<>())
                .build());

        assertEquals(ModelCapabilityRegistry.QWEN_VL_32B, result.getSelectedModel());
        assertEquals(Arrays.asList(ModelCapabilityRegistry.QWEN_VL_235B, ModelCapabilityRegistry.QWEN_VL_32B), result.getFallbackChain());
    }
}
