package com.mei.zhgy.service.ai;

import lombok.Data;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 只记录模型可用性元数据，不保存 API Key 或响应正文。 */
@Component
public class ModelAvailabilityRegistry {
    private final ModelCapabilityRegistry capabilityRegistry;
    private final Map<String, Availability> states = new ConcurrentHashMap<>();

    public ModelAvailabilityRegistry(ModelCapabilityRegistry capabilityRegistry) {
        this.capabilityRegistry = capabilityRegistry;
        capabilityRegistry.all().forEach(definition -> states.put(definition.getModel(), new Availability()));
    }

    public void markAvailable(String model, long latencyMs) {
        Availability availability = states.computeIfAbsent(model, key -> new Availability());
        availability.setStatus("available");
        availability.setLastCheckedAt(Instant.now().toString());
        availability.setLastLatencyMs(latencyMs);
        availability.setErrorType(null);
    }

    public void markUnavailable(String model, String errorType) {
        Availability availability = states.computeIfAbsent(model, key -> new Availability());
        availability.setStatus("unavailable");
        availability.setLastCheckedAt(Instant.now().toString());
        availability.setErrorType(errorType);
    }

    public String status(String model) {
        Availability availability = states.get(model);
        return availability == null || availability.getStatus() == null ? "unknown" : availability.getStatus();
    }

    public List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> result = new ArrayList<>();
        capabilityRegistry.all().forEach(definition -> {
            Availability availability = states.get(definition.getModel());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("model", definition.getModel());
            item.put("role", definition.getRole().name());
            item.put("multimodal", definition.isMultimodal());
            item.put("capabilities", definition.getCapabilities());
            item.put("status", availability == null || availability.getStatus() == null ? "unknown" : availability.getStatus());
            item.put("verificationStatus", verificationStatus(availability));
            if (availability != null) {
                item.put("lastCheckedAt", availability.getLastCheckedAt());
                item.put("lastLatencyMs", availability.getLastLatencyMs());
                item.put("errorType", availability.getErrorType());
            }
            result.add(item);
        });
        return result;
    }

    private String verificationStatus(Availability availability) {
        if (availability == null || availability.getStatus() == null) return "UNVERIFIED";
        if ("available".equals(availability.getStatus())) return "VERIFIED";
        if ("auth_failed".equals(availability.getErrorType()) || "missing_api_key".equals(availability.getErrorType())) return "AUTH_FAILED";
        if ("permission_denied".equals(availability.getErrorType())) return "PERMISSION_DENIED";
        if ("model_not_found".equals(availability.getErrorType())) return "MODEL_NOT_FOUND";
        if ("timeout".equals(availability.getErrorType())) return "NETWORK_ERROR";
        return "UNAVAILABLE";
    }

    @Data
    private static class Availability {
        private String status;
        private String lastCheckedAt;
        private Long lastLatencyMs;
        private String errorType;
    }
}
