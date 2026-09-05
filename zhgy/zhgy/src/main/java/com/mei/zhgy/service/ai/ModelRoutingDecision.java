package com.mei.zhgy.service.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelRoutingDecision {
    private AiTaskType taskType;
    private String requestedModel;
    private ModelRole modelRole;
    private String routingReason;
    private boolean escalated;
    private List<String> fallbackModels;
}
