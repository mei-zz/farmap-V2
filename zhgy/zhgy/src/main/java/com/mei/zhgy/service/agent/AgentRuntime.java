package com.mei.zhgy.service.agent;

import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import com.mei.zhgy.vo.DiagnosisAnalysisResponse;
import com.mei.zhgy.service.operations.OperationsTaskService;
import com.mei.zhgy.service.operations.OperationsWriteException;
import com.mei.zhgy.service.diagnosis.DiagnosisPersistenceService;
import com.mei.zhgy.service.agriculture.FieldFacts;
import com.mei.zhgy.service.agriculture.FieldFactsService;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Bounded agent state machine. A run is persisted after every observable transition. */
@Service
@Slf4j
public class AgentRuntime {
    private final AgentPlanner planner;
    private final ToolExecutor toolExecutor;
    private final AgentRunRepository repository;
    private final TraceRecorder traceRecorder;
    private final ApprovalManager approvalManager;
    private final OperationsTaskService operations;
    private final DiagnosisPersistenceService diagnosisPersistence;
    private final EvidenceSufficiencyEvaluator evidenceSufficiencyEvaluator;
    private final AgentRecoveryPolicy recoveryPolicy;
    private final AgentReplanningPolicy replanningPolicy;
    private final AgentReplanner replanner;
    private final FieldFactsService fieldFactsService;
    private final Executor executor = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "farmap-agent"); t.setDaemon(true); return t; });

    public AgentRuntime(AgentPlanner planner, ToolExecutor toolExecutor, AgentRunRepository repository,
                        TraceRecorder traceRecorder, ApprovalManager approvalManager) {
        this(planner, toolExecutor, repository, traceRecorder, approvalManager, null, null, new EvidenceSufficiencyEvaluator());
    }

    public AgentRuntime(AgentPlanner planner, ToolExecutor toolExecutor, AgentRunRepository repository,
                        TraceRecorder traceRecorder, ApprovalManager approvalManager, OperationsTaskService operations) {
        this(planner, toolExecutor, repository, traceRecorder, approvalManager, operations, null, new EvidenceSufficiencyEvaluator());
    }

    public AgentRuntime(AgentPlanner planner, ToolExecutor toolExecutor, AgentRunRepository repository,
                        TraceRecorder traceRecorder, ApprovalManager approvalManager, OperationsTaskService operations,
                        DiagnosisPersistenceService diagnosisPersistence) {
        this(planner, toolExecutor, repository, traceRecorder, approvalManager, operations, diagnosisPersistence, new EvidenceSufficiencyEvaluator());
    }

    public AgentRuntime(AgentPlanner planner, ToolExecutor toolExecutor, AgentRunRepository repository,
                        TraceRecorder traceRecorder, ApprovalManager approvalManager, OperationsTaskService operations,
                        DiagnosisPersistenceService diagnosisPersistence, EvidenceSufficiencyEvaluator evidenceSufficiencyEvaluator) {
        this(planner, toolExecutor, repository, traceRecorder, approvalManager, operations, diagnosisPersistence,
                evidenceSufficiencyEvaluator, new AgentRecoveryPolicy(), new AgentReplanningPolicy());
    }

    public AgentRuntime(AgentPlanner planner, ToolExecutor toolExecutor, AgentRunRepository repository,
                        TraceRecorder traceRecorder, ApprovalManager approvalManager, OperationsTaskService operations,
                        DiagnosisPersistenceService diagnosisPersistence, EvidenceSufficiencyEvaluator evidenceSufficiencyEvaluator,
                        AgentRecoveryPolicy recoveryPolicy, AgentReplanningPolicy replanningPolicy) {
        this(planner, toolExecutor, repository, traceRecorder, approvalManager, operations, diagnosisPersistence,
                evidenceSufficiencyEvaluator, recoveryPolicy, replanningPolicy, null);
    }

    @Autowired
    public AgentRuntime(AgentPlanner planner, ToolExecutor toolExecutor, AgentRunRepository repository,
                        TraceRecorder traceRecorder, ApprovalManager approvalManager, OperationsTaskService operations,
                        DiagnosisPersistenceService diagnosisPersistence, EvidenceSufficiencyEvaluator evidenceSufficiencyEvaluator,
                        AgentRecoveryPolicy recoveryPolicy, AgentReplanningPolicy replanningPolicy,
                        @Autowired(required = false) FieldFactsService fieldFactsService) {
        this.planner = planner; this.toolExecutor = toolExecutor; this.repository = repository;
        this.traceRecorder = traceRecorder; this.approvalManager = approvalManager; this.operations = operations;
        this.diagnosisPersistence = diagnosisPersistence;
        this.evidenceSufficiencyEvaluator = evidenceSufficiencyEvaluator == null ? new EvidenceSufficiencyEvaluator() : evidenceSufficiencyEvaluator;
        this.recoveryPolicy = recoveryPolicy == null ? new AgentRecoveryPolicy() : recoveryPolicy;
        this.replanningPolicy = replanningPolicy == null ? new AgentReplanningPolicy() : replanningPolicy;
        this.replanner = new AgentReplanner(toolExecutor, this.replanningPolicy);
        this.fieldFactsService = fieldFactsService;
    }

    public AgentRun createRun(String goal, AgentContext context, String mode) {
        return createRun(goal, context, mode, null);
    }

    public AgentRun createRun(String goal, AgentContext context, String mode, Long userId) {
        if (goal == null || goal.isBlank()) throw new IllegalArgumentException("goal 不能为空");
        Instant now = Instant.now();
        AgentContext stableContext = context == null ? new AgentContext() : context;
        enrichFieldFacts(stableContext, mode, userId);
        validateProvenance(stableContext, mode, userId);
        Map<String, Object> runMetadata = new LinkedHashMap<>(Map.of("runtime", "agent-core-v1", "toolPolicy", "read-auto-write-approval", "maxLoop", 12));
        if (stableContext.getDiagnosisId() != null && !stableContext.getDiagnosisId().isBlank()) runMetadata.put("diagnosisId", stableContext.getDiagnosisId());
        if (stableContext.getSourceRecommendationId() != null && !stableContext.getSourceRecommendationId().isBlank()) runMetadata.put("sourceRecommendationId", stableContext.getSourceRecommendationId());
        AgentRun run = AgentRun.builder().runId("agent-" + UUID.randomUUID()).goal(goal.trim())
                .context(stableContext).mode(mode == null ? "real" : mode).ownerUserId(userId)
                .status(RunStatus.CREATED).approvalState(ApprovalState.NONE).createdAt(now).updatedAt(now)
                .metadata(runMetadata).build();
        repository.save(run);
        Map<String, Object> runTrace = new LinkedHashMap<>(); runTrace.put("runId", run.getRunId()); runTrace.put("status", run.getStatus().name());
        if (stableContext.getDiagnosisId() != null) runTrace.put("diagnosisId", stableContext.getDiagnosisId());
        if (stableContext.getSourceRecommendationId() != null) runTrace.put("sourceRecommendationId", stableContext.getSourceRecommendationId());
        traceRecorder.record(run, "run.created", runTrace); repository.save(run);
        return run;
    }

    public CompletableFuture<AgentRun> start(String runId) {
        return CompletableFuture.supplyAsync(() -> execute(runId), executor);
    }

    /** Creates a REAL run from the server-owned persisted Diagnosis snapshot. */
    public AgentRun createRunFromPersistedDiagnosis(String goal, String diagnosisId, String recommendationId, Long userId) {
        if (diagnosisPersistence == null) {
            throw ProductLoopRelationException.validation("Diagnosis 持久化服务不可用，不能创建 REAL Agent", diagnosisId, recommendationId);
        }
        AgentContext canonical = diagnosisPersistence.buildAgentContext(diagnosisId, recommendationId, userId, null);
        AgentRun run = createRun(goal, canonical, "real", userId);
        start(run.getRunId());
        return run;
    }

    /** Synchronous entry point used by deterministic tests and recovery workers. */
    public AgentRun execute(String runId) {
        AgentRun run = require(runId);
        synchronized (run) {
            if (run.getStatus() == RunStatus.CANCELLED || run.getStatus() == RunStatus.COMPLETED) return run;
            transition(run, RunStatus.PLANNING, "run.planning");
            // Persist the transition before the planner calls a thinking model.
            // Planning can take tens of seconds; clients must not observe CREATED
            // while a real Agent is already running in the background.
            repository.save(run);
            try {
                AgentPlan plan = planner.plan(run);
                AgentPlan initialPlan = copyPlan(plan);
                AgentPlan currentPlan = copyPlan(plan);
                run.setInitialPlan(initialPlan); run.setCurrentPlan(currentPlan); run.setPlan(currentPlan);
                run.setPlanVersion(0); run.setReplanAttempted(false); run.setReplanCount(0);
                run.setReplanReason(null); run.getReplanSummary().clear();
                run.setSteps(currentPlan.getSteps());
                run.getMetadata().put("plannerModel", plan.getPlannerModel());
                run.getMetadata().put("maxReplans", replanningPolicy.maxReplans());
                traceRecorder.record(run, "plan.created", Map.of("steps", plan.getSteps().size(), "schemaVersion", plan.getSchemaVersion(), "planVersion", 0));
                transition(run, RunStatus.RUNNING, "run.running");
                executeSteps(run);
            } catch (Exception error) {
                if (run.getStatus() != RunStatus.CANCELLED) fail(run, "AGENT_RUNTIME_FAILED", error.getMessage());
            }
            persist(run); return run;
        }
    }

    private void executeSteps(AgentRun run) {
        Map<String, Object> prior = new LinkedHashMap<>();
        int[] calls = {0};
        int index = 0;
        while (index < run.getSteps().size()) {
            AgentPlanStep step = run.getSteps().get(index);
            if (step == null || step.getStatus() != StepStatus.PENDING) { index++; continue; }
            if (run.isCancellationRequested()) { transition(run, RunStatus.CANCELLED, "run.cancelled"); return; }
            if (Duration.between(run.getCreatedAt(), Instant.now()).toMillis() > run.getPlan().getTimeoutMs()) { fail(run, "AGENT_TIMEOUT", "Agent 运行超时"); return; }

            if (step.isReadOnly() && !step.isRequired() && alreadySufficient(run, prior)) {
                step.setStatus(StepStatus.SKIPPED);
                if (applyReplan(run, step, null, ReplanTrigger.ALREADY_SATISFIED, prior, calls, index)) return;
                index++;
                continue;
            }

            if (!step.isReadOnly()) {
                // Write-capable steps remain a ProposedAction and never become a
                // direct replanner execution target.
                if (!"evaluation".equalsIgnoreCase(run.getMode())) {
                    if (!toolExecutor.supportsWrite(step.getToolName())) {
                        step.setStatus(StepStatus.SKIPPED);
                        traceRecorder.record(run, "action.blocked.unsupported_tool", Map.of("tool", String.valueOf(step.getToolName()), "reason", "WRITE_TOOL_NOT_REGISTERED_OR_NOT_WRITE_CAPABLE"));
                        complete(run, prior); return;
                    }
                    EvidenceResolution evidence = resolveEvidence(run);
                    EvidenceSufficiencyDecision decision = evidenceSufficiencyEvaluator.evaluate(true, evidence.evidence(), recommendationId(run),
                            evidence.recommendationEvidenceIds(), null, evidence.unavailableDependencies(), evidence.emptyObserved());
                    run.setEvidenceSufficiency(decision);
                    Map<String, Object> gateTrace = new LinkedHashMap<>();
                    gateTrace.put("status", decision.getStatus().name()); gateTrace.put("allowsWrite", decision.isAllowsWrite());
                    gateTrace.put("reasonCodes", decision.getReasonCodes()); gateTrace.put("evidenceIds", decision.getAvailableEvidenceIds());
                    gateTrace.put("planVersion", run.getPlanVersion());
                    traceRecorder.record(run, "evidence.sufficiency.evaluated", gateTrace);
                    if (!decision.isAllowsWrite()) {
                        step.setStatus(StepStatus.COMPLETED);
                        Map<String, Object> blockedTrace = new LinkedHashMap<>();
                        blockedTrace.put("status", decision.getStatus().name()); blockedTrace.put("reasonCodes", decision.getReasonCodes());
                        blockedTrace.put("diagnosisId", diagnosisId(run) == null ? "" : diagnosisId(run));
                        if (recommendationId(run) != null) blockedTrace.put("recommendationId", recommendationId(run));
                        traceRecorder.record(run, "action.blocked.insufficient_evidence", blockedTrace);
                        complete(run, prior); return;
                    }
                }
                ProposedAction action = ProposedAction.builder().id("action-" + run.getRunId() + "-" + step.getId()).type("operations_task").title("创建农事复核任务")
                        .detail("由 Agent 根据已持久化 Recommendation 和其支持证据创建 Operations 任务").toolName(step.getToolName()).approvalRequired(true)
                        .diagnosisId(diagnosisId(run)).recommendationId(recommendationId(run)).evidenceIds(actionEvidenceIds(run))
                        .approvalState(ApprovalState.REQUIRED).payload(new LinkedHashMap<>(prior)).build();
                run.getActions().add(action); run.setApprovalState(ApprovalState.REQUIRED); step.setStatus(StepStatus.PENDING);
                traceRecorder.record(run, "approval.required", provenance(action));
                transition(run, RunStatus.WAITING_FOR_APPROVAL, "run.waiting_for_approval"); persist(run); return;
            }

            step.setStatus(StepStatus.RUNNING);
            traceRecorder.record(run, "step.started", Map.of("stepId", step.getId(), "tool", step.getToolName(), "planVersion", run.getPlanVersion()));
            ToolResult result = executeReadWithRecovery(run, step, prior, calls);
            if (result == null) return;
            if (result.getExecutionStatus() == ToolExecutionStatus.NON_RETRYABLE_FAILURE || result.getExecutionStatus() == ToolExecutionStatus.CONTRACT_FAILURE) {
                step.setStatus(StepStatus.FAILED); fail(run, result.getErrorCode(), result.getErrorMessage()); return;
            }
            applyToolResult(run, step, result, prior);
            step.setStatus(result.getExecutionStatus() == ToolExecutionStatus.SUCCESS || result.getExecutionStatus() == ToolExecutionStatus.EMPTY
                    ? StepStatus.COMPLETED : StepStatus.SKIPPED);
            traceRecorder.record(run, "step.completed", Map.of("stepId", step.getId(), "status", step.getStatus().name(),
                    "executionStatus", String.valueOf(result.getExecutionStatus()), "planVersion", run.getPlanVersion()));
            repository.save(run);

            ReplanTrigger trigger = replanTrigger(step, result);
            if (trigger != null && applyReplan(run, step, result, trigger, prior, calls, index)) return;
            index++;
        }
        complete(run, prior);
    }

    private ToolResult executeReadWithRecovery(AgentRun run, AgentPlanStep step, Map<String, Object> prior, int[] calls) {
        ToolResult first = invoke(run, step.getToolName(), prior, calls[0] + 1, false, calls);
        ToolResult finalResult = first;
        if (first.getExecutionStatus() == ToolExecutionStatus.RETRYABLE_FAILURE
                && recoveryPolicy.maxRecoveryAttempts() > 0 && recoveryPolicy.maxAttempts() > 1) {
            recordRecovery(run, step, first, RecoveryDecisionType.RETRY, null, 1);
            delayBeforeRetry();
            finalResult = invoke(run, step.getToolName(), prior, calls[0] + 1, true, calls);
            if (isSuccessful(finalResult)) {
                finalResult.setAttempt(2);
                completeRecovery(run, step, first, finalResult, RecoveryDecisionType.RETRY, null);
            } else {
                completeRecovery(run, step, first, finalResult, RecoveryDecisionType.FAIL_CLOSED, null);
            }
        } else if (first.getExecutionStatus() == ToolExecutionStatus.UNAVAILABLE) {
            List<String> alternatives = toolExecutor.safeAlternatives(step.getToolName());
            if (recoveryPolicy.maxRecoveryAttempts() > 0 && !alternatives.isEmpty()) {
                String alternative = alternatives.get(0);
                recordRecovery(run, step, first, RecoveryDecisionType.USE_SAFE_ALTERNATIVE, alternative, 1);
                finalResult = invoke(run, alternative, prior, calls[0] + 1, true, calls);
                if (isSuccessful(finalResult)) completeRecovery(run, step, first, finalResult, RecoveryDecisionType.USE_SAFE_ALTERNATIVE, alternative);
                else completeRecovery(run, step, first, finalResult, RecoveryDecisionType.FAIL_CLOSED, alternative);
            } else {
                recordRecovery(run, step, first, step.isRequired() ? RecoveryDecisionType.FAIL_CLOSED : RecoveryDecisionType.CONTINUE_WITHOUT_TOOL, null, 0);
            }
        }
        if (finalResult.getExecutionStatus() == ToolExecutionStatus.RETRYABLE_FAILURE) run.setRecoveryOutcome("EXHAUSTED");
        // UNAVAILABLE remains a dependency state, not a generic runtime failure.
        // The pre-write evidence gate will fail closed if the dependency is required.
        return finalResult;
    }

    private ToolResult invoke(AgentRun run, String toolName, Map<String, Object> prior, int attempt, boolean recovery, int[] calls) {
        if (++calls[0] > run.getPlan().getMaxToolCalls()) {
            fail(run, "MAX_TOOL_CALLS_EXCEEDED", "工具调用次数超过上限");
            return ToolResult.failed("MAX_TOOL_CALLS_EXCEEDED", "工具调用次数超过上限");
        }
        if (Duration.between(run.getCreatedAt(), Instant.now()).toMillis() >= run.getPlan().getTimeoutMs()) {
            fail(run, "AGENT_TIMEOUT", "Agent 运行超时");
            return ToolResult.failed("AGENT_TIMEOUT", "Agent 运行超时");
        }
        Instant startedAt = Instant.now();
        traceRecorder.record(run, "tool.started", Map.of("tool", toolName, "attempt", attempt, "recovery", recovery));
        ToolResult result = toolExecutor.execute(toolName, AgentToolRequest.builder().runId(run.getRunId()).goal(run.getGoal())
                .context(run.getContext()).ownerUserId(run.getOwnerUserId()).actorId(run.getOwnerUserId() == null ? null : String.valueOf(run.getOwnerUserId()))
                .priorOutputs(prior).build());
        Instant completedAt = Instant.now();
        result.setAttempt(attempt); result.setDurationMs(Duration.between(startedAt, completedAt).toMillis());
        AgentToolCall call = AgentToolCall.builder().id("toolcall-" + UUID.randomUUID()).toolName(toolName).status(result.getStatus())
                .executionStatus(result.getExecutionStatus()).attempt(attempt).recoveryAttempt(recovery).input(toolInput(run))
                .output(result.getOutput()).errorCode(result.getErrorCode()).errorMessage(result.getErrorMessage())
                .startedAt(startedAt).completedAt(completedAt).durationMs(result.getDurationMs()).build();
        run.getToolCalls().add(call);
        Map<String, Object> event = new LinkedHashMap<>(); event.put("tool", toolName); event.put("status", result.getStatus().name());
        event.put("executionStatus", String.valueOf(result.getExecutionStatus())); event.put("attempt", attempt); event.put("recovery", recovery);
        if (result.getErrorCode() != null) event.put("errorCode", result.getErrorCode());
        traceRecorder.record(run, "tool.completed", event);
        if (result.getExecutionStatus() != ToolExecutionStatus.SUCCESS && result.getExecutionStatus() != ToolExecutionStatus.EMPTY)
            traceRecorder.record(run, "tool.failure.classified", Map.of("tool", toolName, "executionStatus", String.valueOf(result.getExecutionStatus()), "attempt", attempt));
        repository.save(run);
        return result;
    }

    private ReplanTrigger replanTrigger(AgentPlanStep step, ToolResult result) {
        if (result == null) return null;
        return switch (result.getExecutionStatus()) {
            case EMPTY -> step.isRequired() ? ReplanTrigger.EMPTY_REQUIRED_SOURCE : ReplanTrigger.EMPTY_OPTIONAL_SOURCE;
            case UNAVAILABLE -> step.isRequired() ? ReplanTrigger.REQUIRED_SOURCE_UNAVAILABLE : ReplanTrigger.OPTIONAL_SOURCE_UNAVAILABLE;
            case RETRYABLE_FAILURE -> ReplanTrigger.RECOVERY_EXHAUSTED;
            default -> null;
        };
    }

    /**
     * Apply exactly one validated suffix mutation. The completed prefix is never
     * rebuilt or reordered; only the pending suffix is replaced.
     */
    private boolean applyReplan(AgentRun run, AgentPlanStep triggerStep, ToolResult result,
                                ReplanTrigger trigger, Map<String, Object> prior, int[] calls, int index) {
        if (run.getReplanCount() >= replanningPolicy.maxReplans()) {
            traceRecorder.record(run, "plan.replan.blocked", Map.of("trigger", trigger.name(), "reasonCode", "REPLAN_BUDGET_EXHAUSTED", "planVersion", run.getPlanVersion()));
            run.getOutput().put("allowsWrite", false);
            prior.put("allowsWrite", false); prior.put("replanDecision", "TERMINATE");
            run.setFailureCode("REPLAN_BUDGET_EXHAUSTED"); run.setFailureMessage("重规划次数超过上限");
            complete(run, prior);
            return true;
        }
        List<AgentPlanStep> oldRemaining = new ArrayList<>();
        if (index + 1 < run.getSteps().size()) oldRemaining.addAll(run.getSteps().subList(index + 1, run.getSteps().size()));
        ReplanDecision decision = replanner.decide(run, triggerStep, result, trigger, oldRemaining);
        try {
            replanner.validate(run, decision, oldRemaining);
        } catch (IllegalArgumentException invalid) {
            traceRecorder.record(run, "plan.replan.rejected", Map.of("trigger", trigger.name(), "reasonCode", invalid.getMessage()));
            run.getOutput().put("allowsWrite", false);
            prior.put("allowsWrite", false); prior.put("replanDecision", "TERMINATE");
            run.setFailureCode(invalid.getMessage()); run.setFailureMessage("服务器拒绝了不安全的重规划结果");
            complete(run, prior);
            return true;
        }

        run.setReplanAttempted(true); run.setReplanCount(run.getReplanCount() + 1);
        run.setReplanReason(trigger.name()); run.setPlanVersion(run.getPlanVersion() + 1);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("fromPlanVersion", run.getPlanVersion() - 1); summary.put("toPlanVersion", run.getPlanVersion());
        summary.put("trigger", trigger.name()); summary.put("reasonCode", decision.getReasonCode());
        summary.put("decision", decision.getDecision().name()); summary.put("targetStepId", decision.getTargetStepId());
        if (decision.getReplacementTool() != null) summary.put("replacementTool", decision.getReplacementTool());
        summary.put("remainingStepIds", decision.getRemainingSteps().stream().map(AgentPlanStep::getId).toList());
        summary.put("modelCall", false); summary.put("rationale", decision.getRationale());
        run.getReplanSummary().add(summary); run.getMetadata().put("replanSummary", run.getReplanSummary());
        traceRecorder.record(run, "plan.replanned", summary);

        if (decision.getDecision() == ReplanDecisionType.TERMINATE) {
            run.getOutput().put("allowsWrite", false);
            recheckEvidenceAfterReplan(run);
            prior.put("allowsWrite", false); prior.put("replanDecision", "TERMINATE");
            run.setFailureCode("REPLAN_TERMINATED"); run.setFailureMessage("重规划后没有安全的剩余计划");
            complete(run, prior);
            return true;
        }
        List<AgentPlanStep> prefix = new ArrayList<>(run.getSteps().subList(0, index + 1));
        List<AgentPlanStep> next = new ArrayList<>(prefix); next.addAll(decision.getRemainingSteps());
        run.setSteps(next);
        AgentPlan current = currentPlan(run);
        current.setSteps(next); run.setCurrentPlan(current); run.setPlan(current);
        recheckEvidenceAfterReplan(run);
        repository.save(run);
        return false;
    }

    private boolean alreadySufficient(AgentRun run, Map<String, Object> prior) {
        if (run.getEvidence() == null || run.getEvidence().isEmpty() || prior == null || prior.isEmpty()) return false;
        return run.getToolCalls().stream().anyMatch(call -> call != null && call.getExecutionStatus() == ToolExecutionStatus.SUCCESS);
    }

    private void recheckEvidenceAfterReplan(AgentRun run) {
        EvidenceResolution evidence = resolveEvidence(run);
        EvidenceSufficiencyDecision decision = evidenceSufficiencyEvaluator.evaluate(false, evidence.evidence(), recommendationId(run),
                evidence.recommendationEvidenceIds(), null, evidence.unavailableDependencies(), evidence.emptyObserved());
        run.setEvidenceSufficiency(decision);
        int count = Integer.parseInt(String.valueOf(run.getMetadata().getOrDefault("evidenceSufficiencyRechecks", 0))) + 1;
        run.getMetadata().put("evidenceSufficiencyRechecks", count);
        traceRecorder.record(run, "evidence.sufficiency.rechecked", Map.of("planVersion", run.getPlanVersion(),
                "status", decision.getStatus().name(), "allowsWrite", decision.isAllowsWrite(), "reasonCodes", decision.getReasonCodes()));
    }

    private AgentPlan currentPlan(AgentRun run) {
        if (run.getCurrentPlan() != null) return run.getCurrentPlan();
        AgentPlan plan = run.getPlan();
        if (plan == null) plan = AgentPlan.builder().maxSteps(8).maxToolCalls(12).timeoutMs(120000).build();
        return plan;
    }

    private void applyToolResult(AgentRun run, AgentPlanStep step, ToolResult result, Map<String, Object> prior) {
        if (result.getOutput() != null && (isSuccessful(result) || result.getExecutionStatus() == ToolExecutionStatus.EMPTY)) {
            prior.put(step.getToolName(), result.getOutput()); run.getOutput().put(step.getToolName(), result.getOutput());
        }
        if (result.getEvidence() != null) {
            int before = run.getEvidence().size(); mergeEvidence(run, result.getEvidence());
            if (run.getEvidence().size() > before) traceRecorder.record(run, "evidence.added", Map.of("tool", step.getToolName(), "count", run.getEvidence().size() - before));
        }
        if (result.getMetadata() != null) run.getMetadata().put(step.getToolName(), result.getMetadata());
        if (result.getActions() != null) {
            EvidenceResolution actionEvidence = resolveEvidence(run);
            for (ProposedAction proposed : result.getActions()) {
                ProposedAction normalized = normalizeAction(run, proposed);
                if (!toolExecutor.supportsWrite(normalized.getToolName())) {
                    traceRecorder.record(run, "action.blocked.unsupported_tool", Map.of("tool", String.valueOf(normalized.getToolName()), "actionId", String.valueOf(normalized.getId())));
                    continue;
                }
                EvidenceSufficiencyDecision decision = evidenceSufficiencyEvaluator.evaluate(true, actionEvidence.evidence(), recommendationId(run),
                        actionEvidence.recommendationEvidenceIds(), normalized.getEvidenceIds(), actionEvidence.unavailableDependencies(), actionEvidence.emptyObserved());
                run.setEvidenceSufficiency(decision);
                traceRecorder.record(run, "evidence.sufficiency.evaluated", Map.of("status", decision.getStatus().name(), "allowsWrite", decision.isAllowsWrite(), "reasonCodes", decision.getReasonCodes()));
                if (decision.isAllowsWrite()) run.getActions().add(normalized);
                else traceRecorder.record(run, "action.blocked.insufficient_evidence", Map.of("status", decision.getStatus().name(), "reasonCodes", decision.getReasonCodes()));
            }
        }
    }

    private boolean isSuccessful(ToolResult result) {
        return result != null && (result.getExecutionStatus() == ToolExecutionStatus.SUCCESS || result.getExecutionStatus() == ToolExecutionStatus.EMPTY);
    }

    private void recordRecovery(AgentRun run, AgentPlanStep step, ToolResult result, RecoveryDecisionType decision, String alternative, int recoveryAttempt) {
        if (recoveryAttempt > 0) {
            run.setRecoveryAttempted(true);
            run.setRecoveryCount(run.getRecoveryCount() + recoveryAttempt);
        }
        if (decision == RecoveryDecisionType.CONTINUE_WITHOUT_TOOL) run.setRecoveryOutcome("DEGRADED");
        if (decision == RecoveryDecisionType.FAIL_CLOSED) run.setRecoveryOutcome("EXHAUSTED");
        Map<String, Object> summary = new LinkedHashMap<>(); summary.put("initialTool", step.getToolName());
        summary.put("initialStatus", String.valueOf(result.getExecutionStatus())); summary.put("attemptCount", result.getAttempt() == null ? 1 : result.getAttempt());
        summary.put("recoveryDecision", decision.name()); summary.put("allowsWrite", false); summary.put("actionCount", 0);
        if (alternative != null) summary.put("fallbackTool", alternative);
        run.getRecoverySummary().add(summary); run.getMetadata().put("recoverySummary", run.getRecoverySummary());
        traceRecorder.record(run, "recovery.decision", summary);
        repository.save(run);
    }

    private void completeRecovery(AgentRun run, AgentPlanStep step, ToolResult initial, ToolResult finalResult, RecoveryDecisionType decision, String alternative) {
        run.setRecoveryOutcome(isSuccessful(finalResult) ? "RECOVERED" : "EXHAUSTED");
        Map<String, Object> summary = run.getRecoverySummary().isEmpty() ? new LinkedHashMap<>() : run.getRecoverySummary().get(run.getRecoverySummary().size() - 1);
        summary.put("attemptCount", finalResult.getAttempt() == null ? 2 : finalResult.getAttempt()); summary.put("finalStatus", String.valueOf(finalResult.getExecutionStatus()));
        summary.put("recoveryDecision", decision.name()); if (alternative != null) summary.put("fallbackTool", alternative);
        traceRecorder.record(run, isSuccessful(finalResult) ? "recovery.succeeded" : "recovery.exhausted", summary);
        repository.save(run);
    }

    private void delayBeforeRetry() {
        if (recoveryPolicy.retryBaseDelayMs() <= 0) return;
        try { Thread.sleep(recoveryPolicy.retryBaseDelayMs()); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    public AgentRun approve(String runId, String actionId) {
        AgentRun run = require(runId); synchronized (run) {
            ApprovalManager.Decision decision = approvalManager.approveDecision(run, actionId);
            traceRecorder.record(run, "approval.approved", Map.of("runId", runId, "actionId", actionId, "idempotent", !decision.created(), "actor", "current-user"));
            if (!decision.shouldAttemptExecution()) {
                recoverCommittedTask(run, decision);
                return run;
            }
            // The first caller persists its state before claiming. A concurrent retry
            // only claims; it never saves a stale run object unless it becomes the
            // single execution owner, preventing last-write-wins regression.
            if (decision.created()) repository.save(run);
            if (!approvalManager.claimExecution(runId, actionId)) return run;
            if (!decision.created()) repository.save(run);
            executeApprovedAction(run, decision.action());
            return run;
        }
    }

    public AgentRun reject(String runId, String actionId) {
        AgentRun run = require(runId); synchronized (run) {
            ApprovalManager.Decision decision = approvalManager.rejectDecision(run, actionId);
            traceRecorder.record(run, "approval.rejected", Map.of("runId", runId, "actionId", actionId, "idempotent", !decision.created(), "actor", "current-user"));
            if (run.getActions().stream().allMatch(a -> a.getApprovalState() != ApprovalState.REQUIRED)) complete(run, null);
            persist(run); return run;
        }
    }

    private void executeApprovedAction(AgentRun run, ProposedAction action) {
        if (action == null) throw ProductLoopRelationException.validation("AgentAction 不存在", diagnosisId(run), recommendationId(run));
        if (!java.util.Objects.equals(action.getDiagnosisId(), diagnosisId(run)) || !java.util.Objects.equals(action.getRecommendationId(), recommendationId(run)))
            throw ProductLoopRelationException.conflict("批准动作的 provenance 与 AgentRun 不一致", diagnosisId(run), action.getRecommendationId());
        transition(run, RunStatus.RUNNING, "run.resumed");
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("evidenceIds", run.getEvidence().stream().map(DiagnosisEvidenceVO::getId).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toList()));
        AgentToolRequest request = AgentToolRequest.builder().runId(run.getRunId()).actionId(action.getId()).goal(run.getGoal()).context(run.getContext()).ownerUserId(run.getOwnerUserId()).actorId(run.getOwnerUserId() == null ? "current-user" : String.valueOf(run.getOwnerUserId()))
                .diagnosisId(action.getDiagnosisId()).recommendationId(action.getRecommendationId()).approvalGranted(true).attributes(attributes).priorOutputs(run.getOutput()).build();
        Instant startedAt = Instant.now();
        ToolResult result = toolExecutor.execute(action.getToolName(), request);
        AgentToolCall call = AgentToolCall.builder().id("toolcall-" + UUID.randomUUID()).toolName(action.getToolName()).status(result.getStatus())
                .input(toolInput(run)).output(result.getOutput()).errorCode(result.getErrorCode()).errorMessage(result.getErrorMessage())
                .startedAt(startedAt).completedAt(Instant.now()).durationMs(Duration.between(startedAt, Instant.now()).toMillis()).build();
        run.getToolCalls().add(call);
        traceRecorder.record(run, "tool.completed", Map.of("tool", action.getToolName(), "status", result.getStatus().name(), "approvalGranted", true));
        if (result.getExecutionStatus() == ToolExecutionStatus.RETRYABLE_FAILURE) {
            reconcileAmbiguousWrite(run, action, result);
            return;
        }
        if (result.getStatus() == ToolStatus.FAILED || result.getStatus() == ToolStatus.UNAVAILABLE) {
            approvalManager.failExecution(run.getRunId(), action.getId(), result.getErrorCode(), result.getErrorMessage());
            fail(run, result.getErrorCode(), result.getErrorMessage()); return;
        }
        run.getSteps().stream().filter(step -> action.getToolName().equals(step.getToolName())).findFirst().ifPresent(step -> step.setStatus(StepStatus.COMPLETED));
        action.setPayload(result.getOutput()); run.getOutput().put("task", result.getOutput());
        run.getMetadata().put("approvedActionId", action.getId());
        run.getMetadata().put("approvedBy", "current-user");
        String taskId = taskId(result);
        approvalManager.completeExecution(run.getRunId(), action.getId(), taskId);
        Map<String, Object> taskTrace = provenance(action); taskTrace.put("runId", run.getRunId()); taskTrace.put("taskId", taskId == null ? "" : taskId); taskTrace.put("actor", "current-user");
        traceRecorder.record(run, "operations.task.created", taskTrace);
        traceRecorder.record(run, "action.executed", Map.of("actionId", action.getId(), "tool", action.getToolName()));
        complete(run, null);
    }

    /** A write timeout is not proof of non-commit. Query the authoritative task relation once. */
    private void reconcileAmbiguousWrite(AgentRun run, ProposedAction action, ToolResult result) {
        run.setRecoveryAttempted(true); run.setRecoveryOutcome("AMBIGUOUS");
        traceRecorder.record(run, "write.reconciliation.started", Map.of("actionId", action.getId(), "reason", "WRITE_OUTCOME_AMBIGUOUS"));
        if (operations != null) {
            try {
                Map<String, Object> task = operations.findTaskByActionId(action.getId());
                if (task != null) {
                    String taskId = task.get("taskId") == null ? null : String.valueOf(task.get("taskId"));
                    approvalManager.completeExecution(run.getRunId(), action.getId(), taskId);
                    action.setPayload(task); run.getOutput().put("task", task);
                    traceRecorder.record(run, "operations.task.recovered", Map.of("runId", run.getRunId(), "actionId", action.getId(), "taskId", taskId == null ? "" : taskId));
                    complete(run, null); return;
                }
            } catch (OperationsWriteException error) {
                traceRecorder.record(run, "write.reconciliation.failed", Map.of("actionId", action.getId(), "reason", "AUTHORITATIVE_QUERY_UNAVAILABLE"));
            }
        }
        approvalManager.failExecution(run.getRunId(), action.getId(), "WRITE_OUTCOME_AMBIGUOUS", "写操作结果未知，已拒绝自动重放");
        fail(run, "WRITE_OUTCOME_AMBIGUOUS", "写操作结果未知，已拒绝自动重放");
    }

    private String taskId(ToolResult result) {
        if (result == null) return null;
        if (result.getMetadata() != null && result.getMetadata().get("taskId") != null) return String.valueOf(result.getMetadata().get("taskId"));
        if (result.getOutput() != null && result.getOutput().get("taskId") != null) return String.valueOf(result.getOutput().get("taskId"));
        return null;
    }

    private void recoverCommittedTask(AgentRun run, ApprovalManager.Decision decision) {
        if (operations == null || decision.record() == null) return;
        ApprovalExecutionState executionState = decision.record().getExecutionState();
        if (executionState != ApprovalExecutionState.EXECUTING && executionState != ApprovalExecutionState.COMPLETED) return;
        if (executionState == ApprovalExecutionState.COMPLETED && run.getStatus() == RunStatus.COMPLETED) return;
        try {
            Map<String, Object> task = operations.findTaskByActionId(decision.action().getId());
            if (task == null) return;
            String taskId = task.get("taskId") == null ? null : String.valueOf(task.get("taskId"));
            approvalManager.completeExecution(run.getRunId(), decision.action().getId(), taskId);
            decision.action().setPayload(task);
            run.getOutput().put("task", task);
            traceRecorder.record(run, "operations.task.recovered", Map.of("runId", run.getRunId(), "actionId", decision.action().getId(), "taskId", taskId == null ? "" : taskId));
            complete(run, null);
        } catch (OperationsWriteException error) {
            throw ApprovalException.persistenceUnavailable(run.getRunId(), decision.action().getId(), error);
        }
    }

    public AgentRun cancel(String runId) {
        AgentRun run = require(runId); synchronized (run) { run.setCancellationRequested(true); transition(run, RunStatus.CANCELLED, "run.cancelled"); persist(run); return run; }
    }

    public AgentRun require(String runId) { return repository.find(runId).orElseThrow(() -> new IllegalArgumentException("Agent run 不存在: " + runId)); }
    public AgentRun require(String runId, Long userId) {
        if (userId == null) throw AgentAccessException.unauthenticated();
        AgentRun run = require(runId);
        if (run.getOwnerUserId() == null || !java.util.Objects.equals(run.getOwnerUserId(), userId)) throw AgentAccessException.forbidden();
        return run;
    }
    public AgentRun execute(String runId, Long userId) { require(runId, userId); return execute(runId); }
    public AgentRun approve(String runId, String actionId, Long userId) { require(runId, userId); return approve(runId, actionId); }
    public AgentRun reject(String runId, String actionId, Long userId) { require(runId, userId); return reject(runId, actionId); }
    public AgentRun cancel(String runId, Long userId) { require(runId, userId); return cancel(runId); }
    public List<AgentRun> list() { return repository.findAll(); }

    private void mergeEvidence(AgentRun run, List<DiagnosisEvidenceVO> evidence) {
        for (DiagnosisEvidenceVO item : evidence) if (item != null && run.getEvidence().stream().noneMatch(e -> item.getId() != null && item.getId().equals(e.getId()))) run.getEvidence().add(item);
    }

    /** Resolve only server-owned evidence before a write-capable step. */
    private EvidenceResolution resolveEvidence(AgentRun run) {
        List<String> unavailable = new ArrayList<>();
        boolean emptyObserved = run.getToolCalls().stream().anyMatch(this::isEmptyResult);
        run.getToolCalls().stream().filter(call -> call != null && call.getStatus() == ToolStatus.UNAVAILABLE)
                .filter(call -> run.getSteps().stream().filter(step -> step != null && step.getToolName() != null
                        && step.getToolName().equals(call.getToolName())).findFirst().map(AgentPlanStep::isRequired).orElse(true))
                .map(AgentToolCall::getToolName).filter(name -> name != null && !name.isBlank()).forEach(unavailable::add);
        String diagnosisId = diagnosisId(run);
        String recommendationId = recommendationId(run);
        boolean persistedLookupExpected = diagnosisPersistence != null && diagnosisId != null && !diagnosisId.isBlank()
                && !"mock".equalsIgnoreCase(run.getMode()) && !"a12-demo".equalsIgnoreCase(diagnosisId);
        List<String> recommendationEvidenceIds = persistedLookupExpected ? new ArrayList<>() : recommendationEvidenceFromContext(run);
        if (persistedLookupExpected) {
            try {
                java.util.Optional<DiagnosisAnalysisResponse> persisted = diagnosisPersistence.find(diagnosisId, run.getOwnerUserId());
                if (persisted.isPresent()) {
                    mergeEvidence(run, persisted.get().getEvidence());
                    if (recommendationId != null && !recommendationId.isBlank() && persisted.get().getRecommendations() != null) {
                        persisted.get().getRecommendations().stream().filter(item -> item != null && recommendationId.equals(item.getId())).findFirst()
                                .ifPresent(item -> { recommendationEvidenceIds.clear(); if (item.getEvidenceIds() != null) recommendationEvidenceIds.addAll(item.getEvidenceIds()); });
                        if (recommendationEvidenceIds.isEmpty()) unavailable.add("recommendation-snapshot");
                    }
                } else {
                    unavailable.add("diagnosis-snapshot");
                }
            } catch (RuntimeException error) {
                unavailable.add("diagnosis-persistence");
            }
        }
        return new EvidenceResolution(new ArrayList<>(run.getEvidence()), recommendationEvidenceIds, unavailable, emptyObserved);
    }

    private List<String> recommendationEvidenceFromContext(AgentRun run) {
        List<String> ids = new ArrayList<>();
        if (run.getContext() == null || run.getContext().getDiagnosis() == null) return ids;
        Map<String, Object> diagnosis = run.getContext().getDiagnosis();
        addStringValues(ids, diagnosis.get("recommendationEvidenceIds"));
        Object recommendation = diagnosis.get("recommendation");
        if (recommendation instanceof Map<?, ?> map) addStringValues(ids, map.get("evidenceIds"));
        Object recommendations = diagnosis.get("recommendations");
        if (recommendations instanceof Iterable<?> values) {
            for (Object value : values) if (value instanceof Map<?, ?> map) {
                if (recommendationId(run) == null || recommendationId(run).equals(String.valueOf(map.get("id")))) addStringValues(ids, map.get("evidenceIds"));
            }
        }
        return ids;
    }

    private void addStringValues(List<String> target, Object value) {
        if (value instanceof Iterable<?> values) {
            for (Object item : values) if (item != null && !String.valueOf(item).isBlank()) target.add(String.valueOf(item));
        } else if (value != null && !String.valueOf(value).isBlank()) {
            target.add(String.valueOf(value));
        }
    }

    private boolean isEmptyResult(AgentToolCall call) {
        if (call == null || call.getStatus() != ToolStatus.COMPLETED || call.getOutput() == null) return false;
        Object status = call.getOutput().get("status");
        return "EMPTY".equalsIgnoreCase(String.valueOf(status)) || "NO_KNOWLEDGE_AVAILABLE".equalsIgnoreCase(String.valueOf(status));
    }

    private record EvidenceResolution(List<DiagnosisEvidenceVO> evidence, List<String> recommendationEvidenceIds,
                                      List<String> unavailableDependencies, boolean emptyObserved) { }

    private void enrichFieldFacts(AgentContext context, String mode, Long userId) {
        if (fieldFactsService == null || context == null || userId == null
                || context.getFieldId() == null || context.getFieldId().isBlank()
                || "mock".equalsIgnoreCase(mode) || "evaluation".equalsIgnoreCase(mode)) return;
        FieldFacts facts = fieldFactsService.forField(context.getFieldId(), userId);
        if (facts.unknownCrop()) throw ProductLoopRelationException.validation("当前地块的作物暂不在支持目录中，无法启动 Agent", context.getDiagnosisId(), context.getSourceRecommendationId());
        if ("UNAVAILABLE".equals(facts.status())) throw ProductLoopRelationException.validation("当前地块事实暂不可用，无法启动 Agent", context.getDiagnosisId(), context.getSourceRecommendationId());
        if (facts.ready()) context.applyFieldFacts(facts);
    }

    private void complete(AgentRun run, Map<String, Object> prior) {
        Map<String, Object> output = new LinkedHashMap<>();
        if (prior != null) output.putAll(prior); else output.putAll(run.getOutput());
        output.put("evidenceCount", run.getEvidence().size());
        output.put("conclusion", "基于当前农场上下文和已验证证据，建议完成现场复核后执行农事处置。");
        addPhenologyOutput(run, output);
        output.put("confidence", run.getContext() != null && run.getContext().getDiagnosis() != null ? run.getContext().getDiagnosis().getOrDefault("confidence", 0.76) : 0.76);
        output.put("evidenceIds", run.getEvidence().stream().map(DiagnosisEvidenceVO::getId).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toList()));
        output.put("recommendations", List.of("检查地块排水与根区湿度", "48 小时内复拍异常叶片", "由专家复核后形成处置任务"));
        output.put("recommendation", "请依据证据完成现场复核");
        run.setOutput(output); transition(run, RunStatus.COMPLETED, "run.completed"); persist(run);
    }

    private void addPhenologyOutput(AgentRun run, Map<String, Object> output) {
        AgentContext context = run.getContext();
        if (context == null || !"READY".equals(context.getFieldFactsStatus())) return;
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("serverDate", context.getFieldFactsDate()); facts.put("crop", context.getCrop()); facts.put("cropLabel", context.getCropLabel());
        facts.put("cycleType", context.getPhenologyCycleType()); facts.put("current", context.getPhenologyStage()); facts.put("currentLabel", context.getPhenologyStageLabel());
        facts.put("registered", context.getRegisteredPhenologyStage()); facts.put("registeredLabel", context.getRegisteredPhenologyStageLabel());
        facts.put("dateDerived", context.getDateDerivedPhenologyStage()); facts.put("dateDerivedLabel", context.getDateDerivedPhenologyStageLabel());
        facts.put("previous", context.getPreviousPhenologyStage()); facts.put("previousLabel", context.getPreviousPhenologyStageLabel());
        facts.put("next", context.getNextPhenologyStage()); facts.put("nextLabel", context.getNextPhenologyStageLabel());
        facts.put("daysUntilNext", context.getDaysUntilNextPhenologyStage()); facts.put("stageMismatch", context.isPhenologyStageMismatch());
        facts.put("cycleEnded", context.isPhenologyCycleEnded()); facts.put("orderedStages", context.getOrderedPhenologyStages()); facts.put("sources", context.getFactSources());
        output.put("fieldFacts", facts);
        if (context.getCropLabel() == null || context.getPhenologyStageLabel() == null) return;
        String next = context.getNextPhenologyStageLabel();
        String suffix = next == null ? context.isPhenologyCycleEnded() ? "；本茬已结束，没有下一物候期" : "" : "；下一物候期为" + next;
        output.put("conclusion", "按【" + context.getCropLabel() + "·" + context.getPhenologyStageLabel() + "】的地块事实，当前 Agent 结论基于该作物物候历" + suffix + "。建议完成现场复核后执行农事处置。");
    }
    private Map<String, Object> toolInput(AgentRun run) {
        Map<String, Object> input = new LinkedHashMap<>(); input.put("goal", run.getGoal());
        if (run.getContext() != null) input.put("fieldId", run.getContext().getFieldId()); return input;
    }
    private String diagnosisId(AgentRun run) { return run.getContext() == null ? null : run.getContext().getDiagnosisId(); }
    private String recommendationId(AgentRun run) { return run.getContext() == null ? null : run.getContext().getSourceRecommendationId(); }
    private List<String> actionEvidenceIds(AgentRun run) {
        List<String> recommendationEvidence = recommendationEvidenceFromContext(run);
        if (recommendationId(run) != null && !recommendationEvidence.isEmpty()) return new ArrayList<>(new java.util.LinkedHashSet<>(recommendationEvidence));
        return run.getEvidence().stream().map(DiagnosisEvidenceVO::getId).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toList());
    }
    private Map<String, Object> provenance(ProposedAction action) {
        Map<String, Object> value = new LinkedHashMap<>(); value.put("actionId", action.getId());
        if (action.getDiagnosisId() != null) value.put("diagnosisId", action.getDiagnosisId());
        if (action.getRecommendationId() != null) value.put("recommendationId", action.getRecommendationId());
        return value;
    }
    private ProposedAction normalizeAction(AgentRun run, ProposedAction action) {
        if (action == null) throw ProductLoopRelationException.validation("AgentAction 不能为空", diagnosisId(run), recommendationId(run));
        String expectedDiagnosis = diagnosisId(run); String expectedRecommendation = recommendationId(run);
        if (action.getDiagnosisId() != null && !action.getDiagnosisId().equals(expectedDiagnosis))
            throw ProductLoopRelationException.conflict("AgentAction diagnosis provenance 不一致", expectedDiagnosis, action.getRecommendationId());
        if (action.getRecommendationId() != null && !action.getRecommendationId().equals(expectedRecommendation))
            throw ProductLoopRelationException.conflict("AgentAction recommendation provenance 不一致", expectedDiagnosis, action.getRecommendationId());
        action.setId("action-" + run.getRunId() + "-" + UUID.randomUUID());
        action.setDiagnosisId(expectedDiagnosis); action.setRecommendationId(expectedRecommendation);
        if (action.getEvidenceIds() == null) action.setEvidenceIds(new ArrayList<>());
        return action;
    }
    private void validateProvenance(AgentContext context, String mode, Long userId) {
        String diagnosisId = context.getDiagnosisId(); String recommendationId = context.getSourceRecommendationId();
        if (recommendationId != null && !recommendationId.isBlank() && (diagnosisId == null || diagnosisId.isBlank()))
            throw ProductLoopRelationException.validation("Recommendation provenance 必须同时携带 diagnosisId", diagnosisId, recommendationId);
        if (diagnosisPersistence == null || "mock".equalsIgnoreCase(mode) || "a12-demo".equals(diagnosisId)) return;
        if (diagnosisId == null || diagnosisId.isBlank()) return;
        diagnosisPersistence.buildAgentContext(diagnosisId, recommendationId, userId, context);
    }
    private AgentPlan copyPlan(AgentPlan source) {
        if (source == null) return AgentPlan.builder().steps(new ArrayList<>()).maxSteps(8).maxToolCalls(12).timeoutMs(120000).build();
        List<AgentPlanStep> steps = new ArrayList<>();
        if (source.getSteps() != null) for (AgentPlanStep step : source.getSteps()) steps.add(AgentReplanner.copyStep(step));
        return AgentPlan.builder().steps(steps).maxSteps(source.getMaxSteps()).maxToolCalls(source.getMaxToolCalls())
                .timeoutMs(source.getTimeoutMs()).plannerModel(source.getPlannerModel()).schemaVersion(source.getSchemaVersion()).build();
    }
    private void fail(AgentRun run, String code, String message) { run.setFailureCode(code == null ? "AGENT_RUNTIME_FAILED" : code); run.setFailureMessage(message); transition(run, RunStatus.FAILED, "run.failed"); persist(run); }
    private void transition(AgentRun run, RunStatus status, String event) { run.setStatus(status); traceRecorder.record(run, event, Map.of("status", status.name())); }
    private void persist(AgentRun run) {
        repository.save(run);
        if (run != null && (run.getStatus() == RunStatus.COMPLETED || run.getStatus() == RunStatus.FAILED
                || run.getStatus() == RunStatus.WAITING_FOR_APPROVAL || run.getStatus() == RunStatus.CANCELLED)) {
            traceRecorder.complete(run);
        }
    }
}
