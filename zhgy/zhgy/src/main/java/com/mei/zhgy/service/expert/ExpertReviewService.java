package com.mei.zhgy.service.expert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Facade over the existing expert workflow with an explicit diagnosis state model. */
@Service
public class ExpertReviewService {
    private final DataSource dataSource;
    private final ObjectMapper mapper;
    private final Map<String, Map<String, Object>> fallback = new ConcurrentHashMap<>();
    private volatile boolean schemaChecked;

    public ExpertReviewService() { this(null, new ObjectMapper()); }
    @Autowired public ExpertReviewService(@Autowired(required = false) DataSource dataSource, ObjectMapper mapper) {
        this.dataSource = dataSource; this.mapper = mapper == null ? new ObjectMapper() : mapper;
    }

    public Map<String, Object> save(String diagnosisId, Map<String, Object> input, String reviewer) {
        Map<String, Object> review = new LinkedHashMap<>();
        String decision = String.valueOf(input == null ? "" : input.getOrDefault("decision", "IN_REVIEW")).toUpperCase();
        String state = "CONFIRMED".equals(decision) ? "CONFIRMED" : "CORRECTED".equals(decision) ? "CORRECTED" : "IN_REVIEW";
        review.put("diagnosisId", diagnosisId); review.put("status", state); review.put("reviewer", reviewer == null ? "current-user" : reviewer);
        review.put("reviewedAt", Instant.now().toString()); review.put("aiDiagnosis", input == null ? null : input.get("aiDiagnosis"));
        review.put("expertDiagnosis", input == null ? null : input.get("expertDiagnosis")); review.put("evidence", input == null ? new ArrayList<>() : input.getOrDefault("evidence", new ArrayList<>()));
        review.put("recommendations", input == null ? new ArrayList<>() : input.getOrDefault("recommendations", new ArrayList<>()));
        review.put("before", input == null ? null : input.get("before")); review.put("after", input == null ? null : input.get("after"));
        if (!upsert(review)) fallback.put(diagnosisId, review);
        return review;
    }

    public Map<String, Object> get(String diagnosisId) {
        if (dataSource != null) {
            try (Connection connection = dataSource.getConnection()) {
                ensureSchema(connection);
                try (PreparedStatement statement = connection.prepareStatement("SELECT diagnosis_id,status,reviewer,reviewed_at,ai_diagnosis,expert_diagnosis,evidence_json,recommendations_json,before_json,after_json FROM agent_expert_reviews WHERE diagnosis_id=?")) {
                    statement.setString(1, diagnosisId);
                    try (ResultSet rs = statement.executeQuery()) { if (rs.next()) return row(rs); }
                }
            } catch (Exception ignored) { }
        }
        return fallback.get(diagnosisId);
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<>(fallback.values());
        if (dataSource != null) {
            try (Connection connection = dataSource.getConnection()) {
                ensureSchema(connection);
                try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT diagnosis_id,status,reviewer,reviewed_at,ai_diagnosis,expert_diagnosis,evidence_json,recommendations_json,before_json,after_json FROM agent_expert_reviews ORDER BY reviewed_at DESC")) {
                    while (rs.next()) result.add(row(rs));
                }
            } catch (Exception ignored) { }
        }
        return result;
    }

    private boolean upsert(Map<String, Object> review) {
        if (dataSource == null) return false;
        try (Connection connection = dataSource.getConnection()) {
            ensureSchema(connection);
            String sql = "INSERT INTO agent_expert_reviews (diagnosis_id,status,reviewer,reviewed_at,ai_diagnosis,expert_diagnosis,evidence_json,recommendations_json,before_json,after_json) VALUES (?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE status=VALUES(status),reviewer=VALUES(reviewer),reviewed_at=VALUES(reviewed_at),ai_diagnosis=VALUES(ai_diagnosis),expert_diagnosis=VALUES(expert_diagnosis),evidence_json=VALUES(evidence_json),recommendations_json=VALUES(recommendations_json),before_json=VALUES(before_json),after_json=VALUES(after_json)";
            try (PreparedStatement s = connection.prepareStatement(sql)) {
                s.setString(1, str(review,"diagnosisId")); s.setString(2, str(review,"status")); s.setString(3, str(review,"reviewer")); s.setString(4, str(review,"reviewedAt"));
                s.setString(5, json(review.get("aiDiagnosis"))); s.setString(6, json(review.get("expertDiagnosis"))); s.setString(7, json(review.get("evidence"))); s.setString(8, json(review.get("recommendations"))); s.setString(9, json(review.get("before"))); s.setString(10, json(review.get("after")));
                s.executeUpdate(); return true;
            }
        } catch (Exception ignored) { return false; }
    }

    private void ensureSchema(Connection c) throws Exception {
        if (schemaChecked) return;
        synchronized (this) { if (schemaChecked) return; try (Statement s = c.createStatement()) { s.execute("CREATE TABLE IF NOT EXISTS agent_expert_reviews (diagnosis_id VARCHAR(100) PRIMARY KEY,status VARCHAR(32),reviewer VARCHAR(100),reviewed_at VARCHAR(64),ai_diagnosis TEXT,expert_diagnosis TEXT,evidence_json TEXT,recommendations_json TEXT,before_json TEXT,after_json TEXT)"); schemaChecked = true; } }
    }
    private Map<String,Object> row(ResultSet r) throws Exception { Map<String,Object> m=new LinkedHashMap<>(); m.put("diagnosisId",r.getString("diagnosis_id"));m.put("status",r.getString("status"));m.put("reviewer",r.getString("reviewer"));m.put("reviewedAt",r.getString("reviewed_at"));m.put("aiDiagnosis",r.getString("ai_diagnosis"));m.put("expertDiagnosis",r.getString("expert_diagnosis"));m.put("evidence",r.getString("evidence_json"));m.put("recommendations",r.getString("recommendations_json"));m.put("before",r.getString("before_json"));m.put("after",r.getString("after_json"));return m; }
    private String str(Map<String,Object> m,String k){return m.get(k)==null?null:String.valueOf(m.get(k));}
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(JsonProcessingException e){return "null";}}
}
