# FarMap Commercial MVP Pilot Readiness Checklist

Status date: 2026-09-07  
Overall: **PILOT CANDIDATE GO; Commercial MVP V1 FROZEN; production rollout CONDITIONAL**

## Core product loop

- [x] Login success and invalid-login HTTP 401 behavior verified.
- [x] Authenticated Farm retrieval verified.
- [x] Field creation and ownership scoping verified.
- [x] FieldContext returns explicit READY/empty/unavailable source states.
- [x] Fresh REAL Diagnosis persisted through HTTP.
- [x] Recommendation IDs come from persisted Diagnosis.
- [x] Qwen vector retrieval is reachable and evidence is traceable.
- [x] GTE reranker health and retrieval metadata are READY.
- [x] Agent run persists to MySQL.
- [x] Evidence sufficiency reaches SUFFICIENT with `allowsWrite=true` for valid evidence.
- [x] ProposedAction requires approval.
- [x] Approval creates one Operations task.
- [x] Operations lifecycle reaches OPEN → ASSIGNED → IN_PROGRESS → COMPLETED.
- [x] Outcome is persisted and returned.
- [x] Outcome exposes Diagnosis/recommendation/Agent/field/evidence provenance.
- [x] Expert Review can be linked to the completed loop.
- [x] Historical Candidate can be created as ELIGIBLE without automatic corpus promotion.
- [x] Controlled pilot operator is provisioned as a persisted admin and can authenticate.
- [x] Positive admin-curated knowledge import smoke is idempotent and audited (`SKIPPED_IDEMPOTENT` twice).

## Security and idempotency

- [x] Invalid login fails with HTTP 401.
- [x] Cross-user Field access fails with HTTP 403.
- [x] Cross-user Agent access fails with HTTP 403.
- [x] Cross-user Operations task access fails with HTTP 403.
- [x] Cross-user Review/Candidate access fails with HTTP 403.
- [x] Forged recommendation fails closed with HTTP 422 and no run ID.
- [x] Duplicate approval does not create a second task for the action.
- [x] Duplicate completion returns the same Outcome.
- [x] Guest admin knowledge write fails with HTTP 403.

## Runtime consistency

- [x] Spring health is reachable after startup.
- [x] Agent health is READY and persistence is MySQL.
- [x] Knowledge backend is READY.
- [x] Qwen embedding provider is READY.
- [x] GTE reranker is READY.
- [x] Milvus Qwen collection is READY and populated.
- [x] Operations, Review, and Candidate stores are READY.
- [x] Migration readiness is READY with no missing required schema objects.
- [x] Runtime startup script aligns provider, persistence, and local endpoints.
- [x] Preflight timeout covers normal Spring initialization.
- [x] Persisted evidence provenance is distinguished from current dependency availability.

## Degraded and optional-source behavior

- [x] Missing optional camera/weather/soil/GIS/satellite inputs degrade explicitly.
- [x] Knowledge remains usable when optional sources are absent.
- [x] Vision/local image-plane unavailability is surfaced as conditional, not hidden.
- [x] Historical image collection remains explicitly empty/optional.
- [x] Live Vision policy is explicit: `LIVE_VISION_REQUIRED_FOR_CORE_PILOT=false`.
- [ ] Add and verify production adapters for any optional source required by pilot users.

## Admin and operations

- [x] Admin knowledge health endpoint returns HTTP 200 and READY ingestion state.
- [x] Guest knowledge-source write is denied.
- [x] Provision a valid persisted admin operator fixture.
- [x] Execute positive admin-curated knowledge ingest smoke with the provisioned operator.
- [x] Confirm current operator audit event and stable ingestion/document/source IDs.
- [ ] Confirm operator rotation, audit retention, and emergency revoke procedure.
- [ ] Confirm production backup/restore and rollback runbooks.

## Frontend and demo boundary

- [x] REAL and DEMO modes are visibly separated.
- [x] DEMO remains read-only and is not used as E2E evidence.
- [x] Operations UI retains empty/error states.
- [x] Completed real tasks fetch persisted Outcome provenance.
- [x] Changed Operations view passes targeted ESLint.
- [x] Frontend production build passes.
- [x] Run real Playwright Chromium browser smoke with four screenshots against the local deployment-style runtime.
- [x] Verify Desktop Chrome flow and 390x844 mobile smoke with no catastrophic horizontal overflow.
- [x] Browser console/page errors and same-origin core 5xx gate are clean.

## Test matrix

- [x] Full-loop evaluation test added and passing.
- [x] Targeted Agent/Operations/MySQL regression tests pass: 16 tests, 0 failures, 0 errors, 3 skips.
- [x] Retained full Maven baseline reports: 134 tests, 0 failures, 0 errors, 14 skips; final constrained run excluding the heavy optional CLIP test reports 133 tests, 0 failures, 0 errors, 13 skips.
- [x] Phase E retrieval regression retained.
- [x] Phase F Agent regression retained.
- [x] Frontend TypeScript compilation passes; a prior Vite production build passed and the final browser gate passes. Later Vite reruns were host-memory limited during esbuild rendering.
- [x] Browser E2E passes: 1 Playwright test, real APIs, refreshes, approval, Operations outcome, mobile smoke.
- [x] Deployment-style restart and runtime preflight pass; optional Vision/CLIP remain explicitly degraded.

## Production rollout conditions

- [x] Provision and validate admin operator access.
- [x] Run positive admin knowledge ingest and verify audit trail.
- [x] Run browser E2E against the deployment-style local URL.
- [ ] Confirm TLS, secret injection, rate limits, and monitoring.
- [ ] Confirm MySQL/Milvus backup, restore, and rollback.
- [ ] Confirm pilot support owner and incident escalation.
- [ ] Make the explicit Live Vision rollout decision.

## Final gate

**Pilot recommendation:** **PILOT CANDIDATE GO** for the Core Agent Product Loop.  
**Commercial MVP V1:** **FROZEN** at the verified REAL RAG → Agent approval → Operations → Outcome loop.  
**Production recommendation:** conditional approval only; TLS, secret-manager injection, monitoring, rate limits, backup/restore, rollback, support ownership, and Live Vision production adapters remain hardening/backlog items rather than Core Pilot blockers.

## Browser evidence

- Framework: `@playwright/test` with Chromium Desktop Chrome and a 390x844 mobile context.
- Browser cache: `E:\FarMapTools\playwright-browsers`; executable is the installed Chromium full browser, not a mock or browserless substitute.
- Evidence: `docs/pilot-e2e/screenshots/01-login.png`, `02-diagnosis.png`, `03-agent-approval.png`, `04-operations-outcome.png`.
- Browser spec: `farmap-frontend-dev/e2e/commercial-pilot.spec.ts`; API helpers only create field/Diagnosis and complete the lifecycle where the current UI is read-only. UI assertions cover persisted login, Diagnosis, REAL knowledge evidence, Agent approval, Operations visibility, refresh, and outcome.
