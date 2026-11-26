package com.mei.zhgy.service.operations;

import com.mei.zhgy.service.field.FarmField;
import com.mei.zhgy.service.field.FarmFieldService;
import com.mei.zhgy.service.field.FieldAccessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Farm-scoped read models over the canonical Operations task tables. */
@Service
public class OperationsTaskViewService {
    public static final int RESPONSE_LIMIT = 200;
    public static final ZoneId OPERATIONS_ZONE = ZoneId.of("Asia/Shanghai");

    private final DataSource dataSource;
    private final OperationsTaskService tasks;
    private final FarmFieldService fields;
    private final boolean allowFallbackReads;

    @Autowired
    public OperationsTaskViewService(@Autowired(required = false) DataSource dataSource,
                                     OperationsTaskService tasks, FarmFieldService fields) {
        this(dataSource, tasks, fields, false);
    }

    /** Explicit local-only constructor for focused tests with fallback Operations tasks. */
    public OperationsTaskViewService(OperationsTaskService tasks, FarmFieldService fields) {
        this(null, tasks, fields, true);
    }

    private OperationsTaskViewService(DataSource dataSource, OperationsTaskService tasks, FarmFieldService fields,
                                      boolean allowFallbackReads) {
        this.dataSource = dataSource;
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.fields = Objects.requireNonNull(fields, "fields");
        this.allowFallbackReads = allowFallbackReads;
    }

    public Map<String, Object> view(Integer farmId, String fieldId, String from, String to, String view, Long actorId) {
        return view(farmId, fieldId, from, to, view, null, actorId);
    }

    public Map<String, Object> view(Integer farmId, String fieldId, String from, String to, String view, String filter, Long actorId) {
        Long actor = requireActor(actorId);
        if (dataSource == null && !allowFallbackReads) {
            throw new OperationsWriteException("OPERATIONS_PERSISTENCE_UNAVAILABLE", "Operations 任务查询不可用");
        }
        if (farmId == null) throw new FieldAccessException(400, "FARM_ID_REQUIRED", "farmId is required");
        String selectedView = normalizeView(view);
        if (filter != null && !List.of("active", "pending", "in-progress", "completed").contains(filter)) {
            throw new FieldAccessException(400, "TASK_FILTER_INVALID", "unsupported task status filter");
        }
        String requestedFieldId = text(fieldId);
        LocalDate fromDate = parseDate(from, "from");
        LocalDate toDate = parseDate(to, "to");
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            throw new FieldAccessException(400, "DATE_RANGE_INVALID", "from must not be after to");
        }

        fields.requireFarm(farmId, actor);
        Map<String, String> fieldNames = new LinkedHashMap<>();
        List<FarmField> farmFields = fields.list(farmId, actor);
        if (farmFields != null) {
            for (FarmField field : farmFields) {
                if (field != null && field.getFieldId() != null) {
                    fieldNames.put(field.getFieldId(), fieldName(field));
                }
            }
        }
        if (requestedFieldId != null) {
            FarmField selected = fields.get(requestedFieldId, actor);
            if (!Objects.equals(farmId, selected.getFarmId())) {
                throw new FieldAccessException(403, "FARM_ACCESS_DENIED", "field does not belong to the requested farm");
            }
            fieldNames.put(selected.getFieldId(), fieldName(selected));
        }

        boolean dateBounded = fromDate != null || toDate != null;
        List<Map<String, Object>> matched = new ArrayList<>();
        for (Map<String, Object> task : tasks.listTasks(actor)) {
            String taskFieldId = text(task.get("fieldId"));
            if (taskFieldId == null || !fieldNames.containsKey(taskFieldId)) continue;
            if (requestedFieldId != null && !requestedFieldId.equals(taskFieldId)) continue;
            if (!matchesView(task, selectedView, fromDate, toDate, dateBounded)) continue;
            if (filter != null && !matchesStatus(upper(task.get("status")), filter)) continue;
            matched.add(task);
        }

        matched.sort(comparator(selectedView));
        boolean truncated = matched.size() > RESPONSE_LIMIT;
        List<Map<String, Object>> items = new ArrayList<>();
        int limit = Math.min(matched.size(), RESPONSE_LIMIT);
        for (int index = 0; index < limit; index++) {
            Map<String, Object> item = new LinkedHashMap<>(matched.get(index));
            item.put("fieldName", fieldNames.get(text(item.get("fieldId"))));
            items.add(item);
        }
        if ("records".equals(selectedView)) attachOutcomes(items, actor);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("counts", counts(matched));
        body.put("truncated", truncated);
        return body;
    }

    private boolean matchesStatus(String status, String filter) {
        return switch (filter) {
            case "active" -> List.of("OPEN", "ASSIGNED", "IN_PROGRESS").contains(status);
            case "pending" -> List.of("OPEN", "ASSIGNED").contains(status);
            case "in-progress" -> "IN_PROGRESS".equals(status);
            case "completed" -> "COMPLETED".equals(status);
            default -> false;
        };
    }

    private boolean matchesView(Map<String, Object> task, String view, LocalDate from, LocalDate to, boolean bounded) {
        if ("records".equals(view)) {
            if (!"COMPLETED".equals(upper(task.get("status")))) return false;
            LocalDate completed = dateOfInstant(task.get("completedAt"));
            return !bounded || inRange(completed, from, to);
        }
        if ("calendar".equals(view)) {
            if (!bounded) return true;
            for (LocalDate date : calendarDates(task)) {
                if (inRange(date, from, to)) return true;
            }
            return false;
        }
        LocalDate effective = effectiveDate(task);
        return !bounded || inRange(effective, from, to);
    }

    private Comparator<Map<String, Object>> comparator(String view) {
        if ("calendar".equals(view)) {
            return (left, right) -> compareDates(calendarSortDate(left), calendarSortDate(right), false, left, right);
        }
        if ("records".equals(view)) {
            return (left, right) -> compareDates(dateOfInstant(left.get("completedAt")),
                    dateOfInstant(right.get("completedAt")), true, left, right);
        }
        return (left, right) -> compareDates(effectiveDate(left), effectiveDate(right), true, left, right);
    }

    private int compareDates(LocalDate leftDate, LocalDate rightDate, boolean descending,
                             Map<String, Object> leftTask, Map<String, Object> rightTask) {
        if (leftDate == null && rightDate == null) return taskId(rightTask).compareTo(taskId(leftTask));
        if (leftDate == null) return 1;
        if (rightDate == null) return -1;
        int comparison = leftDate.compareTo(rightDate);
        if (comparison != 0) return descending ? -comparison : comparison;
        return descending ? taskId(rightTask).compareTo(taskId(leftTask)) : taskId(leftTask).compareTo(taskId(rightTask));
    }

    private Map<String, Integer> counts(List<Map<String, Object>> matched) {
        int pending = 0;
        int inProgress = 0;
        int completed = 0;
        for (Map<String, Object> task : matched) {
            String status = upper(task.get("status"));
            if ("OPEN".equals(status) || "ASSIGNED".equals(status)) pending++;
            else if ("IN_PROGRESS".equals(status)) inProgress++;
            else if ("COMPLETED".equals(status)) completed++;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("total", matched.size());
        counts.put("pending", pending);
        counts.put("inProgress", inProgress);
        counts.put("completed", completed);
        return counts;
    }

    private void attachOutcomes(List<Map<String, Object>> items, Long actorId) {
        if (items.isEmpty()) return;
        Map<String, Map<String, Object>> outcomes = dataSource == null
                ? fallbackOutcomes(items, actorId) : databaseOutcomes(items);
        for (Map<String, Object> item : items) {
            Map<String, Object> outcome = outcomes.get(text(item.get("taskId")));
            if (outcome != null) {
                outcome.put("diagnosisId", item.get("diagnosisId"));
                outcome.put("recommendationId", item.get("recommendationId"));
                outcome.put("agentRunId", item.get("agentRunId"));
                item.put("outcome", outcome);
            }
        }
    }

    private Map<String, Map<String, Object>> fallbackOutcomes(List<Map<String, Object>> items, Long actorId) {
        Map<String, Map<String, Object>> outcomes = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            String taskId = text(item.get("taskId"));
            try {
                outcomes.put(taskId, tasks.getOutcome(taskId, actorId));
            } catch (OperationsWriteException missing) {
                if (!"TASK_NOT_FOUND".equals(missing.getCode())) throw missing;
            }
        }
        return outcomes;
    }

    private Map<String, Map<String, Object>> databaseOutcomes(List<Map<String, Object>> items) {
        Map<String, Map<String, Object>> outcomes = new LinkedHashMap<>();
        String placeholders = String.join(",", Collections.nCopies(items.size(), "?"));
        String sql = "SELECT outcome_id,task_id,result_type,summary,execution_evidence,notes,actor_id,created_at,updated_at "
                + "FROM agent_operation_outcomes WHERE task_id IN (" + placeholders + ")";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (Map<String, Object> item : items) statement.setString(index++, text(item.get("taskId")));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Map<String, Object> outcome = new LinkedHashMap<>();
                    outcome.put("outcomeId", result.getString("outcome_id"));
                    outcome.put("taskId", result.getString("task_id"));
                    outcome.put("resultType", result.getString("result_type"));
                    outcome.put("summary", result.getString("summary"));
                    outcome.put("executionEvidence", result.getString("execution_evidence"));
                    outcome.put("notes", result.getString("notes"));
                    outcome.put("actor", result.getString("actor_id"));
                    outcome.put("createdAt", instant(result.getTimestamp("created_at")));
                    outcome.put("updatedAt", instant(result.getTimestamp("updated_at")));
                    outcomes.put(String.valueOf(outcome.get("taskId")), outcome);
                }
            }
            return outcomes;
        } catch (Exception error) {
            throw new OperationsWriteException("OPERATIONS_PERSISTENCE_UNAVAILABLE", "Operations Outcome 查询不可用", error);
        }
    }

    private List<LocalDate> calendarDates(Map<String, Object> task) {
        List<LocalDate> dates = new ArrayList<>();
        addDate(dates, scheduledDate(task.get("scheduledFor")));
        addDate(dates, dateOfInstant(task.get("startedAt")));
        addDate(dates, dateOfInstant(task.get("completedAt")));
        return dates;
    }

    private LocalDate calendarSortDate(Map<String, Object> task) {
        return calendarDates(task).stream().min(LocalDate::compareTo).orElse(null);
    }

    private LocalDate effectiveDate(Map<String, Object> task) {
        LocalDate scheduled = scheduledDate(task.get("scheduledFor"));
        return scheduled != null ? scheduled : dateOfInstant(task.get("createdAt"));
    }

    private boolean inRange(LocalDate date, LocalDate from, LocalDate to) {
        if (date == null) return false;
        return (from == null || !date.isBefore(from)) && (to == null || !date.isAfter(to));
    }

    private LocalDate scheduledDate(Object value) {
        if (value instanceof LocalDate date) return date;
        String raw = text(value);
        if (raw == null) return null;
        try { return LocalDate.parse(raw); }
        catch (Exception ignored) { return dateOfInstant(raw); }
    }

    private LocalDate dateOfInstant(Object value) {
        Instant valueInstant = toInstant(value);
        return valueInstant == null ? null : valueInstant.atZone(OPERATIONS_ZONE).toLocalDate();
    }

    private Instant toInstant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant instant) return instant;
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof java.util.Date date) return date.toInstant();
        if (value instanceof LocalDate date) return date.atStartOfDay(OPERATIONS_ZONE).toInstant();
        String raw = String.valueOf(value).trim();
        if (raw.isBlank()) return null;
        try { return Instant.parse(raw); } catch (Exception ignored) { }
        try { return OffsetDateTime.parse(raw).toInstant(); } catch (Exception ignored) { }
        try { return LocalDateTime.parse(raw.replace(' ', 'T')).atZone(OPERATIONS_ZONE).toInstant(); }
        catch (Exception ignored) { }
        try { return LocalDate.parse(raw).atStartOfDay(OPERATIONS_ZONE).toInstant(); }
        catch (Exception ignored) { return null; }
    }

    private LocalDate parseDate(String value, String field) {
        if (value == null || value.isBlank()) return null;
        try { return LocalDate.parse(value.trim()); }
        catch (Exception invalid) {
            throw new FieldAccessException(400, "DATE_INVALID", field + " must use YYYY-MM-DD");
        }
    }

    private String normalizeView(String view) {
        String normalized = view == null || view.isBlank() ? "tasks" : view.trim().toLowerCase(Locale.ROOT);
        if (!List.of("tasks", "calendar", "records").contains(normalized)) {
            throw new FieldAccessException(400, "VIEW_INVALID", "view must be tasks, calendar, or records");
        }
        return normalized;
    }

    private Long requireActor(Long actorId) {
        if (actorId == null) throw new FieldAccessException(401, "UNAUTHENTICATED", "authentication required");
        return actorId;
    }

    private String fieldName(FarmField field) {
        return field.getName() == null || field.getName().isBlank() ? field.getFieldId() : field.getName();
    }

    private String taskId(Map<String, Object> task) {
        String id = text(task.get("taskId"));
        return id == null ? "" : id;
    }

    private void addDate(List<LocalDate> dates, LocalDate date) {
        if (date != null) dates.add(date);
    }

    private String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private String upper(Object value) {
        String text = text(value);
        return text == null ? "" : text.toUpperCase(Locale.ROOT);
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
