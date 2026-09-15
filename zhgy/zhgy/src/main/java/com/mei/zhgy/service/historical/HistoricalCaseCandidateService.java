package com.mei.zhgy.service.historical;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.service.expert.ExpertReviewV2Service;
import com.mei.zhgy.service.expert.ExpertReviewWriteException;
import com.mei.zhgy.service.operations.OperationsTaskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Creates an eligible historical-case candidate without promoting or indexing it. */
@Service
public class HistoricalCaseCandidateService {
    private static final String COLUMNS = "candidate_id,review_id,diagnosis_id,task_id,outcome_id,status,eligibility_reason,learning_value,owner_user_id,created_at,updated_at";
    private final DataSource dataSource;
    private final ObjectMapper mapper;
    private final ExpertReviewV2Service reviews;
    private final OperationsTaskService operations;
    private final boolean allowFallback;
    private final HistoricalCaseCandidateEligibilityEvaluator eligibility = new HistoricalCaseCandidateEligibilityEvaluator();
    private final Map<String, Map<String, Object>> fallback = new ConcurrentHashMap<>();
    private volatile boolean schemaChecked;

    public HistoricalCaseCandidateService() { this(null, new ObjectMapper().findAndRegisterModules(), new ExpertReviewV2Service(), new OperationsTaskService(), true); }

    @Autowired
    public HistoricalCaseCandidateService(@Autowired(required = false) DataSource dataSource,
                                          ObjectMapper mapper,
                                          ExpertReviewV2Service reviews,
                                          OperationsTaskService operations) {
        this(dataSource, mapper, reviews, operations, false);
    }

    public HistoricalCaseCandidateService(DataSource dataSource, ObjectMapper mapper,
                                          ExpertReviewV2Service reviews, OperationsTaskService operations,
                                          boolean allowFallback) {
        this.dataSource = dataSource; this.mapper = mapper == null ? new ObjectMapper().findAndRegisterModules() : mapper.findAndRegisterModules(); this.reviews = reviews == null ? new ExpertReviewV2Service() : reviews; this.operations = operations == null ? new OperationsTaskService() : operations;
        this.allowFallback = allowFallback;
    }

    public Map<String, Object> evaluateForCandidate(String reviewId, Long actorId) {
        Map<String, Object> review = reviews.require(reviewId, actorId);
        Map<String, Object> task = null;
        Map<String, Object> outcome = null;
        if ("OUTCOME_REVIEW".equalsIgnoreCase(text(review.get("scope")))) {
            try { task = operations.getTask(text(review.get("taskId")), actorId); outcome = operations.getOutcome(text(review.get("taskId")), actorId); }
            catch (Exception error) { throw ExpertReviewWriteException.provenance("Candidate 所需 Task/Outcome 不存在或不可访问", reviewId); }
        }
        HistoricalCaseCandidateEligibilityEvaluator.Evaluation evaluation = eligibility.evaluate(review, task, outcome);
        if (!evaluation.eligible()) {
            Map<String, Object> result = new LinkedHashMap<>(); result.put("status", "NOT_ELIGIBLE"); result.put("eligible", false); result.put("reviewId", reviewId); result.put("eligibilityReason", evaluation.reason()); return result;
        }
        Map<String, Object> candidate = build(review, evaluation, actorId);
        if (dataSource == null) {
            if (!allowFallback) throw ExpertReviewWriteException.persistence("Historical Case Candidate 数据库不可用，已拒绝写入", null);
            synchronized (this) { Map<String, Object> existing = fallback.values().stream().filter(item -> reviewId.equals(text(item.get("reviewId")))).findFirst().orElse(null); if (existing != null) return copy(existing); fallback.put(text(candidate.get("candidateId")), candidate); return copy(candidate); }
        }
        SQLException lastSqlError = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try (Connection connection = dataSource.getConnection()) {
                ensureSchema(connection); connection.setAutoCommit(false);
                try {
                    // Do not lock a missing unique-key row. InnoDB gap locks can deadlock two
                    // first writers; the unique constraint below is the concurrency boundary.
                    Map<String, Object> existing = findByReview(connection, reviewId, false);
                    if (existing != null) { authorize(existing, actorId); connection.commit(); return existing; }
                    insert(connection, candidate);
                    reviews.appendCandidateAudit(connection, reviewId, "CANDIDATE_CREATED", actorId, json(candidate));
                    connection.commit(); return candidate;
                } catch (SQLException error) {
                    lastSqlError = error;
                    rollback(connection);
                    if (isDuplicate(error)) {
                        Map<String, Object> existing = findByReview(connection, reviewId, false);
                        if (existing != null) { authorize(existing, actorId); return existing; }
                    }
                    if (!isRetryable(error) || attempt == 2) throw error;
                } catch (Exception error) { rollback(connection); throw error;
                } finally { resetAutoCommit(connection); }
            } catch (ExpertReviewWriteException error) { throw error;
            } catch (SQLException error) {
                lastSqlError = error;
                if (!isRetryable(error) || attempt == 2) throw ExpertReviewWriteException.persistence("Historical Case Candidate 未持久化，已拒绝写入", error);
            } catch (Exception error) { throw ExpertReviewWriteException.persistence("Historical Case Candidate 未持久化，已拒绝写入", error); }
            try { Thread.sleep(25L * (attempt + 1)); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw ExpertReviewWriteException.persistence("Historical Case Candidate 未持久化，已拒绝写入", lastSqlError); }
        }
        throw ExpertReviewWriteException.persistence("Historical Case Candidate 未持久化，已拒绝写入", lastSqlError);
    }

    /** Candidate persistence health is separate from the Milvus HistoricalCase store. */
    public Map<String, Object> health() {
        if (dataSource == null) return Map.of("status", allowFallback ? "DEMO" : "UNAVAILABLE", "mode", "fallback");
        try (Connection connection = dataSource.getConnection()) {
            ensureSchema(connection);
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM historical_case_candidates")) {
                rs.next();
                return Map.of("status", rs.getLong(1) == 0 ? "READY_EMPTY" : "READY", "mode", "database", "candidates", rs.getLong(1));
            }
        } catch (Exception error) {
            return Map.of("status", "UNAVAILABLE", "mode", "database", "error", error.getClass().getSimpleName());
        }
    }

    public Map<String, Object> get(String candidateId, Long actorId) {
        requireActorForProduction(actorId);
        if (candidateId == null || candidateId.isBlank()) throw ExpertReviewWriteException.candidateNotFound(candidateId);
        if (dataSource == null) { requirePersistentReadOrFallback(); Map<String, Object> candidate = fallback.get(candidateId); if (candidate == null) throw ExpertReviewWriteException.candidateNotFound(candidateId); authorize(candidate, actorId); return copy(candidate); }
        try (Connection connection = dataSource.getConnection()) { ensureSchema(connection); Map<String, Object> candidate = findOne(connection, "candidate_id=?", candidateId, false); if (candidate == null) throw ExpertReviewWriteException.candidateNotFound(candidateId); authorize(candidate, actorId); return candidate; }
        catch (ExpertReviewWriteException error) { throw error; } catch (Exception error) { throw ExpertReviewWriteException.persistence("Historical Case Candidate 查询不可用", error); }
    }

    public Map<String, Object> findByReview(String reviewId, Long actorId) {
        requireActorForProduction(actorId);
        if (dataSource == null) { requirePersistentReadOrFallback(); return fallback.values().stream().filter(item -> reviewId.equals(text(item.get("reviewId")))).findFirst().map(item -> { authorize(item, actorId); return copy(item); }).orElse(null); }
        try (Connection connection = dataSource.getConnection()) { ensureSchema(connection); Map<String, Object> candidate = findByReview(connection, reviewId, false); if (candidate != null) authorize(candidate, actorId); return candidate; }
        catch (Exception error) { throw ExpertReviewWriteException.persistence("Historical Case Candidate 查询不可用", error); }
    }

    public List<Map<String, Object>> list(Long actorId, String status) {
        requireActorForProduction(actorId);
        if (dataSource == null) { requirePersistentReadOrFallback(); return fallback.values().stream().filter(item -> status == null || status.isBlank() || status.equalsIgnoreCase(text(item.get("status")))).filter(item -> visible(item, actorId)).sorted(Comparator.comparing(item -> text(item.get("updatedAt")), Comparator.reverseOrder())).map(this::copy).toList(); }
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) { ensureSchema(connection); StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS).append(" FROM historical_case_candidates WHERE (owner_user_id IS NULL OR owner_user_id=?)"); if (status != null && !status.isBlank()) sql.append(" AND status=?"); sql.append(" ORDER BY updated_at DESC"); try (PreparedStatement statement = connection.prepareStatement(sql.toString())) { int i = 1; setLong(statement, i++, actorId); if (status != null && !status.isBlank()) statement.setString(i, status.toUpperCase()); try (ResultSet rs = statement.executeQuery()) { while (rs.next()) result.add(row(rs)); } } return result; }
        catch (Exception error) { throw ExpertReviewWriteException.persistence("Historical Case Candidate 列表不可用", error); }
    }

    private Map<String, Object> build(Map<String, Object> review, HistoricalCaseCandidateEligibilityEvaluator.Evaluation evaluation, Long actorId) {
        Instant now = Instant.now(); Map<String, Object> candidate = new LinkedHashMap<>(); candidate.put("candidateId", "candidate-" + UUID.randomUUID()); candidate.put("reviewId", review.get("reviewId")); candidate.put("diagnosisId", review.get("diagnosisId")); candidate.put("taskId", review.get("taskId")); candidate.put("outcomeId", review.get("outcomeId")); candidate.put("status", "ELIGIBLE"); candidate.put("eligible", true); candidate.put("eligibilityReason", evaluation.reason()); candidate.put("learningValue", evaluation.learningValue()); candidate.put("ownerUserId", review.get("subjectOwnerUserId")); candidate.put("createdAt", now.toString()); candidate.put("updatedAt", now.toString()); return candidate;
    }

    private void insert(Connection c, Map<String, Object> candidate) throws SQLException {
        String sql = "INSERT INTO historical_case_candidates (candidate_id,review_id,diagnosis_id,task_id,outcome_id,status,eligibility_reason,learning_value,owner_user_id,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = c.prepareStatement(sql)) { statement.setString(1, text(candidate.get("candidateId"))); statement.setString(2, text(candidate.get("reviewId"))); statement.setString(3, text(candidate.get("diagnosisId"))); statement.setString(4, nullable(candidate.get("taskId"))); statement.setString(5, nullable(candidate.get("outcomeId"))); statement.setString(6, text(candidate.get("status"))); statement.setString(7, nullable(candidate.get("eligibilityReason"))); statement.setString(8, nullable(candidate.get("learningValue"))); setLong(statement, 9, numberOrNull(candidate.get("ownerUserId"))); statement.setTimestamp(10, timestamp(candidate.get("createdAt"))); statement.setTimestamp(11, timestamp(candidate.get("updatedAt"))); statement.executeUpdate(); }
    }

    private Map<String, Object> findByReview(Connection c, String reviewId, boolean lock) throws Exception { return findOne(c, "review_id=?", reviewId, lock); }
    private Map<String, Object> findOne(Connection c, String predicate, String value, boolean lock) throws Exception { String sql = "SELECT " + COLUMNS + " FROM historical_case_candidates WHERE " + predicate + (lock ? " FOR UPDATE" : ""); try (PreparedStatement statement = c.prepareStatement(sql)) { statement.setString(1, value); try (ResultSet rs = statement.executeQuery()) { return rs.next() ? row(rs) : null; } } }
    private Map<String, Object> row(ResultSet rs) throws SQLException { Map<String, Object> value = new LinkedHashMap<>(); value.put("candidateId", rs.getString("candidate_id")); value.put("reviewId", rs.getString("review_id")); value.put("diagnosisId", rs.getString("diagnosis_id")); value.put("taskId", rs.getString("task_id")); value.put("outcomeId", rs.getString("outcome_id")); value.put("status", rs.getString("status")); value.put("eligible", "ELIGIBLE".equalsIgnoreCase(rs.getString("status"))); value.put("eligibilityReason", rs.getString("eligibility_reason")); value.put("learningValue", rs.getString("learning_value")); value.put("ownerUserId", rs.getObject("owner_user_id")); value.put("createdAt", instant(rs.getTimestamp("created_at"))); value.put("updatedAt", instant(rs.getTimestamp("updated_at"))); return value; }
    private void authorize(Map<String, Object> candidate, Long actorId) { Object owner = candidate.get("ownerUserId"); if (owner != null && (actorId == null || !String.valueOf(owner).equals(String.valueOf(actorId)))) throw ExpertReviewWriteException.forbidden(text(candidate.get("reviewId"))); }
    private boolean visible(Map<String, Object> candidate, Long actorId) { try { authorize(candidate, actorId); return true; } catch (ExpertReviewWriteException error) { return false; } }
    void ensureSchema(Connection c) throws SQLException { if (schemaChecked) return; synchronized (this) { if (schemaChecked) return; try (Statement statement = c.createStatement()) { statement.execute("CREATE TABLE IF NOT EXISTS historical_case_candidates (candidate_id VARCHAR(112) NOT NULL,review_id VARCHAR(96) NOT NULL,diagnosis_id VARCHAR(96) NOT NULL,task_id VARCHAR(80) NULL,outcome_id VARCHAR(96) NULL,status VARCHAR(32) NOT NULL,eligibility_reason VARCHAR(255) NULL,learning_value VARCHAR(64) NULL,owner_user_id BIGINT NULL,created_at TIMESTAMP(3) NOT NULL,updated_at TIMESTAMP(3) NOT NULL,PRIMARY KEY(candidate_id),UNIQUE KEY uk_historical_candidate_review(review_id),INDEX idx_historical_candidate_owner(owner_user_id),INDEX idx_historical_candidate_status(status))"); schemaChecked = true; } } }
    private void requireActorForProduction(Long actorId) { if (dataSource != null && actorId == null) throw ExpertReviewWriteException.forbidden(null); }
    private void requirePersistentReadOrFallback() { if (!allowFallback) throw ExpertReviewWriteException.persistence("Historical Case Candidate 数据库不可用，已拒绝读取", null); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private String nullable(Object value) { return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value); }
    private Long numberOrNull(Object value) { if (value == null) return null; try { return value instanceof Number n ? n.longValue() : Long.valueOf(String.valueOf(value)); } catch (NumberFormatException error) { return null; } }
    private Timestamp timestamp(Object value) { try { return value == null ? null : Timestamp.from(value instanceof Instant ? (Instant) value : Instant.parse(String.valueOf(value))); } catch (Exception error) { return null; } }
    private String instant(Timestamp value) { return value == null ? null : value.toInstant().toString(); }
    private void setLong(PreparedStatement statement, int index, Long value) throws SQLException { if (value == null) statement.setNull(index, java.sql.Types.BIGINT); else statement.setLong(index, value); }
    private String json(Object value) { try { return mapper.writeValueAsString(value == null ? Map.of() : value); } catch (JsonProcessingException error) { return "{}"; } }
    private Map<String, Object> copy(Map<String, Object> value) { return value == null ? null : new LinkedHashMap<>(value); }
    private void rollback(Connection c) { try { c.rollback(); } catch (SQLException ignored) { } }
    private void resetAutoCommit(Connection c) { try { c.setAutoCommit(true); } catch (SQLException ignored) { } }
    private boolean isDuplicate(SQLException error) { for (SQLException current = error; current != null; current = current.getNextException()) if ("23000".equals(current.getSQLState()) || current.getErrorCode() == 1062) return true; return false; }
    private boolean isRetryable(SQLException error) { for (SQLException current = error; current != null; current = current.getNextException()) if ("40001".equals(current.getSQLState()) || "41000".equals(current.getSQLState()) || current.getErrorCode() == 1205 || current.getErrorCode() == 1213) return true; return false; }
}
