package com.mei.zhgy.service.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** Structured, server-produced plan adaptation. No model rationale is persisted. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReplanDecision {
    private ReplanDecisionType decision;
    private ReplanTrigger trigger;
    private String reasonCode;
    private String targetStepId;
    private String replacementTool;
    @Builder.Default private List<AgentPlanStep> remainingSteps = new ArrayList<>();
    private String rationale;
    @Builder.Default private boolean modelCall = false;
}
