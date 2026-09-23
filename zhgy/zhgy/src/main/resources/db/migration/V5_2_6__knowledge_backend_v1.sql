-- FarMap V2 Milestone 5.2.6: minimum persisted Knowledge Backend V1.
-- Apply with the deployment migration runner. Runtime CREATE TABLE bridges are
-- retained for current installations until a formal runner is enabled.
CREATE TABLE IF NOT EXISTS knowledge_sources (
    source_id VARCHAR(112) NOT NULL,
    name VARCHAR(255) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    description TEXT NULL,
    origin_uri VARCHAR(255) NULL,
    owner_user_id BIGINT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (source_id),
    UNIQUE KEY uk_knowledge_source_origin (source_type, origin_uri),
    INDEX idx_knowledge_source_owner (owner_user_id),
    INDEX idx_knowledge_source_status (status)
)
  ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS knowledge_documents (
    document_id VARCHAR(112) NOT NULL,
    source_id VARCHAR(112) NOT NULL,
    title VARCHAR(255) NOT NULL,
    external_reference VARCHAR(512) NULL,
    owner_user_id BIGINT NULL,
    status VARCHAR(32) NOT NULL,
    current_version_id VARCHAR(112) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (document_id),
    INDEX idx_knowledge_document_source (source_id),
    INDEX idx_knowledge_document_owner (owner_user_id),
    INDEX idx_knowledge_document_current (current_version_id)
)
  ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS knowledge_versions (
    version_id VARCHAR(112) NOT NULL,
    document_id VARCHAR(112) NOT NULL,
    version_number INT NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    content LONGTEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    created_by BIGINT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (version_id),
    UNIQUE KEY uk_knowledge_version_hash (document_id, content_hash),
    UNIQUE KEY uk_knowledge_version_number (document_id, version_number),
    INDEX idx_knowledge_version_document (document_id)
)
  ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS knowledge_events (
    event_id VARCHAR(112) NOT NULL,
    entity_type VARCHAR(64) NOT NULL,
    entity_id VARCHAR(112) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    actor_id BIGINT NULL,
    metadata_json LONGTEXT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (event_id),
    INDEX idx_knowledge_event_entity (entity_id),
    INDEX idx_knowledge_event_created (created_at)
)
  ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
