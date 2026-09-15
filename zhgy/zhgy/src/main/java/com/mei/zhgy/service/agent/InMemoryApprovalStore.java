package com.mei.zhgy.service.agent;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Explicitly non-production store used by direct unit construction only. */
public class InMemoryApprovalStore implements ApprovalStore {
    private final Map<String, ApprovalRecord> records = new ConcurrentHashMap<>();

    @Override
    public synchronized Decision decide(String runId, String actionId, ApprovalState state, String actorId, Instant decidedAt) {
        String key = key(runId, actionId);
        ApprovalRecord existing = records.get(key);
        if (existing != null) {
            if (existing.getDecision() == state) return new Decision(existing, false);
            throw ApprovalException.conflict(runId, actionId, "动作已经有相反的审批决定");
        }
        ApprovalRecord created = ApprovalRecord.builder().runId(runId).actionId(actionId).decision(state)
                .executionState(state == ApprovalState.APPROVED ? ApprovalExecutionState.PENDING : ApprovalExecutionState.NONE)
                .actorId(actorId).decidedAt(decidedAt).build();
        records.put(key, created);
        return new Decision(created, true);
    }

    @Override public synchronized ApprovalRecord find(String runId, String actionId) { return records.get(key(runId, actionId)); }

    @Override
    public synchronized boolean claimExecution(String runId, String actionId) {
        ApprovalRecord record = records.get(key(runId, actionId));
        if (record == null || record.getDecision() != ApprovalState.APPROVED) return false;
        if (record.getExecutionState() != ApprovalExecutionState.PENDING) return false;
        record.setExecutionState(ApprovalExecutionState.EXECUTING);
        return true;
    }

    @Override
    public synchronized void completeExecution(String runId, String actionId, String taskId) {
        ApprovalRecord record = require(runId, actionId);
        record.setExecutionState(ApprovalExecutionState.COMPLETED);
        record.setTaskId(taskId);
        record.setFailureCode(null);
        record.setFailureMessage(null);
    }

    @Override
    public synchronized void failExecution(String runId, String actionId, String failureCode, String failureMessage) {
        ApprovalRecord record = require(runId, actionId);
        record.setExecutionState(ApprovalExecutionState.FAILED);
        record.setFailureCode(failureCode);
        record.setFailureMessage(failureMessage);
    }

    private ApprovalRecord require(String runId, String actionId) {
        ApprovalRecord record = find(runId, actionId);
        if (record == null) throw ApprovalException.actionNotFound(runId, actionId);
        return record;
    }

    private String key(String runId, String actionId) { return String.valueOf(runId) + "\u0000" + String.valueOf(actionId); }
}
