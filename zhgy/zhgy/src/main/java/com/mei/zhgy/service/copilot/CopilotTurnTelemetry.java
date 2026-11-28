package com.mei.zhgy.service.copilot;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Records the observable results of one turn. It never stores prompts or tool arguments. */
final class CopilotTurnTelemetry {
    private final long started = System.nanoTime();
    private final Map<String, ToolTiming> toolCalls = new LinkedHashMap<>();
    private final Map<String, String> emittedBlocks = new LinkedHashMap<>();
    private final Map<String, Long> usage = new LinkedHashMap<>();
    private final LinkedHashSet<String> models = new LinkedHashSet<>();
    private String selectedModel;
    private String executionMode = "BACKEND_DIRECT";
    private String groundingLayer;
    private Long ttftMs;

    synchronized void mode(String value) { executionMode = value; }

    synchronized void token() {
        if (ttftMs == null) ttftMs = elapsed(started);
    }

    synchronized void source(Object layer) {
        if (layer instanceof String label && !label.isBlank()) groundingLayer = label;
    }

    synchronized void model(Object name, Map<?, ?> counts) {
        if (name instanceof String label && label.matches("[A-Za-z0-9_.:/-]{1,180}")) {
            selectedModel = label;
            models.add(label);
        }
        if (counts == null) return;
        for (String key : List.of("inputTokens", "outputTokens", "cacheReadTokens")) {
            if (counts.get(key) instanceof Number count && count.longValue() >= 0) {
                usage.merge(key, count.longValue(), Long::sum);
            }
        }
    }

    synchronized void event(Map<String, Object> event) {
        Object kind = event.get("type");
        if ("message_end".equals(kind)) {
            model(event.get("selectedModel"), event.get("usage") instanceof Map<?, ?> counts ? counts : null);
            return;
        }
        if (!(event.get("toolName") instanceof String name)) return;
        String id = event.get("toolCallId") instanceof String callId ? callId : name;
        if ("tool_execution_start".equals(kind) && toolCalls.size() < 100) {
            toolCalls.putIfAbsent(id, new ToolTiming(name));
        } else if ("tool_execution_end".equals(kind)) {
            ToolTiming call = toolCalls.get(id);
            if (call != null && call.duration == null) {
                call.duration = elapsed(call.started);
                call.status = Boolean.TRUE.equals(event.get("isError")) ? "failed" : "completed";
            }
        }
    }

    synchronized void block(Map<String, Object> block) {
        if (block.get("blockId") instanceof String id && block.get("componentType") instanceof String type) {
            emittedBlocks.putIfAbsent(id + ":" + type, type);
        }
    }

    synchronized Map<String, Object> snapshot(String traceId) {
        var record = new LinkedHashMap<String, Object>();
        record.put("traceId", traceId);
        record.put("executionMode", executionMode);
        record.put("selectedModel", selectedModel);
        record.put("modelsUsed", List.copyOf(models));
        record.put("ttftMs", ttftMs);
        record.put("totalLatencyMs", elapsed(started));
        for (String key : List.of("inputTokens", "outputTokens", "cacheReadTokens")) record.put(key, usage.get(key));
        record.put("toolCalls", toolCalls.values().stream().map(ToolTiming::snapshot).toList());
        record.put("emittedBlocks", List.copyOf(emittedBlocks.values()));
        record.put("groundingLayer", groundingLayer);
        return record;
    }

    private static long elapsed(long since) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - since);
    }

    private static final class ToolTiming {
        final long started = System.nanoTime();
        final String name;
        Long duration;
        String status = "incomplete";

        private ToolTiming(String name) { this.name = name; }

        Map<String, Object> snapshot() {
            return Map.of("name", name, "latencyMs", duration == null ? elapsed(started) : duration, "status", status);
        }
    }
}
