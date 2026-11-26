package com.mei.zhgy.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.context.BaseContext;
import com.mei.zhgy.result.Result;
import com.mei.zhgy.service.camera.FarmCamera;
import com.mei.zhgy.service.camera.FarmCameraService;
import com.mei.zhgy.service.camera.CameraSnapshotProvider;
import com.mei.zhgy.service.field.FieldAccessException;
import com.mei.zhgy.service.storage.TencentCosStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    private final FarmCameraService farmCameraService;
    private final TencentCosStorageService tencentCosStorageService;
    private final CameraSnapshotProvider snapshotProvider;

    public MonitorController(FarmCameraService farmCameraService, TencentCosStorageService tencentCosStorageService, CameraSnapshotProvider snapshotProvider) {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
        this.farmCameraService = farmCameraService;
        this.tencentCosStorageService = tencentCosStorageService;
        this.snapshotProvider = snapshotProvider;
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

    /**
     * Captures one still image from an authenticated, farm-owned camera and
     * stores it in the same upload boundary used by Diagnosis.  The browser
     * never receives or needs a Hikvision secret/token for this operation.
     */
    @PostMapping("/capture")
    public ResponseEntity<Result<Map<String, Object>>> capture(@RequestBody CameraCaptureRequest request) {
        Long actorId = BaseContext.getCurrentId();
        if (actorId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Result.error("用户未登录"));
        }
        if (request == null || request.cameraId() == null || request.cameraId().isBlank()) {
            return ResponseEntity.badRequest().body(Result.error("cameraId 不能为空"));
        }

        FarmCamera camera;
        try {
            camera = farmCameraService.get(request.cameraId(), actorId);
        } catch (FieldAccessException error) {
            return ResponseEntity.status(error.getHttpStatus()).body(Result.error(error.getCode() + ": " + error.getMessage()));
        }

        try {
            CameraSnapshotProvider.Snapshot snapshot = snapshotProvider.capture(request.cameraId(), actorId, request.channelNo(), request.quality());
            Map<String, Object> result = new HashMap<>();
            result.put("cameraId", camera.cameraId());
            result.put("serial", camera.serial());
            result.put("cameraName", camera.name());
            result.put("farmId", camera.farmId());
            result.put("fieldId", camera.fieldId());
            result.put("fieldName", camera.fieldName());
            result.put("pointId", camera.pointId());
            result.put("source", snapshot.source());
            result.put("capturedAt", snapshot.capturedAt());
            result.put("imageUrl", snapshot.imageUrl());
            return ResponseEntity.ok(Result.success(result));
        } catch (CameraSnapshotProvider.CaptureRateLimitException error) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Result.error(error.getMessage()));
        } catch (FieldAccessException error) {
            return ResponseEntity.status(error.getHttpStatus()).body(Result.error(error.getCode() + ": " + error.getMessage()));
        } catch (IllegalStateException error) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Result.error(error.getMessage()));
        }
    }

    /**
     * Uploads a frame selected by the user from a browser camera tab. This is
     * the supported path for cameras whose vendor UI only offers local
     * download and has no remote still-capture API.
     */
    @PostMapping(value = "/upload-frame", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Result<Map<String, Object>>> uploadBrowserFrame(
            @RequestParam String cameraId,
            @RequestPart("file") MultipartFile file) {
        Long actorId = BaseContext.getCurrentId();
        if (actorId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Result.error("用户未登录"));
        if (cameraId == null || cameraId.isBlank()) return ResponseEntity.badRequest().body(Result.error("cameraId 不能为空"));

        FarmCamera camera;
        try {
            camera = farmCameraService.get(cameraId, actorId);
            TencentCosStorageService.UploadResult stored = tencentCosStorageService.upload(file, camera);
            Map<String, Object> result = new HashMap<>();
            result.put("cameraId", camera.cameraId());
            result.put("serial", camera.serial());
            result.put("cameraName", camera.name());
            result.put("farmId", camera.farmId());
            result.put("fieldId", camera.fieldId());
            result.put("fieldName", camera.fieldName());
            result.put("pointId", camera.pointId());
            result.put("source", "BROWSER_MONITOR_FRAME_COS");
            result.put("objectKey", stored.objectKey());
            String capturedAt = Instant.now().toString();
            result.put("capturedAt", capturedAt);
            result.put("imageUrl", stored.url());
            snapshotProvider.rememberBrowserFrame(camera, stored.url(), capturedAt);
            return ResponseEntity.ok(Result.success(result));
        } catch (FieldAccessException error) {
            return ResponseEntity.status(error.getHttpStatus()).body(Result.error(error.getCode() + ": " + error.getMessage()));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.unprocessableEntity().body(Result.error(error.getMessage()));
        } catch (IllegalStateException error) {
            log.warn("浏览器监控画面上传不可用，cameraId={}, reason={}", cameraId, error.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Result.error(error.getMessage()));
        } catch (Exception error) {
            log.error("浏览器监控画面上传失败，cameraId={}", cameraId, error);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Result.error("监控画面上传失败，请稍后重试"));
        }
    }

    private String requestAccessToken() throws Exception {
        String url = "https://open.hikyun.com/artemis/oauth/token/v2";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("accessKey", accessKey);
        requestBody.put("secretKey", secretKey);
        requestBody.put("productCode", productCode);
        requestBody.put("projectId", projectId);
        ResponseEntity<String> response = restTemplate.postForEntity(url, new HttpEntity<>(requestBody, headers), String.class);
        JsonNode jsonResponse = objectMapper.readTree(response.getBody());
        String accessToken = jsonResponse.path("data").path("access_token").asText("");
        if (!response.getStatusCode().is2xxSuccessful() || accessToken.isBlank()) {
            throw new IllegalStateException("获取海康访问令牌失败");
        }
        return accessToken;
    }

    public record CameraCaptureRequest(String cameraId, Integer channelNo, Integer quality) {}
}
