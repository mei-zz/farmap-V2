package com.mei.zhgy.controller;

import com.mei.zhgy.result.Result;
import com.mei.zhgy.service.copilot.intent.IntentModelPool;
import com.mei.zhgy.service.copilot.intent.IntentModelProperties;
import com.mei.zhgy.service.copilot.intent.LlmIntentClassifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只读意图影子诊断接口：不返回原文或密钥，也不改变业务路由。
 * The reset endpoint only clears observation state and does not trigger model calls.
 */
@RestController
@RequestMapping("/ai-model/intent")
public class IntentObservabilityController {
    private final LlmIntentClassifier classifier;
    private final IntentModelProperties properties;
    private final IntentModelPool pool;

    public IntentObservabilityController(LlmIntentClassifier classifier,
                                         IntentModelProperties properties,
                                         IntentModelPool pool) {
        this.classifier = classifier;
        this.properties = properties;
        this.pool = pool;
    }

    @GetMapping("/metrics")
    public Result<Map<String, Object>> metrics() {
        Map<String, Object> mode = new LinkedHashMap<>();
        mode.put("enabled", safeEnabled());
        mode.put("decisionMode", safeDecisionMode());
        mode.put("shadowMode", safeShadowMode());
        mode.put("routingMode", safeRoutingMode());
        mode.put("takeoverThreshold", safeTakeoverThreshold());
        mode.put("escalateThreshold", safeEscalateThreshold());
        mode.put("totalTimeoutMs", safeTotalTimeoutMs());

        Map<String, Object> poolSizes = new LinkedHashMap<>();
        poolSizes.put("primary", primaryPoolSize());
        poolSizes.put("flash", listSize(() -> properties.getFlashPool()));
        poolSizes.put("strong", listSize(() -> properties.getStrongPool()));
        mode.put("poolSizes", poolSizes);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("mode", mode);
        response.put("metrics", classifier.metricsSnapshot());
        return Result.success(response);
    }

    @GetMapping("/samples")
    public Result<List<LlmIntentClassifier.IntentShadowSample>> samples(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "false") boolean mismatchOnly) {
        return Result.success(classifier.recentSamples(limit, mismatchOnly));
    }

    @PostMapping("/metrics/reset")
    public Result<Map<String, Boolean>> reset() {
        classifier.resetMetrics();
        return Result.success(Map.of("reset", true));
    }

    private boolean safeEnabled() {
        try { return properties.isEnabled(); } catch (RuntimeException ignored) { return false; }
    }

    private boolean safeShadowMode() {
        try { return properties.effectiveShadowMode(); } catch (RuntimeException ignored) { return true; }
    }

    private String safeDecisionMode() {
        try { return properties.effectiveDecisionMode(); } catch (RuntimeException ignored) { return "SHADOW"; }
    }

    private String safeRoutingMode() {
        try { return properties.getRoutingMode(); } catch (RuntimeException ignored) { return "unknown"; }
    }

    private double safeTakeoverThreshold() {
        try { return properties.getTakeoverThreshold(); } catch (RuntimeException ignored) { return -1D; }
    }

    private double safeEscalateThreshold() {
        try { return properties.getEscalateThreshold(); } catch (RuntimeException ignored) { return -1D; }
    }

    private int safeTotalTimeoutMs() {
        try { return properties.getTotalTimeoutMs(); } catch (RuntimeException ignored) { return -1; }
    }

    private int primaryPoolSize() {
        try {
            return properties.getPrimaryModel() == null || properties.getPrimaryModel().isBlank() ? 0 : 1;
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private int listSize(ListSupplier supplier) {
        try {
            List<String> values = supplier.get();
            return values == null ? -1 : values.size();
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    @FunctionalInterface
    private interface ListSupplier {
        List<String> get();
    }
}
