package com.mei.zhgy.service.knowledge;

/** Closed error boundary for the persisted Knowledge Backend. */
public class KnowledgeBackendException extends RuntimeException {
    private final String code;
    private final String resourceId;

    public KnowledgeBackendException(String code, String message) { this(code, message, null, null); }
    public KnowledgeBackendException(String code, String message, Throwable cause) { this(code, message, cause, null); }
    public KnowledgeBackendException(String code, String message, String resourceId) { this(code, message, null, resourceId); }
    private KnowledgeBackendException(String code, String message, Throwable cause, String resourceId) {
        super(message, cause); this.code = code; this.resourceId = resourceId;
    }
    public String getCode() { return code; }
    public String getResourceId() { return resourceId; }
    public static KnowledgeBackendException validation(String message) { return new KnowledgeBackendException("VALIDATION_FAILED", message); }
    public static KnowledgeBackendException notFound(String code, String message, String id) { return new KnowledgeBackendException(code, message, id); }
    public static KnowledgeBackendException forbidden(String id) { return new KnowledgeBackendException("FORBIDDEN", "无权访问该知识对象", id); }
    public static KnowledgeBackendException conflict(String code, String message, String id) { return new KnowledgeBackendException(code, message, id); }
    public static KnowledgeBackendException persistence(String message, Throwable cause) { return new KnowledgeBackendException("KNOWLEDGE_PERSISTENCE_UNAVAILABLE", message, cause); }
}
