package com.mei.zhgy.service.operations;

import com.mei.zhgy.service.field.FarmField;
import com.mei.zhgy.service.field.FarmFieldService;
import com.mei.zhgy.service.field.FieldAccessException;
import com.mei.zhgy.service.maturity.FarmMaturityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Durable, human-confirmed bridge from a maturity analysis to one Operations task. */
@Service
public class OperationsPlanService {
    private static final String DRAFT_COLUMNS = "draft_id,owner_user_id,analysis_run_id,farm_id,field_id,field_name,title,detail,priority,scheduled_for,version,status,task_id,created_at,updated_at";
    private static final String ANALYSIS_ACTION_PREFIX = "operations-plan:";

    private final FarmMaturityService maturity;
    private final FarmFieldService fields;
    private final OperationsTaskService tasks;
    private final DataSource dataSource;
    private final boolean allowFallbackWrites;
    private final Map<String, Map<String, Object>> fallback = new ConcurrentHashMap<>();
    private volatile boolean schemaChecked;

    @Autowired
    public OperationsPlanService(FarmMaturityService maturity, FarmFieldService fields, OperationsTaskService tasks,
                                 @Autowired(required = false) DataSource dataSource) {
        this(maturity, fields, tasks, dataSource, false);
    }

    /** Explicit local-only constructor for focused tests. Production always requires MySQL. */
    OperationsPlanService(FarmMaturityService maturity, FarmFieldService fields, OperationsTaskService tasks) {
        this(maturity, fields, tasks, null, true);
    }

    private OperationsPlanService(FarmMaturityService maturity, FarmFieldService fields, OperationsTaskService tasks,
                                  DataSource dataSource, boolean allowFallbackWrites) {
        this.maturity = Objects.requireNonNull(maturity, "maturity");
        this.fields = Objects.requireNonNull(fields, "fields");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.dataSource = dataSource;
        this.allowFallbackWrites = allowFallbackWrites;
    }

    public List<Map<String, Object>> listDrafts(String analysisRunId, Long actorId) {
        String runId = required(analysisRunId, "analysisRunId");
        Long actor = requireActor(actorId);
        maturity.run(runId, actor);
        if (dataSource == null) {
            requireFallback();
            return fallback.values().stream()
                    .filter(draft -> sameOwner(draft, actor) && runId.equals(text(draft, "analysisRunId")))
                    .sorted(Comparator.comparing((Map<String, Object> draft) -> text(draft, "createdAt")).reversed())
                    .map(this::publicDraft)
                    .toList();
        }
        try (Connection connection = dataSource.getConnection()) {
            ensureSchema(connection);
            List<Map<String, Object>> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT " + DRAFT_COLUMNS + " FROM operations_plan_drafts WHERE owner_user_id=? AND analysis_run_id=? ORDER BY created_at DESC")) {
                statement.setLong(1, actor);
                statement.setString(2, runId);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) result.add(publicDraft(row(rows)));
                }
            }
            return result;
        } catch (Exception error) {
            throw persistenceUnavailable("无法读取农事计划草稿", error);
        }
    }

    public Map<String, Object> fromAnalysis(String analysisRunId, String fieldId, Long actorId) {
        String runId = required(analysisRunId, "analysisRunId");
        String requestedFieldId = required(fieldId, "fieldId");
        Long actor = requireActor(actorId);
        Map<String, Object> report = maturity.run(runId, actor);
        if (!"MATURITY".equals(upper(report.get("kind")))) {
            throw domain("PLAN_ANALYSIS_NOT_MATURITY", "仅成熟度分析报告可以生成农事计划草稿");
        }
        Integer reportFarmId = integer(report.get("farmId"));
        if (reportFarmId == null) throw domain("PLAN_ANALYSIS_INVALID", "分析报告缺少地块范围");
        FarmField field = fields.get(requestedFieldId, actor);
        if (!Objects.equals(reportFarmId, field.getFarmId())) {
            throw domain("PLAN_FIELD_MISMATCH", "该地块不属于分析报告所在农场");
        }

        boolean synthetic = isSynthetic(field);
        boolean reportDemo = "DEMO".equals(upper(report.get("topology")));
        if (synthetic && !reportDemo) {
            throw domain("PLAN_FIELD_NOT_ELIGIBLE", "本地合成地块只允许从演示分析报告生成草稿");
        }

        ParcelSelection selection = eligibleParcel(report, requestedFieldId);
        if (selection == null) {
            throw domain("PLAN_FIELD_NOT_ELIGIBLE", "该地块没有可用于现场复核的成功成熟观察；离线、未知或未绑定画面不会生成建议");
        }
        if (synthetic && !selection.demo()) {
            throw domain("PLAN_FIELD_NOT_ELIGIBLE", "本地合成地块缺少演示监测拓扑，不能生成生产建议");
        }

        boolean demo = synthetic || selection.demo();
        String fieldName = field.getName() == null || field.getName().isBlank() ? requestedFieldId : field.getName().trim();
        String stageLabel = "OVER_RIPE".equals(selection.stage()) ? "过熟" : "成熟";
        String title = (demo ? "【演示数据】" : "") + "成熟度实地复核 · " + fieldName;
        String detail = (demo ? "【演示数据 · 仅供本地演示，不作为生产采收建议】" : "")
                + "成熟度分析报告显示地块「" + fieldName + "」存在" + stageLabel + "阶段的成功画面，但结论仅代表镜头可见范围。"
                + "请到地块现场复核果实成熟度、采收标准以及天气和病虫害条件；人工确认前不会自动执行采收，也不能替代实地判断。";
        Instant now = Instant.now();
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("draftId", "plan-draft-" + UUID.randomUUID());
        draft.put("ownerUserId", actor);
        draft.put("analysisRunId", runId);
        draft.put("farmId", reportFarmId);
        draft.put("fieldId", requestedFieldId);
        draft.put("fieldName", fieldName);
        draft.put("title", title);
        draft.put("detail", detail);
        draft.put("priority", "HIGH");
        draft.put("scheduledFor", null);
        draft.put("version", 0L);
        draft.put("status", "DRAFT");
        draft.put("taskId", null);
        draft.put("createdAt", now.toString());
        draft.put("updatedAt", now.toString());
        return insertIdempotently(draft);
    }

    public Map<String, Object> getDraft(String draftId, Long actorId) {
        String id = required(draftId, "draftId");
        Long actor = requireActor(actorId);
        if (dataSource == null) {
            requireFallback();
            Map<String, Object> draft = fallback.get(id);
            if (draft == null) throw notFound(id);
            authorize(draft, actor);
            maturity.run(text(draft, "analysisRunId"), actor);
            return publicDraft(draft);
        }
        try (Connection connection = dataSource.getConnection()) {
            ensureSchema(connection);
            Map<String, Object> draft = findById(connection, id, false);
            if (draft == null) throw notFound(id);
            authorize(draft, actor);
            maturity.run(text(draft, "analysisRunId"), actor);
            return publicDraft(draft);
        } catch (FieldAccessException error) {
            throw error;
        } catch (OperationsWriteException error) {
            throw error;
        } catch (Exception error) {
            throw persistenceUnavailable("无法读取农事计划草稿", error);
        }
    }

    public Map<String, Object> save(String draftId, Long actorId, DraftEdit edit) {
        String id = required(draftId, "draftId");
        Long actor = requireActor(actorId);
        DraftFields requested = normalize(edit);
        if (dataSource == null) {
            requireFallback();
            synchronized (this) {
                Map<String, Object> draft = fallback.get(id);
                if (draft == null) throw notFound(id);
                authorize(draft, actor);
                maturity.run(text(draft, "analysisRunId"), actor);
                requireDraftStatus(draft);
                requireVersion(draft, edit.version());
                Map<String, Object> committed = verifiedPlanTask(tasks.findTaskByActionId(actionId(id)), draft, actor);
                if (committed != null) return publicDraft(recoverConfirmed(draft, committed));
                updateFields(draft, requested, false, null);
                return publicDraft(draft);
            }
        }
        try (Connection connection = dataSource.getConnection()) {
            ensureSchema(connection);
            connection.setAutoCommit(false);
            try {
                Map<String, Object> draft = findById(connection, id, true);
                if (draft == null) throw notFound(id);
                authorize(draft, actor);
                maturity.run(text(draft, "analysisRunId"), actor);
                requireDraftStatus(draft);
                requireVersion(draft, edit.version());
                Map<String, Object> committed = verifiedPlanTask(tasks.findTaskByActionId(actionId(id)), draft, actor);
                if (committed != null) {
                    Map<String, Object> confirmed = recoverConfirmed(draft, committed);
                    updateConfirmed(connection, confirmed, actor);
                    connection.commit();
                    return publicDraft(confirmed);
                }
                updateDraft(connection, id, requested, edit.version(), actor);
                connection.commit();
                return publicDraft(findById(connection, id, false));
            } catch (Exception error) {
                rollback(connection);
                throw error;
            } finally {
                resetAutoCommit(connection);
            }
        } catch (FieldAccessException error) {
            throw error;
        } catch (OperationsWriteException error) {
            throw error;
        } catch (Exception error) {
            throw persistenceUnavailable("农事计划草稿未保存", error);
        }
    }

    public Map<String, Object> confirm(String draftId, Long actorId, DraftEdit edit) {
        String id = required(draftId, "draftId");
        Long actor = requireActor(actorId);
        DraftFields requested = normalize(edit);
        if (dataSource == null) {
            requireFallback();
            synchronized (this) {
                Map<String, Object> draft = fallback.get(id);
                if (draft == null) throw notFound(id);
                authorize(draft, actor);
                maturity.run(text(draft, "analysisRunId"), actor);
                if ("CONFIRMED".equals(upper(draft.get("status")))) {
                    if (!sameFields(draft, requested)) throw alreadyConfirmed(id);
                    if (text(draft, "taskId") == null) {
                        Map<String, Object> task = createOrRecoverTask(draft, fieldsOf(draft), actor);
                        recoverConfirmed(draft, task);
                    }
                    return publicDraft(draft);
                }
                requireVersion(draft, edit.version());
                Map<String, Object> task = createOrRecoverTask(draft, requested, actor);
                recoverConfirmed(draft, task);
                return publicDraft(draft);
            }
        }
        try (Connection connection = dataSource.getConnection()) {
            ensureSchema(connection);
            connection.setAutoCommit(false);
            try {
                Map<String, Object> draft = findById(connection, id, true);
                if (draft == null) throw notFound(id);
                authorize(draft, actor);
                maturity.run(text(draft, "analysisRunId"), actor);
                if ("CONFIRMED".equals(upper(draft.get("status")))) {
                    if (!sameFields(draft, requested)) throw alreadyConfirmed(id);
                    if (text(draft, "taskId") == null) {
                        Map<String, Object> task = createOrRecoverTask(draft, fieldsOf(draft), actor);
                        Map<String, Object> confirmed = recoverConfirmed(draft, task);
                        updateConfirmed(connection, confirmed, actor);
                        connection.commit();
                        return publicDraft(confirmed);
                    }
                    return publicDraft(draft);
                }
                requireVersion(draft, edit.version());
                Map<String, Object> task = createOrRecoverTask(draft, requested, actor);
                Map<String, Object> confirmed = recoverConfirmed(draft, task);
                updateConfirmed(connection, confirmed, actor);
                connection.commit();
                return publicDraft(confirmed);
            } catch (Exception error) {
                rollback(connection);
                throw error;
            } finally {
                resetAutoCommit(connection);
            }
        } catch (FieldAccessException error) {
            throw error;
        } catch (OperationsWriteException error) {
            throw error;
        } catch (Exception error) {
            throw persistenceUnavailable("农事计划草稿确认事务未提交", error);
        }
    }

    private Map<String, Object> insertIdempotently(Map<String, Object> draft) {
        Long actor = number(draft.get("ownerUserId"));
        String runId = text(draft, "analysisRunId");
        String fieldId = text(draft, "fieldId");
        if (dataSource == null) {
            requireFallback();
            synchronized (this) {
                for (Map<String, Object> existing : fallback.values()) {
                    if (sameOwner(existing, actor) && runId.equals(text(existing, "analysisRunId")) && fieldId.equals(text(existing, "fieldId"))) {
                        return publicDraft(existing);
                    }
                }
                fallback.put(text(draft, "draftId"), draft);
                return publicDraft(draft);
            }
        }
        try (Connection connection = dataSource.getConnection()) {
            ensureSchema(connection);
            Map<String, Object> existing = findUnique(connection, actor, runId, fieldId);
            if (existing != null) return publicDraft(existing);
            connection.setAutoCommit(false);
            try {
                insertDraft(connection, draft);
                connection.commit();
                return publicDraft(draft);
            } catch (SQLException error) {
                rollback(connection);
                if (isDuplicate(error)) {
                    Map<String, Object> raced = findUniqueFresh(actor, runId, fieldId);
                    if (raced != null) return publicDraft(raced);
                }
                throw error;
            } catch (Exception error) {
                rollback(connection);
                throw error;
            } finally {
                resetAutoCommit(connection);
            }
        } catch (OperationsWriteException error) {
            throw error;
        } catch (Exception error) {
            throw persistenceUnavailable("农事计划草稿未持久化", error);
        }
    }

    private Map<String, Object> createOrRecoverTask(Map<String, Object> draft, DraftFields fields, Long actor) {
        String actionId = actionId(text(draft, "draftId"));
        Map<String, Object> task = tasks.createPlanTask(new OperationsTaskService.PlanTaskCommand(actionId, number(draft.get("ownerUserId")), actor,
                text(draft, "analysisRunId"), text(draft, "fieldId"), text(draft, "fieldName"), fields.title(), fields.detail(),
                fields.priority(), fields.scheduledFor()));
        Map<String, Object> verified = verifiedPlanTask(task, draft, actor);
        if (verified == null) throw domain("PLAN_TASK_PERSISTENCE_FAILED", "Operations 任务未返回可恢复结果");
        return verified;
    }

    private Map<String, Object> verifiedPlanTask(Map<String, Object> task, Map<String, Object> draft, Long actor) {
        if (task == null) return null;
        Object owner = task.get("ownerUserId");
        if (owner == null || !String.valueOf(actor).equals(String.valueOf(owner))) {
            throw new OperationsWriteException("FORBIDDEN", "无权恢复该农事计划任务");
        }
        if (!Objects.equals(text(draft, "analysisRunId"), text(task, "analysisRunId"))) {
            throw domain("PLAN_TASK_CONFLICT", "稳定 actionId 已关联其他分析任务");
        }
        return task;
    }

    private Map<String, Object> recoverConfirmed(Map<String, Object> draft, Map<String, Object> task) {
        DraftFields approved = new DraftFields(text(task, "title"), text(task, "goal"), text(task, "priority"), text(task, "scheduledFor"));
        if (approved.title() == null || approved.detail() == null || approved.priority() == null) {
            throw domain("PLAN_TASK_CONFLICT", "已提交任务缺少批准字段，拒绝伪造草稿确认");
        }
        draft.put("title", approved.title());
        draft.put("detail", approved.detail());
        draft.put("priority", approved.priority());
        draft.put("scheduledFor", approved.scheduledFor());
        draft.put("status", "CONFIRMED");
        draft.put("taskId", text(task, "taskId"));
        draft.put("version", numberValue(draft.get("version")) + 1L);
        String now = Instant.now().toString();
        draft.put("updatedAt", now);
        draft.put("confirmedAt", now);
        return draft;
    }

    private void updateDraft(Connection connection, String draftId, DraftFields fields, Long expectedVersion, Long actor) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE operations_plan_drafts SET title=?,detail=?,priority=?,scheduled_for=?,version=version+1,updated_at=? WHERE draft_id=? AND owner_user_id=? AND status='DRAFT' AND version=?")) {
            statement.setString(1, fields.title());
            statement.setString(2, fields.detail());
            statement.setString(3, fields.priority());
            statement.setString(4, fields.scheduledFor());
            statement.setString(5, Instant.now().toString());
            statement.setString(6, draftId);
            statement.setLong(7, actor);
            statement.setLong(8, expectedVersion);
            if (statement.executeUpdate() != 1) throw versionConflict(draftId);
        }
    }

    private void updateConfirmed(Connection connection, Map<String, Object> draft, Long actor) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE operations_plan_drafts SET title=?,detail=?,priority=?,scheduled_for=?,status='CONFIRMED',task_id=?,version=version+1,updated_at=?,confirmed_at=? WHERE draft_id=? AND owner_user_id=? AND status IN ('DRAFT','CONFIRMED')")) {
            statement.setString(1, text(draft, "title"));
            statement.setString(2, text(draft, "detail"));
            statement.setString(3, text(draft, "priority"));
            statement.setString(4, text(draft, "scheduledFor"));
            statement.setString(5, text(draft, "taskId"));
            statement.setString(6, text(draft, "updatedAt"));
            statement.setString(7, text(draft, "confirmedAt"));
            statement.setString(8, text(draft, "draftId"));
            statement.setLong(9, actor);
            if (statement.executeUpdate() != 1) throw versionConflict(text(draft, "draftId"));
        }
    }

    private void insertDraft(Connection connection, Map<String, Object> draft) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO operations_plan_drafts (draft_id,owner_user_id,analysis_run_id,farm_id,field_id,field_name,title,detail,priority,scheduled_for,version,status,task_id,created_at,updated_at,confirmed_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            statement.setString(1, text(draft, "draftId"));
            statement.setLong(2, number(draft.get("ownerUserId")));
            statement.setString(3, text(draft, "analysisRunId"));
            statement.setInt(4, integer(draft.get("farmId")));
            statement.setString(5, text(draft, "fieldId"));
            statement.setString(6, text(draft, "fieldName"));
            statement.setString(7, text(draft, "title"));
            statement.setString(8, text(draft, "detail"));
            statement.setString(9, text(draft, "priority"));
            statement.setString(10, text(draft, "scheduledFor"));
            statement.setLong(11, numberValue(draft.get("version")));
            statement.setString(12, text(draft, "status"));
            setNullableString(statement, 13, text(draft, "taskId"));
            statement.setString(14, text(draft, "createdAt"));
            statement.setString(15, text(draft, "updatedAt"));
            statement.setString(16, text(draft, "confirmedAt"));
            statement.executeUpdate();
        }
    }

    private Map<String, Object> findById(Connection connection, String draftId, boolean lock) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + DRAFT_COLUMNS + " FROM operations_plan_drafts WHERE draft_id=?" + (lock ? " FOR UPDATE" : ""))) {
            statement.setString(1, draftId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? row(rows) : null;
            }
        }
    }

    private Map<String, Object> findUnique(Connection connection, Long owner, String runId, String fieldId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + DRAFT_COLUMNS + " FROM operations_plan_drafts WHERE owner_user_id=? AND analysis_run_id=? AND field_id=?")) {
            statement.setLong(1, owner);
            statement.setString(2, runId);
            statement.setString(3, fieldId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? row(rows) : null;
            }
        }
    }

    private Map<String, Object> findUniqueFresh(Long owner, String runId, String fieldId) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ensureSchema(connection);
            return findUnique(connection, owner, runId, fieldId);
        }
    }

    private Map<String, Object> row(ResultSet row) throws SQLException {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("draftId", row.getString("draft_id"));
        draft.put("ownerUserId", row.getObject("owner_user_id"));
        draft.put("analysisRunId", row.getString("analysis_run_id"));
        draft.put("farmId", row.getInt("farm_id"));
        draft.put("fieldId", row.getString("field_id"));
        draft.put("fieldName", row.getString("field_name"));
        draft.put("title", row.getString("title"));
        draft.put("detail", row.getString("detail"));
        draft.put("priority", row.getString("priority"));
        draft.put("scheduledFor", row.getString("scheduled_for"));
        draft.put("version", row.getLong("version"));
        draft.put("status", row.getString("status"));
        draft.put("taskId", row.getString("task_id"));
        draft.put("createdAt", row.getString("created_at"));
        draft.put("updatedAt", row.getString("updated_at"));
        return draft;
    }

    private Map<String, Object> publicDraft(Map<String, Object> draft) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("draftId", text(draft, "draftId"));
        result.put("analysisRunId", text(draft, "analysisRunId"));
        result.put("farmId", integer(draft.get("farmId")));
        result.put("fieldId", text(draft, "fieldId"));
        result.put("fieldName", text(draft, "fieldName"));
        result.put("title", text(draft, "title"));
        result.put("detail", text(draft, "detail"));
        result.put("priority", text(draft, "priority"));
        result.put("scheduledFor", text(draft, "scheduledFor"));
        result.put("version", numberValue(draft.get("version")));
        result.put("status", text(draft, "status"));
        if ("CONFIRMED".equals(upper(draft.get("status"))) && text(draft, "taskId") != null) {
            result.put("taskId", text(draft, "taskId"));
        }
        return result;
    }

    private DraftFields normalize(DraftEdit edit) {
        if (edit == null) throw domain("PLAN_DRAFT_VALIDATION", "请求体不能为空");
        if (edit.version() == null || edit.version() < 0) throw domain("PLAN_DRAFT_VALIDATION", "version 必须是非负整数");
        String title = required(edit.title(), "title").trim();
        if (title.length() > 255) throw domain("PLAN_DRAFT_VALIDATION", "title 不能超过 255 个字符");
        String detail = required(edit.detail(), "detail").trim();
        if (detail.length() > 8000) throw domain("PLAN_DRAFT_VALIDATION", "detail 不能超过 8000 个字符");
        String priority = required(edit.priority(), "priority").trim().toUpperCase(Locale.ROOT);
        if (!List.of("HIGH", "MEDIUM", "LOW").contains(priority)) throw domain("PLAN_DRAFT_VALIDATION", "priority 仅支持 HIGH、MEDIUM、LOW");
        return new DraftFields(title, detail, priority, normalizeDate(edit.scheduledFor()));
    }

    private String normalizeDate(String value) {
        if (value == null || value.isBlank()) return null;
        String candidate = value.trim();
        try {
            return LocalDate.parse(candidate).toString();
        } catch (Exception localDateError) {
            try {
                return OffsetDateTime.parse(candidate).toLocalDate().toString();
            } catch (Exception offsetDateError) {
                throw domain("PLAN_DRAFT_VALIDATION", "scheduledFor 必须是 ISO 日期，例如 2026-11-25");
            }
        }
    }

    private DraftFields fieldsOf(Map<String, Object> source) {
        return new DraftFields(text(source, "title"), text(source, "detail"), text(source, "priority"), text(source, "scheduledFor"));
    }

    private boolean sameFields(Map<String, Object> draft, DraftFields fields) {
        return Objects.equals(text(draft, "title"), fields.title())
                && Objects.equals(text(draft, "detail"), fields.detail())
                && Objects.equals(text(draft, "priority"), fields.priority())
                && Objects.equals(text(draft, "scheduledFor"), fields.scheduledFor());
    }

    private void updateFields(Map<String, Object> draft, DraftFields fields, boolean confirmed, String taskId) {
        draft.put("title", fields.title());
        draft.put("detail", fields.detail());
        draft.put("priority", fields.priority());
        draft.put("scheduledFor", fields.scheduledFor());
        draft.put("version", numberValue(draft.get("version")) + 1L);
        draft.put("updatedAt", Instant.now().toString());
        draft.put("status", confirmed ? "CONFIRMED" : "DRAFT");
        draft.put("taskId", confirmed ? taskId : null);
    }

    private ParcelSelection eligibleParcel(Map<String, Object> report, String fieldId) {
        Object parcelsValue = report.get("parcels");
        if (!(parcelsValue instanceof List<?> parcels)) return null;
        for (Object parcelValue : parcels) {
            if (!(parcelValue instanceof Map<?, ?> rawParcel)) continue;
            Map<?, ?> parcel = rawParcel;
            if (!fieldId.equals(asText(parcel.get("fieldId")))) continue;
            String scope = upper(parcel.get("scope"));
            if ("UNASSIGNED_CAMERA".equals(scope) || "IMAGE".equals(scope)) continue;
            boolean demo = "DEMO_MONITORING_REGION".equals(scope)
                    || "DEMO".equals(upper(parcel.get("topology")))
                    || "CURATED_PRESENTATION_TOPOLOGY".equals(upper(parcel.get("bindingSource")));
            String ripeStage = null;
            Object observationsValue = parcel.get("observations");
            if (observationsValue instanceof List<?> observations) {
                for (Object observationValue : observations) {
                    if (!(observationValue instanceof Map<?, ?> observation)) continue;
                    if (!"SUCCESS".equals(upper(observation.get("status")))) continue;
                    String source = upper(observation.get("source"));
                    if ("OFFLINE".equals(source) || "UNAVAILABLE".equals(source)) continue;
                    String quality = upper(observation.get("quality"));
                    if ("UNKNOWN".equals(quality) || "INSUFFICIENT".equals(quality)) continue;
                    String stage = upper(observation.get("stage"));
                    if ("OVER_RIPE".equals(stage)) { ripeStage = stage; break; }
                    if ("RIPE".equals(stage)) ripeStage = stage;
                }
            }
            if (ripeStage != null) return new ParcelSelection(ripeStage, demo);
        }
        return null;
    }

    private Integer integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value == null) return null;
        try { return Integer.valueOf(String.valueOf(value)); } catch (NumberFormatException error) { return null; }
    }

    private Long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        try { return Long.valueOf(String.valueOf(value)); } catch (NumberFormatException error) { return null; }
    }

    private long numberValue(Object value) {
        Long number = number(value);
        return number == null ? 0L : number;
    }

    private String text(Map<String, Object> map, String key) {
        return map == null ? null : asText(map.get(key));
    }

    private String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String upper(Object value) {
        return value == null ? null : String.valueOf(value).toUpperCase(Locale.ROOT);
    }

    private boolean sameOwner(Map<String, Object> draft, Long actor) {
        Long owner = number(draft.get("ownerUserId"));
        return owner != null && owner.equals(actor);
    }

    private void authorize(Map<String, Object> draft, Long actor) {
        if (!sameOwner(draft, actor)) throw new OperationsWriteException("FORBIDDEN", "无权操作该农事计划草稿");
    }

    private void requireDraftStatus(Map<String, Object> draft) {
        if ("CONFIRMED".equals(upper(draft.get("status")))) throw alreadyConfirmed(text(draft, "draftId"));
    }

    private void requireVersion(Map<String, Object> draft, Long version) {
        if (version == null || version != numberValue(draft.get("version"))) throw versionConflict(text(draft, "draftId"));
    }

    private boolean isSynthetic(FarmField field) {
        return field.getSourceMode() != null && field.getSourceMode().toUpperCase(Locale.ROOT).startsWith("SYNTHETIC");
    }

    private String required(String value, String name) {
        if (value == null || value.isBlank()) throw new OperationsWriteException("PLAN_DRAFT_VALIDATION", name + " 不能为空");
        return value.trim();
    }

    private Long requireActor(Long actorId) {
        if (actorId == null) throw new OperationsWriteException("FORBIDDEN", "用户未登录");
        return actorId;
    }

    private String actionId(String draftId) {
        return ANALYSIS_ACTION_PREFIX + draftId;
    }

    private OperationsWriteException domain(String code, String message) {
        return new OperationsWriteException(code, message);
    }

    private OperationsWriteException notFound(String draftId) {
        return new OperationsWriteException("PLAN_DRAFT_NOT_FOUND", "农事计划草稿不存在: " + draftId);
    }

    private OperationsWriteException versionConflict(String draftId) {
        return new OperationsWriteException("PLAN_DRAFT_VERSION_CONFLICT", "农事计划草稿已被更新，请刷新后重试: " + draftId);
    }

    private OperationsWriteException alreadyConfirmed(String draftId) {
        return new OperationsWriteException("PLAN_DRAFT_ALREADY_CONFIRMED", "农事计划草稿已人工确认，不能再次编辑: " + draftId);
    }

    private OperationsWriteException persistenceUnavailable(String message, Throwable error) {
        return new OperationsWriteException("OPERATIONS_PERSISTENCE_UNAVAILABLE", message, error);
    }

    private void requireFallback() {
        if (!allowFallbackWrites) throw persistenceUnavailable("Operations 数据库不可用，已拒绝读取草稿", null);
    }

    private void ensureSchema(Connection connection) throws SQLException {
        if (schemaChecked) return;
        synchronized (this) {
            if (schemaChecked) return;
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS operations_plan_drafts ("
                        + "draft_id VARCHAR(80) PRIMARY KEY,"
                        + "owner_user_id BIGINT NOT NULL,"
                        + "analysis_run_id VARCHAR(64) NOT NULL,"
                        + "farm_id INT NOT NULL,"
                        + "field_id VARCHAR(100) NOT NULL,"
                        + "field_name VARCHAR(255) NOT NULL,"
                        + "title VARCHAR(255) NOT NULL,"
                        + "detail TEXT NOT NULL,"
                        + "priority VARCHAR(32) NOT NULL,"
                        + "scheduled_for VARCHAR(40) NULL,"
                        + "version BIGINT NOT NULL DEFAULT 0,"
                        + "status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',"
                        + "task_id VARCHAR(80) NULL,"
                        + "created_at VARCHAR(40) NOT NULL,"
                        + "updated_at VARCHAR(40) NOT NULL,"
                        + "confirmed_at VARCHAR(40) NULL,"
                        + "UNIQUE KEY uk_plan_draft_owner_report_field(owner_user_id,analysis_run_id,field_id),"
                        + "KEY idx_plan_draft_owner_report(owner_user_id,analysis_run_id))");
                schemaChecked = true;
            }
        }
    }

    private void setNullableString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) statement.setNull(index, java.sql.Types.VARCHAR);
        else statement.setString(index, value);
    }

    private boolean isDuplicate(SQLException error) {
        for (SQLException current = error; current != null; current = current.getNextException()) {
            if ("23000".equals(current.getSQLState()) || current.getErrorCode() == 1062) return true;
        }
        return false;
    }

    private void rollback(Connection connection) {
        try { connection.rollback(); } catch (SQLException ignored) { }
    }

    private void resetAutoCommit(Connection connection) {
        try { connection.setAutoCommit(true); } catch (SQLException ignored) { }
    }

    public record DraftEdit(Long version, String title, String detail, String priority, String scheduledFor) {}

    private record DraftFields(String title, String detail, String priority, String scheduledFor) {}

    private record ParcelSelection(String stage, boolean demo) {}
}
