package com.mei.zhgy.service.copilot;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded, session-scoped active-ID context. This is an accelerator only;
 * every business read is reloaded from its authoritative repository.
 */
@Component
public class CopilotSessionContextStore {
    private static final int MAX_SESSIONS = 2048;
    private static final Duration TTL = Duration.ofMinutes(30);
    private final Map<String, CopilotStableContext> sessions = new ConcurrentHashMap<>();

    public Optional<CopilotStableContext> find(String sessionId) {
        String key = normalize(sessionId);
        if (key == null) return Optional.empty();
        CopilotStableContext value = sessions.get(key);
        if (value == null) return Optional.empty();
        if (value.getUpdatedAt() == null || value.getUpdatedAt().plus(TTL).isBefore(Instant.now())) {
            sessions.remove(key, value);
            return Optional.empty();
        }
        return Optional.of(value.copy());
    }

    public void update(String sessionId, CopilotStableContext context, CopilotIntent intent) {
        String key = normalize(sessionId);
        if (key == null || context == null) return;
        if (sessions.size() >= MAX_SESSIONS && !sessions.containsKey(key)) evictOne();
        CopilotStableContext next = context.copy();
        next.setLastIntent(intent);
        next.setUpdatedAt(Instant.now());
        sessions.put(key, next);
    }

    public int size() { return sessions.size(); }

    private void evictOne() {
        sessions.entrySet().stream().min((left, right) -> {
            Instant l = left.getValue().getUpdatedAt();
            Instant r = right.getValue().getUpdatedAt();
            if (l == null) return r == null ? 0 : -1;
            if (r == null) return 1;
            return l.compareTo(r);
        }).map(Map.Entry::getKey).ifPresent(sessions::remove);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
