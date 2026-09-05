package com.mei.zhgy.service.agent;

import com.mei.zhgy.vo.DiagnosisEvidenceVO;
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
    private final Executor executor = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "farmap-agent"); t.setDaemon(true); return t; });

    public AgentRuntime(AgentPlanner planner, ToolExecutor toolExecutor, AgentRunRepository repository,
                        TraceRecorder traceRecorder, ApprovalManager approvalManager) {
        this.planner = planner; this.toolExecutor = toolExecutor; this.repository = repository;
        this.traceRecorder = traceRecorder; this.approvalManager = approvalManager;
    }

    public AgentRun createRun(String goal, AgentContext context, String mode) {
        if (goal == null || goal.isBlank()) throw new IllegalArgumentException("goal 不能为空");
        Instant now = Instant.now();
        AgentRun run = AgentRun.builder().runId("agent-" + UUID.randomUUID()).goal(goal.trim())
                .context(context == null ? new AgentContext() : context).mode(mode == null ? "real" : mode)
                .status(RunStatus.CREATED).approvalState(ApprovalState.NONE).createdAt(now).updatedAt(now)
                .metadata(new LinkedHashMap<>(Map.of("runtime", "agent-core-v1", "toolPolicy", "read-auto-write-approval", "maxLoop", 12))).build();
        repository.save(run); traceRecorder.record(run, "run.created", Map.of("status", run.getStatus().name())); repository.save(run);
        return run;
    }

    public CompletableFuture<AgentRun> start(String runId) {
        return CompletableFuture.supplyAsync(() -> execute(runId), executor);
    }

    /** Synchronous entry point used by deterministic tests and recovery workers. */
    public AgentRun execute(String runId) {
        AgentRun run = require(runId);
        synchronized (run) {
            if (run.getStatus() == RunStatus.CANCELLED || run.getStatus() == RunStatus.COMPLETED) return run;
            transition(run, RunStatus.PLANNING, "run.planning");
            try {
                AgentPlan plan = planner.plan(run);
                run.setPlan(plan); run.setSteps(new ArrayList<>(plan.getSteps()));
                run.getMetadata().put("plannerModel", plan.getPlannerModel());
                traceRecorder.record(run, "plan.created", Map.of("steps", plan.getSteps().size(), "schemaVersion", plan.getSchemaVersion()));
                transition(run, RunStatus.RUNNING, "run.running");
                executeSteps(run);
            } catch (Exception error) {
                if (run.getStatus() != RunStatus.CANCELLED) fail(run, "AGENT_RUNTIME_FAILED", error.getMessage());
            }
            repository.save(run); return run;
        }
    }

    private void executeSteps(AgentRun run) {
        Map<String, Object> prior = new LinkedHashMap<>();
        int calls = 0;
        for (AgentPlanStep step : run.getSteps()) {
            if (run.isCancellationRequested()) { transition(run, RunStatus.CANCELLED, "run.cancelled"); return; }
            if (++calls > run.getPlan().getMaxToolCalls()) { fail(run, "MAX_TOOL_CALLS_EXCEEDED", "工具调用次数超过上限"); return; }
            if (Duration.between(run.getCreatedAt(), Instant.now()).toMillis() > run.getPlan().getTimeoutMs()) { fail(run, "AGENT_TIMEOUT", "Agent 运行超时"); return; }
            if (!step.isReadOnly()) {
                ProposedAction action = ProposedAction.builder().id("action-" + step.getId()).type("operations_task").title("创建农事复核任务")
                        .detail("由 Agent 根据当前证据创建 Operations 任务").toolName(step.getToolName()).approvalRequired(true)
                        .approvalState(ApprovalState.REQUIRED).payload(new LinkedHashMap<>(prior)).build();
                run.getActions().add(action); run.setApprovalState(ApprovalState.REQUIRED); step.setStatus(StepStatus.PENDING);
                traceRecorder.record(run, "approval.required", Map.of("actionId", action.getId(), "tool", step.getToolName()));
                transition(run, RunStatus.WAITING_FOR_APPROVAL, "run.waiting_for_approval");
                repository.save(run); return;
            }
            step.setStatus(StepStatus.RUNNING); traceRecorder.record(run, "step.started", Map.of("stepId", step.getId(), "tool", step.getToolName()));
            Instant startedAt = Instant.now();
            AgentToolCall call = AgentToolCall.builder().id("toolcall-" + UUID.randomUUID()).toolName(step.getToolName()).status(ToolStatus.RUNNING)
                    .input(toolInput(run)).startedAt(startedAt).build();
            run.getToolCalls().add(call);
            traceRecorder.record(run, "tool.started", Map.of("tool", step.getToolName(), "stepId", step.getId()));
            ToolResult result = toolExecutor.execute(step.getToolName(), AgentToolRequest.builder().runId(run.getRunId()).goal(run.getGoal()).context(run.getContext()).priorOutputs(prior).build());
            call.setStatus(result.getStatus()); call.setOutput(result.getOutput()); call.setErrorCode(result.getErrorCode()); call.setErrorMessage(result.getErrorMessage()); call.setCompletedAt(Instant.now());
            call.setDurationMs(Duration.between(startedAt, call.getCompletedAt()).toMillis());
            if (result.getOutput() != null) { prior.put(step.getToolName(), result.getOutput()); run.getOutput().put(step.getToolName(), result.getOutput()); }
            if (result.getEvidence() != null) {
                int before = run.getEvidence().size();
                mergeEvidence(run, result.getEvidence());
                if (run.getEvidence().size() > before) traceRecorder.record(run, "evidence.added", Map.of("tool", step.getToolName(), "count", run.getEvidence().size() - before));
            }
            if (result.getMetadata() != null) run.getMetadata().put(step.getToolName(), result.getMetadata());
            if (result.getActions() != null) run.getActions().addAll(result.getActions());
            traceRecorder.record(run, "tool.completed", Map.of("tool", step.getToolName(), "status", result.getStatus().name()));
            if (result.getStatus() == ToolStatus.FAILED) { step.setStatus(StepStatus.FAILED); fail(run, result.getErrorCode(), result.getErrorMessage()); return; }
            step.setStatus(result.getStatus() == ToolStatus.UNAVAILABLE ? StepStatus.SKIPPED : StepStatus.COMPLETED);
            traceRecorder.record(run, "step.completed", Map.of("stepId", step.getId(), "status", step.getStatus().name()));
            repository.save(run);
        }
        complete(run, prior);
    }

    public AgentRun approve(String runId, String actionId) {
        AgentRun run = require(runId); synchronized (run) {
            ProposedAction action = approvalManager.approve(run, actionId);
            traceRecorder.record(run, "approval.approved", Map.of("actionId", actionId));
            executeApprovedAction(run, action);
            return run;
        }
    }

    public AgentRun reject(String runId, String actionId) {
        AgentRun run = require(runId); synchronized (run) {
            ProposedAction action = approvalManager.reject(run, actionId);
            traceRecorder.record(run, "approval.rejected", Map.of("actionId", actionId));
            if (run.getActions().stream().allMatch(a -> a.getApprovalState() != ApprovalState.REQUIRED)) complete(run, null);
            repository.save(run); return run;
        }
    }

    private void executeApprovedAction(AgentRun run, ProposedAction action) {
        transition(run, RunStatus.RUNNING, "run.resumed");
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("evidenceIds", run.getEvidence().stream().map(DiagnosisEvidenceVO::getId).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toList()));
        AgentToolRequest request = AgentToolRequest.builder().runId(run.getRunId()).goal(run.getGoal()).context(run.getContext()).approvalGranted(true).attributes(attributes).priorOutputs(run.getOutput()).build();
        Instant startedAt = Instant.now();
        ToolResult result = toolExecutor.execute(action.getToolName(), request);
        AgentToolCall call = AgentToolCall.builder().id("toolcall-" + UUID.randomUUID()).toolName(action.getToolName()).status(result.getStatus())
                .input(toolInput(run)).output(result.getOutput()).errorCode(result.getErrorCode()).errorMessage(result.getErrorMessage())
                .startedAt(startedAt).completedAt(Instant.now()).durationMs(Duration.between(startedAt, Instant.now()).toMillis()).build();
        run.getToolCalls().add(call);
        traceRecorder.record(run, "tool.completed", Map.of("tool", action.getToolName(), "status", result.getStatus().name(), "approvalGranted", true));
        if (result.getStatus() == ToolStatus.FAILED || result.getStatus() == ToolStatus.UNAVAILABLE) { fail(run, result.getErrorCode(), result.getErrorMessage()); return; }
        run.getSteps().stream().filter(step -> action.getToolName().equals(step.getToolName())).findFirst().ifPresent(step -> step.setStatus(StepStatus.COMPLETED));
        action.setPayload(result.getOutput()); run.getOutput().put("task", result.getOutput());
        run.getMetadata().put("approvedActionId", action.getId());
        run.getMetadata().put("approvedBy", "current-user");
        traceRecorder.record(run, "action.executed", Map.of("actionId", action.getId(), "tool", action.getToolName()));
        complete(run, null);
    }

    public AgentRun cancel(String runId) {
        AgentRun run = require(runId); synchronized (run) { run.setCancellationRequested(true); transition(run, RunStatus.CANCELLED, "run.cancelled"); repository.save(run); return run; }
    }

    public AgentRun require(String runId) { return repository.find(runId).orElseThrow(() -> new IllegalArgumentException("Agent run 不存在: " + runId)); }
    public List<AgentRun> list() { return repository.findAll(); }

    private void mergeEvidence(AgentRun run, List<DiagnosisEvidenceVO> evidence) {
        for (DiagnosisEvidenceVO item : evidence) if (item != null && run.getEvidence().stream().noneMatch(e -> item.getId() != null && item.getId().equals(e.getId()))) run.getEvidence().add(item);
    }
    private void complete(AgentRun run, Map<String, Object> prior) {
        Map<String, Object> output = new LinkedHashMap<>();
        if (prior != null) output.putAll(prior); else output.putAll(run.getOutput());
        output.put("evidenceCount", run.getEvidence().size());
        output.put("conclusion", "基于当前农场上下文和已验证证据，建议完成现场复核后执行农事处置。");
        output.put("confidence", run.getContext() != null && run.getContext().getDiagnosis() != null ? run.getContext().getDiagnosis().getOrDefault("confidence", 0.76) : 0.76);
        output.put("evidenceIds", run.getEvidence().stream().map(DiagnosisEvidenceVO::getId).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toList()));
        output.put("recommendations", List.of("检查地块排水与根区湿度", "48 小时内复拍异常叶片", "由专家复核后形成处置任务"));
        output.put("recommendation", "请依据证据完成现场复核");
        run.setOutput(output); transition(run, RunStatus.COMPLETED, "run.completed"); repository.save(run);
    }
    private Map<String, Object> toolInput(AgentRun run) {
        Map<String, Object> input = new LinkedHashMap<>(); input.put("goal", run.getGoal());
        if (run.getContext() != null) input.put("fieldId", run.getContext().getFieldId()); return input;
    }
    private void fail(AgentRun run, String code, String message) { run.setFailureCode(code == null ? "AGENT_RUNTIME_FAILED" : code); run.setFailureMessage(message); transition(run, RunStatus.FAILED, "run.failed"); repository.save(run); }
    private void transition(AgentRun run, RunStatus status, String event) { run.setStatus(status); traceRecorder.record(run, event, Map.of("status", status.name())); }
}
