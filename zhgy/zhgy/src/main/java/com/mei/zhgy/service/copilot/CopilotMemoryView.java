package com.mei.zhgy.service.copilot;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/** Safe API projection: no owner ID, raw payload, credentials, or hidden reasoning. */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CopilotMemoryView {
    String memoryId;
    CopilotMemoryScope scope;
    String scopeId;
    CopilotMemoryType type;
    String key;
    String value;
    String displayText;
    CopilotMemoryStatus status;
    String sourceConversationId;
    String sourceTurnId;
    Instant createdAt;
    Instant updatedAt;

    public static CopilotMemoryView from(CopilotLongTermMemory memory) {
        return CopilotMemoryView.builder().memoryId(memory.getMemoryId()).scope(memory.getScope()).scopeId(memory.getScopeId())
                .type(memory.getType()).key(memory.getKey()).value(memory.getValue()).displayText(memory.getDisplayText())
                .status(memory.getStatus()).sourceConversationId(memory.getSourceConversationId()).sourceTurnId(memory.getSourceTurnId())
                .createdAt(memory.getCreatedAt()).updatedAt(memory.getUpdatedAt()).build();
    }
}
