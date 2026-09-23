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

/** MySQL-backed bounded conversation state. No Redis or vector memory is used in Phase 1.5. */
@Repository
public class JdbcCopilotConversationRepository implements CopilotConversationRepository {
    private final JdbcTemplate jdbc;
    private volatile boolean schemaChecked;

    public JdbcCopilotConversationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void ensureTable() {
        ensureSchema();
    }

    @Override
    public CopilotConversationSnapshot create(CopilotConversationSnapshot snapshot) {
        ensureSchema();
        jdbc.update("INSERT INTO copilot_conversations(conversation_id,user_id,status,active_farm_id,active_field_id,active_diagnosis_id,active_recommendation_id,active_run_id,active_action_id,active_task_id,active_outcome_id,last_intent,summary_json,summary_version,turn_count,version,pending_type,pending_question,pending_created_turn,pending_expires_after_turn,created_at,updated_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                snapshot.getConversationId(), snapshot.getUserId(), snapshot.getStatus(), id(snapshot.getActiveContext(), "farmId"), id(snapshot.getActiveContext(), "fieldId"), id(snapshot.getActiveContext(), "diagnosisId"), id(snapshot.getActiveContext(), "recommendationId"), id(snapshot.getActiveContext(), "runId"), id(snapshot.getActiveContext(), "actionId"), id(snapshot.getActiveContext(), "taskId"), id(snapshot.getActiveContext(), "outcomeId"), enumName(snapshot.getLastIntent()), snapshot.getSummary(), snapshot.getSummaryVersion(), snapshot.getTurnCount(), snapshot.getVersion(), pending(snapshot, "type"), pending(snapshot, "question"), pendingInteger(snapshot, "created"), pendingInteger(snapshot, "expires"), timestamp(snapshot.getCreatedAt()), timestamp(snapshot.getUpdatedAt()), timestamp(snapshot.getExpiresAt()));
        return snapshot.copy();
    }

    @Override
    public Optional<CopilotConversationSnapshot> find(String conversationId, Long userId) {
        ensureSchema();
        List<CopilotConversationSnapshot> result = jdbc.query("SELECT conversation_id,user_id,status,active_farm_id,active_field_id,active_diagnosis_id,active_recommendation_id,active_run_id,active_action_id,active_task_id,active_outcome_id,last_intent,summary_json,summary_version,turn_count,version,pending_type,pending_question,pending_created_turn,pending_expires_after_turn,created_at,updated_at,expires_at FROM copilot_conversations WHERE conversation_id=? AND user_id=?", (rs, row) -> row(rs), conversationId, userId);
        if (result.isEmpty()) return Optional.empty();
        CopilotConversationSnapshot snapshot = result.get(0);
        snapshot.setRecentTurns(recentTurns(conversationId, userId, 10));
        return Optional.of(snapshot);
    }

    @Override
    public List<CopilotConversationSnapshot> list(Long userId, int limit) {
        ensureSchema();
        int safeLimit = Math.max(1, Math.min(limit, 50));
        return jdbc.query("SELECT conversation_id,user_id,status,active_farm_id,active_field_id,active_diagnosis_id,active_recommendation_id,active_run_id,active_action_id,active_task_id,active_outcome_id,last_intent,summary_json,summary_version,turn_count,version,pending_type,pending_question,pending_created_turn,pending_expires_after_turn,created_at,updated_at,expires_at FROM copilot_conversations WHERE user_id=? ORDER BY updated_at DESC LIMIT " + safeLimit,
                (rs, row) -> row(rs), userId);
    }

    @Override
    public Optional<String> firstUserMessage(String conversationId, Long userId) {
        ensureSchema();
        return jdbc.query("SELECT message FROM copilot_turns WHERE conversation_id=? AND user_id=? AND role='USER' ORDER BY created_at ASC LIMIT 1",
                (rs, row) -> rs.getString("message"), conversationId, userId).stream().findFirst();
    }

    @Override
    public List<CopilotConversationTurn> recentTurns(String conversationId, Long userId, int limit) {
        ensureSchema();
        int safeLimit = Math.max(1, Math.min(limit, 10));
        List<CopilotConversationTurn> reverse = jdbc.query("SELECT turn_id,conversation_id,user_id,role,message,intent,trace_id,client_message_id,result_json,created_at FROM copilot_turns WHERE conversation_id=? AND user_id=? ORDER BY created_at DESC LIMIT " + safeLimit, (rs, row) -> turn(rs), conversationId, userId);
        List<CopilotConversationTurn> result = new ArrayList<>(reverse);
        java.util.Collections.reverse(result);
        return result;
    }

    @Override
    public List<CopilotConversationTurn> allTurns(String conversationId, Long userId) {
        ensureSchema();
        return jdbc.query("SELECT turn_id,conversation_id,user_id,role,message,intent,trace_id,client_message_id,result_json,created_at FROM copilot_turns WHERE conversation_id=? AND user_id=? ORDER BY created_at ASC", (rs, row) -> turn(rs), conversationId, userId);
    }

    @Override
    public Optional<CopilotConversationTurn> findByClientMessageId(String conversationId, Long userId, String clientMessageId) {
        ensureSchema();
        List<CopilotConversationTurn> result = jdbc.query("SELECT turn_id,conversation_id,user_id,role,message,intent,trace_id,client_message_id,result_json,created_at FROM copilot_turns WHERE conversation_id=? AND user_id=? AND client_message_id=? LIMIT 1", (rs, row) -> turn(rs), conversationId, userId, clientMessageId);
        return result.stream().findFirst();
    }

    @Override
    @Transactional
    public boolean commit(CopilotConversationSnapshot next, long expectedVersion, List<CopilotConversationTurn> turns) {
        ensureSchema();
        int updated = jdbc.update("UPDATE copilot_conversations SET status=?,active_farm_id=?,active_field_id=?,active_diagnosis_id=?,active_recommendation_id=?,active_run_id=?,active_action_id=?,active_task_id=?,active_outcome_id=?,last_intent=?,summary_json=?,summary_version=?,turn_count=?,version=?,pending_type=?,pending_question=?,pending_created_turn=?,pending_expires_after_turn=?,updated_at=?,expires_at=? WHERE conversation_id=? AND user_id=? AND version=?",
                next.getStatus(), id(next.getActiveContext(), "farmId"), id(next.getActiveContext(), "fieldId"), id(next.getActiveContext(), "diagnosisId"), id(next.getActiveContext(), "recommendationId"), id(next.getActiveContext(), "runId"), id(next.getActiveContext(), "actionId"), id(next.getActiveContext(), "taskId"), id(next.getActiveContext(), "outcomeId"), enumName(next.getLastIntent()), next.getSummary(), next.getSummaryVersion(), next.getTurnCount(), next.getVersion(), pending(next, "type"), pending(next, "question"), pendingInteger(next, "created"), pendingInteger(next, "expires"), timestamp(next.getUpdatedAt()), timestamp(next.getExpiresAt()), next.getConversationId(), next.getUserId(), expectedVersion);
        if (updated != 1) return false;
        for (CopilotConversationTurn turn : turns) {
            jdbc.update("INSERT INTO copilot_turns(turn_id,conversation_id,user_id,role,message,intent,trace_id,client_message_id,result_json,created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    turn.getTurnId(), turn.getConversationId(), turn.getUserId(), turn.getRole(), turn.getMessage(), enumName(turn.getIntent()), turn.getTraceId(), turn.getClientMessageId(), turn.getResultJson(), timestamp(turn.getCreatedAt()));
        }
        return true;
    }

    @Override
    @Transactional
    public void appendTurn(CopilotConversationTurn turn) {
        ensureSchema();
        int owner = jdbc.queryForObject("SELECT COUNT(*) FROM copilot_conversations WHERE conversation_id=? AND user_id=?", Integer.class,
                turn.getConversationId(), turn.getUserId());
        if (owner != 1) throw new CopilotConversationException("CONVERSATION_NOT_FOUND", "conversation 不存在或不属于当前用户。", 404);
        jdbc.update("INSERT INTO copilot_turns(turn_id,conversation_id,user_id,role,message,intent,trace_id,client_message_id,result_json,created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                turn.getTurnId(), turn.getConversationId(), turn.getUserId(), turn.getRole(), turn.getMessage(), enumName(turn.getIntent()), turn.getTraceId(), turn.getClientMessageId(), turn.getResultJson(), timestamp(turn.getCreatedAt()));
        // Do not advance the optimistic context version: the answer does not
        // mutate routing context, but it must count as a visible conversation turn.
        Instant now = Instant.now();
        jdbc.update("UPDATE copilot_conversations SET turn_count=turn_count+1,updated_at=?,expires_at=? WHERE conversation_id=? AND user_id=?",
                timestamp(now), timestamp(now.plus(java.time.Duration.ofDays(30))), turn.getConversationId(), turn.getUserId());
    }

    @Override
    public void updateResult(String conversationId, String turnId, Long userId, String resultJson) {
        ensureSchema();
        jdbc.update("UPDATE copilot_turns SET result_json=? WHERE conversation_id=? AND turn_id=? AND user_id=? AND role='USER'", resultJson, conversationId, turnId, userId);
    }

    private CopilotConversationSnapshot row(java.sql.ResultSet rs) throws java.sql.SQLException {
        CopilotStableContext context = CopilotStableContext.builder().farmId(rs.getString("active_farm_id"))
                .fieldId(rs.getString("active_field_id")).diagnosisId(rs.getString("active_diagnosis_id"))
                .recommendationId(rs.getString("active_recommendation_id")).runId(rs.getString("active_run_id"))
                .actionId(rs.getString("active_action_id")).taskId(rs.getString("active_task_id"))
                .outcomeId(rs.getString("active_outcome_id")).build();
        String pendingType = rs.getString("pending_type");
        CopilotPendingClarification pending = pendingType == null ? null : CopilotPendingClarification.builder()
                .type(pendingType).question(rs.getString("pending_question"))
                .createdAtTurn(rs.getInt("pending_created_turn")).expiresAfterTurns(rs.getInt("pending_expires_after_turn")).build();
        return CopilotConversationSnapshot.builder().conversationId(rs.getString("conversation_id")).userId(rs.getLong("user_id"))
                .status(rs.getString("status")).activeContext(context).lastIntent(enumValue(rs.getString("last_intent")))
                .summary(rs.getString("summary_json")).summaryVersion(rs.getInt("summary_version"))
                .turnCount(rs.getInt("turn_count")).version(rs.getLong("version")).pendingClarification(pending)
                .createdAt(instant(rs.getTimestamp("created_at"))).updatedAt(instant(rs.getTimestamp("updated_at")))
                .expiresAt(instant(rs.getTimestamp("expires_at"))).recentTurns(new ArrayList<>()).build();
    }

    private CopilotConversationTurn turn(java.sql.ResultSet rs) throws java.sql.SQLException {
        return CopilotConversationTurn.builder().turnId(rs.getString("turn_id")).conversationId(rs.getString("conversation_id"))
                .userId(rs.getLong("user_id")).role(rs.getString("role")).message(rs.getString("message"))
                .intent(enumValue(rs.getString("intent"))).traceId(rs.getString("trace_id"))
                .clientMessageId(rs.getString("client_message_id")).resultJson(rs.getString("result_json"))
                .createdAt(instant(rs.getTimestamp("created_at"))).build();
    }

    private void ensureSchema() {
        if (schemaChecked) return;
        synchronized (this) {
            if (schemaChecked) return;
            jdbc.execute("CREATE TABLE IF NOT EXISTS copilot_conversations (conversation_id VARCHAR(96) PRIMARY KEY,user_id BIGINT NOT NULL,status VARCHAR(16) NOT NULL,active_farm_id VARCHAR(128),active_field_id VARCHAR(128),active_diagnosis_id VARCHAR(128),active_recommendation_id VARCHAR(128),active_run_id VARCHAR(128),active_action_id VARCHAR(128),active_task_id VARCHAR(128),active_outcome_id VARCHAR(128),last_intent VARCHAR(64),summary_json LONGTEXT,summary_version INT NOT NULL DEFAULT 0,turn_count INT NOT NULL DEFAULT 0,version BIGINT NOT NULL DEFAULT 0,pending_type VARCHAR(64),pending_question TEXT,pending_created_turn INT,pending_expires_after_turn INT,created_at TIMESTAMP(3) NULL,updated_at TIMESTAMP(3) NULL,expires_at TIMESTAMP(3) NULL,INDEX idx_copilot_conversations_user_updated (user_id,updated_at))");
            jdbc.execute("CREATE TABLE IF NOT EXISTS copilot_turns (turn_id VARCHAR(96) PRIMARY KEY,conversation_id VARCHAR(96) NOT NULL,user_id BIGINT NOT NULL,role VARCHAR(20) NOT NULL,message LONGTEXT NOT NULL,intent VARCHAR(64),trace_id VARCHAR(96),client_message_id VARCHAR(160),result_json LONGTEXT,created_at TIMESTAMP(3) NULL,UNIQUE KEY uq_copilot_turn_client (conversation_id,client_message_id),INDEX idx_copilot_turns_conversation_created (conversation_id,created_at),INDEX idx_copilot_turns_user (user_id))");
            schemaChecked = true;
        }
    }

    private String id(CopilotStableContext context, String field) {
        if (context == null) return null;
        return switch (field) {
            case "farmId" -> context.getFarmId(); case "fieldId" -> context.getFieldId(); case "diagnosisId" -> context.getDiagnosisId();
            case "recommendationId" -> context.getRecommendationId(); case "runId" -> context.getRunId(); case "actionId" -> context.getActionId();
            case "taskId" -> context.getTaskId(); case "outcomeId" -> context.getOutcomeId(); default -> null;
        };
    }
    private String pending(CopilotConversationSnapshot s, String field) { if (s.getPendingClarification() == null) return null; return "type".equals(field) ? s.getPendingClarification().getType() : s.getPendingClarification().getQuestion(); }
    private Integer pendingInteger(CopilotConversationSnapshot s, String field) { if (s.getPendingClarification() == null) return null; return "created".equals(field) ? s.getPendingClarification().getCreatedAtTurn() : s.getPendingClarification().getExpiresAfterTurns(); }
    private String enumName(Enum<?> value) { return value == null ? null : value.name(); }
    private CopilotIntent enumValue(String value) { return value == null ? null : CopilotIntent.valueOf(value); }
    private Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}
