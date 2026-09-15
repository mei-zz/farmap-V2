package com.mei.zhgy.service.copilot.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.service.agriculture.CropResolver;
import com.mei.zhgy.service.copilot.CopilotIntent;
import com.mei.zhgy.service.copilot.CopilotIntentRouter;
import com.mei.zhgy.service.ai.ModelCallException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmIntentClassifierObservabilityTest {
    @Test
    void recordsBoundedRedactedSamplesAndDerivedRates() {
        IntentModelProperties properties = properties();
        properties.setRecentSampleCapacity(3);
        LlmIntentClassifier classifier = classifier(properties,
                (model, tier, prompt, maxTokens) -> response("KNOWLEDGE_QUERY", model, tier));
        CopilotIntentRouter.Classification match = rule(CopilotIntent.KNOWLEDGE_QUERY);
        CopilotIntentRouter.Classification mismatch = rule(CopilotIntent.GENERAL_QA);

        classifier.classify("sample-one", null, match);
        classifier.classify("sample-two", null, mismatch);
        classifier.classify("sample-three", null, match);
        classifier.classify("sample-four", null, mismatch);

        Map<String, Object> metrics = classifier.metricsSnapshot();
        assertEquals(4L, metrics.get("ruleComparisons"));
        assertEquals(2L, metrics.get("ruleMismatches"));
        assertEquals(0.5D, (Double) metrics.get("ruleMismatchRate"), 0.00001D);
        assertEquals(0D, (Double) metrics.get("fallbackRate"), 0.00001D);
        assertEquals(0D, (Double) metrics.get("strongEscalationRate"), 0.00001D);
        assertEquals(3, metrics.get("recentSampleCapacity"));
        assertEquals(3, metrics.get("recentSampleSize"));

        List<LlmIntentClassifier.IntentShadowSample> recent = classifier.recentSamples(0, false);
        assertEquals(3, recent.size(), "invalid limit uses the default before capacity is applied");
        assertEquals(hash("sample-four"), recent.get(0).messageHash());
        assertEquals(hash("sample-three"), recent.get(1).messageHash());
        assertEquals(hash("sample-two"), recent.get(2).messageHash());
        List<LlmIntentClassifier.IntentShadowSample> mismatches = classifier.recentSamples(50, true);
        assertEquals(2, mismatches.size());
        assertFalse(mismatches.get(0).match());
        assertFalse(mismatches.get(1).match());
        assertNoRawMessageFields(recent);
    }

    @Test
    void derivesStrongEscalationAndFallbackRates() {
        IntentModelProperties properties = properties();
        properties.setStrongPool(List.of("strong-test"));
        AtomicInteger primaryCalls = new AtomicInteger();
        LlmIntentClassifier classifier = classifier(properties, (model, tier, prompt, maxTokens) -> {
            if ("STRONG".equals(tier)) return response("DIAGNOSIS_REQUEST", model, tier);
            if (primaryCalls.incrementAndGet() == 2) throw new ModelCallException("provider_unavailable", "test");
            return response("KNOWLEDGE_QUERY", model, tier);
        });

        classifier.classify("脆李叶片黄化并且最近施肥，怎么处理", null, rule(CopilotIntent.GENERAL_QA));
        classifier.classify("failure-case", null, rule(CopilotIntent.GENERAL_QA));

        Map<String, Object> metrics = classifier.metricsSnapshot();
        assertEquals(2L, metrics.get("primaryCalls"));
        assertEquals(1L, metrics.get("strongEscalations"));
        assertEquals(1L, metrics.get("fallbackCalls"));
        assertEquals(0.5D, (Double) metrics.get("strongEscalationRate"), 0.00001D);
        assertEquals(0.5D, (Double) metrics.get("fallbackRate"), 0.00001D);
    }

    @Test
    void resetClearsObservationsButRetainsLruCache() {
        IntentModelProperties properties = properties();
        AtomicInteger calls = new AtomicInteger();
        LlmIntentClassifier classifier = classifier(properties, (model, tier, prompt, maxTokens) -> {
            calls.incrementAndGet();
            return response("KNOWLEDGE_QUERY", model, tier);
        });
        CopilotIntentRouter.Classification rule = rule(CopilotIntent.GENERAL_QA);
        classifier.classify("cache-survives-reset", null, rule);

        classifier.resetMetrics();
        Map<String, Object> reset = classifier.metricsSnapshot();
        assertEquals(0L, reset.get("primaryCalls"));
        assertEquals(0L, reset.get("ruleComparisons"));
        assertEquals(0L, reset.get("cacheHits"));
        assertEquals(0, reset.get("recentSampleSize"));
        assertTrue(((Map<?, ?>) reset.get("modelCalls")).isEmpty());
        assertTrue(((Map<?, ?>) reset.get("intentDistribution")).isEmpty());

        classifier.classify("cache-survives-reset", null, rule);
        assertEquals(1, calls.get(), "the LRU result cache is intentionally retained");
        assertEquals(1L, classifier.metricsSnapshot().get("cacheHits"));
    }

    @Test
    void concurrentClassificationObservationAndResetRemainBounded() throws Exception {
        IntentModelProperties properties = properties();
        properties.setCacheSize(0);
        properties.setRecentSampleCapacity(30);
        properties.setMaxConcurrentCalls(4);
        properties.setQueueCapacity(64);
        LlmIntentClassifier classifier = classifier(properties,
                (model, tier, prompt, maxTokens) -> response("KNOWLEDGE_QUERY", model, tier));
        ExecutorService workers = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int worker = 0; worker < 4; worker++) {
                int workerId = worker;
                futures.add(workers.submit(() -> {
                    for (int index = 0; index < 50; index++) {
                        classifier.classify("concurrent-" + workerId + "-" + index, null,
                                rule(CopilotIntent.GENERAL_QA));
                        classifier.recentSamples(20, index % 2 == 0);
                        if (index % 17 == 0) classifier.resetMetrics();
                    }
                }));
            }
            for (Future<?> future : futures) future.get();
        } finally {
            workers.shutdownNow();
        }

        Map<String, Object> metrics = classifier.metricsSnapshot();
        for (String key : List.of("primaryCalls", "fallbackCalls", "ruleComparisons", "ruleMismatches")) {
            assertTrue(((Number) metrics.get(key)).longValue() >= 0L, key + " must not be negative");
        }
        assertTrue(((Number) metrics.get("recentSampleSize")).intValue() <= 30);
        assertTrue(classifier.recentSamples(200, false).size() <= 30);
    }

    private LlmIntentClassifier classifier(IntentModelProperties properties, IntentClassifierClient client) {
        return new LlmIntentClassifier(properties, client, new IntentPromptBuilder(),
                new CropResolver(), new ObjectMapper());
    }

    private IntentModelProperties properties() {
        IntentModelProperties properties = new IntentModelProperties();
        properties.setEnabled(true);
        properties.setShadowMode(true);
        properties.setPrimaryModel("primary-test");
        properties.setFlashPool(List.of());
        properties.setStrongPool(List.of());
        properties.setCacheSize(512);
        return properties;
    }

    private CopilotIntentRouter.Classification rule(CopilotIntent intent) {
        return new CopilotIntentRouter.Classification(intent, 0.85D, null);
    }

    private IntentClassifierClient.RawIntentResponse response(String intent, String model, String tier) {
        return new IntentClassifierClient.RawIntentResponse(
                "{\"intent\":\"" + intent + "\",\"confidence\":0.9}", model, tier, 1L);
    }

    private void assertNoRawMessageFields(List<LlmIntentClassifier.IntentShadowSample> samples) {
        for (RecordComponent component : LlmIntentClassifier.IntentShadowSample.class.getRecordComponents()) {
            assertFalse(component.getName().equals("message") || component.getName().equals("rawMessage"));
        }
        for (LlmIntentClassifier.IntentShadowSample sample : samples) {
            assertNotNull(sample.messageHash());
            assertTrue(sample.messageHash().matches("[0-9a-f]{16}"));
            assertFalse(sample.messageHash().contains("sample-"));
        }
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.toLowerCase().getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder();
            for (int i = 0; i < 8; i++) output.append(String.format("%02x", digest[i]));
            return output.toString();
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
