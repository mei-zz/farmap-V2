package com.mei.zhgy.controller;

import com.mei.zhgy.context.BaseContext;
import com.mei.zhgy.result.Result;
import com.mei.zhgy.service.operations.OperationsTaskService;
import com.mei.zhgy.service.operations.OperationsWriteException;
import lombok.Data;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/operations")
public class OperationsController {
    private final OperationsTaskService tasks;
    public OperationsController(OperationsTaskService tasks) { this.tasks = tasks; }

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

    @GetMapping("/tasks/{taskId}/outcome")
    public Result<Map<String, Object>> outcome(@PathVariable String taskId) { return Result.success(tasks.getOutcome(taskId, actor())); }

    @GetMapping("/tasks/{taskId}/events")
    public Result<List<Map<String, Object>>> events(@PathVariable String taskId) { return Result.success(tasks.listEvents(taskId, actor())); }

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

    private HttpStatus httpStatus(OperationsWriteException error) {
        return switch (error.getCode()) {
            case "TASK_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "FORBIDDEN" -> HttpStatus.FORBIDDEN;
            case "VALIDATION_FAILED", "ACTION_ID_REQUIRED" -> HttpStatus.UNPROCESSABLE_ENTITY;
            case "OPERATIONS_PERSISTENCE_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.CONFLICT;
        };
    }

    @Data public static class AssignRequest { private String assignee; }
    @Data public static class CompleteRequest { private String summary; private String executionEvidence; private String notes; }
    @Data public static class CancelRequest { private String reason; }
}
