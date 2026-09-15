package com.mei.zhgy.service.knowledge;

/** Persisted admin ingestion lifecycle. READY is the only searchable state. */
public enum KnowledgeIngestionStatus {
    UPLOADED, PROCESSING, READY, FAILED
}
