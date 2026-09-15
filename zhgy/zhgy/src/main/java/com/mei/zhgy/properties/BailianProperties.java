package com.mei.zhgy.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Local runtime configuration for the Alibaba Cloud Model Studio gateway.
 * Secrets are supplied by the process environment and are never exposed by
 * this class's string representation.
 */
@Component
@ConfigurationProperties(prefix = "bailian")
public class BailianProperties {
    private Api api = new Api();
    private String region = "beijing";
    private String workspaceId;

    public Api getApi() {
        return api;
    }

    public void setApi(Api api) {
        this.api = api == null ? new Api() : api;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(String workspaceId) {
        this.workspaceId = workspaceId;
    }

    public String getApiKey() {
        return api.getKey();
    }

    public String getBaseUrl() {
        return api.getUrl();
    }

    /** Accept both an OpenAI-compatible /v1 base URL and a full endpoint. */
    public String getChatCompletionsUrl() {
        String configured = getBaseUrl();
        if (configured == null || configured.trim().isEmpty()) {
            return Api.DEFAULT_URL;
        }
        String normalized = configured.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/chat/completions")) {
            return normalized;
        }
        return normalized + "/chat/completions";
    }

    public int getConnectTimeoutMs() {
        return api.getConnectTimeoutMs();
    }

    public int getReadTimeoutMs() {
        return api.getReadTimeoutMs();
    }

    public int getMaxRetries() {
        return api.getMaxRetries();
    }

    public long getRetryBackoffMs() {
        return api.getRetryBackoffMs();
    }

    public static class Api {
        private static final String DEFAULT_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
        private String key;
        private String url = DEFAULT_URL;
        private int connectTimeoutMs = 10000;
        private int readTimeoutMs = 120000;
        private int maxRetries = 1;
        private long retryBackoffMs = 250;

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = Math.max(0, Math.min(maxRetries, 2));
        }

        public long getRetryBackoffMs() {
            return retryBackoffMs;
        }

        public void setRetryBackoffMs(long retryBackoffMs) {
            this.retryBackoffMs = Math.max(0, Math.min(retryBackoffMs, 5000));
        }
    }
}
