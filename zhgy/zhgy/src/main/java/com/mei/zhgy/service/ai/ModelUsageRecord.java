package com.mei.zhgy.service.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelUsageRecord {
    private String requestId;
    private String runId;
    private AiTaskType taskType;
    private String requestedModel;
    private String selectedModel;
    private List<String> fallbackChain;
    private ModelRole modelRole;
    private String routingReason;
    private boolean escalated;
    private String timestamp;
    private List<String> inputModalities;
    private Long latencyMs;
    private boolean success;
    private String errorType;
    private Long inputTokens;
    private Long outputTokens;
    private Long thinkingTokens;
    private Long totalTokens;
}
