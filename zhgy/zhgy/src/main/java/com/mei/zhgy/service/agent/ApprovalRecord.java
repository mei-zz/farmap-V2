package com.mei.zhgy.service.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** Durable decision and execution lease for one run/action pair. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalRecord {
    private String runId;
    private String actionId;
    private ApprovalState decision;
    private ApprovalExecutionState executionState;
    private String actorId;
    private Instant decidedAt;
    private String taskId;
    private String failureCode;
    private String failureMessage;
}
