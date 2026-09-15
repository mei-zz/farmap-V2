-- FarMap V2 Milestone 5.2.4: Operations lifecycle, canonical Outcome and audit trail.
-- Nullable lifecycle columns preserve legacy OPEN rows without guessing history.

ALTER TABLE agent_operations_tasks ADD COLUMN updated_at TIMESTAMP(3) NULL;
ALTER TABLE agent_operations_tasks ADD COLUMN owner_user_id BIGINT NULL;
ALTER TABLE agent_operations_tasks ADD COLUMN assignee VARCHAR(128) NULL;
ALTER TABLE agent_operations_tasks ADD COLUMN assigned_at TIMESTAMP(3) NULL;
ALTER TABLE agent_operations_tasks ADD COLUMN started_at TIMESTAMP(3) NULL;
ALTER TABLE agent_operations_tasks ADD COLUMN completed_at TIMESTAMP(3) NULL;
ALTER TABLE agent_operations_tasks ADD COLUMN cancelled_at TIMESTAMP(3) NULL;
ALTER TABLE agent_operations_tasks ADD COLUMN cancelled_reason TEXT NULL;
ALTER TABLE agent_operations_tasks ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

CREATE INDEX idx_operations_task_status ON agent_operations_tasks(status);
CREATE INDEX idx_operations_task_assignee ON agent_operations_tasks(assignee);

CREATE TABLE agent_operation_outcomes (
    outcome_id VARCHAR(96) NOT NULL,
    task_id VARCHAR(80) NOT NULL,
    result_type VARCHAR(64) NOT NULL,
    summary TEXT NOT NULL,
    execution_evidence TEXT NULL,
    notes TEXT NULL,
    actor_id VARCHAR(128) NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (outcome_id),
    UNIQUE KEY uk_operation_outcome_task (task_id),
    INDEX idx_operation_outcome_created (created_at)
);

CREATE TABLE operations_task_events (
    event_id VARCHAR(96) NOT NULL,
    task_id VARCHAR(80) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    from_status VARCHAR(32) NULL,
    to_status VARCHAR(32) NULL,
    actor VARCHAR(128) NOT NULL,
    reason TEXT NULL,
    metadata_json TEXT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (event_id),
    INDEX idx_operations_event_task (task_id),
    INDEX idx_operations_event_created (created_at)
);
