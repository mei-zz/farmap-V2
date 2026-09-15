package com.mei.zhgy.service.agent;

import java.time.Instant;

/** Storage contract for atomic approval decisions and one execution claim. */
public interface ApprovalStore {
    Decision decide(String runId, String actionId, ApprovalState state, String actorId, Instant decidedAt);
    ApprovalRecord find(String runId, String actionId);
    boolean claimExecution(String runId, String actionId);
    void completeExecution(String runId, String actionId, String taskId);
    void failExecution(String runId, String actionId, String failureCode, String failureMessage);

    record Decision(ApprovalRecord record, boolean created) { }
}
