package com.mei.zhgy.service.expert;

/** Stable fail-closed error for the Expert Review and learning-candidate boundary. */
public class ExpertReviewWriteException extends RuntimeException {
    private final String code;
    private final String reviewId;
    private final String candidateId;

    public ExpertReviewWriteException(String code, String message) { this(code, message, null, null, null); }
    public ExpertReviewWriteException(String code, String message, Throwable cause) { this(code, message, cause, null, null); }
    public ExpertReviewWriteException(String code, String message, String reviewId, String candidateId) { this(code, message, null, reviewId, candidateId); }

    private ExpertReviewWriteException(String code, String message, Throwable cause, String reviewId, String candidateId) {
        super(message, cause);
        this.code = code;
        this.reviewId = reviewId;
        this.candidateId = candidateId;
    }

    public String getCode() { return code; }
    public String getReviewId() { return reviewId; }
    public String getCandidateId() { return candidateId; }

    public static ExpertReviewWriteException validation(String message) {
        return new ExpertReviewWriteException("VALIDATION_FAILED", message);
    }

    public static ExpertReviewWriteException notFound(String reviewId) {
        return new ExpertReviewWriteException("REVIEW_NOT_FOUND", "Expert Review 不存在", reviewId, null);
    }

    public static ExpertReviewWriteException candidateNotFound(String candidateId) {
        return new ExpertReviewWriteException("CANDIDATE_NOT_FOUND", "Historical Case Candidate 不存在", null, candidateId);
    }

    public static ExpertReviewWriteException forbidden(String reviewId) {
        return new ExpertReviewWriteException("FORBIDDEN", "无权访问该 Expert Review", reviewId, null);
    }

    public static ExpertReviewWriteException provenance(String message, String reviewId) {
        return new ExpertReviewWriteException("REVIEW_PROVENANCE_CONFLICT", message, reviewId, null);
    }

    public static ExpertReviewWriteException conflict(String code, String message, String reviewId) {
        return new ExpertReviewWriteException(code, message, reviewId, null);
    }

    public static ExpertReviewWriteException persistence(String message, Throwable cause) {
        return new ExpertReviewWriteException("EXPERT_REVIEW_PERSISTENCE_UNAVAILABLE", message, cause);
    }
}
