# FarMap AI Engine V2 — Phase 0 Evaluation Baseline Result

**Branch:** `feature/commercial-product`
**Phase:** Reality Audit + Evaluation Baseline
**Decision:** **GO for measurement foundation; no Agent/RAG quality claim**
**Backend Product Loop:** FROZEN; regression gate required and executed separately

## 1. Executive Summary

FarMap now has a reproducible, deterministic way to measure the current Agent and multimodal RAG. The implementation deliberately stops at measurement: no planner redesign, prompt rewrite, new model, reranker, Graph RAG, historical promotion, Milvus ingestion or production schema change was made.

The baseline shows a useful engineering starting point: stable provenance and approval safety are intact, while generic tool failure recovery and irrelevant lexical retrieval are visible weaknesses. Those findings determine the next experiments; they are not patched in Phase 0.

## 2. Scope

Included: Agent/RAG code audit, capability matrices, failure taxonomy, versioned fixtures, deterministic runner, metric calculation, per-case results, JSON/Markdown artifacts, and backend regression checks.
Excluded: Agent V2, Multimodal RAG V2, live provider optimisation, UI changes, Product Loop schema changes, HistoricalCase promotion and automatic learning.

## 3. Backend Freeze Verification

The existing Product Loop remains the authoritative chain:

```text
KnowledgeSource → KnowledgeDocument → KnowledgeVersion → Evidence → Diagnosis
→ Recommendation? → AgentRun → AgentAction → Approval → OperationsTask
→ Outcome → ExpertReview → HistoricalCaseCandidate
```

Phase 0 uses only isolated in-memory doubles and existing read-only retrievers. It does not change the frozen IDs, relationships, transaction boundaries or migrations from Milestones 5.2.1–5.2 Final.

## 4. Agent Reality Audit

The current Agent receives goal, authenticated owner and `AgentContext`; it builds a deterministic bounded sequence of typed tool steps. The optional planner model call is an availability probe and its output is not parsed into a plan. The runtime executes sequentially, persists after observable transitions, propagates prior outputs and evidence, and creates a server-normalized action for the write step.

`WAITING_FOR_APPROVAL` is reached when a non-read-only step is encountered. `FAILED` is reached on tool failure, timeout or call-limit violation. `UNAVAILABLE` skips a step and continues. Cancellation is explicit. There is no retry, replan, evidence-sufficiency gate, memory or reflection loop.

## 5. Agent Capability Matrix

| Capability | Status |
| --- | --- |
| Context, diagnosis and optional recommendation grounding | CURRENT / OPTIONAL |
| Typed tool registry and bounded multi-tool execution | CURRENT / BOUNDED |
| Tool selection | PARTIAL; fixed policy |
| Evidence collection and stable IDs | CURRENT |
| Evidence sufficiency | MISSING |
| Action grounding | PARTIAL |
| Tool failure recovery | PARTIAL; approval-task recovery only |
| Replanning, memory and reflection | MISSING |
| Human approval and stable action provenance | CURRENT |

Detailed evidence is in [`ai-engine-v2-current-capability-audit.md`](ai-engine-v2-current-capability-audit.md).

## 6. Multimodal RAG Reality Audit

The diagnosis pipeline invokes camera, weather and current Knowledge evidence for the normal path, with conditional spatial, soil, satellite and historical adapters. Knowledge uses lexical retrieval and optional local BGE semantic reranking; fusion applies fixed modality weights and source reliability. The default response is a deterministic evidence-grounded template; Qwen structured generation is opt-in and validates evidence IDs with one repair attempt.

The pipeline distinguishes empty, disabled and unavailable states at the retriever/health boundaries. It does not yet calibrate scores across modalities, detect contradiction, measure semantic entailment, enforce evidence diversity or decide domain-level evidence sufficiency.

## 7. RAG Capability Matrix

| Capability | Status |
| --- | --- |
| Camera, weather, knowledge, GIS/spatial and soil evidence | CURRENT / CONTEXT-BOUND |
| Historical retrieval | READY_EMPTY / CURRENT adapter |
| Lexical ranking and optional BGE rerank | CURRENT / PARTIAL |
| Modality routing and metadata filtering | PARTIAL |
| Cross-modal calibration, diversity and contradiction handling | MISSING |
| Source/document/version provenance | CURRENT |
| Claim/recommendation evidence-ID validation | CURRENT / STRUCTURAL |
| Retrieval and end-to-end evaluation | ADDED in Phase 0; semantic judge still missing |

## 8. Agent Failure Taxonomy

The baseline uses relevant categories rather than pretending all possible failures exist:

`A01 WRONG_TOOL_SELECTION`, `A02 REQUIRED_TOOL_NOT_CALLED`, `A03 UNNECESSARY_TOOL_CALL`, `A04 TOOL_FAILURE_NOT_HANDLED`, `A05 TOOL_EMPTY_MISINTERPRETED`, `A06 TOOL_UNAVAILABLE_MISINTERPRETED`, `A07 INSUFFICIENT_EVIDENCE_ACTION`, `A08 UNSUPPORTED_ACTION`, `A10 INVALID_PROVENANCE`, `A12 PREMATURE_TERMINATION`, `A14 APPROVAL_REQUIRED_NOT_REQUESTED`, `A15 UNSAFE_WRITE_WITHOUT_APPROVAL`, `A17 FAILURE_RECOVERY_FAILURE`, `A19 RECOMMENDATION_MISALIGNMENT` and `A20 UNKNOWN`.

The deterministic run observed one `A04` case. The taxonomy is extensible and does not convert every expected failure into a pass.

## 9. RAG Failure Taxonomy

The baseline uses `R01 RELEVANT_EVIDENCE_NOT_RETRIEVED`, `R02 IRRELEVANT_EVIDENCE_RETRIEVED`, `R03 WRONG_MODALITY_USED`, `R04 REQUIRED_MODALITY_MISSING`, `R05 DUPLICATE_EVIDENCE`, `R07 STALE_EVIDENCE_USED`, `R09 WRONG_KNOWLEDGE_VERSION`, `R10 EVIDENCE_PROVENANCE_MISSING`, `R11 SCORE_MISINTERPRETATION`, `R12 CONTRADICTORY_EVIDENCE_NOT_HANDLED`, `R13 UNSUPPORTED_CLAIM`, `R14 UNSUPPORTED_RECOMMENDATION`, `R15 CITATION_MISMATCH`, `R16 OVERCONFIDENT_DIAGNOSIS`, `R18 EMPTY_MISINTERPRETED`, `R19 UNAVAILABLE_MISINTERPRETED` and `R20 UNKNOWN`.

The deterministic run observed one `R02` case. Semantic claim failures remain unmeasured rather than fabricated.

## 10. Evaluation Architecture

The test-only package `com.mei.zhgy.evaluation` contains the runner, deterministic tool doubles, retriever fixtures, metric calculation, failure classification and artifact writer. It executes the real `AgentRuntime`, `ToolExecutor`, `ApprovalManager`, `KnowledgeEvidenceRetriever`, `DiagnosisRagService` and `EvidenceFusionService` where deterministic inputs are available. It never runs as a production controller or write path.

## 11. Dataset Design

The fixture file is [`farmap-eval-v1.json`](../zhgy/zhgy/src/test/resources/evaluation/farmap-eval-v1.json). It contains `agentCases`, `ragCases` and `endToEndCases`, with stable IDs, expected tools/modalities/evidence and explicit tags. All synthetic facts are marked `TEST_FIXTURE` and remain outside Knowledge and HistoricalCase stores.

## 12. Dataset Statistics

| Group | Cases |
| --- | ---: |
| Agent | 10 |
| RAG retrieval | 12 |
| End-to-end | 5 |

This exceeds the Phase 0 minimum of 8 Agent, 10 RAG and 3 end-to-end cases while staying small enough for code review.

## 13. Deterministic Evaluation Mode

Hard-gate mode is `DETERMINISTIC`: fixed planner, fixed tool results, packaged knowledge fixture, fixed structured request context and no external model/vector dependency. Re-running the command produces the same case-level metric values; run ID and timestamp are artifact metadata.

## 14. Live Evaluation Mode

Live mode is **NOT RUN** in Phase 0. Existing opt-in integration tests remain separate and require explicit environment configuration. Provider/API keys, local AI, Mongo and Milvus are never required for the deterministic gate.

## 15. Agent Metrics

| Metric | Result |
| --- | ---: |
| Tool selection precision / recall / F1 | 1.0000 / 1.0000 / 1.0000 |
| Unnecessary tool rate | 0.0000 |
| Approval compliance | 1.0000 |
| Expected terminal success | 1.0000 |
| Tool status handling | 0.6000 |
| Action grounding rate | 0.2500 |
| Unsupported action rate | 0.7500 |
| Recovery success rate | 0.0000 |

Selection metrics describe the deterministic fixture planner, not a live model. Recovery is zero because no generic retry/replan loop exists; this is an honest baseline.

## 16. RAG Metrics

| Metric | Result |
| --- | ---: |
| Recall@1 / @3 / @5 | 0.8611 / 0.9167 / 0.9167 |
| Precision@3 / @5 | 0.9167 / 0.9167 |
| MRR | 0.7500 |
| Modality coverage | 1.0000 |
| Evidence provenance integrity | 1.0000 |
| Citation validity | structural only |

## 17. Grounding Metrics

The deterministic grounded template achieved structural citation validity and structural grounding coverage of `1.0000`: every checked claim/recommendation evidence ID existed in the server-owned evidence list. Semantic entailment and agronomic correctness are explicitly **not measured** in this phase.

## 18. Safety / Contract Metrics

Stable ID provenance, approval compliance, Knowledge source/document/version provenance and the Demo/Real boundary were all `1.0000` in the deterministic run. These are hard contract metrics and are independent of model quality.

## 19. Baseline Results

Machine-readable output is `zhgy/zhgy/target/evaluation-results/baseline-v1.json`. The test also writes a short Markdown artifact beside it. The latest run has 10 Agent, 12 RAG and 5 end-to-end cases, with the metrics in Sections 15–18.

## 20. Failure Distribution

| Code | Count | Case |
| --- | ---: | --- |
| `A04_TOOL_FAILURE_NOT_HANDLED` | 1 | `agent-error-006` |
| `A08_UNSUPPORTED_ACTION` | 3 | `agent-approval-007`, `agent-optional-recommendation-009`, `agent-stable-provenance-010` |
| `R02_IRRELEVANT_EVIDENCE_RETRIEVED` | 1 | `rag-knowledge-irrelevant-011` |

## 21. Representative Agent Failures

`agent-error-006` uses a fixed failing tool. The actual runtime correctly transitions to `FAILED`, but no recovery or replan is attempted, so the failure is classified as `A04_TOOL_FAILURE_NOT_HANDLED`. The next experiment should compare explicit retry/replan policies against this baseline; no such change is included here.

The three approval cases intentionally expose a second safety gap: the current runtime can propose a write action before an evidence-sufficiency policy rejects it. Approval compliance remains 100%, but action grounding is only 25% because only the case with an explicit fixture evidence ID is grounded. This is a high-value Phase 1 gate.

## 22. Representative RAG Failures

`rag-knowledge-irrelevant-011` sends a no-match query to the current lexical Knowledge retriever. It still returns top chunks because the current implementation ranks candidates without a zero-score rejection threshold. This is classified as `R02`, not hidden as an empty success.

## 23. Reproducibility

Use one of:

```text
cd zhgy/zhgy
mvn -Dtest=AiEngineBaselineEvaluationTest test
```

or:

```powershell
./scripts/run-ai-engine-v2-baseline.ps1
```

No secrets, API keys, database credentials or JWTs are required.

## 24. Regression Gate Proposal

Future Agent/RAG changes must preserve 100% stable provenance, approval compliance and Knowledge version integrity. They must publish per-case deltas, failure-code distribution and safety regressions. Retrieval Recall@5 and unsupported action rate become non-regression gates after the labelled dataset is expanded and thresholds are agreed.

## 25. Tests

The new deterministic test is `AiEngineBaselineEvaluationTest`. It validates dataset schema, stable IDs, case counts, metric calculation, artifact generation, structural grounding and hard safety metrics.

## 26. Backend Regression

The Phase 0 harness does not modify production Java classes, migrations or controllers. Existing frozen backend regression tests were retained. The full Maven run below is the authoritative regression result for this worktree.

Frontend: **NO FRONTEND CHANGE** in Phase 0. Existing frontend edits from prior Commercial Product milestones remain untouched; no UI build was needed for this test-only phase.

## 27. Full Maven

Final run after the fixture/harness changes:

```text
mvn test
```

Result: **96 tests, 0 failures, 0 errors, 12 skipped; BUILD SUCCESS**. The skips are the existing opt-in MySQL/provider integration tests when they are not enabled in the default process. The deterministic baseline itself ran in the full suite with 0/0/0.

The real local MySQL regression was also executed with the user-provided credentials supplied only through process environment variables: **10 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS** across write safety, Diagnosis persistence, Product Loop relationships, Operations lifecycle, ExpertReview, Knowledge, migration readiness and Product Loop E2E.

## 28. Files Changed

Phase 0 additions:

- `zhgy/zhgy/src/test/java/com/mei/zhgy/evaluation/AiEngineBaselineEvaluationTest.java`
- `zhgy/zhgy/src/test/resources/evaluation/farmap-eval-v1.json`
- `scripts/run-ai-engine-v2-baseline.ps1`
- `docs/ai-engine-v2-current-capability-audit.md`
- `docs/ai-engine-v2-baseline-v1.md`
- `docs/ai-engine-v2-evaluation-dataset-v1.md`
- `docs/ai-engine-v2-phase-0-evaluation-baseline-result.md`

No frontend files, production migrations or Product Loop source files were added for Phase 0.

## 29. Risks

The benchmark is small and synthetic; semantic grounding, agronomic correctness, confidence calibration, contradiction handling, latency/cost and live provider behavior remain open. The deterministic fixture planner also makes tool-selection metrics optimistic by design. These limitations are visible in the report and are not reasons to modify the baseline implementation before measurement is frozen.

## 30. Phase 0 Go / No-Go

**GO for the Evaluation Foundation V1.** Reality audits, capability matrices, taxonomies, versioned cases, deterministic runner, per-case results, failure distribution, baseline JSON/Markdown and reproducibility are present. **Not a GO for Agent/RAG quality** and not a live-evaluation certification.

## 31. Recommended Phase 1

Proceed to Agent V2 only after user acceptance of this baseline. Prioritize the first experiment around the observed failure categories: an evidence-sufficiency gate for proposed write actions, followed by general tool-failure recovery/replanning. Expand expert-labelled cases before changing the planner, and compare every change against `farmap-eval-v1` with safety gates unchanged.
