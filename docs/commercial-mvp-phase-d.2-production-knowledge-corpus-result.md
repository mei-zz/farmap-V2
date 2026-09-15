# FarMap Commercial MVP — Phase D.2 Result

**Phase:** Production admin-curated knowledge corpus ingestion and Qwen/GTE retrieval gate  
**Date:** 2026-09-07  
**Branch:** `feature/commercial-product`  
**Decision:** **CONDITIONAL GO** for the supported, text-extractable corpus; the explicitly unsupported/corrupt/no-text files remain outside the production index.

## 1. Scope and safety boundary

The only inventoried and imported source root was:

```text
D:\前后端项目\farmap\知识库
```

The source files were read-only. No other disk or corpus root was scanned. No source file, credential, API key, JWT, MySQL password, raw document text, or embedding payload was written to the repository or result artifacts. No destructive MySQL, Milvus, history, reset, restore, checkout, commit, or push operation was performed. Phase E was not started.

The importer uses the top-level directory as the stable admin source boundary and the normalized relative path as document identity. The final manifest is [admin-knowledge-corpus-import-manifest.json](admin-knowledge-corpus-import-manifest.json).

## 2. Inventory and disposition

| Item | Count | Bytes | Disposition |
|---|---:|---:|---|
| Total files | 37 | 321,689,646 | inventoried |
| DOCX | 20 | 264,895,502 | supported; Apache POI extraction |
| PDF | 9 | 9,710,780 | supported when text is extractable |
| legacy DOC | 6 | 70,144 | unsupported |
| PPTX | 1 | 37,587,215 | unsupported |
| JPG | 1 | 9,426,005 | unsupported |

Final first-pass disposition:

- **26 documents READY**, producing **507 Qwen chunks**.
- **11 explicitly UNSUPPORTED**: six legacy `.doc`, one `.pptx`, one `.jpg`, one truncated/corrupt DOCX, and two PDFs with no extractable text. The two no-text PDFs are not silently indexed and are recorded with `KNOWLEDGE_TEXT_EXTRACTION_EMPTY`.
- A complete directory rerun returned **26 `SKIPPED_IDEMPOTENT`** and **11 `UNSUPPORTED`** entries. No new version or vector row was created by the rerun.

The corrupt DOCX was detected as an invalid ZIP (`End of Central Directory record could not be found`) without changing the source. DOC/PPTX/JPG conversion and OCR are intentionally outside this phase.

## 3. Production embedding and reranking contract

The live credential was loaded only into the Spring process environment from the existing local configuration path. The production provider is:

| Contract | Value | Evidence |
|---|---|---|
| Embedding provider | `BAILIAN` | live HTTP probe and runtime health |
| Embedding model | `qwen3.7-text-embedding-flash` | live response model |
| Embedding dimension | 1024 | three-vector live probe, finite values |
| Embedding batching | configurable, bounded at 1–20; runtime default 10 | provider implementation |
| Reranker | `gte-rerank-v2` | live DashScope text-rerank probe |
| Candidate top-N | 20 | runtime health |
| Final top-K | 5 | runtime health |
| Collection | `farmap_knowledge_chunks_qwen3_v1` | isolated production collection |
| Metric/index | COSINE / IVF_FLAT / `nlist=128` | collection health |

The live embedding probe returned HTTP 200, three vectors of dimension 1024, and finite values. The live rerank probe returned HTTP 200, three finite scores and a valid ranking. The implementation never falls back to Local BGE for the Qwen corpus path. The existing BGE collection remains a separate compatibility path.

Provider contract references: [Alibaba Cloud Embedding](https://www.alibabacloud.com/help/en/model-studio/embedding), [synchronous text embedding API](https://www.alibabacloud.com/help/en/model-studio/text-embedding-synchronous-api), and [text rerank API](https://www.alibabacloud.com/help/en/model-studio/text-rerank-api).

## 4. Runtime and persistence gates

Runtime health after import reported:

- MySQL knowledge backend: `READY`; migration readiness: `READY`.
- Qwen collection: `READY`, collection exists, 1024 dimensions, **507 rows**.
- Qwen embedding: `BAILIAN / qwen3.7-text-embedding-flash / 1024 / READY`.
- Qwen reranker: `BAILIAN / gte-rerank-v2 / READY`.
- Relational Qwen ingestion state: **26 READY ingestions / 507 chunks**.
- Existing BGE collection `farmap_knowledge_chunks_v1`: still 512/COSINE/IVF_FLAT/128 with **4 rows**.
- Historical `farmap_image_vectors_new`: unchanged and still empty; no historical vector write was performed.

The database read-only snapshot also showed 4 total `ADMIN_CURATED` source records, 28 admin documents and 29 knowledge versions, including pre-existing Phase D records. The 26 corpus documents in this phase are isolated by model and collection metadata.

## 5. Search API and provenance

`GET`/`POST /api/knowledge/search` now returns `retrievalProvider`, `embeddingModel`, `rerankerModel`, `rerankerStatus`, `vectorScore`, `rerankScore`, and provenance fields including `sourceId`, `documentId`, `versionId`, `chunkId`, `relativePath`, and `contentHash`. The default path uses Qwen embeddings plus GTE reranking; a bounded `rerank=false` query option was added only for evaluation comparison.

Authentication gates were verified against the live application:

- unauthenticated search: HTTP 401;
- normal user attempting admin import: HTTP 403;
- administrator import: accepted and idempotent on rerun.

## 6. Retrieval quality gate

The machine-readable evaluation artifact is [commercial-mvp-phase-d.2-retrieval-evaluation.json](commercial-mvp-phase-d.2-retrieval-evaluation.json). It contains ten relevant agricultural queries plus one irrelevant query, with vector-only and reranked results represented by relative path and scores only.

| Metric | Vector-only | Qwen + GTE reranked |
|---|---:|---:|
| Relevant queries | 10 | 10 |
| Expected source top-1 | 10/10 | 10/10 |
| Expected source top-5 | 10/10 | 10/10 |
| Reranked provenance complete | n/a | 10/10 |
| Irrelevant query empty | yes | yes |

The irrelevant query did not produce fabricated evidence or a forced non-empty answer. Search status and provider/reranker metadata were present on the live responses.

## 7. Tests and regression

Targeted Phase D.2 tests passed, including DOCX paragraph/table extraction and the isolated Qwen collection contract. The full Maven suite completed:

```text
124 tests, 0 failures, 0 errors, 13 skipped
```

The existing AI Engine V2 baseline remains a deterministic measurement artifact, not a live LLM quality claim. Its previously recorded baseline included Recall@5 `0.9167`, evidence provenance integrity `1.0000`, action grounding `0.2500`, unsupported action rate `0.7500`, and recovery success `0.0000`. Agent Phase 1.1 remains the earlier conditional production gate with evidence sufficiency accuracy `8/8` and unsupported-action prevention `5/5`; this phase did not broaden that scope.

## 8. Changed implementation surface

- Added Apache POI DOCX extraction and tests.
- Added Bailian Qwen embedding and GTE reranker providers with bounded retry/batch validation.
- Added isolated `QwenKnowledgeVectorStore` collection/schema/index/health/search path.
- Added model-aware ingestion metadata, failed-ingestion retry recovery, relative-path provenance and admin corpus endpoint.
- Added `scripts/import-admin-knowledge.ps1` directory mode with recursion, read-only streaming upload, SHA-256 manifesting, unsupported classification, continuation after per-file failure, and idempotent reruns.
- Added runtime health fields and vector-only evaluation switch.
- Added the manifest and retrieval evaluation artifact linked above.

## 9. Residual conditions and release decision

This is a **CONDITIONAL GO**, not an unconditional claim that every file in the root is indexable. The supported text-extractable corpus is live, searchable, reranked, provenance-preserving and idempotent. Release conditions are the documented 11 unsupported files, no OCR/legacy-DOC/PPTX ingestion in this phase, and the existing local runtime requirement that Java Milvus use IPv6 loopback `::1` in this environment. The non-blocking Mongo hostname warning remains outside the Phase D.2 knowledge path.

No Phase E work was started.
