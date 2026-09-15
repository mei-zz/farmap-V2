package com.mei.zhgy.service.copilot.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.service.copilot.CopilotIntent;
import com.mei.zhgy.service.copilot.CopilotIntentRouter;
import com.mei.zhgy.service.ai.ModelCallException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmIntentClassifierTest {
    @Test
    void parsesJsonAndCachesTheSameNormalizedSentence() {
        IntentModelProperties properties = properties();
        AtomicInteger calls = new AtomicInteger();
        IntentClassifierClient client = (model, tier, prompt, maxTokens) -> {
            calls.incrementAndGet();
            return new IntentClassifierClient.RawIntentResponse("""
                    {"intent":"KNOWLEDGE_QUERY","confidence":0.91,"slots":{"crop":"脆李","stage":null,"fieldId":null,"time":null,"organ":null,"disease":null,"action":null},"needsClarification":false,"multiIntent":[]}
                    """, model, tier, 4L);
        };
        LlmIntentClassifier classifier = classifier(properties, client);
        CopilotIntentRouter.Classification rule = new CopilotIntentRouter.Classification(CopilotIntent.GENERAL_QA, 0.85D, null);
        CopilotIntentRouter.HybridContext context = new CopilotIntentRouter.HybridContext("", null, "plum");

        LlmIntentResult first = classifier.classify("脆李是什么？", context, rule);
        LlmIntentResult second = classifier.classify(" 脆李是什么？ ", context, rule);

        assertTrue(first.ok());
        assertEquals(CopilotIntent.KNOWLEDGE_QUERY, first.intent());
        assertEquals("脆李", first.slots().crop());
        assertEquals(1, calls.get());
        assertEquals(first, second);
        assertEquals(1L, classifier.metricsSnapshot().get("cacheHits"));
    }

    @Test
    void invalidJsonAndProviderExceptionsFailOpen() {
        IntentModelProperties properties = properties();
        AtomicInteger calls = new AtomicInteger();
        IntentClassifierClient client = (model, tier, prompt, maxTokens) -> {
            calls.incrementAndGet();
            if (calls.get() == 1) return new IntentClassifierClient.RawIntentResponse("```json\n{not-json}\n```", model, tier, 1L);
            throw new IllegalStateException("simulated provider failure");
        };
        LlmIntentClassifier classifier = classifier(properties, client);
        CopilotIntentRouter.Classification rule = new CopilotIntentRouter.Classification(CopilotIntent.GENERAL_QA, 0.85D, null);

        LlmIntentResult result = assertDoesNotThrow(() -> classifier.classify("普通闲聊", null, rule));

        assertFalse(result.ok());
        assertEquals(1, calls.get(), "malformed output must not trigger a second model call");
        assertTrue(result.fallbackReason().contains("json_parse_failed")
                || result.fallbackReason().contains("provider"));
    }

    @Test
    void fencedJsonWithIllegalEnumIsRejected() {
        IntentModelProperties properties = properties();
        IntentClassifierClient client = (model, tier, prompt, maxTokens) -> new IntentClassifierClient.RawIntentResponse(
                "```json\n{\"intent\":\"NOT_A_FARMAP_INTENT\",\"confidence\":0.9,\"slots\":{},\"needsClarification\":false,\"multiIntent\":[]}\n```",
                model, tier, 1L);
        LlmIntentClassifier classifier = classifier(properties, client);
        CopilotIntentRouter.Classification rule = new CopilotIntentRouter.Classification(CopilotIntent.GENERAL_QA, 0.85D, null);

        LlmIntentResult result = assertDoesNotThrow(() -> classifier.classify("再问一句", null, rule));

        assertFalse(result.ok());
        assertEquals("intent_enum_invalid", result.fallbackReason().split(",")[0]);
    }

    @Test
    void httpFailureReturnsRuleSafeFailure() {
        IntentModelProperties properties = properties();
        LlmIntentClassifier classifier = classifier(properties, (model, tier, prompt, maxTokens) -> {
            throw new ModelCallException("provider_unavailable", "simulated HTTP failure");
        });
        CopilotIntentRouter.Classification rule = new CopilotIntentRouter.Classification(CopilotIntent.GENERAL_QA, 0.85D, null);

        LlmIntentResult result = assertDoesNotThrow(() -> classifier.classify("网络异常测试", null, rule));

        assertFalse(result.ok());
        assertTrue(result.fallbackReason().contains("provider_unavailable"));
    }

    @Test
    void configuredTimeoutReturnsWithoutPropagatingTheSlowCall() {
        IntentModelProperties properties = properties();
        properties.setConnectTimeoutMs(1000);
        properties.setReadTimeoutMs(1000);
        properties.setFlashPool(List.of());
        AtomicInteger calls = new AtomicInteger();
        long started = System.currentTimeMillis();
        LlmIntentClassifier classifier = classifier(properties, (model, tier, prompt, maxTokens) -> {
            calls.incrementAndGet();
            long deadline = System.currentTimeMillis() + 1500L;
            while (System.currentTimeMillis() < deadline) {
                // Deliberately ignore interruption to model a stuck provider;
                // the classifier's bounded Future is the safety boundary.
                Thread.yield();
            }
            return new IntentClassifierClient.RawIntentResponse("{}", model, tier, 1500L);
        });
        CopilotIntentRouter.Classification rule = new CopilotIntentRouter.Classification(CopilotIntent.GENERAL_QA, 0.85D, null);

        LlmIntentResult result = assertDoesNotThrow(() -> classifier.classify("超时测试", null, rule));

        assertFalse(result.ok());
        assertEquals(1, calls.get(), "timeout must not switch models");
        assertTrue(System.currentTimeMillis() - started < 1800L);
        assertTrue(result.fallbackReason().contains("timeout"));
    }

    @Test
    void optionalSchemaFieldsDefaultWithoutInvalidatingIntent() {
        IntentModelProperties properties = properties();
        IntentClassifierClient client = (model, tier, prompt, maxTokens) -> new IntentClassifierClient.RawIntentResponse(
                "{\"intent\":\"KNOWLEDGE_QUERY\",\"confidence\":0.8}", model, tier, 1L);
        LlmIntentClassifier classifier = classifier(properties, client);
        CopilotIntentRouter.Classification rule = new CopilotIntentRouter.Classification(CopilotIntent.GENERAL_QA, 0.85D, null);

        LlmIntentResult result = classifier.classify("字段缺失测试", null, rule);

        assertTrue(result.ok());
        assertEquals(CopilotIntent.KNOWLEDGE_QUERY, result.intent());
        assertEquals(new IntentSlots(null, null, null, null, null, null, null), result.slots());
        assertFalse(result.needsClarification());
        assertTrue(result.multiIntent().isEmpty());
    }

    @Test
    void cropSymptomAndOperationTogetherEscalateToStrongPool() {
        IntentModelProperties properties = properties();
        properties.setFlashPool(List.of());
        properties.setStrongPool(List.of("strong-test"));
        AtomicInteger calls = new AtomicInteger();
        IntentClassifierClient client = (model, tier, prompt, maxTokens) -> {
            calls.incrementAndGet();
            String intent = "PRIMARY".equals(tier) ? "KNOWLEDGE_QUERY" : "DIAGNOSIS_REQUEST";
            return new IntentClassifierClient.RawIntentResponse(
                    "{\"intent\":\"" + intent + "\",\"confidence\":0.9,\"slots\":{\"crop\":null,\"stage\":null,\"fieldId\":null,\"time\":null,\"organ\":null,\"disease\":null,\"action\":null},\"needsClarification\":false,\"multiIntent\":[]}",
                    model, tier, 1L);
        };
        LlmIntentClassifier classifier = classifier(properties, client);
        CopilotIntentRouter.Classification rule = new CopilotIntentRouter.Classification(CopilotIntent.GENERAL_QA, 0.85D, null);

        LlmIntentResult result = classifier.classify("脆李叶片黄化并且最近施肥，怎么处理", null, rule);

        assertTrue(result.ok());
        assertEquals(CopilotIntent.DIAGNOSIS_REQUEST, result.intent());
        assertEquals("STRONG", result.tier());
        assertEquals(2, calls.get());
        assertEquals(1L, classifier.metricsSnapshot().get("strongEscalations"));
    }

    private LlmIntentClassifier classifier(IntentModelProperties properties, IntentClassifierClient client) {
        return new LlmIntentClassifier(properties, client, new IntentPromptBuilder(),
                new com.mei.zhgy.service.agriculture.CropResolver(), new ObjectMapper());
    }

    private IntentModelProperties properties() {
        IntentModelProperties properties = new IntentModelProperties();
        properties.setEnabled(true);
        properties.setShadowMode(false);
        properties.setPrimaryModel("primary-test");
        properties.setFlashPool(List.of("flash-test"));
        properties.setStrongPool(List.of());
        properties.setCacheSize(512);
        return properties;
    }
}
