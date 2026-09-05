package com.mei.zhgy.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@RestController
@Slf4j
@RequestMapping("/monitor")
public class MonitorController {

    @Value("${hikyun.access-key:}")
    private String accessKey;

    @Value("${hikyun.secret-key:}")
    private String secretKey;

    @Value("${hikyun.product-code:}")
    private String productCode;

    @Value("${hikyun.project-id:}")
    private String projectId;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public MonitorController() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 获取监控 accesstoken
     */
    @GetMapping("/get-accesstoken")
    public Result<Map<String, Object>> getAccessToken() {
        try {
            String url = "https://open.hikyun.com/artemis/oauth/token/v2";

            // 设置请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // 构建请求体
            Map<String, String> requestBody = new HashMap<>();
            requestBody.put("accessKey", accessKey);
            requestBody.put("secretKey", secretKey);
            requestBody.put("productCode", productCode);
            requestBody.put("projectId", projectId);

            HttpEntity<Map<String, String>> requestEntity = new HttpEntity<>(requestBody, headers);

            // 发送POST请求
            ResponseEntity<String> response = restTemplate.postForEntity(url, requestEntity, String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                JsonNode jsonResponse = objectMapper.readTree(response.getBody());
                JsonNode dataNode = jsonResponse.get("data");

                if (dataNode != null) {
                    String accessToken = dataNode.get("access_token").asText();
                    long expiresIn = dataNode.get("expires_in").asLong();

                    Map<String, Object> resultData = new HashMap<>();
                    resultData.put("accessToken", accessToken);
                    resultData.put("expiresAt", System.currentTimeMillis() + expiresIn * 1000);

                    return Result.success(resultData);
                }
            }

            return Result.error("获取访问令牌失败");
        } catch (Exception e) {
            log.error("获取海康云平台访问令牌时发生错误", e);
            return Result.error("后端请求 hikyun token 错误");
        }
    }

    /**
     * 获取监控预览地址
     */
    @GetMapping("/preview")
    public Result<JsonNode> getPreviewUrl(@RequestParam String accessToken,
                                          @RequestParam String deviceSerial) {
        try {
            String url = "https://open.hikyun.com/artemis/api/eits/v1/global/live/video/web?access_token=" + accessToken;

            // 设置请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // 构建请求体
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("Object", null);
            requestBody.put("productId", projectId);
            requestBody.put("deviceSerial", deviceSerial);
            requestBody.put("channelNo", 1);
            requestBody.put("videoLevel", 2);
            requestBody.put("recordType", 1);

            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

            // 发送POST请求
            ResponseEntity<String> response = restTemplate.postForEntity(url, requestEntity, String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                JsonNode jsonResponse = objectMapper.readTree(response.getBody());
                return Result.success(jsonResponse);
            }

            return Result.error("获取预览地址失败");
        } catch (Exception e) {
            log.error("获取海康云平台预览地址时发生错误", e);
            return Result.error("后端请求 hikyun 预览地址错误");
        }
    }
}
