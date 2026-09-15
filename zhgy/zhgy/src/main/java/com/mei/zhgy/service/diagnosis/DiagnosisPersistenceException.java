package com.mei.zhgy.service.diagnosis;

/** Fail-closed error for Diagnosis business-object persistence. */
public class DiagnosisPersistenceException extends RuntimeException {
    private final String code;

    public DiagnosisPersistenceException(String code, String message) { super(message); this.code = code; }
    public DiagnosisPersistenceException(String code, String message, Throwable cause) { super(message, cause); this.code = code; }
    public String getCode() { return code; }
}
