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
public class AgentPlan {
    @Builder.Default private List<AgentPlanStep> steps = new ArrayList<>();
    private int maxSteps;
    private int maxToolCalls;
    private long timeoutMs;
    private String plannerModel;
    private String schemaVersion;
}
