# FarMap AI Engine V2 — Phase 1.1 Result

**Phase:** Commercial Agent Readiness V1 — Evidence Sufficiency Gate + Unsupported Action Prevention  
**Branch:** `feature/commercial-product`  
**Scope decision:** **GO for deterministic evidence-gate implementation; Production CONDITIONAL GO**  
**Change boundary:** Agent pre-write safety only. No RAG, planner, Operations lifecycle, database migration or frontend redesign was added.

## 1. Reality Audit

Before this phase, `AgentRuntime.execute()` executed read-only plan steps and then created an `operations_task` `ProposedAction` as soon as it reached the first write step. It copied whatever evidence IDs were already in the run and moved directly to `WAITING_FOR_APPROVAL`. There was no sufficiency decision, recommendation evidence intersection check, or unsupported-write block.

Evidence entered a run only when a tool returned `ToolResult.evidence`. A persisted Diagnosis already retained an immutable response snapshot, including evidence and recommendation `evidenceIds`, through `DiagnosisPersistenceService`; this was not consulted by the old write branch. `ProposedAction` already had `diagnosisId`, optional `recommendationId` and `evidenceIds`. `AgentRun` is persisted as a JSON snapshot by both the file and JDBC repositories, so the gate decision can be stored without a schema migration.

## 2. Current Agent Model

The planner remains a bounded deterministic sequence with optional provider availability probing. The runtime executes typed tools sequentially, persists observable transitions, distinguishes `COMPLETED`, `FAILED` and `UNAVAILABLE`, and retains stable run/action provenance. Approval remains explicit and is still the only path to a task write. Generic retry/replan, memory and reflection remain out of scope.

## 3. Current Evidence Flow

The Phase 1.1 runtime now combines current server-returned run evidence with the persisted Diagnosis snapshot resolved by `diagnosisId + ownerUserId`. Recommendation evidence is read from the matching persisted `recommendationId`; no title or free-text matching is used. When no persistence service exists in an isolated fixture/demo, the test-only context map can provide recommendation evidence IDs. That fallback is documented and is not a client authority in production.

## 4. Root Problems

The Phase 0 artifact recorded three `A08_UNSUPPORTED_ACTION` cases. Approval was safe, but approval safety alone did not mean the proposed agricultural action was supported by current evidence. A no-evidence write, a recommendation with unrelated evidence, and a diagnosis-level write with no trusted evidence all reached the approval boundary. `farmap-eval-v1` therefore showed approval compliance `1.0` but action grounding `0.25` and unsupported action rate `0.75`.

## 5. Architecture Decision

The smallest safe boundary is a deterministic `EvidenceSufficiencyEvaluator` called immediately before the runtime adds a write-capable action to an `AgentRun`. It returns a typed `EvidenceSufficiencyDecision`. A blocked run uses the existing `COMPLETED` terminal state with no action and no approval request; no new run state is necessary. The decision is saved inside the run JSON snapshot.

## 6. EvidenceSufficiency Decision Model

`EvidenceSufficiencyDecision` contains:

* `status`: `SUFFICIENT`, `DEGRADED` or `INSUFFICIENT`;
* `allowsWrite`: the server-calculated write permission;
* stable `reasonCodes`;
* current server-owned `availableEvidenceIds`;
* `unavailableDependencies`;
* a concise summary.

`SUFFICIENT` only means that the action may enter the existing human approval state. It never auto-approves or executes a task.

## 7. Policy

| Input condition | Decision | Write outcome |
| --- | --- | --- |
| No trusted current evidence | `INSUFFICIENT` | blocked |
| `EMPTY` retrieval with no evidence | `INSUFFICIENT` | blocked |
| `UNAVAILABLE` dependency with no evidence | `INSUFFICIENT` | blocked |
| Partial valid evidence plus unavailable dependency | `DEGRADED` | blocked by default |
| Requested evidence ID absent from current server evidence | `INSUFFICIENT` | blocked |
| Recommendation ID has no evidence or no ID intersection | `INSUFFICIENT` | blocked |
| Diagnosis-level run with trusted current evidence | `SUFFICIENT` | allowed to approval |
| Supported action with trusted evidence and no unavailable dependency | `SUFFICIENT` | `WAITING_FOR_APPROVAL` |

## 8. Reason Codes

`NO_TRUSTED_EVIDENCE`, `EMPTY_RESULT`, `UNAVAILABLE_DEPENDENCY`, `EVIDENCE_PROVENANCE_INVALID` and `RECOMMENDATION_EVIDENCE_MISSING` are emitted by the evaluator. A blocked trace includes the status and codes, so a user-facing layer can explain the block without exposing model/tool internals.

## 9. Unsupported Action Behavior

The old sequence was:

```text
read-only tools → create ProposedAction → approval
```

The new sequence is:

```text
read-only tools → resolve trusted evidence → evaluate →
  block and complete with no action
  OR create ProposedAction → WAITING_FOR_APPROVAL
```

The runtime never creates an unsupported persisted action and then deletes it. Tool-returned proposed actions are checked before being added as well.

## 10. Recommendation-Specific Semantics

When `sourceRecommendationId` is present, the runtime loads the exact recommendation from the persisted Diagnosis snapshot and requires a non-empty intersection between its evidence IDs and current run evidence IDs. A different recommendation, a same-title recommendation in another diagnosis, or a client-supplied unrelated evidence list cannot satisfy the gate. When no recommendation exists, the run remains diagnosis-level and does not invent recommendation evidence.

## 11. Diagnosis-Level Semantics

Diagnosis-level writes keep `recommendationId = null`. They are legal only when current trusted evidence exists. A diagnosis-level run with no evidence is blocked by `NO_TRUSTED_EVIDENCE`; a diagnosis-level run with current evidence is allowed to the same approval state as before.

## 12. EMPTY and UNAVAILABLE

`EMPTY` is observed from completed tool output such as `status=EMPTY` or `NO_KNOWLEDGE_AVAILABLE`; `UNAVAILABLE` is observed from `ToolStatus.UNAVAILABLE` and its tool name. Neither status becomes an evidence ID. The decision keeps the distinction so the next recovery phase can treat an empty data state differently from a missing dependency.

## 13. Trace Events

Every write evaluation emits `evidence.sufficiency.evaluated`. A denied write additionally emits `action.blocked.insufficient_evidence`. Existing `approval.required`, approval and Operations traces are unchanged for `SUFFICIENT` actions.

## 14. Persistence

The typed decision is part of the `AgentRun` snapshot. File and `JdbcAgentRunRepository` serialization therefore preserve it across reload/restart. No new table or migration is required. The existing production persistence requirement remains `FARMAP_AGENT_PERSISTENCE=mysql`; fail-closed Diagnosis lookup marks the dependency unavailable and blocks the write.

## 15. Server-Side Validation

The evaluator only accepts current evidence objects already held by the server runtime. Its requested-ID overload rejects an ID absent from that set. Recommendation evidence comes from `DiagnosisPersistenceService.findRecommendation`; ownership is checked by the existing `ownerUserId` path before execution. There is no frontend or LLM decision authority for sufficiency.

## 16. Stable Provenance

`diagnosisId` remains distinct from `sourceRunId`. The existing server-normalized action ID and `diagnosisId/recommendationId` provenance remain unchanged. The gate copies only current evidence IDs into an allowed action and does not replace Diagnosis or Recommendation snapshots.

## 17. Approval Contract

An allowed action still has `approvalRequired=true`, `approvalState=REQUIRED`, and run status `WAITING_FOR_APPROVAL`. A blocked action has no `ProposedAction`, no approval record and no task. The evaluator does not execute Operations and does not alter approval race/idempotency code.

## 18. Dataset

`farmap-eval-v1` remains unchanged as the Phase 0 baseline identity. The new [`farmap-eval-v1.1.json`](../zhgy/zhgy/src/test/resources/evaluation/farmap-eval-v1.1.json) adds eight deterministic cases 011–018: no evidence, valid evidence, recommendation-supported, recommendation-unsupported, unavailable, empty, invalid evidence ID and diagnosis-level supported. All facts are test fixtures and do not write production data.

## 19. Evaluation Method

`AgentV2Phase11EvaluationTest` uses the real `AgentRuntime`, `ToolExecutor` and `EvidenceSufficiencyEvaluator` with fixed tools. The invalid-ID case directly exercises the evaluator's provenance overload. The test writes machine-readable and Markdown artifacts under `zhgy/zhgy/target/evaluation-results/`.

## 20. Phase 0 Baseline

| Metric | Baseline (`farmap-eval-v1`) |
| --- | ---: |
| Tool selection recall | 1.0000 |
| Tool status handling | 0.6000 |
| Recovery success | 0.0000 |
| Approval compliance | 1.0000 |
| Stable ID provenance | 1.0000 |
| Action grounding rate | 0.2500 |
| Unsupported action rate | 0.7500 |
| Unsupported action count | 3 |

## 21. Phase 1.1 Metrics

| Metric | Phase 1.1 (`farmap-eval-v1.1`) |
| --- | ---: |
| Evidence Sufficiency Decision Accuracy | 1.0000 (8/8) |
| Unsupported Action Prevention Rate | 1.0000 (5/5) |
| False Block Rate | 0.0000 (0/3) |
| Safe Action Allow Rate | 1.0000 (3/3) |
| Approval compliance | 1.0000 |
| Stable ID provenance | 1.0000 |
| Tool selection recall | 1.0000 |
| Tool status handling | 0.6000 |
| Recovery success | 0.0000 |
| Action grounding rate | 1.0000 |
| Unsupported action rate | 0.0000 |
| Unsupported action count | 0 |

## 22. Baseline | Phase 1.1 | Delta

| Metric | Baseline | Phase 1.1 | Delta |
| --- | ---: | ---: | ---: |
| Action grounding rate | 0.2500 | 1.0000 | +0.7500 |
| Unsupported action rate | 0.7500 | 0.0000 | -0.7500 |
| Unsupported action count | 3 | 0 | -3 |
| Approval compliance | 1.0000 | 1.0000 | 0.0000 |
| Stable ID provenance | 1.0000 | 1.0000 | 0.0000 |
| Tool selection recall | 1.0000 | 1.0000 | 0.0000 |
| Tool status handling | 0.6000 | 0.6000 | 0.0000 |
| Recovery success | 0.0000 | 0.0000 | 0.0000 |

## 23. Failure Delta

| Failure | Baseline | Phase 1.1 | Delta |
| --- | ---: | ---: | ---: |
| `A08_UNSUPPORTED_ACTION` | 3 | 0 | -3 |
| `A04_TOOL_FAILURE_NOT_HANDLED` | 1 | 1 | 0 |

The gate addresses unsupported writes. It intentionally does not claim to solve generic tool recovery; `A04` remains the next experiment.

## 24. Tests Added

* `EvidenceSufficiencyEvaluatorTest` — seven policy/provenance/EMPTY/UNAVAILABLE cases.
* `AgentEvidenceSufficiencyTest` — five runtime cases proving pre-action blocking, approval preservation, recommendation intersection, empty/unavailable behavior and filtering of tool-returned actions.
* `AgentV2Phase11EvaluationTest` — eight dataset cases and metric/artifact assertions.

## 25. Backend Regression

The frozen Product Loop tests continued to pass, including `ProductLoopRelationshipTest`, `AgentRuntimeTest`, `ProductionWriteSafetyTest` and all other default-suite tests. The production write-safety fixture now supplies one explicit fixture evidence item because it models a supported action under the new contract; no production behavior is weakened.

## 26. MySQL Integration

With `FARMAP_AGENT_PERSISTENCE=mysql` and the user-provided password supplied only as a process environment variable, the targeted Product Loop command passed `ProductLoopEndToEndMysqlTest`, `ProductLoopRelationshipMysqlTest` and `ProductionWriteSafetyMysqlTest` with 4 tests, 0 failures and 0 errors. The full `-Dtest=*MysqlTest` regression then passed **9 tests, 0 failures, 0 errors and 0 skipped**, covering Diagnosis persistence, write safety, Product Loop relationships, Operations lifecycle, ExpertReview, Knowledge and migration readiness. The persisted Diagnosis hydration path therefore remains compatible with the approval/task graph. No credential is stored in source or documentation.

## 27. HTTP E2E

No live HTTP smoke was run in this phase. The deterministic runtime and MySQL product-loop tests cover the new gate and persisted relationships at service level. An HTTP test should be added when the deployed environment and authenticated test user are available; this is a release gate for production rollout, not a reason to change the current API contract.

## 28. Full Maven

`mvn test` completed with **109 tests, 0 failures, 0 errors and 12 skipped; BUILD SUCCESS**. The skipped tests are the existing opt-in provider/MySQL integration tests when the MySQL environment is not enabled. `mvn -DskipTests compile` also passed.

## 29. Frontend

**NO FRONTEND CHANGE.** Existing agent, diagnosis, Operations and Commercial Product UI work remains untouched. The persisted run decision can be consumed by a later UI contract update without requiring a redesign in Phase 1.1.

## 30. Files Added or Modified

Added:

* `zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/EvidenceSufficiencyStatus.java`
* `zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/EvidenceSufficiencyDecision.java`
* `zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/EvidenceSufficiencyEvaluator.java`
* `zhgy/zhgy/src/test/java/com/mei/zhgy/service/agent/EvidenceSufficiencyEvaluatorTest.java`
* `zhgy/zhgy/src/test/java/com/mei/zhgy/service/agent/AgentEvidenceSufficiencyTest.java`
* `zhgy/zhgy/src/test/java/com/mei/zhgy/evaluation/AgentV2Phase11EvaluationTest.java`
* `zhgy/zhgy/src/test/resources/evaluation/farmap-eval-v1.1.json`
* `scripts/run-ai-engine-v2-phase-1.1.ps1`
* `docs/agent-v2-evidence-sufficiency-design.md`
* this result document

Modified for the new typed snapshot and gate:

* `zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/AgentRun.java`
* `zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/AgentRuntime.java`
* `zhgy/zhgy/src/test/java/com/mei/zhgy/service/agent/ProductionWriteSafetyTest.java` (supported-action fixture evidence)

No migration, controller, RAG, planner, Operations service or frontend file was changed for this phase.

## 31. Risks

The Phase 1.1 dataset is deterministic and small; it proves the contract, not agronomic correctness. The context-map recommendation fallback exists only for isolated fixtures/demo paths and must not become a client-controlled production source. Existing migration-runner and enterprise Expert-role authorization debt remain release risks. `A04` recovery and `R02` irrelevant retrieval remain open.

## 32. Release Gates

Before production rollout, require MySQL schema/runtime verification, an authenticated HTTP E2E, explicit ownership policy for legacy ownerless records, and a user-facing empty/unavailable explanation. The gate itself must remain fail-closed, keep `WAITING_FOR_APPROVAL` for supported actions, and preserve stable provenance.

## 33. Commercial Agent Readiness

Phase 1.1 materially improves commercial safety: an unsupported agricultural write cannot reach approval, while supported actions retain human approval and stable IDs. This is a necessary readiness increment, not a claim that FarMap has a complete autonomous agent. Recovery, replanning, semantic evidence quality, latency/cost measurement and broader labelled evaluation remain unfinished.

## 34. GO / NO-GO

**GO for Phase 1.1 code and deterministic evaluation.** All eight evidence cases pass, unsupported-write prevention is 100%, false blocks are zero, approval and provenance remain 100%, and the full Maven suite passes. **Production CONDITIONAL GO** remains appropriate until HTTP E2E, deployment migration execution and enterprise authorization gates are satisfied.

## 35. Next Phase 1.2

Proceed to **Tool Failure Recovery + Bounded Replanning**. Compare a bounded retry/replan policy against `A04_TOOL_FAILURE_NOT_HANDLED`, preserve the new sufficiency decision as a pre-write prerequisite, and add recovery cases without changing the RAG ranking or introducing unbounded autonomous loops.

## 36. Worktree / Git

No new branch was created, no commit was made, and no reset/restore/clean/checkout operation was used. The pre-existing dirty worktree from earlier milestones was preserved.
