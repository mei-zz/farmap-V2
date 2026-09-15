# FarMap AI Engine V2 — Evidence Sufficiency Gate Design

## Scope

Phase 1.1 adds one deterministic safety boundary before a write-capable `ProposedAction` is persisted in an `AgentRun`. It does not change the planner, retrieval ranking, tool implementations, approval manager, Operations lifecycle, frontend, database schema, historical-case promotion or model training.

## Current flow found in the Reality Audit

`AgentRuntime.execute()` runs the bounded planner steps. Read-only tools append their server-returned evidence to the run. When the first non-read-only step is reached, the previous implementation immediately created an `operations_task` action, copied the current evidence IDs, set `approvalState=REQUIRED`, and moved the run to `WAITING_FOR_APPROVAL`. There was no deterministic check that the action had current supporting evidence. `DiagnosisPersistenceService` already owns the persisted diagnosis snapshot and can resolve a recommendation and its `evidenceIds` by stable IDs.

## New flow

```text
read-only tools
  ↓
server-owned current evidence + persisted Diagnosis/Recommendation snapshot
  ↓
EvidenceSufficiencyEvaluator
  ├─ INSUFFICIENT / DEGRADED → persist decision, trace block, complete run, no action
  └─ SUFFICIENT → create ProposedAction → WAITING_FOR_APPROVAL
```

The gate runs before the runtime adds an action to the run. Tool-returned proposed actions are also checked before they are added.

## Decision model

`EvidenceSufficiencyDecision` is persisted inside the `AgentRun` JSON snapshot. It contains `status`, `allowsWrite`, stable `reasonCodes`, current evidence IDs, unavailable dependencies, and a human-readable summary. Statuses are:

* `SUFFICIENT`: trusted current evidence supports the requested action and no dependency is unavailable; write is allowed to reach the existing approval gate.
* `DEGRADED`: some evidence exists but a dependency is unavailable; write remains blocked by default.
* `INSUFFICIENT`: no trusted evidence, invalid evidence provenance, or missing recommendation evidence intersection.

The evaluator is pure, deterministic, and does not call a model.

## Policy and reason codes

| Condition | Result | Reason |
| --- | --- | --- |
| No current evidence | `INSUFFICIENT` | `NO_TRUSTED_EVIDENCE` |
| Empty retriever result | `INSUFFICIENT` | `EMPTY_RESULT` plus `NO_TRUSTED_EVIDENCE` |
| Tool/dependency unavailable with no evidence | `INSUFFICIENT` | `UNAVAILABLE_DEPENDENCY` plus `NO_TRUSTED_EVIDENCE` |
| Tool/dependency unavailable with partial evidence | `DEGRADED` | `UNAVAILABLE_DEPENDENCY` |
| Action asks for an ID absent from current server evidence | `INSUFFICIENT` | `EVIDENCE_PROVENANCE_INVALID` |
| Recommendation has no evidence or no ID intersection with current evidence | `INSUFFICIENT` | `RECOMMENDATION_EVIDENCE_MISSING` |
| Diagnosis-level run with trusted current evidence | `SUFFICIENT` | none |

`EMPTY` and `UNAVAILABLE` are retained as distinct facts. Neither is silently counted as evidence. Recommendation matching is by `recommendationId` and evidence ID intersection only; titles are never used.

## Provenance and ownership

For a persisted real run, the runtime resolves the diagnosis snapshot with `diagnosisId + ownerUserId`, merges its evidence into the run, and reads the recommendation evidence IDs from that snapshot. The context map is only a deterministic fixture/demo fallback when no persistence service is present. The client cannot choose the reviewer, action evidence, recommendation ownership, or eligibility result.

## Trace contract

Every evaluated write records `evidence.sufficiency.evaluated`. A blocked write additionally records `action.blocked.insufficient_evidence`. The action is never created and then deleted. A blocked run uses the existing `COMPLETED` terminal state with no approval request and no action.

## Baseline compatibility

The Phase 0 deterministic fixture uses mode `evaluation` to preserve the historical pre-gate baseline artifact. Production modes and the Phase 1.1 experiment use the gate. This keeps `farmap-eval-v1` immutable while `farmap-eval-v1.1` measures the new policy.

## Explicit non-goals

No new run state, retry/replan loop, evidence ranking change, RAG change, schema migration, frontend redesign, Milvus insertion, HistoricalCase promotion, fine-tuning or automatic learning is part of this design.
