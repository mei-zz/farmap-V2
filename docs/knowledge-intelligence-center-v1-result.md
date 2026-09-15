# FarMap Knowledge Intelligence Center V1

Date: 2026-09-08

Decision: **GO for the Knowledge Intelligence Center V1 relational/provenance surface; runtime is DEGRADED while local Milvus is unavailable.**

Commercial MVP remains **FROZEN / NO REGRESSION**. Phase 1.7 was not started.

## Architecture

Knowledge Graph V1 is a read-only logical graph assembled per request from authoritative existing data:

- Spring Boot remains the product control plane.
- MySQL remains the canonical store for sources, documents, versions, diagnoses, reviews, and candidates.
- Milvus is queried only for current vector-collection health and counts.
- No Neo4j, graph database, graph write model, re-embedding, or Milvus rebuild was introduced.

The backend contract is `GET /api/knowledge/graph` under the existing `/api/knowledge` namespace. It accepts bounded filters for `nodeTypes`, `sourceId`, `documentId`, `diagnosisId`, `limit`, and `includeEvidence`, and returns typed `status`, `generatedAt`, `nodes`, `edges`, `summary`, `coverage`, `collections`, `pipeline`, and `retrieval` fields.

The server caps visible nodes at 150 and does not emit individual chunk nodes by default. Chunk counts are aggregated on KnowledgeVersion/KnowledgeDocument metadata.

## Graph definition

This is a **provenance / relationship graph**, not a semantic ontology graph. No LLM-generated Topic, Disease, Crop, Symptom, or Treatment nodes are created.

Observed live graph for authenticated user `guest` / actor `1`:

| Item | Count |
|---|---:|
| Nodes | 87 |
| Edges | 140 |
| KNOWLEDGE_SOURCE | 5 |
| KNOWLEDGE_DOCUMENT | 29 |
| KNOWLEDGE_VERSION | 29 |
| EVIDENCE | 12 |
| DIAGNOSIS | 10 |
| EXPERT_REVIEW | 1 |
| HISTORICAL_CASE_CANDIDATE | 1 |

Node IDs are server-owned stable IDs from the existing records. Evidence IDs are accepted only when present in persisted Diagnosis evidence payloads; their provenance fields are taken only from explicit `sourceId`, `documentId`, and `versionId` values.

Observed edge types:

| Edge type | Count | Explicit relation |
|---|---:|---|
| HAS_DOCUMENT | 29 | Source → Document |
| HAS_VERSION | 29 | Document → current Version |
| SUPPORTS | 10 | Version → Evidence |
| USED_BY | 70 | Evidence → Diagnosis |
| REVIEWED_BY | 1 | Diagnosis → Expert Review |
| PRODUCES_CANDIDATE | 1 | Expert Review → Historical Case Candidate |

No edge is inferred from names, titles, latest/nearest position, natural language, or LLM output. Dangling edges were checked as zero.

## Frontend

The former Knowledge & Expert page is now a Knowledge Intelligence Center with these tabs:

1. 知识图谱 — the default tab.
2. 知识库状态 — health, coverage, collections, RAG pipeline, source/document table, and read-only retrieval observability.
3. 专家复核 — the existing Queue and Workspace workflow.
4. 历史案例 — confirmed-case empty state and candidate distinction.

The graph canvas uses a bounded SVG read model rendered as a center-expanding point cloud: real nodes are circular points with type colors, size reflects local relation degree, and the selected/high-association node becomes the visual anchor. It supports node-type filtering, stable-text/ID search, fit, zoom, reset, selection, connected-relation highlighting, and a selected-entity detail panel. This is a visual layout change only; node and edge semantics remain unchanged.

The page keeps one global `Ask Copilot` entry. It does not create a second chat panel.

## Knowledge runtime and coverage

Live API values at verification time:

| Metric | Value / status |
|---|---|
| Knowledge Sources | 5 |
| Documents | 29 |
| Versions | 29 |
| Relational chunks | 511 |
| Production Qwen chunks in relational registry | 507 |
| Compatibility BGE chunks | 4 (511 − 507) |
| READY documents | 26 |
| Registered ingestion files | 28 |
| Total original corpus files | unavailable from current registry |
| Unsupported/corrupt/no-text files | unavailable from current registry |

The UI shows 507 production chunks dynamically from MySQL, while the production Milvus row count remains `—` when the runtime cannot prove it. It does not hardcode or silently convert the relational chunk count into a vector count.

### Collections

The two collections are shown separately:

- Production Knowledge: `farmap_knowledge_chunks_qwen3_v1`, Qwen `qwen3.7-text-embedding-flash`, 1024 dimensions, COSINE. Current status: **DEGRADED**, current vector row count unavailable because local Milvus `19530` was not listening.
- Historical Image / Case: `farmap_image_vectors_new`, 512 dimensions, L2. Current status: **UNAVAILABLE**, current row count unavailable for the same reason. No historical vectors or cases were fabricated. The earlier verified baseline was `READY_EMPTY` / 0, but this run does not re-claim that count while Milvus is down.

The current overall graph status and RAG pipeline status are **DEGRADED**. MySQL document extraction/chunk metadata, stored Evidence provenance, actor-scoped Diagnosis, and GTE reranker metadata are available; Qwen/Milvus health and candidate retrieval are unavailable in this runtime. The page therefore does not show Milvus or retrieval as READY.

Retrieval observability keeps Qwen vector score and GTE rerank score as separate fields and does not raw-combine them. The read-only test query remains unavailable while the vector dependency is down.

## Expert Review preservation

The existing Expert Review Queue, Review Workspace, AI Diagnosis, Expert Judgment, Evidence/Recommendations, Confirm AI Diagnosis, and Submit Expert Revision actions remain under 专家复核. Existing V2 review APIs remain unchanged; the page still falls back to the legacy review read surface only when no V2 records are returned.

When an existing review Evidence ID is also present in the graph, the workspace exposes 查看知识来源 and focuses the corresponding graph node. It does not use title matching. The current actor-scoped review fixture had no linked Evidence action to activate, which is an honest data condition rather than a fabricated link.

## Historical Cases and traceability

Historical Cases remain separate from HistoricalCaseCandidate. The current UI shows **0 confirmed historical cases** and **1 actor-scoped candidate**. The empty historical vector/runtime state is displayed separately and no case is promoted without the existing confirmation, valid image/record, expert judgment, and outcome conditions.

Traceability is compact and explicitly states that Memory is not Evidence. Long-term Memory is not connected to this V1 graph.

## Security

The graph controller obtains the actor from the existing authenticated `BaseContext`; the client cannot select an actor. Relational reads apply:

- `agent_diagnoses.user_id = actor`.
- Expert reviews require `subject_owner_user_id = actor` and an existing Diagnosis owned by that actor.
- Historical candidates require `owner_user_id = actor` and an existing returned review relationship.
- Global KnowledgeSource/Document visibility follows the current owner/global permission predicate.

Live security check: 19 foreign private IDs were collected from canonical MySQL records, the user-1 graph contained 12 private nodes, and the intersection was **0**. Cross-user private node leakage: **PASS / 0**.

No fake graph data was emitted: the live node type set was limited to the seven declared types above, with no semantic ontology nodes and no chunk expansion.

## Verification

- Backend compile: `mvn -q -DskipTests compile` — **PASS**.
- Backend targeted regression/contract run: `KnowledgeGraphServiceMysqlTest`, `CurrentWeatherServiceTest`, `AgriculturalContextServiceTest`, `ExpertReviewV2Test`, and `HistoricalCaseRetrieverTest` — **11 tests, 0 failures, 0 errors, 0 skipped** with the real local MySQL credential source.
- Frontend build: `pnpm build` — **PASS**; 7,180 modules transformed. Vite emitted only the existing large-chunk advisory.
- Browser login: real `guest` / actor 1 session — **PASS**.
- Browser default view: `/farmap/knowledge/library` opened on 知识图谱 — **PASS**.
- Browser graph/API: HTTP 200, 87 rendered real nodes, bounded at 150, stable Source → Document relation, selected Stable ID details, no unexpected node types, and no dangling edges — **PASS**.
- Browser status: both production and historical collection names visible, 507 production chunks visible, Milvus degradation visible — **PASS**.
- Browser preservation: Expert Review Queue/Workspace, Historical Cases, and Ask Copilot visible — **PASS**.
- Browser gate: no page errors, no backend 5xx, and no failed browser requests in the final run. Existing shared/legacy Ant Design deprecation warnings are non-blocking and do not change the product state.

Screenshots are retained at:

- `farmap-frontend-dev/test-results/knowledge-intelligence-graph.png`
- `farmap-frontend-dev/test-results/knowledge-intelligence-status.png`
- `farmap-frontend-dev/test-results/knowledge-intelligence-review.png`
- `farmap-frontend-dev/test-results/knowledge-intelligence-history.png`

## Files added or changed for this milestone

Backend:

- `zhgy/zhgy/src/main/java/com/mei/zhgy/vo/KnowledgeGraphResponse.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/knowledge/KnowledgeGraphService.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/controller/KnowledgeController.java`
- `zhgy/zhgy/src/test/java/com/mei/zhgy/service/knowledge/KnowledgeGraphServiceMysqlTest.java`

Frontend:

- `farmap-frontend-dev/src/types/knowledge-graph.ts`
- `farmap-frontend-dev/src/features/knowledge-expert/service.ts`
- `farmap-frontend-dev/src/features/knowledge-expert/components/KnowledgeExpertCenter.tsx`
- `farmap-frontend-dev/src/features/knowledge-expert/components/KnowledgeGraphCanvas.tsx`
- `farmap-frontend-dev/src/features/knowledge-expert/components/KnowledgeSummaryCards.tsx`
- `farmap-frontend-dev/src/features/knowledge-expert/components/KnowledgeStatusPanel.tsx`
- `farmap-frontend-dev/src/features/knowledge-expert/components/ExpertReviewPanel.tsx`
- `farmap-frontend-dev/index.css`
- `farmap-frontend-dev/index.html` (removed an unrelated external favicon request that caused a browser resource failure)

Runtime support:

- `scripts/run-backend-local.ps1` now resolves the existing local `mysql.txt` user/password in-process when explicit datasource environment variables are absent; it never echoes or commits credentials.

## Known limitations

1. Local Milvus `19530` is currently unavailable. The graph remains useful from MySQL, but production vector row count, historical vector row count, and live vector retrieval are intentionally unavailable/degraded. No rebuild was attempted.
2. The current runtime cannot safely derive the original 37-file / 11-unsupported corpus split from the relational ingestion registry, so those fields remain unavailable instead of being inferred.
3. V1 is a bounded provenance graph with aggregate chunk counts. It does not implement semantic ontology extraction, graph editing, or automatic 20–50 chunk drill-down.
4. Historical vector status is `UNAVAILABLE` in this run rather than `READY_EMPTY` because the current Milvus runtime cannot be queried. Confirmed historical cases remain 0 in the product surface.

## Operational handoff

The verified local services remain running: MySQL `3306`, frontend `5173`, Spring Boot `8080`, Local AI `8001`, and Vision `5000`. Milvus `19530` was not started or rebuilt. Open:

`http://localhost:5173/farmap/`

Then choose **知识与专家** and verify **知识图谱 → 知识库状态 → 专家复核 → 历史案例**.

Git safety: **NO COMMIT / NO PUSH**. The intentionally dirty worktree was preserved; no reset, restore, clean, branch, or unrelated Commercial MVP changes were performed.
