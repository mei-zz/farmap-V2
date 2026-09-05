package com.mei.zhgy.service.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelCallResult {
    private boolean success;
    private String content;
    private String requestedModel;
    private String selectedModel;
    private ModelRole modelRole;
    private String routingReason;
    private boolean escalated;
    @Builder.Default
    private List<String> fallbackChain = new ArrayList<>();
    private Long latencyMs;
    private ModelUsageMetadata usage;
    private String errorType;
    private String errorMessage;
    @Builder.Default
    private Map<String, Object> metadata = new LinkedHashMap<>();
}
