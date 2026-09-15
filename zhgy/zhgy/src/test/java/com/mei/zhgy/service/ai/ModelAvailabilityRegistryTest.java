package com.mei.zhgy.service.ai;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelAvailabilityRegistryTest {
    @Test
    void exposesVerifiedStatusWithoutRemovingLegacyInternalStatus() {
        ModelCapabilityRegistry capabilities = new ModelCapabilityRegistry();
        ModelAvailabilityRegistry registry = new ModelAvailabilityRegistry(capabilities);

        registry.markAvailable(ModelCapabilityRegistry.QWEN36_PLUS, 42);

        Map<String, Object> model = registry.snapshot().stream()
                .filter(item -> ModelCapabilityRegistry.QWEN36_PLUS.equals(item.get("model")))
                .findFirst()
                .orElseThrow();
        assertEquals("available", model.get("status"));
        assertEquals("VERIFIED", model.get("verificationStatus"));
    }

    @Test
    void classifiesKnownFailureStates() {
        ModelCapabilityRegistry capabilities = new ModelCapabilityRegistry();
        ModelAvailabilityRegistry registry = new ModelAvailabilityRegistry(capabilities);

        registry.markUnavailable(ModelCapabilityRegistry.QWEN36_PLUS, "timeout");
        Map<String, Object> model = registry.snapshot().stream()
                .filter(item -> ModelCapabilityRegistry.QWEN36_PLUS.equals(item.get("model")))
                .findFirst()
                .orElseThrow();
        assertEquals("NETWORK_ERROR", model.get("verificationStatus"));
    }
}
