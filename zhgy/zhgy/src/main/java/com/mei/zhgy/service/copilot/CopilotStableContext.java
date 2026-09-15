package com.mei.zhgy.service.copilot;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** Only stable IDs are persisted in the bounded session context. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CopilotStableContext {
    private String farmId;
    private String fieldId;
    private String diagnosisId;
    private String recommendationId;
    private String runId;
    private String actionId;
    private String taskId;
    private String outcomeId;
    private CopilotIntent lastIntent;
    private Instant updatedAt;

    public CopilotStableContext copy() {
        return CopilotStableContext.builder()
                .farmId(farmId).fieldId(fieldId).diagnosisId(diagnosisId)
                .recommendationId(recommendationId).runId(runId).actionId(actionId)
                .taskId(taskId).outcomeId(outcomeId).lastIntent(lastIntent).updatedAt(updatedAt)
                .build();
    }
}
