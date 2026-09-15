package com.mei.zhgy.service.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentRun {
    private String runId;
    /** Authenticated owner captured when the run is created. */
    private Long ownerUserId;
    private String goal;
    private String mode;
    private AgentContext context;
    private RunStatus status;
    /** Immutable server-owned snapshot of the planner output at planVersion 0. */
    private AgentPlan initialPlan;
    /** Current server-owned plan; run.plan remains the compatibility alias. */
    private AgentPlan currentPlan;
    private AgentPlan plan;
    private int planVersion;
    private boolean replanAttempted;
    private int replanCount;
    private String replanReason;
    @Builder.Default private List<Map<String, Object>> replanSummary = new ArrayList<>();
    @Builder.Default private List<AgentPlanStep> steps = new ArrayList<>();
    @Builder.Default private List<AgentToolCall> toolCalls = new ArrayList<>();
    @Builder.Default private List<DiagnosisEvidenceVO> evidence = new ArrayList<>();
    @Builder.Default private Map<String, Object> output = new LinkedHashMap<>();
    @Builder.Default private List<ProposedAction> actions = new ArrayList<>();
    private ApprovalState approvalState;
    /** Last server-side evidence decision, persisted with the run snapshot. */
    private EvidenceSufficiencyDecision evidenceSufficiency;
    @Builder.Default private List<AgentEvent> traces = new ArrayList<>();
    @Builder.Default private Map<String, Object> metadata = new LinkedHashMap<>();
    private Instant createdAt;
    private Instant updatedAt;
    private String failureCode;
    private String failureMessage;
    private boolean recoveryAttempted;
    private int recoveryCount;
    private String recoveryOutcome;
    @Builder.Default private List<Map<String, Object>> recoverySummary = new ArrayList<>();
    private boolean cancellationRequested;

}
