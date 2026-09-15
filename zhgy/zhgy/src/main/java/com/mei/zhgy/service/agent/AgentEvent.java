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
public class AgentEvent {
    private long sequence;
    private Instant timestamp;
    private String type;
    @Builder.Default private Map<String, Object> payload = new LinkedHashMap<>();
}
