-- FarMap V2 Milestone 5.2.1
-- Apply with the deployment migration runner before enabling production writes.
-- Legacy Operations rows keep action_id NULL; every new Agent-created task must set it.

CREATE TABLE IF NOT EXISTS agent_approvals (
    run_id VARCHAR(128) NOT NULL,
    action_id VARCHAR(180) NOT NULL,
    decision_state VARCHAR(32) NOT NULL,
    execution_state VARCHAR(32) NOT NULL,
    actor_id VARCHAR(128),
    decided_at TIMESTAMP(3) NULL,
    task_id VARCHAR(128),
    failure_code VARCHAR(64),
    failure_message TEXT,
    PRIMARY KEY (run_id, action_id)
);

ALTER TABLE agent_operations_tasks ADD COLUMN action_id VARCHAR(180) NULL;
CREATE UNIQUE INDEX uk_agent_operations_action_id ON agent_operations_tasks(action_id);
