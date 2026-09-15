package com.mei.zhgy.service.historical;

import java.util.Map;

/**
 * Pure eligibility policy for a learning candidate. It deliberately accepts
 * failure/correction cases and rejects only incomplete or inconclusive review
 * signals. No model output or text heuristic participates in this decision.
 */
public final class HistoricalCaseCandidateEligibilityEvaluator {
    public Evaluation evaluate(Map<String, Object> review, Map<String, Object> task, Map<String, Object> outcome) {
        if (review == null) return Evaluation.notEligible("REVIEW_REQUIRED");
        if (!"OUTCOME_REVIEW".equalsIgnoreCase(text(review.get("scope")))) return Evaluation.notEligible("OUTCOME_REVIEW_REQUIRED");
        if (task == null || !"COMPLETED".equalsIgnoreCase(text(task.get("status")))) return Evaluation.notEligible("COMPLETED_TASK_REQUIRED");
        if (outcome == null) return Evaluation.notEligible("OUTCOME_REQUIRED");
        if (blank(review.get("diagnosisJudgment")) || "NOT_REVIEWED".equalsIgnoreCase(text(review.get("diagnosisJudgment")))) {
            return Evaluation.notEligible("DIAGNOSIS_JUDGMENT_REQUIRED");
        }
        String outcomeJudgment = text(review.get("outcomeJudgment"));
        if (blank(outcomeJudgment) || "NOT_REVIEWED".equalsIgnoreCase(outcomeJudgment)) return Evaluation.notEligible("OUTCOME_JUDGMENT_REQUIRED");
        if ("INCONCLUSIVE".equalsIgnoreCase(outcomeJudgment)) return Evaluation.notEligible("OUTCOME_INCONCLUSIVE");
        Object confidence = review.get("expertConfidence");
        if (!(confidence instanceof Number) || ((Number) confidence).doubleValue() < 0.7D) return Evaluation.notEligible("EXPERT_CONFIDENCE_BELOW_THRESHOLD");
        if (!blank(review.get("recommendationId"))) {
            String judgment = text(review.get("recommendationJudgment"));
            if (blank(judgment) || "NOT_REVIEWED".equalsIgnoreCase(judgment)) return Evaluation.notEligible("RECOMMENDATION_JUDGMENT_REQUIRED");
        }
        String diagnosis = text(review.get("diagnosisJudgment"));
        String recommendation = text(review.get("recommendationJudgment"));
        String learningValue = "INCORRECT".equalsIgnoreCase(diagnosis)
                || "INAPPROPRIATE".equalsIgnoreCase(recommendation)
                || "UNSUCCESSFUL".equalsIgnoreCase(outcomeJudgment)
                ? "CORRECTION_CASE"
                : "SUCCESS_CASE";
        return Evaluation.eligible(learningValue,
                "完整 Task/Outcome provenance，专家判断完整且 confidence >= 0.7；失败或纠正案例保留为学习信号");
    }

    private boolean blank(Object value) { return value == null || text(value).isBlank(); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }

    public static final class Evaluation {
        private final boolean eligible;
        private final String reason;
        private final String learningValue;

        private Evaluation(boolean eligible, String reason, String learningValue) {
            this.eligible = eligible; this.reason = reason; this.learningValue = learningValue;
        }
        public static Evaluation eligible(String value, String reason) { return new Evaluation(true, reason, value); }
        public static Evaluation notEligible(String reason) { return new Evaluation(false, reason, null); }
        public boolean eligible() { return eligible; }
        public String reason() { return reason; }
        public String learningValue() { return learningValue; }
    }
}
