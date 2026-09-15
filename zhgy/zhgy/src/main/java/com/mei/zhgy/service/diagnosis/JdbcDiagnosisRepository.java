package com.mei.zhgy.service.diagnosis;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import com.mei.zhgy.vo.DiagnosisAnalysisResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/** MySQL repository for the Diagnosis business object and its snapshot payload. */
public class JdbcDiagnosisRepository implements DiagnosisRepository {
    private final DataSource dataSource;
    private final ObjectMapper mapper;
    private volatile boolean schemaChecked;

    public JdbcDiagnosisRepository(DataSource dataSource, ObjectMapper mapper) {
        this.dataSource = dataSource;
        this.mapper = mapper == null ? new ObjectMapper().findAndRegisterModules() : mapper;
    }

    @Override
    public DiagnosisRecord save(DiagnosisRecord record) {
        try (Connection connection = dataSource.getConnection()) {
            ensureSchema(connection);
            String payload = mapper.writeValueAsString(record.getPayload());
            String sql = "INSERT INTO agent_diagnoses (diagnosis_id,source_run_id,user_id,farm_id,field_id,crop,status,risk_level,confidence,summary,mode,payload_json,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE source_run_id=VALUES(source_run_id),user_id=VALUES(user_id),farm_id=VALUES(farm_id),field_id=VALUES(field_id),crop=VALUES(crop),status=VALUES(status),risk_level=VALUES(risk_level),confidence=VALUES(confidence),summary=VALUES(summary),mode=VALUES(mode),payload_json=VALUES(payload_json),updated_at=VALUES(updated_at)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, record.getDiagnosisId()); statement.setString(2, record.getSourceRunId());
                setLong(statement, 3, record.getUserId()); setInt(statement, 4, record.getFarmId());
                statement.setString(5, record.getFieldId()); statement.setString(6, record.getCrop());
                statement.setString(7, record.getStatus()); statement.setString(8, record.getRiskLevel());
                statement.setObject(9, record.getConfidence()); statement.setString(10, record.getSummary());
                statement.setString(11, record.getMode()); statement.setString(12, payload);
                statement.setTimestamp(13, timestamp(record.getCreatedAt())); statement.setTimestamp(14, timestamp(record.getUpdatedAt()));
                statement.executeUpdate();
            }
            return record;
        } catch (Exception error) {
            throw new DiagnosisPersistenceException("DIAGNOSIS_PERSISTENCE_UNAVAILABLE", "Diagnosis 未持久化，已拒绝返回稳定身份", error);
        }
    }

    @Override
    public Optional<DiagnosisRecord> find(String diagnosisId, Long userId) {
        try (Connection connection = dataSource.getConnection()) {
            ensureSchema(connection);
            String sql = userId == null
                    ? "SELECT diagnosis_id,source_run_id,user_id,farm_id,field_id,crop,status,risk_level,confidence,summary,mode,payload_json,created_at,updated_at FROM agent_diagnoses WHERE diagnosis_id=? AND user_id IS NULL"
                    : "SELECT diagnosis_id,source_run_id,user_id,farm_id,field_id,crop,status,risk_level,confidence,summary,mode,payload_json,created_at,updated_at FROM agent_diagnoses WHERE diagnosis_id=? AND user_id=?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, diagnosisId);
                if (userId != null) statement.setLong(2, userId);
                try (ResultSet rs = statement.executeQuery()) { return rs.next() ? Optional.of(row(rs)) : Optional.empty(); }
            }
        } catch (DiagnosisPersistenceException error) {
            throw error;
        } catch (Exception error) {
            throw new DiagnosisPersistenceException("DIAGNOSIS_PERSISTENCE_UNAVAILABLE", "Diagnosis 查询不可用", error);
        }
    }

    public void ensureTable() {
        try (Connection connection = dataSource.getConnection()) { ensureSchema(connection); }
        catch (Exception error) { throw new DiagnosisPersistenceException("DIAGNOSIS_PERSISTENCE_UNAVAILABLE", "Diagnosis 表不可用", error); }
    }

    private DiagnosisRecord row(ResultSet rs) throws Exception {
        DiagnosisAnalysisResponse payload = mapper.readValue(rs.getString("payload_json"), DiagnosisAnalysisResponse.class);
        payload.setDiagnosisId(rs.getString("diagnosis_id")); payload.setSourceRunId(rs.getString("source_run_id"));
        payload.setStatus(rs.getString("status")); payload.setCreatedAt(instant(rs.getTimestamp("created_at"))); payload.setUpdatedAt(instant(rs.getTimestamp("updated_at")));
        return DiagnosisRecord.builder().diagnosisId(rs.getString("diagnosis_id")).sourceRunId(rs.getString("source_run_id"))
                .userId((Long) rs.getObject("user_id")).farmId((Integer) rs.getObject("farm_id"))
                .fieldId(rs.getString("field_id")).crop(rs.getString("crop")).status(rs.getString("status"))
                .riskLevel(rs.getString("risk_level")).confidence((Double) rs.getObject("confidence"))
                .summary(rs.getString("summary")).mode(rs.getString("mode")).payload(payload)
                .createdAt(instant(rs.getTimestamp("created_at"))).updatedAt(instant(rs.getTimestamp("updated_at"))).build();
    }

    private void ensureSchema(Connection connection) throws SQLException {
        if (schemaChecked) return;
        synchronized (this) {
            if (schemaChecked) return;
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS agent_diagnoses (diagnosis_id VARCHAR(96) PRIMARY KEY,source_run_id VARCHAR(96) NOT NULL,user_id BIGINT NULL,farm_id INT NULL,field_id VARCHAR(100) NOT NULL,crop VARCHAR(100),status VARCHAR(32) NOT NULL,risk_level VARCHAR(32),confidence DOUBLE,summary TEXT,mode VARCHAR(32),payload_json LONGTEXT NOT NULL,created_at TIMESTAMP(3) NULL,updated_at TIMESTAMP(3) NULL,INDEX idx_agent_diagnoses_source_run (source_run_id),INDEX idx_agent_diagnoses_field (field_id),INDEX idx_agent_diagnoses_user (user_id))");
                schemaChecked = true;
            }
        }
    }

    private void setLong(PreparedStatement statement, int index, Long value) throws SQLException { if (value == null) statement.setNull(index, java.sql.Types.BIGINT); else statement.setLong(index, value); }
    private void setInt(PreparedStatement statement, int index, Integer value) throws SQLException { if (value == null) statement.setNull(index, java.sql.Types.INTEGER); else statement.setInt(index, value); }
    private Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}
