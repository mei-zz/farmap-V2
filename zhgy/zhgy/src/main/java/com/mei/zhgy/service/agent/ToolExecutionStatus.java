package com.mei.zhgy.service.agent;

/** Semantic outcome of one tool execution. ToolStatus remains the legacy lifecycle status. */
public enum ToolExecutionStatus {
    SUCCESS,
    EMPTY,
    UNAVAILABLE,
    RETRYABLE_FAILURE,
    NON_RETRYABLE_FAILURE,
    CONTRACT_FAILURE
}
