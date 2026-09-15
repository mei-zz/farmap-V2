package com.mei.zhgy.service.ai;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Single Java boundary for all calls into the Python Intelligence Plane. */
@Component
public class IntelligenceGateway {
    public static final String CONTRACT_VERSION = "ai.v1";
    public static final int EMBEDDING_DIMENSION = 512;

    private final LocalAiClient localAi;
    private final String visionUrl;
    private final String serviceToken;
    private final RestTemplate http;
    private final boolean legacyCompatibility;

    @Autowired
    public IntelligenceGateway(LocalAiClient localAi,
                               @Value("${model.api.v1-url:http://127.0.0.1:5000/v1/vision/analyze}") String visionUrl,
                               @Value("${farmap.ai.service-token:}") String serviceToken,
                               @Value("${farmap.ai.local.timeout-ms:30000}") int timeoutMs) {
        this.localAi = localAi;
        this.visionUrl = visionUrl.replaceAll("/$", "");
        this.serviceToken = serviceToken == null ? "" : serviceToken;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.min(Math.max(timeoutMs, 1000), 5000));
        factory.setReadTimeout(Math.max(timeoutMs, 1000));
        this.http = new RestTemplate(factory);
        this.legacyCompatibility = false;
    }

    /** Test-friendly constructor that keeps the gateway independent from Spring. */
    public IntelligenceGateway(LocalAiClient localAi, String visionUrl, RestTemplate http) {
        this(localAi, visionUrl, http, true);
    }

    /** Test/legacy adapter constructor; production always uses the versioned endpoints. */
    public IntelligenceGateway(LocalAiClient localAi, String visionUrl, RestTemplate http, boolean legacyCompatibility) {
        this.localAi = localAi;
        this.visionUrl = visionUrl.replaceAll("/$", "");
        this.serviceToken = "";
        this.http = http;
        this.legacyCompatibility = legacyCompatibility;
    }

    public Map<String, Object> health() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("intelligencePlane", "READY");
        output.put("contractVersion", CONTRACT_VERSION);
        Map<String, Object> local;
        try {
            local = localAi.healthV1();
        } catch (Exception error) {
            local = unavailable("AI_RUNTIME_UNAVAILABLE", error.getClass().getSimpleName());
        }
        Map<String, Object> vision;
        try {
            vision = get(visionUrl.replace("/vision/analyze", "/health"));
        } catch (Exception error) {
            vision = unavailable("AI_RUNTIME_UNAVAILABLE", error.getClass().getSimpleName());
        }
        output.put("vision", vision.getOrDefault("vision", vision.getOrDefault("status", "UNAVAILABLE")));
        output.put("textEmbedding", local.getOrDefault("textEmbedding", "UNAVAILABLE"));
        output.put("imageEmbedding", local.getOrDefault("imageEmbedding", "UNAVAILABLE"));
        String localStatus = String.valueOf(local.getOrDefault("status", "UNAVAILABLE"));
        String visionStatus = String.valueOf(vision.getOrDefault("status", "UNAVAILABLE"));
        output.put("status", "READY".equals(localStatus) && ("READY".equals(visionStatus) || "DEGRADED".equals(visionStatus))
                ? "READY" : ("UNAVAILABLE".equals(localStatus) && "UNAVAILABLE".equals(visionStatus) ? "UNAVAILABLE" : "DEGRADED"));
        return output;
    }

    public Map<String, Object> capabilities() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("contractVersion", CONTRACT_VERSION);
        try {
            output.put("vision", get(visionUrl.replace("/vision/analyze", "/capabilities")));
        } catch (Exception error) {
            output.put("vision", unavailable("AI_RUNTIME_UNAVAILABLE", error.getClass().getSimpleName()));
        }
        try {
            output.put("embeddings", localAi.capabilitiesV1());
        } catch (Exception error) {
            output.put("embeddings", unavailable("AI_RUNTIME_UNAVAILABLE", error.getClass().getSimpleName()));
        }
        return output;
    }

    public Map<String, Object> analyzeVision(List<String> images, Map<String, Object> context,
                                             String requestId, String traceId) {
        String id = requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
        if (images == null || images.isEmpty() || images.size() > 5) {
            throw new IntelligenceGatewayException("AI_INVALID_REQUEST", "images must contain 1-5 items", id, false);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contractVersion", CONTRACT_VERSION);
        body.put("requestId", id);
        body.put("traceId", traceId);
        body.put("task", "VISION_ANALYZE");
        List<Map<String, String>> refs = new ArrayList<>();
        for (String image : images) {
            refs.add(Collections.singletonMap("uri", ImageReferenceResolver.forVision(image)));
        }
        body.put("images", refs);
        if (context != null && !context.isEmpty()) body.put("context", context);
        try {
            Map<String, Object> response = post(visionUrl, body, id);
            validateContract(response, id);
            return response;
        } catch (IntelligenceGatewayException error) {
            throw error;
        } catch (Exception error) {
            throw translate(error, id);
        }
    }

    public Map<String, Object> embedText(String text, String requestId, String traceId) {
        List<Map<String, Object>> values = embedTexts(Collections.singletonList(text), requestId, traceId);
        return values.isEmpty() ? Collections.emptyMap() : values.get(0);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> embedTexts(List<String> texts, String requestId, String traceId) {
        String id = requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
        if (texts == null || texts.isEmpty() || texts.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IntelligenceGatewayException("AI_INVALID_REQUEST", "texts must be non-empty strings", id, false);
        }
        try {
            Map<String, Object> response = legacyCompatibility ? localAi.embedTexts(texts) : localAi.embedTextsV1(texts, id, traceId);
            if (!legacyCompatibility) validateContract(response, id);
            if (!legacyCompatibility) validateDimension(response, id);
            Object raw = response.get("embeddings");
            if (!(raw instanceof List)) throw new IntelligenceGatewayException("AI_CONTRACT_INVALID", "embeddings missing", id, false);
            List<Map<String, Object>> output = new ArrayList<>();
            for (Object vector : (List<?>) raw) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("vector", vector);
                item.put("dimension", response.get("dimension"));
                item.put("model", response.get("model"));
                item.put("latencyMs", response.get("latencyMs"));
                output.add(item);
            }
            return output;
        } catch (IntelligenceGatewayException error) {
            throw error;
        } catch (Exception error) {
            throw translate(error, id);
        }
    }

    public Map<String, Object> embedImage(String image, String requestId, String traceId) {
        String id = requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
        if (image == null || image.isBlank()) throw new IntelligenceGatewayException("AI_INVALID_REQUEST", "image is required", id, false);
        try {
            String normalizedImage = ImageReferenceResolver.forLocalEmbedding(image);
            Map<String, Object> response = legacyCompatibility ? localAi.embedImage(normalizedImage) : localAi.embedImageV1(normalizedImage, id, traceId);
            if (!legacyCompatibility) validateContract(response, id);
            if (!legacyCompatibility) validateDimension(response, id);
            return response;
        } catch (IntelligenceGatewayException error) {
            throw error;
        } catch (Exception error) {
            throw translate(error, id);
        }
    }

    public boolean isLegacyCompatibility() { return legacyCompatibility; }

    private void validateContract(Map<String, Object> response, String requestId) {
        if (response == null || response.isEmpty() || !CONTRACT_VERSION.equals(response.get("contractVersion"))) {
            throw new IntelligenceGatewayException("AI_CONTRACT_INVALID", "response contractVersion must be ai.v1", requestId, false);
        }
        String status = String.valueOf(response.getOrDefault("status", ""));
        if ("UNAVAILABLE".equals(status) || "FAILED".equals(status)) {
            throw new IntelligenceGatewayException(String.valueOf(response.getOrDefault("errorCode", "AI_MODEL_UNAVAILABLE")),
                    String.valueOf(response.getOrDefault("message", "intelligence capability unavailable")), requestId, true);
        }
    }

    private void validateDimension(Map<String, Object> response, String requestId) {
        Object dimension = response.get("dimension");
        if (!(dimension instanceof Number) || ((Number) dimension).intValue() != EMBEDDING_DIMENSION) {
            throw new IntelligenceGatewayException("AI_CONTRACT_INVALID", "embedding dimension must be 512", requestId, false);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String url) {
        ResponseEntity<Map> response = http.exchange(url, HttpMethod.GET, authEntity(), Map.class);
        return response.getBody() == null ? Collections.emptyMap() : response.getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String url, Map<String, Object> body, String requestId) {
        ResponseEntity<Map> response = http.exchange(url, HttpMethod.POST, json(body), Map.class);
        return response.getBody() == null ? Collections.emptyMap() : response.getBody();
    }

    private HttpEntity<Map<String, Object>> json(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (!serviceToken.isBlank()) headers.setBearerAuth(serviceToken);
        return new HttpEntity<>(body, headers);
    }

    private HttpEntity<Void> authEntity() {
        HttpHeaders headers = new HttpHeaders();
        if (!serviceToken.isBlank()) headers.setBearerAuth(serviceToken);
        return new HttpEntity<>(headers);
    }

    private Map<String, Object> unavailable(String code, String detail) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UNAVAILABLE");
        result.put("errorCode", code);
        result.put("detail", detail);
        return result;
    }

    private IntelligenceGatewayException translate(Exception error, String requestId) {
        if (error instanceof HttpStatusCodeException) {
            HttpStatusCodeException httpError = (HttpStatusCodeException) error;
            return new IntelligenceGatewayException(httpError.getStatusCode().value() == 400 ? "AI_INVALID_REQUEST" : "AI_MODEL_UNAVAILABLE",
                    "intelligence request failed with HTTP " + httpError.getStatusCode().value(), requestId,
                    httpError.getStatusCode().is5xxServerError(), error);
        }
        if (error instanceof ResourceAccessException) {
            return new IntelligenceGatewayException("AI_MODEL_TIMEOUT", "intelligence request timed out", requestId, true, error);
        }
        return new IntelligenceGatewayException("AI_INTERNAL_ERROR", "intelligence request failed", requestId, false, error);
    }
}
