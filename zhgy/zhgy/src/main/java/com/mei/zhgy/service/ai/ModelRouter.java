package com.mei.zhgy.service.ai;

import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 根据任务和复杂度选择模型，不使用随机或轮询。 */
@Service
public class ModelRouter {
    private final ModelRoutingPolicy routingPolicy;
    private final ModelEscalationPolicy escalationPolicy;
    private final ModelCapabilityRegistry capabilityRegistry;

    public ModelRouter(ModelRoutingPolicy routingPolicy,
                       ModelEscalationPolicy escalationPolicy,
                       ModelCapabilityRegistry capabilityRegistry) {
        this.routingPolicy = routingPolicy;
        this.escalationPolicy = escalationPolicy;
        this.capabilityRegistry = capabilityRegistry;
    }

    public ModelRoutingDecision route(AiTaskType taskType, ModelRoutingContext context) {
        String requested = routingPolicy.preferredModel(taskType, context);
        boolean escalated = false;
        String reason = "task_policy";
        if ("force_qwen36".equals(context == null ? null : context.getModelPolicy())) {
            requested = routingPolicy.getDefaultModel();
            reason = "forced_model_policy";
        } else if ("force_vl32".equals(context == null ? null : context.getModelPolicy())) {
            requested = routingPolicy.getMultimodalModel();
            reason = "forced_model_policy";
        } else if ("force_vl235".equals(context == null ? null : context.getModelPolicy())) {
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
        ModelDefinition definition = capabilityRegistry.get(requested);
        List<String> fallbackModels = fallbackModels(requested, taskType);
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
        if (routingPolicy.getExpertModel().equals(requested)) {
            return Arrays.asList(routingPolicy.getMultimodalModel(), routingPolicy.getDefaultModel());
        }
        if (routingPolicy.getMultimodalModel().equals(requested)) {
            return Collections.singletonList(routingPolicy.getDefaultModel());
        }
        if (taskType == AiTaskType.IMAGE_UNDERSTANDING || taskType == AiTaskType.MULTIMODAL_DIAGNOSIS) {
            return Collections.singletonList(routingPolicy.getMultimodalModel());
        }
        return Collections.emptyList();
    }
}
