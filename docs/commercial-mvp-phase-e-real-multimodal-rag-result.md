# FarMap Commercial MVP — Phase E Real Multimodal / Multi-Source RAG Result

## 1. Executive Summary

Phase E Core RAG is **GO**. The live path used an authenticated `field-*` request, a server-assembled FieldContext, the production Qwen knowledge collection, Qwen 1024-dimension query embedding, and `gte-rerank-v2` inside the diagnosis path. The live diagnosis persisted and reloaded with the same stable identity and provenance. Live vision remains **CONDITIONAL** because the configured vision provider is unavailable; this is an allowed optional dependency degradation.

## 2. Starting State

Phases A–D.2 were treated as authoritative and were not reimplemented. The existing dirty worktree was preserved. The Phase D.2 corpus remains 37 files, 26 READY text-extractable files, 507 Qwen chunks/vectors, and 11 unsupported/corrupt/no-text files excluded.

## 3. Phase E Scope

The implementation was limited to diagnosis input canonicalization, FieldContext, weather/GIS/temperature/soil/satellite/historical retrievers, production knowledge retrieval, optional vision wiring, evidence grounding, persistence/reload, frontend diagnosis submission, evaluation tests, and runtime verification. Agent planning and Phase F work were excluded.

## 4. Production Knowledge Baseline

The authoritative production index is `farmap_knowledge_chunks_qwen3_v1` with 507 rows, COSINE, IVF_FLAT, `nlist=128`, candidate top-N 20, final top-K 5, and the frozen model `qwen3.7-text-embedding-flash` at dimension 1024. The historical image collection remains separate and empty.

## 5. Canonical Diagnosis Input

The canonical request is `fieldId`, `query`, and optional authenticated image references, with server-owned farm, crop, growth stage, weather, GIS, and source statuses. The frontend no longer sends duplicate crop/growth/weather facts for the real diagnosis path. Client-supplied duplicates are ignored or replaced at the server boundary.

## 6. REAL / DEMO Boundary

`field-*` requests use the real path and require a server FieldContext. `a12-demo` is rejected by the real diagnosis controller. No demo weather, demo crop, demo evidence, or packaged fallback is injected into a real field request.

## 7. FieldContext Trust Boundary

`FarmFieldService` authenticates farm ownership, loads the field from MySQL, assembles the trusted context, and attaches it as a transient server-owned request value. Forged client weather, soil, satellite, historical cases, farm ID, crop, and growth stage cannot override the trusted context.

## 8. Context Snapshot

The snapshot contains farm, field, crop, location, weather, accumulated temperature, images, soil, GIS, satellite, status, source mode, scope, freshness, and assembly state. The live field used for verification was `field-c3c5c1de-67f7-4111-a263-a6e52d856e08` in farm 1.

## 9. Knowledge Retrieval Path

Production Spring wiring injects `QwenKnowledgeVectorStore` into `KnowledgeEvidenceRetriever`. When that bean is present, diagnosis uses Qwen/Milvus only; it does not fall back to the legacy BGE vector store or lexical packaged corpus. An unavailable Qwen backend is surfaced as UNAVAILABLE/HTTP 503 rather than converted into fake evidence.

## 10. Qwen Embedding

The live diagnosis health and snapshot report provider BAILIAN, model `qwen3.7-text-embedding-flash`, and dimension 1024. The query embedding participates in the diagnosis request and is not mixed with BGE scores.

## 11. Milvus Candidate Retrieval

The live Java process connected to Milvus at `::1:19530`. Candidate retrieval used `farmap_knowledge_chunks_qwen3_v1`, COSINE, IVF_FLAT, and the frozen candidate top-N 20 contract. The historical collection was not written and remained at row count 0.

## 12. GTE Reranking

`gte-rerank-v2` is called from the Qwen diagnosis retrieval path, not only from a standalone search endpoint. The live diagnosis snapshot reports reranker status READY and five knowledge hits after the final top-K limit.

## 13. Knowledge Evidence Provenance

Knowledge evidence carries source ID, document ID, version ID, chunk ID, relative path, content hash, classification, vector/retrieval/rerank scores, evidence confidence, embedding provider/model/dimension, reranker model/status, retrieval rank, source mode, and status. The API source contract now exposes the extended provenance directly and retains the metadata copy for compatibility.

## 14. Weather Evidence

The legacy weather table contains seasonal prose without an observed date or trusted rainfall series. It is therefore represented as READY_EMPTY, not as numeric historical weather evidence. The live snapshot reported weather READY_EMPTY and emitted zero weather evidence.

## 15. Accumulated Temperature

Accumulated temperature is a separate trusted evidence modality. The live field had one READY accumulated-temperature evidence record, and the model received it through FieldContext rather than from client input.

## 16. GIS / Spatial Evidence

GIS evidence is scoped to FIELD when field coordinates exist and may fall back to FARM scope only when the farm center is the available trusted location. The live field coordinates produced one READY spatial evidence record with truthful field scope.

## 17. Vision / Image Wiring

The image path is connected to `ai.v1 VISION_ANALYZE` through `IntelligenceGateway`. Image references are optional for Core Phase E. Uploaded/selected image references are not treated as camera facts, and the controller accepts only authenticated HTTP(S) references under the configured upload boundary.

## 18. Soil Status

No trusted soil provider or field-bound soil record is configured. Soil is reported UNAVAILABLE and produces zero soil evidence. Forged client soil values were ignored in evaluation and live request assembly.

## 19. Satellite Status

No trusted satellite provider is configured. Satellite is reported UNAVAILABLE and produces zero NDVI or satellite evidence. Forged client NDVI was ignored.

## 20. Historical Status

The historical vector store is READY_EMPTY with zero rows. It produces zero historical evidence and does not use forged client historical cases. The frozen historical collection `farmap_image_vectors_new` was not modified.

## 21. Retriever Availability Semantics

Retrievers report READY, READY_EMPTY, DEGRADED, or UNAVAILABLE explicitly. Empty and unavailable sources never create placeholder evidence. Production knowledge UNAVAILABLE is a dependency failure; degraded reranking may retain vector candidates with the degraded status.

## 22. Evidence Fusion

Evidence fusion retains stable retriever IDs and source metadata while separating modality evidence confidence from diagnosis confidence. The live response returned seven evidence records across accumulated temperature, spatial, and production knowledge modalities.

## 23. Claim Grounding

The generator and validator require every claim to reference an evidence ID emitted by a retriever. Live claims referenced existing accumulated-temperature or knowledge evidence IDs; unknown IDs are rejected and trigger grounded fallback/repair behavior.

## 24. Recommendation Grounding

Every recommendation is validated against the same emitted evidence set. The live recommendations referenced existing knowledge evidence IDs. No model-created source or arbitrary evidence reference was accepted.

## 25. Diagnosis Persistence

The live request persisted a completed diagnosis in MySQL with diagnosis ID `diagnosis-28a36d7b-0b25-4c75-b7b7-54e84f064c4e`, source run ID `rag-a9b5dd39-e184-4258-92ef-1ca1f792ab18`, user ID 1, farm ID 1, and the real field ID. Persistence uses an immutable payload snapshot.

## 26. Diagnosis Reload

`GET /diagnoses/{id}` returned HTTP 200, the same diagnosis ID, COMPLETED status, and the same seven evidence records. The reload invariant passed.

## 27. Knowledge Version Pinning

The persisted diagnosis retains the knowledge version IDs used during retrieval. Replacing or activating a later knowledge version does not mutate the old diagnosis payload. Unit evaluation covers the old-version snapshot invariant.

## 28. HTTP E2E

The live sequence was Login → Farm field list/create → FieldContext GET → POST `/diagnosis/analyze` → MySQL persistence → GET diagnosis reload. Login, context, diagnosis, and reload all returned HTTP 200. The request deliberately included forged farm/crop/growth/weather/context values; the response retained server farm 1 and REAL FieldContext.

## 29. REAL Source Integrity

The live response reported `realContextMode=REAL`, farm ID 1, the `field-*` ID, Qwen BAILIAN embedding, 1024 dimensions, Qwen collection, GTE READY, weather READY_EMPTY, soil/satellite UNAVAILABLE, GIS READY, and historical READY_EMPTY. No A-12/demo evidence appeared.

## 30. Multisource Evaluation

`CommercialMultisourceRagEvaluationTest` covers Qwen provenance, reranker degradation, forged client weather/soil, unavailable soil/satellite, empty historical data, trusted GIS, vision unavailable/ready branches, grounded references, persistence reload, and Qwen-unavailable/no-BGE-fallback behavior.

## 31. Runtime Health

Final runtime health: Spring reachable; MySQL READY; Qwen knowledge READY with 507 rows; BAILIAN embedding READY; GTE reranker READY; Milvus endpoint `::1:19530`; historical READY_EMPTY with 0 rows; migration readiness READY. local-ai text embedding is READY. The vision provider and CLIP image embedding are explicitly unavailable, so the intelligence plane is DEGRADED rather than falsely READY.

## 32. Targeted Tests

The targeted suite passed: `CommercialMultisourceRagEvaluationTest`, `DiagnosisRagServiceTest`, `HybridKnowledgeRetrieverTest`, `KnowledgeEvidenceProvenanceTest`, `EvidenceFusionTest`, and `HistoricalCaseRetrieverTest`. Java compile also passed.

## 33. MySQL Tests

The runtime used the existing local MySQL `zhgy` database over TCP, authenticated through the existing user service, loaded real farms and field data, created the Phase E verification field through the authenticated API, and persisted/reloaded the diagnosis through the JDBC repository. No production corpus reset or destructive SQL was used.

## 34. Live Bailian Validation

The live diagnosis invoked Bailian for Qwen evidence synthesis and structured multimodal diagnosis generation. The runtime logged successful model calls and returned a persisted grounded diagnosis. Provider API credentials were supplied only through the local process environment and are not included in this document.

## 35. AI Baseline Regression

The existing AI/runtime baseline tests remained green in the full Maven run. The Phase E changes did not alter the frozen local BGE/CLIP compatibility contract or the historical image collection contract.

## 36. Agent Phase 1.1 Regression

The existing Agent Phase 1.1 regression tests remained green in the full Maven run. No Agent Planner, approval, or execution semantics were changed for Phase E.

## 37. Full Maven

Full Maven completed with **130 tests, 0 failures, 0 errors, and 13 skipped**. The earlier ONNX allocation failure was reproduced only under concurrent local runtime memory pressure; stopping the local AI/vision processes for test isolation allowed the unchanged full suite to pass. The local processes were then restored for the final runtime health check.

## 38. Frontend Validation

TypeScript compilation passed, targeted ESLint passed for the diagnosis request/contract/service, and `npx vite build --minify=false` passed after transforming 7172 modules. The diagnosis view now submits canonical real-field inputs and allows image-optional Core Phase E diagnosis.

## 39. Files Changed

Phase E changes are confined to the existing diagnosis/RAG/context/gateway/frontend/test surfaces plus the required result document. The working tree remains dirty by design; prior user changes and Phase A–D.2 artifacts were preserved. Temporary runtime helper scripts used for local verification are removed before handoff.

## 40. Remaining Commercial MVP Gaps

Live vision provider availability, CLIP image embedding availability, trusted soil integration, trusted satellite integration, and non-empty historical cases remain external/product data gaps. OCR, legacy DOC/PPTX/JPG indexing, camera registry, Agent recovery, adaptive planning, voice, and Graph RAG remain out of Phase E scope.

## 41. Phase E Decision

**CONDITIONAL GO.** Phase E Core RAG is GO on all hard gates. Live Vision is CONDITIONAL solely because the external vision provider is unavailable; the system reports this explicitly and emits zero visual evidence. The dependency does not create fake success and does not block Core Phase E.

## 42. Phase F Entry

Phase F may begin only after this result is accepted and the remaining product gaps are separately prioritized. The next safe entry state is the persisted, provenance-complete, Qwen/GTE-grounded diagnosis path with explicit optional-source degradation and no Agent scope expansion.

## Phase E Core GO Hard Gates

- REAL `field-*` requests use server FieldContext.
- Client forged weather, crop, soil, GIS, NDVI, and historical values do not become evidence.
- Production Qwen retrieval, 1024 dimensions, the frozen Milvus collection, and GTE reranking participate in diagnosis.
- Knowledge provenance, claims, recommendations, persistence, reload, and version pinning are stable.
- HTTP E2E, targeted tests, frontend validation, AI baseline regression, Agent regression, and full Maven passed.

## Vision GO Classification

Core RAG: **GO**. Live Vision: **CONDITIONAL**. This is the permitted Phase E outcome when the only unmet live capability is the external vision provider; Soil, Satellite, and Historical remain explicit unavailable/empty sources rather than NO-GO conditions.
