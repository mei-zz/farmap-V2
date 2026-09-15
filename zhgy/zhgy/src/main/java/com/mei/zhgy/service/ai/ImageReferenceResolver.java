package com.mei.zhgy.service.ai;

import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Base64;

/**
 * Normalizes locally uploaded images before they cross a service boundary.
 *
 * The browser-facing upload URL is intentionally kept in evidence and preview
 * records, but local vision and external multimodal providers cannot reliably
 * fetch 127.0.0.1 or a Docker-only hostname. Converting only local object
 * references to a bounded data URI keeps the provenance URL unchanged while
 * making the actual inference request portable.
 */
public final class ImageReferenceResolver {
    private static final int MAX_BYTES = 12 * 1024 * 1024;

    private ImageReferenceResolver() {
    }

    public static String forVision(String reference) {
        return normalizeTrustedReference(reference);
    }

    public static String forLocalEmbedding(String reference) {
        return normalizeTrustedReference(reference);
    }

    public static String forModel(String reference) {
        return normalizeLocalReference(reference);
    }

    private static String normalizeLocalReference(String reference) {
        if (!StringUtils.hasText(reference) || reference.startsWith("data:image/")) return reference;
        try {
            URI uri = URI.create(reference);
            if (!isLocalHost(uri.getHost())) return reference;
            return downloadAsDataUri(uri.toURL());
        } catch (Exception ignored) {
            // The downstream contract still returns a structured, actionable
            // error. Do not replace the original reference in persisted data.
            return reference;
        }
    }

    /**
     * COS objects are private in the normal deployment and are represented by
     * short-lived signed URLs. The local vision/embedding services cannot
     * always fetch those URLs directly, so trusted COS references are converted
     * to a bounded data URI at the Java boundary. Arbitrary remote URLs remain
     * untouched and are still rejected by the Python service allow-list.
     */
    private static String normalizeTrustedReference(String reference) {
        String normalized = normalizeLocalReference(reference);
        if (!StringUtils.hasText(normalized) || normalized.startsWith("data:image/")) return normalized;
        try {
            URI uri = URI.create(normalized);
            if (isTrustedRemoteHost(uri.getHost())) return downloadAsDataUri(uri.toURL());
        } catch (Exception ignored) {
            // Preserve the original reference so the downstream boundary can
            // return a structured fetch/allow-list error.
        }
        return normalized;
    }

    private static boolean isTrustedRemoteHost(String host) {
        if (!StringUtils.hasText(host)) return false;
        String normalized = host.toLowerCase();
        String configured = System.getenv("FARMAP_ALLOWED_IMAGE_HOSTS");
        if (configured != null) {
            for (String item : configured.split(",")) {
                if (normalized.equals(item.trim().toLowerCase())) return true;
            }
        }
        String bucket = System.getenv("TENCENT_COS_BUCKET");
        String region = System.getenv("TENCENT_COS_REGION");
        if (StringUtils.hasText(bucket) && StringUtils.hasText(region)) {
            if (normalized.equals((bucket + ".cos." + region + ".myqcloud.com").toLowerCase())
                    || normalized.equals((bucket + ".cos-internal." + region + ".myqcloud.com").toLowerCase())) {
                return true;
            }
        }
        String publicBase = System.getenv("TENCENT_COS_PUBLIC_BASE_URL");
        if (StringUtils.hasText(publicBase)) {
            try {
                String publicHost = URI.create(publicBase).getHost();
                return publicHost != null && normalized.equals(publicHost.toLowerCase());
            } catch (Exception ignored) {
                return false;
            }
        }
        return false;
    }

    private static boolean isLocalHost(String host) {
        if (!StringUtils.hasText(host)) return false;
        String normalized = host.toLowerCase();
        return "localhost".equals(normalized)
                || "127.0.0.1".equals(normalized)
                || "::1".equals(normalized)
                || "backend".equals(normalized);
    }

    private static String downloadAsDataUri(URL url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(10000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("GET");
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) throw new IllegalStateException("image fetch status " + status);
        String contentType = connection.getContentType();
        if (!StringUtils.hasText(contentType) || !contentType.toLowerCase().startsWith("image/")) {
            contentType = "image/jpeg";
        } else if (contentType.contains(";")) {
            contentType = contentType.substring(0, contentType.indexOf(';')).trim();
        }
        int declared = connection.getContentLength();
        if (declared > MAX_BYTES) throw new IllegalStateException("image too large");
        try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            int total = 0;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_BYTES) throw new IllegalStateException("image too large");
                output.write(buffer, 0, read);
            }
            if (total == 0) throw new IllegalStateException("image is empty");
            return "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(output.toByteArray());
        } finally {
            connection.disconnect();
        }
    }
}
