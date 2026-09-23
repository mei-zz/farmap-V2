package com.mei.zhgy.service.copilot;

import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Deterministic repository used by isolated evaluations; production uses JdbcCopilotConversationRepository. */
public class InMemoryCopilotConversationRepository implements CopilotConversationRepository {
    private final Map<String, CopilotConversationSnapshot> conversations = new LinkedHashMap<>();
    private final Map<String, List<CopilotConversationTurn>> turns = new LinkedHashMap<>();

    @Override
    public synchronized CopilotConversationSnapshot create(CopilotConversationSnapshot snapshot) {
        conversations.put(snapshot.getConversationId(), snapshot.copy());
        turns.put(snapshot.getConversationId(), new ArrayList<>());
        return snapshot.copy();
    }

    @Override
    public synchronized Optional<CopilotConversationSnapshot> find(String conversationId, Long userId) {
        CopilotConversationSnapshot snapshot = conversations.get(conversationId);
        if (snapshot == null || !java.util.Objects.equals(snapshot.getUserId(), userId)) return Optional.empty();
        return Optional.of(withTurns(snapshot));
    }

    @Override
    public synchronized List<CopilotConversationSnapshot> list(Long userId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        return conversations.values().stream()
                .filter(snapshot -> java.util.Objects.equals(snapshot.getUserId(), userId))
                .sorted(Comparator.comparing(CopilotConversationSnapshot::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(safeLimit)
                .map(CopilotConversationSnapshot::copy)
                .toList();
    }

    @Override
    public synchronized Optional<String> firstUserMessage(String conversationId, Long userId) {
        return turns.getOrDefault(conversationId, List.of()).stream()
                .filter(turn -> java.util.Objects.equals(turn.getUserId(), userId) && "USER".equals(turn.getRole()))
                .min(Comparator.comparing(CopilotConversationTurn::getCreatedAt))
                .map(CopilotConversationTurn::getMessage);
    }

    @Override
    public synchronized List<CopilotConversationTurn> recentTurns(String conversationId, Long userId, int limit) {
        CopilotConversationSnapshot snapshot = conversations.get(conversationId);
        if (snapshot == null || !java.util.Objects.equals(snapshot.getUserId(), userId)) return List.of();
        List<CopilotConversationTurn> values = new ArrayList<>(turns.getOrDefault(conversationId, List.of()));
        values.sort(Comparator.comparing(CopilotConversationTurn::getCreatedAt));
        int from = Math.max(0, values.size() - Math.max(0, limit));
        return new ArrayList<>(values.subList(from, values.size()));
    }

    @Override
    public synchronized List<CopilotConversationTurn> allTurns(String conversationId, Long userId) {
        return recentTurns(conversationId, userId, Integer.MAX_VALUE);
    }

    @Override
    public synchronized Optional<CopilotConversationTurn> findByClientMessageId(String conversationId, Long userId, String clientMessageId) {
        return recentTurns(conversationId, userId, Integer.MAX_VALUE).stream()
                .filter(turn -> java.util.Objects.equals(clientMessageId, turn.getClientMessageId())).findFirst();
    }

    @Override
    public synchronized boolean commit(CopilotConversationSnapshot next, long expectedVersion, List<CopilotConversationTurn> newTurns) {
        CopilotConversationSnapshot current = conversations.get(next.getConversationId());
        if (current == null || current.getVersion() != expectedVersion || !java.util.Objects.equals(current.getUserId(), next.getUserId())) return false;
        conversations.put(next.getConversationId(), next.copy());
        turns.computeIfAbsent(next.getConversationId(), ignored -> new ArrayList<>()).addAll(newTurns);
        return true;
    }

    @Override
    public synchronized void appendTurn(CopilotConversationTurn turn) {
        CopilotConversationSnapshot current = conversations.get(turn.getConversationId());
        if (current == null || !java.util.Objects.equals(current.getUserId(), turn.getUserId())) {
            throw new CopilotConversationException("CONVERSATION_NOT_FOUND", "conversation 不存在或不属于当前用户。", 404);
        }
        current.setTurnCount(current.getTurnCount() + 1);
        current.setUpdatedAt(turn.getCreatedAt());
        turns.computeIfAbsent(turn.getConversationId(), ignored -> new ArrayList<>()).add(turn);
    }

    @Override
    public synchronized void updateResult(String conversationId, String turnId, Long userId, String resultJson) {
        turns.getOrDefault(conversationId, List.of()).stream().filter(turn -> turnId.equals(turn.getTurnId()) && java.util.Objects.equals(userId, turn.getUserId())).findFirst().ifPresent(turn -> turn.setResultJson(resultJson));
    }

    private CopilotConversationSnapshot withTurns(CopilotConversationSnapshot snapshot) {
        CopilotConversationSnapshot copy = snapshot.copy();
        copy.setRecentTurns(recentTurns(snapshot.getConversationId(), snapshot.getUserId(), 10));
        return copy;
    }
}
