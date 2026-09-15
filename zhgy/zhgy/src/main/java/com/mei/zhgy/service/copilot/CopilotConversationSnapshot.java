package com.mei.zhgy.service.copilot;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Server-owned bounded conversation state. Only stable IDs enter activeContext. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CopilotConversationSnapshot {
    private String conversationId;
    @JsonIgnore
    private Long userId;
    private String status;
    private CopilotStableContext activeContext;
    private CopilotIntent lastIntent;
    private String summary;
    private int summaryVersion;
    private int turnCount;
    private long version;
    private CopilotPendingClarification pendingClarification;
    private Instant createdAt;
    private Instant updatedAt;
    @JsonIgnore
    private Instant expiresAt;
    @Builder.Default
    private List<CopilotConversationTurn> recentTurns = new ArrayList<>();

    public CopilotConversationSnapshot copy() {
        return CopilotConversationSnapshot.builder()
                .conversationId(conversationId).userId(userId).status(status)
                .activeContext(activeContext == null ? null : activeContext.copy())
                .lastIntent(lastIntent).summary(summary).summaryVersion(summaryVersion)
                .turnCount(turnCount).version(version)
                .pendingClarification(pendingClarification == null ? null : CopilotPendingClarification.builder()
                        .type(pendingClarification.getType()).question(pendingClarification.getQuestion())
                        .createdAtTurn(pendingClarification.getCreatedAtTurn())
                        .expiresAfterTurns(pendingClarification.getExpiresAfterTurns()).build())
                .createdAt(createdAt).updatedAt(updatedAt).expiresAt(expiresAt)
                .recentTurns(recentTurns == null ? new ArrayList<>() : new ArrayList<>(recentTurns))
                .build();
    }
}
