# FarMap Backend Contract Gap Analysis

本文件汇总当前 Backend 真实 contract、目标 Contract V1 和优先级。完整矩阵与对象盘点见 [commercial-contract-hardening-result.md](./commercial-contract-hardening-result.md)。

## Current API Surface

| Domain | Current API | Truth |
| --- | --- | --- |
| Diagnosis | `POST /diagnosis/analyze` | 一次分析响应；run_id、diagnosis、claims、evidence、recommendations；无独立 Diagnosis persistence |
| Agent | `/api/agent/runs*` | AgentRun、ToolCall、Action、Approval 操作；运行时 file/MySQL 可选 |
| Operations | `GET /api/operations/tasks` | 只有列表；TaskTool 隐式创建，只有 OPEN 真实状态 |
| Expert Review | `GET/POST /api/expert-review` | diagnosisId upsert；无 reviewId/version/history |
| Historical | `POST /api/historical-cases/ingest` | map ingest；只接受 CONFIRMED + image + expertDiagnosis + outcome |
| Knowledge | 无 `/api/knowledge` | classpath document + Retriever/KnowledgeTool |
| Runtime | `GET /api/runtime/health` | Agent、Operations、Historical、Knowledge 的简要运行状态 |

## Gap Matrix

| Object | Current | Target V1 | Priority | Required Backend Work |
| --- | --- | --- | --- | --- |
| Diagnosis | run_id response only | diagnosisId + persisted context/status | P0 | persistence、详情 API |
| Evidence | DiagnosisEvidenceVO | provenance/freshness/availability | P1 | DTO normalization |
| Recommendation | nested response item | durable recommendationId/state | P1 | persistence/relationship |
| AgentRun | payload repository | stable correlations/version | P1 | persistence semantics |
| AgentAction | ProposedAction in run | actionId/evidence/result/version | P0 | schema/API |
| Approval | approvalState only | approval record/idempotency/audit | P0 | unique constraint + transaction |
| OperationsTask | 15 fields, OPEN | lifecycle/actionId/outcome | P0 | schema + transitions |
| ExpertReview | diagnosisId upsert | reviewId/history/version | P1 | schema + optimistic lock |
| HistoricalCaseCandidate | no object | candidate lifecycle | P1 | persistence + APIs |
| HistoricalCase | Milvus request_id/vector | metadata/detail/search | P2 | metadata store + query |
| KnowledgeSource | absent | source authority/scope | P2 | resource model |
| KnowledgeDocument | classpath file/chunkId | version/index lifecycle | P2 | document/index APIs |

## Minimal Target API Proposal

### Product Loop P0/P1

```text
GET  /api/diagnoses/{diagnosisId}
GET  /api/diagnoses/{diagnosisId}/evidence
GET  /api/agent/runs/{runId}/actions
POST /api/agent/runs/{runId}/actions/{actionId}/approve
POST /api/agent/runs/{runId}/actions/{actionId}/reject
GET  /api/operations/tasks?page=&status=&farmId=&fieldId=
GET  /api/operations/tasks/{taskId}
POST /api/operations/tasks/{taskId}/transitions
GET  /api/expert-reviews?page=&status=&farmId=&fieldId=&reviewerId=
GET  /api/expert-reviews/{reviewId}
GET  /api/expert-reviews/{reviewId}/history
POST /api/expert-reviews/{reviewId}/submit
GET  /api/historical-case-candidates
GET  /api/historical-case-candidates/{candidateId}
POST /api/historical-case-candidates/{candidateId}/decision
```

### P2 Knowledge/Case

```text
GET  /api/historical-cases/search
GET  /api/historical-cases/{caseId}
GET  /api/historical-cases/ingestion/{jobId}
GET  /api/knowledge/sources
GET  /api/knowledge/documents
GET  /api/knowledge/documents/{documentId}
GET  /api/knowledge/search
POST /api/knowledge/documents
POST /api/knowledge/documents/{documentId}/versions/{version}/index
```

本阶段不实现这些接口，也不修改现有 Controller。

## Release Blockers

1. Approval duplicate approve：同一 `runId + actionId` 最多一个批准结果和一个 OperationsTask。
2. Task 写入必须由 actionId 唯一约束保护。
3. Diagnosis 必须有独立 diagnosisId，不能永久借用 runId。
4. Review 必须有版本和并发冲突语义。
5. Historical Candidate 必须先于 Milvus ingest 存在。
