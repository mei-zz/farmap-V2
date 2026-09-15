package com.mei.zhgy.service.copilot;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.util.List;

/** Minimal refresh payload; it deliberately omits owner identity and raw internal state. */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CopilotConversationView {
    String conversationId;
    String status;
    CopilotStableContext activeContext;
    CopilotIntent lastIntent;
    String summary;
    int summaryVersion;
    int turnCount;
    long contextVersion;
    CopilotPendingClarification pendingClarification;
    List<CopilotConversationTurn> recentTurns;
    List<CopilotMemoryView> relevantLongTermMemories;
}
