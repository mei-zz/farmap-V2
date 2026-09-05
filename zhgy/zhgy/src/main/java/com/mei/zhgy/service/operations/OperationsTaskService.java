package com.mei.zhgy.service.operations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.service.agent.AgentContext;
import com.mei.zhgy.service.agent.AgentToolRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The single Operations write boundary used by Agent and the Operations page.
 * It uses the application's existing datasource when available and keeps a
 * process-local fallback for demo/test environments where MySQL is absent.
 */
@Service
public class OperationsTaskService {
    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final Map<String, Map<String, Object>> fallback = new ConcurrentHashMap<>();
    private volatile boolean schemaChecked;

    public OperationsTaskService() {
        this(null, new ObjectMapper());
    }

    @Autowired
    public OperationsTaskService(@Autowired(required = false) DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public Map<String, Object> createTask(AgentToolRequest request) {
        AgentContext context = request == null ? null : request.getContext();
        String taskId = "task-" + UUID.randomUUID();
        String fieldId = context == null ? null : context.getFieldId();
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("taskId", taskId);
        task.put("title", "复核 " + (fieldId == null ? "当前地块" : fieldId) + " 农情");
        task.put("fieldId", fieldId);
        task.put("diagnosisId", diagnosisId(context));
        task.put("agentRunId", request == null ? null : request.getRunId());
        task.put("status", "OPEN");
        task.put("priority", "HIGH");
        task.put("source", "agent");
        task.put("createdFrom", "FarMap Agent");
        task.put("createdBy", "FarMap Agent");
        task.put("goal", request == null ? null : request.getGoal());
        task.put("evidenceIds", request == null ? new ArrayList<>() : request.getAttributes().getOrDefault("evidenceIds", new ArrayList<>()));
        task.put("approvedBy", "current-user");
        task.put("approvedAt", Instant.now().toString());
        task.put("createdAt", Instant.now().toString());
        if (!insert(task)) fallback.put(taskId, task);
        return task;
    }

    public List<Map<String, Object>> listTasks() {
        List<Map<String, Object>> result = new ArrayList<>();
        if (dataSource != null) {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement("SELECT task_id,title,field_id,diagnosis_id,agent_run_id,status,priority,source,created_from,created_by,evidence_ids,approved_by,approved_at,created_at,goal FROM agent_operations_tasks ORDER BY created_at DESC")) {
                ensureSchema(connection);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) result.add(row(rs));
                }
                return result;
            } catch (Exception ignored) {
                // Fall through to the local records so a disconnected DB never breaks the UI.
            }
        }
        result.addAll(fallback.values());
        result.sort(Comparator.comparing(item -> String.valueOf(item.getOrDefault("createdAt", "")), Comparator.reverseOrder()));
        return result;
    }

    public int count() { return listTasks().size(); }

    private boolean insert(Map<String, Object> task) {
        if (dataSource == null) return false;
        try (Connection connection = dataSource.getConnection()) {
            ensureSchema(connection);
            String sql = "INSERT INTO agent_operations_tasks (task_id,title,field_id,diagnosis_id,agent_run_id,status,priority,source,created_from,created_by,evidence_ids,approved_by,approved_at,created_at,goal) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, value(task, "taskId")); statement.setString(2, value(task, "title"));
                statement.setString(3, value(task, "fieldId")); statement.setString(4, value(task, "diagnosisId"));
                statement.setString(5, value(task, "agentRunId")); statement.setString(6, value(task, "status"));
                statement.setString(7, value(task, "priority")); statement.setString(8, value(task, "source"));
                statement.setString(9, value(task, "createdFrom")); statement.setString(10, value(task, "createdBy"));
                statement.setString(11, json(task.get("evidenceIds"))); statement.setString(12, value(task, "approvedBy"));
                statement.setString(13, value(task, "approvedAt")); statement.setString(14, value(task, "createdAt"));
                statement.setString(15, value(task, "goal"));
                statement.executeUpdate();
                return true;
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    private void ensureSchema(Connection connection) throws Exception {
        if (schemaChecked) return;
        synchronized (this) {
            if (schemaChecked) return;
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS agent_operations_tasks (task_id VARCHAR(80) PRIMARY KEY,title VARCHAR(255),field_id VARCHAR(100),diagnosis_id VARCHAR(100),agent_run_id VARCHAR(100),status VARCHAR(32),priority VARCHAR(32),source VARCHAR(64),created_from VARCHAR(100),created_by VARCHAR(100),evidence_ids TEXT,approved_by VARCHAR(100),approved_at VARCHAR(64),created_at VARCHAR(64),goal TEXT)");
                schemaChecked = true;
            }
        }
    }

    private Map<String, Object> row(ResultSet rs) throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("taskId", rs.getString("task_id")); row.put("title", rs.getString("title"));
        row.put("fieldId", rs.getString("field_id")); row.put("diagnosisId", rs.getString("diagnosis_id"));
        row.put("agentRunId", rs.getString("agent_run_id")); row.put("status", rs.getString("status"));
        row.put("priority", rs.getString("priority")); row.put("source", rs.getString("source"));
        row.put("createdFrom", rs.getString("created_from")); row.put("createdBy", rs.getString("created_by"));
        row.put("evidenceIds", rs.getString("evidence_ids")); row.put("approvedBy", rs.getString("approved_by"));
        row.put("approvedAt", rs.getString("approved_at")); row.put("createdAt", rs.getString("created_at"));
        row.put("goal", rs.getString("goal"));
        return row;
    }

    private String diagnosisId(AgentContext context) {
        if (context == null || context.getDiagnosis() == null) return null;
        Object id = context.getDiagnosis().get("id");
        return id == null ? null : String.valueOf(id);
    }

    private String value(Map<String, Object> map, String key) { return map.get(key) == null ? null : String.valueOf(map.get(key)); }
    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value == null ? new ArrayList<>() : value); }
        catch (JsonProcessingException error) { return "[]"; }
    }
}
