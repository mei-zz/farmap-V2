package com.mei.zhgy.service.copilot;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Bounded clarification state; it is not a prompt or hidden reasoning trace. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CopilotPendingClarification {
    private String type;
    private String question;
    private int createdAtTurn;
    private int expiresAfterTurns;
}
