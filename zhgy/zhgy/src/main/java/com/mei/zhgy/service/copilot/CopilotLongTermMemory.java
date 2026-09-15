package com.mei.zhgy.service.copilot;

import com.fasterxml.jackson.annotation.JsonInclude;
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
public class CopilotLongTermMemory {
    private String memoryId;
    private Long userId;
    private CopilotMemoryScope scope;
    private String scopeId;
    private CopilotMemoryType type;
    private String key;
    private String value;
    private String displayText;
    private CopilotMemoryStatus status;
    private String sourceConversationId;
    private String sourceTurnId;
    private Instant createdAt;
    private Instant updatedAt;
    private long version;
    private String supersedesMemoryId;
}
