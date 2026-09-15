package com.mei.zhgy.service.copilot;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Single-table MySQL persistence for explicit structured memory; no vector or cache store. */
@Repository
public class JdbcCopilotLongTermMemoryRepository implements CopilotLongTermMemoryRepository {
    private final JdbcTemplate jdbc;
    private volatile boolean schemaChecked;

    public JdbcCopilotLongTermMemoryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostConstruct
    public void ensureTable() { ensureSchema(); }

    @Override
    public Optional<CopilotLongTermMemory> findById(String memoryId, Long userId) {
        ensureSchema();
        return jdbc.query("SELECT memory_id,user_id,scope,scope_id,memory_type,memory_key,memory_value,display_text,status,source_conversation_id,source_turn_id,created_at,updated_at,version,supersedes_memory_id FROM copilot_long_term_memories WHERE memory_id=? AND user_id=?", (rs, row) -> map(rs), memoryId, userId).stream().findFirst();
    }

    @Override
    public Optional<CopilotLongTermMemory> findActiveBySemantic(Long userId, CopilotMemoryCandidate candidate) {
        ensureSchema();
        return jdbc.query("SELECT memory_id,user_id,scope,scope_id,memory_type,memory_key,memory_value,display_text,status,source_conversation_id,source_turn_id,created_at,updated_at,version,supersedes_memory_id FROM copilot_long_term_memories WHERE user_id=? AND status='ACTIVE' AND scope=? AND scope_id <=> ? AND memory_type=? AND memory_key=? AND memory_value=? LIMIT 1", (rs, row) -> map(rs), userId, candidate.getScope().name(), candidate.getScopeId(), candidate.getType().name(), candidate.getKey(), candidate.getValue()).stream().findFirst();
    }

    @Override
    public List<CopilotLongTermMemory> findActiveByKey(Long userId, CopilotMemoryCandidate candidate) {
        ensureSchema();
        return jdbc.query("SELECT memory_id,user_id,scope,scope_id,memory_type,memory_key,memory_value,display_text,status,source_conversation_id,source_turn_id,created_at,updated_at,version,supersedes_memory_id FROM copilot_long_term_memories WHERE user_id=? AND status='ACTIVE' AND scope=? AND scope_id <=> ? AND memory_key=? ORDER BY updated_at DESC", (rs, row) -> map(rs), userId, candidate.getScope().name(), candidate.getScopeId(), candidate.getKey());
    }

    @Override
    public List<CopilotLongTermMemory> findActive(Long userId, List<ScopeRef> scopes, int limit) {
        ensureSchema();
        if (scopes == null || scopes.isEmpty()) return List.of();
        StringBuilder sql = new StringBuilder("SELECT memory_id,user_id,scope,scope_id,memory_type,memory_key,memory_value,display_text,status,source_conversation_id,source_turn_id,created_at,updated_at,version,supersedes_memory_id FROM copilot_long_term_memories WHERE user_id=? AND status='ACTIVE' AND (");
        List<Object> args = new ArrayList<>(); args.add(userId);
        for (int i = 0; i < scopes.size(); i++) {
            if (i > 0) sql.append(" OR ");
            sql.append("(scope=? AND scope_id <=> ?)"); args.add(scopes.get(i).scope().name()); args.add(scopes.get(i).scopeId());
        }
        sql.append(") ORDER BY updated_at DESC LIMIT ").append(Math.max(1, Math.min(limit * 4, 80)));
        List<CopilotLongTermMemory> loaded = jdbc.query(sql.toString(), (rs, row) -> map(rs), args.toArray());
        List<CopilotLongTermMemory> result = new ArrayList<>();
        for (ScopeRef scope : scopes) for (CopilotLongTermMemory memory : loaded) if (memory.getScope() == scope.scope() && java.util.Objects.equals(memory.getScopeId(), scope.scopeId()) && result.stream().noneMatch(existing -> existing.getKey().equals(memory.getKey()))) { result.add(memory); break; }
        return result.stream().limit(limit).toList();
    }

    @Override
    @Transactional
    public CopilotLongTermMemory saveActive(CopilotLongTermMemory memory, CopilotLongTermMemory superseded) {
        ensureSchema();
        if (superseded != null) {
            int updated = jdbc.update("UPDATE copilot_long_term_memories SET status='SUPERSEDED',version=version+1,updated_at=? WHERE memory_id=? AND user_id=? AND status='ACTIVE' AND version=?", Timestamp.from(Instant.now()), superseded.getMemoryId(), superseded.getUserId(), superseded.getVersion());
            if (updated != 1) throw new CopilotConversationException("MEMORY_CONFLICT", "长期记忆已被其他请求更新。", 409);
        }
        jdbc.update("INSERT INTO copilot_long_term_memories(memory_id,user_id,scope,scope_id,memory_type,memory_key,memory_value,display_text,status,source_conversation_id,source_turn_id,created_at,updated_at,version,supersedes_memory_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", memory.getMemoryId(), memory.getUserId(), memory.getScope().name(), memory.getScopeId(), memory.getType().name(), memory.getKey(), memory.getValue(), memory.getDisplayText(), memory.getStatus().name(), memory.getSourceConversationId(), memory.getSourceTurnId(), timestamp(memory.getCreatedAt()), timestamp(memory.getUpdatedAt()), memory.getVersion(), memory.getSupersedesMemoryId());
        return memory;
    }

    @Override
    @Transactional
    public Optional<CopilotLongTermMemory> revoke(String memoryId, Long userId, long expectedVersion) {
        ensureSchema();
        int updated = jdbc.update("UPDATE copilot_long_term_memories SET status='REVOKED',version=version+1,updated_at=? WHERE memory_id=? AND user_id=? AND status='ACTIVE' AND version=?", Timestamp.from(Instant.now()), memoryId, userId, expectedVersion);
        return updated == 1 ? findById(memoryId, userId) : Optional.empty();
    }

    private CopilotLongTermMemory map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return CopilotLongTermMemory.builder().memoryId(rs.getString("memory_id")).userId(rs.getLong("user_id"))
                .scope(CopilotMemoryScope.valueOf(rs.getString("scope"))).scopeId(rs.getString("scope_id"))
                .type(CopilotMemoryType.valueOf(rs.getString("memory_type"))).key(rs.getString("memory_key"))
                .value(rs.getString("memory_value")).displayText(rs.getString("display_text"))
                .status(CopilotMemoryStatus.valueOf(rs.getString("status"))).sourceConversationId(rs.getString("source_conversation_id"))
                .sourceTurnId(rs.getString("source_turn_id")).createdAt(instant(rs.getTimestamp("created_at")))
                .updatedAt(instant(rs.getTimestamp("updated_at"))).version(rs.getLong("version"))
                .supersedesMemoryId(rs.getString("supersedes_memory_id")).build();
    }

    private void ensureSchema() {
        if (schemaChecked) return;
        synchronized (this) {
            if (schemaChecked) return;
            jdbc.execute("CREATE TABLE IF NOT EXISTS copilot_long_term_memories (memory_id VARCHAR(96) PRIMARY KEY,user_id BIGINT NOT NULL,scope VARCHAR(16) NOT NULL,scope_id VARCHAR(128) NULL,memory_type VARCHAR(64) NOT NULL,memory_key VARCHAR(128) NOT NULL,memory_value VARCHAR(256) NOT NULL,display_text TEXT NULL,status VARCHAR(16) NOT NULL,source_conversation_id VARCHAR(96) NOT NULL,source_turn_id VARCHAR(96) NOT NULL,created_at TIMESTAMP(3) NULL,updated_at TIMESTAMP(3) NULL,version BIGINT NOT NULL DEFAULT 0,supersedes_memory_id VARCHAR(96) NULL,INDEX idx_copilot_memory_owner_status (user_id,status),INDEX idx_copilot_memory_scope (user_id,scope,scope_id),INDEX idx_copilot_memory_key (user_id,memory_type,memory_key))");
            schemaChecked = true;
        }
    }

    private Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}
