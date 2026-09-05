package com.mei.zhgy.service.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 配置化升级规则，避免阈值散落在 Diagnosis 业务代码。 */
@Component
public class ModelEscalationPolicy {
    @Value("${farmap.ai.router.expert-escalation.confidence-threshold:0.65}")
    private double confidenceThreshold = 0.65;

    @Value("${farmap.ai.router.expert-escalation.retrieval-quality-threshold:0.55}")
    private double retrievalQualityThreshold = 0.55;

    @Value("${farmap.ai.router.expert-escalation.image-count-threshold:4}")
    private int imageCountThreshold = 4;

    @Value("${farmap.ai.router.expert-escalation.evidence-conflict:true}")
    private boolean evidenceConflictEnabled = true;

    @Value("${farmap.ai.router.expert-escalation.high-risk:true}")
    private boolean highRiskEnabled = true;

    public boolean shouldEscalate(ModelRoutingContext context) {
        if (context == null) {
            return false;
        }
        return (evidenceConflictEnabled && context.isEvidenceConflict())
                || (highRiskEnabled && context.isHighRisk())
                || (context.getDiagnosisConfidence() != null && context.getDiagnosisConfidence() < confidenceThreshold)
                || (context.getRetrievalQuality() != null && context.getRetrievalQuality() < retrievalQualityThreshold)
                || context.getImageCount() >= imageCountThreshold
                || context.isMultipleCompetingHypotheses()
                || context.isExplicitDeepAnalysis();
    }

    public String reason(ModelRoutingContext context) {
        if (context == null) {
            return "configured_multimodal_route";
        }
        if (context.isEvidenceConflict()) return "evidence_conflict";
        if (context.getDiagnosisConfidence() != null && context.getDiagnosisConfidence() < confidenceThreshold) return "low_confidence";
        if (context.getRetrievalQuality() != null && context.getRetrievalQuality() < retrievalQualityThreshold) return "low_retrieval_quality";
        if (context.isHighRisk()) return "high_risk";
        if (context.getImageCount() >= imageCountThreshold) return "complex_multimodal_input";
        if (context.isMultipleCompetingHypotheses()) return "competing_hypotheses";
        if (context.isExplicitDeepAnalysis()) return "explicit_deep_analysis";
        return "configured_multimodal_route";
    }
}
