package com.mei.zhgy.service.copilot;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

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
    private Instant createdAt;
}
