package com.mei.zhgy.service.copilot;

import java.time.Duration;
import java.util.LinkedHashMap;

/** Carries verified grounding across Pi's in-memory pause boundary, without retaining tool content. */
final class CopilotContinuationGrounding {
    private static final int MAX_ENTRIES = 128;
    private static final long RETENTION = Duration.ofMinutes(30).toNanos();
    private final LinkedHashMap<Key, Saved> states = new LinkedHashMap<>();

    record Key(Long ownerId, String conversationId, long contextVersion, String userTurnId) {}
    record Evidence(boolean privateFacts, boolean publicFacts, int knowledgeCount) {
        static final Evidence EMPTY = new Evidence(false, false, 0);
        boolean grounded() { return privateFacts || publicFacts || knowledgeCount > 0; }
    }
    private record Saved(long expiresAt, Evidence evidence) {}

    synchronized void remember(Key key, Evidence evidence) {
        if (key == null || !evidence.grounded()) return;
        long now = System.nanoTime();
        states.values().removeIf(saved -> now - saved.expiresAt() >= 0);
        states.remove(key);
        if (states.size() >= MAX_ENTRIES) states.remove(states.keySet().iterator().next());
        states.put(key, new Saved(now + RETENTION, evidence));
    }

    synchronized Evidence read(Key key) {
        if (key == null) return Evidence.EMPTY;
        Saved saved = states.get(key);
        if (saved == null) return Evidence.EMPTY;
        if (System.nanoTime() - saved.expiresAt() >= 0) {
            states.remove(key);
            return Evidence.EMPTY;
        }
        return saved.evidence();
    }

    synchronized void clear(Key key) {
        if (key != null) states.remove(key);
    }
}
