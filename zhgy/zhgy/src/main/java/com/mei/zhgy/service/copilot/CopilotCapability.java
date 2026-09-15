package com.mei.zhgy.service.copilot;

/** Canonical product capability, intentionally separate from natural-language intent. */
public enum CopilotCapability {
    CONTEXTUAL_QA,
    FARM_CONTEXT_READ,
    FIELD_CONTEXT_READ,
    PRIORITIES_READ,
    HISTORICAL_CASE_READ,
    DIAGNOSIS_CREATE,
    DIAGNOSIS_READ,
    RECOMMENDATION_READ,
    AGENT_START,
    AGENT_READ,
    OPERATIONS_READ,
    OUTCOME_READ,
    KNOWLEDGE_SEARCH,
    NAVIGATE,
    MEMORY_WRITE,
    MEMORY_READ,
    MEMORY_REVOKE
}
