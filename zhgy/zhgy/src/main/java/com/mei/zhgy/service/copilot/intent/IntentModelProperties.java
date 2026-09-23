package com.mei.zhgy.service.copilot.intent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Externalized policy for the optional hybrid intent classifier. */
@Component
@ConfigurationProperties(prefix = "farmap.ai.router.intent")
public class IntentModelProperties {
    private boolean enabled = true;
    private boolean shadowMode = true;
    /**
     * Explicit decision mode for controlled rollout. Blank keeps the legacy
     * shadowMode compatibility behavior: true -> SHADOW, false -> HYBRID.
     */
    private String decisionMode = "";
    private String routingMode = "primary";
    private double primaryWeight = 0.70D;
    private double takeoverThreshold = 0.70D;
    private double escalateThreshold = 0.55D;
    // Model identifiers are supplied by application configuration so Java code
    // does not silently select a model outside the configured pool.
    private String primaryModel = "";
    private List<String> flashPool = new ArrayList<>();
    private List<String> strongPool = new ArrayList<>();
    private int connectTimeoutMs = 1000;
    private int readTimeoutMs = 1700;
    private int totalTimeoutMs = 1800;
    private int maxTokens = 300;
    private int cacheSize = 512;
    private int contextMaxChars = 512;
    private int maxConcurrentCalls = 4;
    private int queueCapacity = 16;
    private int recentSampleCapacity = 200;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isShadowMode() { return shadowMode; }
    public void setShadowMode(boolean shadowMode) { this.shadowMode = shadowMode; }
    public String getDecisionMode() { return decisionMode; }
    public void setDecisionMode(String decisionMode) {
        if (decisionMode == null || decisionMode.isBlank()) {
            this.decisionMode = "";
            return;
        }
        String normalized = decisionMode.trim().toUpperCase();
        this.decisionMode = switch (normalized) {
            case "RULE_ONLY", "SHADOW", "HYBRID" -> normalized;
            default -> "";
        };
    }
    public String effectiveDecisionMode() {
        if (!decisionMode.isBlank()) return decisionMode;
        return shadowMode ? "SHADOW" : "HYBRID";
    }
    public boolean effectiveShadowMode() { return "SHADOW".equals(effectiveDecisionMode()); }
    public String getRoutingMode() { return routingMode; }
    public void setRoutingMode(String routingMode) { this.routingMode = routingMode == null ? "weighted" : routingMode.trim().toLowerCase(); }
    public double getPrimaryWeight() { return primaryWeight; }
    public void setPrimaryWeight(double primaryWeight) { this.primaryWeight = clamp(primaryWeight, 0D, 1D); }
    public double getTakeoverThreshold() { return takeoverThreshold; }
    public void setTakeoverThreshold(double takeoverThreshold) { this.takeoverThreshold = clamp(takeoverThreshold, 0D, 1D); }
    public double getEscalateThreshold() { return escalateThreshold; }
    public void setEscalateThreshold(double escalateThreshold) { this.escalateThreshold = clamp(escalateThreshold, 0D, 1D); }
    public String getPrimaryModel() { return primaryModel; }
    public void setPrimaryModel(String primaryModel) { this.primaryModel = primaryModel == null ? "" : primaryModel.trim(); }
    public List<String> getFlashPool() { return flashPool; }
    public void setFlashPool(List<String> flashPool) { this.flashPool = clean(flashPool); }
    public List<String> getStrongPool() { return strongPool; }
    public void setStrongPool(List<String> strongPool) { this.strongPool = clean(strongPool); }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = Math.max(1000, connectTimeoutMs); }
    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = Math.max(1000, readTimeoutMs); }
    public int getTotalTimeoutMs() { return totalTimeoutMs; }
    public void setTotalTimeoutMs(int totalTimeoutMs) { this.totalTimeoutMs = Math.max(100, Math.min(10000, totalTimeoutMs)); }
    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = Math.max(256, Math.min(300, maxTokens)); }
    public int getCacheSize() { return cacheSize; }
    public void setCacheSize(int cacheSize) { this.cacheSize = Math.max(0, Math.min(10000, cacheSize)); }
    public int getContextMaxChars() { return contextMaxChars; }
    public void setContextMaxChars(int contextMaxChars) { this.contextMaxChars = Math.max(128, Math.min(512, contextMaxChars)); }
    public int getMaxConcurrentCalls() { return maxConcurrentCalls; }
    public void setMaxConcurrentCalls(int maxConcurrentCalls) { this.maxConcurrentCalls = Math.max(1, Math.min(64, maxConcurrentCalls)); }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = Math.max(1, Math.min(1024, queueCapacity)); }
    public int getRecentSampleCapacity() { return recentSampleCapacity; }
    public void setRecentSampleCapacity(int recentSampleCapacity) { this.recentSampleCapacity = Math.max(0, Math.min(1000, recentSampleCapacity)); }

    private double clamp(double value, double min, double max) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : min;
    }

    private List<String> clean(List<String> values) {
        if (values == null) return new ArrayList<>();
        return values.stream().filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList();
    }
}
