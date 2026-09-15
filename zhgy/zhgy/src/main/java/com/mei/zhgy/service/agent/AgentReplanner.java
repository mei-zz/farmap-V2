package com.mei.zhgy.service.agent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic bounded replanning policy. It may only select registry-declared
 * safe alternatives and only mutates the not-yet-executed suffix supplied by
 * the runtime.
 */
public final class AgentReplanner {
    private final ToolExecutor toolExecutor;
    private final AgentReplanningPolicy policy;

    public AgentReplanner(ToolExecutor toolExecutor, AgentReplanningPolicy policy) {
        this.toolExecutor = toolExecutor;
        this.policy = policy == null ? new AgentReplanningPolicy() : policy;
    }

    public ReplanDecision decide(AgentRun run, AgentPlanStep triggerStep, ToolResult result,
                                 ReplanTrigger trigger, List<AgentPlanStep> remainingSteps) {
        List<AgentPlanStep> remaining = copySteps(remainingSteps);
        String targetId = triggerStep == null ? null : triggerStep.getId();

        if (trigger == ReplanTrigger.OPTIONAL_SOURCE_UNAVAILABLE
                || trigger == ReplanTrigger.EMPTY_OPTIONAL_SOURCE
                || trigger == ReplanTrigger.STEP_NO_LONGER_NEEDED
                || trigger == ReplanTrigger.ALREADY_SATISFIED) {
            remaining.removeIf(step -> triggerStep != null && !step.isRequired()
                    && dependsOn(step, triggerStep.getId()));
            return ReplanDecision.builder().decision(remaining.size() == (remainingSteps == null ? 0 : remainingSteps.size())
                            ? ReplanDecisionType.SKIP_STEP : ReplanDecisionType.SHORTEN_PLAN)
                    .trigger(trigger).reasonCode(reasonCode(trigger)).targetStepId(targetId)
                    .remainingSteps(remaining).rationale("跳过不再必要或不可用的可选步骤").build();
        }

        if (trigger == ReplanTrigger.RECOVERY_EXHAUSTED || trigger == ReplanTrigger.SAFE_FALLBACK_FAILED
                || trigger == ReplanTrigger.EMPTY_REQUIRED_SOURCE || trigger == ReplanTrigger.REQUIRED_SOURCE_UNAVAILABLE) {
            String replacement = triggerStep == null ? null : firstSafeAlternative(run, triggerStep.getToolName());
            if (replacement != null && trigger != ReplanTrigger.SAFE_FALLBACK_FAILED) {
                AgentPlanStep replacementStep = replacementStep(triggerStep, replacement,
                        run == null ? 1 : run.getReplanCount() + 1);
                remaining.add(0, replacementStep);
                return ReplanDecision.builder().decision(ReplanDecisionType.REPLACE_STEP).trigger(trigger)
                        .reasonCode(trigger == ReplanTrigger.RECOVERY_EXHAUSTED ? "ALTERNATIVE_AVAILABLE" : reasonCode(trigger))
                        .targetStepId(targetId).replacementTool(replacement).remainingSteps(remaining)
                        .rationale("使用 registry 声明的同 capability 只读替代工具").build();
            }
            return ReplanDecision.builder().decision(ReplanDecisionType.TERMINATE).trigger(trigger)
                    .reasonCode(replacement == null ? "NO_SAFE_ALTERNATIVE" : "SAFE_FALLBACK_FAILED")
                    .targetStepId(targetId).remainingSteps(List.of()).rationale("没有安全的剩余计划可执行").build();
        }

        return ReplanDecision.builder().decision(ReplanDecisionType.KEEP_PLAN).trigger(trigger)
                .reasonCode("PLAN_ALREADY_SUFFICIENT").targetStepId(targetId).remainingSteps(remaining)
                .rationale("当前计划仍然满足目标").build();
    }

    /** Server-side validation; a candidate cannot introduce a tool or mutate completed history. */
    public void validate(AgentRun run, ReplanDecision decision, List<AgentPlanStep> oldRemaining) {
        if (decision == null || decision.getDecision() == null) throw new IllegalArgumentException("REPLAN_DECISION_MISSING");
        if (decision.isModelCall()) throw new IllegalArgumentException("REPLAN_MODEL_CALL_NOT_ALLOWED");
        List<AgentPlanStep> next = decision.getRemainingSteps() == null ? List.of() : decision.getRemainingSteps();
        int oldSize = oldRemaining == null ? 0 : oldRemaining.size();
        if (next.size() > oldSize + 1) throw new IllegalArgumentException("REPLAN_STEP_BUDGET_EXCEEDED");
        int prefixSize = run == null || run.getSteps() == null ? 0 : run.getSteps().size() - oldSize;
        if (run != null && run.getPlan() != null && prefixSize + next.size() > run.getPlan().getMaxSteps())
            throw new IllegalArgumentException("REPLAN_MAX_STEPS_EXCEEDED");
        java.util.Map<String, AgentPlanStep> oldById = new java.util.LinkedHashMap<>();
        if (oldRemaining != null) for (AgentPlanStep step : oldRemaining) if (step != null && step.getId() != null) oldById.put(step.getId(), step);

        Set<String> completed = new HashSet<>();
        if (run != null && run.getSteps() != null) {
            run.getSteps().stream().filter(step -> step != null && step.getStatus() == StepStatus.COMPLETED)
                    .map(AgentPlanStep::getId).filter(id -> id != null && !id.isBlank()).forEach(completed::add);
        }
        Set<String> ids = new HashSet<>();
        for (AgentPlanStep step : next) {
            if (step == null || step.getId() == null || step.getId().isBlank() || !ids.add(step.getId()))
                throw new IllegalArgumentException("REPLAN_STEP_ID_INVALID");
            if (completed.contains(step.getId())) throw new IllegalArgumentException("REPLAN_COMPLETED_STEP_MUTATION");
            if (!toolExecutor.isRegistered(step.getToolName())) throw new IllegalArgumentException("REPLAN_TOOL_NOT_REGISTERED");
            AgentPlanStep original = oldById.get(step.getId());
            if (original != null && (!java.util.Objects.equals(original.getToolName(), step.getToolName())
                    || original.isReadOnly() != step.isReadOnly() || original.isRequired() != step.isRequired()))
                throw new IllegalArgumentException("REPLAN_PENDING_STEP_MUTATION");
            if (original == null && !java.util.Objects.equals(decision.getReplacementTool(), step.getToolName()))
                throw new IllegalArgumentException("REPLAN_UNDECLARED_NEW_STEP");
        }
        if (decision.getReplacementTool() != null) {
            AgentPlanStep source = run == null || run.getSteps() == null ? null : run.getSteps().stream()
                    .filter(step -> step != null && java.util.Objects.equals(step.getId(), decision.getTargetStepId()))
                    .findFirst().orElse(null);
            if (!toolExecutor.isRegistered(decision.getReplacementTool())
                    || !toolExecutor.isReadOnly(decision.getReplacementTool())
                    || source == null || !toolExecutor.isDeclaredSafeAlternative(source.getToolName(), decision.getReplacementTool()))
                throw new IllegalArgumentException("REPLAN_UNSAFE_TOOL_REPLACEMENT");
        }
        if (decision.getDecision() == ReplanDecisionType.REPLACE_STEP && decision.getReplacementTool() == null)
            throw new IllegalArgumentException("REPLAN_REPLACEMENT_MISSING");
        if (policy.maxReplans() < 1) throw new IllegalArgumentException("REPLAN_DISABLED");
    }

    private String firstSafeAlternative(AgentRun run, String toolName) {
        List<String> alternatives = toolExecutor.safeAlternatives(toolName);
        if (alternatives == null) return null;
        return alternatives.stream().filter(candidate -> !(run != null && "real".equalsIgnoreCase(run.getMode())
                && candidate != null && candidate.toLowerCase(java.util.Locale.ROOT).contains("demo"))).findFirst().orElse(null);
    }

    private AgentPlanStep replacementStep(AgentPlanStep source, String toolName, int version) {
        return AgentPlanStep.builder().id(source.getId() + "-replan-" + version)
                .title(source.getTitle()).description(source.getDescription()).toolName(toolName)
                .readOnly(true).required(source.isRequired()).dependsOn(new ArrayList<>(source.getDependsOn()))
                .status(StepStatus.PENDING).build();
    }

    private boolean dependsOn(AgentPlanStep step, String id) {
        return id != null && step.getDependsOn() != null && step.getDependsOn().contains(id);
    }

    private String reasonCode(ReplanTrigger trigger) {
        return switch (trigger) {
            case RECOVERY_EXHAUSTED -> "RECOVERY_EXHAUSTED";
            case SAFE_FALLBACK_FAILED -> "SAFE_FALLBACK_FAILED";
            case EMPTY_REQUIRED_SOURCE -> "EMPTY_REQUIRED_SOURCE";
            case EMPTY_OPTIONAL_SOURCE -> "EMPTY_OPTIONAL_SOURCE";
            case REQUIRED_SOURCE_UNAVAILABLE -> "REQUIRED_SOURCE_UNAVAILABLE";
            case OPTIONAL_SOURCE_UNAVAILABLE -> "OPTIONAL_SOURCE_UNAVAILABLE";
            case STEP_NO_LONGER_NEEDED -> "STEP_NO_LONGER_NEEDED";
            case ALREADY_SATISFIED -> "PLAN_ALREADY_SUFFICIENT";
        };
    }

    private List<AgentPlanStep> copySteps(List<AgentPlanStep> source) {
        List<AgentPlanStep> result = new ArrayList<>();
        if (source != null) for (AgentPlanStep step : source) result.add(copyStep(step));
        return result;
    }

    static AgentPlanStep copyStep(AgentPlanStep source) {
        if (source == null) return null;
        return AgentPlanStep.builder().id(source.getId()).title(source.getTitle()).description(source.getDescription())
                .toolName(source.getToolName()).readOnly(source.isReadOnly()).required(source.isRequired())
                .dependsOn(source.getDependsOn() == null ? new ArrayList<>() : new ArrayList<>(source.getDependsOn()))
                .status(source.getStatus() == null ? StepStatus.PENDING : source.getStatus()).build();
    }
}
