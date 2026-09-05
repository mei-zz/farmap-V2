package com.mei.zhgy.service.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentToolRequest {
    private String runId;
    private String goal;
    private AgentContext context;
    private boolean approvalGranted;
    @Builder.Default private Map<String, Object> priorOutputs = new LinkedHashMap<>();
}
