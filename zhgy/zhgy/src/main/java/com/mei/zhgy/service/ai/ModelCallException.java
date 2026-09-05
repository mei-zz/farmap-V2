package com.mei.zhgy.service.ai;

public class ModelCallException extends RuntimeException {
    private final String errorType;

    public ModelCallException(String errorType, String message) {
        super(message);
        this.errorType = errorType;
    }

    public ModelCallException(String errorType, String message, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
    }

    public String getErrorType() {
        return errorType;
    }
}
