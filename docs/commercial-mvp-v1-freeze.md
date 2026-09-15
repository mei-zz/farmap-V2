# FarMap Commercial MVP V1 Freeze

Freeze date: 2026-09-07  
Decision: **Commercial MVP V1 FROZEN**  
Pilot status: **PILOT CANDIDATE GO**  
Production rollout: **CONDITIONAL**

## Frozen product contract

The commercial MVP V1 contract is the real, authenticated product loop:

`Farm/Field context → persisted Diagnosis → curated Qwen/GTE evidence → REAL Agent Run → evidence sufficiency → human approval → one Operations task → lifecycle → persisted Outcome/provenance`

The loop is MySQL-persisted, ownership-scoped, approval-gated for writes, and idempotent at approval/completion boundaries. REAL and DEMO are visibly separated; DEMO is read-only and never counts as pilot evidence.

## Verified runtime

- Spring backend: reachable after deployment-style restart.
- MySQL: Agent/Operations/Review/Candidate persistence ready.
- Milvus: Qwen knowledge collection ready and populated.
- Qwen embedding: `qwen3.7-text-embedding-flash`, BAILIAN, ready.
- GTE reranker: `gte-rerank-v2`, ready.
- Migration verifier: no missing tables, columns, or indexes.
- Playwright: real Chromium Desktop plus 390x844 mobile smoke passed.
- Screenshots: `docs/pilot-e2e/screenshots/01-login.png` through `04-operations-outcome.png`.

## Controlled-pilot policy

`LIVE_VISION_REQUIRED_FOR_CORE_PILOT=false`.

Core Pilot acceptance is knowledge/context driven. Vision, CLIP, historical image vectors, and other optional sources must remain explicit `UNAVAILABLE`/`READY_EMPTY` states when not configured. No synthetic image evidence is permitted.

## Admin knowledge boundary

A persisted admin operator is provisioned and authenticated. The existing curated corpus import smoke was run twice and returned `SKIPPED_IDEMPOTENT` both times with stable IDs and an audit event. Normal users remain forbidden from admin knowledge writes.

## Supported pilot surface

Supported: authenticated REAL farm/field context, persisted Diagnosis, curated knowledge evidence, Agent planning, human approval, one Operations task, lifecycle/outcome display, refresh persistence, explicit degraded optional-source states, and admin-curated corpus operations.

Conditional: Live Vision, CLIP/image embedding, optional camera/weather/soil/GIS/satellite adapters, deployment-environment browser matrix, and production observability.

Out of scope: Agent Recovery, RAG V2, automatic historical-corpus promotion, broad AI quality tuning, and Post-MVP Phase 1.2 implementation.

## Production hardening backlog

Production TLS, secret-manager injection/rotation, rate limits, monitoring and alerting, backup/restore, rollback rehearsal, support ownership, and production Live Vision adapters remain required before a general production rollout. These are not being relabeled as Core Pilot blockers.

## Freeze rule

Do not alter the frozen Core Pilot contract or enter a new AI-engine phase under this freeze. The next authorized boundary is:

`Post-MVP AI Engine Phase 1.2 — Tool Failure Handling / Recovery`
