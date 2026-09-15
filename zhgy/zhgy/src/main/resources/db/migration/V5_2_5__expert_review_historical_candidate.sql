-- FarMap V2 Milestone 5.2.5: stable Expert Review and Historical Case Candidate foundation.
-- The legacy agent_expert_reviews table is diagnosis-keyed and remains readable.
-- The additive V2 table provides independent review identity and Outcome uniqueness.

CREATE TABLE IF NOT EXISTS agent_expert_reviews_v2 (
    review_id VARCHAR(96) NOT NULL,
    diagnosis_id VARCHAR(96) NOT NULL,
    task_id VARCHAR(80) NULL,
    outcome_id VARCHAR(96) NULL,
    recommendation_id VARCHAR(100) NULL,
    scope VARCHAR(32) NOT NULL,
    diagnosis_only_key VARCHAR(96) NULL,
    diagnosis_judgment VARCHAR(32) NULL,
    recommendation_judgment VARCHAR(32) NULL,
    outcome_judgment VARCHAR(32) NULL,
    diagnosis_correction TEXT NULL,
    recommendation_correction TEXT NULL,
    expert_confidence DOUBLE NULL,
    notes TEXT NULL,
    reviewer_user_id BIGINT NULL,
    subject_owner_user_id BIGINT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (review_id),
    UNIQUE KEY uk_expert_review_v2_outcome (outcome_id),
    UNIQUE KEY uk_expert_review_v2_diag_only (diagnosis_only_key),
    INDEX idx_expert_review_v2_diag (diagnosis_id),
    INDEX idx_expert_review_v2_owner (subject_owner_user_id)
);

CREATE TABLE IF NOT EXISTS expert_review_events (
    event_id VARCHAR(112) NOT NULL,
    review_id VARCHAR(96) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    actor_id BIGINT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    before_json LONGTEXT NULL,
    after_json LONGTEXT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (event_id),
    INDEX idx_expert_review_event_review (review_id),
    INDEX idx_expert_review_event_created (created_at)
);

CREATE TABLE IF NOT EXISTS historical_case_candidates (
    candidate_id VARCHAR(112) NOT NULL,
    review_id VARCHAR(96) NOT NULL,
    diagnosis_id VARCHAR(96) NOT NULL,
    task_id VARCHAR(80) NULL,
    outcome_id VARCHAR(96) NULL,
    status VARCHAR(32) NOT NULL,
    eligibility_reason VARCHAR(255) NULL,
    learning_value VARCHAR(64) NULL,
    owner_user_id BIGINT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (candidate_id),
    UNIQUE KEY uk_historical_candidate_review (review_id),
    INDEX idx_historical_candidate_owner (owner_user_id),
    INDEX idx_historical_candidate_status (status)
);
