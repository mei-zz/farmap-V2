package com.mei.zhgy.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.properties.BailianProperties;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;

/** Alibaba Cloud Model Studio OpenAI-compatible Chat Completions client. */
@Component
public class BailianModelClient {
    private static final Logger log = LoggerFactory.getLogger(BailianModelClient.class);
    private final ObjectMapper objectMapper;
    private final BailianProperties properties;
    private final RestTemplate restTemplate;
    @org.springframework.beans.factory.annotation.Value("${farmap.ai.generation.global-max-output-tokens:8192}")
    private int globalMaxOutputTokens = 8192;

    @Autowired
    public BailianModelClient(ObjectMapper objectMapper, BailianProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.max(1000, properties.getConnectTimeoutMs()));
        factory.setReadTimeout(Math.max(1000, properties.getReadTimeoutMs()));
        this.restTemplate = new RestTemplate(factory);
        log.info("Qwen Model Studio gateway configured={}, endpoint={}, credentialFingerprint={}",
                isConfigured(), properties.getChatCompletionsUrl(), fingerprint(properties.getApiKey()));
    }

    /** Kept for unit-test doubles and small local probes. */
    public BailianModelClient(ObjectMapper objectMapper) {
        this(objectMapper, new BailianProperties());
    }

    public boolean isConfigured() {
        return StringUtils.hasText(properties.getApiKey());
    }

    public String getRegion() {
        return properties.getRegion();
    }

    public String getWorkspaceId() {
        return StringUtils.hasText(properties.getWorkspaceId()) ? properties.getWorkspaceId() : null;
    }

    public String getApiUrl() {
        return properties.getChatCompletionsUrl();
    }

    private String fingerprint(String value) {
        if (!StringUtils.hasText(value)) return "MISSING";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder();
            for (int i = 0; i < 6 && i < digest.length; i++) output.append(String.format("%02x", digest[i]));
            return output.toString();
        } catch (Exception error) {
            return "UNAVAILABLE";
        }
    }

    public String getEmbeddingUrl() {
        return compatibleBaseUrl() + "/embeddings";
    }

    public String getTextRerankUrl() {
        return serviceRoot() + "/api/v1/services/rerank/text-rerank/text-rerank";
    }

    /** OpenAI-compatible dense embeddings for a single knowledge index generation. */
    public List<EmbeddingProvider.EmbeddingResult> embedTexts(String model, List<String> texts, int dimension) {
        if (!isConfigured()) throw new ModelCallException("missing_api_key", "BAILIAN_API_KEY / DASHSCOPE_API_KEY 未配置");
        if (texts == null || texts.isEmpty()) return Collections.emptyList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", texts);
        body.put("dimensions", dimension);
        body.put("encoding_format", "float");
        long started = System.currentTimeMillis();
        try {
            JsonNode root = objectMapper.readTree(postJson(getEmbeddingUrl(), body));
            JsonNode data = root.path("data");
            if (!data.isArray() || data.size() != texts.size()) {
                throw new ModelCallException("invalid_response", "百炼 embedding 返回数量不匹配");
            }
            List<EmbeddingProvider.EmbeddingResult> output = new ArrayList<>();
            for (JsonNode item : data) {
                JsonNode values = item.path("embedding");
                if (!values.isArray() || values.size() != dimension) {
                    throw new ModelCallException("invalid_response", "百炼 embedding 维度不匹配");
                }
                List<Float> vector = new ArrayList<>(dimension);
                for (JsonNode value : values) {
                    if (!value.isNumber() || !Double.isFinite(value.asDouble())) {
                        throw new ModelCallException("invalid_response", "百炼 embedding 含非有限数值");
                    }
                    vector.add((float) value.asDouble());
                }
                output.add(new EmbeddingProvider.EmbeddingResult(vector, "BAILIAN", root.path("model").asText(model), dimension,
                        System.currentTimeMillis() - started));
            }
            return output;
        } catch (ModelCallException error) {
            throw error;
        } catch (Exception error) {
            throw new ModelCallException("invalid_response", "百炼 embedding 返回无法解析", error);
        }
    }

    /** DashScope text-rerank contract for gte-rerank-v2. */
    public List<KnowledgeRerankResult> rerank(String model, String query, List<String> documents, int topK) {
        if (!isConfigured()) throw new ModelCallException("missing_api_key", "BAILIAN_API_KEY / DASHSCOPE_API_KEY 未配置");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("query", query);
        input.put("documents", documents);
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("return_documents", true);
        parameters.put("top_n", Math.max(1, Math.min(topK, documents.size())));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", input);
        body.put("parameters", parameters);
        try {
            JsonNode root = objectMapper.readTree(postJson(getTextRerankUrl(), body));
            JsonNode results = root.path("output").path("results");
            if (!results.isArray()) results = root.path("results");
            if (!results.isArray()) throw new ModelCallException("invalid_response", "百炼 rerank 返回缺少 results");
            List<KnowledgeRerankResult> output = new ArrayList<>();
            for (JsonNode item : results) {
                int index = item.path("index").asInt(-1);
                double score = item.path("relevance_score").asDouble(Double.NaN);
                if (index < 0 || index >= documents.size() || !Double.isFinite(score)) {
                    throw new ModelCallException("invalid_response", "百炼 rerank 返回索引或分数无效");
                }
                output.add(new KnowledgeRerankResult(index, score));
            }
            return output;
        } catch (ModelCallException error) {
            throw error;
        } catch (Exception error) {
            throw new ModelCallException("invalid_response", "百炼 rerank 返回无法解析", error);
        }
    }

    private String compatibleBaseUrl() {
        String configured = properties.getBaseUrl();
        if (configured == null || configured.trim().isEmpty()) return "https://dashscope.aliyuncs.com/compatible-mode/v1";
        String normalized = configured.trim();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        if (normalized.endsWith("/chat/completions")) normalized = normalized.substring(0, normalized.length() - "/chat/completions".length());
        return normalized;
    }

    private String serviceRoot() {
        String normalized = compatibleBaseUrl();
        int compatible = normalized.indexOf("/compatible-mode/");
        if (compatible >= 0) return normalized.substring(0, compatible);
        int version = normalized.indexOf("/v1");
        return version >= 0 ? normalized.substring(0, version) : normalized;
    }

    private String postJson(String url, Map<String, Object> body) {
        int maxRetries = properties.getMaxRetries();
        for (int attempt = 0; ; attempt++) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.setBearerAuth(properties.getApiKey());
                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST,
                        new HttpEntity<>(body, headers), String.class);
                return Objects.requireNonNullElse(response.getBody(), "{}");
            } catch (HttpStatusCodeException error) {
                String type = classifyStatus(error.getRawStatusCode());
                if (attempt < maxRetries && isRetryable(type)) { backoff(attempt); continue; }
                throw new ModelCallException(type, "百炼请求失败，HTTP " + error.getRawStatusCode(), error);
            } catch (ResourceAccessException error) {
                if (attempt < maxRetries) { backoff(attempt); continue; }
                throw new ModelCallException("timeout", "百炼请求超时或网络不可达", error);
            }
        }
    }

    public static final class KnowledgeRerankResult {
        private final int index;
        private final double score;
        public KnowledgeRerankResult(int index, double score) { this.index = index; this.score = score; }
        public int getIndex() { return index; }
        public double getScore() { return score; }
    }

    public ModelCallResult call(String model, ModelCallRequest request, ModelRole role) {
        return callInternal(model, request, role, true);
    }

    /** Additive no-retry entry point for callers that enforce their own deadline. */
    public ModelCallResult callOnce(String model, ModelCallRequest request, ModelRole role) {
        return callInternal(model, request, role, false);
    }

    /** Streams content deltas from the OpenAI-compatible chat endpoint. */
    public ModelCallResult stream(String model, ModelCallRequest request, ModelRole role, Consumer<String> onDelta) {
        long start = System.currentTimeMillis();
        if (!isConfigured()) throw new ModelCallException("missing_api_key", "BAILIAN_API_KEY / DASHSCOPE_API_KEY 未配置");
        AtomicBoolean emitted = new AtomicBoolean(false);
        try {
            Map<String, Object> body = requestBody(model, request);
            body.put("stream", true);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(Collections.singletonList(MediaType.TEXT_EVENT_STREAM));
            headers.setBearerAuth(properties.getApiKey());
            ModelCallResult result = restTemplate.execute(
                    properties.getChatCompletionsUrl(), HttpMethod.POST,
                    clientRequest -> {
                        clientRequest.getHeaders().putAll(headers);
                        objectMapper.writeValue(clientRequest.getBody(), body);
                    },
                    response -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new ModelCallException(classifyStatus(response.getRawStatusCode()), "百炼流式模型调用失败，HTTP " + response.getRawStatusCode());
                        }
                        StringBuilder content = new StringBuilder();
                        ModelUsageMetadata usage = null;
                        String finishReason = null;
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (!line.startsWith("data:")) continue;
                                String payload = line.substring("data:".length()).trim();
                                if (payload.isEmpty() || "[DONE]".equals(payload)) continue;
                                JsonNode root = objectMapper.readTree(payload);
                                JsonNode choices = root.path("choices");
                                if (choices.isArray() && !choices.isEmpty()) {
                                    JsonNode choice = choices.get(0);
                                    finishReason = choice.path("finish_reason").asText(finishReason);
                                    String delta = extractContent(choice.path("delta").path("content"));
                                    if (StringUtils.hasText(delta)) {
                                        content.append(delta);
                                        emitted.set(true);
                                        onDelta.accept(delta);
                                    }
                                }
                                JsonNode usageNode = root.path("usage");
                                if (usageNode.isObject()) {
                                    usage = ModelUsageMetadata.builder().provider("alibaba-bailian")
                                            .model(root.path("model").asText(model))
                                            .inputTokens(longValue(usageNode, "prompt_tokens"))
                                            .outputTokens(longValue(usageNode, "completion_tokens"))
                                            .thinkingTokens(thinkingTokens(usageNode))
                                            .totalTokens(longValue(usageNode, "total_tokens"))
                                            .finishReason(finishReason).build();
                                }
                            }
                        }
                        if (usage != null) usage.setFinishReason(finishReason);
                        String finalContent = stripPrivateReasoning(content.toString());
                        if (!StringUtils.hasText(finalContent)) throw new ModelCallException("invalid_response", "百炼流式响应缺少可展示结果");
                        if (usage == null) usage = ModelUsageMetadata.builder().provider("alibaba-bailian").model(model).finishReason(finishReason).build();
                        return ModelCallResult.builder().success(true).content(finalContent).selectedModel(model)
                                .modelRole(role).latencyMs(System.currentTimeMillis() - start).usage(usage).build();
                    });
            if (result == null) throw new ModelCallException("invalid_response", "百炼流式响应为空");
            return result;
        } catch (HttpStatusCodeException exception) {
            throw new ModelCallException(classifyStatus(exception.getRawStatusCode()), "百炼流式模型调用失败，HTTP " + exception.getRawStatusCode(), exception);
        } catch (ResourceAccessException exception) {
            throw new ModelCallException("timeout", "百炼流式模型请求超时或网络不可达", exception);
        } catch (ModelCallException exception) {
            // A few OpenAI-compatible deployments accept the request but do
            // not implement streaming. Retry once through the existing
            // synchronous contract and expose bounded deltas to the caller.
            if (!emitted.get() && ("provider_error".equals(exception.getErrorType()) || "invalid_response".equals(exception.getErrorType()))) {
                ModelCallResult complete = call(model, request, role);
                emitChunks(complete.getContent(), onDelta);
                return complete;
            }
            throw exception;
        } catch (Exception exception) {
            throw new ModelCallException("provider_error", "百炼流式模型调用异常", exception);
        }
    }

    private void emitChunks(String content, Consumer<String> onDelta) {
        if (!StringUtils.hasText(content)) return;
        int chunkSize = 24;
        for (int offset = 0; offset < content.length(); offset += chunkSize) {
            onDelta.accept(content.substring(offset, Math.min(content.length(), offset + chunkSize)));
        }
    }

    private ModelCallResult callInternal(String model, ModelCallRequest request, ModelRole role, boolean allowRetry) {
        long start = System.currentTimeMillis();
        if (!isConfigured()) {
            throw new ModelCallException("missing_api_key", "BAILIAN_API_KEY / DASHSCOPE_API_KEY 未配置");
        }
        int maxRetries = allowRetry ? properties.getMaxRetries() : 0;
        for (int attempt = 0; ; attempt++) {
            try {
                Map<String, Object> body = requestBody(model, request);

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.setBearerAuth(properties.getApiKey());
                ResponseEntity<String> response = restTemplate.exchange(
                        properties.getChatCompletionsUrl(),
                        HttpMethod.POST,
                        new HttpEntity<>(body, headers),
                        String.class);
                if (!response.getStatusCode().is2xxSuccessful()) {
                    throw new ModelCallException(classifyStatus(response.getStatusCodeValue()), "百炼模型调用失败，HTTP " + response.getStatusCodeValue());
                }
                return parseResponse(model, role, response.getBody(), System.currentTimeMillis() - start);
            } catch (HttpStatusCodeException exception) {
                ModelCallException failure = new ModelCallException(classifyStatus(exception.getRawStatusCode()), "百炼模型调用失败，HTTP " + exception.getRawStatusCode(), exception);
                String providerBody = exception.getResponseBodyAsString();
                if (providerBody != null && providerBody.length() > 600) providerBody = providerBody.substring(0, 600);
                log.warn("Qwen Model Studio request rejected: model={}, status={}, type={}, body={}", model, exception.getRawStatusCode(), failure.getErrorType(), providerBody);
                if (attempt < maxRetries && isRetryable(failure.getErrorType())) {
                    backoff(attempt);
                    continue;
                }
                throw failure;
            } catch (ResourceAccessException exception) {
                ModelCallException failure = new ModelCallException(resourceAccessType(exception), "百炼模型请求超时或网络不可达", exception);
                if (attempt < maxRetries) {
                    backoff(attempt);
                    continue;
                }
                throw failure;
            } catch (ModelCallException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new ModelCallException("provider_error", "百炼模型调用异常", exception);
            }
        }
    }

    private Map<String, Object> requestBody(String model, ModelCallRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", buildMessages(model, request));
        Object configuredTemperature = request == null || request.getContext() == null
                ? null : request.getContext().get("temperature");
        double temperature = configuredTemperature instanceof Number number ? number.doubleValue() : 0.1D;
        body.put("temperature", Double.isFinite(temperature) ? Math.max(0D, Math.min(2D, temperature)) : 0.1D);
        body.put("max_tokens", boundedMaxTokens(request));
        // Copilot and evidence synthesis never expose hidden reasoning.
        body.put("enable_thinking", thinkingEnabled(model, request));
        if (request.isStructuredOutput()) body.put("response_format", Collections.singletonMap("type", "json_object"));
        return body;
    }

    private boolean thinkingEnabled(String model, ModelCallRequest request) {
        Object configured = request.getContext() == null ? null : request.getContext().get("enableThinking");
        if (configured != null) return Boolean.parseBoolean(String.valueOf(configured));
        return model != null && model.contains("thinking");
    }

    private int boundedMaxTokens(ModelCallRequest request) {
        Object smoke = request == null || request.getContext() == null
                ? null : request.getContext().get("answerPoolSmoke");
        if (Boolean.TRUE.equals(smoke) || "true".equalsIgnoreCase(String.valueOf(smoke))) return 1;
        Object configured = request.getContext() == null ? null : request.getContext().get("maxOutputTokens");
        int requested = configured instanceof Number number ? number.intValue() : defaultMaxTokens(request);
        int ceiling = Math.max(256, Math.min(globalMaxOutputTokens, 8192));
        return Math.max(256, Math.min(requested, ceiling));
    }

    private int defaultMaxTokens(ModelCallRequest request) {
        if (request == null || request.getTaskType() == null) return 1800;
        return switch (request.getTaskType()) {
            case COPILOT -> 2048;
            case IMAGE_UNDERSTANDING -> 700;
            case MULTIMODAL_DIAGNOSIS, EVIDENCE_SYNTHESIS, GROUNDED_GENERATION,
                    HARD_DIAGNOSIS, EXPERT_PRE_REVIEW -> 3072;
            default -> 1800;
        };
    }

    private boolean isRetryable(String errorType) {
        return "rate_limited".equals(errorType)
                || "provider_unavailable".equals(errorType)
                || "provider_error".equals(errorType)
                || "timeout".equals(errorType);
    }

    private void backoff(int attempt) {
        long delay = Math.min(properties.getRetryBackoffMs() * (1L << Math.min(attempt, 4)), 5000);
        if (delay <= 0) return;
        try {
            Thread.sleep(delay);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new ModelCallException("timeout", "百炼模型重试被中断", interruptedException);
        }
    }

    private List<Map<String, Object>> buildMessages(String model, ModelCallRequest request) {
        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("role", "user");
        // Some Model Studio deployments reject a system-role message for
        // text-only compatibility models. Carry the instruction in user
        // content for those models while preserving the grounding contract.
        boolean textOnlyModel = isTextOnlyModel(model);
        boolean mergeInstruction = textOnlyModel;
        if (!mergeInstruction) {
            Map<String, Object> system = new LinkedHashMap<>();
            system.put("role", "system");
            system.put("content", request.getSystemPrompt());
            messages.add(system);
        }
        String prompt = mergeInstruction && StringUtils.hasText(request.getSystemPrompt())
                ? request.getSystemPrompt() + "\n\n" + request.getUserPrompt()
                : request.getUserPrompt();
        if (textOnlyModel && request.getImageUrls() != null && !request.getImageUrls().isEmpty()) {
            // Text models are the verified fallback when the account has no
            // entitlement for a VL model. Keep the uploaded image in the
            // persisted Evidence/CLIP retrieval chain, but do not send an
            // unsupported image payload to the text endpoint or invent visual
            // observations. The grounded prompt still contains field,
            // weather, knowledge and image-retrieval evidence.
            prompt += "\n\n现场图片已完成上传和图像向量检索；当前回答模型不直接接收原始图片。禁止臆测图片内容，只依据已提供的文字证据、图像索引结果和地块上下文回答。";
            user.put("content", prompt);
        } else if (request.getImageUrls() == null || request.getImageUrls().isEmpty()) {
            user.put("content", prompt);
        } else {
            List<Map<String, Object>> content = new ArrayList<>();
            for (String imageUrl : request.getImageUrls()) {
                if (!StringUtils.hasText(imageUrl)) continue;
                Map<String, Object> image = new LinkedHashMap<>();
                image.put("type", "image_url");
                image.put("image_url", Collections.singletonMap("url", ImageReferenceResolver.forModel(imageUrl)));
                content.add(image);
            }
            Map<String, Object> text = new LinkedHashMap<>();
            text.put("type", "text");
            text.put("text", prompt);
            content.add(text);
            user.put("content", content);
        }
        messages.add(user);
        return messages;
    }

    private String resourceAccessType(ResourceAccessException error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ConnectException || current instanceof UnknownHostException
                    || current instanceof NoRouteToHostException) return "connection_error";
            current = current.getCause();
        }
        return "timeout";
    }

    private boolean isTextOnlyModel(String model) {
        return "qwen-plus-2025-07-28".equalsIgnoreCase(model)
                || "qwen3.6-plus".equalsIgnoreCase(model)
                || "qwen-plus".equalsIgnoreCase(model);
    }

    private ModelCallResult parseResponse(String requestedModel, ModelRole role, String body, long latencyMs) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new ModelCallException("invalid_response", "百炼返回缺少 choices");
            }
            JsonNode message = choices.get(0).path("message");
            String content = extractContent(message.path("content"));
            if (!StringUtils.hasText(content)) {
                throw new ModelCallException("invalid_response", "百炼返回缺少可展示结果");
            }
            JsonNode usage = root.path("usage");
            ModelUsageMetadata usageMetadata = ModelUsageMetadata.builder()
                    .provider("alibaba-bailian")
                    .model(root.path("model").asText(requestedModel))
                    .inputTokens(longValue(usage, "prompt_tokens"))
                    .outputTokens(longValue(usage, "completion_tokens"))
                    .thinkingTokens(thinkingTokens(usage))
                    .totalTokens(longValue(usage, "total_tokens"))
                    .finishReason(choices.get(0).path("finish_reason").asText(null))
                    .build();
            return ModelCallResult.builder()
                    .success(true)
                    .content(stripPrivateReasoning(content))
                    .selectedModel(root.path("model").asText(requestedModel))
                    .modelRole(role)
                    .latencyMs(latencyMs)
                    .usage(usageMetadata)
                    .build();
        } catch (ModelCallException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ModelCallException("invalid_response", "百炼返回无法解析", exception);
        }
    }

    private String extractContent(JsonNode contentNode) {
        if (contentNode.isTextual()) return contentNode.asText();
        if (!contentNode.isArray()) return "";
        StringBuilder builder = new StringBuilder();
        Iterator<JsonNode> iterator = contentNode.elements();
        while (iterator.hasNext()) {
            JsonNode item = iterator.next();
            if (item.has("text")) builder.append(item.path("text").asText());
        }
        return builder.toString();
    }

    private String stripPrivateReasoning(String content) {
        return content.replaceAll("(?s)<think>.*?</think>", "").trim();
    }

    private Long longValue(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.longValue() : null;
    }

    private Long thinkingTokens(JsonNode usage) {
        Long direct = longValue(usage, "thinking_tokens");
        if (direct != null) return direct;
        return longValue(usage.path("completion_tokens_details"), "reasoning_tokens");
    }

    private String classifyStatus(int status) {
        if (status == 401) return "auth_failed";
        if (status == 403) return "permission_denied";
        if (status == 404) return "model_not_found";
        if (status == 429) return "rate_limited";
        if (status >= 500) return "provider_unavailable";
        return "provider_error";
    }

    public ModelCallResult probe(String model) {
        ModelCallRequest request = ModelCallRequest.builder()
                .taskType(AiTaskType.COPILOT)
                .systemPrompt("只返回一个简短中文词语，不要解释，不要输出隐藏推理。")
                .userPrompt("请只回复“好”。")
                // Availability must test auth/model/network with the smallest provider-compatible request.
                .structuredOutput(false)
                .context(Map.of("maxOutputTokens", 1, "answerPoolSmoke", true))
                .build();
        return callInternal(model, request, ModelRole.DEFAULT_MODEL, false);
    }
}
