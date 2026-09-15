# FarMap Commercial MVP Phase G — Full E2E / Pilot Readiness Result

Date: 2026-09-07  
Branch: `feature/commercial-product`  
Decision boundary: Phase G only; no post-MVP AI optimization, knowledge reimport, or Agent/RAG redesign.

## 1. Decision

**Core commercial MVP pilot loop: GO. Production rollout: CONDITIONAL GO.** The persisted Diagnosis → Agent → approval → Operations task → Outcome loop is executable against the local MySQL/Milvus/Bailian runtime with real HTTP requests.

## 2. Scope

The verification covered runtime consistency, the authenticated product loop, fail-closed behavior, cross-user authorization, idempotency, review/candidate boundaries, startup health, migrations, frontend build health, and pilot checklist readiness.

## 3. Stop boundary

Work stopped at the requested Phase G boundary. Live vision remains conditional because the optional vision plane is unavailable in this runtime. No speculative AI-quality tuning was added.

## 4. Runtime gap observed

Phase E health reported Knowledge `READY`, while the Phase F Agent gate reported `knowledge unavailable` and degraded to `allowsWrite=false`. This was an operational consistency defect, not a missing knowledge corpus.

## 5. Runtime consistency root cause

The local commercial startup path did not explicitly select the Qwen/Bailian knowledge provider and MySQL Agent persistence. The application defaults still pointed at local-BGE/file-oriented settings, while the Agent and Diagnosis paths expected the Qwen vector backend.

## 6. Generic health limitation

The generic knowledge health response counted persisted database versions but did not prove that the configured vector provider was reachable or that the Agent gate could use the same backend. The audit therefore separates persisted evidence provenance from current dependency availability.

## 7. Startup fix

`scripts/run-backend-local.ps1` now supplies the local commercial runtime defaults for MySQL, Milvus IPv6 loopback, Mongo optional host, BAILIAN embeddings, MySQL Agent persistence, and disabled case ingestion unless explicitly enabled.

## 8. Persistence configuration

The verified runtime uses MySQL for Agent runs, Operations tasks, Expert Reviews, Historical Candidates, and related persisted relations. The full loop does not depend on in-memory Agent state.

## 9. Milvus configuration

The verified Qwen collection is reachable at the configured local Milvus endpoint. The Qwen collection exists with dimension 1024 and populated rows; the historical image collection remains an explicitly empty optional surface.

## 10. Runtime restart

After the startup override, the backend was restarted and direct health checks showed Agent, Knowledge, Qwen embedding, GTE reranker, Milvus, Operations, Review, Candidate, and migration readiness as `READY`.

## 11. Login valid

A fresh pilot user registered and then re-authenticated through the real HTTP login endpoint. The response returned a usable authenticated session and one scoped farm fixture.

## 12. Login invalid

An invalid username produced HTTP 401 after the auth-boundary fix. The controller no longer converts a missing account into a misleading server error.

## 13. Farm

The authenticated pilot user retrieved the scoped farm through HTTP. The farm fixture is local pilot data created only to make the fresh end-to-end run reproducible.

## 14. Field

The user created a field through the real farm API. The field was assigned a stable UUID and remained the ownership anchor for context, Diagnosis, task, and Outcome evidence.

## 15. FieldContext

`GET /api/fields/{fieldId}/context` returned HTTP 200 with `sourceMode=MYSQL` and `status=READY`. Weather was explicitly `UNAVAILABLE`; images were explicitly `READY_EMPTY`, without blocking the core knowledge path.

## 16. Diagnosis request

A fresh HTTP Diagnosis request ran against the authenticated field and persisted a new Diagnosis snapshot. This was not a mocked response or a pre-seeded diagnosis.

## 17. Diagnosis persistence

The fresh Diagnosis ID was `diagnosis-22486cdb-2e3f-4bae-b6a2-47d1c3bf7696`. It contained five evidence records and two recommendations, with server-generated identifiers used by later Agent calls.

## 18. Recommendation

`recommendation_1` was selected from the persisted Diagnosis response. The Agent request referenced that persisted recommendation rather than accepting client-supplied free-form action content.

## 19. Knowledge evidence

The real Diagnosis response contained curated knowledge evidence with `sourceMode=REAL`, `status=READY`, `classification=ADMIN_CURATED`, source/document/version/chunk identifiers, and content hashes.

## 20. Qwen embedding

The Qwen embedding provider was `READY`; the verified retrieval metadata identified `qwen3.7-text-embedding-flash`. Direct provider probes returned successfully without exposing credentials or payload secrets.

## 21. GTE reranker

The GTE reranker was `READY`. Diagnosis evidence reported the Qwen-vector plus GTE-rerank retrieval path, and the Agent sufficiency gate accepted the resulting persisted evidence.

## 22. Optional source states

Knowledge remained usable while camera, weather, soil, GIS, and satellite inputs were absent or unavailable. Optional absence was represented as evidence/source state rather than silently treated as a successful observation.

## 23. Agent request

The fresh REAL Agent request used the persisted Diagnosis ID and `recommendation_1`. The resulting run ID was `agent-7bb0fa52-8c7a-4fab-a470-129648d76365`.

## 24. Agent run

The run reached `WAITING_FOR_APPROVAL` with six steps and one proposed write action. It was persisted in MySQL and was retrievable through the Agent HTTP API.

## 25. Sufficiency

The run reported `evidenceSufficiency.status=SUFFICIENT` and `allowsWrite=true`. The sufficiency evidence was server-resolved from persisted Diagnosis evidence; unavailable dependency state was not hidden.

## 26. ProposedAction

The proposed action was `action-agent-7bb0fa52-8c7a-4fab-a470-129648d76365-task`. It retained stable Diagnosis, recommendation, and evidence IDs and required approval before task creation.

## 27. Approval

The first approval returned HTTP 200, changed the run to completed/approved state, and created the related Operations task. A repeated approval returned HTTP 200 while the matching task count remained one, confirming action-level approval idempotency for the tested run.

## 28. Operations task

The created task was `task-8a842605-b0ae-4eab-9920-91b5a65e7213`. It retained the Agent action, Agent run, Diagnosis, recommendation, field, and evidence relationships.

## 29. Task OPEN

Immediately after approval, the task was `OPEN` and visible through the authenticated Operations task list.

## 30. Task ASSIGNED

The task transitioned to `ASSIGNED` with an explicit operator assignee through the Operations API.

## 31. Task IN_PROGRESS

The same task transitioned to `IN_PROGRESS` through the real lifecycle endpoint. Ownership and authorization remained scoped to the pilot user.

## 32. Task COMPLETED

The task transitioned to `COMPLETED`. Completion was accepted once and subsequent completion returned the same persisted Outcome rather than creating a second outcome.

## 33. Outcome

The persisted Outcome ID was `outcome-25903396-a0b7-4ed5-89d2-2f22646aa9ba`. The Outcome endpoint returned HTTP 200 after task completion.

## 34. Outcome provenance

The Outcome response now exposes `diagnosisId`, `recommendationId`, `agentRunId`, `fieldId`, and `evidenceIds` from the persisted task relation. This closes the earlier provenance visibility gap without changing the Outcome table schema.

## 35. Completion idempotency

Repeated completion returned the same Outcome ID and did not create a second completion result. The lifecycle event count remained stable for the duplicate request.

## 36. Approval idempotency

Repeated approval of the already approved action returned HTTP 200 and did not add another task for that action. The task list contained one matching action task; an unrelated pre-existing task remained separately identifiable.

## 37. Audit events

The task lifecycle retained the expected audit sequence: creation/open, assignment, start, completion, and outcome association. The persisted action and task IDs provide the join keys for review.

## 38. Expert Review

An authenticated review was created with `scope=OUTCOME_REVIEW`, linked Diagnosis/task/Outcome/recommendation IDs, and explicit Diagnosis, recommendation, outcome, and confidence judgments. Review ID: `review-56738dbf-c3f6-47a2-bc2a-a43ba35845c0`.

## 39. Historical Candidate

The linked candidate was created with status `ELIGIBLE` and retained Diagnosis and Outcome links. Candidate ID: `candidate-4ef2073d-8304-4352-b0d3-6ce3db90a148`. Promotion into a historical corpus remains outside this Phase G run.

## 40. G-A summary

G-A passed: Login → Farm → Field → FieldContext → Diagnosis → Recommendation → REAL Agent → sufficient evidence → approval → Operations task → full lifecycle → Outcome → provenance → review → candidate.

## 41. G-B summary

G-B passed: a Diagnosis request with knowledge plus unavailable optional modalities returned a degraded-but-usable result. Knowledge evidence remained present, optional camera evidence count was zero, and the core loop was not falsely blocked.

## 42. G-C summary

G-C passed: a REAL Agent request using a forged recommendation ID returned HTTP 422 with no run ID and `failClosed=true`. No unauthorized write action was created.

## 43. Cross-user

A second authenticated user received HTTP 403 for the first user’s Field, Agent run, Operations task, Expert Review, and Historical Candidate. No cross-user read or write was observed.

## 44. Auth error states

Missing/invalid credentials and ownership violations are surfaced as authentication/authorization failures rather than empty successful payloads. Guest access to admin knowledge writes returned HTTP 403.

## 45. REAL/DEMO boundary

REAL mode requires persisted, user-scoped Diagnosis data and creates auditable state only after approval. DEMO mode is explicitly read-only, uses demo-labelled routes/data, and is not used as evidence for the REAL loop.

## 46. Empty states

FieldContext, optional source, historical image, and optional vision surfaces expose explicit empty/unavailable states. The frontend Operations view retains task rows if an Outcome fetch fails and displays the available state instead of inventing an Outcome.

## 47. Preflight

The runtime preflight now allows a 30-second Spring health window, avoiding false startup failure during normal initialization. The final preflight reached the Spring endpoint and reported the core services.

## 48. Runtime health

Final core health: Spring `REACHABLE`; Agent `READY`, persistence `mysql`; Knowledge `READY`; Qwen `READY`; embedding `READY`; reranker `READY`; Milvus `READY`; Operations `READY`; Review `READY`; Candidate `READY`; migration `READY`.

## 49. Migration

Migration readiness reported `READY` with no missing required tables, columns, or indexes. No destructive migration or schema reset was performed.

## 50. Admin knowledge

The authenticated smoke check returned admin knowledge health HTTP 200 with ingestion `READY`. A guest knowledge-source write returned HTTP 403. A positive admin import was not re-run because this local database has no provisioned valid admin fixture; the pilot runbook must provision and verify one.

## 51. Startup

The verified local startup is reproducible through `scripts/run-backend-local.ps1`. Runtime secrets remain process/environment inputs and are excluded from this report and from source changes.

## 52. Performance

Template generation mode was used for the final loop while retaining real Qwen vector/GTE retrieval. The earlier Qwen generation/VL attempt demonstrated real provider latency but exceeded the practical HTTP timeout; Live Vision therefore remains conditional rather than being represented as complete.

## 53. Full Maven

Full Maven reports showed 134 tests, 0 failures, 0 errors, and 14 skips across 72 test classes. The skipped tests are environment/native-resource or optional capability cases; no failure was suppressed.

## 54. Phase E regression

Phase E multisource retrieval behavior remained intact: real Qwen vector evidence and GTE reranking were present, optional sources degraded explicitly, and the vision plane stayed conditional.

## 55. Phase F regression

Phase F Agent product-loop behavior remained intact: server-side sufficiency, approval gating, persisted MySQL runs, task creation, and action-to-task relationships all passed in the fresh HTTP run.

## 56. AI baseline / Agent Phase 1.1

The AI baseline is grounded retrieval plus deterministic/template synthesis in this runtime. Agent Phase 1.1 is operationally ready for the core loop, but production live vision and unrestricted generative diagnosis are not declared ready.

## 57. Frontend

The Operations view now fetches and displays persisted Outcome provenance for completed real tasks while retaining the existing real/demo and empty/error states. The changed file passed targeted ESLint and the production frontend build.

## 58. Browser E2E

No browser automation connector was available in this environment, so browser screenshot evidence was not claimed. API-level E2E plus frontend build/type validation is the verified substitute; browser smoke remains a pilot gate.

## 59. Pilot blockers

Before external pilot rollout, provision a valid admin operator fixture and run the positive admin knowledge ingest smoke; run browser E2E; decide whether Live Vision is required for the pilot; and confirm production secrets, TLS, observability, backup, and rollback procedures.

## 60. Production hardening / AI / optional backlog

Production hardening includes admin provisioning/runbook, browser automation, external health monitoring, alerting, backup/restore, rate limits, and deployment rollback. AI backlog includes live vision reliability and latency evaluation. Optional data-source backlog includes real weather/soil/GIS/satellite adapters and historical candidate promotion policy.

## 61. Files changed

Relevant Phase G changes are the startup runtime defaults, login error boundary, Outcome provenance mapping, preflight timeout, Operations Outcome fetch, the full-loop evaluation test, this result, and the pilot checklist. Existing unrelated dirty-worktree changes were preserved.

## 62. Commercial MVP status / NEXT

Commercial MVP status is **GO for controlled pilot of the Core Agent Product Loop; CONDITIONAL GO for production rollout**. NEXT: provision the admin operator, execute browser smoke and deployment preflight, then make the explicit Live Vision/pilot rollout decision.

