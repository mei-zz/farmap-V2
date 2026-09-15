package com.mei.zhgy.service.storage;

import com.mei.zhgy.service.camera.FarmCamera;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.PutObjectResult;
import com.qcloud.cos.region.Region;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;

/**
 * Server-side Tencent COS boundary for images captured from a browser monitor
 * tab. SecretId/SecretKey never leave the backend and are never sent to the UI.
 */
@Service
public class TencentCosStorageService {
    private static final long MAX_IMAGE_SIZE = 10 * 1024 * 1024L;

    @Value("${tencent-cos.enabled:false}")
    private boolean enabled;

    @Value("${tencent-cos.secret-id:}")
    private String secretId;

    @Value("${tencent-cos.secret-key:}")
    private String secretKey;

    @Value("${tencent-cos.region:}")
    private String region;

    @Value("${tencent-cos.bucket:}")
    private String bucket;

    @Value("${tencent-cos.public-base-url:}")
    private String publicBaseUrl;

    @Value("${tencent-cos.prefix:farmap/diagnosis}")
    private String prefix;

    private volatile COSClient client;

    public boolean configured() {
        return enabled && !blank(secretId) && !blank(secretKey) && !blank(region) && !blank(bucket);
    }

    public UploadResult upload(MultipartFile file, FarmCamera camera) throws IOException {
        validate(file);
        if (!configured()) {
            throw new IllegalStateException("腾讯云 COS 尚未配置，请在本机 .env 填写 TENCENT_COS_SECRET_ID 和 TENCENT_COS_SECRET_KEY");
        }

        String extension = "image/png".equalsIgnoreCase(file.getContentType()) ? ".png" : ".jpg";
        String fieldSegment = safeSegment(camera.fieldId(), "unassigned-field");
        String key = trimSlashes(prefix) + "/farm-" + camera.farmId()
                + "/" + fieldSegment + "/" + LocalDate.now(ZoneOffset.UTC)
                + "/camera-" + safeSegment(camera.serial(), "unknown") + "-" + UUID.randomUUID() + extension;
        return put(file, key);
    }

    /**
     * Uploads a user-selected field image. This is deliberately separate from
     * camera capture: the image may be a downloaded vendor screenshot or a
     * manually taken detail photo, so there is no requirement for a remote
     * camera API.
     */
    public UploadResult uploadDiagnosis(MultipartFile file, Integer farmId, String fieldId,
                                        String pointId, String cameraId) throws IOException {
        validate(file);
        if (!configured()) {
            throw new IllegalStateException("腾讯云 COS 尚未配置，请在本机 .env 填写 TENCENT_COS_SECRET_ID 和 TENCENT_COS_SECRET_KEY");
        }
        String extension = "image/png".equalsIgnoreCase(file.getContentType()) ? ".png" : ".jpg";
        String farmSegment = safeSegment(farmId == null ? null : String.valueOf(farmId), "unknown-farm");
        String fieldSegment = safeSegment(fieldId, "unassigned-field");
        String cameraSegment = safeSegment(cameraId, "manual-upload");
        String pointSegment = safeSegment(pointId, "unassigned-point");
        String key = trimSlashes(prefix) + "/farm-" + farmSegment + "/" + fieldSegment
                + "/point-" + pointSegment + "/" + LocalDate.now(ZoneOffset.UTC)
                + "/" + cameraSegment + "-" + UUID.randomUUID() + extension;
        return put(file, key);
    }

    private UploadResult put(MultipartFile file, String key) throws IOException {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType(file.getContentType());
        metadata.setContentLength(file.getSize());
        metadata.setCacheControl("public,max-age=86400");

        try (var input = file.getInputStream()) {
            PutObjectRequest request = new PutObjectRequest(bucket, key, input, metadata);
            PutObjectResult result = getClient().putObject(request);
            if (result == null) throw new IllegalStateException("腾讯云 COS 未返回上传结果");
        }
        return new UploadResult(key, buildReadUrl(key));
    }

    /** A stable bucket origin used by Diagnosis URL-boundary validation. */
    public String referenceBaseUrl() {
        if (!blank(publicBaseUrl)) return trimSlashes(publicBaseUrl);
        if (blank(bucket) || blank(region)) return "";
        return "https://" + bucket + ".cos." + region + ".myqcloud.com";
    }

    private String buildReadUrl(String key) {
        if (!blank(publicBaseUrl)) return trimSlashes(publicBaseUrl) + "/" + key;
        try {
            Date expires = Date.from(Instant.now().plus(Duration.ofHours(2)));
            URL url = getClient().generatePresignedUrl(bucket, key, expires, HttpMethodName.GET);
            return url.toExternalForm();
        } catch (Exception error) {
            throw new IllegalStateException("腾讯云 COS 图片地址生成失败", error);
        }
    }

    private COSClient getClient() {
        COSClient current = client;
        if (current != null) return current;
        synchronized (this) {
            if (client == null) {
                COSCredentials credentials = new BasicCOSCredentials(secretId, secretKey);
                ClientConfig config = new ClientConfig(new Region(region));
                config.setMaxConnectionsCount(20);
                config.setConnectionTimeout(10000);
                config.setSocketTimeout(30000);
                client = new COSClient(credentials, config);
            }
            return client;
        }
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请提供一张监控画面");
        if (file.getSize() > MAX_IMAGE_SIZE) throw new IllegalArgumentException("监控画面不能超过 10MB");
        String contentType = file.getContentType();
        if (!("image/jpeg".equalsIgnoreCase(contentType) || "image/png".equalsIgnoreCase(contentType))) {
            throw new IllegalArgumentException("监控画面只支持 JPG/PNG");
        }
    }

    private String safeSegment(String value, String fallback) {
        if (blank(value)) return fallback;
        return value.replaceAll("[^a-zA-Z0-9._-]", "-");
    }

    private String trimSlashes(String value) {
        return value == null ? "" : value.replaceAll("/+$", "").replaceAll("^/+", "");
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }

    @PreDestroy
    public void close() {
        COSClient current = client;
        if (current != null) current.shutdown();
    }

    public record UploadResult(String objectKey, String url) {}
}
