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
    private String goal;
    private String mode;
    private AgentContext context;
    private RunStatus status;
    private AgentPlan plan;
    @Builder.Default private List<AgentPlanStep> steps = new ArrayList<>();
    @Builder.Default private List<AgentToolCall> toolCalls = new ArrayList<>();
    @Builder.Default private List<DiagnosisEvidenceVO> evidence = new ArrayList<>();
    @Builder.Default private Map<String, Object> output = new LinkedHashMap<>();
    @Builder.Default private List<ProposedAction> actions = new ArrayList<>();
    private ApprovalState approvalState;
    @Builder.Default private List<AgentEvent> traces = new ArrayList<>();
    @Builder.Default private Map<String, Object> metadata = new LinkedHashMap<>();
    private Instant createdAt;
    private Instant updatedAt;
    private String failureCode;
    private String failureMessage;
    private boolean cancellationRequested;

}
