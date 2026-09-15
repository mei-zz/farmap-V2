# FarMap Commercial MVP Phase F — Web Agent + Real Diagnosis Evidence Result

## 1. Decision

**CONDITIONAL GO.** The persisted Diagnosis → Agent → sufficiency → ProposedAction → approval → OperationsTask loop is GO. Automatic write-capable execution remains conditional on the knowledge dependency being available; Live Vision remains conditional exactly as frozen in Phase E.

## 2. Scope

This phase covered only the Web Agent handoff, persisted diagnosis/recommendation provenance, evidence sufficiency, approval, OperationsTask creation, ownership, reload behavior, and frontend wiring.

## 3. Explicit exclusions

Phase E RAG/Knowledge redesign, new Agent planning architecture, and Phase G work were not performed.

## 4. Phase E boundary

Phase E remains **Core Multisource RAG = GO / Live Vision = CONDITIONAL GO**.

## 5. Canonical entry contract

REAL Agent creation now accepts `diagnosisId`, `recommendationId`, and optional `intent`; the server reloads the persisted snapshot before creating the run.

## 6. Client context trust

Client-supplied diagnosis, recommendation, evidence, weather, and image content are not authoritative. Only the server-owned diagnosis snapshot and authenticated field context are used.

## 7. Diagnosis ownership

Diagnosis lookup is owner-scoped. A different user receives a generic domain denial (`DIAGNOSIS_NOT_FOUND`, HTTP 422) rather than the record.

## 8. Recommendation ownership

The requested recommendation must exist in the persisted diagnosis snapshot and belong to the requested diagnosis.

## 9. Recommendation evidence validation

Every recommendation evidence ID must exist in the persisted diagnosis evidence snapshot; unknown IDs are rejected before AgentRun creation.

## 10. AgentContext assembly

The backend assembles field, crop, phenology, location, source modes, diagnosis snapshot, recommendation snapshot, and evidence IDs from trusted server data.

## 11. RAG reuse rule

An Agent run with a persisted diagnosis does not rerun `multimodal-rag`; it consumes the persisted snapshot and preserves stable provenance.

## 12. Agent planner

The existing bounded sequential planner, max-step limit, max-tool-call limit, and timeout remain in place.

## 13. Tool registry

Write-capable steps are checked against the registered Tool Registry before action creation.

## 14. Unsupported action prevention

An unregistered or read-only write tool is skipped and produces no ProposedAction. The deterministic evaluation gate passed this prevention case.

## 15. Sufficiency gate

The gate checks trusted evidence, recommendation/evidence intersection, requested action evidence IDs, empty results, and unavailable dependencies.

## 16. Fail-closed behavior

Unavailable knowledge or other required dependencies produce a DEGRADED decision with `allowsWrite=false`; no write action is emitted.

## 17. ProposedAction

A supported action carries the server-owned diagnosis ID, recommendation ID, supporting evidence IDs, tool name, and `approvalRequired=true`.

## 18. Approval state

The action transitions to `WAITING_FOR_APPROVAL` with `ApprovalState.REQUIRED`; execution cannot occur before approval.

## 19. Approval CAS

Approval continues to use the existing compare-and-set/idempotency path and a single execution claim.

## 20. Duplicate approval

Repeated approval returns the already committed result and does not create a second OperationsTask.

## 21. OperationsTask provenance

The created task retains `actionId`, `agentRunId`, `diagnosisId`, `recommendationId`, owner, and evidence IDs.

## 22. Operations ownership

Operations task reads and lifecycle endpoints remain owner-scoped and return HTTP 403 for a different user.

## 23. Frontend diagnosis handoff

Persisted diagnosis recommendations now hand off stable IDs to Agent rather than serializing a client-created evidence context.

## 24. Frontend recommendation selection

The selected recommendation ID is preserved through navigation and copied back into the workspace context after reload.

## 25. Frontend real route

Real routes load the existing AgentRun when a run ID is present; they do not fall back to the A12 demo workspace.

## 26. Frontend demo boundary

`/analysis/a12-demo` remains an explicit demo-only path. REAL routes use neutral loading fallbacks and never leak demo diagnosis/images.

## 27. Refresh invariant

Refreshing the real Agent workspace reloads the server run and preserves diagnosis/recommendation provenance instead of reconstructing it from browser state.

## 28. HTTP runtime

Spring Boot was run on localhost:8080 with MySQL-backed diagnosis, approvals, and OperationsTask storage.

## 29. Agent persistence runtime

The final runtime verification used `FARMAP_AGENT_PERSISTENCE=mysql`; the health response reported `agent=READY/mysql`.

## 30. MySQL diagnosis fixture

The verified persisted diagnosis was `diagnosis-28a36d7b-0b25-4c75-b7b7-54e84f064c4e`; the selected recommendation was `rec-1`.

## 31. HTTP Scenario A input

User 1 submitted a REAL request with the persisted IDs and forged client diagnosis/evidence content. The server ignored the forged content.

## 32. HTTP Scenario A result

The run completed with `DEGRADED`, `allowsWrite=false`, unavailable dependency `knowledge`, and zero actions. This is the intended safe block.

## 33. HTTP Scenario A evidence

The run retained server-owned evidence and stable `diagnosisId`/`recommendationId`; no `a12-demo` content entered the real run.

## 34. HTTP Scenario B input

A controlled supported-action HTTP run used the same persisted diagnosis/recommendation IDs with `mode=evaluation` to exercise the approval/task path without changing production fixtures.

## 35. HTTP Scenario B waiting state

Scenario B reached `WAITING_FOR_APPROVAL` with one action, `approval=REQUIRED`, the persisted diagnosis ID, `rec-1`, and two server-owned evidence IDs.

## 36. HTTP Scenario B before approval

Before approval, the matching `actionId` had zero OperationsTask records.

## 37. HTTP Scenario B first approval

The first approval returned `COMPLETED` and created exactly one task: `task-9304abc4-a9ef-449d-92b9-8055546f4611`.

## 38. HTTP Scenario B duplicate approval

The duplicate approval returned `COMPLETED`, reused the same task ID, and left the matching task count at one.

## 39. HTTP Scenario B provenance

The task retained the persisted diagnosis ID, `rec-1`, the action ID, and owner user 1 after reload.

## 40. Cross-user Run denial

User 2 reading the Scenario B run received HTTP 403 `FORBIDDEN`.

## 41. Cross-user approval denial

User 2 approving the Scenario B action received HTTP 403 `FORBIDDEN`; the task was unchanged.

## 42. Cross-user task denial

User 2 reading the created OperationsTask received HTTP 403 `FORBIDDEN`.

## 43. Hard-gate metrics

Stable provenance integrity: **1.0**. Unsupported action prevention: **1.0**. Approval-required compliance: **1.0**. Duplicate task prevention: **1.0**. Cross-user denial: **1.0**. REAL/demo leakage: **0**. Unknown evidence/recommendation acceptance: **0**.

## 44. Phase F evaluation test

`CommercialAgentProductLoopEvaluationTest` covers server snapshot reload, forged client content, stable IDs, approval idempotency, unknown recommendation/evidence rejection, unsupported tools, and owner denial.

## 45. Phase E regression

`CommercialMultisourceRagEvaluationTest` and the related Phase E diagnosis/RAG regression set passed.

## 46. AI baseline regression

`AiEngineBaselineEvaluationTest` passed; the Phase 0 fixture identity and evaluation dataset were not modified.

## 47. Agent Phase 1.1 regression

`AgentV2Phase11EvaluationTest`, `AgentEvidenceSufficiencyTest`, and `EvidenceSufficiencyEvaluatorTest` passed.

## 48. MySQL regression

Diagnosis persistence, Product Loop relationship, approval, Operations, and MySQL E2E tests in the targeted command passed with zero failures and zero errors.

## 49. Full Maven result

With `-DforkCount=0`, the full Maven suite passed **133 tests, 0 failures, 0 errors, 14 skipped**. The default forked run hit the existing ONNX/CLIP memory-allocation fork crash in `LocalVisionServiceTest`; its XML report had zero assertion failures/errors.

## 50. Frontend result

TypeScript build, targeted ESLint, and `vite build --minify=false` passed after transforming 7172 modules.

## 51. Runtime health

The runtime health check reported Spring reachable, MySQL agent persistence READY, MySQL knowledge backend READY, text embedding READY, historical READY_EMPTY, and Live Vision/image embedding UNAVAILABLE.

## 52. Remaining blocker

Knowledge retrieval is not available in the tested runtime path for the selected REAL Agent run, so automatic write creation correctly remains blocked until that dependency is healthy.

## 53. Vision boundary

Live Vision remains a conditional capability; its unavailability does not block nonvisual grounded flows and does not create synthetic visual evidence.

## 54. Files changed

Phase F changes are limited to Agent access/context validation, planner/RAG reuse boundary, runtime sufficiency/action checks, Tool Registry write capability, diagnosis persistence integration, frontend stable-ID handoff, and the Phase F evaluation test.

## 55. Worktree safety

The pre-existing dirty worktree was preserved. No reset, checkout, commit, push, or destructive cleanup was performed.

## 56. Final Phase F status

Core product-loop safety and approval semantics are **GO**. Production rollout is **CONDITIONAL GO** pending healthy knowledge retrieval and normal deployment migration/health validation.

## 57. NEXT

Stop after Phase F. No Phase G implementation or validation was started.

## 58. Phase G boundary

**Phase G — Commercial MVP Full E2E / Pilot Readiness** remains the next phase and is intentionally not entered in this task.
