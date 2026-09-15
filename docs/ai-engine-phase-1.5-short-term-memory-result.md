# FarMap AI Engine — Phase 1.5 Short-term Memory V1

## 1 Executive Summary

Phase 1.5 implements bounded, owner-scoped Copilot conversation state and short-term working memory. The server is authoritative; React keeps only presentation state. Deterministic evaluation, Phase 1.4 compatibility, full constrained Maven, TypeScript, lint, and production build passed. Live MySQL/browser proof remains conditional because the available local runtime did not provide a usable MySQL password or pilot credentials.

## 2 Frozen Baseline

Commercial MVP V1 and Phase 1.1–1.4 behavior remain frozen. The existing Intent Router, Context Resolver, Agent Runtime, Diagnosis persistence, Operations lifecycle, HITL approval, evidence gates, and stable business IDs remain the execution authorities.

## 3 Phase 1.5 Scope

Implemented: `CopilotConversation`, bounded `CopilotConversationTurn`, server-side working memory, deterministic summary, recent-turn window, ownership, refresh restoration, new-conversation isolation, pending clarification, invalidation, CAS versioning, and turn idempotency. No long-term profile, vector memory, Redis, reflection, multi-agent, RAG rewrite, or Copilot redesign was added.

## 4 Existing Phase 1.4 Context

The Phase 1.4 `/api/copilot/route` boundary remains compatible. Requests without a conversation continue through the old constructor path in isolated Phase 1.4 tests; production requests automatically create or continue a server conversation.

## 5 Conversation Domain

Conversation IDs use `conversation-UUID`. The conversation stores lifecycle status, active stable IDs, last intent, deterministic summary, summary version, turn count, version, clarification state, and timestamps.

## 6 Conversation Ownership

Every load, recent-turn query, duplicate check, and update is scoped by `conversationId AND userId`. A different user cannot open or continue another user's conversation.

## 7 Conversation Turn Model

Turns store `turnId`, conversation ID, role (`USER`, `ASSISTANT`, `SYSTEM_EVENT`), bounded message content, intent, trace ID, client message ID, result metadata, and creation time. User and assistant turns are persisted together for each routed request.

## 8 Working Memory

Working memory contains only validated stable IDs, recent intent, deterministic summary, and bounded pending clarification. It never stores chain-of-thought, scratchpad, hidden reasoning, raw tool payloads, credentials, or a model prompt.

## 9 Stable-ID Context

The active graph is Farm → Field → Diagnosis → Recommendation → AgentRun → Task → Outcome. IDs are updated only from server-resolved contexts or server-confirmed page/entity references.

## 10 Pending Clarification

Clarification state is typed and bounded to three subsequent turns. “帮我安排一下” stores a recommendation clarification after validating the current diagnosis; “第一条” continues that clarification and resolves the first recommendation instead of reclassifying to an unrelated request.

## 11 Context Dependency / Invalidation

An explicit field change invalidates stale downstream memory unless a new explicit/page downstream ID is supplied and server validation confirms it. The dependency graph is enforced by the Context Resolver before memory is updated.

## 12 Context Resolution Priority

Priority is explicit request IDs, then page IDs, then validated conversation active IDs, then pending clarification, then a new clarification. Page data remains a hint and never overrides an explicit ID.

## 13 Recent Turn Window

The configured recent-turn limit is `FARMAP_COPILOT_MEMORY_MAX_RECENT_TURNS=12`, bounded to at most 16 by the assembler. Refresh restores recent server turns only; it does not replay an unbounded transcript.

## 14 Context Budget

The configured message-context budget is `FARMAP_COPILOT_MEMORY_MAX_CONTEXT_CHARS=12000`. Individual recent messages are bounded and the assembler stops at the total character budget.

## 15 Conversation Summary

V1 uses a deterministic structured JSON summary containing topic, confirmed-fact marker, user goal, resolved entity IDs, last allowed outcome, and pending question. No model-generated summary or hidden reasoning is used.

## 16 Memory Persistence

Production persistence uses MySQL tables `copilot_conversations` and `copilot_turns`, created append-only with `CREATE TABLE IF NOT EXISTS`. The deterministic evaluation uses an in-memory repository implementation without changing the production authority.

## 17 Refresh / Reload

The frontend retains the conversation ID and context version in `sessionStorage`, loads the server conversation on drawer open, and hydrates only recent user/assistant turns. The backend GET boundary is `GET /api/copilot/conversations/{conversationId}`.

## 18 New Conversation Isolation

Without a conversation ID, the route creates a new `conversation-UUID`; no previous conversation history is loaded. The frontend exposes a minimal “新建 Copilot 对话” action that clears the presentation cache and starts a fresh server conversation.

## 19 Concurrency / Versioning

Each conversation carries a monotonically increasing context version. Updates use a version predicate; a stale version returns `CONVERSATION_CONFLICT` with HTTP 409 rather than silently overwriting another turn.

## 20 Turn Idempotency

Requests accept `clientMessageId`. A duplicate within the same owner-scoped conversation replays the persisted structured route result and does not create a second user/assistant turn.

## 21 Prompt Injection Memory Boundary

Injection-like content remains user content and is rejected by the existing deterministic router. It cannot become a system instruction or alter capability, whitelist, evidence, or approval policy. Known credential-shaped message fragments are redacted before turn persistence.

## 22 HITL / Approval Boundary

Short-term memory can retain a validated run context, but natural-language confirmation is not an approval command. `ACTION_REQUEST` continues to return `START_AGENT_REQUIRES_APPROVAL`; existing explicit approval APIs remain the only approval authority.

## 23 Intent Router Integration

The router remains finite and deterministic. Context-dependent follow-ups now include task “现在怎么样？” and outcome “结果呢”, while pending “第一条” can continue a recommendation clarification.

## 24 Context Resolver Integration

The resolver receives validated conversation active IDs after explicit/page precedence is applied. It validates field, diagnosis, recommendation, task, outcome, run, and ownership through the existing business services before state writes.

## 25 Copilot Backend

`POST /api/copilot/route` remains the compatibility route and now returns `conversationId`, `turnId`, `memoryStatus`, and `contextVersion`. Minimal create and GET conversation endpoints support refresh and new-conversation lifecycle.

## 26 Copilot Frontend

The frontend sends conversation ID, client message ID, page stable IDs, and context version. It loads recent server turns, keeps React/Zustand as presentation cache, supports refresh restoration, and adds only a new-conversation action.

## 27 Multi-turn Evaluation

`AgentV2Phase15ShortTermMemoryEvaluationTest` covers 20 deterministic M01–M20 cases: continuity, task/outcome follow-up, refresh, isolation, switch invalidation, priority, clarification continuation/expiry, idempotency, CAS, bounded context, secrets, injection, stale IDs, approval, REAL/DEMO boundary, cross-user ownership, and MockMvc HTTP lifecycle.

## 28 Memory Metrics

All Phase 1.5 evaluation hard targets passed: Context Continuity Accuracy 1.0; Reference Resolution Accuracy 1.0; Context Invalidation Accuracy 1.0; Conversation Isolation Accuracy 1.0; Cross-user Memory Violation Rate 0; Duplicate Turn Rate 0; Context Window Compliance 1.0; Clarification Continuation Accuracy 1.0; Approval Bypass Rate 0; REAL Demo Leakage 0.

## 29 HTTP Multi-turn Smoke

Controlled MockMvc HTTP proof passed: create conversation → route a diagnosis request → persist two turns → GET the same conversation. The required live MySQL HTTP gate is `LIVE_RUNTIME_CONDITIONAL` because local backend startup was blocked by the existing MySQL `zhgy` account having no usable password.

## 30 Browser Multi-turn Smoke

The Playwright pilot now covers Copilot Turn 1, Turn 2, conversation ID capture, GET verification, reload, restored recent assistant turn, and Turn 3 action boundary. Actual browser execution is `LIVE_RUNTIME_CONDITIONAL` because `FARMAP_PILOT_USERNAME` and `FARMAP_PILOT_PASSWORD` were not available.

## 31 Browser Refresh

The browser contract asserts that the same `conversationId` survives reload, the server returns at least the prior turns, and the post-refresh action remains on the explicit Agent/approval boundary rather than directly creating an Operations task.

## 32 Cross-user Tests

The deterministic evaluation verifies that user 43 cannot continue user 42's conversation. The repository and GET/route paths enforce owner scope in SQL and in the isolated repository.

## 33 Phase 1.4 Regression

Passed: `AgentV2Phase14IntentRouterEvaluationTest`, 3 tests, 0 failures, 0 errors.

## 34 Phase 1.3 Regression

Passed: `AgentV2Phase13EvaluationTest`, 2 tests, 0 failures, 0 errors.

## 35 Phase 1.2 Regression

Passed: `AgentV2Phase12EvaluationTest`, 2 tests, 0 failures, 0 errors.

## 36 Phase 1.1 Regression

Passed: `AgentV2Phase11EvaluationTest`, 1 test, 0 failures, 0 errors.

## 37 AI Baseline

Passed: `AiEngineBaselineEvaluationTest`, 1 test, 0 failures, 0 errors.

## 38 Commercial MVP Regression

Commercial multisource RAG, commercial Agent product loop, and commercial full product loop evaluation coverage passed: 10 tests, 0 failures, 0 errors. Existing REAL/DEMO boundaries remain unchanged.

## 39 Full Maven

Passed with `-DforkCount=0 -Dfarmap.agent.store=unique -Dtest=*,!LocalVisionServiceTest`: 142 tests, 0 failures, 0 errors, 14 skipped. The existing `LocalVisionServiceTest` exclusion remains required by the frozen host-memory constraint; the known VisualEvidenceRetriever warning is non-fatal.

## 40 Frontend

Passed: `npx tsc -b --pretty false`; targeted ESLint for Copilot, drawer, store, and pilot E2E; `npx vite build --minify=false` with 7173 modules. Live browser remains conditional as stated above.

## 41 Deferred Long-term Memory / Cache / Reflection

Deferred exactly to later approval: cross-session user profile/preferences, long-term facts, vectorized conversation memory/Milvus, Redis/cache, reflection/self-learning, multi-agent memory, and broader Copilot redesign.

## 42 Files Changed

Backend: `CopilotRouteRequest`, `CopilotRouteResult`, `CopilotRouteService`, `CopilotContextResolver`, `CopilotIntentRouter`, `CopilotController`; added `CopilotConversationException`, `CopilotConversationRepository`, `JdbcCopilotConversationRepository`, `InMemoryCopilotConversationRepository`, `CopilotConversationService`, `CopilotConversationSnapshot`, `CopilotConversationTurn`, `CopilotConversationView`, `CopilotPendingClarification`, and `CopilotContextAssembler`; added `AgentV2Phase15ShortTermMemoryEvaluationTest`. Frontend: Copilot contract/service/store/drawer and the controlled pilot E2E. Result evidence is this document plus `target/evaluation-results/agent-v2-phase-1.5.{json,md}`.

## 43 Phase 1.5 Decision

**Core Short-term Conversation Memory V1 = GO. Live MySQL + Browser Pilot = CONDITIONAL GO.** Local deterministic proof and regression gates are green; production/live rollout still requires the existing supported DB provisioning and pilot credentials. Commercial MVP V1 remains **FROZEN / NO REGRESSION**.

