# FarMap V2 — Backend Product Loop V2 Final Architecture

状态：**5.2 Final code GO / Backend Foundation FROZEN / Production CONDITIONAL GO**  
分支：`feature/commercial-product`  
审计日期：2026-09-06

## 1. 冻结范围

本架构冻结 5.2.1–5.2.6 已实现的后端产品闭环。冻结对象是身份、关系、所有权、持久化、事务、失败语义和知识版本 provenance；不包含 Agent V2、Multimodal RAG V2、HistoricalCase promotion 或新的业务对象。

## 2. Domain graph

```text
KnowledgeSource S
  ↓ sourceId
KnowledgeDocument D
  ↓ documentId / currentVersionId
KnowledgeVersion V
  ↓ versionId + chunkId
Evidence E
  ↓ evidenceId (Diagnosis payload snapshot)
Diagnosis DX
  ↓ optional recommendationId
Recommendation R?
  ↓ validated AgentRun context
AgentRun RUN
  ↓ actionId
AgentAction A
  ↓ (runId, actionId)
Approval
  ↓ taskId
OperationsTask T
  ↓ outcomeId
Outcome O
  ↓ reviewId
ExpertReview ER
  ↓ candidateId
HistoricalCaseCandidate HC
```

每条关系均由服务端通过稳定 ID 和真实父对象验证。title、filename、数组序号、latest record、自然语言和前端推断不参与关系恢复。

## 3. Stable identity and authoritative source

| Object | Stable ID | Authoritative source | Nullable relation |
| --- | --- | --- | --- |
| KnowledgeSource | `sourceId` | `knowledge_sources.source_id` | owner may be null only for PACKAGED |
| KnowledgeDocument | `documentId` | `knowledge_documents.document_id` | `currentVersionId` may be null until activation |
| KnowledgeVersion | `versionId` | `knowledge_versions.version_id` | `createdBy` may be null for PACKAGED |
| Evidence | `evidenceId` | immutable `agent_diagnoses.payload_json` snapshot | historical modality may be empty |
| Diagnosis | `diagnosisId` | `agent_diagnoses.diagnosis_id` | recommendation list may be empty |
| Recommendation | `recommendationId` | Diagnosis persisted payload snapshot | optional; no separate table in 5.2 |
| AgentRun | `runId` / `sourceRunId` | `agent_runs.payload` | diagnosis context may be absent for legacy run |
| AgentAction | `actionId` | AgentRun persisted actions | recommendationId optional |
| Approval | `(runId, actionId)` | `agent_approvals` composite primary key | taskId null until execution |
| OperationsTask | `taskId` | `agent_operations_tasks.task_id` | legacy `actionId` may be null |
| Outcome | `outcomeId` | `agent_operation_outcomes.outcome_id` | one canonical row per task |
| ExpertReview | `reviewId` | `agent_expert_reviews_v2.review_id` | task/outcome null for DIAGNOSIS_ONLY |
| HistoricalCaseCandidate | `candidateId` | `historical_case_candidates.candidate_id` | candidate requires complete outcome review |

`diagnosisId` is intentionally different from `sourceRunId`; a Diagnosis is the commercial snapshot, while the Agent/RAG run is provenance. Recommendation remains part of that immutable snapshot to avoid a second fact source in this milestone.

## 4. Ownership boundary

New user-managed Knowledge Source/Document, Diagnosis, AgentRun, Task, Review and Candidate records receive their owner from authenticated JWT `BaseContext`. Requests cannot set `ownerUserId`, `reviewerUserId`, timestamps or IDs. Read and mutation paths apply the same ownership rule; system PACKAGED Knowledge is read-only. Legacy rows with `owner_user_id IS NULL` remain readable only through the documented compatibility path and are marked enterprise security debt. The current Expert boundary authenticates an actor and checks ownership; a verified Expert role/RBAC model is intentionally not part of 5.2.

`assignee` is an operational string, not a workforce directory identity. It is not used as an authorization substitute.

## 5. Persistence boundaries

- MySQL is authoritative whenever `FARMAP_AGENT_PERSISTENCE=mysql` or a production DataSource is present.
- Explicit no-DataSource constructors are test/demo compatibility only. Production constructors set `allowFallback=false` and fail closed.
- Diagnosis, AgentRun, Approval, Task, Outcome, ExpertReview V2, Candidate and Knowledge writes never report success from an in-memory fallback when the database is configured.
- Legacy file Agent storage remains a separate DEMO mode and is exposed as `DEMO` in health.
- HistoricalCase Candidate is a MySQL row only. It does not write the Milvus collection `farmap_image_vectors_new`.

## 6. Transaction boundaries and concurrency

| Operation | Atomic boundary |
| --- | --- |
| Diagnosis save | one immutable `agent_diagnoses` insert; duplicate identity is rejected/returned according to service contract |
| Approval | composite-key insert and execution claim in `JdbcApprovalStore`; a single DB claim wins |
| Approval → Task | action provenance is revalidated, and `action_id` unique boundary makes task creation idempotent |
| Task completion | row lock/state check, Task terminal update, Outcome insert and `TASK_COMPLETED`/`OUTCOME_CREATED` events commit together |
| Task cancel vs complete | terminal state update is the winner; a cancelled task cannot receive an Outcome |
| ExpertReview update | `version` compare-and-set; stale version returns `REVIEW_CONFLICT`/409 |
| Candidate create | eligibility evaluation plus insert; `UNIQUE(review_id)` and duplicate-read retry return the same candidate |
| Knowledge activation | Document row lock, current pointer update and `VERSION_ACTIVATED` event commit together |

No JVM-only lock is relied upon for a cross-instance uniqueness guarantee.

## 7. Database constraints

The installed MySQL 8.0.36 schema was inspected directly and matches the versioned migrations:

- `agent_approvals.PRIMARY(run_id, action_id)` — one decision/execution record per action.
- `agent_operations_tasks.uk_agent_operations_action_id(action_id)` — at most one Task per AgentAction; nullable keeps legacy rows readable.
- `agent_operation_outcomes.uk_operation_outcome_task(task_id)` — at most one canonical Outcome per Task.
- `agent_expert_reviews_v2.uk_expert_review_v2_outcome(outcome_id)` — one canonical Outcome-level Review; `uk_expert_review_v2_diag_only` preserves one diagnosis-only canonical review.
- `historical_case_candidates.uk_historical_candidate_review(review_id)` — one Candidate per Review.
- `knowledge_versions.uk_knowledge_version_hash(document_id, content_hash)` and `uk_knowledge_version_number(document_id, version_number)` — append-only content/version boundaries.
- Primary keys exist for every stable ID and audit event ID. Knowledge source origin also has a unique `(source_type, origin_uri)` boundary.

## 8. Immutable snapshots and provenance

Diagnosis GET reads the stored payload and never reruns RAG. Evidence stores `sourceId`, `documentId`, `versionId`, content hash and chunk provenance in the snapshot. Activating Knowledge V2 therefore cannot mutate a V1 Diagnosis; a newly created Diagnosis can use V2. Recommendation IDs are validated against the same Diagnosis snapshot. Task, Outcome, Review and Candidate carry only the minimum relationship IDs needed for querying and audit.

## 9. Failure and status semantics

The product distinguishes:

- `READY`: dependency is available and has usable records.
- `READY_EMPTY`: dependency is healthy but no eligible records exist (for example, empty historical Milvus or a knowledge document without a current version).
- `UNAVAILABLE`: dependency or database cannot be reached; API writes fail and read APIs return a dependency error rather than an empty success.
- `DEMO`: explicit in-memory/file compatibility mode, never silently mixed with REAL.

Knowledge health returns HTTP 503 for `UNAVAILABLE`; diagnosis dependency failures return 503. Runtime health reports MySQL Agent/Knowledge/Operations/Review/Candidate states separately from Milvus historical status. The installed runtime reports Knowledge `READY`, historical store `READY_EMPTY` with rowCount 0, and migration readiness `READY`; the local AI/Mongo endpoints remain environment-dependent and are not masked as ready.

## 10. Migration strategy

The repository uses ordered scripts `V5_2_1` through `V5_2_6` under `src/main/resources/db/migration`. There is no formal migration runner. 5.2 Final therefore adopts **Option B — Production migration verifier**: `MigrationReadinessVerifier` reads `information_schema` and verifies required tables, columns and unique indexes without creating or altering anything. `GET /api/runtime/migration-readiness` is `READY` only when the verifier passes; missing schema is `NOT_READY`, database outage is `UNAVAILABLE`.

Runtime `CREATE TABLE IF NOT EXISTS` bridges remain for current local/test installations. They are compatibility bridges, not migration proof. Production deployment must run the six migrations in order before enabling writes. The existing scripts contain non-idempotent `ALTER TABLE` statements, so deployment must apply each version once and record it in the organization’s release process.

## 11. Legacy and demo boundaries

Legacy `agent_expert_reviews`, ownerless Tasks and `rag-*` references remain readable through compatibility services. They are not backfilled into V2 provenance and cannot be used to claim tenant isolation. `a12-demo`, file persistence and no-DataSource constructors remain explicit demo/test paths. Demo reviews/candidates do not promote to HistoricalCase, and packaged orchard text is a read-only system Knowledge source.

## 12. AI and external dependency boundaries

Multimodal RAG, Knowledge retrieval, Agent tool calling, approval and evidence grounding are represented in product objects and health, but this milestone does not redesign their planners, embeddings or rerankers. Local AI/model gateway, Mongo and Milvus availability is surfaced as dependency state. Historical vector ingestion is disabled until an explicit Candidate promotion milestone; no candidate save triggers embedding, Milvus insertion, prompt update, training or fine-tuning.

## 13. Freeze acceptance

The freeze is accepted because the final test matrix demonstrates stable IDs, provenance conflict rejection, ownership rejection, approval/task/outcome idempotency, complete/cancel terminal safety, Review CAS, Candidate idempotency/concurrency, Knowledge V1→V2 snapshot preservation, restart/repository reload stability, database fail-closed behavior and frontend contract compatibility. Production remains conditional on deployment migration execution, enterprise ownership/RBAC policy and external dependency provisioning.
