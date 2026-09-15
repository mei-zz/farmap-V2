# FarMap AI Engine V2 — Evaluation Dataset V1

Dataset name: `FarMap AI Engine Evaluation Dataset`
Dataset version: `farmap-eval-v1`
Fixture file: `zhgy/zhgy/src/test/resources/evaluation/farmap-eval-v1.json`

The dataset is intentionally small, deterministic and traceable. Every case has a stable `caseId`; all records are marked as `TEST_FIXTURE` in the test harness. They are not production Diagnosis, Knowledge, HistoricalCase, ExpertReview or Candidate records.

## Dataset groups

| Group | Count | Purpose |
| --- | ---: | --- |
| `agentCases` | 10 | plan selection, tool status, approval, evidence and stable provenance |
| `ragCases` | 12 | camera, weather, knowledge, GIS, soil, satellite, historical-empty, disabled and irrelevant retrieval |
| `endToEndCases` | 5 | grounded diagnosis, insufficient evidence, dependency degradation, approval and supervision boundary |

## Agent cases

| Case ID | Coverage | Expected terminal state |
| --- | --- | --- |
| `agent-read-context-001` | read-only context tool | `COMPLETED` |
| `agent-multi-tool-002` | sequential multi-tool plan | `COMPLETED` |
| `agent-camera-003` | camera input tool | `COMPLETED` |
| `agent-empty-004` | empty result is observable | `COMPLETED` |
| `agent-unavailable-005` | unavailable dependency is skipped explicitly | `COMPLETED` |
| `agent-error-006` | tool error termination | `FAILED` |
| `agent-approval-007` | write action is gated | `WAITING_FOR_APPROVAL` |
| `agent-grounded-action-008` | action carries fixture evidence ID | `WAITING_FOR_APPROVAL` |
| `agent-optional-recommendation-009` | diagnosis-level run with null recommendation | `WAITING_FOR_APPROVAL` |
| `agent-stable-provenance-010` | server-owned diagnosis/recommendation IDs | `WAITING_FOR_APPROVAL` |

The agent cases use a custom deterministic planner and tool doubles so that no model provider, external service or Production Product Loop write is needed. They execute the real `AgentRuntime`, `ToolExecutor`, `ApprovalManager`, trace recording and evidence merge logic.

## RAG retrieval cases

| Case ID | Retriever / modality | Coverage |
| --- | --- | --- |
| `rag-camera-001` | camera | image evidence construction |
| `rag-weather-002` | weather | temporal structured evidence |
| `rag-knowledge-003` | knowledge | current packaged knowledge and version IDs |
| `rag-spatial-004` | spatial | GIS context adapter |
| `rag-soil-005` | soil | sensor context adapter |
| `rag-satellite-006` | satellite | remote-sensing context adapter |
| `rag-multimodal-007` | camera + weather + knowledge | actual `DiagnosisRagService` fusion path |
| `rag-disabled-knowledge-008` | knowledge disabled | disabled is distinct from empty |
| `rag-historical-empty-009` | historical case | `READY_EMPTY`, no fabricated case |
| `rag-knowledge-missing-010` | knowledge | context/query boundary |
| `rag-knowledge-irrelevant-011` | knowledge | no-match lexical query; expected irrelevant retrieval failure |
| `rag-provenance-012` | knowledge | source/document/version integrity |

The knowledge fixture uses the existing packaged orchard resource and the actual `KnowledgeEvidenceRetriever` lexical path. The multimodal case uses `DiagnosisRagService` and `EvidenceFusionService`. Historical retrieval is tested with an empty deterministic store; no Milvus insertion occurs.

## End-to-end cases

| Case ID | Scenario | Observable check |
| --- | --- | --- |
| `e2e-strong-evidence-001` | strong evidence | grounded template returns evidence and recommendations |
| `e2e-insufficient-evidence-002` | insufficient evidence | benchmark records explicit uncertainty policy requirement |
| `e2e-dependency-unavailable-003` | dependency unavailable | `UNAVAILABLE` remains distinct from `EMPTY` |
| `e2e-recommendation-approval-004` | recommendation-specific action | approval path is covered by Agent case |
| `e2e-failure-correction-005` | failure/correction | frozen ExpertReview/Candidate supervision boundary is available |

The final three cases are deliberately structural in Phase 0. They document the boundary to be extended with selected ExpertReview labels; they do not manufacture labels from free text or automatically add candidates to the benchmark.

## Fixture validation

The harness fails fast on:

- missing `datasetVersion`, group arrays or `caseId`;
- duplicate case IDs across groups;
- fewer than 8 Agent, 10 RAG or 3 end-to-end cases;
- missing retriever fixtures;
- unknown evidence references when structural grounding is evaluated.

## Future dataset expansion

The next dataset revision should add explicit expert-selected records for correct, partially correct, incorrect and inconclusive diagnoses; contradictory evidence; stale Knowledge versions; and real dependency outages in an isolated integration database. Production-like Review rows must be selected explicitly and must never be bulk-scanned or silently promoted into benchmark truth.
