package com.mei.zhgy.service.camera;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientResponseException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class CameraSnapshotProvider {
    @Value("${hikyun.client-id:}") private String clientId;
    @Value("${hikyun.client-secret:}") private String clientSecret;
    @Value("${hikyun.capture-url:https://api2.hik-cloud.com/api/v1/open/basic/channels/actions/capture}") private String captureUrl;
    @Value("${zhgy.upload.dir:./uploads}") private String uploadDir;
    @Value("${zhgy.upload.base-url:http://localhost:24009/uploads}") private String uploadBaseUrl;
    private final FarmCameraService farmCameraService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Long> lastCaptureAt = new ConcurrentHashMap<>();
    private final Map<String, BrowserFrame> browserFrames = new ConcurrentHashMap<>();
    private static final long BROWSER_FRAME_TTL_MS = 2 * 60 * 60 * 1000L;
    private record BrowserFrame(Snapshot snapshot, long savedAt) {}

    public CameraSnapshotProvider(FarmCameraService farmCameraService) {
        this.farmCameraService = farmCameraService;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5000);
        requestFactory.setReadTimeout(15_000);
        this.restTemplate = new RestTemplate(requestFactory);
    }
    public record Snapshot(String imageUrl, String capturedAt, String source) {}
    public static class CaptureRateLimitException extends IllegalStateException {
        public CaptureRateLimitException(String message) { super(message); }
    }
    public static class CameraOfflineException extends IllegalStateException {
        public CameraOfflineException() { super("摄像头已离线"); }
    }
    public static class CaptureNotConfiguredException extends IllegalStateException {
        public CaptureNotConfiguredException() { super("未配置云眸 Open API 抓图凭据"); }
    }
    public Snapshot capture(String cameraId, Long actorId) {
        // Check ownership before consulting the cache or requesting the vendor.
        if (actorId == null) throw new IllegalArgumentException("用户未登录");
        if (cameraId == null || cameraId.isBlank()) throw new IllegalArgumentException("cameraId 不能为空");
        FarmCamera camera = farmCameraService.get(cameraId, actorId);
        try { return captureRemote(camera, null, null); }
        catch (CameraOfflineException offline) { throw offline; }
        catch (IllegalStateException error) {
            BrowserFrame frame = recentBrowserFrame(camera.cameraId());
            if (frame != null) return frame.snapshot();
            throw error;
        }
    }
    /** Register only a frame already stored by the authenticated upload endpoint. */
    public void rememberBrowserFrame(FarmCamera camera, String imageUrl, String capturedAt) {
        if (camera == null || imageUrl == null || imageUrl.isBlank()) throw new IllegalArgumentException("监控画面不可用");
        browserFrames.put(camera.cameraId(), new BrowserFrame(new Snapshot(imageUrl, capturedAt, "BROWSER_CAMERA_FRAME"), System.currentTimeMillis()));
    }
    public Snapshot capture(String cameraId, Long actorId, Integer requestedChannel, Integer requestedQuality) {
        if (actorId == null) throw new IllegalArgumentException("用户未登录");
        if (cameraId == null || cameraId.isBlank()) throw new IllegalArgumentException("cameraId 不能为空");
        return captureRemote(farmCameraService.get(cameraId, actorId), requestedChannel, requestedQuality);
    }

    public boolean hasRecentBrowserFrame(FarmCamera authorizedCamera) {
        return recentBrowserFrame(authorizedCamera.cameraId()) != null;
    }

    public boolean remoteCaptureConfigured() {
        return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }

    private BrowserFrame recentBrowserFrame(String cameraId) {
        BrowserFrame frame = browserFrames.get(cameraId);
        if (frame != null && System.currentTimeMillis() - frame.savedAt() > BROWSER_FRAME_TTL_MS) {
            browserFrames.remove(cameraId, frame);
            return null;
        }
        return frame;
    }

    private Snapshot captureRemote(FarmCamera camera, Integer requestedChannel, Integer requestedQuality) {
        if (!remoteCaptureConfigured()) throw new CaptureNotConfiguredException();
        int channelNo = requestedChannel == null || requestedChannel < 1 ? 1 : requestedChannel;
        int quality = requestedQuality == null ? 3 : Math.max(0, Math.min(3, requestedQuality));
        String key = camera.cameraId() + ":" + channelNo;
        long now = System.currentTimeMillis();
        final long[] previous = {0};
        lastCaptureAt.compute(key, (k, old) -> {
            if (old != null && now - old < 5000L) { previous[0] = old; return old; }
            return now;
        });
        if (previous[0] != 0) {
            long seconds = Math.max(1L, (5000L - (now - previous[0]) + 999L) / 1000L);
            throw new CaptureRateLimitException("同一摄像头抓拍过于频繁，请约 " + seconds + " 秒后重试");
        }
        try {
            String accessToken = requestCaptureAccessToken();
            Map<String, Object> body = new HashMap<>();
            body.put("deviceSerial", camera.serial()); body.put("channelNo", channelNo); body.put("quality", quality);
            HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); headers.setBearerAuth(accessToken);
            ResponseEntity<String> response = restTemplate.exchange(captureUrl, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            JsonNode json = objectMapper.readTree(response.getBody());
            String code = json.path("code").asText();
            String url = json.path("data").path("picUrl").asText("");
            if (!response.getStatusCode().is2xxSuccessful() || url.isBlank() || !("200".equals(code) || "0".equals(code))) {
                if (explicitOffline(json)) throw new CameraOfflineException();
                throw new IllegalStateException(json.path("message").asText("该摄像头暂时无法抓取当前画面"));
            }
            StoredImage stored = downloadToUploadBoundary(url);
            return new Snapshot(stored.url(), Instant.now().toString(), "HIKVISION_REMOTE_CAPTURE");
        } catch (RestClientResponseException error) {
            try {
                if (explicitOffline(objectMapper.readTree(error.getResponseBodyAsString()))) throw new CameraOfflineException();
            } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) { /* The provider did not return a structured offline signal. */ }
            throw new IllegalStateException("摄像头抓拍服务返回错误", error);
        } catch (IllegalStateException error) { throw error; }
        catch (Exception error) {
            log.warn("海康摄像头抓图失败，cameraId={}", camera.cameraId(), error);
            throw new IllegalStateException("摄像头抓图失败，请确认设备在线并稍后重试", error);
        }
    }

    private boolean explicitOffline(JsonNode json) {
        if (json == null) return false;
        String message = (json.path("message").asText("") + " " + json.path("msg").asText("")).toLowerCase(Locale.ROOT);
        return message.contains("设备离线") || message.contains("摄像头离线") || message.contains("通道离线")
                || message.contains("device offline") || message.contains("device is offline") || message.contains("camera offline");
    }
    private String requestCaptureAccessToken() throws Exception {
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

    private record StoredImage(String url, String fileName) {}
}
