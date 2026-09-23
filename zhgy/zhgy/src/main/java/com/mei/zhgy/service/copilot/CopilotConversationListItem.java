package com.mei.zhgy.service.copilot;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/** User-owned, compact entry for the Copilot conversation switcher. */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CopilotConversationListItem {
    String conversationId;
    String title;
    String status;
    CopilotIntent lastIntent;
    int turnCount;
    Instant updatedAt;
    CopilotStableContext activeContext;
}
