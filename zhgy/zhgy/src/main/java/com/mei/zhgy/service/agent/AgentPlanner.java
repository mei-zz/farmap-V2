package com.mei.zhgy.service.agent;

import com.mei.zhgy.service.ai.ModelGateway;
import com.mei.zhgy.service.ai.AiTaskType;
import com.mei.zhgy.service.ai.ModelCallRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Produces a typed, bounded plan. Tool selection never depends on parsing free text. */
@Service
@Slf4j
public class AgentPlanner {
    private final ModelGateway modelGateway;
    private final boolean modelEnabled;
    private final int maxSteps;
    private final int maxToolCalls;
    private final long timeoutMs;
    @Value("${farmap.ai.router.default-model:qwen-plus-2025-07-28}")
    private String plannerModel = "qwen-plus-2025-07-28";

    public AgentPlanner(ModelGateway modelGateway,
                        @Value("${farmap.agent.planner.model-enabled:true}") boolean modelEnabled,
                        @Value("${farmap.agent.max-steps:8}") int maxSteps,
                        @Value("${farmap.agent.max-tool-calls:12}") int maxToolCalls,
                        @Value("${farmap.agent.timeout-ms:120000}") long timeoutMs) {
        this.modelGateway = modelGateway;
        this.modelEnabled = modelEnabled;
        this.maxSteps = Math.max(1, maxSteps);
        this.maxToolCalls = Math.max(1, maxToolCalls);
        this.timeoutMs = Math.max(1000, timeoutMs);
    }

    public AgentPlan plan(AgentRun run) {
        AgentContext context = run.getContext() == null ? new AgentContext() : run.getContext();
        // A persisted diagnosis already contains the typed evidence snapshot
        // that determines the plan. Avoid a second planner-model round trip
        // here; it used to make the Agent appear frozen for 40-60 seconds and
        // could not change the deterministic tool chain below.
        if (modelEnabled && (context.getDiagnosisId() == null || context.getDiagnosisId().isBlank())) {
            try {
                java.util.Map<String, Object> plannerContext = new java.util.LinkedHashMap<>(); plannerContext.put("fieldId", context.getFieldId()); plannerContext.put("imageCount", context.imageCount());
                modelGateway.complete(ModelCallRequest.builder().requestId("planner-" + run.getRunId()).runId(run.getRunId())
                        .taskType(AiTaskType.FUTURE_AGENT_TOOL_DECISION).systemPrompt("Return a JSON AgentPlan matching agent-plan-v1; never return prose.")
                        .userPrompt(run.getGoal()).structuredOutput(true).context(plannerContext).build());
            } catch (Exception error) {
                log.warn("Agent planner model unavailable; deterministic schema plan retained: {}", error.getClass().getSimpleName());
            }
        }
        List<AgentPlanStep> steps = new ArrayList<>();
        add(steps, "context", "获取地块上下文", "读取农场、地块、作物和物候信息", "field-context", true);
        add(steps, "weather", "获取气象数据", "读取当前页面注入的天气与未来 24 小时风险", "weather", true);
        if (context.imageCount() > 0 || containsAny(run.getGoal(), "叶片", "图像", "相机", "视觉")) {
            add(steps, "camera", "获取监控图像", "读取用户已选择的图像证据", "camera", true);
        }
        add(steps, "gis", "获取空间上下文", "读取地块位置、边界和来源模式", "gis", true);
        // A persisted Diagnosis already owns the grounded snapshot. The Agent
        // consumes that snapshot and must not rerun RAG or drift its provenance.
        if (context.getDiagnosisId() == null || context.getDiagnosisId().isBlank()) {
            add(steps, "rag", "多模态检索", "复用现有 DiagnosisRagService 检索证据", "multimodal-rag", true);
            add(steps, "history", "检索历史案例", "读取专家案例；向量为空时继续使用其他证据", "historical-case", true, false);
            add(steps, "knowledge", "检索农业知识", "读取本地农业知识库", "knowledge", true);
        } else {
            add(steps, "diagnosis", "读取诊断证据", "复用已保存的多模态 Evidence 与 Recommendation，不重复生成诊断", "diagnosis-evidence", true);
        }
        add(steps, "task", "生成后续农事任务", "提出可执行任务，提交前需要人工审批", "task", false);
        if (steps.size() > maxSteps) steps = new ArrayList<>(steps.subList(0, maxSteps));
        String previousRequiredStep = null;
        for (AgentPlanStep step : steps) {
            step.setDependsOn(previousRequiredStep == null ? new ArrayList<>() : List.of(previousRequiredStep));
            if (step.isRequired()) previousRequiredStep = step.getId();
        }
        AgentPlan plan = AgentPlan.builder().steps(steps).maxSteps(maxSteps).maxToolCalls(maxToolCalls)
                .timeoutMs(timeoutMs).plannerModel(plannerModel).schemaVersion("agent-plan-v1").build();
        return plan;
    }

    private void add(List<AgentPlanStep> steps, String id, String title, String description, String tool, boolean readOnly) {
        add(steps, id, title, description, tool, readOnly, true);
    }
    private void add(List<AgentPlanStep> steps, String id, String title, String description, String tool, boolean readOnly, boolean required) {
        steps.add(AgentPlanStep.builder().id(id).title(title).description(description).toolName(tool).readOnly(readOnly).required(required).status(StepStatus.PENDING).build());
    }
    private boolean containsAny(String value, String... words) {
        if (value == null) return false;
        return Arrays.stream(words).anyMatch(value::contains);
    }
}
