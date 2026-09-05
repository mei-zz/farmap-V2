package com.mei.zhgy.service.ai;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 显式触发三模型探测；应用启动时不主动消耗 API 配额。 */
@Service
public class ModelAvailabilityService {
    private final ModelCapabilityRegistry capabilityRegistry;
    private final ModelAvailabilityRegistry availabilityRegistry;
    private final BailianModelClient bailianModelClient;

    public ModelAvailabilityService(ModelCapabilityRegistry capabilityRegistry,
                                    ModelAvailabilityRegistry availabilityRegistry,
                                    BailianModelClient bailianModelClient) {
        this.capabilityRegistry = capabilityRegistry;
        this.availabilityRegistry = availabilityRegistry;
        this.bailianModelClient = bailianModelClient;
    }

    public List<Map<String, Object>> probeAll() {
        List<Map<String, Object>> results = new ArrayList<>();
        capabilityRegistry.all().forEach(definition -> {
            java.util.LinkedHashMap<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("model", definition.getModel());
            item.put("role", definition.getRole().name());
            try {
                long start = System.currentTimeMillis();
                bailianModelClient.probe(definition.getModel());
                availabilityRegistry.markAvailable(definition.getModel(), System.currentTimeMillis() - start);
                item.put("status", "available");
            } catch (ModelCallException exception) {
                availabilityRegistry.markUnavailable(definition.getModel(), exception.getErrorType());
                item.put("status", "unavailable");
                item.put("errorType", exception.getErrorType());
            }
            results.add(item);
        });
        return results;
    }
}
