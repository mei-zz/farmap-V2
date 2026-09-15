package com.mei.zhgy.service.agent;

/** Stable API error for approval and production write safety failures. */
public class ApprovalException extends RuntimeException {
    private final String code;
    private final int httpStatus;
    private final String runId;
    private final String actionId;

    public ApprovalException(String code, String message, int httpStatus, String runId, String actionId) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
        this.runId = runId;
        this.actionId = actionId;
    }

    public String getCode() { return code; }
    public int getHttpStatus() { return httpStatus; }
    public String getRunId() { return runId; }
    public String getActionId() { return actionId; }

    public static ApprovalException actionNotFound(String runId, String actionId) {
        return new ApprovalException("ACTION_NOT_FOUND", "动作不存在: " + actionId, 404, runId, actionId);
    }

    public static ApprovalException invalidState(String runId, String actionId, String message) {
        return new ApprovalException("INVALID_ACTION_STATE", message, 409, runId, actionId);
    }

    public static ApprovalException conflict(String runId, String actionId, String message) {
        return new ApprovalException("CONFLICT", message, 409, runId, actionId);
    }

    public static ApprovalException alreadyApproved(String runId, String actionId) {
        return new ApprovalException("ALREADY_APPROVED", "动作已经批准", 409, runId, actionId);
    }

    public static ApprovalException alreadyRejected(String runId, String actionId) {
        return new ApprovalException("ALREADY_REJECTED", "动作已经拒绝", 409, runId, actionId);
    }

    public static ApprovalException persistenceUnavailable(String runId, String actionId, Throwable cause) {
        ApprovalException exception = new ApprovalException("PERSISTENCE_UNAVAILABLE", "审批写入持久化不可用，已拒绝执行写操作", 503, runId, actionId);
        exception.initCause(cause);
        return exception;
    }
}
