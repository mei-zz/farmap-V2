package com.mei.zhgy.service.copilot;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** Canonical Copilot routing input. Direct IDs are explicit hints and pageContext is lower priority. */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CopilotRouteRequest {
    private String message;
    private String sessionId;
    private String conversationId;
    private String clientMessageId;
    private Long contextVersion;
    /** True only after the user explicitly reopens a saved conversation. */
    private Boolean useConversationContext;
    private CopilotPageContext pageContext;
    private String farmId;
    private String fieldId;
    private String diagnosisId;
    private String recommendationId;
    private String runId;
    private String actionId;
    private String taskId;
    private String outcomeId;
}
