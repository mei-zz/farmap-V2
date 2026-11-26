-- FarMap V5.6.3: durable confirmation-backed orchard profile memory proposals.
--
-- A proposal row is metadata only. It is never active memory. Confirmation is
-- idempotent, revalidates current farm/field access, writes through the existing
-- copilot_long_term_memories service, and records the resulting memory_id only
-- after that write succeeds. All profile values are self-reported user context,
-- not regulatory certification or verified agronomic evidence.
CREATE TABLE IF NOT EXISTS copilot_memory_proposals (
    proposal_id VARCHAR(96) NOT NULL,
    owner_user_id BIGINT NOT NULL,
    farm_id VARCHAR(128) NOT NULL,
    field_id VARCHAR(128) NULL,
    memory_scope VARCHAR(16) NOT NULL,
    profile_key VARCHAR(32) NOT NULL,
    profile_value VARCHAR(256) NOT NULL,
    status VARCHAR(16) NOT NULL,
    memory_id VARCHAR(96) NULL,
    source_conversation_id VARCHAR(96) NULL,
    source_turn_id VARCHAR(96) NULL,
    created_at TIMESTAMP(3) NOT NULL,
    confirmed_at TIMESTAMP(3) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (proposal_id),
    INDEX idx_copilot_proposal_owner_status (owner_user_id, status),
    INDEX idx_copilot_proposal_scope (owner_user_id, farm_id, field_id),
    INDEX idx_copilot_proposal_memory (memory_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
