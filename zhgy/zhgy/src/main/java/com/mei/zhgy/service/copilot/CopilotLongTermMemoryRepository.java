package com.mei.zhgy.service.copilot;

import java.util.List;
import java.util.Optional;

public interface CopilotLongTermMemoryRepository {
    Optional<CopilotLongTermMemory> findById(String memoryId, Long userId);
    Optional<CopilotLongTermMemory> findActiveBySemantic(Long userId, CopilotMemoryCandidate candidate);
    List<CopilotLongTermMemory> findActiveByKey(Long userId, CopilotMemoryCandidate candidate);
    List<CopilotLongTermMemory> findActive(Long userId, List<ScopeRef> scopes, int limit);
    CopilotLongTermMemory saveActive(CopilotLongTermMemory memory, CopilotLongTermMemory superseded);
    Optional<CopilotLongTermMemory> revoke(String memoryId, Long userId, long expectedVersion);
    record ScopeRef(CopilotMemoryScope scope, String scopeId) {}
}
