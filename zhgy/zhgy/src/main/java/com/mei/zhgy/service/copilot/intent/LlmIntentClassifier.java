package com.mei.zhgy.service.copilot.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.service.agriculture.CropResolver;
import com.mei.zhgy.service.copilot.CopilotIntent;
import com.mei.zhgy.service.copilot.CopilotIntentRouter;
import com.mei.zhgy.service.ai.ModelCallException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/** Optional model-backed classifier. Every failure returns a rule-safe result. */
@Component
public class LlmIntentClassifier {
    private static final Logger log = LoggerFactory.getLogger(LlmIntentClassifier.class);
    private final IntentModelProperties properties;
    private final IntentModelPool pool;
    private final IntentClassifierClient client;
    private final IntentPromptBuilder promptBuilder;
    private final CropResolver crops;
    private final ObjectMapper mapper;
    private final Map<String, LlmIntentResult> cache;
    private final ExecutorService calls;
    private final ExecutorService shadowCallsExecutor;
    private final AtomicLong primaryCalls = new AtomicLong();
    private final AtomicLong strongEscalations = new AtomicLong();
    private final AtomicLong fallbackCalls = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong shadowCalls = new AtomicLong();
    private final AtomicLong shadowDropped = new AtomicLong();
    private final AtomicLong ruleComparisons = new AtomicLong();
    private final AtomicLong ruleMismatches = new AtomicLong();
    private final ConcurrentHashMap<String, AtomicLong> modelCalls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> modelFailures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> intentDistribution = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Long> latencySamples = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<IntentShadowSample> recentSamples = new ConcurrentLinkedQueue<>();

    /** A redacted shadow observation; it intentionally contains no raw message field. */
    public record IntentShadowSample(long ts, String messageHash, String ruleIntent, String llmIntent,
                                     boolean match, String tier, String model, double confidence,
                                     long latencyMs, String fallbackReason, String modeAtRecord) { }

    @Autowired
    public LlmIntentClassifier(IntentModelProperties properties, IntentModelPool pool,
                               IntentClassifierClient client, IntentPromptBuilder promptBuilder,
                               CropResolver crops, ObjectMapper mapper) {
        this.properties = properties == null ? new IntentModelProperties() : properties;
        this.pool = pool == null ? new IntentModelPool(this.properties) : pool;
        this.client = client;
        this.promptBuilder = promptBuilder == null ? new IntentPromptBuilder() : promptBuilder;
        this.crops = crops == null ? new CropResolver() : crops;
        this.mapper = mapper == null ? new ObjectMapper().findAndRegisterModules() : mapper;
        this.calls = new ThreadPoolExecutor(
                this.properties.getMaxConcurrentCalls(), this.properties.getMaxConcurrentCalls(),
                0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(this.properties.getQueueCapacity()),
                runnable -> {
                    Thread thread = new Thread(runnable, "farmap-intent-model");
                    thread.setDaemon(true);
                    return thread;
                 }, new ThreadPoolExecutor.AbortPolicy());
        this.shadowCallsExecutor = new ThreadPoolExecutor(
                Math.max(1, Math.min(4, this.properties.getMaxConcurrentCalls())),
                Math.max(1, Math.min(4, this.properties.getMaxConcurrentCalls())),
                0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(this.properties.getQueueCapacity()),
                runnable -> {
                    Thread thread = new Thread(runnable, "farmap-intent-shadow");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(32, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, LlmIntentResult> eldest) {
                return LlmIntentClassifier.this.properties.getCacheSize() > 0
                        && size() > LlmIntentClassifier.this.properties.getCacheSize();
            }
        });
    }

    public LlmIntentClassifier(IntentModelProperties properties, IntentClassifierClient client,
                               IntentPromptBuilder promptBuilder, CropResolver crops, ObjectMapper mapper) {
        this(properties, new IntentModelPool(properties), client, promptBuilder, crops, mapper);
    }

    public boolean isEnabled() { return properties.isEnabled() && client != null; }
    public boolean isShadowMode() { return properties.effectiveShadowMode(); }
    public boolean isRuleOnlyMode() { return "RULE_ONLY".equals(properties.effectiveDecisionMode()); }
    public String decisionMode() { return properties.effectiveDecisionMode(); }
    public double takeoverThreshold() { return properties.getTakeoverThreshold(); }

    public LlmIntentResult classify(String rawMessage, CopilotIntentRouter.HybridContext context,
                                    CopilotIntentRouter.Classification rule) {
        if (!isEnabled()) return failure(null, null, 0L, "disabled_or_unavailable");
        try {
            String normalized = normalize(rawMessage);
            String key = normalized + "|" + (context == null ? "" : safe(context.contextCrop()));
            LlmIntentResult cachedResult = getCached(key);
            if (cachedResult != null) {
                cacheHits.incrementAndGet();
                recordOutcome(rule, cachedResult, hash(normalized));
                return cachedResult;
            }
            IntentPromptBuilder.Prompt prompt = promptBuilder.build(normalized, context, properties.getContextMaxChars());
            long started = System.currentTimeMillis();
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(properties.getTotalTimeoutMs());
            LlmIntentResult primary = attempt(pool.primaryCandidates(), prompt, false, started, deadline);
            LlmIntentResult finalResult = primary;
            if (primary.ok() && needsEscalation(normalized, rule, primary)) {
                if (remainingMillis(deadline) > 0) {
                    strongEscalations.incrementAndGet();
                    LlmIntentResult strong = attempt(pool.strongCandidates(), prompt, true, started, deadline);
                    if (strong.ok()) {
                        finalResult = strong;
                    } else {
                        finalResult = withReason(primary, append(primary.fallbackReason(), "strong_pool_failed"));
                    }
                } else {
                    finalResult = withReason(primary, append(primary.fallbackReason(), "deadline_exceeded"));
                }
            }
            if (!finalResult.ok()) fallbackCalls.incrementAndGet();
            putCached(key, finalResult);
            recordOutcome(rule, finalResult, hash(normalized));
            return finalResult;
        } catch (Exception error) {
            fallbackCalls.incrementAndGet();
            LlmIntentResult failed = failure(null, null, 0L, reason(error));
            recordOutcome(rule, failed, hash(rawMessage));
            return failed;
        }
    }

    public void classifyShadow(String rawMessage, CopilotIntentRouter.HybridContext context,
                               CopilotIntentRouter.Classification rule) {
        if (!isEnabled()) return;
        shadowCalls.incrementAndGet();
        try {
            shadowCallsExecutor.execute(() -> {
                LlmIntentResult result = classify(rawMessage, context, rule);
                String ruleIntent = rule == null || rule.intent() == null ? "null" : rule.intent().name();
                String llmIntent = result.intent() == null ? "null" : result.intent().name();
                log.info("intent_shadow messageHash={} ruleIntent={} llmIntent={} tier={} model={} confidence={} latencyMs={} fallback={}",
                        hash(rawMessage), ruleIntent, llmIntent, result.tier(), result.model(), result.confidence(), result.latencyMs(),
                        result.fallbackReason() == null ? "none" : result.fallbackReason());
            });
        } catch (RejectedExecutionException rejected) {
            // Shadow work is explicitly best effort. Never make an interactive
            // request wait when the bounded diagnostic queue is full.
            shadowDropped.incrementAndGet();
        }
    }

    public Map<String, Object> metricsSnapshot() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("primaryCalls", primaryCalls.get());
        metrics.put("strongEscalations", strongEscalations.get());
        metrics.put("fallbackCalls", fallbackCalls.get());
        metrics.put("cacheHits", cacheHits.get());
        metrics.put("shadowCalls", shadowCalls.get());
        metrics.put("shadowDropped", shadowDropped.get());
        long comparisons = ruleComparisons.get();
        metrics.put("ruleComparisons", comparisons);
        metrics.put("ruleMismatches", ruleMismatches.get());
        metrics.put("ruleMismatchRate", comparisons == 0 ? 0D : (double) ruleMismatches.get() / comparisons);
        long primary = primaryCalls.get();
        metrics.put("strongEscalationRate", primary == 0 ? 0D : (double) strongEscalations.get() / primary);
        metrics.put("fallbackRate", primary == 0 ? 0D : (double) fallbackCalls.get() / primary);
        metrics.put("recentSampleCapacity", properties.getRecentSampleCapacity());
        metrics.put("recentSampleSize", recentSamples.size());
        metrics.put("intentDistribution", counts(intentDistribution));
        metrics.put("latencyP50Ms", percentile(0.50D));
        metrics.put("latencyP95Ms", percentile(0.95D));
        metrics.put("modelCalls", counts(modelCalls));
        metrics.put("modelFailures", counts(modelFailures));
        return Map.copyOf(metrics);
    }

    /** Returns newest observations first and never exposes the original message. */
    public List<IntentShadowSample> recentSamples(int limit, boolean mismatchOnly) {
        int boundedLimit = limit <= 0 ? 50 : Math.min(limit, 200);
        List<IntentShadowSample> snapshot = new ArrayList<>(recentSamples);
        List<IntentShadowSample> output = new ArrayList<>(Math.min(boundedLimit, snapshot.size()));
        for (int index = snapshot.size() - 1; index >= 0 && output.size() < boundedLimit; index--) {
            IntentShadowSample sample = snapshot.get(index);
            if (!mismatchOnly || !sample.match()) output.add(sample);
        }
        return List.copyOf(output);
    }

    /** Clears observation state only; the LRU result cache is retained for performance. */
    public void resetMetrics() {
        primaryCalls.set(0L);
        strongEscalations.set(0L);
        fallbackCalls.set(0L);
        cacheHits.set(0L);
        shadowCalls.set(0L);
        shadowDropped.set(0L);
        ruleComparisons.set(0L);
        ruleMismatches.set(0L);
        modelCalls.clear();
        modelFailures.clear();
        intentDistribution.clear();
        latencySamples.clear();
        recentSamples.clear();
    }

    private LlmIntentResult attempt(List<IntentModelPool.ModelChoice> choices,
                                    IntentPromptBuilder.Prompt prompt, boolean strong, long started, long deadline) {
        if (choices == null || choices.isEmpty()) return failure(null, strong ? "STRONG" : "PRIMARY", elapsed(started), "model_pool_empty");
        String lastReason = null;
        String lastModel = null;
        String lastTier = null;
        for (IntentModelPool.ModelChoice choice : choices.stream().limit(2).toList()) {
            if (remainingMillis(deadline) <= 0) return failure(lastModel, lastTier, elapsed(started), append(lastReason, "deadline_exceeded"));
            lastModel = choice.model();
            lastTier = choice.tier();
            if (!strong) primaryCalls.incrementAndGet();
            modelCalls.computeIfAbsent(choice.model(), ignored -> new AtomicLong()).incrementAndGet();
            try {
                IntentClassifierClient.RawIntentResponse raw = invoke(choice, prompt, deadline);
                LlmIntentResult parsed = parse(raw, choice, started);
                if (parsed.ok()) return parsed;
                // A malformed response is not a transport failure. Do not
                // spend the remaining deadline on another model for it.
                return parsed;
            } catch (Exception error) {
                modelFailures.computeIfAbsent(choice.model(), ignored -> new AtomicLong()).incrementAndGet();
                lastReason = append(lastReason, reason(error));
                if (error instanceof IntentTimeoutException || !retryableTransport(error)) {
                    return failure(lastModel, lastTier, elapsed(started), lastReason);
                }
            }
        }
        return failure(lastModel, lastTier, elapsed(started), lastReason == null ? "model_call_failed" : lastReason);
    }

    private IntentClassifierClient.RawIntentResponse invoke(IntentModelPool.ModelChoice choice,
                                                            IntentPromptBuilder.Prompt prompt, long deadline) throws Exception {
        long remaining = remainingMillis(deadline);
        if (remaining <= 0) throw new IntentTimeoutException();
        Future<IntentClassifierClient.RawIntentResponse> future = calls.submit(
                () -> client.classify(choice.model(), choice.tier(), prompt, properties.getMaxTokens()));
        try {
            long perCall = Math.max(properties.getConnectTimeoutMs(), properties.getReadTimeoutMs());
            return future.get(Math.min(remaining, perCall), TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            future.cancel(true);
            throw new IntentTimeoutException();
        } finally {
            future.cancel(true);
        }
    }

    private LlmIntentResult parse(IntentClassifierClient.RawIntentResponse raw,
                                  IntentModelPool.ModelChoice choice, long started) {
        if (raw == null || raw.content() == null || raw.content().isBlank()) return failure(choice.model(), choice.tier(), elapsed(started), "empty_response");
        try {
            String content = unwrapJson(raw.content());
            JsonNode root = mapper.readTree(content);
            if (root == null || !root.isObject()) return failure(choice.model(), choice.tier(), elapsed(started), "json_not_object");
            String intentText = text(root, "intent");
            CopilotIntent intent = intentText == null ? null : CopilotIntent.valueOf(intentText.trim().replace('-', '_').toUpperCase(Locale.ROOT));
            JsonNode confidenceNode = root.get("confidence");
            if (intent == null || confidenceNode == null || !confidenceNode.isNumber()) return failure(choice.model(), choice.tier(), elapsed(started), "required_field_missing");
            double confidence = confidenceNode.asDouble(Double.NaN);
            if (!Double.isFinite(confidence)) return failure(choice.model(), choice.tier(), elapsed(started), "confidence_invalid");
            JsonNode slotsNode = root.get("slots");
            IntentSlots slots = slotsNode != null && slotsNode.isObject()
                    ? new IntentSlots(optionalText(slotsNode, "crop"), optionalText(slotsNode, "stage"),
                    optionalText(slotsNode, "fieldId"), optionalText(slotsNode, "time"), optionalText(slotsNode, "organ"),
                    optionalText(slotsNode, "disease"), optionalText(slotsNode, "action"))
                    : new IntentSlots(null, null, null, null, null, null, null);
            JsonNode clarificationNode = root.get("needsClarification");
            JsonNode multiNode = root.get("multiIntent");
            boolean needsClarification = clarificationNode != null && clarificationNode.isBoolean() && clarificationNode.asBoolean();
            List<String> multiIntent = new ArrayList<>();
            if (multiNode != null && multiNode.isArray()) {
                for (JsonNode item : multiNode) {
                    if (!item.isTextual()) continue;
                    String value = item.asText().trim().replace('-', '_').toUpperCase(Locale.ROOT);
                    try {
                        CopilotIntent.valueOf(value);
                        multiIntent.add(value);
                    } catch (IllegalArgumentException ignored) {
                        // Optional diagnostics must not invalidate the primary
                        // intent; unknown secondary labels are discarded.
                    }
                }
            }
            return new LlmIntentResult(intent, Math.max(0D, Math.min(1D, confidence)), slots,
                    needsClarification, List.copyOf(multiIntent),
                    raw.model() == null ? choice.model() : raw.model(), choice.tier(),
                    Math.max(raw.latencyMs(), elapsed(started)), null, true);
        } catch (IllegalArgumentException error) {
            return failure(choice.model(), choice.tier(), elapsed(started), "intent_enum_invalid");
        } catch (Exception error) {
            return failure(choice.model(), choice.tier(), elapsed(started), "json_parse_failed");
        }
    }

    private boolean needsEscalation(String normalized, CopilotIntentRouter.Classification rule, LlmIntentResult llm) {
        if (llm == null || !llm.ok()) return false;
        if (llm.confidence() < properties.getEscalateThreshold()) return true;
        if (rule != null && rule.intent() != null && rule.intent() != llm.intent()
                && isConcrete(rule.intent()) && isConcrete(llm.intent())) return true;
        return complexSignals(normalized);
    }

    private boolean complexSignals(String normalized) {
        boolean crop = crops.containsKnownAlias(normalized);
        boolean symptom = contains(normalized, "病", "虫", "病斑", "发黄", "黄化", "掉果", "腐烂", "裂果", "长势");
        boolean operation = contains(normalized, "施肥", "浇水", "灌溉", "打药", "修剪", "防治", "安排", "执行", "前茬");
        return crop && symptom && operation;
    }

    private boolean isConcrete(CopilotIntent intent) {
        return intent != CopilotIntent.GENERAL_QA && intent != CopilotIntent.CLARIFICATION_REQUIRED
                && !isProtected(intent);
    }

    private boolean retryableTransport(Exception error) {
        Throwable current = error;
        while (current.getCause() != null && (current instanceof java.util.concurrent.ExecutionException
                || current instanceof java.util.concurrent.CompletionException)) current = current.getCause();
        if (current instanceof ModelCallException modelError) {
            return "provider_unavailable".equals(modelError.getErrorType())
                    || "connection_error".equals(modelError.getErrorType());
        }
        return current instanceof ConnectException || current instanceof UnknownHostException
                || current instanceof SocketTimeoutException;
    }

    private long remainingMillis(long deadline) {
        if (deadline == Long.MAX_VALUE) return Long.MAX_VALUE;
        long nanos = deadline - System.nanoTime();
        return nanos <= 0 ? 0L : TimeUnit.NANOSECONDS.toMillis(nanos);
    }

    private boolean isProtected(CopilotIntent intent) {
        return intent == CopilotIntent.UNSUPPORTED || intent == CopilotIntent.NAVIGATION_REQUEST
                || intent == CopilotIntent.ACTION_REQUEST || intent == CopilotIntent.MEMORY_WRITE_REQUEST
                || intent == CopilotIntent.MEMORY_QUERY || intent == CopilotIntent.MEMORY_FORGET_REQUEST;
    }

    private LlmIntentResult getCached(String key) {
        if (properties.getCacheSize() <= 0) return null;
        synchronized (cache) { return cache.get(key); }
    }

    private void putCached(String key, LlmIntentResult result) {
        if (properties.getCacheSize() <= 0 || result == null) return;
        synchronized (cache) { cache.put(key, result); }
    }

    private String unwrapJson(String raw) {
        String value = raw.trim();
        if (value.startsWith("```") && value.endsWith("```")) {
            int newline = value.indexOf('\n');
            value = newline >= 0 ? value.substring(newline + 1, value.length() - 3).trim() : value.substring(3, value.length() - 3).trim();
        }
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        return start >= 0 && end > start ? value.substring(start, end + 1) : value;
    }

    private String optionalText(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual() || value.asText().isBlank()) return null;
        return value.asText().trim();
    }

    private String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value == null || value.isNull() || !value.isTextual() ? null : value.asText();
    }

    private LlmIntentResult failure(String model, String tier, long latency, String reason) {
        return new LlmIntentResult(null, 0D, null, false, List.of(), model, tier, Math.max(0L, latency), reason, false);
    }

    private LlmIntentResult withReason(LlmIntentResult result, String reason) {
        return new LlmIntentResult(result.intent(), result.confidence(), result.slots(), result.needsClarification(),
                result.multiIntent(), result.model(), result.tier(), result.latencyMs(), reason, result.ok());
    }

    private void recordOutcome(CopilotIntentRouter.Classification rule, LlmIntentResult result) {
        recordOutcome(rule, result, "");
    }

    private void recordOutcome(CopilotIntentRouter.Classification rule, LlmIntentResult result, String messageHash) {
        if (result == null) return;
        latencySamples.add(Math.max(0L, result.latencyMs()));
        while (latencySamples.size() > 2048) latencySamples.poll();
        if (result.ok() && result.intent() != null) {
            intentDistribution.computeIfAbsent(result.intent().name(), ignored -> new AtomicLong()).incrementAndGet();
        }
        if (result.ok() && rule != null && rule.intent() != null && result.intent() != null) {
            ruleComparisons.incrementAndGet();
            if (rule.intent() != result.intent()) ruleMismatches.incrementAndGet();
        }
        String ruleIntent = rule == null || rule.intent() == null ? "null" : rule.intent().name();
        String llmIntent = result.intent() == null ? "null" : result.intent().name();
        boolean match = rule != null && rule.intent() != null && rule.intent() == result.intent();
        recentSamples.add(new IntentShadowSample(
                System.currentTimeMillis(),
                messageHash == null ? "" : messageHash,
                ruleIntent,
                llmIntent,
                match,
                result.tier(),
                result.model(),
                result.confidence(),
                Math.max(0L, result.latencyMs()),
                result.fallbackReason(),
                properties.effectiveDecisionMode().toLowerCase(Locale.ROOT)));
        while (recentSamples.size() > properties.getRecentSampleCapacity()) recentSamples.poll();
    }

    private long percentile(double quantile) {
        List<Long> values = new ArrayList<>(latencySamples);
        if (values.isEmpty()) return 0L;
        Collections.sort(values);
        int index = (int) Math.ceil(Math.max(0D, Math.min(1D, quantile)) * values.size()) - 1;
        return values.get(Math.max(0, Math.min(values.size() - 1, index)));
    }

    private String append(String existing, String value) {
        if (value == null || value.isBlank()) return existing;
        return existing == null || existing.isBlank() ? value : existing + "," + value;
    }

    private String reason(Throwable error) {
        if (error instanceof java.util.concurrent.ExecutionException execution && execution.getCause() != null) error = execution.getCause();
        if (error instanceof ModelCallException modelError) return modelError.getErrorType();
        if (error instanceof IntentTimeoutException) return "timeout";
        return error.getClass().getSimpleName();
    }

    private long elapsed(long started) { return Math.max(0L, System.currentTimeMillis() - started); }

    private String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).trim().toLowerCase(Locale.ROOT);
    }

    private String safe(String value) { return value == null ? "" : value.trim(); }
    private boolean contains(String value, String... terms) { for (String term : terms) if (value.contains(term)) return true; return false; }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalize(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder();
            for (int i = 0; i < 8; i++) output.append(String.format("%02x", digest[i]));
            return output.toString();
        } catch (Exception ignored) { return "unavailable"; }
    }

    private Map<String, Long> counts(Map<String, AtomicLong> source) {
        Map<String, Long> output = new LinkedHashMap<>();
        source.forEach((key, value) -> output.put(key, value.get()));
        return Map.copyOf(output);
    }

    private static class IntentTimeoutException extends RuntimeException { }
}
