# FarMap AI Engine Phase 1.3 — Bounded Replanning Result

## 1. Executive Summary

Phase 1.3 implements one server-constrained plan adaptation after an explicit tool/evidence event. It is deterministic, registry-bound, budgeted, provenance-preserving, and approval-preserving. No open-ended planning, reflection, memory, multi-agent loop, or RAG rerun was added.

Decision: **GO**.

## 2. Frozen Baseline

Commercial MVP V1, Phase G.1, Phase 1.1, and Phase 1.2 contracts remain frozen. Phase 1.2 recovery classification and bounded read retry are reused unchanged as the recovery boundary.

## 3. Phase 1.3 Scope

Supported decisions are `SKIP_STEP`, `REPLACE_STEP`, `SHORTEN_PLAN`, and `TERMINATE`; `KEEP_PLAN` is available for a validated no-op decision. Only the not-yet-executed suffix may change.

## 4. Existing Planning Model

`AgentPlanner.plan(run)` produces the initial typed `AgentPlan`. `AgentRuntime` executes the current step, records `ToolResult`, applies Phase 1.2 recovery, and then evaluates a possible replan trigger before advancing the execution index. Write steps remain proposed actions.

## 5. Replanning Trigger Model

Triggers are explicit server events: `RECOVERY_EXHAUSTED`, `SAFE_FALLBACK_FAILED`, `EMPTY_REQUIRED_SOURCE`, `EMPTY_OPTIONAL_SOURCE`, `REQUIRED_SOURCE_UNAVAILABLE`, `OPTIONAL_SOURCE_UNAVAILABLE`, `STEP_NO_LONGER_NEEDED`, and `ALREADY_SATISFIED`. Normal successful execution does not replan.

## 6. Replan Contract

`ReplanDecision` is structured and contains decision, trigger, reason code, target step, replacement tool, remaining steps, and a short business rationale. No chain-of-thought is retained.

## 7. Plan Versioning

`AgentRun` persists `initialPlan`, `currentPlan`, `plan`, `planVersion`, `replanAttempted`, `replanCount`, `replanReason`, and `replanSummary`. Initial plan version is 0; the first accepted adaptation is version 1.

## 8. Replan Budget

`FARMAP_AGENT_MAX_REPLANS` defaults to 1 and is independently bounded from `maxRecoveryAttempts`. A second trigger terminates/degrades without another replan.

## 9. Completed-Step Immutability

Completed steps remain in the historical prefix and are never deleted, rewritten, or reordered. A replacement receives a new step identity and is appended to the remaining suffix.

## 10. Remaining-Plan Mutation Rules

The suffix may remove optional dependent steps, insert one safe replacement, or terminate. The validated suffix cannot grow by more than one step and remains within the existing `maxSteps`/`maxToolCalls`/timeout constraints.

## 11. Tool Registry Boundary

Every step and replacement is validated against the server-side `ToolRegistry`. Unknown or model-invented tools are rejected.

## 12. Capability Compatibility

Replacement tools must be registered, read-only, declared as a safe alternative by the source tool, and in the same capability family. Write capability cannot be reached from a read failure.

## 13. Recovery → Replan Boundary

The order is fixed: Phase 1.2 classification → bounded recovery → trigger evaluation → at most one replan. Retry and replan counters are separate.

## 14. EMPTY Handling

`EMPTY` is never retried. An optional empty source can shorten the remaining plan; a required empty source may use a registered safe alternative or terminate.

## 15. UNAVAILABLE Handling

`UNAVAILABLE` is not blindly retried. Optional unavailable sources may be skipped; required unavailable sources terminate unless a registered safe alternative is valid.

## 16. Optional Source Replanning

Optional source failure or an already-sufficient server evidence state can skip that source and optional dependents while allowing the remaining approved flow to continue.

## 17. Required Source Replanning

Required source failure is fail-closed. If no safe alternative exists, the run ends with no write action and `allowsWrite=false`.

## 18. Alternative Tool Selection

The replanner selects only the first server-declared same-family read alternative. REAL runs explicitly reject demo-named alternatives.

## 19. Evidence Sufficiency Recheck

Every accepted replan performs a fresh server-side `EvidenceSufficiencyEvaluator` pass and the final write step evaluates again. The recheck is traced with plan version and reason codes.

## 20. Approval Boundary

Replanning never executes a write step. A final write remains `ProposedAction → Evidence Sufficiency → WAITING_FOR_APPROVAL`.

## 21. Stable Provenance

`diagnosisId`, `recommendationId`, and evidence IDs remain server-owned and stable across replanning. No Diagnosis or Recommendation is recreated.

## 22. Write Safety

No automatic write replay was added. Existing ambiguous-write authoritative `actionId` reconciliation remains the only recovery path for an approved write outcome.

## 23. Replan Persistence

Replan state is persisted in the existing AgentRun JSON payload and MySQL-compatible repository payload. No core business table was added.

## 24. Audit / Trace

Traces include `plan.created`, `plan.replanned` or `plan.replan.blocked`, `evidence.sufficiency.rechecked`, trigger, decision, plan version, target, replacement, and remaining step IDs. No hidden reasoning is persisted.

## 25. Evaluation Dataset

`AgentV2Phase13EvaluationTest` covers P01–P25. The suite also covers real loopback HTTP primary failure → bounded recovery exhaustion → safe alternative, normal HTTP no-replan, and required-failure smoke.

## 26. Replanning Metrics

| Metric | Result |
|---|---:|
| Replan Trigger Accuracy | 1.0 |
| Replan Decision Accuracy | 1.0 |
| Unnecessary Replan Rate | 0 |
| Replan Budget Compliance | 1.0 |
| Unsafe Tool Escalation Rate | 0 |
| Authorization Bypass Rate | 0 |
| Evidence Sufficiency Recheck Compliance | 1.0 |
| Approval Compliance | 1.0 |
| Stable Provenance Integrity | 1.0 |
| REAL Demo Leakage | 0 |

## 27. HTTP Replan Smoke

PASS: controlled loopback HTTP returned 503 twice for the primary tool, then a registered same-family alternative returned 200. The primary made exactly two requests and the alternative exactly one.

## 28. HTTP No-Replan Smoke

PASS: controlled HTTP success completed with one request and `replanCount=0`.

## 29. Required-Failure Smoke

PASS: required source unavailable without a safe alternative ended with no action and `allowsWrite=false`.

## 30. Phase 1.2 Regression

PASS. A04 remains 0; Tool Status Classification Accuracy and Recovery Success Rate remain 1.0 in the Phase 1.2 suite.

## 31. Phase 1.1 Regression

PASS. Evidence sufficiency and unsupported-action prevention remain unchanged.

## 32. AI Baseline

PASS. Existing `AiEngineBaselineEvaluationTest` passes without fixture modification.

## 33. Commercial MVP Regression

PASS. Commercial multisource RAG, agent product loop, and full product loop evaluation tests pass.

## 34. Full Maven

PASS: `mvn -q -DforkCount=0 -Dtest=*,!LocalVisionServiceTest test`; 138 tests, 0 failures, 0 errors, 14 skipped. The existing `LocalVisionServiceTest` host-memory limitation remains frozen and was not used to justify production-agent changes.

## 35. Frontend

No frontend changes. Existing Phase G.1 browser baseline remains applicable.

## 36. Files Changed

Backend-only: `AgentRuntime`, `AgentRun`, `ToolExecutor`, replanning policy/decision/trigger classes, application config, Phase 1.3 evaluation fixtures, and this result document.

## 37. Deferred Agent Capabilities

Intent Router, Short-term Memory, Long-term Memory, Cache, Reflection, and Multi-Agent behavior remain deferred.

## 38. Remaining AI Gaps

Advanced semantic reordering and model-assisted replanning remain intentionally deferred. Vision availability and the existing REAL/DEMO boundary are unchanged. Phase 1.4 remains the next planned capability boundary.

## 39. Phase 1.3 Decision

**GO — Core bounded replanning V1.** Commercial MVP V1 remains **FROZEN / NO REGRESSION**. Stop here and await approval for Phase 1.4 — Intent Router + Context Resolver V1.
