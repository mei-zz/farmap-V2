# FarMap AI Engine Phase 1.6 — Structured Long-term Memory V1 Result

## 1 Executive Summary

Phase 1.6 implements bounded, explicit, structured long-term memory for Copilot. The core memory model is GO. Live MySQL and browser validation remain CONDITIONAL because the local pilot environment does not currently provide usable database credentials or `FARMAP_PILOT_USERNAME`.

## 2 Frozen Baseline

Commercial MVP V1 and Phases 1.1–1.5 remain frozen. Phase 1.5 short-term conversation memory remains bounded, owner-scoped, CAS-protected, and separate from long-term memory.

## 3 Phase 1.6 Scope

In scope: typed preference/constraint records, explicit write intent, deterministic extraction, ownership, provenance, MySQL persistence, lifecycle, supersession, revocation, bounded retrieval, Copilot integration, minimal UI/API, and hard evaluation cases. Vector memory, cache, reflection, and multi-agent work are out of scope.

## 4 Short-term vs Long-term Memory

Short-term memory is conversation-local and expires with the conversation boundary. Long-term memory is a durable, explicit, structured record identified by `memory-UUID`; it is not a transcript and is not automatically inherited from ordinary conversation text.

## 5 Memory Domain

The domain contains only user preferences and operating constraints that can safely personalize Copilot responses. It cannot represent evidence, diagnosis state, task state, authorization, approval, or an operation.

## 6 Memory Types

Supported types are `USER_PREFERENCE`, `FARM_PREFERENCE`, `FIELD_PREFERENCE`, `OPERATING_CONSTRAINT`, and `COMMUNICATION_PREFERENCE`.

## 7 Memory Scopes

Supported scopes are `USER`, `FARM`, and `FIELD`. Farm and field scope IDs are verified through the existing owner/access services before write or retrieval.

## 8 Memory Write Policy

Writes require an explicit memory intent. Ordinary statements, recommendations, evidence, diagnoses, and tool results do not become memory. The server is authoritative for type, key, value, scope, ownership, provenance, lifecycle, and authorization.

## 9 Explicit Memory Intent

The router recognizes explicit Chinese and English write phrases such as “记住”, “请记住”, and “以后都”. Query and forget phrases are separately classified as `MEMORY_QUERY` and `MEMORY_FORGET_REQUEST`. Injection and unsupported requests are rejected before memory handling.

## 10 Structured Extraction

Extraction is deterministic and finite. It maps explicit requests to registered keys and normalized values; it does not ask a model to decide whether ordinary prose should be remembered. Supported examples include low-residue/low-cost treatment style, short/detailed response detail, conservative/balanced risk level, night avoidance, and irrigation avoidance.

## 11 Memory Validation

Validation rejects missing scope context, unsupported keys or values, cross-farm field references, unauthorized farm/field IDs, sensitive values, safety overrides, and malformed ownership/provenance. Invalid candidates are not persisted.

## 12 Memory Key Registry

The finite registry includes `preferred_treatment_style`, `preferred_response_detail`, `preferred_risk_level`, `avoid_operation_window`, `preferred_operation_window`, `avoid_operation_type`, and `user_defined_constraint` where the value is still validated as a bounded explicit constraint.

## 13 Ownership

Every record carries `userId`. User-scoped records belong to that user; farm and field records are also checked against the existing farm/field access relationship. Retrieval and revocation use the authenticated/request owner, never a caller-supplied owner alone.

## 14 Provenance

Every write records `sourceConversationId` and `sourceTurnId`, together with creation/update timestamps. The API exposes provenance-safe memory views so a remembered item can be traced to the explicit request that created it.

## 15 Persistence

The MySQL repository creates at most one new table, `copilot_long_term_memories`, with indexed owner/scope/status/key fields and append-oriented lifecycle updates. An in-memory repository is retained for deterministic unit/evaluation tests. No Redis, MongoDB, Milvus, or vector store is used.

## 16 Memory Status Lifecycle

Records use `ACTIVE`, `SUPERSEDED`, and `REVOKED`. Revocation is a status transition, not a normal hard delete. Only active records are eligible for retrieval.

## 17 Idempotency

An active semantic duplicate for the same owner, scope, type, key, and normalized value returns the existing record instead of creating another active copy. The write path remains tied to the originating conversation turn.

## 18 Supersession

For the same owner, scope, and key, a new different value supersedes the prior active value. The new record stores `supersedesMemoryId`; the old record becomes `SUPERSEDED` and is excluded from retrieval.

## 19 Revocation / Forget

Explicit forget requests and `POST /api/copilot/memories/{memoryId}/revoke` use owner-checked compare-and-set semantics. A revoked record is not loaded into later context and remains auditable in storage.

## 20 Retrieval

`GET /api/copilot/memories` provides the minimal management path. Copilot query and normal route assembly retrieve only active, owner-authorized, context-relevant records and cap the result at 20 active memories.

## 21 Scope Priority

For the same key, specificity is `FIELD > FARM > USER`. A more specific active record masks a less specific value for that key, preventing contradictory context expansion.

## 22 Context Assembly

`CopilotContextAssembler` adds relevant long-term memory views after system, current-user, stable page context, pending clarification, business context, and recent turns. Memory is lower priority than those existing context classes.

## 23 Context Budget

The existing 12,000-character Copilot context budget remains unchanged. Memory is bounded by count and serialization size; it cannot displace higher-priority safety, identity, pending, or current-page context.

## 24 Agent Integration

Phase 1.6 integrates memory with the Copilot route and context layer only. Memory does not create tools, plans, operations, approvals, or autonomous execution. Any future Agent use must carry memory provenance and independently validate authority.

## 25 Memory vs Evidence

Memory is never an evidence item, evidence ID, diagnosis source, or evidence sufficiency signal. The evaluation explicitly checks that memory cannot satisfy evidence requirements.

## 26 Memory vs Knowledge

Memory is not curated knowledge, a knowledge chunk, a retrieval document, or a source of agronomic facts. It only personalizes how an otherwise grounded response is presented or constrained.

## 27 Memory vs HistoricalCase

Memory is not a historical case and cannot promote, mutate, or qualify a `HistoricalCase`. Historical-case eligibility remains governed by its existing evidence and review rules.

## 28 Safety Override Defense

Requests to ignore evidence, skip approval, bypass authorization, auto-execute, ignore system rules, or use demo fallback are rejected as memory candidates. A preference cannot weaken safety gates.

## 29 Sensitive Memory Rejection

Passwords, tokens, JWTs, API keys, secrets, and similar credentials are rejected and are never written to memory or exposed in responses. The Phase 1.6 evaluation includes a sensitive-memory case.

## 30 Prompt Injection Boundary

Memory extraction is deterministic and runs after injection/unsupported-request checks. Retrieved memory is represented as bounded structured data, not executable instructions, and cannot override system or server policy.

## 31 Copilot Backend

Implemented typed memory models, repositories, service validation, lifecycle handling, route intents/capabilities, bounded assembler integration, conversation result persistence, memory query/revoke endpoints, and field-access error handling.

## 32 Copilot Frontend

Updated contracts and service methods for memory views and revocation. The drawer supports explicit “已记住”, query, and forget feedback while preserving the Phase 1.5 new-conversation path.

## 33 Cross-session Evaluation

The deterministic Phase 1.6 evaluation covers L01–L25. It writes a memory in one conversation, starts another conversation, verifies retrieval, verifies no short-term-only inheritance, tests scope, ownership, duplicate, supersession, revocation, security, and pollution boundaries.

## 34 Memory Metrics

Phase 1.6 evaluation results: write precision `1.00`; scope accuracy `1.00`; cross-session retrieval accuracy `1.00`; supersession accuracy `1.00`; revocation accuracy `1.00`; memory context bound compliance `1.00`; sensitive-memory rejection rate `1.00`; safety-override rejection rate `1.00`; cross-user memory violation rate `0.00`; memory-as-evidence violation rate `0.00`; REAL demo leakage `0.00`.

## 35 HTTP Cross-session Smoke

Controlled HTTP smoke passed in the evaluation test: explicit write response, new conversation query, and owner-scoped GET all returned the expected active memory and provenance.

## 36 HTTP Forget Smoke

Controlled HTTP smoke passed: owner-checked revoke returned success, and subsequent retrieval omitted the revoked memory.

## 37 HTTP Supersession Smoke

Controlled service/HTTP-equivalent smoke passed: a new value for the same scoped key superseded the old value, preserved `supersedesMemoryId`, and exposed only the active value.

## 38 Browser Smoke

Browser test coverage was added for explicit write, new-conversation query, and forget. Live execution is `LIVE_BROWSER_CONDITIONAL`: Playwright stopped before the test because `FARMAP_PILOT_USERNAME` was not available. No credential was printed or fabricated.

## 39 Phase 1.5 Regression

`AgentV2Phase15ShortTermMemoryEvaluationTest` passed. Conversation continuity, refresh, new conversation, owner isolation, bounded turns, CAS, and idempotent append behavior remain green.

## 40 Phase 1.4 Regression

Phase 1.4 intent/context router regression tests passed, including deterministic intent classification, stable-ID resolution, unsupported/injection rejection, and page-context precedence.

## 41 Phase 1.3 Regression

Phase 1.3 bounded replanning and dynamic plan adaptation regression tests passed with existing approval, retry, bounded-replan, and terminal-state semantics intact.

## 42 Phase 1.2 Regression

Phase 1.2 tool-failure handling and bounded recovery regression tests passed with fail-closed behavior and bounded retry/replan behavior intact.

## 43 Phase 1.1 Regression

Phase 1.1 agent evidence-sufficiency regression passed. Memory did not alter evidence sufficiency or allow unsupported diagnosis completion.

## 44 AI Baseline

The constrained AI baseline remained green alongside the Phase 1.1–1.6 targeted suite. Existing nonfatal visual-gateway warnings do not change the tested result; `LocalVisionServiceTest` remains explicitly excluded as required.

## 45 Commercial MVP Regression

Commercial MVP regression remains `FROZEN / NO REGRESSION`. Existing RAG, Agent, full product-loop, approval, knowledge, operations, and field-context tests were included in the constrained full Maven run and passed.

## 46 Migration Readiness

Migration readiness passed with the new `copilot_long_term_memories` table and required columns, including provenance, status, version, and supersession fields. Live database application remains conditional because the prior local MySQL startup could not authenticate the configured `zhgy` user.

## 47 Full Maven

Constrained full Maven command passed: `143` tests, `0` failures, `0` errors, `14` skipped, with `LocalVisionServiceTest` excluded and no destructive test-store cleanup.

## 48 Frontend

Frontend verification passed: TypeScript build, targeted ESLint for Copilot/store/E2E files, and Vite production build with minification disabled.

## 49 Deferred Vector Memory / Cache / Reflection

Deferred by explicit Phase 1.6 boundary: semantic/vector memory, Milvus, Redis, MongoDB, LLM cache, embedding cache, tool cache, reflection/self-correction, multi-agent memory, transcript-to-memory promotion, and Copilot-wide redesign.

## 50 Phase 1.6 Decision

**Core Long-term Memory V1 = GO / Live DB + Browser = CONDITIONAL GO.** The core contract, deterministic evaluation, regression suite, frontend checks, migration-readiness checks, ownership, lifecycle, and safety boundaries are complete. Live rollout requires valid local MySQL credentials and authorized pilot browser credentials.
