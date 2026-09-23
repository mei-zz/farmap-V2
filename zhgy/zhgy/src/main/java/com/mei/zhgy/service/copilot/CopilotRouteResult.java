package com.mei.zhgy.service.copilot;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mei.zhgy.service.copilot.intent.IntentSlots;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Stable server result consumed by the web Copilot and deterministic evaluations. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CopilotRouteResult {
    private CopilotIntent intent;
    private CopilotResolutionStatus resolutionStatus;
    private CopilotStableContext resolvedContext;
    private CopilotCapability capability;
    private boolean requiresClarification;
    private String clarificationQuestion;
    private String allowedNextAction;
    private String safeRoute;
    private String traceId;
    private double confidence;
    private String conversationId;
    private String turnId;
    private String memoryStatus;
    private Long contextVersion;
    private String memoryId;
    private CopilotMemoryScope memoryScope;
    private CopilotMemoryType memoryType;
    private List<CopilotMemoryView> relevantLongTermMemories;
    // Hybrid intent diagnostics. These are nullable so existing consumers can
    // continue to deserialize the original route contract unchanged.
    private CopilotIntent ruleIntent;
    private CopilotIntent llmIntent;
    private CopilotIntent finalIntent;
    private IntentSlots intentSlots;
    private String intentTier;
    private String intentModel;
    private Double intentModelConfidence;
    private Long intentLatencyMs;
    private String intentFallbackReason;
    /** Additive understanding contract used by SSE/observability consumers. */
    private String originalQuery;
    private String normalizedQuery;
    private CopilotMediaNeed mediaNeed;
    /** Canonical action text after pending-confirmation recovery. */
    private String requestedAction;

    /** Binary-compatible constructor kept for callers compiled against the original DTO. */
    public CopilotRouteResult(CopilotIntent intent, CopilotResolutionStatus resolutionStatus,
                              CopilotStableContext resolvedContext, CopilotCapability capability,
                              boolean requiresClarification, String clarificationQuestion,
                              String allowedNextAction, String safeRoute, String traceId,
                              double confidence, String conversationId, String turnId,
                              String memoryStatus, Long contextVersion, String memoryId,
                              CopilotMemoryScope memoryScope, CopilotMemoryType memoryType,
                              List<CopilotMemoryView> relevantLongTermMemories) {
        this.intent = intent;
        this.resolutionStatus = resolutionStatus;
        this.resolvedContext = resolvedContext;
        this.capability = capability;
        this.requiresClarification = requiresClarification;
        this.clarificationQuestion = clarificationQuestion;
        this.allowedNextAction = allowedNextAction;
        this.safeRoute = safeRoute;
        this.traceId = traceId;
        this.confidence = confidence;
        this.conversationId = conversationId;
        this.turnId = turnId;
        this.memoryStatus = memoryStatus;
        this.contextVersion = contextVersion;
        this.memoryId = memoryId;
        this.memoryScope = memoryScope;
        this.memoryType = memoryType;
        this.relevantLongTermMemories = relevantLongTermMemories;
    }
}
