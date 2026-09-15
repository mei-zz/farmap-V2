-- Milestone 5.2.3: Recommendation provenance on OperationsTask.
-- Nullable keeps legacy tasks readable; no unique constraint is added because
-- one Recommendation may produce multiple AgentActions.
ALTER TABLE agent_operations_tasks
    ADD COLUMN recommendation_id VARCHAR(100) NULL;

CREATE INDEX idx_agent_operations_recommendation_id
    ON agent_operations_tasks (recommendation_id);
