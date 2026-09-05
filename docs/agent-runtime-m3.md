# FarMap Agent Runtime · Milestone 3

## A. Scope

`feature/agent-runtime` is based on `main@381713a`, which contains the merged real multimodal RAG baseline (`rag-real-v1`). This milestone adds the bounded Agent Core Framework and connects the existing Agent Workspace to the runtime while preserving its commercial layout.

## B. Contract

`POST /api/agent/runs` accepts `{ goal, context, mode }` and returns `{ runId, status: "planning" }`. `GET /api/agent/runs/{runId}` returns the typed plan, steps, tool calls, evidence, output, actions, approval state, metadata and trace events. SSE replay is available at `/events`; polling remains supported. Approval, rejection, cancellation and an explicit `/execute` endpoint are provided.

## C. Context

`AgentContext` carries farm, field, crop, variety, phenology, location, camera, image URLs, weather, diagnosis and source modes. The frontend adapter maps the existing `AIContext` directly; no DOM scraping is used.

## D. Runtime and safety

The state machine is bounded by `maxSteps`, `maxToolCalls` and `timeoutMs`. Read tools execute automatically. `TaskTool` is a write boundary and refuses execution unless `approvalGranted=true`; approval is recorded before the tool call. Cancellation is terminal for the run. `TraceRecorder` records transition and tool events without chain-of-thought.

## E. Tools

The first registry contains Field Context, Weather, Camera, GIS, Multimodal RAG, Historical Case, Knowledge and Task tools. `MultimodalRagTool` delegates to the existing `DiagnosisRagService`; it does not implement a second retrieval or fusion path. Empty historical vectors return `UNAVAILABLE/HISTORICAL_VECTOR_DATA_EMPTY` and the runtime continues.

## F. Planner and models

`AgentPlanner` emits `agent-plan-v1` typed steps and uses `ModelGateway` with `qwen3.6-plus` as the normal planning model. If that gateway is unavailable, the same typed deterministic schema plan is retained; no free-text parser or hidden reasoning is introduced. The 235B model is not used for ordinary planning. Set `FARMAP_AGENT_PLANNER_MODEL_ENABLED=false` for offline unit/smoke runs.

## G. Persistence boundary

`AgentRunRepository` is the persistence boundary and the current default is a concurrency-safe repository for local development/tests. Replacing this bean with the existing MySQL stack does not change the API or runtime state machine. No second Operations task service was introduced; the write tool exposes the existing Operations boundary and only creates after approval.

## H. Frontend

`src/features/agent/{contract,service,adapter}.ts` provides typed real-mode calls, polling and context conversion. `VITE_AGENT_MODE=mock|real` is preserved. Real failures render “Agent Runtime 暂不可用” and do not silently switch to the mock timer. The existing workspace binds run status, steps, tools, evidence, output, approval and traces without a layout redesign.

## I. Validation

- Backend: bundled Maven `test` — 31 tests, 0 failures, 2 optional skips.
- Frontend: `pnpm build` — passed.
- Local AI: BGE 512d and CLIP 512d health/embedding smoke — passed.
- Milvus: endpoint verified; `farmap_image_vectors_new` absent/empty and reported as `HISTORICAL_VECTOR_DATA_EMPTY`.
- Agent smoke: `python scripts/validate-agent-runtime.py` checks the no-write-before-approval invariant and post-approval task creation.

## J. Decision

AGENT CORE FRAMEWORK READY
