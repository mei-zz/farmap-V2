# FarMap V2 Milestone 4 — Product Loop

## A. Git

`feature/agent-runtime` was checked, built and tested, merged through PR #2 into `main`, tagged `agent-core-v1` (`FarMap context-aware agent runtime baseline`), and `feature/product-loop` was created from the merged commit.

## B. Context Integration

`AIContext` remains the single global context for farm, field, crop, location, weather, phenology, images, device, diagnosis and page. Overview, Farm Map, Diagnosis, Camera/Monitor and Agent update it. `PageContextAdapter` is the only conversion boundary to `AgentContext`.

## C. Copilot

Short questions stay in the quick assistant. Rule-based escalation covers deep/comprehensive analysis, plans, cause finding, multi-source comparisons and long requests; escalation creates a real Agent run when `VITE_AGENT_MODE=real` and links to `/analysis/{runId}`.

## D. Diagnosis → Agent

A-12 abnormal insight sets the current field and opens its diagnosis. Diagnosis deep analysis submits the global context (including diagnosis and selected images) to Agent Runtime and navigates to the persisted run route. `/analysis/a12-demo` remains available for demo mode.

## E. Agent Tools and Trace

Existing Field, Weather, Camera, GIS, RAG, Knowledge and Historical tools are reused. Trace events now include tool start, evidence additions, step completion and action execution. Tool calls carry start/end and duration metadata; model chain-of-thought is never returned.

## F–G. Approval and Operations

Writes remain approval-gated. `TaskTool` delegates to the single `OperationsTaskService`, which uses the application datasource when available and a test/demo fallback otherwise. Tasks carry source, Agent run, action, field, diagnosis, evidence, approver and timestamp metadata. Operations lists tasks and links back to the Agent run.

## H. Expert Review

`ExpertReviewService` provides persisted `PENDING`, `IN_REVIEW`, `CONFIRMED` and `CORRECTED` states, reviewer/timestamp, AI and expert diagnosis, evidence, recommendations and before/after payloads. The existing Expert page shows these states alongside the legacy expert APIs.

## I. Historical Cases

`HistoricalCaseIngestionService` accepts only expert-confirmed cases with images, expert diagnosis and outcome, embeds with the existing Local CLIP provider, validates 512 dimensions and is idempotent. `scripts/bootstrap-historical-case-collection.py` is dry-run by default and safely plans/creates `farmap_image_vectors_new` with 512-d L2 IVF_FLAT (`nlist=128`), never dropping an existing collection. Current verified state is collection absent and zero real historical candidates.

## J–L. E2E Checks

`scripts/validate-product-loop.py` passed the A-12 Agent → approval → Operations backlink → Expert CONFIRMED → historical dry-run loop. The Expert CORRECTED transition and before/after payload were also exercised. `scripts/validate-agent-runtime.py` passed the pre-approval write boundary check. Copilot and page-context paths are covered by the frontend build and adapter/runtime tests.

## M. Persistence

The local E2E used file persistence. With `FARMAP_AGENT_PERSISTENCE=mysql`, the supplied `root` account was verified against the local `zhgy` database: `agent_runs` was created, a run was written, updated to `COMPLETED`, and read back through a new repository instance. The verification row was removed afterward. The named MySQL integration test covers the same create/refresh/restart path when MySQL mode is enabled.

## N. Tests

Frontend `pnpm build` passed. Backend Maven tests passed: 39 tests, 0 failures, 2 skipped in the default file mode; the MySQL persistence test also passed with the supplied credentials. Named Milestone 4 tests are present, including PageContextAdapter, Copilot escalation, diagnosis link, Operations integration, approval audit, expert review, historical ingestion and MySQL persistence.

## O. Remaining Demo Data

The A-12 visual workspace and historical cards remain explicitly demo-labelled. No B-07 historical vector or other fabricated case was inserted.

## P. Remaining Limitations

The target Milvus collection is still absent in this workspace, and the Python bootstrap cannot execute because `pymilvus` is unavailable and package installation is blocked by the local package mirror. No historical vector or fabricated B-07 case was inserted. No deployment was performed.

**FARMAP CORE PRODUCT LOOP NOT READY**
