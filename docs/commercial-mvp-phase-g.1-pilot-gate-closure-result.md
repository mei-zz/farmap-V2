# FarMap Commercial MVP — Phase G.1 Pilot Gate Closure Result

Date: 2026-09-07  
Branch: `feature/commercial-product`  
Decision boundary: close the Phase G controlled-pilot blockers only. No Agent Recovery, RAG V2, or post-MVP Phase 1.2 implementation is included.

## 1. Executive decision

**PILOT CANDIDATE GO.** The Core Agent Product Loop is suitable for a controlled pilot on the verified local deployment-style runtime. **Commercial MVP V1 is FROZEN.** Production rollout remains conditional on the hardening items listed below.

## 2. Scope closed

This gate closed the missing persisted admin operator, positive/idempotent admin knowledge smoke, browser evidence, deployment-style restart/preflight, optional Live Vision policy, pilot error states, and secrets review. It did not expand product scope or redesign the AI engine.

## 3. Admin operator

A unique local pilot operator was registered through the supported user flow, then narrowly promoted in the persisted MySQL user row to `role=admin`. The operator remained persisted across the runtime restart and authenticated successfully. Credentials are intentionally omitted from this artifact.

## 4. Admin login

Admin login through the real `/user/login` endpoint passed and returned the persisted `admin` role. Admin knowledge health returned HTTP 200 with `status=READY`.

## 5. Positive admin knowledge smoke

The current admin operator imported the existing curated corpus manifest for `知识库/脆李时期/采后期.docx` twice. Both calls returned `SKIPPED_IDEMPOTENT`; ingestion, document, and source IDs were stable across both calls. No duplicate version or vector corpus was created.

## 6. Admin audit

The second-path audit was recorded in MySQL as `KNOWLEDGE_INGESTION_SKIPPED_IDEMPOTENT`, with the current admin actor (`actor_id=42`) and the existing ingestion entity. This proves the current operator, not only a historical fixture, can execute and audit the idempotent path.

## 7. Normal-user boundary

The normal pilot user’s admin knowledge write attempt returned `FORBIDDEN`. The positive admin path and the denial path were both verified against the live backend.

## 8. Browser framework

`@playwright/test` is installed in `farmap-frontend-dev`. The browser spec is `e2e/commercial-pilot.spec.ts`, configured for one serial worker, real HTTP APIs, no mocked backend responses, and JSON result output.

## 9. Browser install

Chromium was installed at `E:\FarMapTools\playwright-browsers`; the executed full-browser binary was `chromium-1243/chrome-win64/chrome.exe`. The headless-shell download was not used as a substitute.

## 10. Desktop browser flow

The final run passed one Chromium Desktop test. It covered invalid login, valid login, Overview, Farm Map, creation of a fresh field, fresh REAL Diagnosis, persisted knowledge evidence, Agent analysis, human approval, Operations visibility, task lifecycle, persisted Outcome, refreshes, and console/page-error/5xx gates.

## 11. Mobile smoke

The same test created a 390x844 mobile context, logged in through the real UI, asserted Overview, and verified no catastrophic horizontal overflow. The mobile Sider/header layout and optional monitor-token error state were repaired only where they directly blocked this gate.

## 12. Browser Login

Invalid credentials produced the expected user-facing error. Valid credentials reached `/overview`, with no `DEMO` marker.

## 13. Browser Field

The test created a fresh field under the authenticated pilot farm through the real API helper because the current UI does not expose the required field-write form. FieldContext returned `READY` with `sourceMode=MYSQL`; this field was the ownership anchor for the remainder of the run.

## 14. Browser Diagnosis

The test submitted a fresh REAL Diagnosis with the knowledge modality enabled and no synthetic image evidence. The Diagnosis persisted and rendered in the browser with a real Diagnosis title and `REAL RAG`/knowledge state.

## 15. Browser Knowledge Evidence

The Diagnosis page rendered persisted curated knowledge evidence with source/document/version/chunk identity. The final browser screenshot captured the evidence state and contained no `DEMO` marker.

## 16. Browser Agent

The browser started a fresh REAL Agent Run from the persisted Diagnosis/recommendation relationship. The UI rendered `REAL AGENT RUN`, the evidence-backed proposal, and the waiting-for-human-confirmation state.

## 17. Browser Approval

The browser clicked `批准并创建任务`. The approval response was reconciled against the persisted Agent Run before rendering the execution result, so the UI cannot regress to a stale waiting snapshot at the approval boundary.

## 18. Browser Operations

The approved run returned one persisted Operations task. The Operations page rendered `REAL OPERATIONS`, the task remained visible after refresh, and the test used real assign/start/complete endpoints because the current Operations UI is intentionally read-oriented for this pilot.

## 19. Browser Outcome

The browser completed the task with a pilot execution summary and evidence, fetched the persisted Outcome, refreshed Operations, and asserted `已完成` plus the persisted completion text. The final Operations screenshot captures this state.

## 20. Browser refresh and REAL/DEMO separation

Diagnosis, Agent, and Operations were refreshed during the final run. The final assertions found no `DEMO` marker in the REAL flow. DEMO remains a read-only presentation boundary and is not used as pilot evidence.

## 21. Browser console gate

The final run passed with no collected page errors, no relevant console errors, and no same-origin core 5xx responses for the tested flow. The optional monitor-token endpoint now degrades safely when its local response has `data=null`, rather than producing an uncaught browser error.

## 22. Screenshots

The final run produced:

- `docs/pilot-e2e/screenshots/01-login.png`
- `docs/pilot-e2e/screenshots/02-diagnosis.png`
- `docs/pilot-e2e/screenshots/03-agent-approval.png`
- `docs/pilot-e2e/screenshots/04-operations-outcome.png`

## 23. Deployment-style restart

The old backend process was stopped and relaunched using `scripts/run-backend-local.ps1 -GenerationMode template -JavaTempDir D:\farmap-runtime-temp` with the local commercial provider/persistence defaults. Spring started on port 8080 after the restart. Mongo connection refusal remained optional and did not block the core path.

## 24. Pilot preflight

`scripts/check-commercial-mvp-runtime.ps1` reached all three services: Vision endpoint `REACHABLE` but degraded/unavailable vision, Local AI `REACHABLE` with BGE ready and CLIP unavailable, and Spring `REACHABLE`. The Spring payload reported migration readiness with no missing tables, columns, or indexes.

## 25. Runtime health

After restart: backend `REAL`; Knowledge `READY`; Qwen/Bailian embedding `READY`; GTE reranker `READY`; Qwen knowledge collection `READY` with 507 rows; Agent `READY` with MySQL persistence; Operations, Review, Candidate, and migration stores `READY`; historical image vector surface `READY_EMPTY` with zero rows.

## 26. Migration readiness

The production schema verifier reported `missingTables=[]`, `missingColumns=[]`, and `missingIndexes=[]` across the verified migration range. No migration or schema change was added in G.1.

## 27. Qwen knowledge

Qwen retrieval remains the production pilot knowledge path: model `qwen3.7-text-embedding-flash`, dimension 1024, BAILIAN provider, persisted Qwen collection populated, and evidence visible in REAL Diagnosis and Agent views.

## 28. GTE reranker

The configured GTE reranker reports `READY` with model `gte-rerank-v2`. The final runtime health and browser Diagnosis evidence confirm that retrieval metadata and evidence remain available after restart.

## 29. Live Vision policy

Live Vision is optional for the Core Pilot. The local Vision/CLIP plane is explicitly degraded/unavailable and no synthetic image result was inserted. The UI and runtime preserve explicit unavailable/empty states.

## 30. Required pilot flag

`LIVE_VISION_REQUIRED_FOR_CORE_PILOT=false` is the explicit policy. The Core Pilot is knowledge/context driven and does not claim production Live Vision readiness.

## 31. Error-state closure

The pilot-visible auth, optional-source, Agent approval, Operations empty/error, REAL/DEMO, and monitor-token degradation states are explicit. A missing optional token response no longer becomes an uncaught page error.

## 32. Secrets review

No password, bearer token, API key, or secret value was written to the new G.1 documents or tracked browser artifacts. Raw token/password logging was removed from the touched authentication controller paths; logs retain only presence state or non-secret identifiers. Temporary test credentials remained process-local and are omitted here.

## 33. Cross-user regression

The retained Phase G regression matrix passed cross-user denial for Field, Agent, Operations, Review, and Candidate access, plus fail-closed forged recommendation behavior. The new admin smoke did not weaken the normal-user `FORBIDDEN` boundary.

## 34. Phase G regression

The persisted Diagnosis → Agent → approval → Operations → Outcome → Review/Candidate relationships remain intact. Approval and completion idempotency remain enforced.

## 35. Phase F regression

The REAL Agent path remains MySQL-persisted, evidence-sufficient, approval-gated, and linked to the persisted recommendation. The final browser run exercised the product loop through the real frontend.

## 36. Phase E regression

Core multisource RAG remains GO for the pilot. Knowledge evidence is usable when optional weather, camera, soil, GIS, satellite, and image planes are absent; Live Vision remains conditional by explicit policy.

## 37. Full Maven

The retained baseline full Maven result is 134 tests, 0 failures, 0 errors, and 14 skips. The final constrained run excluding only the heavy optional `LocalVisionServiceTest` completed 133 tests, 0 failures, 0 errors, and 13 skips; the G.1 targeted set completed 23 tests, 0 failures, 0 errors, and 1 skip. A literal all-test rerun reached the CLIP model test and stopped on host/JVM memory while loading the optional ONNX model, with no assertion failures in the 74 tests executed before that resource stop.

## 38. Frontend verification

The final browser test passed and `tsc -b` passed after the final changes. A successful Vite production build was obtained before the final optional-source patch; subsequent full and unminified Vite attempts reached the transform/render path but hit Windows/Node host-memory limits. No TypeScript error was reported. Targeted ESLint output retains pre-existing errors in legacy Map/Monitor files; the G.1 changes themselves are narrow and browser-verified.

## 39. Checklist update

The existing `docs/commercial-mvp-pilot-readiness-checklist.md` was updated in place. No second readiness checklist was created. It now records the admin, browser, restart/preflight, Live Vision policy, screenshot, and final gate results while keeping production hardening unchecked.

## 40. Freeze boundary

Frozen scope is the verified Core Agent Product Loop: authenticated REAL context → persisted Diagnosis → curated Qwen/GTE evidence → evidence-sufficient Agent plan → human approval → one Operations task → lifecycle/outcome provenance. DEMO is read-only; Live Vision is not part of the Core Pilot contract.

## 41. Production hardening remaining

TLS, production secret-manager injection and rotation, rate limits, monitoring/alerting, backup/restore, rollback rehearsal, support ownership, deployment-environment browser smoke, and production Live Vision adapters remain hardening/backlog items. They are not represented as complete and are not blockers for the controlled Core Pilot decision.

## 42. Final gate

**PILOT CANDIDATE GO. Commercial MVP V1 FROZEN.** Stop at this Phase G.1 boundary.

## 43. NEXT

`Post-MVP AI Engine Phase 1.2 — Tool Failure Handling / Recovery`
