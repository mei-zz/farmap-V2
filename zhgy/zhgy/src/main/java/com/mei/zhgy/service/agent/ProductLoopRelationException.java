package com.mei.zhgy.service.agent;

/** Fail-closed error for invalid Diagnosis → Recommendation → Agent relationships. */
public class ProductLoopRelationException extends RuntimeException {
    private final String code;
    private final String diagnosisId;
    private final String recommendationId;

    public ProductLoopRelationException(String code, String message, String diagnosisId, String recommendationId) {
        super(message);
        this.code = code;
        this.diagnosisId = diagnosisId;
        this.recommendationId = recommendationId;
    }

    public String getCode() { return code; }
    public String getDiagnosisId() { return diagnosisId; }
    public String getRecommendationId() { return recommendationId; }

    public static ProductLoopRelationException validation(String message, String diagnosisId, String recommendationId) {
        return new ProductLoopRelationException("VALIDATION_FAILED", message, diagnosisId, recommendationId);
    }

    public static ProductLoopRelationException diagnosisNotFound(String diagnosisId) {
        return new ProductLoopRelationException("DIAGNOSIS_NOT_FOUND", "Diagnosis 不存在或不可访问", diagnosisId, null);
    }

    public static ProductLoopRelationException recommendationNotFound(String diagnosisId, String recommendationId) {
        return new ProductLoopRelationException("RECOMMENDATION_NOT_FOUND", "Recommendation 不属于指定 Diagnosis", diagnosisId, recommendationId);
    }

    public static ProductLoopRelationException conflict(String message, String diagnosisId, String recommendationId) {
        return new ProductLoopRelationException("PROVENANCE_CONFLICT", message, diagnosisId, recommendationId);
    }
}
