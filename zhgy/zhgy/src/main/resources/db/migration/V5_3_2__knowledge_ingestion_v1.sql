-- FarMap Phase D: admin-curated knowledge ingestion/index metadata.
-- Runtime schema bridge exists for local compatibility; production must run
-- this versioned migration before enabling the import endpoint.
CREATE TABLE IF NOT EXISTS knowledge_ingestions (
  ingestion_id VARCHAR(112) NOT NULL,
  source_id VARCHAR(112) NOT NULL,
  document_id VARCHAR(112) NOT NULL,
  version_id VARCHAR(112) NOT NULL,
  status VARCHAR(32) NOT NULL,
  source_file_name VARCHAR(255) NOT NULL,
  source_file_hash CHAR(64) NOT NULL,
  raw_storage_key VARCHAR(255) NOT NULL,
  content_type VARCHAR(128) NOT NULL,
  chunk_count INT NOT NULL DEFAULT 0,
  embedding_model VARCHAR(255) NULL,
  embedding_dimension INT NULL,
  index_collection VARCHAR(255) NULL,
  failure_code VARCHAR(96) NULL,
  failure_message VARCHAR(512) NULL,
  created_by BIGINT NULL,
  started_at TIMESTAMP(3) NULL,
  completed_at TIMESTAMP(3) NULL,
  created_at TIMESTAMP(3) NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL,
  PRIMARY KEY (ingestion_id),
  UNIQUE KEY uk_knowledge_ingestion_version (version_id),
  UNIQUE KEY uk_knowledge_ingestion_file_hash (source_id, source_file_hash),
  INDEX idx_knowledge_ingestion_status (status),
  INDEX idx_knowledge_ingestion_document (document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS knowledge_chunks (
  chunk_id VARCHAR(160) NOT NULL,
  source_id VARCHAR(112) NOT NULL,
  document_id VARCHAR(112) NOT NULL,
  version_id VARCHAR(112) NOT NULL,
  chunk_index INT NOT NULL,
  title VARCHAR(255) NULL,
  content LONGTEXT NOT NULL,
  content_hash CHAR(64) NOT NULL,
  classification VARCHAR(64) NOT NULL DEFAULT 'ADMIN_CURATED',
  created_at TIMESTAMP(3) NOT NULL,
  PRIMARY KEY (chunk_id),
  UNIQUE KEY uk_knowledge_chunk_version_index (version_id, chunk_index),
  INDEX idx_knowledge_chunk_version (version_id),
  INDEX idx_knowledge_chunk_source (source_id),
  INDEX idx_knowledge_chunk_document (document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
