package com.mei.zhgy.service.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class LocalAiClient {
    private final String baseUrl;
    private final String serviceToken;
    private final RestTemplate http;

    @Autowired
    public LocalAiClient(@Value("${farmap.ai.local.base-url:http://127.0.0.1:8001}") String baseUrl,
                         @Value("${farmap.ai.local.timeout-ms:30000}") int timeoutMs,
                         @Value("${farmap.ai.service-token:}") String serviceToken) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.serviceToken = serviceToken == null ? "" : serviceToken;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.min(timeoutMs, 5000));
        factory.setReadTimeout(timeoutMs);
        this.http = new RestTemplate(factory);
    }

    public LocalAiClient(String baseUrl, int timeoutMs) {
        this(baseUrl, timeoutMs, "");
    }

    LocalAiClient(String baseUrl, RestTemplate http) {
        this.baseUrl = baseUrl;
        this.serviceToken = "";
        this.http = http;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> health() {
        Map<String, Object> value = http.exchange(baseUrl + "/health", org.springframework.http.HttpMethod.GET,
                authEntity(), Map.class).getBody();
        return value == null ? Collections.emptyMap() : value;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> healthV1() {
        Map<String, Object> value = http.exchange(baseUrl + "/v1/health", org.springframework.http.HttpMethod.GET,
                authEntity(), Map.class).getBody();
        return value == null ? Collections.emptyMap() : value;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> capabilitiesV1() {
        Map<String, Object> value = http.exchange(baseUrl + "/v1/capabilities", org.springframework.http.HttpMethod.GET,
                authEntity(), Map.class).getBody();
        return value == null ? Collections.emptyMap() : value;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> embedTexts(List<String> texts) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("texts", texts);
        Map<String, Object> value = http.exchange(baseUrl + "/embedding/text", org.springframework.http.HttpMethod.POST,
                json(body), Map.class).getBody();
        return value == null ? Collections.emptyMap() : value;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> embedTextsV1(List<String> texts, String requestId, String traceId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contractVersion", "ai.v1");
        body.put("requestId", requestId);
        body.put("traceId", traceId);
        body.put("task", "TEXT_EMBED");
        body.put("texts", texts);
        Map<String, Object> value = http.exchange(baseUrl + "/v1/embeddings/text", org.springframework.http.HttpMethod.POST,
                json(body), Map.class).getBody();
        return value == null ? Collections.emptyMap() : value;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> embedImage(String image) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("image", image);
        Map<String, Object> value = http.exchange(baseUrl + "/embedding/image", org.springframework.http.HttpMethod.POST,
                json(body), Map.class).getBody();
        return value == null ? Collections.emptyMap() : value;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> embedImageV1(String image, String requestId, String traceId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contractVersion", "ai.v1");
        body.put("requestId", requestId);
        body.put("traceId", traceId);
        body.put("task", "IMAGE_EMBED");
        body.put("image", image);
        Map<String, Object> value = http.exchange(baseUrl + "/v1/embeddings/image", org.springframework.http.HttpMethod.POST,
                json(body), Map.class).getBody();
        return value == null ? Collections.emptyMap() : value;
    }

    public String getBaseUrl() { return baseUrl; }

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
}
