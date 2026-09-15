package com.mei.zhgy.service.copilot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Isolated deterministic repository for evaluation; production uses MySQL. */
public class InMemoryCopilotLongTermMemoryRepository implements CopilotLongTermMemoryRepository {
    private final Map<String, CopilotLongTermMemory> memories = new LinkedHashMap<>();

    @Override
    public synchronized Optional<CopilotLongTermMemory> findById(String memoryId, Long userId) {
        return Optional.ofNullable(memories.get(memoryId)).filter(memory -> java.util.Objects.equals(memory.getUserId(), userId)).map(this::copy);
    }

    @Override
    public synchronized Optional<CopilotLongTermMemory> findActiveBySemantic(Long userId, CopilotMemoryCandidate candidate) {
        return memories.values().stream().filter(memory -> memory.getStatus() == CopilotMemoryStatus.ACTIVE)
                .filter(memory -> java.util.Objects.equals(memory.getUserId(), userId)).filter(memory -> same(memory, candidate)).findFirst().map(this::copy);
    }

    @Override
    public synchronized List<CopilotLongTermMemory> findActiveByKey(Long userId, CopilotMemoryCandidate candidate) {
        return memories.values().stream().filter(memory -> memory.getStatus() == CopilotMemoryStatus.ACTIVE)
                .filter(memory -> java.util.Objects.equals(memory.getUserId(), userId)).filter(memory -> sameKey(memory, candidate)).map(this::copy).toList();
    }

    @Override
    public synchronized List<CopilotLongTermMemory> findActive(Long userId, List<ScopeRef> scopes, int limit) {
        List<CopilotLongTermMemory> result = new ArrayList<>();
        for (ScopeRef scope : scopes) {
            memories.values().stream().filter(memory -> memory.getStatus() == CopilotMemoryStatus.ACTIVE)
                    .filter(memory -> java.util.Objects.equals(memory.getUserId(), userId))
                    .filter(memory -> memory.getScope() == scope.scope() && java.util.Objects.equals(memory.getScopeId(), scope.scopeId()))
                    .sorted(Comparator.comparing(CopilotLongTermMemory::getUpdatedAt).reversed()).forEach(memory -> {
                        if (result.size() < limit && result.stream().noneMatch(existing -> existing.getKey().equals(memory.getKey()))) result.add(copy(memory));
                    });
        }
        return result;
    }

    @Override
    public synchronized CopilotLongTermMemory saveActive(CopilotLongTermMemory memory, CopilotLongTermMemory superseded) {
        if (superseded != null) {
            CopilotLongTermMemory old = memories.get(superseded.getMemoryId());
            if (old != null && old.getVersion() == superseded.getVersion() && old.getStatus() == CopilotMemoryStatus.ACTIVE) {
                old.setStatus(CopilotMemoryStatus.SUPERSEDED); old.setVersion(old.getVersion() + 1); old.setUpdatedAt(Instant.now());
            }
        }
        memories.put(memory.getMemoryId(), copy(memory));
        return copy(memory);
    }

    @Override
    public synchronized Optional<CopilotLongTermMemory> revoke(String memoryId, Long userId, long expectedVersion) {
        CopilotLongTermMemory memory = memories.get(memoryId);
        if (memory == null || !java.util.Objects.equals(memory.getUserId(), userId) || memory.getStatus() != CopilotMemoryStatus.ACTIVE || memory.getVersion() != expectedVersion) return Optional.empty();
        memory.setStatus(CopilotMemoryStatus.REVOKED); memory.setVersion(memory.getVersion() + 1); memory.setUpdatedAt(Instant.now());
        return Optional.of(copy(memory));
    }

    private boolean same(CopilotLongTermMemory memory, CopilotMemoryCandidate candidate) { return sameKey(memory, candidate) && java.util.Objects.equals(memory.getValue(), candidate.getValue()); }
    private boolean sameKey(CopilotLongTermMemory memory, CopilotMemoryCandidate candidate) { return memory.getScope() == candidate.getScope() && java.util.Objects.equals(memory.getScopeId(), candidate.getScopeId()) && java.util.Objects.equals(memory.getKey(), candidate.getKey()); }
    private CopilotLongTermMemory copy(CopilotLongTermMemory memory) { return CopilotLongTermMemory.builder().memoryId(memory.getMemoryId()).userId(memory.getUserId()).scope(memory.getScope()).scopeId(memory.getScopeId()).type(memory.getType()).key(memory.getKey()).value(memory.getValue()).displayText(memory.getDisplayText()).status(memory.getStatus()).sourceConversationId(memory.getSourceConversationId()).sourceTurnId(memory.getSourceTurnId()).createdAt(memory.getCreatedAt()).updatedAt(memory.getUpdatedAt()).version(memory.getVersion()).supersedesMemoryId(memory.getSupersedesMemoryId()).build(); }
}
