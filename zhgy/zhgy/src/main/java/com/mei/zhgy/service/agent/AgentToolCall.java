package com.mei.zhgy.service.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentToolCall {
    private String id;
    private String toolName;
    @Builder.Default private ToolStatus status = ToolStatus.PENDING;
    @Builder.Default private Map<String, Object> input = new LinkedHashMap<>();
    @Builder.Default private Map<String, Object> output = new LinkedHashMap<>();
    private String errorCode;
    private String errorMessage;
    private Instant startedAt;
    private Instant completedAt;
    private Long durationMs;
}
