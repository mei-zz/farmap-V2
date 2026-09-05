package com.mei.zhgy.service.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentPlanStep {
    private String id;
    private String title;
    private String description;
    private String toolName;
    private boolean readOnly;
    @Builder.Default private List<String> dependsOn = new ArrayList<>();
    @Builder.Default private StepStatus status = StepStatus.PENDING;
}
