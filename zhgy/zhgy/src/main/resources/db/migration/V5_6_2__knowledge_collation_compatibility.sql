-- FarMap V5.6.2: keep knowledge retrieval joins compatible with MySQL 8.
--
-- Some existing installations created the knowledge tables under
-- utf8mb4_0900_ai_ci while the relational knowledge tables were created under
-- utf8mb4_unicode_ci.  The Qwen dense/BM25 retrieval path joins these tables
-- by stable IDs; mixed collations make those joins fail with MySQL error 1267.
-- This metadata-only migration preserves all rows and embeddings.
ALTER TABLE knowledge_sources
    CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE knowledge_documents
    CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE knowledge_ingestions
    CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE knowledge_chunks
    CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
