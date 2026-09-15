# FarMap Admin Knowledge Ingestion Contract V1

## Scope

This contract is for administrator-curated agricultural documents only. Supported uploads are PDF, TXT, Markdown and `.markdown`, with a maximum size of 10 MB. The browser Knowledge & Expert page remains read-only. Ordinary authenticated users cannot write through the legacy `/api/knowledge` command endpoints; those commands now require the authenticated user's `admin` role.

## Write boundary

`POST /api/admin/knowledge/import` accepts `multipart/form-data`:

- `sourceName` (required, 1–247 characters)
- `title` (optional, at most 255 characters)
- `file` (required; PDF/TXT/Markdown)

The server takes the multipart bytes, strips any client path, validates the extension and size, writes the bytes under the server-controlled `FARMAP_KNOWLEDGE_STORAGE_DIR` root with a generated filename, extracts UTF-8/PDF text, normalizes line endings, and computes a SHA-256 file hash. A client cannot submit `sourceId`, `documentId`, `versionId`, `chunkId`, owner, status, vector, embedding, or eligibility fields.

`GET /api/admin/knowledge/ingestions/{ingestionId}` reads ingestion state. `GET /api/admin/knowledge/health` reports database and vector-store readiness. Both require authentication; writes require admin authorization from `User.role`.

## Lifecycle

```text
UPLOADED → PROCESSING → READY
                     ↘ FAILED
```

`READY` is written only after all chunks have been persisted, BGE embeddings have passed the 512-dimension contract, Milvus insertion has succeeded, and the document's `current_version_id` is activated. A failed dependency marks the ingestion `FAILED` and does not report success or silently fall back to memory.

## Identity and idempotency

IDs are server-generated UUID-prefixed values. The canonical byte-level retry key is `(admin source, source_file_hash)`, backed by `uk_knowledge_ingestion_file_hash`; retries return the existing ingestion record. A document is stable for an admin source and normalized filename. Changed content creates a new immutable `KnowledgeVersion` under that document; identical extracted content reuses the existing version. Chunks are deterministic and unique by `(version_id, chunk_index)`.

## Chunk and embedding contract

Paragraphs are normalized and split deterministically at 1,200 characters with a 120-character overlap for long paragraphs. Each chunk stores `chunkId`, source/document/version IDs, content hash, classification `ADMIN_CURATED`, and the original extracted text. Embeddings are requested through Java `IntelligenceGateway` using the `ai.v1` `TEXT_EMBED` task. The expected model is the configured local BGE provider (`BAAI/bge-small-zh-v1.5`) and the only accepted dimension is 512.

## Milvus contract

The dedicated collection is `farmap_knowledge_chunks_v1`. It is independent from `farmap_image_vectors_new` and contains a VarChar `chunk_id` primary key plus a 512-dimensional FloatVector `vector`, indexed with IVF_FLAT/COSINE (`nlist=128`). Initialization is idempotent and never drops or clears an existing collection. Search returns IDs and scores; Java performs a second database lookup requiring active source/document, `ADMIN_CURATED`, `READY` ingestion and `current_version_id = chunk.version_id` before emitting evidence.

## Read/search boundary

`POST /api/knowledge/search` accepts `{ "query": "...", "topK": 5 }`; the equivalent GET form is `/api/knowledge/search?q=...&topK=5`. Both are read-only, authenticated, and bounded to 1–20 results. Returned data contains `status` and `results[]`; each result includes source, document, version, chunk, content hash, classification and score. If the vector store is unavailable, the existing packaged/legacy retriever remains available to Diagnosis/RAG as a clearly marked fallback; the verification API does not emit fake semantic results.

## Provenance and version semantics

Diagnosis evidence references the immutable `sourceId`, `documentId`, `versionId`, `chunkId`, and content hash. Activating a later document version does not rewrite prior diagnosis snapshots. Candidate or historical-case ingestion is outside this contract; no historical case, Milvus historical collection, model training, or automatic promotion is performed.

## Operational configuration

- `MILVUS_HOST`, `MILVUS_PORT` configure the vector service.
- `LOCAL_AI_BASE_URL` configures the local BGE service.
- `FARMAP_KNOWLEDGE_*` controls chunking, candidate/top-k, minimum score and existing ranking weights.
- `FARMAP_INTELLIGENCE_SERVICE_TOKEN` is forwarded by the gateway when configured.

Production must apply `V5_3_2__knowledge_ingestion_v1.sql` before enabling imports. It persists `raw_storage_key`, `started_at` and `completed_at` in addition to ingestion status and indexing metadata. The current project still has no migration runner, so runtime `CREATE TABLE IF NOT EXISTS` is a local compatibility bridge rather than a deployment substitute.

## CLI

```powershell
$env:FARMAP_ADMIN_TOKEN = '<authenticated administrator JWT>'
./scripts/import-admin-knowledge.ps1 -File .\docs\orchard.md -SourceName 'Orchard SOP' -Title 'Irrigation SOP'
```

The script resolves one explicit local file, applies the same extension/size gate, and submits multipart bytes to the protected endpoint. It never sends a client filesystem path to the server and never contains credentials.
