package com.mei.zhgy.controller;

import com.mei.zhgy.context.BaseContext;
import com.mei.zhgy.result.Result;
import com.mei.zhgy.service.field.FieldAccessException;
import com.mei.zhgy.service.WorkspaceAdminAccess;
import com.mei.zhgy.service.operations.OperationsPlanService;
import com.mei.zhgy.service.operations.OperationsTaskService;
import com.mei.zhgy.service.operations.OperationsTaskViewService;
import com.mei.zhgy.service.operations.OperationsWriteException;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/operations")
public class OperationsController {
    private final OperationsTaskService tasks;
    private final OperationsPlanService plans;
    private final OperationsTaskViewService views;
    private final WorkspaceAdminAccess admin;

    @Autowired
    public OperationsController(OperationsTaskService tasks, OperationsPlanService plans,
                                OperationsTaskViewService views, WorkspaceAdminAccess admin) {
        this.tasks = tasks; this.plans = plans; this.views = views; this.admin = admin;
    }

    /** Compatibility constructor for focused lifecycle tests that do not mount read/batch routes. */
    public OperationsController(OperationsTaskService tasks, OperationsPlanService plans) {
        this(tasks, plans, null, null);
    }

    @GetMapping("/tasks")
    public Result<Map<String, Object>> list() {
        List<Map<String, Object>> items = tasks.listTasks(actor());
        Map<String, Object> body = new LinkedHashMap<>(); body.put("items", items); body.put("count", items.size());
        return Result.success(body);
    }

    @GetMapping("/tasks/{taskId}")
    public Result<Map<String, Object>> get(@PathVariable String taskId) { return Result.success(tasks.getTask(taskId, actor())); }

    @PostMapping("/tasks/{taskId}/assign")
    public Result<Map<String, Object>> assign(@PathVariable String taskId, @RequestBody AssignRequest request) {
        return Result.success(tasks.assignTask(taskId, request == null ? null : request.getAssignee(), actor()));
    }

    @PostMapping("/tasks/{taskId}/start")
    public Result<Map<String, Object>> start(@PathVariable String taskId) { return Result.success(tasks.startTask(taskId, actor())); }

    @PostMapping("/tasks/{taskId}/complete")
    public Result<Map<String, Object>> complete(@PathVariable String taskId, @RequestBody(required = false) CompleteRequest request) {
        CompleteRequest value = request == null ? new CompleteRequest() : request;
        return Result.success(tasks.completeTask(taskId, value.getSummary(), value.getExecutionEvidence(), value.getNotes(), actor()));
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public Result<Map<String, Object>> cancel(@PathVariable String taskId, @RequestBody(required = false) CancelRequest request) {
        return Result.success(tasks.cancelTask(taskId, request == null ? null : request.getReason(), actor()));
    }

    @PostMapping("/tasks/{taskId}/schedule")
    public Result<Map<String, Object>> schedule(@PathVariable String taskId, @RequestBody(required = false) ScheduleRequest request) {
        ScheduleRequest value = request == null ? new ScheduleRequest() : request;
        return Result.success(tasks.scheduleTask(taskId, value.getScheduledFor(), value.getVersion(), actor()));
    }

    @PostMapping("/tasks/schedule-batch")
    public Result<Map<String, Object>> scheduleBatch(@RequestBody(required = false) ScheduleBatchRequest request) {
        admin.requireAdmin();
        ScheduleBatchRequest value = request == null ? new ScheduleBatchRequest() : request;
        List<OperationsTaskService.ScheduleItem> items = value.getItems() == null ? null : value.getItems().stream()
                .map(item -> item == null ? null : new OperationsTaskService.ScheduleItem(
                        item.getTaskId(), item.getScheduledFor(), item.getVersion()))
                .toList();
        return Result.success(tasks.scheduleBatch(value.getFarmId(), items, actor()));
    }

    @GetMapping("/task-view")
    public Result<Map<String, Object>> taskView(@RequestParam Integer farmId,
                                                @RequestParam(required = false) String fieldId,
                                                @RequestParam(required = false) String from,
                                                @RequestParam(required = false) String to,
                                                @RequestParam(defaultValue = "tasks") String view,
                                                @RequestParam(required = false) String filter) {
        Long user = actor();
        return Result.success(filter == null ? views.view(farmId, fieldId, from, to, view, user) : views.view(farmId, fieldId, from, to, view, filter, user));
    }

    @GetMapping("/tasks/{taskId}/outcome")
    public Result<Map<String, Object>> outcome(@PathVariable String taskId) { return Result.success(tasks.getOutcome(taskId, actor())); }

    @GetMapping("/tasks/{taskId}/events")
    public Result<List<Map<String, Object>>> events(@PathVariable String taskId) { return Result.success(tasks.listEvents(taskId, actor())); }

    @GetMapping("/plan-drafts")
    public Result<List<Map<String, Object>>> listPlanDrafts(@RequestParam String analysisRunId) {
        return Result.success(plans.listDrafts(analysisRunId, actor()));
    }

    @PostMapping("/plan-drafts/from-analysis")
    public Result<Map<String, Object>> planDraftFromAnalysis(@RequestBody(required = false) FromAnalysisRequest request) {
        FromAnalysisRequest value = request == null ? new FromAnalysisRequest() : request;
        return Result.success(plans.fromAnalysis(value.getAnalysisRunId(), value.getFieldId(), actor()));
    }

    @GetMapping("/plan-drafts/{draftId}")
    public Result<Map<String, Object>> getPlanDraft(@PathVariable String draftId) {
        return Result.success(plans.getDraft(draftId, actor()));
    }

    @PostMapping("/plan-drafts/{draftId}/save")
    public Result<Map<String, Object>> savePlanDraft(@PathVariable String draftId, @RequestBody(required = false) PlanDraftRequest request) {
        return Result.success(plans.save(draftId, actor(), draftEdit(request)));
    }

    @PostMapping("/plan-drafts/{draftId}/confirm")
    public Result<Map<String, Object>> confirmPlanDraft(@PathVariable String draftId, @RequestBody(required = false) PlanDraftRequest request) {
        return Result.success(plans.confirm(draftId, actor(), draftEdit(request)));
    }

    private Long actor() {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) throw new OperationsWriteException("FORBIDDEN", "用户未登录");
        return userId;
    }

    @ExceptionHandler(OperationsWriteException.class)
    public ResponseEntity<Map<String, Object>> operationsError(OperationsWriteException error) {
        Map<String, Object> body = new LinkedHashMap<>(); body.put("code", error.getCode()); body.put("message", error.getMessage());
        if (error.getTaskId() != null) body.put("taskId", error.getTaskId());
        if (error.getCurrentStatus() != null) body.put("currentStatus", error.getCurrentStatus());
        if (error.getRequestedTransition() != null) body.put("requestedTransition", error.getRequestedTransition());
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(httpStatus(error)).body(body);
    }

    @ExceptionHandler(FieldAccessException.class)
    public ResponseEntity<Map<String, Object>> fieldError(FieldAccessException error) {
        Map<String, Object> body = new LinkedHashMap<>(); body.put("code", error.getCode()); body.put("message", error.getMessage());
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(error.getHttpStatus()).body(body);
    }

    private HttpStatus httpStatus(OperationsWriteException error) {
        return switch (error.getCode()) {
            case "TASK_NOT_FOUND", "PLAN_DRAFT_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "FORBIDDEN" -> HttpStatus.FORBIDDEN;
            case "VALIDATION_FAILED", "ACTION_ID_REQUIRED", "PLAN_DRAFT_VALIDATION", "PLAN_ANALYSIS_NOT_MATURITY",
                    "PLAN_ANALYSIS_INVALID", "PLAN_FIELD_MISMATCH", "PLAN_FIELD_NOT_ELIGIBLE" -> HttpStatus.UNPROCESSABLE_ENTITY;
            case "OPERATIONS_PERSISTENCE_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.CONFLICT;
        };
    }

    private OperationsPlanService.DraftEdit draftEdit(PlanDraftRequest request) {
        if (request == null) return null;
        return new OperationsPlanService.DraftEdit(request.getVersion(), request.getTitle(), request.getDetail(),
                request.getPriority(), request.getScheduledFor());
    }

    @Data public static class AssignRequest { private String assignee; }
    @Data public static class CompleteRequest { private String summary; private String executionEvidence; private String notes; }
    @Data public static class CancelRequest { private String reason; }
    @Data public static class ScheduleRequest { private String scheduledFor; private Long version; }
    @Data public static class ScheduleBatchRequest { private Integer farmId; private List<ScheduleItemRequest> items; }
    @Data public static class ScheduleItemRequest { private String taskId; private String scheduledFor; private Long version; }
    @Data public static class FromAnalysisRequest { private String analysisRunId; private String fieldId; }
    @Data public static class PlanDraftRequest {
        private Long version; private String title; private String detail; private String priority; private String scheduledFor;
    }
}
