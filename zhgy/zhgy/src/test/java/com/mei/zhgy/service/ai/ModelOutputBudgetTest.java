package com.mei.zhgy.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelOutputBudgetTest {
    private final BailianModelClient client = new BailianModelClient(new ObjectMapper());

    @Test
    void selectsTaskSpecificDefaultsAndHonorsGlobalCeiling() throws Exception {
        Method bounded = BailianModelClient.class.getDeclaredMethod("boundedMaxTokens", ModelCallRequest.class);
        bounded.setAccessible(true);

        assertEquals(2048, bounded.invoke(client, ModelCallRequest.builder().taskType(AiTaskType.COPILOT).build()));
        assertEquals(3072, bounded.invoke(client, ModelCallRequest.builder().taskType(AiTaskType.MULTIMODAL_DIAGNOSIS).build()));
        assertEquals(700, bounded.invoke(client, ModelCallRequest.builder().taskType(AiTaskType.IMAGE_UNDERSTANDING).build()));
        assertEquals(8192, bounded.invoke(client, ModelCallRequest.builder().taskType(AiTaskType.COPILOT)
                .context(Map.of("maxOutputTokens", 10000)).build()));
    }

    @Test
    void capturesProviderFinishReason() throws Exception {
        Method parse = BailianModelClient.class.getDeclaredMethod("parseResponse", String.class, ModelRole.class, String.class, long.class);
        parse.setAccessible(true);
        ModelCallResult result = (ModelCallResult) parse.invoke(client, "qwen", ModelRole.DEFAULT_MODEL,
                "{\"model\":\"qwen\",\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"length\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20,\"total_tokens\":30}}",
                1L);

        assertEquals("length", result.getUsage().getFinishReason());
    }
}
