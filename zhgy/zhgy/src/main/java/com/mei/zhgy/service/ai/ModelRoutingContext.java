package com.mei.zhgy.service.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelRoutingContext {
    private Double retrievalQuality;
    private Double diagnosisConfidence;
    private boolean evidenceConflict;
    private boolean highRisk;
    private int imageCount;
    private boolean multipleCompetingHypotheses;
    private boolean explicitDeepAnalysis;
    private String modelPolicy;
}
