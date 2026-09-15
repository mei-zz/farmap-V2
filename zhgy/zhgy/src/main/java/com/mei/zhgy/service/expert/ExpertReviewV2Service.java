package com.mei.zhgy.service.expert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.service.diagnosis.DiagnosisPersistenceException;
import com.mei.zhgy.service.diagnosis.DiagnosisPersistenceService;
import com.mei.zhgy.service.operations.OperationsTaskService;
import com.mei.zhgy.service.operations.OperationsWriteException;
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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V2 Expert Review boundary. The legacy diagnosis-keyed facade remains in
 * {@link ExpertReviewService}; this service owns the additive stable review
 * identity and Outcome-level provenance contract.
 */
@Service
public class ExpertReviewV2Service {
    private static final String COLUMNS = "review_id,diagnosis_id,task_id,outcome_id,recommendation_id,scope,diagnosis_judgment,recommendation_judgment,outcome_judgment,diagnosis_correction,recommendation_correction,expert_confidence,notes,reviewer_user_id,subject_owner_user_id,version,created_at,updated_at";
    private final DataSource dataSource;
    private final ObjectMapper mapper;
    private final DiagnosisPersistenceService diagnosisPersistence;
    private final OperationsTaskService operations;
    private final boolean allowFallback;
    private final Map<String, Map<String, Object>> fallbackReviews = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, Object>>> fallbackEvents = new ConcurrentHashMap<>();
    private volatile boolean schemaChecked;

    /** Local-only construction is retained for deterministic unit/demo tests. */
    public ExpertReviewV2Service() {
        this(null, new ObjectMapper().findAndRegisterModules(), new DiagnosisPersistenceService(), new OperationsTaskService(), true);
    }

    @Autowired
    public ExpertReviewV2Service(@Autowired(required = false) DataSource dataSource,
                                 ObjectMapper mapper,
                                 DiagnosisPersistenceService diagnosisPersistence,
                                 OperationsTaskService operations) {
        this(dataSource, mapper, diagnosisPersistence, operations, false);
    }

    /** Test seam for supplying deterministic resource boundaries. */
    public ExpertReviewV2Service(DataSource dataSource, ObjectMapper mapper,
                                 DiagnosisPersistenceService diagnosisPersistence,
                                 OperationsTaskService operations, boolean allowFallback) {
        this.dataSource = dataSource;
        this.mapper = mapper == null ? new ObjectMapper().findAndRegisterModules() : mapper.findAndRegisterModules();
        this.diagnosisPersistence = diagnosisPersistence == null ? new DiagnosisPersistenceService() : diagnosisPersistence;
        this.operations = operations == null ? new OperationsTaskService() : operations;
        this.allowFallback = allowFallback;
    }

    public Map<String, Object> create(Map<String, Object> input, Long actorId) {
        requireActorForProduction(actorId);
        Map<String, Object> review = buildReview(input, actorId);
        validateProvenance(review, actorId);
        if (dataSource == null) {
            if (!allowFallback) throw ExpertReviewWriteException.persistence("Expert Review 数据库不可用，已拒绝写入", null);
            synchronized (this) {
                Map<String, Object> existing = findFallbackCanonical(review);
                if (existing != null) return copy(existing);
                fallbackReviews.put(text(review.get("reviewId")), review);
                appendFallbackEvent(review, "EXPERT_REVIEW_CREATED", null, review);
                return copy(review);
            }
        }
        try (Connection connection = dataSource.getConnection()) {
            ensureSchema(connection);
            connection.setAutoCommit(false);
            try {
                Map<String, Object> existing = findCanonical(connection, review);
                if (existing != null) {
                    authorize(existing, actorId);
                    connection.commit();
                    return existing;
                }
                insert(connection, review);
                insertAudit(connection, review, "EXPERT_REVIEW_CREATED", null, review, actorId);
                connection.commit();
                return review;
            } catch (SQLException error) {
                rollback(connection);
                if (isDuplicate(error)) {
                    Map<String, Object> existing = findCanonical(connection, review);
                    if (existing != null) {
                        authorize(existing, actorId);
                        return existing;
                    }
                }
                throw error;
            } catch (Exception error) {
                rollback(connection);
                throw error;
            } finally {
                resetAutoCommit(connection);
            }
        } catch (ExpertReviewWriteException error) { throw error;
        } catch (Exception error) { throw ExpertReviewWriteException.persistence("Expert Review 未持久化，已拒绝写入", error); }
    }

    /** Read-only health probe; it never enables the in-memory fallback. */
    public Map<String, Object> health() {
        if (dataSource == null) return Map.of("status", allowFallback ? "DEMO" : "UNAVAILABLE", "mode", "fallback");
        try (Connection connection = dataSource.getConnection()) {
            ensureSchema(connection);
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM agent_expert_reviews_v2")) {
                rs.next();
                return Map.of("status", rs.getLong(1) == 0 ? "READY_EMPTY" : "READY", "mode", "database", "reviews", rs.getLong(1));
            }
        } catch (Exception error) {
            return Map.of("status", "UNAVAILABLE", "mode", "database", "error", error.getClass().getSimpleName());
        }
    }

    public Map<String, Object> get(String reviewId, Long actorId) {
        requireActorForProduction(actorId);
        if (reviewId == null || reviewId.isBlank()) throw ExpertReviewWriteException.notFound(reviewId);
        if (dataSource == null) {
            requirePersistentReadOrFallback();
            Map<String, Object> review = fallbackReviews.get(reviewId);
            if (review == null) throw ExpertReviewWriteException.notFound(reviewId);
            authorize(review, actorId);
            return copy(review);
        }
        try (Connection connection = dataSource.getConnection()) {
            ensureSchema(connection);
            Map<String, Object> review = findById(connection, reviewId, false);
            if (review == null) throw ExpertReviewWriteException.notFound(reviewId);
            authorize(review, actorId);
            return review;
        } catch (ExpertReviewWriteException error) { throw error;
        } catch (Exception error) { throw ExpertReviewWriteException.persistence("Expert Review 查询不可用", error); }
    }

    public Map<String, Object> findByDiagnosis(String diagnosisId, Long actorId) {
        requireActorForProduction(actorId);
        if (diagnosisId == null || diagnosisId.isBlank()) throw ExpertReviewWriteException.validation("diagnosisId 不能为空");
        if (dataSource == null) {
            requirePersistentReadOrFallback();
            return fallbackReviews.values().stream()
                    .filter(review -> diagnosisId.equals(text(review.get("diagnosisId"))))
                    .sorted(Comparator.comparing(review -> text(review.get("updatedAt")), Comparator.reverseOrder()))
                    .findFirst().map(review -> { authorize(review, actorId); return copy(review); }).orElse(null);
        }
        try (Connection connection = dataSource.getConnection()) {
            ensureSchema(connection);
            String sql = "SELECT " + COLUMNS + " FROM agent_expert_reviews_v2 WHERE diagnosis_id=? AND (subject_owner_user_id IS NULL OR subject_owner_user_id=? OR reviewer_user_id=?) ORDER BY updated_at DESC LIMIT 1";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, diagnosisId); setLong(statement, 2, actorId); setLong(statement, 3, actorId);
                try (ResultSet rs = statement.executeQuery()) { return rs.next() ? row(rs) : null; }
            }
        } catch (Exception error) { throw ExpertReviewWriteException.persistence("Expert Review 查询不可用", error); }
    }

    public List<Map<String, Object>> list(Long actorId, String diagnosisId) {
        requireActorForProduction(actorId);
        if (dataSource == null) {
            requirePersistentReadOrFallback();
            return fallbackReviews.values().stream()
                    .filter(review -> diagnosisId == null || diagnosisId.isBlank() || diagnosisId.equals(text(review.get("diagnosisId"))))
                    .filter(review -> visible(review, actorId))
                    .sorted(Comparator.comparing(review -> text(review.get("updatedAt")), Comparator.reverseOrder()))
                    .map(this::copy).toList();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            ensureSchema(connection);
            StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS).append(" FROM agent_expert_reviews_v2 WHERE (subject_owner_user_id IS NULL OR subject_owner_user_id=? OR reviewer_user_id=?)");
            if (diagnosisId != null && !diagnosisId.isBlank()) sql.append(" AND diagnosis_id=?");
            sql.append(" ORDER BY updated_at DESC");
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                int index = 1; setLong(statement, index++, actorId); setLong(statement, index++, actorId);
                if (diagnosisId != null && !diagnosisId.isBlank()) statement.setString(index, diagnosisId);
                try (ResultSet rs = statement.executeQuery()) { while (rs.next()) result.add(row(rs)); }
            }
            return result;
        } catch (Exception error) { throw ExpertReviewWriteException.persistence("Expert Review 列表不可用", error); }
    }

    public Map<String, Object> update(String reviewId, Map<String, Object> input, Long actorId) {
        requireActorForProduction(actorId);
        Map<String, Object> existing = get(reviewId, actorId);
        requireVersion(input);
        validateImmutableIdentity(existing, input, reviewId);
        Map<String, Object> updated = mergeJudgments(existing, input, actorId);
        validateProvenance(updated, actorId);
        long expectedVersion = number(input.get("version"), -1L);
        if (expectedVersion != number(existing.get("version"), 0L)) throw ExpertReviewWriteException.conflict("REVIEW_CONFLICT", "Expert Review version 已过期", reviewId);
        // Provenance ownership is immutable; validation may resolve the current actor,
        // but an update must retain the subject owner captured at creation time.
        updated.put("subjectOwnerUserId", existing.get("subjectOwnerUserId"));
        if (dataSource == null) {
            if (!allowFallback) throw ExpertReviewWriteException.persistence("Expert Review 数据库不可用，已拒绝更新", null);
            synchronized (this) {
                Map<String, Object> latest = fallbackReviews.get(reviewId);
                if (latest == null) throw ExpertReviewWriteException.notFound(reviewId);
                authorize(latest, actorId);
                if (number(latest.get("version"), 0L) != expectedVersion) throw ExpertReviewWriteException.conflict("REVIEW_CONFLICT", "Expert Review version 已过期", reviewId);
                updated.put("version", expectedVersion + 1L);
                fallbackReviews.put(reviewId, updated);
                appendFallbackEvent(updated, "EXPERT_REVIEW_UPDATED", latest, updated);
                return copy(updated);
            }
        }
        try (Connection connection = dataSource.getConnection()) {
            ensureSchema(connection);
            connection.setAutoCommit(false);
            try {
                String sql = "UPDATE agent_expert_reviews_v2 SET diagnosis_judgment=?,recommendation_judgment=?,outcome_judgment=?,diagnosis_correction=?,recommendation_correction=?,expert_confidence=?,notes=?,updated_at=?,version=version+1 WHERE review_id=? AND version=?";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, nullableText(updated.get("diagnosisJudgment")));
                    statement.setString(2, nullableText(updated.get("recommendationJudgment")));
                    statement.setString(3, nullableText(updated.get("outcomeJudgment")));
                    statement.setString(4, nullableText(updated.get("diagnosisCorrection")));
                    statement.setString(5, nullableText(updated.get("recommendationCorrection")));
                    setDouble(statement, 6, decimal(updated.get("expertConfidence")));
                    statement.setString(7, nullableText(updated.get("notes")));
                    Instant now = Instant.now(); statement.setTimestamp(8, Timestamp.from(now));
                    statement.setString(9, reviewId); statement.setLong(10, expectedVersion);
                    if (statement.executeUpdate() != 1) throw ExpertReviewWriteException.conflict("REVIEW_CONFLICT", "Expert Review version 已过期", reviewId);
                    updated.put("updatedAt", now.toString()); updated.put("version", expectedVersion + 1L);
                }
                insertAudit(connection, updated, "EXPERT_REVIEW_UPDATED", existing, updated, actorId);
                connection.commit();
                return updated;
            } catch (Exception error) { rollback(connection); throw error;
            } finally { resetAutoCommit(connection); }
        } catch (ExpertReviewWriteException error) { throw error;
        } catch (Exception error) { throw ExpertReviewWriteException.persistence("Expert Review 更新事务未提交", error); }
    }

    /** Internal read used by candidate service; it still enforces actor ownership. */
    public Map<String, Object> require(String reviewId, Long actorId) { return get(reviewId, actorId); }

    private Map<String, Object> buildReview(Map<String, Object> input, Long actorId) {
        Map<String, Object> value = input == null ? Map.of() : input;
        String diagnosisId = requiredText(value.get("diagnosisId"), "diagnosisId 不能为空");
        String taskId = optionalText(value.get("taskId"));
        String outcomeId = optionalText(value.get("outcomeId"));
        String recommendationId = optionalText(value.get("recommendationId"));
        String scope = optionalText(value.get("scope"));
        if (scope == null) scope = taskId != null || outcomeId != null ? "OUTCOME_REVIEW" : "DIAGNOSIS_ONLY";
        scope = scope.toUpperCase();
        if (!"DIAGNOSIS_ONLY".equals(scope) && !"OUTCOME_REVIEW".equals(scope)) throw ExpertReviewWriteException.validation("scope 只能是 DIAGNOSIS_ONLY 或 OUTCOME_REVIEW");
        if ("DIAGNOSIS_ONLY".equals(scope) && (taskId != null || outcomeId != null)) throw ExpertReviewWriteException.provenance("Diagnosis-only Review 不能携带 taskId/outcomeId", null);
        if ("OUTCOME_REVIEW".equals(scope) && (taskId == null || outcomeId == null)) throw ExpertReviewWriteException.validation("Outcome Review 必须同时携带 taskId 和 outcomeId");
        Double confidence = decimal(value.get("expertConfidence"));
        if (value.containsKey("expertConfidence") && confidence == null) throw ExpertReviewWriteException.validation("expertConfidence 必须是 0 到 1 之间的数字");
        if (confidence != null && (confidence < 0D || confidence > 1D)) throw ExpertReviewWriteException.validation("expertConfidence 必须在 0 到 1 之间");
        Instant now = Instant.now();
        Map<String, Object> review = new LinkedHashMap<>();
        review.put("reviewId", "review-" + UUID.randomUUID()); review.put("diagnosisId", diagnosisId); review.put("taskId", taskId); review.put("outcomeId", outcomeId); review.put("recommendationId", recommendationId); review.put("scope", scope);
        review.put("diagnosisJudgment", judgment(value.get("diagnosisJudgment"), DIAGNOSIS_JUDGMENTS, "diagnosisJudgment"));
        review.put("recommendationJudgment", judgment(value.get("recommendationJudgment"), RECOMMENDATION_JUDGMENTS, "recommendationJudgment"));
        review.put("outcomeJudgment", judgment(value.get("outcomeJudgment"), OUTCOME_JUDGMENTS, "outcomeJudgment"));
        review.put("diagnosisCorrection", optionalText(value.get("diagnosisCorrection"))); review.put("recommendationCorrection", optionalText(value.get("recommendationCorrection")));
        review.put("expertConfidence", confidence); review.put("notes", optionalText(value.get("notes"))); review.put("reviewerUserId", actorId); review.put("subjectOwnerUserId", null); review.put("version", 0L); review.put("createdAt", now.toString()); review.put("updatedAt", now.toString());
        validateCompleteness(review);
        return review;
    }

    private void validateCompleteness(Map<String, Object> review) {
        if (!"OUTCOME_REVIEW".equals(text(review.get("scope")))) return;
        if (review.get("diagnosisJudgment") == null) throw ExpertReviewWriteException.validation("Outcome Review 必须提供 diagnosisJudgment");
        if (review.get("outcomeJudgment") == null) throw ExpertReviewWriteException.validation("Outcome Review 必须提供 outcomeJudgment");
        if (review.get("expertConfidence") == null) throw ExpertReviewWriteException.validation("Outcome Review 必须提供 expertConfidence");
        if (review.get("recommendationId") != null && review.get("recommendationJudgment") == null) throw ExpertReviewWriteException.validation("存在 recommendationId 时必须提供 recommendationJudgment");
    }

    private void validateProvenance(Map<String, Object> review, Long actorId) {
        String diagnosisId = text(review.get("diagnosisId"));
        Long subjectOwner = null;
        if (dataSource != null) {
            Optional<?> diagnosis = findDiagnosis(diagnosisId, actorId);
            if (diagnosis.isEmpty()) throw ExpertReviewWriteException.provenance("Diagnosis 不存在或当前用户不可访问", text(review.get("reviewId")));
            subjectOwner = actorId;
        }
        String recommendationId = optionalText(review.get("recommendationId"));
        if (recommendationId != null && dataSource != null) {
            Optional<?> recommendation = diagnosisPersistence.findRecommendation(diagnosisId, recommendationId, actorId);
            if (recommendation.isEmpty()) throw ExpertReviewWriteException.provenance("Recommendation 不属于指定 Diagnosis", text(review.get("reviewId")));
        }
        if ("OUTCOME_REVIEW".equals(text(review.get("scope")))) {
            String taskId = text(review.get("taskId")); String outcomeId = text(review.get("outcomeId"));
            Map<String, Object> task;
            Map<String, Object> outcome;
            try {
                task = operations.getTask(taskId, actorId);
                outcome = operations.getOutcome(taskId, actorId);
            } catch (OperationsWriteException error) {
                throw ExpertReviewWriteException.provenance("Task/Outcome 不存在或当前用户不可访问", text(review.get("reviewId")));
            }
            if (!"COMPLETED".equalsIgnoreCase(text(task.get("status")))) throw ExpertReviewWriteException.provenance("Outcome Review 要求 Task 已 COMPLETED", text(review.get("reviewId")));
            if (!diagnosisId.equals(text(task.get("diagnosisId"))) || !outcomeId.equals(text(outcome.get("outcomeId"))) || !taskId.equals(text(outcome.get("taskId")))) {
                throw ExpertReviewWriteException.provenance("Diagnosis、Task、Outcome provenance 不一致", text(review.get("reviewId")));
            }
            Object owner = task.get("ownerUserId"); if (owner instanceof Number n) subjectOwner = n.longValue();
        }
        review.put("subjectOwnerUserId", subjectOwner);
    }

    private Optional<?> findDiagnosis(String diagnosisId, Long actorId) {
        try {
            Optional<?> value = diagnosisPersistence.find(diagnosisId, actorId);
            if (value.isPresent()) return value;
            return diagnosisPersistence.find(diagnosisId, null); // explicit ownerless legacy compatibility
        } catch (DiagnosisPersistenceException error) { throw ExpertReviewWriteException.persistence("Diagnosis 查询不可用", error); }
    }

    private Map<String, Object> mergeJudgments(Map<String, Object> existing, Map<String, Object> input, Long actorId) {
        Map<String, Object> updated = copy(existing); updated.put("reviewerUserId", existing.get("reviewerUserId")); updated.put("subjectOwnerUserId", existing.get("subjectOwnerUserId"));
        if (input != null) {
            updated.put("diagnosisJudgment", judgment(input.get("diagnosisJudgment"), DIAGNOSIS_JUDGMENTS, "diagnosisJudgment", existing.get("diagnosisJudgment")));
            updated.put("recommendationJudgment", judgment(input.get("recommendationJudgment"), RECOMMENDATION_JUDGMENTS, "recommendationJudgment", existing.get("recommendationJudgment")));
            updated.put("outcomeJudgment", judgment(input.get("outcomeJudgment"), OUTCOME_JUDGMENTS, "outcomeJudgment", existing.get("outcomeJudgment")));
            if (input.containsKey("diagnosisCorrection")) updated.put("diagnosisCorrection", optionalText(input.get("diagnosisCorrection")));
            if (input.containsKey("recommendationCorrection")) updated.put("recommendationCorrection", optionalText(input.get("recommendationCorrection")));
            if (input.containsKey("notes")) updated.put("notes", optionalText(input.get("notes")));
            if (input.containsKey("expertConfidence")) {
                Double confidence = decimal(input.get("expertConfidence"));
                if (confidence == null || confidence < 0D || confidence > 1D) throw ExpertReviewWriteException.validation("expertConfidence 必须在 0 到 1 之间");
                updated.put("expertConfidence", confidence);
            }
        }
        return updated;
    }

    private void validateImmutableIdentity(Map<String, Object> existing, Map<String, Object> input, String reviewId) {
        if (input == null) return;
        for (String key : List.of("reviewId", "diagnosisId", "taskId", "outcomeId", "recommendationId", "scope")) {
            if (!input.containsKey(key) || input.get(key) == null) continue;
            if (!Objects.equals(optionalText(existing.get(key)), optionalText(input.get(key)))) throw ExpertReviewWriteException.provenance("Review identity/provenance 创建后不可修改", reviewId);
        }
    }

    private void requireVersion(Map<String, Object> input) {
        if (input == null || !input.containsKey("version") || number(input.get("version"), -1L) < 0L) throw ExpertReviewWriteException.validation("更新 Expert Review 必须提供 version");
    }

    private Map<String, Object> findCanonical(Connection c, Map<String, Object> review) throws Exception {
        String outcomeId = optionalText(review.get("outcomeId"));
        if (outcomeId != null) {
            Map<String, Object> existing = findOne(c, "outcome_id=?", outcomeId, false);
            if (existing != null) return existing;
        }
        if ("DIAGNOSIS_ONLY".equals(text(review.get("scope")))) return findOne(c, "diagnosis_only_key=?", text(review.get("diagnosisId")), false);
        return null;
    }

    private Map<String, Object> findById(Connection c, String reviewId, boolean lock) throws Exception { return findOne(c, "review_id=?", reviewId, lock); }
    private Map<String, Object> findOne(Connection c, String predicate, String value, boolean lock) throws Exception {
        String sql = "SELECT " + COLUMNS + " FROM agent_expert_reviews_v2 WHERE " + predicate + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = c.prepareStatement(sql)) { statement.setString(1, value); try (ResultSet rs = statement.executeQuery()) { return rs.next() ? row(rs) : null; } }
    }

    private void insert(Connection c, Map<String, Object> review) throws SQLException {
        String sql = "INSERT INTO agent_expert_reviews_v2 (review_id,diagnosis_id,task_id,outcome_id,recommendation_id,scope,diagnosis_only_key,diagnosis_judgment,recommendation_judgment,outcome_judgment,diagnosis_correction,recommendation_correction,expert_confidence,notes,reviewer_user_id,subject_owner_user_id,version,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            statement.setString(1, text(review.get("reviewId"))); statement.setString(2, text(review.get("diagnosisId"))); statement.setString(3, nullableText(review.get("taskId"))); statement.setString(4, nullableText(review.get("outcomeId"))); statement.setString(5, nullableText(review.get("recommendationId"))); statement.setString(6, text(review.get("scope")));
            statement.setString(7, "DIAGNOSIS_ONLY".equals(text(review.get("scope"))) ? text(review.get("diagnosisId")) : null); statement.setString(8, nullableText(review.get("diagnosisJudgment"))); statement.setString(9, nullableText(review.get("recommendationJudgment"))); statement.setString(10, nullableText(review.get("outcomeJudgment"))); statement.setString(11, nullableText(review.get("diagnosisCorrection"))); statement.setString(12, nullableText(review.get("recommendationCorrection"))); setDouble(statement, 13, decimal(review.get("expertConfidence"))); statement.setString(14, nullableText(review.get("notes"))); setLong(statement, 15, numberOrNull(review.get("reviewerUserId"))); setLong(statement, 16, numberOrNull(review.get("subjectOwnerUserId"))); statement.setLong(17, number(review.get("version"), 0L)); statement.setTimestamp(18, timestamp(review.get("createdAt"))); statement.setTimestamp(19, timestamp(review.get("updatedAt"))); statement.executeUpdate();
        }
    }

    private void insertAudit(Connection c, Map<String, Object> review, String eventType, Map<String, Object> before, Map<String, Object> after, Long actorId) throws SQLException {
        String sql = "INSERT INTO expert_review_events (event_id,review_id,event_type,actor_id,version,before_json,after_json,created_at) VALUES (?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = c.prepareStatement(sql)) { statement.setString(1, "review-event-" + UUID.randomUUID()); statement.setString(2, text(review.get("reviewId"))); statement.setString(3, eventType); setLong(statement, 4, actorId); statement.setLong(5, number(after.get("version"), 0L)); statement.setString(6, json(before == null ? Map.of() : before)); statement.setString(7, json(after)); statement.setTimestamp(8, Timestamp.from(Instant.now())); statement.executeUpdate(); }
    }

    public void appendCandidateAudit(Connection c, String reviewId, String eventType, Long actorId, String afterJson) throws SQLException {
        String sql = "INSERT INTO expert_review_events (event_id,review_id,event_type,actor_id,version,before_json,after_json,created_at) VALUES (?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = c.prepareStatement(sql)) { statement.setString(1, "review-event-" + UUID.randomUUID()); statement.setString(2, reviewId); statement.setString(3, eventType); setLong(statement, 4, actorId); statement.setLong(5, 0L); statement.setString(6, "{}"); statement.setString(7, afterJson == null ? "{}" : afterJson); statement.setTimestamp(8, Timestamp.from(Instant.now())); statement.executeUpdate(); }
    }

    void ensureSchema(Connection c) throws SQLException {
        if (schemaChecked) return;
        synchronized (this) {
            if (schemaChecked) return;
            try (Statement statement = c.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS agent_expert_reviews_v2 (review_id VARCHAR(96) NOT NULL,diagnosis_id VARCHAR(96) NOT NULL,task_id VARCHAR(80) NULL,outcome_id VARCHAR(96) NULL,recommendation_id VARCHAR(100) NULL,scope VARCHAR(32) NOT NULL,diagnosis_only_key VARCHAR(96) NULL,diagnosis_judgment VARCHAR(32) NULL,recommendation_judgment VARCHAR(32) NULL,outcome_judgment VARCHAR(32) NULL,diagnosis_correction TEXT NULL,recommendation_correction TEXT NULL,expert_confidence DOUBLE NULL,notes TEXT NULL,reviewer_user_id BIGINT NULL,subject_owner_user_id BIGINT NULL,version BIGINT NOT NULL DEFAULT 0,created_at TIMESTAMP(3) NOT NULL,updated_at TIMESTAMP(3) NOT NULL,PRIMARY KEY(review_id),UNIQUE KEY uk_expert_review_v2_outcome(outcome_id),UNIQUE KEY uk_expert_review_v2_diag_only(diagnosis_only_key),INDEX idx_expert_review_v2_diag(diagnosis_id),INDEX idx_expert_review_v2_owner(subject_owner_user_id))");
                statement.execute("CREATE TABLE IF NOT EXISTS expert_review_events (event_id VARCHAR(112) NOT NULL,review_id VARCHAR(96) NOT NULL,event_type VARCHAR(64) NOT NULL,actor_id BIGINT NULL,version BIGINT NOT NULL DEFAULT 0,before_json LONGTEXT NULL,after_json LONGTEXT NULL,created_at TIMESTAMP(3) NOT NULL,PRIMARY KEY(event_id),INDEX idx_expert_review_event_review(review_id),INDEX idx_expert_review_event_created(created_at))");
                schemaChecked = true;
            }
        }
    }

    private Map<String, Object> row(ResultSet rs) throws SQLException {
        Map<String, Object> value = new LinkedHashMap<>(); value.put("reviewId", rs.getString("review_id")); value.put("diagnosisId", rs.getString("diagnosis_id")); value.put("taskId", rs.getString("task_id")); value.put("outcomeId", rs.getString("outcome_id")); value.put("recommendationId", rs.getString("recommendation_id")); value.put("scope", rs.getString("scope")); value.put("diagnosisJudgment", rs.getString("diagnosis_judgment")); value.put("recommendationJudgment", rs.getString("recommendation_judgment")); value.put("outcomeJudgment", rs.getString("outcome_judgment")); value.put("diagnosisCorrection", rs.getString("diagnosis_correction")); value.put("recommendationCorrection", rs.getString("recommendation_correction")); value.put("expertConfidence", rs.getObject("expert_confidence")); value.put("notes", rs.getString("notes")); value.put("reviewerUserId", rs.getObject("reviewer_user_id")); value.put("subjectOwnerUserId", rs.getObject("subject_owner_user_id")); value.put("version", rs.getLong("version")); value.put("createdAt", instant(rs.getTimestamp("created_at"))); value.put("updatedAt", instant(rs.getTimestamp("updated_at"))); return value;
    }

    private Map<String, Object> findFallbackCanonical(Map<String, Object> review) {
        return fallbackReviews.values().stream().filter(existing -> {
            if ("DIAGNOSIS_ONLY".equals(text(review.get("scope")))) return Objects.equals(text(review.get("diagnosisId")), text(existing.get("diagnosisId"))) && "DIAGNOSIS_ONLY".equals(text(existing.get("scope")));
            return Objects.equals(text(review.get("outcomeId")), text(existing.get("outcomeId")));
        }).findFirst().map(this::copy).orElse(null);
    }

    private void authorize(Map<String, Object> review, Long actorId) {
        Object owner = review.get("subjectOwnerUserId"); Object reviewer = review.get("reviewerUserId");
        if (owner != null && actorId != null && !String.valueOf(owner).equals(String.valueOf(actorId)) && (reviewer == null || !String.valueOf(reviewer).equals(String.valueOf(actorId)))) throw ExpertReviewWriteException.forbidden(text(review.get("reviewId")));
        if (owner != null && actorId == null) throw ExpertReviewWriteException.forbidden(text(review.get("reviewId")));
    }

    private boolean visible(Map<String, Object> review, Long actorId) { try { authorize(review, actorId); return true; } catch (ExpertReviewWriteException error) { return false; } }
    private void appendFallbackEvent(Map<String, Object> review, String eventType, Map<String, Object> before, Map<String, Object> after) { Map<String, Object> event = new LinkedHashMap<>(); event.put("eventId", "review-event-" + UUID.randomUUID()); event.put("reviewId", review.get("reviewId")); event.put("eventType", eventType); event.put("actorId", review.get("reviewerUserId")); event.put("version", after.getOrDefault("version", 0L)); event.put("before", before == null ? Map.of() : before); event.put("after", after); event.put("createdAt", Instant.now().toString()); fallbackEvents.computeIfAbsent(text(review.get("reviewId")), key -> new ArrayList<>()).add(event); }
    private static final List<String> DIAGNOSIS_JUDGMENTS = List.of("CORRECT", "PARTIALLY_CORRECT", "INCORRECT", "UNCERTAIN", "NOT_REVIEWED");
    private static final List<String> RECOMMENDATION_JUDGMENTS = List.of("APPROPRIATE", "PARTIALLY_APPROPRIATE", "INAPPROPRIATE", "UNCERTAIN", "NOT_REVIEWED");
    private static final List<String> OUTCOME_JUDGMENTS = List.of("SUCCESSFUL", "PARTIALLY_SUCCESSFUL", "UNSUCCESSFUL", "INCONCLUSIVE");
    private String judgment(Object value, List<String> allowed, String name) { return judgment(value, allowed, name, null); }
    private String judgment(Object value, List<String> allowed, String name, Object current) { if (value == null || String.valueOf(value).isBlank()) return current == null ? null : String.valueOf(current); String normalized = String.valueOf(value).toUpperCase(); if (!allowed.contains(normalized)) throw ExpertReviewWriteException.validation(name + " 值不受支持"); return normalized; }
    private String requiredText(Object value, String message) { String result = optionalText(value); if (result == null) throw ExpertReviewWriteException.validation(message); return result; }
    private String optionalText(Object value) { return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim(); }
    private String nullableText(Object value) { return optionalText(value); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private Long numberOrNull(Object value) { return value == null ? null : number(value, 0L); }
    private long number(Object value, long fallbackValue) { if (value instanceof Number n) return n.longValue(); try { return value == null ? fallbackValue : Long.parseLong(String.valueOf(value)); } catch (NumberFormatException error) { return fallbackValue; } }
    private Double decimal(Object value) { if (value == null || String.valueOf(value).isBlank()) return null; if (value instanceof Number n) return n.doubleValue(); try { return Double.parseDouble(String.valueOf(value)); } catch (NumberFormatException error) { return null; } }
    private Timestamp timestamp(Object value) { try { return value == null ? null : Timestamp.from(value instanceof Instant ? (Instant) value : Instant.parse(String.valueOf(value))); } catch (Exception error) { return null; } }
    private String instant(Timestamp value) { return value == null ? null : value.toInstant().toString(); }
    private void setLong(PreparedStatement statement, int index, Long value) throws SQLException { if (value == null) statement.setNull(index, java.sql.Types.BIGINT); else statement.setLong(index, value); }
    private void setDouble(PreparedStatement statement, int index, Double value) throws SQLException { if (value == null) statement.setNull(index, java.sql.Types.DOUBLE); else statement.setDouble(index, value); }
    private String json(Object value) { try { return mapper.writeValueAsString(value == null ? Map.of() : value); } catch (JsonProcessingException error) { return "{}"; } }
    private Map<String, Object> copy(Map<String, Object> value) { return value == null ? null : new LinkedHashMap<>(value); }
    private void rollback(Connection c) { try { c.rollback(); } catch (SQLException ignored) { } }
    private void resetAutoCommit(Connection c) { try { c.setAutoCommit(true); } catch (SQLException ignored) { } }
    private boolean isDuplicate(SQLException error) { for (SQLException current = error; current != null; current = current.getNextException()) if ("23000".equals(current.getSQLState()) || current.getErrorCode() == 1062) return true; return false; }
    private void requireActorForProduction(Long actorId) { if (dataSource != null && actorId == null) throw ExpertReviewWriteException.forbidden(null); }
    private void requirePersistentReadOrFallback() { if (!allowFallback) throw ExpertReviewWriteException.persistence("Expert Review 数据库不可用，已拒绝读取", null); }
}
