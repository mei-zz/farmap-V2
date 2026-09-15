package com.mei.zhgy.service.copilot.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.mei.zhgy.properties.BailianProperties;
import com.mei.zhgy.service.ai.AiTaskType;
import com.mei.zhgy.service.ai.BailianModelClient;
import com.mei.zhgy.service.ai.ModelCallRequest;
import com.mei.zhgy.service.ai.ModelCallResult;
import com.mei.zhgy.service.ai.ModelRole;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * Explicit, opt-in connectivity probe for the specialized intent model. It
 * never runs in an account without an API key and never prints the credential.
 */
class IntentModelConnectivityProbeTest {
    @Test
    void probeSpecializedModelChatContract() {
        String key = firstNonBlank(System.getenv("BAILIAN_API_KEY"), System.getenv("DASHSCOPE_API_KEY"), System.getenv("APIKEY"));
        Assumptions.assumeTrue(key != null && !key.isBlank(), "intent model probe skipped: no Bailian API key in environment");

        BailianProperties properties = new BailianProperties();
        properties.getApi().setKey(key);
        properties.getApi().setUrl(firstNonBlank(System.getenv("BAILIAN_API_BASE_URL"), System.getenv("DASHSCOPE_BASE_URL"), null));
        properties.getApi().setConnectTimeoutMs(1000);
        properties.getApi().setReadTimeoutMs(1200);
        properties.getApi().setMaxRetries(0);
        ObjectMapper mapper = new ObjectMapper();
        BailianModelClient client = new BailianModelClient(mapper, properties);
        ModelCallRequest request = ModelCallRequest.builder()
                .taskType(AiTaskType.COPILOT)
                .systemPrompt("只输出一个 JSON 对象，字段为 intent、confidence、slots、needsClarification、multiIntent。")
                .userPrompt("将这句话分类：脆李叶子发黄是怎么回事？")
                .structuredOutput(true)
                .context(Map.of("maxOutputTokens", 300, "enableThinking", false, "temperature", 0D))
                .build();
        try {
            ModelCallResult result = client.call("tongyi-intent-detect-v3", request, ModelRole.DEFAULT_MODEL);
            boolean hasContent = result != null && result.isSuccess() && result.getContent() != null && !result.getContent().isBlank();
            boolean jsonShape = hasContent && intentShape(result.getContent(), mapper);
            System.out.println("INTENT_MODEL_PROBE model=tongyi-intent-detect-v3 transport=chat_json status="
                    + (hasContent && jsonShape ? "OK" : "INVALID_RESPONSE") + " shape=" + jsonShape
                    + " latencyMs=" + (result == null ? "unknown" : result.getLatencyMs()));
        } catch (Exception error) {
            // The production classifier must use this exact failure as the
            // signal to fall through to the configured Flash pool.
            System.out.println("INTENT_MODEL_PROBE model=tongyi-intent-detect-v3 transport=chat_json status=FAILED type="
                    + error.getClass().getSimpleName());
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    private boolean intentShape(String content, ObjectMapper mapper) {
        try {
            String value = content.trim();
            int start = value.indexOf('{');
            int end = value.lastIndexOf('}');
            if (start < 0 || end <= start) return false;
            JsonNode root = mapper.readTree(value.substring(start, end + 1));
            return root.isObject() && root.has("intent") && root.has("confidence")
                    && root.has("slots") && root.has("needsClarification") && root.has("multiIntent");
        } catch (Exception ignored) {
            return false;
        }
    }
}
