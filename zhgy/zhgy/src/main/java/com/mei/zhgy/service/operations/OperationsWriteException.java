package com.mei.zhgy.service.operations;

/** Domain failure for an Operations command. */
public class OperationsWriteException extends RuntimeException {
    private final String code;
    private final String taskId;
    private final String currentStatus;
    private final String requestedTransition;

    public OperationsWriteException(String code, String message) { this(code, message, null, null, null, null); }
    public OperationsWriteException(String code, String message, Throwable cause) { this(code, message, cause, null, null, null); }
    public OperationsWriteException(String code, String message, String taskId, String currentStatus, String requestedTransition) { this(code, message, null, taskId, currentStatus, requestedTransition); }

    private OperationsWriteException(String code, String message, Throwable cause, String taskId, String currentStatus, String requestedTransition) {
        super(message, cause); this.code = code; this.taskId = taskId; this.currentStatus = currentStatus; this.requestedTransition = requestedTransition;
    }

    public String getCode() { return code; }
    public String getTaskId() { return taskId; }
    public String getCurrentStatus() { return currentStatus; }
    public String getRequestedTransition() { return requestedTransition; }
    public static OperationsWriteException notFound(String taskId) { return new OperationsWriteException("TASK_NOT_FOUND", "Operations 任务不存在", taskId, null, null); }
    public static OperationsWriteException forbidden(String taskId) { return new OperationsWriteException("FORBIDDEN", "无权操作该 Operations 任务", taskId, null, null); }
    public static OperationsWriteException invalidTransition(String taskId, String current, String requested) { return new OperationsWriteException("INVALID_TASK_TRANSITION", "不允许的任务状态转换: " + current + " -> " + requested, taskId, current, requested); }
    public static OperationsWriteException conflict(String taskId, String message) { return new OperationsWriteException("TASK_CONFLICT", message, taskId, null, null); }
    public static OperationsWriteException validation(String taskId, String message) { return new OperationsWriteException("VALIDATION_FAILED", message, taskId, null, null); }
}
