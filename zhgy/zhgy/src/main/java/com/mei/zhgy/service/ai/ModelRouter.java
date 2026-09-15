package com.mei.zhgy.service.ai;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Arrays;
import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** 根据任务和复杂度选择模型，并为纯文字任务提供可配置的候选池。 */
@Service
public class ModelRouter {
    private final ModelRoutingPolicy routingPolicy;
    private final ModelEscalationPolicy escalationPolicy;
    private final ModelCapabilityRegistry capabilityRegistry;
    private final AnswerModelPool answerModelPool;

    @Autowired
    public ModelRouter(ModelRoutingPolicy routingPolicy,
                       ModelEscalationPolicy escalationPolicy,
                       ModelCapabilityRegistry capabilityRegistry,
                       AnswerModelPool answerModelPool) {
        this.routingPolicy = routingPolicy;
        this.escalationPolicy = escalationPolicy;
        this.capabilityRegistry = capabilityRegistry;
        this.answerModelPool = answerModelPool;
    }

    /** Kept for existing unit-test callers and compatibility with old wiring. */
    public ModelRouter(ModelRoutingPolicy routingPolicy,
                       ModelEscalationPolicy escalationPolicy,
                       ModelCapabilityRegistry capabilityRegistry) {
        this(routingPolicy, escalationPolicy, capabilityRegistry, null);
    }

    public ModelRoutingDecision route(AiTaskType taskType, ModelRoutingContext context) {
        String requested = routingPolicy.preferredModel(taskType, context);
        boolean escalated = false;
        String reason = "task_policy";
        String modelPolicy = context == null ? null : context.getModelPolicy();
        boolean forced = modelPolicy != null && modelPolicy.startsWith("force_");
        if ("force_fast".equals(modelPolicy)) {
            requested = routingPolicy.getFastModel();
            reason = "fast_conversational_policy";
        } else if ("force_qwen36".equals(modelPolicy)) {
            requested = routingPolicy.getDefaultModel();
            reason = "forced_model_policy";
        } else if ("force_vl32".equals(modelPolicy)) {
            requested = routingPolicy.getMultimodalModel();
            reason = "forced_model_policy";
        } else if ("force_vl235".equals(modelPolicy)) {
            requested = routingPolicy.getExpertModel();
            reason = "forced_model_policy";
        } else if (taskType == AiTaskType.MULTIMODAL_DIAGNOSIS
                && escalationPolicy.shouldEscalate(context)) {
            requested = routingPolicy.getExpertModel();
            escalated = true;
            reason = escalationPolicy.reason(context);
        } else if (taskType == AiTaskType.EXPERT_PRE_REVIEW && escalationPolicy.shouldEscalate(context)) {
            requested = routingPolicy.getExpertModel();
            escalated = true;
            reason = escalationPolicy.reason(context);
        } else if (taskType == AiTaskType.MULTIMODAL_DIAGNOSIS) {
            reason = "multimodal_diagnosis";
        } else if (taskType == AiTaskType.HARD_DIAGNOSIS) {
                reason = "hard_diagnosis";
            escalated = true;
        }

        String originalRequested = requested;
        List<String> fallbackModels;
        List<String> ordered = answerModelPool == null || forced || escalated
                ? Collections.emptyList()
                : answerModelPool.orderedCandidates(taskType);
        if (!ordered.isEmpty()) {
            requested = ordered.get(0);
            LinkedHashSet<String> candidates = new LinkedHashSet<>();
            int samePoolLimit = Math.min(answerModelPool.samePoolFallbackN(), Math.max(0, ordered.size() - 1));
            for (int index = 1; index <= samePoolLimit; index++) {
                candidates.add(ordered.get(index));
            }
            candidates.addAll(fallbackModels(originalRequested, taskType));
            candidates.remove(requested);
            fallbackModels = new ArrayList<>(candidates);
            reason = reason + ";answer_pool:" + answerModelPool.selectionStrategy();
        } else {
            fallbackModels = fallbackModels(originalRequested, taskType).stream()
                    .filter(model -> !originalRequested.equals(model))
                    .distinct()
                    .toList();
        }
        ModelDefinition definition = capabilityRegistry.get(requested);
        return ModelRoutingDecision.builder()
                .taskType(taskType)
                .requestedModel(requested)
                .modelRole(definition == null ? ModelRole.DEFAULT_MODEL : definition.getRole())
                .routingReason(reason)
                .escalated(escalated)
                .fallbackModels(fallbackModels)
                .build();
    }

    private List<String> fallbackModels(String requested, AiTaskType taskType) {
        if (routingPolicy.getFastModel().equals(requested)) {
            return Arrays.asList(ModelCapabilityRegistry.QWEN_PLUS_DATED,
                    ModelCapabilityRegistry.QWEN_MAX,
                    ModelCapabilityRegistry.GLM_5);
        }
        if (ModelCapabilityRegistry.QWEN_PLUS_DATED.equals(requested)) {
            return Arrays.asList(ModelCapabilityRegistry.QWEN_MAX,
                    ModelCapabilityRegistry.QWEN_MT_FLASH,
                    ModelCapabilityRegistry.QWEN36_PLUS_FALLBACK);
        }
        if (routingPolicy.getExpertModel().equals(requested)) {
            return Arrays.asList(routingPolicy.getMultimodalModel(),
                    ModelCapabilityRegistry.QWEN_VL_30B,
                    ModelCapabilityRegistry.QWEN_PLUS_DATED,
                    ModelCapabilityRegistry.QWEN36_PLUS);
        }
        if (routingPolicy.getMultimodalModel().equals(requested)) {
            // A diagnosis must remain executable when the VL entitlement is
            // missing. The dated Qwen Plus model is the first grounded text
            // fallback, followed by the compatibility alias.
            return Arrays.asList(ModelCapabilityRegistry.QWEN_VL_30B,
                    ModelCapabilityRegistry.QWEN_PLUS_DATED,
                    ModelCapabilityRegistry.QWEN36_PLUS,
                    ModelCapabilityRegistry.QWEN_MAX);
        }
        if (taskType == AiTaskType.IMAGE_UNDERSTANDING || taskType == AiTaskType.MULTIMODAL_DIAGNOSIS) {
            return Arrays.asList(routingPolicy.getMultimodalModel(),
                    ModelCapabilityRegistry.QWEN_VL_30B,
                    ModelCapabilityRegistry.QWEN_PLUS_DATED,
                    ModelCapabilityRegistry.QWEN36_PLUS);
        }
        if (routingPolicy.getDefaultModel().equals(requested)) {
            return Arrays.asList(ModelCapabilityRegistry.QWEN36_PLUS,
                    ModelCapabilityRegistry.QWEN36_PLUS_FALLBACK,
                    ModelCapabilityRegistry.GLM_5,
                    ModelCapabilityRegistry.DEEPSEEK_R1_DISTILL_QWEN_7B);
        }
        return Collections.emptyList();
    }
}
