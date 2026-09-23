package com.mei.zhgy.service.copilot.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.mei.zhgy.properties.BailianProperties;
import com.mei.zhgy.service.ai.AiTaskType;
import com.mei.zhgy.service.ai.BailianModelClient;
import com.mei.zhgy.service.ai.ModelCallRequest;
import com.mei.zhgy.service.ai.ModelCallResult;
import com.mei.zhgy.service.ai.ModelRole;
import com.mei.zhgy.service.copilot.CopilotIntentRouter;
import com.mei.zhgy.service.copilot.CopilotIntent;
import com.mei.zhgy.service.agriculture.CropResolver;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * Explicit, opt-in connectivity probe for the configured intent Router model. It
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
        String model = firstNonBlank(System.getProperty("intent.probe.model"), "qwen-flash");
        try {
            ModelCallResult result = client.call(model, request, ModelRole.DEFAULT_MODEL);
            boolean hasContent = result != null && result.isSuccess() && result.getContent() != null && !result.getContent().isBlank();
            boolean jsonShape = hasContent && intentShape(result.getContent(), mapper);
            System.out.println("INTENT_MODEL_PROBE model=" + model + " transport=chat_json status="
                    + (hasContent && jsonShape ? "OK" : "INVALID_RESPONSE") + " shape=" + jsonShape
                    + " latencyMs=" + (result == null ? "unknown" : result.getLatencyMs()));
            probeFullHybridRoute(model, properties, mapper);
        } catch (Exception error) {
            // The production classifier must use this exact failure as the
            // signal to fall through to the configured Flash pool.
            String type = error instanceof com.mei.zhgy.service.ai.ModelCallException modelError
                    ? modelError.getErrorType() : error.getClass().getSimpleName();
            System.out.println("INTENT_MODEL_PROBE model=" + model + " transport=chat_json status=FAILED type=" + type);
        }
    }

    private void probeFullHybridRoute(String model, BailianProperties bailianProperties, ObjectMapper mapper) {
        IntentModelProperties intentProperties = new IntentModelProperties();
        intentProperties.setEnabled(true);
        intentProperties.setShadowMode(false);
        intentProperties.setPrimaryModel(model);
        intentProperties.setFlashPool(java.util.List.of());
        intentProperties.setStrongPool(java.util.List.of());
        intentProperties.setTotalTimeoutMs(1800);
        LlmIntentClassifier classifier = new LlmIntentClassifier(intentProperties,
                new BailianIntentClassifierClient(new BailianModelClient(mapper, bailianProperties)),
                new IntentPromptBuilder(), new CropResolver(), mapper);
        CopilotIntentRouter router = new CopilotIntentRouter();
        router.setHybridClassifier(classifier);
        CopilotIntentRouter.HybridClassification route = router.classifyHybrid(
                "叶片有点黄是咋回事", new CopilotIntentRouter.HybridContext("", null, "脆李"));
        String llmIntent = route.llm() == null || route.llm().intent() == null ? "none" : route.llm().intent().name();
        String finalIntent = route.finalClassification() == null || route.finalClassification().intent() == null
                ? "none" : route.finalClassification().intent().name();
        String ruleIntent = route.rule() == null || route.rule().intent() == null ? "none" : route.rule().intent().name();
        System.out.println("INTENT_MODEL_ROUTE_PROBE model=" + model + " ruleIntent=" + ruleIntent
                + " llmIntent=" + llmIntent + " finalIntent=" + finalIntent
                + " tier=" + (route.llm() == null ? "none" : route.llm().tier())
                + " status=" + (route.llm() != null && route.llm().ok() ? "MODEL_DECISION" : "RULE_FALLBACK")
                + " fallback=" + (route.llm() == null || route.llm().fallbackReason() == null ? "none" : route.llm().fallbackReason())
                + " latencyMs=" + (route.llm() == null ? "unknown" : route.llm().latencyMs()));
        if (route.llm() != null && route.llm().ok()) {
            // This assertion is deliberately limited to the contract, not to
            // one model label: the existing policy may safely retain the rule
            // intent when the model is uncertain or protected.
            boolean validIntent = route.llm().intent() == CopilotIntent.GENERAL_QA
                    || route.llm().intent() == CopilotIntent.KNOWLEDGE_QUERY
                    || route.llm().intent() == CopilotIntent.DIAGNOSIS_REQUEST
                    || route.llm().intent() == CopilotIntent.RECOMMENDATION_QUERY;
            if (!validIntent) throw new AssertionError("live Router returned an unexpected intent");
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
