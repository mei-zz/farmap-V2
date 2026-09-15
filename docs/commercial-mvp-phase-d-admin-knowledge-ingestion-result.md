# FarMap Commercial MVP Phase D — Admin Knowledge Ingestion Result

## 1. Executive Summary

Phase D implementation adds a protected administrator import path for PDF, TXT and Markdown, deterministic text chunking, BGE `ai.v1/TEXT_EMBED` indexing and a dedicated Milvus collection. The implementation is intentionally limited to knowledge ingestion/retrieval; it does not write HistoricalCase data or change the historical image collection.

Current release decision: **NO-GO pending runtime verification**. The code and contracts are present, but this workspace cannot claim the required MySQL + Local AI + Milvus import-to-search evidence until the project test/build environment is available.

## 2. Starting State / Handoff

The worktree was intentionally dirty on `feature/commercial-product`. Existing Phase A–C changes and packaged orchard knowledge were preserved. No branch operation, reset, cleanup, commit or push was performed.

## 3. Existing Knowledge Backend

The existing `KnowledgeSource → KnowledgeDocument → KnowledgeVersion` tables and service are reused. `KnowledgeVersion` remains append-only and `current_version_id` remains the explicit retrieval pointer. Packaged orchard knowledge remains separate from administrator-curated knowledge.

## 4. Admin Boundary

Production knowledge writes require an authenticated `User` whose persisted role is `admin`. Normal users can use read/search endpoints but cannot create sources, documents, versions, activate versions, change status or import files.

## 5. Import Mechanism

`POST /api/admin/knowledge/import` accepts multipart `sourceName`, optional `title` and `file`. The server receives bytes, never a client filesystem path. `GET /api/admin/knowledge/ingestions/{id}` reads lifecycle state and `/health` reports import/index state.

## 6. Supported File Formats

PDF, UTF-8 TXT, `.md` and `.markdown` are supported. DOCX and other formats are rejected in V1.

## 7. File Security

The endpoint enforces an extension allow-list, a 10 MB limit, a non-empty filename and path stripping. IDs and filenames are generated or validated server-side; client fields cannot set business IDs, status, vector data or ownership.

## 8. Raw File Storage

Raw bytes are written with `CREATE_NEW` below `FARMAP_KNOWLEDGE_STORAGE_DIR` (Spring property `farmap.knowledge.storage-dir`) using a UUID-generated object name and the validated extension. The stored key is persisted; arbitrary server paths and traversal are not accepted.

## 9. KnowledgeSource

The source is created with `source_type=ADMIN_CURATED`, `status=ACTIVE`, a server-generated `knowledge-source-*` ID and an `admin://` origin derived from the normalized source name.

## 10. KnowledgeDocument

The document ID is server-generated and stable for an admin source plus normalized uploaded filename. A changed file with the same source and filename reuses this document instead of creating a parallel business document.

## 11. KnowledgeVersion

Every new extracted content hash creates a new server-generated immutable version with an incrementing `version_number`. The current pointer is not changed while the new version is processing or failed.

## 12. Content Hash

The uploaded bytes use SHA-256 for file-level retry identity. Normalized extracted UTF-8 content uses SHA-256 for version identity; each chunk also stores its UTF-8 content hash.

## 13. Idempotency

`(source_id, source_file_hash)` is database-unique. A repeated import returns the existing ingestion record without creating another source, document, version or vector set. Identical normalized content under the same document also reuses the existing version.

## 14. Ingestion Lifecycle

The persisted lifecycle is `UPLOADED → PROCESSING → READY` or `FAILED`. `started_at` and `completed_at` are recorded. Dependency and contract failures are stored as failure codes and never reported as READY.

## 15. Text Extraction

TXT/Markdown is decoded as UTF-8 with BOM and line-ending normalization. PDF uses PDFBox text extraction only. OCR, Tesseract and vision OCR are not part of Phase D. Empty extracted text returns `KNOWLEDGE_TEXT_EXTRACTION_EMPTY`.

## 16. Chunking Policy

Chunking is deterministic and paragraph-aware: paragraph boundaries are preferred, long paragraphs use bounded pieces and a small overlap. Configuration is `FARMAP_KNOWLEDGE_CHUNK_SIZE` and `FARMAP_KNOWLEDGE_CHUNK_OVERLAP`; the defaults are 1,200 and 120 characters and are product policy values, not scientific optima.

## 17. Chunk Identity

Chunk IDs are `versionId + "-chunk-" + chunkIndex`, with stable ordering and relational uniqueness on `(version_id, chunk_index)`. A chunk remains an indexing artifact, not a new product-loop business entity.

## 18. IntelligenceGateway / BGE

All commercial embeddings flow through Java `IntelligenceGateway`, which calls the existing `ai.v1` `TEXT_EMBED` contract. No new Python route, raw `localhost:8001` call or Python business ID generation was added.

## 19. Embedding Validation

Every returned vector must contain exactly 512 finite numeric values. A count, shape or value contract failure is stored as `AI_CONTRACT_INVALID`; gateway unavailable/timeout errors result in `FAILED`.

## 20. Knowledge Milvus Collection

The dedicated collection is `farmap_knowledge_chunks_v1`. The historical image collection `farmap_image_vectors_new` is not used by this path.

## 21. Collection Schema

The collection contract contains `chunk_id` primary key, `vector[512]`, `source_id`, `document_id`, `version_id`, `chunk_index`, `content_hash` and `classification`. Full chunk text remains in MySQL and is reloaded after a vector hit.

## 22. Index Strategy

The index is IVF_FLAT with `nlist=128` and COSINE metric. BGE output is normalized by the existing local provider, so cosine similarity is the natural semantic ranking policy for this text collection; this is an engineering choice, not an agricultural-science threshold.

## 23. Collection Initialization

Missing collections are created only in execute mode. Existing collections are described and schema/index validated. A mismatch returns `MIGRATION_REQUIRED`; no drop, clear or destructive recreation is performed.

## 24. Version Activation

The ingestion is first made READY after relational chunk persistence, embedding validation and Milvus insertion/index verification. Only then is `current_version_id` advanced. If activation fails, the old pointer remains unchanged and the new ingestion is failed.

## 25. Vector Retrieval

Search embeds the query through `IntelligenceGateway`, searches the dedicated collection, applies the minimum score policy and reloads authoritative metadata/content from MySQL.

## 26. Min Score / Empty Semantics

`FARMAP_KNOWLEDGE_MIN_SCORE` defaults to `0.45`. It is an MVP retrieval policy threshold, not a scientifically calibrated agricultural threshold. Below-threshold, ineligible or unrelated hits can produce `READY_EMPTY`; the API does not always return top-K garbage.

## 27. KnowledgeEvidenceRetriever Wiring

`KnowledgeEvidenceRetriever` tries the new semantic store for READY/current administrator chunks. Its existing packaged/legacy path remains a clearly marked fallback for Diagnosis/RAG when the semantic store is unavailable. Lexical fallback now requires a real lexical or sufficiently strong semantic match.

## 28. Evidence Provenance

Semantic evidence preserves server-created `evidenceId` plus `sourceId`, `documentId`, `versionId`, `chunkId`, content hash, classification and retrieval score.

## 29. Old Diagnosis Version Invariant

Changing a document’s current pointer does not mutate persisted diagnosis evidence. Existing evidence continues to carry the version and chunk that originally supported it.

## 30. Health

Runtime health now exposes `knowledgeIndex` separately from the knowledge relational backend. The index reports `READY`, `READY_EMPTY` or `UNAVAILABLE`; a missing/mismatched collection and a Milvus failure are not represented as READY_EMPTY.

## 31. Failure Semantics

Database, raw-storage, PDF parsing, BGE contract, BGE availability, Milvus initialization, insertion and activation failures fail closed. A failed version is excluded by the relational eligibility query.

## 32. Admin CLI

`scripts/import-admin-knowledge.ps1` resolves one explicit local file, applies the extension/size gate and submits multipart bytes with `FARMAP_ADMIN_TOKEN`. It prints the JSON response containing source/document/version IDs, status, chunk count, model, dimension and collection without printing the token.

## 33. Frontend Boundary

No normal-user knowledge upload UI was added. The Knowledge & Expert page now states that knowledge is platform-admin maintained and that admin import is performed through the protected API/CLI.

## 34. Tests

Added/retained unit coverage covers deterministic bounded chunking, TXT/Markdown normalization, unsupported formats, empty extracted text, unauthenticated import rejection, the 10 MB policy and the separate cosine collection contract. Existing Knowledge backend and provenance tests remain in place.

## 35. Python Runtime Validation

The implementation is wired to the existing `ai.v1` Local AI service and does not alter Python identity or persistence responsibilities. Live validation succeeded: `/v1/health` reported BGE `READY`, model `BAAI/bge-small-zh-v1.5`, dimension `512`; a real `/v1/embeddings/text` request returned `SUCCEEDED` with one 512-dimensional vector. The existing Python contract unit test also passed.

## 36. MySQL Validation

The formal migration is `V5_3_2__knowledge_ingestion_v1.sql`. It adds ingestion metadata (`raw_storage_key`, `started_at`, `completed_at`) and chunk indexing metadata. Migration readiness includes both Phase D tables and their uniqueness boundaries. Live MySQL import validation remains pending.

## 37. Milvus Validation

The Java initializer has an isolated collection contract and does not reference the historical image collection. The read-only Milvus audit of `127.0.0.1:19530` was blocked by `MilvusException`; no collection was created, dropped, cleared or modified.

## 38. Admin Import → Search E2E

The intended E2E is `admin file → source/document/version → READY → POST /api/knowledge/search → provenance-preserving result`. No repository content was auto-downloaded or promoted as real admin knowledge. Live E2E is blocked by unavailable Spring and Milvus runtimes.

## 39. AI / Agent Regression

No Agent planner, approval, HistoricalCase or multimodal RAG redesign was made. Existing Phase B, baseline and evidence-sufficiency regressions should be run once Maven is available.

## 40. Maven

`where mvn` found no Maven executable; no `mvnw` or repository `.tools` Maven was found. Full Maven verification is therefore `ENVIRONMENT_BLOCKED`, not silently treated as passed.

## 41. Frontend Validation

Only a small Knowledge copy change was made. `npx tsc -b --pretty false`, targeted ESLint for the changed adapter and `npx vite build --minify=false` all passed.

## 42. git diff

The worktree remains intentionally dirty and includes prior accepted milestones. No reset, restore, clean, commit or push was performed. Final whitespace validation is performed separately with `git diff --check`.

## 43. Files Changed

Phase D files include the ingestion service, document extractor/chunker integration, dedicated vector store, read-only search controller, runtime health wiring, migration readiness metadata, application configuration, admin CLI, frontend Knowledge copy and the Phase D tests/documents. Existing unrelated milestone changes were preserved.

## 44. Remaining Commercial MVP Gaps

Live runtime proof is pending: apply/run the migration, start Local AI, verify BGE 512 output, initialize the dedicated Milvus collection, import a real administrator-provided PDF/TXT, search it, and run the targeted/full regressions. No real admin knowledge content has been imported by this change.

## 45. Phase D GO / NO-GO

**NO-GO for release sign-off at this checkpoint.** The protected implementation and fail-closed contracts are present, but the handoff requires real import-to-search evidence before GO. The blocking item is environment/runtime verification, not a fallback to demo data.

## 46. Phase E Entry

Phase E is not started. It may begin only after Phase D is re-verified GO, with FieldContext, a real selected image, weather/GIS evidence and ADMIN_CURATED knowledge wired into grounded multimodal RAG.

## Final Runtime Verification

### 47. Docker Runtime Status

The configured `desktop-linux` Docker engine did not answer the Docker API ping or `docker ps`. The responding Linux engine was queried explicitly through its existing engine endpoint. It has `milvus-standalone`, `milvus-etcd`, `milvus-minio` and `mongodb` running; it has no MySQL, Spring backend or tree-analyzer container. No volumes were removed, no database was dropped and no collection was dropped or recreated.

### 48. MySQL Runtime Verification

Host port `3306` is owned by a separate Windows `MySQL` service. A read-only connection using the deployment `zhgy` credentials was rejected, so this is not the Compose `zhgy` database and cannot be used as Phase D runtime evidence. No relational import rows were written.

### 49. V5_3_2 Migration Verification

`V5_3_2__knowledge_ingestion_v1.sql` is present in the repository, but Spring and the intended MySQL schema were unavailable. `/api/runtime/migration-readiness` could not be called; migration status is therefore `ENVIRONMENT_BLOCKED`, not `READY`.

### 50. Local AI BGE Runtime

Live `/v1/health` returned `TEXT_EMBED=READY` for `BAAI/bge-small-zh-v1.5` with dimension `512`. A fresh `ai.v1` `TEXT_EMBED` request returned `SUCCEEDED` with one finite 512-dimensional vector. `local-ai/test_contract.py` passed.

### 51. Milvus Runtime

Milvus `v2.4.0` is running with etcd and MinIO. The gRPC route works through `localhost:19530`; the Windows `127.0.0.1:19530` route accepts TCP but fails the PyMilvus handshake. This is a local Docker Desktop loopback routing/configuration issue, not a Milvus collection-schema result. No destructive Milvus action was attempted.

### 52. Knowledge Collection Initialization

The read-only collection check found `farmap_knowledge_chunks_v1` missing. Because Spring was unavailable, the existing `KnowledgeVectorStore` initializer was not executed and the collection was not created. Knowledge collection schema, index and dimension are therefore unverified at runtime.

### 53. Historical Collection Before/After

`farmap_image_vectors_new` was verified through Milvus before the blocked import gate: it existed with `0` rows and its existing `512`-dimension L2 index. The same read-only check after the attempted verification remained at `0` rows. Phase D did not modify it.

### 54. TXT Import E2E

Not executed. There was no reachable Spring admin endpoint and no admin token was used. Consequently no `sourceId`, `documentId`, `versionId` or ingestion status can be claimed.

### 55. PDF Import E2E

Not executed. No PDF fixture was submitted because the same Spring/MySQL runtime blocker prevented a valid import path.

### 56. Ingestion Lifecycle

No live `UPLOADED → PROCESSING → READY` or `FAILED` transition was observed. The lifecycle remains verified by code/tests only, not by this runtime gate.

### 57. Milvus Row Verification

Not executed for the knowledge collection because it does not exist and no import occurred. Knowledge chunk count and indexed-row count are both `0` for this verification run.

### 58. Relevant Search E2E

Not executed. Spring was unavailable, so no real `POST /api/knowledge/search` response exists.

### 59. Irrelevant Search E2E

Not executed for the same reason. No unrelated-query result is claimed.

### 60. Provenance Verification

Not executed. No live result containing `sourceId`, `documentId`, `versionId` and `chunkId` was produced.

### 61. Duplicate Import Verification

Not executed. No initial TXT/PDF import reached the service, so idempotency cannot be promoted from automated coverage to runtime evidence.

### 62. Runtime Health After Import

`/api/runtime/health` was unavailable because Spring was not running. The post-import `knowledgeIndex=READY` requirement is therefore unmet; the expected state cannot be inferred from the missing collection.

### 63. Maven/Regression Verification

`where mvn` found no Maven executable, no `mvnw` or repository `.tools` Maven was available, and the full Maven suite remains `ENVIRONMENT_BLOCKED`. Frontend work was not part of this final verification. The fresh Python contract test passed; Java targeted, MySQL and Spring regression suites could not run.

### 64. Final Phase D Decision

**NO-GO.** BGE and Milvus infrastructure checks passed with the routing qualification above, and the historical image collection remained untouched. The required Phase D runtime gate did not pass because the reachable Docker engine has no MySQL/backend stack, the host MySQL is an unrelated credential-incompatible service, and Spring therefore could not execute migration readiness, admin import, lifecycle, vector indexing or search. The direct next action is to restore the repository's MySQL + Spring runtime and rerun this gate; Phase E remains unopened.

## Runtime Recovery Verification (2026-09-06)

The earlier sections above record the prior blocked checkpoint. This section records the narrow recovery attempt against the existing local services and supersedes the earlier MySQL/Spring availability statements.

### 65. Local MySQL Reality

The user-confirmed local credentials connected successfully to `localhost:3306`, database `zhgy`. The required product and Phase D tables were present, including `knowledge_sources`, `knowledge_documents`, `knowledge_versions`, `knowledge_ingestions` and `knowledge_chunks`. Existing product rows were preserved; no delete, reset, restore or schema replacement was performed.

### 66. V5_3_2 Runtime Schema

The repository migration `V5_3_2__knowledge_ingestion_v1.sql` was applied to the existing `zhgy` schema. Because `knowledge_ingestions` already existed as a partial table, the three missing definitions (`raw_storage_key`, `started_at`, `completed_at`) were added with the exact migration types and nullability. Required Phase D uniqueness boundaries and indexes were then present. This was the only database repair performed in this recovery.

### 67. Latest Phase D Spring Runtime

Maven and `mvnw` were unavailable, so the latest Phase D source classes were compiled with the installed JDK using Java 17-compatible classfiles and launched ahead of the existing compiled classes. The application started on port `8080`; the runtime health endpoint returned HTTP `200`. The health payload reported `migrationReadiness.status=READY`, `knowledgeBackend.status=READY` and one existing knowledge version.

### 68. Local AI / BGE

The single fresh live embedding smoke remained successful: Local AI reported BGE `READY`, model `BAAI/bge-small-zh-v1.5`, dimension `512`; the `ai.v1` text-embedding response was `SUCCEEDED` with a finite 512-dimensional vector. No model, Python service or `ai.v1` implementation was changed.

### 69. Milvus localhost Recovery Result

The existing Milvus runtime was queried through `localhost:19530`. The historical collection `farmap_image_vectors_new` remained present with `0` rows, dimension `512`, metric `L2` and its existing index. The dedicated collection `farmap_knowledge_chunks_v1` was still absent.

The latest Spring runtime used `localhost:19530` as configured. The Java Milvus SDK opened the local TCP connection but its gRPC handshake did not become ready and ended with `DEADLINE_EXCEEDED` after the configured 30-second deadline. The application therefore exposed `knowledgeIndex.status=UNAVAILABLE` with `MILVUS_CLIENT_UNAVAILABLE`; its Java initializer did not create the dedicated collection. No collection was created, dropped, cleared or modified by the recovery attempt.

### 70. TXT/PDF Import, Search and Provenance

Not executed. Without a usable Java Milvus client and a `READY` knowledge index, claiming administrator TXT/PDF import, lifecycle transitions, search hits, irrelevant-query filtering, provenance IDs or retriever success would be invalid. No test fixture was imported and no `sourceId`, `documentId`, `versionId` or `chunkId` is claimed.

### 71. Idempotency and Access Control

Not executed at the live endpoint. No administrator token was used and no duplicate import was attempted. The database remained free of recovery-created ingestion/chunk rows.

### 72. Build and Regression Boundary

The narrow latest-source compilation and Spring startup were successful. Full Maven verification remains `ENVIRONMENT_BLOCKED` because no Maven executable, wrapper or repository-bundled Maven exists. No broad audit, Phase E work, dependency installation, Docker restart or destructive cleanup was performed.

### 73. Final Recovery Decision

**NO-GO.** MySQL, migration readiness, Spring startup and BGE are now verified against the existing local environment, but the required Phase D gate still fails at the Java Milvus gRPC handshake. The dedicated knowledge collection, real TXT/PDF import, vector rows, search, provenance, idempotency and post-import `knowledgeIndex=READY` therefore remain unverified. Phase E remains unopened. The next action is to repair the existing Java Milvus client/runtime compatibility on `localhost:19530`, then rerun only the blocked import-to-search gate.

## Autonomous Recovery Completion (2026-09-07)

Section 73 recorded the pre-repair state. The following evidence supersedes Sections 69–73 for the final Phase D gate.

### 74. Tooling and Java/Milvus Runtime Repair

Portable tooling was installed outside the repository on `E:`: Apache Maven `3.9.16` at `E:\FarMapTools\maven\apache-maven-3.9.16`, Maven local repository at `E:\FarMapTools\m2`, and `grpcurl` `1.9.4` at `E:\FarMapTools\grpcurl`. Maven was run with the existing JDK `D:\jdk-17.0.7`; no repository dependency upgrade was required.

The failure was isolated to the Windows Docker Desktop loopback route: `127.0.0.1:19530` accepted TCP but never returned the gRPC/HTTP2 SETTINGS handshake. `grpcurl` reproduced the timeout, while a real Java Milvus SDK `2.4.0` probe against `::1:19530` completed `listCollections` successfully and returned `[farmap_image_vectors_new]`. The formal Spring runtime therefore uses the verified endpoint `MILVUS_HOST=::1`, `MILVUS_PORT=19530`; it does not claim that Java `localhost` is equivalent to the working IPv6 route.

### 75. Formal Build and Runtime Health

The formal Maven project is `zhgy/zhgy`. `mvn compile -DskipTests` passed, and the restarted latest Spring Boot runtime started on port `8080` with JDK 17. After restart, `/api/runtime/health` returned `code=1` with:

- `milvus.endpoint=::1:19530`, knowledge collection present, `knowledgeIndex.status=READY`, `rowCount=4`;
- knowledge collection contract `dimension=512`, `metric=COSINE`, `index=IVF_FLAT`, `nlist=128`;
- `knowledgeBackend.status=READY`, `migrationReadiness.status=READY`, and BGE `READY` with model `BAAI/bge-small-zh-v1.5`, dimension `512`;
- historical `farmap_image_vectors_new` remained read-only and unchanged at `0` rows.

### 76. Access Control and Admin Import E2E

A normal user (`plum`) received HTTP `403` from `POST /api/knowledge/sources`. The administrator import endpoint then completed both supported Phase D fixture paths.

The UTF-8 TXT import returned `READY`, `chunkCount=3`, BGE dimension `512`, activated version and collection `farmap_knowledge_chunks_v1`, with ingestion `knowledge-ingestion-17cb493e-8176-4c12-97a5-b898e666fd29`, source `knowledge-source-1153fa29-b68b-4b08-92c0-45e1e55bcd05`, document `knowledge-document-62d36148-ed62-4501-83b1-5693740c60a5` and version `knowledge-version-15c54a65-fb71-4d5c-9088-4c6c33dd8008`. Re-submitting the identical file returned the same ingestion ID, proving file-level idempotency.

The PDF import returned `READY`, `chunkCount=1`, activated version and the same BGE/collection contract, with ingestion `knowledge-ingestion-082282fa-09b8-4e9c-ba9b-94cde74874be` and document `knowledge-document-58f10b8b-37ac-4506-9e09-b61bdab73459`.

### 77. Search, Provenance and Retriever Smoke

After the restart, the Chinese TXT query returned HTTP `200`, API `code=1`, `status=READY` and three results. The returned result preserved `sourceId`, `documentId`, `versionId`, `chunkId`, content hash and `classification=ADMIN_CURATED`. The PDF query likewise returned three results and a result whose content was the imported PDF sentence, with the expected document ID and administrator classification.

The targeted Java regression set passed, including `KnowledgeEvidenceProvenanceTest`, `HybridKnowledgeRetrieverTest`, `KnowledgeIngestionServiceTest`, `KnowledgeVectorStoreTest` and `DiagnosisRagIntegrationTest`. The recovery fixed a real Chinese lexical fallback defect in `KnowledgeEvidenceRetriever`: contiguous CJK phrases are now kept together instead of being split into discarded one-character tokens, which had incorrectly produced `READY_EMPTY` for a phrase present in the evidence. This change is covered by the passing provenance/retriever tests.

The live database counts after the E2E run were `knowledge_sources=3`, `knowledge_documents=3`, `knowledge_versions=3`, `knowledge_ingestions=2`, `knowledge_chunks=4`; existing `agent_diagnoses=4` and `agent_operations_tasks=3` were preserved.

### 78. AI and Full Regression Verification

`AiEngineBaselineEvaluationTest` and `AgentV2Phase11EvaluationTest` both passed. The deterministic baseline recorded RAG recall@1 `0.8611`, recall@3/recall@5 `0.9167`, provenance `1.0`, grounding/safety `1.0`; Phase 1.1 recorded unsupported-action rate `0`, unsupported-action count `0`, action-grounding `1.0` and approval compliance `1.0`.

The full Maven suite passed: `122` tests run, `0` failures, `0` errors, `13` skipped opt-in tests. No reset, restore, clean, commit, push, Docker volume deletion, database deletion or historical-vector mutation was performed. Phase E remains unopened.

### 79. Final Phase D Decision

**CONDITIONAL GO for Phase D local runtime and admin-curated knowledge ingestion/search.** All required Phase D runtime gates now pass, including real Java `listCollections`, collection initialization/contract validation, protected admin TXT/PDF import, lifecycle READY state, idempotency, search, provenance, retriever smoke and full Java regression. The deployment condition is that the Milvus Java endpoint remains the verified IPv6 loopback route (`::1`) or is replaced by a gRPC route that completes HTTP/2 negotiation. The app also emits non-blocking warnings for the unrelated `mongo:27017` DNS target and multiple SLF4J bindings; these did not affect the Phase D MySQL/Milvus/BGE path.
