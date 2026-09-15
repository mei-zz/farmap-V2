package com.mei.zhgy.service.ai;

/** Typed failure returned by the versioned Python Intelligence Plane. */
public class IntelligenceGatewayException extends RuntimeException {
    private final String code;
    private final String requestId;
    private final boolean retryable;

    public IntelligenceGatewayException(String code, String message, String requestId, boolean retryable) {
        super(message);
        this.code = code;
        this.requestId = requestId;
        this.retryable = retryable;
    }

    public IntelligenceGatewayException(String code, String message, String requestId, boolean retryable, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.requestId = requestId;
        this.retryable = retryable;
    }

    public String getCode() { return code; }
    public String getRequestId() { return requestId; }
    public boolean isRetryable() { return retryable; }
}
