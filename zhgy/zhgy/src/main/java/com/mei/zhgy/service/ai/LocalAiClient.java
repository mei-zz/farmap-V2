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
    private final RestTemplate http;

    @Autowired
    public LocalAiClient(@Value("${farmap.ai.local.base-url:http://127.0.0.1:8001}") String baseUrl,
                         @Value("${farmap.ai.local.timeout-ms:30000}") int timeoutMs) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.min(timeoutMs, 5000));
        factory.setReadTimeout(timeoutMs);
        this.http = new RestTemplate(factory);
    }

    LocalAiClient(String baseUrl, RestTemplate http) {
        this.baseUrl = baseUrl;
        this.http = http;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> health() {
        Map<String, Object> value = http.getForObject(baseUrl + "/health", Map.class);
        return value == null ? Collections.emptyMap() : value;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> embedTexts(List<String> texts) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("texts", texts);
        Map<String, Object> value = http.postForObject(baseUrl + "/embedding/text", json(body), Map.class);
        return value == null ? Collections.emptyMap() : value;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> embedImage(String image) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("image", image);
        Map<String, Object> value = http.postForObject(baseUrl + "/embedding/image", json(body), Map.class);
        return value == null ? Collections.emptyMap() : value;
    }

    public String getBaseUrl() { return baseUrl; }

    private HttpEntity<Map<String, Object>> json(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
