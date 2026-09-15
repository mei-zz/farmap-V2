package com.mei.zhgy.service.operations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.service.agent.AgentContext;
import com.mei.zhgy.service.agent.AgentToolRequest;
import com.mei.zhgy.service.agent.ProductLoopRelationException;
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

/** Durable Operations lifecycle boundary. Production writes never fall back to memory. */
@Service
public class OperationsTaskService {
    private static final String TASK_COLUMNS = "task_id,action_id,title,field_id,diagnosis_id,recommendation_id,agent_run_id,status,priority,source,created_from,created_by,evidence_ids,approved_by,approved_at,created_at,updated_at,owner_user_id,assignee,assigned_at,started_at,completed_at,cancelled_at,cancelled_reason,version,goal";

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final boolean allowFallbackWrites;
    private final Map<String, Map<String, Object>> fallback = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> fallbackOutcomes = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, Object>>> fallbackEvents = new ConcurrentHashMap<>();
    private volatile boolean schemaChecked;

    /** Explicit local-only constructor retained for deterministic unit/demo construction. */
    public OperationsTaskService() { this(null, new ObjectMapper(), true); }

    @Autowired
    public OperationsTaskService(@Autowired(required = false) DataSource dataSource, ObjectMapper objectMapper) {
        this(dataSource, objectMapper, false);
    }

    private OperationsTaskService(DataSource dataSource, ObjectMapper objectMapper, boolean allowFallbackWrites) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.allowFallbackWrites = allowFallbackWrites;
    }

    public Map<String, Object> createTask(AgentToolRequest request) {
        String actionId = request == null ? null : request.getActionId();
        validateProvenance(request);
        if (request != null && request.isApprovalGranted() && actionId == null && !allowFallbackWrites) {
            throw new OperationsWriteException("ACTION_ID_REQUIRED", "生产写入必须携带 actionId");
        }
        if (dataSource == null) {
            if (!allowFallbackWrites) throw persistenceUnavailable("Operations 数据库不可用，已拒绝创建任务", null);
            synchronized (this) {
                Map<String, Object> existing = actionId == null ? null : findFallbackByActionId(actionId);
                if (existing != null) return existing;
                Map<String, Object> task = buildTask(request);
                fallback.put(string(task, "taskId"), task);
                appendFallbackEvent(task, "TASK_CREATED", null, "OPEN", actor(request), null, nowString());
                return copy(task);
            }
        }
        try (Connection connection = dataSource.getConnection()) {
            ensureSchema(connection);
            connection.setAutoCommit(false);
            try {
                Map<String, Object> existing = actionId == null ? null : findByActionId(connection, actionId, false);
                if (existing != null) { connection.commit(); return existing; }
                Map<String, Object> task = buildTask(request);
                insertTask(connection, task);
                insertEvent(connection, task, "TASK_CREATED", null, "OPEN", actor(request), null, Instant.now());
                connection.commit();
                return task;
            } catch (SQLException error) {
                rollback(connection);
                if (isDuplicate(error) && actionId != null) {
                    Map<String, Object> existing = findByActionId(connection, actionId, false);
                    if (existing != null) return existing;
                }
                throw error;
            } catch (Exception error) {
                rollback(connection);
                throw error;
            } finally { resetAutoCommit(connection); }
        } catch (OperationsWriteException error) { throw error; }
        catch (Exception error) { throw persistenceUnavailable("Operations 任务未持久化，已拒绝写入", error); }
    }

    /** Compatibility read for direct local tests; HTTP callers use the actor-aware overload. */
    public List<Map<String, Object>> listTasks() { return listTasks(null); }

    public List<Map<String, Object>> listTasks(Long actorId) {
        if (dataSource == null) { requirePersistentReadOrFallback(); return filterTasks(sortedFallbackTasks(), actorId); }
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            ensureSchema(connection);
            try (PreparedStatement statement = connection.prepareStatement("SELECT " + TASK_COLUMNS + " FROM agent_operations_tasks ORDER BY created_at DESC")) {
                try (ResultSet rs = statement.executeQuery()) { while (rs.next()) result.add(row(rs)); }
            }
            return filterTasks(result, actorId);
        } catch (Exception error) { throw persistenceUnavailable("Operations 任务查询不可用", error); }
    }

    public int count() { return listTasks().size(); }

    /** Honest dependency health used by the release readiness surface. */
    public Map<String, Object> health() {
        try {
            int tasks = dataSource == null ? fallback.size() : listTasks().size();
            return Map.of("status", dataSource == null ? (allowFallbackWrites ? "DEMO" : "UNAVAILABLE") : "READY", "tasks", tasks,
                    "mode", dataSource == null ? "fallback" : "database");
        } catch (Exception error) {
            return Map.of("status", "UNAVAILABLE", "mode", "database", "error", error.getClass().getSimpleName());
        }
    }

    /** Compatibility read for direct local tests; HTTP callers use the actor-aware overload. */
    public Map<String, Object> getTask(String taskId) { return getTask(taskId, null); }

    public Map<String, Object> getTask(String taskId, Long actorId) {
        if (taskId == null || taskId.isBlank()) throw OperationsWriteException.notFound(taskId);
        if (dataSource == null) {
            requirePersistentReadOrFallback();
            Map<String, Object> task = fallback.get(taskId);
            if (task == null) throw OperationsWriteException.notFound(taskId);
            authorize(task, actorId);
            return copy(task);
        }
        try (Connection connection = dataSource.getConnection()) {
            ensureSchema(connection);
            Map<String, Object> task = findById(connection, taskId, false);
            if (task == null) throw OperationsWriteException.notFound(taskId);
            authorize(task, actorId);
            return task;
        } catch (OperationsWriteException error) { throw error; }
        catch (Exception error) { throw persistenceUnavailable("Operations 任务查询不可用", error); }
    }

    /** Recovery read used after a process crash between task commit and approval completion. */
    public Map<String, Object> findTaskByActionId(String actionId) {
        if (actionId == null) return null;
        if (dataSource == null) { requirePersistentReadOrFallback(); return findFallbackByActionId(actionId); }
        try (Connection connection = dataSource.getConnection()) {
            ensureSchema(connection);
            return findByActionId(connection, actionId, false);
        } catch (Exception error) { throw persistenceUnavailable("无法核对已创建的 Operations 任务", error); }
    }

    public Map<String, Object> assignTask(String taskId, String assignee, Long actorId) {
        if (assignee == null || assignee.isBlank()) throw OperationsWriteException.validation(taskId, "assignee 不能为空");
        return transition(taskId, actorId, "ASSIGNED", assignee.trim(), null);
    }

    public Map<String, Object> startTask(String taskId, Long actorId) {
        return transition(taskId, actorId, "IN_PROGRESS", null, null);
    }

    public Map<String, Object> cancelTask(String taskId, String reason, Long actorId) {
        if (reason == null || reason.isBlank()) throw OperationsWriteException.validation(taskId, "取消任务必须提供 reason");
        return transition(taskId, actorId, "CANCELLED", null, reason.trim());
    }

    /** Completion is the only canonical Outcome creation path. */
    public Map<String, Object> completeTask(String taskId, String summary, String executionEvidence, String notes, Long actorId) {
        if (summary == null || summary.isBlank()) throw OperationsWriteException.validation(taskId, "完成任务必须提供 summary");
        if (dataSource == null) { requirePersistentReadOrFallback(); return completeFallback(taskId, summary.trim(), executionEvidence, notes, actorId); }
        try (Connection connection = dataSource.getConnection()) {
            ensureSchema(connection);
            connection.setAutoCommit(false);
            try {
                Map<String, Object> task = findById(connection, taskId, true);
                if (task == null) throw OperationsWriteException.notFound(taskId);
                authorize(task, actorId);
                String status = upper(task.get("status"));
                if ("COMPLETED".equals(status)) {
                    Map<String, Object> existingOutcome = findOutcome(connection, taskId);
                    if (existingOutcome == null) throw OperationsWriteException.conflict(taskId, "任务已完成但缺少 Outcome，拒绝伪造完成结果");
                    connection.commit();
                    return completionBody(task, existingOutcome);
                }
                requireTransition(taskId, status, "COMPLETED", "IN_PROGRESS".equals(status));
                Instant now = Instant.now();
                updateTask(connection, taskId, "COMPLETED", now, null, null);
                Map<String, Object> outcome = buildOutcome(taskId, summary.trim(), executionEvidence, notes, actorId, now);
                insertOutcome(connection, outcome);
                insertEvent(connection, task, "TASK_COMPLETED", status, "COMPLETED", actor(actorId), null, now);
                // Keep the append-only audit order deterministic when TIMESTAMP(3) is used.
                insertOutcomeEvent(connection, task, outcome, now.plusMillis(1));
                connection.commit();
                applyTransition(task, "COMPLETED", now, null, null);
                return completionBody(task, outcome);
            } catch (Exception error) { rollback(connection); throw error; }
            finally { resetAutoCommit(connection); }
        } catch (OperationsWriteException error) { throw error; }
        catch (Exception error) { throw persistenceUnavailable("任务完成事务未提交", error); }
    }

    public Map<String, Object> getOutcome(String taskId, Long actorId) {
        Map<String, Object> task = getTask(taskId, actorId);
        if (dataSource == null) {
            Map<String, Object> outcome = fallbackOutcomes.get(taskId);
            if (outcome == null) throw OperationsWriteException.notFound("outcome:" + taskId);
            return enrichOutcomeProvenance(task, copy(outcome));
        }
        try (Connection connection = dataSource.getConnection()) {
            ensureSchema(connection);
            Map<String, Object> outcome = findOutcome(connection, taskId);
            if (outcome == null) throw OperationsWriteException.notFound("outcome:" + taskId);
            return enrichOutcomeProvenance(task, outcome);
        } catch (OperationsWriteException error) { throw error; }
        catch (Exception error) { throw persistenceUnavailable("Outcome 查询不可用", error); }
    }

    public List<Map<String, Object>> listEvents(String taskId, Long actorId) {
        Map<String, Object> task = getTask(taskId, actorId);
        if (dataSource == null) return fallbackEvents.getOrDefault(taskId, List.of()).stream().map(this::copy).toList();
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            ensureSchema(connection);
            try (PreparedStatement statement = connection.prepareStatement("SELECT event_id,task_id,event_type,from_status,to_status,actor,reason,metadata_json,created_at FROM operations_task_events WHERE task_id=? ORDER BY created_at ASC,event_id ASC")) {
                statement.setString(1, taskId);
                try (ResultSet rs = statement.executeQuery()) { while (rs.next()) result.add(eventRow(rs)); }
            }
            return result;
        } catch (Exception error) { throw persistenceUnavailable("Operations 审计记录查询不可用", error); }
    }

    private Map<String, Object> transition(String taskId, Long actorId, String target, String assignee, String cancelReason) {
        if (dataSource == null) {
            requirePersistentReadOrFallback();
            synchronized (this) {
                Map<String, Object> task = fallback.get(taskId);
                if (task == null) throw OperationsWriteException.notFound(taskId);
                authorize(task, actorId);
                String current = upper(task.get("status"));
                requireTransition(taskId, current, target, isAllowed(current, target));
                Instant now = Instant.now(); applyTransition(task, target, now, assignee, cancelReason);
                appendFallbackEvent(task, eventType(target), current, target, actor(actorId), cancelReason, now.toString());
                return copy(task);
            }
        }
        try (Connection connection = dataSource.getConnection()) {
            ensureSchema(connection); connection.setAutoCommit(false);
            try {
                Map<String, Object> task = findById(connection, taskId, true);
                if (task == null) throw OperationsWriteException.notFound(taskId);
                authorize(task, actorId);
                String current = upper(task.get("status"));
                requireTransition(taskId, current, target, isAllowed(current, target));
                Instant now = Instant.now();
                updateTask(connection, taskId, target, now, assignee, cancelReason);
                insertEvent(connection, task, eventType(target), current, target, actor(actorId), cancelReason, now);
                connection.commit(); applyTransition(task, target, now, assignee, cancelReason); return task;
            } catch (Exception error) { rollback(connection); throw error; }
            finally { resetAutoCommit(connection); }
        } catch (OperationsWriteException error) { throw error; }
        catch (Exception error) { throw persistenceUnavailable("Operations 状态转换事务未提交", error); }
    }

    private Map<String, Object> completeFallback(String taskId, String summary, String evidence, String notes, Long actorId) {
        synchronized (this) {
            Map<String, Object> task = fallback.get(taskId);
            if (task == null) throw OperationsWriteException.notFound(taskId);
            authorize(task, actorId);
            String status = upper(task.get("status"));
            if ("COMPLETED".equals(status)) {
                Map<String, Object> existing = fallbackOutcomes.get(taskId);
                if (existing == null) throw OperationsWriteException.conflict(taskId, "任务已完成但缺少 Outcome，拒绝伪造完成结果");
                return completionBody(copy(task), copy(existing));
            }
            requireTransition(taskId, status, "COMPLETED", "IN_PROGRESS".equals(status));
            Instant now = Instant.now(); applyTransition(task, "COMPLETED", now, null, null);
            Map<String, Object> outcome = buildOutcome(taskId, summary, evidence, notes, actorId, now);
            fallbackOutcomes.put(taskId, outcome);
            appendFallbackEvent(task, "TASK_COMPLETED", status, "COMPLETED", actor(actorId), null, now.toString());
            appendFallbackEvent(task, "OUTCOME_CREATED", null, null, actor(actorId), null, now.plusMillis(1).toString());
            return completionBody(copy(task), copy(outcome));
        }
    }

    private Map<String, Object> buildTask(AgentToolRequest request) {
        AgentContext context = request == null ? null : request.getContext();
        String fieldId = context == null ? null : context.getFieldId(); Instant now = Instant.now();
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("taskId", "task-" + UUID.randomUUID()); task.put("actionId", request == null ? null : request.getActionId());
        String fieldName = context == null ? null : context.getFieldName();
        task.put("title", "复核 " + (fieldName == null || fieldName.isBlank() ? fieldId == null ? "当前地块" : fieldId : fieldName) + " 农情"); task.put("fieldId", fieldId);
        task.put("diagnosisId", serverDiagnosisId(request, context)); task.put("recommendationId", serverRecommendationId(request, context));
        task.put("agentRunId", request == null ? null : request.getRunId()); task.put("status", "OPEN"); task.put("priority", "HIGH");
        task.put("source", "agent"); task.put("createdFrom", "FarMap Agent"); task.put("createdBy", "FarMap Agent");
        task.put("goal", request == null ? null : request.getGoal()); task.put("evidenceIds", request == null ? new ArrayList<>() : request.getAttributes().getOrDefault("evidenceIds", new ArrayList<>()));
        task.put("approvedBy", actor(request)); task.put("approvedAt", now.toString()); task.put("createdAt", now.toString()); task.put("updatedAt", now.toString());
        task.put("ownerUserId", request == null ? null : request.getOwnerUserId()); task.put("version", 0L); return task;
    }

    private void insertTask(Connection c, Map<String, Object> task) throws SQLException {
        String sql = "INSERT INTO agent_operations_tasks (task_id,action_id,title,field_id,diagnosis_id,recommendation_id,agent_run_id,status,priority,source,created_from,created_by,evidence_ids,approved_by,approved_at,created_at,updated_at,owner_user_id,assignee,assigned_at,started_at,completed_at,cancelled_at,cancelled_reason,version,goal) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1,string(task,"taskId")); s.setString(2,string(task,"actionId")); s.setString(3,string(task,"title")); s.setString(4,string(task,"fieldId")); s.setString(5,string(task,"diagnosisId")); s.setString(6,string(task,"recommendationId")); s.setString(7,string(task,"agentRunId")); s.setString(8,string(task,"status")); s.setString(9,string(task,"priority")); s.setString(10,string(task,"source")); s.setString(11,string(task,"createdFrom")); s.setString(12,string(task,"createdBy")); s.setString(13,json(task.get("evidenceIds"))); s.setString(14,string(task,"approvedBy")); s.setString(15,string(task,"approvedAt")); s.setString(16,string(task,"createdAt")); s.setTimestamp(17,timestamp(task.get("updatedAt"))); setLong(s,18,number(task.get("ownerUserId"))); s.setString(19,string(task,"assignee")); s.setTimestamp(20,timestamp(task.get("assignedAt"))); s.setTimestamp(21,timestamp(task.get("startedAt"))); s.setTimestamp(22,timestamp(task.get("completedAt"))); s.setTimestamp(23,timestamp(task.get("cancelledAt"))); s.setString(24,string(task,"cancelledReason")); s.setLong(25,0L); s.setString(26,string(task,"goal")); s.executeUpdate();
        }
    }

    private void updateTask(Connection c,String taskId,String status,Instant now,String assignee,String cancelReason)throws SQLException{
        String sql="UPDATE agent_operations_tasks SET status=?,updated_at=?,assignee=COALESCE(?,assignee),assigned_at=CASE WHEN ?='ASSIGNED' THEN ? ELSE assigned_at END,started_at=CASE WHEN ?='IN_PROGRESS' THEN ? ELSE started_at END,completed_at=CASE WHEN ?='COMPLETED' THEN ? ELSE completed_at END,cancelled_at=CASE WHEN ?='CANCELLED' THEN ? ELSE cancelled_at END,cancelled_reason=CASE WHEN ?='CANCELLED' THEN ? ELSE cancelled_reason END,version=version+1 WHERE task_id=?";
        try(PreparedStatement s=c.prepareStatement(sql)){int i=1;s.setString(i++,status);s.setTimestamp(i++,Timestamp.from(now));s.setString(i++,assignee);s.setString(i++,status);s.setTimestamp(i++,Timestamp.from(now));s.setString(i++,status);s.setTimestamp(i++,Timestamp.from(now));s.setString(i++,status);s.setTimestamp(i++,Timestamp.from(now));s.setString(i++,status);s.setTimestamp(i++,Timestamp.from(now));s.setString(i++,status);s.setString(i++,cancelReason);s.setString(i,taskId);if(s.executeUpdate()!=1)throw OperationsWriteException.conflict(taskId,"任务状态未更新");}
    }

    private void insertOutcome(Connection c,Map<String,Object> o)throws SQLException{try(PreparedStatement s=c.prepareStatement("INSERT INTO agent_operation_outcomes (outcome_id,task_id,result_type,summary,execution_evidence,notes,actor_id,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?)")){s.setString(1,string(o,"outcomeId"));s.setString(2,string(o,"taskId"));s.setString(3,string(o,"resultType"));s.setString(4,string(o,"summary"));s.setString(5,string(o,"executionEvidence"));s.setString(6,string(o,"notes"));s.setString(7,string(o,"actor"));s.setTimestamp(8,timestamp(o.get("createdAt")));s.setTimestamp(9,timestamp(o.get("updatedAt")));s.executeUpdate();}}
    private void insertEvent(Connection c,Map<String,Object> t,String type,String from,String to,String actor,String reason,Instant at)throws SQLException{try(PreparedStatement s=c.prepareStatement("INSERT INTO operations_task_events (event_id,task_id,event_type,from_status,to_status,actor,reason,metadata_json,created_at) VALUES (?,?,?,?,?,?,?,?,?)")){s.setString(1,"event-"+UUID.randomUUID());s.setString(2,string(t,"taskId"));s.setString(3,type);s.setString(4,from);s.setString(5,to);s.setString(6,actor);s.setString(7,reason);s.setString(8,"{}");s.setTimestamp(9,Timestamp.from(at));s.executeUpdate();}}
    private void insertOutcomeEvent(Connection c,Map<String,Object> t,Map<String,Object> o,Instant at)throws SQLException{insertEvent(c,t,"OUTCOME_CREATED",null,null,string(o,"actor"),null,at);}
    private Map<String,Object> buildOutcome(String id,String summary,String evidence,String notes,Long actorId,Instant now){Map<String,Object> o=new LinkedHashMap<>();o.put("outcomeId","outcome-"+UUID.randomUUID());o.put("taskId",id);o.put("resultType","EXECUTION_RESULT");o.put("summary",summary);o.put("executionEvidence",evidence);o.put("notes",notes);o.put("actor",actor(actorId));o.put("createdAt",now.toString());o.put("updatedAt",now.toString());return o;}
    private Map<String,Object> completionBody(Map<String,Object> t,Map<String,Object> o){Map<String,Object>b=new LinkedHashMap<>();b.put("task",t);b.put("outcome",enrichOutcomeProvenance(t,o));return b;}
    /**
     * Outcome rows intentionally remain small and immutable. Their source
     * provenance is the already-persisted Operations task relation, so expose
     * that relation on every Outcome response without duplicating columns.
     */
    private Map<String,Object> enrichOutcomeProvenance(Map<String,Object> task,Map<String,Object> outcome){
        if (outcome == null) return null;
        if (task == null) return outcome;
        outcome.put("diagnosisId", task.get("diagnosisId"));
        outcome.put("recommendationId", task.get("recommendationId"));
        outcome.put("agentRunId", task.get("agentRunId"));
        outcome.put("fieldId", task.get("fieldId"));
        outcome.put("evidenceIds", task.get("evidenceIds"));
        return outcome;
    }
    private Map<String,Object> findById(Connection c,String id,boolean lock)throws Exception{return findOne(c,"task_id=?",id,lock);}
    private Map<String,Object> findByActionId(Connection c,String id,boolean lock)throws Exception{return findOne(c,"action_id=?",id,lock);}
    private Map<String,Object> findOne(Connection c,String predicate,String value,boolean lock)throws Exception{try(PreparedStatement s=c.prepareStatement("SELECT "+TASK_COLUMNS+" FROM agent_operations_tasks WHERE "+predicate+(lock?" FOR UPDATE":""))){s.setString(1,value);try(ResultSet r=s.executeQuery()){return r.next()?row(r):null;}}}
    private Map<String,Object> findOutcome(Connection c,String id)throws SQLException{try(PreparedStatement s=c.prepareStatement("SELECT outcome_id,task_id,result_type,summary,execution_evidence,notes,actor_id,created_at,updated_at FROM agent_operation_outcomes WHERE task_id=?")){s.setString(1,id);try(ResultSet r=s.executeQuery()){return r.next()?outcomeRow(r):null;}}}

    private void ensureSchema(Connection c)throws SQLException{if(schemaChecked)return;synchronized(this){if(schemaChecked)return;try(Statement s=c.createStatement()){
        s.execute("CREATE TABLE IF NOT EXISTS agent_operations_tasks (task_id VARCHAR(80) PRIMARY KEY,action_id VARCHAR(180) NULL,title VARCHAR(255),field_id VARCHAR(100),diagnosis_id VARCHAR(100),recommendation_id VARCHAR(100) NULL,agent_run_id VARCHAR(100),status VARCHAR(32) DEFAULT 'OPEN',priority VARCHAR(32),source VARCHAR(64),created_from VARCHAR(100),created_by VARCHAR(100),evidence_ids TEXT,approved_by VARCHAR(100),approved_at VARCHAR(64),created_at VARCHAR(64),goal TEXT)");
        alter(s,"action_id VARCHAR(180) NULL");alter(s,"recommendation_id VARCHAR(100) NULL");alter(s,"updated_at TIMESTAMP(3) NULL");alter(s,"owner_user_id BIGINT NULL");alter(s,"assignee VARCHAR(128) NULL");alter(s,"assigned_at TIMESTAMP(3) NULL");alter(s,"started_at TIMESTAMP(3) NULL");alter(s,"completed_at TIMESTAMP(3) NULL");alter(s,"cancelled_at TIMESTAMP(3) NULL");alter(s,"cancelled_reason TEXT NULL");alter(s,"version BIGINT NOT NULL DEFAULT 0");
        try{s.execute("CREATE UNIQUE INDEX uk_agent_operations_action_id ON agent_operations_tasks(action_id)");}catch(SQLException ignored){}
        s.execute("CREATE TABLE IF NOT EXISTS agent_operation_outcomes (outcome_id VARCHAR(96) PRIMARY KEY,task_id VARCHAR(80) NOT NULL,result_type VARCHAR(64) NOT NULL,summary TEXT NOT NULL,execution_evidence TEXT,notes TEXT,actor_id VARCHAR(128),created_at TIMESTAMP(3) NOT NULL,updated_at TIMESTAMP(3) NOT NULL,UNIQUE KEY uk_operation_outcome_task(task_id),INDEX idx_operation_outcome_created(created_at))");
        s.execute("CREATE TABLE IF NOT EXISTS operations_task_events (event_id VARCHAR(96) PRIMARY KEY,task_id VARCHAR(80) NOT NULL,event_type VARCHAR(64) NOT NULL,from_status VARCHAR(32),to_status VARCHAR(32),actor VARCHAR(128) NOT NULL,reason TEXT,metadata_json TEXT,created_at TIMESTAMP(3) NOT NULL,INDEX idx_operations_event_task(task_id),INDEX idx_operations_event_created(created_at))");schemaChecked=true;
    }}}
    private void alter(Statement s,String definition){try{s.execute("ALTER TABLE agent_operations_tasks ADD COLUMN "+definition);}catch(SQLException ignored){}}
    private Map<String,Object> row(ResultSet r)throws Exception{Map<String,Object>m=new LinkedHashMap<>();m.put("taskId",r.getString("task_id"));m.put("actionId",r.getString("action_id"));m.put("title",r.getString("title"));m.put("fieldId",r.getString("field_id"));m.put("diagnosisId",r.getString("diagnosis_id"));m.put("recommendationId",r.getString("recommendation_id"));m.put("agentRunId",r.getString("agent_run_id"));m.put("status",r.getString("status"));m.put("priority",r.getString("priority"));m.put("source",r.getString("source"));m.put("createdFrom",r.getString("created_from"));m.put("createdBy",r.getString("created_by"));m.put("evidenceIds",r.getString("evidence_ids"));m.put("approvedBy",r.getString("approved_by"));m.put("approvedAt",r.getString("approved_at"));m.put("createdAt",r.getString("created_at"));m.put("updatedAt",r.getString("updated_at"));m.put("ownerUserId",r.getObject("owner_user_id"));m.put("assignee",r.getString("assignee"));m.put("assignedAt",instant(r.getTimestamp("assigned_at")));m.put("startedAt",instant(r.getTimestamp("started_at")));m.put("completedAt",instant(r.getTimestamp("completed_at")));m.put("cancelledAt",instant(r.getTimestamp("cancelled_at")));m.put("cancelledReason",r.getString("cancelled_reason"));m.put("version",r.getLong("version"));m.put("goal",r.getString("goal"));return m;}
    private Map<String,Object> outcomeRow(ResultSet r)throws SQLException{Map<String,Object>m=new LinkedHashMap<>();m.put("outcomeId",r.getString("outcome_id"));m.put("taskId",r.getString("task_id"));m.put("resultType",r.getString("result_type"));m.put("summary",r.getString("summary"));m.put("executionEvidence",r.getString("execution_evidence"));m.put("notes",r.getString("notes"));m.put("actor",r.getString("actor_id"));m.put("createdAt",instant(r.getTimestamp("created_at")));m.put("updatedAt",instant(r.getTimestamp("updated_at")));return m;}
    private Map<String,Object> eventRow(ResultSet r)throws SQLException{Map<String,Object>m=new LinkedHashMap<>();m.put("eventId",r.getString("event_id"));m.put("taskId",r.getString("task_id"));m.put("eventType",r.getString("event_type"));m.put("fromStatus",r.getString("from_status"));m.put("toStatus",r.getString("to_status"));m.put("actor",r.getString("actor"));m.put("reason",r.getString("reason"));m.put("metadata",r.getString("metadata_json"));m.put("createdAt",instant(r.getTimestamp("created_at")));return m;}
    private boolean isAllowed(String from,String to){return("OPEN".equals(from)&&("ASSIGNED".equals(to)||"IN_PROGRESS".equals(to)||"CANCELLED".equals(to)))||("ASSIGNED".equals(from)&&("IN_PROGRESS".equals(to)||"CANCELLED".equals(to)))||("IN_PROGRESS".equals(from)&&"CANCELLED".equals(to));}
    private void authorize(Map<String,Object> task,Long actorId){Object owner=task.get("ownerUserId");if(owner!=null&&(actorId==null||!String.valueOf(owner).equals(String.valueOf(actorId))))throw OperationsWriteException.forbidden(string(task,"taskId"));}
    private void requireTransition(String id,String current,String target,boolean allowed){if(!allowed)throw OperationsWriteException.invalidTransition(id,current,target);}
    private void applyTransition(Map<String,Object>t,String target,Instant now,String assignee,String reason){t.put("status",target);t.put("updatedAt",now.toString());if("ASSIGNED".equals(target)){t.put("assignee",assignee);t.put("assignedAt",now.toString());}if("IN_PROGRESS".equals(target))t.put("startedAt",now.toString());if("COMPLETED".equals(target))t.put("completedAt",now.toString());if("CANCELLED".equals(target)){t.put("cancelledAt",now.toString());t.put("cancelledReason",reason);}Long v=number(t.get("version"));t.put("version",v==null?1L:v+1L);}
    private String eventType(String target){return"ASSIGNED".equals(target)?"TASK_ASSIGNED":"IN_PROGRESS".equals(target)?"TASK_STARTED":"CANCELLED".equals(target)?"TASK_CANCELLED":"TASK_"+target;}
    private void appendFallbackEvent(Map<String,Object>t,String type,String from,String to,String actor,String reason,String at){Map<String,Object>e=new LinkedHashMap<>();e.put("eventId","event-"+UUID.randomUUID());e.put("taskId",string(t,"taskId"));e.put("eventType",type);e.put("fromStatus",from);e.put("toStatus",to);e.put("actor",actor);e.put("reason",reason);e.put("metadata","{}");e.put("createdAt",at);fallbackEvents.computeIfAbsent(string(t,"taskId"),k->new ArrayList<>()).add(e);}
    private Map<String,Object> findFallbackByActionId(String id){return fallback.values().stream().filter(t->id.equals(string(t,"actionId"))).findFirst().map(this::copy).orElse(null);}
    private List<Map<String,Object>> sortedFallbackTasks(){return new ArrayList<>(fallback.values().stream().map(this::copy).sorted(Comparator.comparing(t->String.valueOf(t.getOrDefault("createdAt","")),Comparator.reverseOrder())).toList());}
    private List<Map<String,Object>> filterTasks(List<Map<String,Object>> tasks, Long actorId){
        if (actorId == null) return tasks;
        return tasks.stream().filter(task -> {
            Object owner = task.get("ownerUserId");
            return owner == null || String.valueOf(actorId).equals(String.valueOf(owner));
        }).toList();
    }
    private Map<String,Object> copy(Map<String,Object>m){return m==null?null:new LinkedHashMap<>(m);}
    private void requirePersistentReadOrFallback(){if(dataSource==null&&!allowFallbackWrites)throw persistenceUnavailable("Operations 数据库不可用，已拒绝读取",null);}
    private String string(Map<String,Object>m,String k){return m==null||m.get(k)==null?null:String.valueOf(m.get(k));}
    private String upper(Object v){return v==null?"OPEN":String.valueOf(v).toUpperCase();}
    private Long number(Object v){if(v instanceof Number n)return n.longValue();if(v==null)return null;try{return Long.valueOf(String.valueOf(v));}catch(NumberFormatException e){return null;}}
    private void setLong(PreparedStatement s,int i,Long v)throws SQLException{if(v==null)s.setNull(i,java.sql.Types.BIGINT);else s.setLong(i,v);}
    private Timestamp timestamp(Object v){if(v==null)return null;try{return Timestamp.from(v instanceof Instant?(Instant)v:Instant.parse(String.valueOf(v)));}catch(Exception e){return null;}}
    private Instant instant(Timestamp v){return v==null?null:v.toInstant();}
    private String nowString(){return Instant.now().toString();}
    private String actor(AgentToolRequest r){return r!=null&&r.getActorId()!=null?r.getActorId():r!=null&&r.getOwnerUserId()!=null?String.valueOf(r.getOwnerUserId()):"FarMap Agent";}
    private String actor(Long id){return id==null?"current-user":String.valueOf(id);}
    private String json(Object v){try{return objectMapper.writeValueAsString(v==null?new ArrayList<>():v);}catch(JsonProcessingException e){return"[]";}}
    private OperationsWriteException persistenceUnavailable(String message,Throwable e){return e==null?new OperationsWriteException("OPERATIONS_PERSISTENCE_UNAVAILABLE",message):new OperationsWriteException("OPERATIONS_PERSISTENCE_UNAVAILABLE",message,e);}
    private void rollback(Connection c){try{c.rollback();}catch(SQLException ignored){}}
    private void resetAutoCommit(Connection c){try{c.setAutoCommit(true);}catch(SQLException ignored){}}
    private boolean isDuplicate(SQLException e){for(SQLException x=e;x!=null;x=x.getNextException())if("23000".equals(x.getSQLState())||x.getErrorCode()==1062)return true;return false;}
    private void validateProvenance(AgentToolRequest r){if(r==null)return;AgentContext c=r.getContext();String d=diagnosisId(c),rec=c==null?null:c.getSourceRecommendationId();if(r.getDiagnosisId()!=null&&!java.util.Objects.equals(r.getDiagnosisId(),d))throw ProductLoopRelationException.conflict("Task diagnosis provenance 与 AgentContext 不一致",d,rec);if(r.getRecommendationId()!=null&&!java.util.Objects.equals(r.getRecommendationId(),rec))throw ProductLoopRelationException.conflict("Task recommendation provenance 与 AgentContext 不一致",d,rec);if(r.getRecommendationId()!=null&&rec==null)throw ProductLoopRelationException.conflict("Task recommendation provenance 缺少已验证来源",d,r.getRecommendationId());}
    private String diagnosisId(AgentContext c){if(c==null)return null;if(c.getDiagnosisId()!=null&&!c.getDiagnosisId().isBlank())return c.getDiagnosisId();if(c.getDiagnosis()==null)return null;Object id=c.getDiagnosis().get("id");return id==null?null:String.valueOf(id);}
    private String serverDiagnosisId(AgentToolRequest r,AgentContext c){return r!=null&&r.getDiagnosisId()!=null&&!r.getDiagnosisId().isBlank()?r.getDiagnosisId():diagnosisId(c);}
    private String serverRecommendationId(AgentToolRequest r,AgentContext c){String v=c==null?null:c.getSourceRecommendationId();return r!=null&&r.getRecommendationId()!=null&&!r.getRecommendationId().isBlank()?r.getRecommendationId():v;}
}
