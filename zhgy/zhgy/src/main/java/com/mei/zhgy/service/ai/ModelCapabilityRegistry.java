package com.mei.zhgy.service.ai;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** 三个逻辑模型角色的唯一能力注册表；业务层不散落模型字符串。 */
@Component
public class ModelCapabilityRegistry {
    /** Legacy compatibility alias; the active default is the dated Model Studio id below. */
    public static final String QWEN36_PLUS = "qwen-plus";
    /** Dated model id shown in the Model Studio quota console. */
    public static final String QWEN_PLUS_DATED = "qwen-plus-2025-07-28";
    /** Text fallback when the active account grants this model. */
    public static final String QWEN36_PLUS_FALLBACK = "qwen3.6-plus";
    public static final String QWEN_MAX = "qwen-max";
    public static final String QWEN_MT_FLASH = "qwen-mt-flash";
    public static final String QWEN_MATH_TURBO = "qwen-math-turbo";
    public static final String DEEPSEEK_R1_DISTILL_QWEN_7B = "deepseek-r1-distill-qwen-7b";
    public static final String GLM_5 = "glm-5";
    public static final String QWEN_VL_32B = "qwen3-vl-32b-thinking";
    public static final String QWEN_VL_30B = "qwen3-vl-30b-a3b-thinking";
    public static final String QWEN_VL_235B = "qwen3-vl-235b-a22b-thinking";

    private final Map<String, ModelDefinition> definitions = new LinkedHashMap<>();

    public ModelCapabilityRegistry() {
        definitions.put(QWEN36_PLUS, ModelDefinition.builder()
                .model(QWEN36_PLUS)
                .role(ModelRole.DEFAULT_MODEL)
                .multimodal(false)
                .capabilities(Arrays.asList("text", "evidence-synthesis", "grounded-generation", "copilot", "text-grounded-image-fallback"))
                .build());
        definitions.put(QWEN36_PLUS_FALLBACK, ModelDefinition.builder()
                .model(QWEN36_PLUS_FALLBACK)
                .role(ModelRole.DEFAULT_MODEL)
                .multimodal(false)
                .capabilities(Arrays.asList("text", "evidence-synthesis", "grounded-generation", "copilot"))
                .build());
        definitions.put(QWEN_PLUS_DATED, ModelDefinition.builder()
                .model(QWEN_PLUS_DATED)
                .role(ModelRole.DEFAULT_MODEL)
                .multimodal(false)
                .capabilities(Arrays.asList("text", "evidence-synthesis", "grounded-generation", "copilot"))
                .build());
        for (String model : Arrays.asList(QWEN_MAX, QWEN_MT_FLASH, QWEN_MATH_TURBO,
                DEEPSEEK_R1_DISTILL_QWEN_7B, GLM_5)) {
            definitions.put(model, ModelDefinition.builder()
                    .model(model)
                    .role(ModelRole.DEFAULT_MODEL)
                    .multimodal(false)
                    .capabilities(Arrays.asList("text", "grounded-generation", "copilot"))
                    .build());
        }
        definitions.put(QWEN_VL_32B, ModelDefinition.builder()
                .model(QWEN_VL_32B)
                .role(ModelRole.MULTIMODAL_REASONER)
                .multimodal(true)
                .capabilities(Arrays.asList("image-understanding", "multimodal-diagnosis", "case-comparison"))
                .build());
        definitions.put(QWEN_VL_30B, ModelDefinition.builder()
                .model(QWEN_VL_30B)
                .role(ModelRole.MULTIMODAL_REASONER)
                .multimodal(true)
                .capabilities(Arrays.asList("image-understanding", "multimodal-diagnosis", "case-comparison"))
                .build());
        definitions.put(QWEN_VL_235B, ModelDefinition.builder()
                .model(QWEN_VL_235B)
                .role(ModelRole.MULTIMODAL_EXPERT)
                .multimodal(true)
                .capabilities(Arrays.asList("hard-diagnosis", "expert-pre-review", "evidence-conflict-resolution"))
                .build());
    }

    public ModelDefinition get(String model) {
        return definitions.get(model);
    }

    public Collection<ModelDefinition> all() {
        return definitions.values();
    }
}
