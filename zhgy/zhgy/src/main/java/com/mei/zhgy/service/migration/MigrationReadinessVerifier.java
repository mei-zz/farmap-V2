package com.mei.zhgy.service.migration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Release-time schema verifier for the 5.2 product loop.  It deliberately
 * never creates or alters schema. Runtime CREATE TABLE bridges remain useful
 * for local compatibility, but this component is the production readiness
 * proof that the required tables, columns and uniqueness boundaries exist.
 */
@Component
public class MigrationReadinessVerifier {
    private static final Map<String, List<String>> REQUIRED_COLUMNS = columns();
    private static final Map<String, List<String>> REQUIRED_UNIQUE_INDEXES = uniqueIndexes();
    private final DataSource dataSource;

    @Autowired
    public MigrationReadinessVerifier(@Autowired(required = false) DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Map<String, Object> verify() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("strategy", "PRODUCTION_SCHEMA_VERIFIER");
        report.put("migrationRange", List.of("V5_2_1", "V5_2_2", "V5_2_3", "V5_2_4", "V5_2_5", "V5_2_6", "V5_3_1", "V5_3_2", "V5_4_1"));
        report.put("mode", dataSource == null ? "NO_DATASOURCE" : "DATABASE");
        if (dataSource == null) {
            report.put("status", "NOT_READY");
            report.put("reason", "DATASOURCE_NOT_CONFIGURED");
            report.put("missingTables", new ArrayList<>(REQUIRED_COLUMNS.keySet()));
            report.put("missingColumns", List.of());
            report.put("missingIndexes", List.of());
            return report;
        }
        try (Connection connection = dataSource.getConnection()) {
            List<String> missingTables = new ArrayList<>();
            List<String> missingColumns = new ArrayList<>();
            List<String> missingIndexes = new ArrayList<>();
            for (Map.Entry<String, List<String>> table : REQUIRED_COLUMNS.entrySet()) {
                if (!tableExists(connection, table.getKey())) {
                    missingTables.add(table.getKey());
                    continue;
                }
                for (String column : table.getValue()) {
                    if (!columnExists(connection, table.getKey(), column)) missingColumns.add(table.getKey() + "." + column);
                }
            }
            for (Map.Entry<String, List<String>> index : REQUIRED_UNIQUE_INDEXES.entrySet()) {
                int separator = index.getKey().indexOf(".");
                String table = index.getKey().substring(0, separator);
                String name = index.getKey().substring(separator + 1);
                if (!uniqueIndexMatches(connection, table, name, index.getValue())) missingIndexes.add(table + "." + name + "(" + String.join(",", index.getValue()) + ")");
            }
            report.put("missingTables", missingTables);
            report.put("missingColumns", missingColumns);
            report.put("missingIndexes", missingIndexes);
            boolean ready = missingTables.isEmpty() && missingColumns.isEmpty() && missingIndexes.isEmpty();
            report.put("status", ready ? "READY" : "NOT_READY");
            if (!ready) report.put("reason", "REQUIRED_SCHEMA_MISSING");
            return report;
        } catch (SQLException error) {
            report.put("status", "UNAVAILABLE");
            report.put("reason", "DATABASE_UNAVAILABLE");
            report.put("error", error.getClass().getSimpleName());
            report.put("missingTables", List.of());
            report.put("missingColumns", List.of());
            report.put("missingIndexes", List.of());
            return report;
        }
    }

    public boolean isReady() { return "READY".equals(verify().get("status")); }

    public static Map<String, List<String>> requiredColumns() { return REQUIRED_COLUMNS; }
    public static Map<String, List<String>> requiredUniqueIndexes() { return REQUIRED_UNIQUE_INDEXES; }

    private boolean tableExists(Connection c, String table) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?")) {
            s.setString(1, table);
            try (ResultSet r = s.executeQuery()) { r.next(); return r.getInt(1) == 1; }
        }
    }

    private boolean columnExists(Connection c, String table, String column) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name=?")) {
            s.setString(1, table); s.setString(2, column);
            try (ResultSet r = s.executeQuery()) { r.next(); return r.getInt(1) == 1; }
        }
    }

    private boolean uniqueIndexMatches(Connection c, String table, String index, List<String> expectedColumns) throws SQLException {
        String sql = "SELECT non_unique, GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? AND index_name=? GROUP BY index_name, non_unique";
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, table); s.setString(2, index);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next() || r.getInt(1) != 0) return false;
                return String.join(",", expectedColumns).equalsIgnoreCase(r.getString(2));
            }
        }
    }

    private static Map<String, List<String>> columns() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        result.put("agent_runs", List.of("run_id", "payload"));
        result.put("agent_diagnoses", List.of("diagnosis_id", "source_run_id", "payload_json"));
        result.put("agent_approvals", List.of("run_id", "action_id", "decision_state", "execution_state"));
        result.put("agent_operations_tasks", List.of("task_id", "action_id", "diagnosis_id", "recommendation_id", "agent_run_id", "status", "owner_user_id", "version"));
        result.put("agent_operation_outcomes", List.of("outcome_id", "task_id", "result_type", "summary"));
        result.put("operations_task_events", List.of("event_id", "task_id", "event_type", "actor"));
        result.put("agent_expert_reviews_v2", List.of("review_id", "diagnosis_id", "task_id", "outcome_id", "recommendation_id", "scope", "version"));
        result.put("expert_review_events", List.of("event_id", "review_id", "event_type", "actor_id", "version"));
        result.put("historical_case_candidates", List.of("candidate_id", "review_id", "diagnosis_id", "status", "owner_user_id"));
        result.put("knowledge_sources", List.of("source_id", "source_type", "owner_user_id", "status"));
        result.put("knowledge_documents", List.of("document_id", "source_id", "current_version_id", "owner_user_id", "status"));
        result.put("knowledge_versions", List.of("version_id", "document_id", "version_number", "content_hash"));
        result.put("knowledge_events", List.of("event_id", "entity_id", "event_type", "actor_id"));
        result.put("knowledge_ingestions", List.of("ingestion_id", "source_id", "document_id", "version_id", "status", "source_file_hash", "raw_storage_key", "chunk_count", "embedding_dimension", "started_at", "completed_at"));
        result.put("knowledge_chunks", List.of("chunk_id", "source_id", "document_id", "version_id", "chunk_index", "content_hash", "classification"));
        result.put("farm_fields", List.of("field_id", "farm_id", "name", "source_mode", "owner_user_id"));
        result.put("farm_map_points", List.of("point_id", "farm_id", "point_kind", "source_system", "source_entity_type", "source_legacy_id", "longitude", "latitude", "coordinate_reference_system", "owner_user_id", "status"));
        result.put("copilot_long_term_memories", List.of("memory_id", "user_id", "scope", "scope_id", "memory_type", "memory_key", "memory_value", "status", "source_conversation_id", "source_turn_id", "version"));
        return result;
    }

    private static Map<String, List<String>> uniqueIndexes() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        result.put("agent_approvals.PRIMARY", List.of("run_id", "action_id"));
        result.put("agent_operations_tasks.uk_agent_operations_action_id", List.of("action_id"));
        result.put("agent_operation_outcomes.uk_operation_outcome_task", List.of("task_id"));
        result.put("agent_expert_reviews_v2.uk_expert_review_v2_outcome", List.of("outcome_id"));
        result.put("historical_case_candidates.uk_historical_candidate_review", List.of("review_id"));
        result.put("knowledge_versions.uk_knowledge_version_hash", List.of("document_id", "content_hash"));
        result.put("knowledge_ingestions.uk_knowledge_ingestion_version", List.of("version_id"));
        result.put("knowledge_ingestions.uk_knowledge_ingestion_file_hash", List.of("source_id", "source_file_hash"));
        result.put("knowledge_chunks.uk_knowledge_chunk_version_index", List.of("version_id", "chunk_index"));
        result.put("farm_fields.uk_farm_field_name", List.of("farm_id", "name"));
        result.put("farm_map_points.uk_farm_map_source", List.of("source_system", "source_entity_type", "source_legacy_id"));
        return result;
    }
}
