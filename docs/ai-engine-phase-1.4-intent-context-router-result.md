# FarMap AI Engine Phase 1.4 — Intent Router + Context Resolver V1

## Executive Summary

Phase 1.4 adds a server-side Copilot route boundary: natural-language input is classified into a finite intent, stable IDs are resolved against existing owner-scoped services, and a canonical capability is returned. The route layer does not create Operations tasks or bypass the existing Agent approval boundary.

## Intent Taxonomy

The finite taxonomy is `FIELD_STATUS_QUERY`, `DIAGNOSIS_REQUEST`, `RECOMMENDATION_QUERY`, `ACTION_REQUEST`, `OPERATIONS_STATUS_QUERY`, `OUTCOME_QUERY`, `KNOWLEDGE_QUERY`, `NAVIGATION_REQUEST`, `CLARIFICATION_REQUIRED`, and `UNSUPPORTED`.

## Intent Router

`CopilotIntentRouter` is a separate server component from context resolution. It returns only a whitelisted enum, confidence, and an optional safe navigation route.

## Deterministic Router

Chinese rules are evaluated first, with English coverage for the controlled cases. Obvious commands such as diagnosis, recommendation, task status, outcome, navigation, unsupported destructive requests, and prompt-injection text do not require a model call.

## Model-assisted Router

No model call is needed for the Phase 1.4 finite deterministic contract. The V1 boundary leaves ambiguous input as clarification; it does not allow a model to invent IDs, capabilities, or URLs.

## Structured Output

`CopilotRouteResult` returns `intent`, `resolutionStatus`, `resolvedContext`, `capability`, `requiresClarification`, `clarificationQuestion`, `allowedNextAction`, `safeRoute`, `traceId`, and confidence. Capability values are the finite server enum.

## Intent Confidence

Deterministic matches use `0.99`; conflicting or unknown matches use a low confidence and clarification. The configured threshold is `FARMAP_COPILOT_INTENT_MIN_CONFIDENCE`, default `0.70`.

## Clarification

Missing field, diagnosis, recommendation, task, or Outcome context returns clarification rather than a guessed entity. `帮我处理一下` returns `ACTION_REQUEST -> AGENT_START` with `CLARIFY_CONTEXT`; it does not execute.

## Context Resolver

`CopilotContextResolver` is separate from classification and resolves through `FarmFieldService`, `DiagnosisPersistenceService`, `OperationsTaskService`, and `AgentRuntime` ownership-aware reads.

## Context Priority

The resolver applies explicit request IDs first, current page context second, bounded server session context third, and clarification fourth. A page hint is never accepted as business truth without server validation.

## Session Context

`CopilotSessionContextStore` stores only active stable IDs, `lastIntent`, and `updatedAt`. It is bounded to 2,048 sessions with a 30-minute TTL. It stores no transcript, summary, preference, cross-session memory, or evidence.

## Stable IDs

The supported identity fields are `farmId`, `fieldId`, `diagnosisId`, `recommendationId`, `runId`, `actionId`, `taskId`, and `outcomeId`. Names, title similarity, order, and LLM guesses are not identity sources.

## Ownership

Field access uses the existing farm/field owner boundary; Diagnosis uses the owner-scoped repository read; Agent uses `AgentRuntime.require(runId, userId)`; Operations uses actor-aware task and Outcome reads.

## Cross-user

An unresolved owner-scoped ID is denied without revealing whether another user owns it. The deterministic evaluation covers a cross-user Diagnosis ID and records a `FORBIDDEN` route with no execution.

## Referential Resolution

“第一条建议” is resolved only as index-to-ID within the current server-loaded persisted Diagnosis snapshot. The returned context contains the persisted `recommendationId`; no name or title is treated as identity.

## Capability Mapping

The mapping is `FIELD_STATUS_QUERY -> FIELD_CONTEXT_READ`, `DIAGNOSIS_REQUEST -> DIAGNOSIS_CREATE`, `RECOMMENDATION_QUERY -> RECOMMENDATION_READ`, `ACTION_REQUEST -> AGENT_START`, `OPERATIONS_STATUS_QUERY -> OPERATIONS_READ`, `OUTCOME_QUERY -> OUTCOME_READ`, `KNOWLEDGE_QUERY -> KNOWLEDGE_SEARCH`, and `NAVIGATION_REQUEST -> NAVIGATE`.

## ACTION_REQUEST

An action request requires a server-resolved Diagnosis and Recommendation. Missing context produces clarification. A resolved action returns `AGENT_START` and stable provenance; it never directly calls Operations task creation.

## Approval Boundary

The frontend starts the existing real Agent path only after a resolved `AGENT_START` result. Evidence Sufficiency, Agent planning, and human approval remain existing Phase 1.1–1.3 gates. The route endpoint itself is read-only.

## Prompt Injection V1

Prompt-injection and destructive system requests are treated as user data and rejected as `UNSUPPORTED`. There is no browser-side Bailian call, no system prompt exposure, and no model-authorized capability or identity creation.

## Copilot Backend

Added `POST /api/copilot/route`. The endpoint is authenticated by the existing JWT interceptor and returns the route result directly. It is connected to existing business services rather than a new Agent architecture.

## Copilot Frontend

The Drawer now calls the route endpoint and sends only safe page context IDs and the route. It renders clarification, unsupported, forbidden, intent, capability, and approval-boundary messages. It does not send weather, evidence, titles, or client state as authority.

## Multi-turn

The bounded active-ID session supports follow-ups such as Diagnosis → Recommendation and Recommendation → Agent. A persisted Diagnosis page context can continue from “这个地块怎么了？” to “那应该怎么办？” without a new diagnosis identity or RAG rerun.

## Intent Accuracy

The deterministic evaluation contains 50 cases and passed at 1.0 accuracy.

## Intent Macro F1

The controlled finite-intent evaluation passed the target macro F1 of 1.0 across the represented intent classes.

## Clarification Precision

Unknown, conflicting, and context-free requests were classified as clarification or capability-preserving clarification with no guessed business entity. Target: 1.0; evaluation: 1.0.

## Context Resolution Accuracy

The evaluation passed explicit-ID priority, page-context fallback, session fallback, Diagnosis-to-Recommendation continuation, first-recommendation ID mapping, Operations task reads, and Outcome reads.

## Ambiguous Guess Rate

Target is 0. No title similarity, list position, name matching, or free-form model output is used for entity identity. The only permitted index behavior is first-item lookup inside a server-persisted Diagnosis snapshot.

## Cross-user Violation Rate

Target is 0. The cross-user fixture returned `FORBIDDEN` and did not produce an executable capability action.

## Invalid Capability Acceptance

Target is 0. Capabilities are returned only from the finite enum mapping. Unsupported and conflicting requests cannot select arbitrary tools, URLs, or write paths.

## HTTP Routing Smoke

The MockMvc HTTP smoke passed: authenticated actor → `POST /api/copilot/route` → `DIAGNOSIS_REQUEST` → `RESOLVED` → stable `fieldId` → `DIAGNOSIS_CREATE`.

## HTTP Multi-turn Smoke

The deterministic route-service smoke passed the Diagnosis → Recommendation continuation and “安排第一条建议” → `AGENT_START` with `recommendationId` and `START_AGENT_REQUIRES_APPROVAL`. No Operations task was created by routing.

## Browser Copilot Smoke

The Playwright test was extended with Login → Overview/current Field → Copilot → `这个地块有什么问题？` → `DIAGNOSIS_REQUEST`, followed by `那应该怎么办？` recommendation routing. Chrome launch and test setup were reached, then the run stopped safely because `FARMAP_PILOT_USERNAME` was not provided. A local Spring boot run was also blocked before listening because the configured MySQL user `zhgy` had no password; no credentials were guessed or printed.

## Phase1.3 Regression

`AgentV2Phase13EvaluationTest`: 2 tests passed, 0 failures, 0 errors.

## Phase1.2 Regression

`AgentV2Phase12EvaluationTest`: 2 tests passed, 0 failures, 0 errors.

## Phase1.1 Regression

`AgentV2Phase11EvaluationTest`: 1 test passed, 0 failures, 0 errors.

## AI Baseline

`AiEngineBaselineEvaluationTest`: 1 test passed, 0 failures, 0 errors.

## Commercial MVP Regression

`CommercialMultisourceRagEvaluationTest` (6), `CommercialAgentProductLoopEvaluationTest` (3), and `CommercialMvpFullProductLoopEvaluationTest` (1) passed with 0 failures and 0 errors.

## Full Maven

The constrained full suite passed: 141 tests, 0 failures, 0 errors, 14 skipped. `LocalVisionServiceTest` remains excluded per the frozen host-memory constraint; no vision behavior was changed.

## Frontend

Passed `npx tsc -b --pretty false`, targeted ESLint for the Copilot files, and `npx vite build --minify=false`.

## Commercial MVP V1

`FROZEN / NO REGRESSION`

## Deferred list

Phase 1.5 conversation state and short-term memory; Redis; full transcript or summary memory; cross-session memory; model-assisted ambiguous classification; automatic diagnosis execution from a route result; Copilot UI redesign; live browser pilot execution until credentials and a valid local database runtime are supplied.

## Files Changed

Backend: Copilot intent/capability/status models, request/result DTOs, bounded session context store, deterministic router, context resolver, route service, controller, JWT route registration, and Phase 1.4 evaluation. Frontend: Copilot contract/service, minimal Drawer routing integration, and the controlled pilot browser smoke extension. Documentation: this result file. No commit or push was performed.

## Remaining AI Gaps

The route layer is intentionally not a conversational memory system and does not invoke a model for ambiguous language. Live browser verification and live HTTP verification require valid local MySQL credentials plus pilot credentials; those external prerequisites are not available in this run.

## Decision

Core Phase 1.4 Intent Router + Context Resolver V1 is implementation-ready and deterministic/HTTP-test GO. Production browser rollout remains CONDITIONAL GO until the environment provides a valid database runtime and approved pilot credentials. Phase 1.5 is not started.
