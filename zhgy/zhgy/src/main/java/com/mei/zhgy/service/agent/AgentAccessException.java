package com.mei.zhgy.service.agent;

/** Authentication/ownership failure for Agent resources. */
public class AgentAccessException extends RuntimeException {
    private final int httpStatus;
    private final String code;

    public AgentAccessException(int httpStatus, String code, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public int getHttpStatus() { return httpStatus; }
    public String getCode() { return code; }

    public static AgentAccessException unauthenticated() {
        return new AgentAccessException(401, "UNAUTHENTICATED", "authentication required");
    }

    public static AgentAccessException forbidden() {
        return new AgentAccessException(403, "FORBIDDEN", "Agent resource is not accessible");
    }
}
