# FarMap AI Engine V2 — Current Capability Audit

**Audit date:** 2026-09-06
**Branch:** `feature/commercial-product`
**Mode:** read-only code audit plus deterministic test fixtures
**Scope:** Agent runtime, multimodal RAG, evidence grounding, persistence and observable evaluation seams. No Agent/RAG optimisation was made.

## Executive assessment

FarMap has a real, bounded Agent Runtime and a real multimodal diagnosis pipeline, but it is still an **AI Engine V1 baseline**, not an Agent V2 or Multimodal RAG V2 system. The current implementation is strongest at typed context propagation, explicit tool boundaries, evidence IDs, approval-gated writes, and knowledge-version provenance. It is weakest at adaptive planning, recovery/replanning, explicit evidence sufficiency, cross-modal ranking calibration, contradiction handling, and semantic evaluation.

The audit found no existing reproducible benchmark before this phase. The new deterministic harness is intentionally test-only and measures the current behavior without changing it.

## Agent reality audit

### Inputs and context

`AgentRuntime.createRun` receives a goal, `AgentContext`, mode and authenticated owner. `AgentContext` carries farm/field/crop/phenology, image URLs, weather, diagnosis ID, optional recommendation ID, source modes and a diagnosis map. Stable diagnosis and recommendation IDs are copied into run metadata and action provenance. The model does not become the owner of these identifiers.

### Actual planning strategy

`AgentPlanner` constructs a deterministic ordered plan: field context, weather, optional camera, GIS, multimodal RAG, historical case, knowledge, then task. The list is bounded by `maxSteps` and `maxToolCalls`; each step depends on the previous step. An optional model availability probe is made, but its response is discarded. Therefore planning is **single-shot, fixed-policy planning**, not model-generated iterative planning.

### Tool selection and execution

`ToolRegistry` discovers named `AgentTool` implementations and `ToolExecutor` converts missing tools and thrown exceptions into explicit `UNAVAILABLE` or `FAILED` results. The runtime executes a plan sequentially, records each observable tool event, merges evidence by stable evidence ID, and injects prior outputs into the next request. Multiple tools are supported as multiple sequential plan steps, but there is no parallel execution or dynamic reordering.

### Failure, timeout and recovery

The runtime has a maximum call count and wall-clock timeout. `FAILED` ends a run as `FAILED`; `UNAVAILABLE` marks a step skipped and continues to completion. Empty output is not a first-class runtime status, so a tool can communicate empty data only inside its output/metadata. There is no retry policy, recovery worker, replanning loop, or explicit distinction between an empty result and an unavailable dependency at the Agent state-machine level. Approval execution has idempotent persistence and recovery of a committed Operations task, but this is write safety rather than general Agent recovery.

### Approval and termination

The non-read-only `task` step creates a server-normalized `ProposedAction`, attaches current evidence IDs, marks approval `REQUIRED`, and transitions to `WAITING_FOR_APPROVAL`. The write tool is not executed before approval. A successful read-only plan reaches `COMPLETED`; tool failure reaches `FAILED`; cancellation reaches `CANCELLED`; timeout and max-call violations fail closed. There is no evidence-sufficiency gate before proposing an action.

### Trace and observability

`AgentEvent` records sequence, timestamp, type and structured payload. The trace includes run creation, planning, step start/completion, tool start/completion, evidence additions, approval, action execution and Operations task creation/recovery. It does not record private model reasoning or a formal evaluation outcome. This is sufficient for deterministic structural metrics but not for semantic planning-quality evaluation.

## Current Agent capability matrix

| Capability | Status | Evidence / limitation |
| --- | --- | --- |
| Context grounding | CURRENT | `AgentContext` is passed to tools and model gateway |
| Diagnosis context | CURRENT | stable `diagnosisId` in context, metadata and action |
| Recommendation context | CURRENT / OPTIONAL | `sourceRecommendationId`; nullable for diagnosis-level runs |
| Tool registry | CURRENT | `ToolRegistry` + named `AgentTool` implementations |
| Tool selection | PARTIAL | deterministic ordered plan; optional model probe is not consumed |
| Multi-tool planning | CURRENT / BOUNDED | sequential steps, bounded by max steps/calls |
| Evidence collection | CURRENT | tool results merge `DiagnosisEvidenceVO` by ID |
| Evidence sufficiency | MISSING | no explicit sufficiency decision before action proposal |
| Action grounding | PARTIAL | action copies current evidence IDs; no semantic sufficiency check |
| Uncertainty handling | PARTIAL | unavailable/failed are explicit; empty is tool metadata, not a state |
| Tool failure recovery | PARTIAL | fail/skip semantics and approval-task recovery only |
| Replanning | MISSING | no dynamic replan after tool result |
| Termination policy | CURRENT / BOUNDED | completed, failed, cancelled, timeout and max-call paths |
| Human approval | CURRENT | write action enters `WAITING_FOR_APPROVAL` |
| Stable action provenance | CURRENT | server normalizes diagnosis/recommendation/evidence IDs |
| Outcome-aware evaluation | AVAILABLE AS DATA | frozen Product Loop stores Outcome and ExpertReview; Agent does not consume it |
| Memory | MISSING | no Agent memory layer |
| Reflection | MISSING | no reflection loop or model judge |

## Multimodal RAG reality audit

### Input and modality routing

`DiagnosisRagService` requires a field ID, 1–5 image URLs and weather context. It invokes every registered retriever unless an explicit `enabledModalities` ablation list disables a retriever. Camera evidence is conditional on image URLs; weather evidence is conditional on rainfall; GIS/spatial, soil and satellite evidence are conditional on structured request context; knowledge uses the current Knowledge Backend V1 version when available; historical cases use local CLIP/Milvus or explicit provided context.

### Retrieval and ranking

Knowledge retrieval builds a lexical query from crop, growth stage, query and `黄化`, takes a candidate limit, and optionally uses BGE embeddings when a provider is injected. `EvidenceFusionService` applies configurable modality weights and expert-status reliability, sorts by fused score, and writes `retrievalRank` and `fusedScore`. This is weighted fusion, not a learned reranker. Scores are not calibrated across modalities, and there is no nDCG-trained ranking or cross-modal normalization contract.

### Evidence construction and provenance

Each retriever creates a structured `DiagnosisEvidenceVO` with modality, stable evidence ID, source, preview and metadata. Knowledge evidence carries `sourceId`, `documentId`, `versionId`, content hash and retrieval metadata. Camera evidence can carry local CLIP model information; historical evidence can carry CLIP/Milvus distance. The diagnosis response preserves the server-owned evidence list and `EvidenceReferenceValidator` validates model claim/recommendation references in Qwen mode.

### Generation and grounding

The default generation path is a deterministic evidence-grounded template. The opt-in Qwen path uses evidence synthesis, structured multimodal generation, evidence-reference validation, one repair attempt, and conditional escalation for high-risk/low-confidence cases. Claims and recommendations must reference existing evidence IDs, but semantic support, contradiction resolution, and evidence sufficiency are not independently judged. Confidence is generated by the template or model; retrieval score is present in evidence but there is no calibrated confidence evaluation.

### Empty, unavailable and historical semantics

Knowledge uses `READY`, `READY_EMPTY` and `UNAVAILABLE`; historical storage distinguishes `READY_EMPTY` from `UNAVAILABLE`; model/provider and database health are reported separately. An empty historical store does not fabricate evidence or write Milvus. A Knowledge persistence exception is preserved to the HTTP boundary instead of being silently converted into an empty result.

## Current multimodal RAG capability matrix

| Capability | Status | Evidence / limitation |
| --- | --- | --- |
| Text knowledge retrieval | CURRENT | lexical retrieval; optional BGE hybrid path |
| Image retrieval | CURRENT / PARTIAL | camera evidence and optional CLIP embedding; model diagnosis remains separate |
| Weather evidence | CURRENT | structured rainfall/time evidence |
| GIS evidence | CURRENT / CONTEXT-BOUND | spatial adapter only when context is supplied |
| Soil evidence | CURRENT / CONTEXT-BOUND | structured sensor adapter |
| Historical case retrieval | READY_EMPTY / CURRENT | CLIP/Milvus path; no verified rows in baseline |
| Metadata filtering | PARTIAL | owner/current-version/status filters in Knowledge V1; no general modality filter language |
| Query understanding | PARTIAL | concatenated field/crop/stage/query terms |
| Modality routing | PARTIAL | enabled-modality ablation plus context presence |
| Candidate ranking | CURRENT / HEURISTIC | lexical/BGE score and fixed modality weights |
| Cross-modal normalization | MISSING | scores are weighted, not calibrated |
| Reranking | PARTIAL | local BGE semantic rerank when provider is injected |
| Evidence deduplication | PARTIAL | Agent merge by ID; fusion itself does not enforce content-level deduplication |
| Evidence diversity | MISSING | no explicit diversity objective or gate |
| Contradiction handling | MISSING | no contradiction detector or resolution policy |
| Evidence sufficiency | MISSING | minimum modality check is not a domain sufficiency judgment |
| Grounded claims | CURRENT / STRUCTURAL | reference validator checks evidence IDs, not semantic entailment |
| Grounded recommendations | CURRENT / STRUCTURAL | evidence IDs validated; action safety still requires approval |
| Citation correctness | PARTIAL | structural existence is validated; semantic relation is not |
| Retrieval evaluation | CURRENT after Phase 0 | deterministic Recall/Precision/MRR harness added |
| End-to-end diagnosis evaluation | PARTIAL after Phase 0 | deterministic structural cases; live semantic evaluation remains opt-in |

## Existing strengths

1. Stable Product Loop IDs and server-side provenance are already frozen.
2. Read-only tools and write tools are visibly separated; Operations writes require approval.
3. Knowledge evidence can be traced to source, document and current version.
4. The RAG response keeps evidence under server ownership and rejects unknown model citations in the live structured-output path.
5. Empty historical storage is not treated as verified historical knowledge.
6. The new Phase 0 harness is offline, deterministic, per-case and non-mutating.

## Highest-value hypotheses for later phases (not implemented)

- If required-tool recall is the dominant failure, evaluate planner/tool-routing changes before changing prompts broadly.
- If irrelevant knowledge retrieval dominates, compare query/ranking thresholds and modality calibration before introducing a reranker.
- If semantic grounding fails while citation IDs remain valid, add an expert-labelled entailment benchmark before changing generation.
- If dependency failures cause unsafe or overconfident actions, add an evidence sufficiency and degraded-mode gate before adding memory or multi-agent orchestration.
