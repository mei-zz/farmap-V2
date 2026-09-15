package com.mei.zhgy.service.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelUsageMetadata {
    private String provider;
    private String model;
    private Long inputTokens;
    private Long outputTokens;
    private Long thinkingTokens;
    private Long totalTokens;
    private String finishReason;
}
