package com.mei.zhgy.service.field;

public class FieldAccessException extends RuntimeException {
    private final int httpStatus;
    private final String code;

    public FieldAccessException(int httpStatus, String code, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public int getHttpStatus() { return httpStatus; }
    public String getCode() { return code; }
}
