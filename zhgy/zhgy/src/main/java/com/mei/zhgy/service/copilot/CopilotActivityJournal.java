package com.mei.zhgy.service.copilot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Durable owner-scoped presentation journal, separate from model conversation turns. */
@Service
public class CopilotActivityJournal {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private volatile boolean initialized;
    public CopilotActivityJournal(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }
    private void schema() {
        if (initialized) return;
        synchronized (this) {
            if (initialized) return;
            jdbc.execute("CREATE TABLE IF NOT EXISTS copilot_activity_journal (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, conversation_id VARCHAR(96) NOT NULL, user_id BIGINT NOT NULL, payload_json TEXT NOT NULL, created_at TIMESTAMP(3) NOT NULL, KEY idx_copilot_activity_owner (conversation_id,user_id,id))");
            initialized = true;
        }
    }
    public void append(String conversationId, Long userId, Map<String, Object> event) {
        schema();
        try { jdbc.update("INSERT INTO copilot_activity_journal(conversation_id,user_id,payload_json,created_at) VALUES (?,?,?,?)", conversationId, userId, mapper.writeValueAsString(event), Timestamp.from(Instant.now())); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("Activity journal serialization failed", e); }
    }
    public Map<String, Object> read(String conversationId, Long userId) {
        schema();
        List<String> raw = jdbc.query("SELECT payload_json FROM copilot_activity_journal WHERE conversation_id=? AND user_id=? ORDER BY id DESC LIMIT 100", (rs, row) -> rs.getString(1), conversationId, userId);
        Collections.reverse(raw);
        List<Map<String, Object>> events = new ArrayList<>();
        Map<String, Map<String, Object>> inputs = new LinkedHashMap<>();
        for (String json : raw) try {
            Map<String, Object> entry = mapper.readValue(json, new TypeReference<>() {});
            events.add(entry);
            Object id = entry.get("requestId");
            if (id != null && entry.containsKey("operation")) {
                Map<String, Object> input = inputs.computeIfAbsent(id.toString(), ignored -> new LinkedHashMap<>());
                input.putAll(entry);
            }
        } catch (Exception ignored) { }
        // Acceptance by the worker is not proof that a queued input ran.
        // Keep it reviewable after worker restart; never replay it automatically.
        List<Map<String, Object>> pending = new ArrayList<>(inputs.values());
        return Map.of("events", events, "pendingInputs", pending);
    }
}
