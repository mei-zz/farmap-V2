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
public class ModelCallRequest {
    private String requestId;
    private String runId;
    private AiTaskType taskType;
    private String systemPrompt;
    private String userPrompt;
    @Builder.Default
    private List<String> imageUrls = new ArrayList<>();
    private boolean structuredOutput;
    @Builder.Default
    private Map<String, Object> context = new LinkedHashMap<>();
}
