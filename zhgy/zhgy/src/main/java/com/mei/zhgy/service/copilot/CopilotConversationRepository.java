package com.mei.zhgy.service.copilot;

import java.util.List;
import java.util.Optional;

public interface CopilotConversationRepository {
    CopilotConversationSnapshot create(CopilotConversationSnapshot snapshot);
    Optional<CopilotConversationSnapshot> find(String conversationId, Long userId);
    List<CopilotConversationSnapshot> list(Long userId, int limit);
    Optional<String> firstUserMessage(String conversationId, Long userId);
    List<CopilotConversationTurn> recentTurns(String conversationId, Long userId, int limit);
    /** Full owner-scoped transcript for the conversation history UI. */
    List<CopilotConversationTurn> allTurns(String conversationId, Long userId);
    Optional<CopilotConversationTurn> findByClientMessageId(String conversationId, Long userId, String clientMessageId);
    boolean commit(CopilotConversationSnapshot next, long expectedVersion, List<CopilotConversationTurn> turns);
    void appendTurn(CopilotConversationTurn turn);
    void updateResult(String conversationId, String turnId, Long userId, String resultJson);
}
