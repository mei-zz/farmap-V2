package com.mei.zhgy.service.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Task-aware routing policy for the three Bailian Qwen models. */
@Component
public class ModelRoutingPolicy {
    @Value("${farmap.ai.router.enabled:true}")
    private boolean enabled = true;

    @Value("${farmap.ai.router.default-model:qwen3.6-plus}")
    private String defaultModel = ModelCapabilityRegistry.QWEN36_PLUS;

    @Value("${farmap.ai.router.multimodal-model:qwen3-vl-32b-thinking}")
    private String multimodalModel = ModelCapabilityRegistry.QWEN_VL_32B;

    @Value("${farmap.ai.router.expert-model:qwen3-vl-235b-a22b-thinking}")
    private String expertModel = ModelCapabilityRegistry.QWEN_VL_235B;

    public boolean isEnabled() {
        return enabled;
    }

    public String preferredModel(AiTaskType taskType, ModelRoutingContext context) {
        if (!enabled || taskType == null) {
            return defaultModel;
        }
        switch (taskType) {
            case MULTIMODAL_DIAGNOSIS:
                return multimodalModel;
            case HARD_DIAGNOSIS:
                return expertModel;
            case EXPERT_PRE_REVIEW:
                return context != null && context.isEvidenceConflict()
                        ? expertModel
                        : multimodalModel;
            case IMAGE_UNDERSTANDING:
                return context != null && (context.getImageCount() >= 3 || context.isExplicitDeepAnalysis())
                        ? multimodalModel
                        : defaultModel;
            case COPILOT:
            case FIELD_SUMMARY:
            case EVIDENCE_SYNTHESIS:
            case GROUNDED_GENERATION:
            case FUTURE_AGENT_TOOL_DECISION:
            default:
                return defaultModel;
        }
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public String getMultimodalModel() {
        return multimodalModel;
    }

    public String getExpertModel() {
        return expertModel;
    }
}
