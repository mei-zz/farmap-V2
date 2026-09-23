package com.mei.zhgy.service.copilot;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CopilotConversationTurn {
    private String turnId;
    private String conversationId;
    @JsonIgnore
    private Long userId;
    private String role;
    private String message;
    private CopilotIntent intent;
    private String traceId;
    @JsonIgnore
    private String clientMessageId;
    @JsonIgnore
    private String resultJson;
    /** Safe assistant-only metadata projected from resultJson for conversation restore. */
    private Map<String, Object> responseMetadata;
    private Instant createdAt;
}
