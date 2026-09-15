# FarMap AI Engine Phase 1.2 — Tool Failure Handling + Bounded Recovery V1

## 1. Executive Summary

Phase 1.2 upgrades the bounded Agent runtime from terminal failure on every tool failure to classified, bounded recovery. The implementation remains sequential and deterministic: one configured recovery attempt is allowed for an idempotent read, safe alternatives are server-registered only, and writes are never automatically replayed.

Decision: **GO** for Recovery V1. Advanced replanning is deferred.

## 2. Starting Frozen Baseline

- Commercial MVP V1 remains frozen.
- Phase G.1 remains `PILOT CANDIDATE GO`; production rollout remains conditional.
- Phase 1.1 evidence sufficiency, unsupported-action prevention, approval compliance and stable provenance are treated as frozen contracts.
- Phase 0 A04 was `A04_TOOL_FAILURE_NOT_HANDLED = 1`; A08 was already closed at `0`.

## 3. Phase 1.2 Scope

Changed only the Agent runtime boundary, tool result semantics, tool descriptors, AgentRun persistence metadata and deterministic evaluation fixtures. No RAG redesign, knowledge re-ingestion, browser redesign, Vision work or open-ended planner loop was added.

## 4. Existing Failure Path

The path was `AgentPlanner → ToolExecutor → ToolResult → AgentRuntime.executeSteps`. `ToolExecutor` converted most exceptions to `FAILED`; `executeSteps` immediately called `fail(...)` for `FAILED`. There was no semantic classification, retry decision, recovery budget or persisted recovery summary. This was the direct A04 root cause.

## 5. A04 Root Cause

Failure meaning was lost at the `ToolExecutor`/`AgentRuntime` boundary: transient, unavailable, invalid-request, authorization, provenance and contract failures were not distinguishable. The runtime therefore treated a recoverable read timeout and a permanent authorization error as the same terminal `FAILED` state.

## 6. Tool Result Contract

The existing `ToolResult` remains the sole result contract. It now carries `executionStatus`, `errorType`, `retryable`, `attempt` and `durationMs` in addition to the existing lifecycle `status`, payload/output, evidence, actions and error fields. `ToolStatus` remains unchanged for compatibility.

## 7. Failure Classification

The semantic statuses are `SUCCESS`, `EMPTY`, `UNAVAILABLE`, `RETRYABLE_FAILURE`, `NON_RETRYABLE_FAILURE` and `CONTRACT_FAILURE`. `ToolStatus.COMPLETED` is therefore no longer used to imply that an empty result is a successful data hit.

## 8. Retryable Failures

Only explicit transient signals are retryable: provider-declared retryable gateway errors, temporary timeout/connection/IO failures and controlled HTTP 429/502/503-style adapter results. The policy is not inferred from arbitrary exception text.

## 9. Non-Retryable Failures

Invalid input, authorization/403, unknown resources, unsupported operations, business/provenance conflicts and ordinary tool failures are non-retryable. They fail closed with their original error code and no retry.

## 10. EMPTY Semantics

`EMPTY` is distinct from failure and unavailable. Existing `READY_EMPTY`, `EMPTY` and `NO_KNOWLEDGE_AVAILABLE` outputs are classified as `EMPTY` and are never retried.

## 11. UNAVAILABLE Semantics

Unavailable dependencies are recorded as unavailable and may be continued over for optional read steps. Required unavailable evidence remains visible to the existing Evidence Sufficiency gate, which prevents a write action. No blind retry is performed unless a server-registered safe alternative is explicitly configured.

## 12. Contract Failure

Malformed/invalid tool contracts are represented as `CONTRACT_FAILURE` and fail closed. They are not converted into fabricated success and are not automatically retried.

## 13. Tool Capability Metadata

`AgentTool` now exposes capability, idempotency, capability family and server-declared safe alternatives. The registry descriptor exposes `READ_ONLY`/`WRITE_CAPABLE`, idempotency and filtered alternatives. Alternatives must be registered, read-only and in the same capability family.

## 14. Read-only Retry Policy

The default is initial attempt plus one retry (`FARMAP_AGENT_TOOL_MAX_ATTEMPTS=2`). Only read-only idempotent tools can use this path. The retry is counted as a tool call and recovery attempt; it cannot bypass max tool calls or the run timeout.

## 15. Write Tool Policy

Agent recovery never retries a write-capable tool. Approval, Operations task creation and external farm actions remain behind the existing human approval boundary and existing idempotency key (`actionId`).

## 16. Ambiguous Write Handling

For a write timeout that may have committed, the runtime performs one authoritative lookup by stable `actionId`. If a Task exists, it completes the AgentRun using that Task; if not, it records `WRITE_OUTCOME_AMBIGUOUS` and fails closed. It never replays the write.

## 17. Recovery Budget

`FARMAP_AGENT_MAX_RECOVERY_ATTEMPTS` defaults to `1` and is bounded to one. A retry or safe alternative consumes that budget. There is no recursive or open-ended recovery loop.

## 18. Backoff

`FARMAP_AGENT_TOOL_RETRY_BASE_DELAY_MS` defaults to a short 25 ms delay and is capped at 1000 ms. Deterministic unit construction uses zero delay. There is no minutes-long retry loop.

## 19. Recovery Decision Model

Decisions are `RETRY`, `CONTINUE_WITHOUT_TOOL`, `USE_SAFE_ALTERNATIVE` and `FAIL_CLOSED`. The decision is derived from tool capability, semantic status, required/optional step policy and the bounded budget; it is never invented by the model.

## 20. Alternative Tool Policy

Only a registered same-family read-only alternative can be selected. The runtime chooses the first server-declared alternative once. If it also fails, there is no third arbitrary tool and the run degrades or fails closed according to the step policy.

## 21. Provenance Preservation

Recovery reuses the existing run context and persisted Diagnosis snapshot. Diagnosis, Recommendation and Evidence IDs are not regenerated or inferred from titles, indexes or similarity. Retried results retain their server-owned evidence identities.

## 22. Evidence Deduplication

The existing stable evidence-ID merge remains the deduplication boundary. Repeated results with the same evidence ID are merged once; no fuzzy deduplication was added.

## 23. Approval Boundary

A tool failure cannot bypass approval. Before approval, recovery can only gather read evidence or degrade. After approval, the existing ApprovalManager and Operations layer owns write execution and reconciliation.

## 24. Task Idempotency

The existing `actionId`-backed Operations idempotency remains unchanged. Duplicate approval or recovery returns the same Task and does not execute `TaskTool` twice.

## 25. Outcome Idempotency

The existing Operations completion transaction remains unchanged. Duplicate completion returns the same immutable Outcome and does not create a second completion record.

## 26. Recovery Persistence

`AgentRun` now persists `recoveryAttempted`, `recoveryCount`, `recoveryOutcome` and `recoverySummary`. Tool calls persist semantic status, attempt number, recovery marker, duration and error information. No recovery business table or migration was added.

## 27. Audit / Logging

Existing trace persistence records classification, recovery decision, retry/fallback, success/exhaustion, write reconciliation and authoritative task recovery. No credentials, JWTs, raw knowledge content or chain-of-thought are logged.

## 28. Evaluation Dataset

`AgentV2Phase12EvaluationTest` covers R01–R20: transient read recovery, repeated failure, EMPTY, UNAVAILABLE, invalid input, authorization, provenance conflict, contract failure, write failure, ambiguous write, duplicate approval/completion, safe alternative, required/optional dependencies, stable IDs, no RAG rerun, cross-user access and REAL/DEMO isolation.

## 29. A04 Result

The Phase 1.2 fixture closes A04: **before 1, after 0**. A tool failure is now classified and either boundedly recovered, explicitly degraded, reconciled or fail-closed.

## 30. Recovery Metrics

The deterministic fixture produced:

| Metric | Result |
|---|---:|
| Tool Status Classification Accuracy | 1.0 |
| Recoverable Failure Success Rate | 1.0 |
| Retry Policy Compliance | 1.0 |
| Unsafe Write Retry Rate | 0 |
| Alternative Tool Policy Compliance | 1.0 |
| Approval Compliance | 1.0 |
| Stable Provenance Integrity | 1.0 |
| REAL Demo Leakage | 0 |

## 31. HTTP Recovery Smoke

PASS. A controlled local HTTP adapter received a real 503 on attempt 1 and 200 on attempt 2. The Agent completed after exactly one bounded retry; two HTTP requests were observed and the run recovery outcome was `RECOVERED`. No production dependency was stopped or damaged.

## 32. Write Safety Regression

PASS. R09 performs no write replay. R10 commits a Task before returning a simulated ambiguous timeout, then recovers it by authoritative `actionId` lookup. Duplicate approval and completion preserve the same Task and Outcome.

## 33. Phase 1.1 Regression

PASS. `AgentV2Phase11EvaluationTest` remains green; Evidence Sufficiency and unsupported-action prevention remain unchanged.

## 34. AI Baseline Regression

PASS. `AiEngineBaselineEvaluationTest` remains green. Its historical A04 baseline remains preserved as historical evidence and is not rewritten.

## 35. Commercial MVP Regression

PASS. `CommercialMultisourceRagEvaluationTest`, `CommercialAgentProductLoopEvaluationTest` and `CommercialMvpFullProductLoopEvaluationTest` remain green. Login, Diagnosis, Agent, Approval, Task and Outcome contracts were not redesigned.

## 36. Full Maven

The retained Phase G baseline is 134 tests, 0 failures, 0 errors and 14 skips. The final Phase 1.2 constrained full run excluding only the known optional `LocalVisionServiceTest` host-memory case passed: **136 tests, 0 failures, 0 errors, 14 skips**. The CLIP/ONNX case is not changed by this phase.

## 37. Frontend Validation

No frontend files were changed. Phase G.1 browser evidence remains the frozen browser baseline. No new Agent UI retry-write control was introduced.

## 38. Files Changed

- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/ToolExecutionStatus.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/ToolCapability.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/RecoveryDecisionType.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/AgentRecoveryPolicy.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/ToolResult.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/ToolExecutor.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/ToolRegistry.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/AgentTool.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/AgentToolCall.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/AgentPlanStep.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/AgentRun.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/AgentRuntime.java`
- `zhgy/zhgy/src/main/resources/application.yml`
- `zhgy/zhgy/src/test/java/com/mei/zhgy/evaluation/AgentV2Phase12EvaluationTest.java`

## 39. Remaining AI Gaps

Advanced bounded replanning, richer alternative-tool planning, semantic recovery quality measurement and RAG V2 quality optimization remain deferred. Live Vision remains outside this phase and `LIVE_VISION_REQUIRED_FOR_CORE_PILOT=false` remains unchanged.

## 40. Phase 1.2 Decision

**GO — Recovery V1.** The hard gates are satisfied: A04 is closed, read recovery is bounded, EMPTY is not retried, non-retryable failures fail closed, writes are not replayed, ambiguous writes reconcile authoritatively, approval and idempotency remain intact, stable provenance is preserved, and Commercial MVP regression passes.
