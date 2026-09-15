package com.mei.zhgy.service.copilot;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Deterministically extracted candidate; it is never written without server validation. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CopilotMemoryCandidate {
    private CopilotMemoryScope scope;
    private String scopeId;
    private CopilotMemoryType type;
    private String key;
    private String value;
    private String displayText;
}
