-- Milestone 5.2.2: stable Diagnosis identity and immutable analysis snapshots.
CREATE TABLE IF NOT EXISTS agent_diagnoses (
    diagnosis_id VARCHAR(96) PRIMARY KEY,
    source_run_id VARCHAR(96) NOT NULL,
    user_id BIGINT NULL,
    farm_id INT NULL,
    field_id VARCHAR(100) NOT NULL,
    crop VARCHAR(100),
    status VARCHAR(32) NOT NULL,
    risk_level VARCHAR(32),
    confidence DOUBLE,
    summary TEXT,
    mode VARCHAR(32),
    payload_json LONGTEXT NOT NULL,
    created_at TIMESTAMP(3) NULL,
    updated_at TIMESTAMP(3) NULL,
    INDEX idx_agent_diagnoses_source_run (source_run_id),
    INDEX idx_agent_diagnoses_field (field_id),
    INDEX idx_agent_diagnoses_user (user_id)
);
