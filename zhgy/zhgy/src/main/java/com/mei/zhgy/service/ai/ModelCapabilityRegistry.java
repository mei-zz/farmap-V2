package com.mei.zhgy.service.ai;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** 三个逻辑模型角色的唯一能力注册表；业务层不散落模型字符串。 */
@Component
public class ModelCapabilityRegistry {
    public static final String QWEN36_PLUS = "qwen3.6-plus";
    public static final String QWEN_VL_32B = "qwen3-vl-32b-thinking";
    public static final String QWEN_VL_235B = "qwen3-vl-235b-a22b-thinking";

    private final Map<String, ModelDefinition> definitions = new LinkedHashMap<>();

    public ModelCapabilityRegistry() {
        definitions.put(QWEN36_PLUS, ModelDefinition.builder()
                .model(QWEN36_PLUS)
                .role(ModelRole.DEFAULT_MODEL)
                .multimodal(true)
                .capabilities(Arrays.asList("text", "basic-image-understanding", "evidence-synthesis", "grounded-generation", "copilot"))
                .build());
        definitions.put(QWEN_VL_32B, ModelDefinition.builder()
                .model(QWEN_VL_32B)
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
