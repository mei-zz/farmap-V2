package com.mei.zhgy.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.context.BaseContext;
import com.mei.zhgy.result.Result;
import com.mei.zhgy.service.camera.FarmCamera;
import com.mei.zhgy.service.camera.FarmCameraService;
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

    /** Cloud Open API credentials used by the server-side remote-capture API. */
    @Value("${hikyun.client-id:}")
    private String clientId;

    @Value("${hikyun.client-secret:}")
    private String clientSecret;

    @Value("${hikyun.capture-url:https://api2.hik-cloud.com/api/v1/open/basic/channels/actions/capture}")
    private String captureUrl;

    @Value("${zhgy.upload.dir:./uploads}")
    private String uploadDir;

    @Value("${zhgy.upload.base-url:http://localhost:24009/uploads}")
    private String uploadBaseUrl;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final FarmCameraService farmCameraService;
    private final TencentCosStorageService tencentCosStorageService;
    private final Map<String, Long> lastCaptureAt = new ConcurrentHashMap<>();

    public MonitorController(FarmCameraService farmCameraService, TencentCosStorageService tencentCosStorageService) {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
        this.farmCameraService = farmCameraService;
        this.tencentCosStorageService = tencentCosStorageService;
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

        int channelNo = request.channelNo() == null || request.channelNo() < 1 ? 1 : request.channelNo();
        int quality = request.quality() == null ? 3 : Math.max(0, Math.min(3, request.quality()));
        String captureKey = camera.cameraId() + ":" + channelNo;
        long now = System.currentTimeMillis();
        Long previous = lastCaptureAt.putIfAbsent(captureKey, now);
        if (previous != null && now - previous < 5000L) {
            long retryAfter = Math.max(1L, (5000L - (now - previous) + 999L) / 1000L);
            lastCaptureAt.put(captureKey, previous);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Result.error("同一摄像头抓拍过于频繁，请约 " + retryAfter + " 秒后重试"));
        }
        lastCaptureAt.put(captureKey, now);

        try {
            String accessToken = requestCaptureAccessToken();
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("deviceSerial", camera.serial());
            requestBody.put("channelNo", channelNo);
            requestBody.put("quality", quality);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(accessToken);
            ResponseEntity<String> response = restTemplate.exchange(
                    captureUrl,
                    HttpMethod.POST,
                    new HttpEntity<>(requestBody, headers),
                    String.class);
            JsonNode jsonResponse = objectMapper.readTree(response.getBody());
            String upstreamCode = jsonResponse.path("code").asText();
            String picUrl = jsonResponse.path("data").path("picUrl").asText("");
            if (!response.getStatusCode().is2xxSuccessful() || picUrl.isBlank() || !("200".equals(upstreamCode) || "0".equals(upstreamCode))) {
                log.warn("海康抓图失败，cameraId={}, status={}, body={}", camera.cameraId(), response.getStatusCodeValue(), response.getBody());
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Result.error(
                        jsonResponse.path("message").asText("该摄像头暂时无法抓取当前画面")));
            }

            StoredImage stored = downloadToUploadBoundary(picUrl);
            Map<String, Object> result = new HashMap<>();
            result.put("cameraId", camera.cameraId());
            result.put("serial", camera.serial());
            result.put("cameraName", camera.name());
            result.put("farmId", camera.farmId());
            result.put("fieldId", camera.fieldId());
            result.put("fieldName", camera.fieldName());
            result.put("pointId", camera.pointId());
            result.put("source", "HIKVISION_REMOTE_CAPTURE");
            result.put("capturedAt", Instant.now().toString());
            result.put("imageUrl", stored.url());
            return ResponseEntity.ok(Result.success(result));
        } catch (IllegalStateException error) {
            log.warn("海康摄像头抓图配置或响应不可用，cameraId={}, reason={}", camera.cameraId(), error.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Result.error(error.getMessage()));
        } catch (Exception error) {
            log.error("海康摄像头抓图失败，cameraId={}", camera.cameraId(), error);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Result.error("摄像头抓图失败，请确认设备在线并稍后重试"));
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
            result.put("capturedAt", Instant.now().toString());
            result.put("imageUrl", stored.url());
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

    private String requestCaptureAccessToken() throws Exception {
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw new IllegalStateException("未配置云眸 Open API 抓图凭据");
        }
        String url = "https://api2.hik-cloud.com/oauth/token";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        org.springframework.util.MultiValueMap<String, String> form = new org.springframework.util.LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("grant_type", "client_credentials");
        form.add("scope", "app");
        ResponseEntity<String> response = restTemplate.postForEntity(url, new HttpEntity<>(form, headers), String.class);
        JsonNode jsonResponse = objectMapper.readTree(response.getBody());
        String accessToken = jsonResponse.path("access_token").asText("");
        if (!response.getStatusCode().is2xxSuccessful() || accessToken.isBlank()) {
            throw new IllegalStateException("云眸 Open API 抓图凭据无效或已失效");
        }
        return accessToken;
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

    private StoredImage downloadToUploadBoundary(String imageUrl) throws Exception {
        ResponseEntity<byte[]> image = restTemplate.getForEntity(imageUrl, byte[].class);
        byte[] bytes = image.getBody();
        if (!image.getStatusCode().is2xxSuccessful() || bytes == null || bytes.length == 0) {
            throw new IllegalStateException("海康图片下载失败");
        }
        if (bytes.length > 10 * 1024 * 1024L) {
            throw new IllegalStateException("抓拍图片超过 10MB");
        }
        MediaType contentType = image.getHeaders().getContentType();
        String extension = contentType != null && MediaType.IMAGE_PNG.isCompatibleWith(contentType) ? ".png" : ".jpg";
        Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
        Files.createDirectories(root);
        String fileName = "camera-" + UUID.randomUUID() + extension;
        Path target = root.resolve(fileName).normalize();
        if (!target.startsWith(root)) throw new IllegalStateException("非法上传路径");
        Files.copy(new java.io.ByteArrayInputStream(bytes), target, StandardCopyOption.REPLACE_EXISTING);
        return new StoredImage(uploadBaseUrl.replaceAll("/$", "") + "/" + fileName, fileName);
    }

    public record CameraCaptureRequest(String cameraId, Integer channelNo, Integer quality) {}
    private record StoredImage(String url, String fileName) {}
}
