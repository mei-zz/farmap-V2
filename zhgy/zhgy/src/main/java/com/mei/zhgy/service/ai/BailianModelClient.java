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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Alibaba Cloud Model Studio OpenAI-compatible Chat Completions client. */
@Component
public class BailianModelClient {
    private final ObjectMapper objectMapper;
    private final BailianProperties properties;
    private final RestTemplate restTemplate;

    @Autowired
    public BailianModelClient(ObjectMapper objectMapper, BailianProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.max(1000, properties.getConnectTimeoutMs()));
        factory.setReadTimeout(Math.max(1000, properties.getReadTimeoutMs()));
        this.restTemplate = new RestTemplate(factory);
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

    public ModelCallResult call(String model, ModelCallRequest request, ModelRole role) {
        return callInternal(model, request, role, true);
    }

    private ModelCallResult callInternal(String model, ModelCallRequest request, ModelRole role, boolean allowRetry) {
        long start = System.currentTimeMillis();
        if (!isConfigured()) {
            throw new ModelCallException("missing_api_key", "BAILIAN_API_KEY / DASHSCOPE_API_KEY 未配置");
        }
        int maxRetries = allowRetry ? properties.getMaxRetries() : 0;
        for (int attempt = 0; ; attempt++) {
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", model);
                body.put("messages", buildMessages(request));
                body.put("temperature", 0.1);
                body.put("max_tokens", 1800);
                if (model.contains("thinking")) {
                    body.put("enable_thinking", true);
                }
                if (request.isStructuredOutput()) {
                    body.put("response_format", Collections.singletonMap("type", "json_object"));
                }

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
                if (attempt < maxRetries && isRetryable(failure.getErrorType())) {
                    backoff(attempt);
                    continue;
                }
                throw failure;
            } catch (ResourceAccessException exception) {
                ModelCallException failure = new ModelCallException("timeout", "百炼模型请求超时或网络不可达", exception);
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

    private List<Map<String, Object>> buildMessages(ModelCallRequest request) {
        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> system = new LinkedHashMap<>();
        system.put("role", "system");
        system.put("content", request.getSystemPrompt());
        messages.add(system);

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("role", "user");
        if (request.getImageUrls() == null || request.getImageUrls().isEmpty()) {
            user.put("content", request.getUserPrompt());
        } else {
            List<Map<String, Object>> content = new ArrayList<>();
            for (String imageUrl : request.getImageUrls()) {
                if (!StringUtils.hasText(imageUrl)) continue;
                Map<String, Object> image = new LinkedHashMap<>();
                image.put("type", "image_url");
                image.put("image_url", Collections.singletonMap("url", imageUrl));
                content.add(image);
            }
            Map<String, Object> text = new LinkedHashMap<>();
            text.put("type", "text");
            text.put("text", request.getUserPrompt());
            content.add(text);
            user.put("content", content);
        }
        messages.add(user);
        return messages;
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
                .systemPrompt("Return only the requested JSON object. Do not include hidden reasoning.")
                .userPrompt("Return {\"ok\":true,\"model\":\"" + model + "\"}.")
                // Availability must test auth/model/network with the smallest provider-compatible request.
                .structuredOutput(false)
                .build();
        return callInternal(model, request, ModelRole.DEFAULT_MODEL, false);
    }
}
