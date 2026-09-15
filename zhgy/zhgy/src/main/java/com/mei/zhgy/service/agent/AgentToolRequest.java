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
    /** Stable approval provenance used as the Operations task idempotency key. */
    private String actionId;
    /** Server-resolved provenance, never sourced from LLM action payload. */
    private String diagnosisId;
    private String recommendationId;
    /** Server-provided owner and actor context; never read from the LLM payload. */
    private Long ownerUserId;
    private String actorId;
    private String goal;
    private AgentContext context;
    private boolean approvalGranted;
    @Builder.Default private Map<String, Object> priorOutputs = new LinkedHashMap<>();
    @Builder.Default private Map<String, Object> attributes = new LinkedHashMap<>();
}
