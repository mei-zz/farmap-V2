# Milestone 5.1.6 Commercial Contract Hardening Result

FarMap V2 当前定位为 **Agricultural AI Decision Platform**。本里程碑是 Commercial UI 与 Backend V2 之间的合同冻结阶段，目标是把已经产品化的 Overview、Diagnosis、Agent、Approval、Operations、Expert Review、Historical Case 和 Knowledge 反向整理成可实现、可审计、可演进的 Commercial Product Contract V1。

本里程碑只完成审计、领域模型、关系、状态机、API Proposal、写安全和路线图定义。没有修改运行代码、Backend、Database Schema、Agent Runtime、ApprovalManager、RAG、Milvus、Redis、MQ、Spring Cloud、Neo4j 或 UI。

## 1. Reality Audit

### 1.1 当前可确认的真实入口

| 领域 | 当前真实入口 | 当前持久化/来源 | 主要消费者 |
| --- | --- | --- | --- |
| Farm | `/user/get-farm`、`/map/farm`、`/map/farm/{farmId}/locations` | MySQL `farm`、`location` | Layout、Farm、Overview、AgentContext |
| Field | `fieldId` 出现在 Diagnosis 请求、AgentContext、OperationsTask | 没有独立 Field entity/Repository；Demo 使用 `A-12` 等字符串 | Diagnosis、Agent、Operations、Overview |
| Crop | `FarmCropVO`、`AgentContext.crop`、前端 Field.crop | Farm crop 查询与 Demo 对象 | Farm、Diagnosis、Agent |
| Diagnosis | `POST /diagnosis/analyze` | 当前主要是一次分析响应，不是独立诊断存储 | Diagnosis 页面、Agent context、Expert Review |
| Claim/Finding | `DiagnosisAnalysisResponse.claims[]` | 响应内嵌 | Diagnosis Adapter、Evidence 引用校验 |
| Evidence | `DiagnosisEvidenceVO` | Retriever/AgentRun 内存或 JSON payload | Diagnosis、Agent、Overview、Review |
| Recommendation | `DiagnosisAnalysisResponse.recommendations[]` | 诊断响应内嵌；无独立表 | Diagnosis、Overview、Agent output |
| AgentRun | `/api/agent/runs`、`/api/agent/runs/{runId}`、SSE events | file repository 默认，可选 MySQL `agent_runs` | Agent Workspace、Approval、TaskTool |
| AgentAction | `AgentRun.actions[]` / `ProposedAction` | AgentRun payload | Agent Runtime、ApprovalManager、前端 Agent |
| Approval | `/api/agent/runs/{runId}/actions/{actionId}/approve`、reject | 仅 `ProposedAction.approvalState`，无 Approval entity | AgentRuntime、TaskTool |
| OperationsTask | `GET /api/operations/tasks`；写入由 TaskTool 触发 | 动态 MySQL `agent_operations_tasks` 或进程 fallback | Operations Center、Agent |
| ExpertReview | `/api/expert-review`、`/{diagnosisId}`、POST `/{diagnosisId}` | 动态 MySQL `agent_expert_reviews` 或 fallback | Diagnosis、Knowledge & Expert Center |
| HistoricalCaseCandidate | 无实体、无查询 API | ingest 请求中的 `Map` 临时字段 | HistoricalCaseIngestionService |
| HistoricalCase | Milvus `farmap_image_vectors_new` 只有 `request_id` + 512 vector | Milvus；无案例详情表/API | HistoricalCase Retriever、Agent、RAG |
| KnowledgeSource/Document | 无 REST resource；`KnowledgeEvidenceRetriever` 读取 classpath | `knowledge/orchard_management.txt` + embedding cache | Diagnosis RAG、KnowledgeTool、Agent |

### 1.2 关键现实结论

1. `run_id` 是 RAG/Agent 运行身份，当前前端会把真实 Diagnosis 的 `run_id` 映射为页面诊断 ID；后端没有独立 `diagnosisId` 与 Diagnosis Repository。
2. `Claim` 已经有 `id/text/evidence_ids`，可以作为 Finding 的当前实现，但它没有独立生命周期。
3. Evidence 已有统一 `DiagnosisEvidenceVO`，包括 `id/modality/title/summary/score/source/preview/metadata`；`score` 是检索/融合分数，不是诊断置信度。
4. Recommendation 已有 `id/title/priority/detail/evidence_ids`，但仍嵌在诊断响应中，不能作为稳定跨页面业务对象。
5. AgentAction 的 `id` 只在 AgentRun 内生成；没有 `recommendationId`、`evidenceIds`、`createdAt`、`approvedBy`、`approvedAt` 或执行结果关联。
6. Approval 目前不是独立对象。`ApprovalManager.approve()` 只把动作标记为 APPROVED，随后 `AgentRuntime.executeApprovedAction()` 立即调用 Tool；重复 approve 可能再次执行写操作，这是当前生产 P0。
7. OperationsTask 写入字段有 `taskId/title/fieldId/diagnosisId/agentRunId/status/priority/source/createdFrom/createdBy/evidenceIds/approvedBy/approvedAt/createdAt/goal`，没有 `actionId`、`recommendationId`、执行者、截止时间、结果或异常。
8. Expert Review 新 contract 以 `diagnosisId` 为主键；旧 Expert Workflow 仍以 `requestId`、`expertRevision` 和 `user_requests.status` 为主，存在两套 Review 语义。
9. Historical Case ingest 只接受已确认专家案例的 map；Candidate 没有独立对象，Milvus 也不保存完整案例 metadata。
10. Knowledge 目前是 Retriever 能力而非产品化知识管理系统。

## 2. Current Domain Object Inventory

| 对象 | 当前真实身份 | 当前字段 | 创建者/来源 | 生命周期 | 存储位置 | API/消费者 | 缺失字段 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Farm | `farm.id: Integer` | id/name/type/address/zoom/center/username/adcode | 用户/数据库 | 已存在 | MySQL | user/map/Layout | tenant、时间、权限、版本 |
| Field | `fieldId: String`，多为上下文或 Demo ID | name/crop/area/phenology 等主要在前端 | Demo、AgentContext、外部 map context | 无明确 lifecycle | 无独立表 | Diagnosis/Agent/Task | farmId 外键、geometry、版本、状态 |
| Crop | 当前是字符串或 `FarmCropVO.info` | crop/variety/stage | Farm/前端上下文 | 无明确 lifecycle | Farm query/Demo | Diagnosis/Agent | cropId、标准字典、区域 |
| Diagnosis | 当前使用 `run_id` 作为页面 ID | diagnosis.title/riskLevel/confidence/summary/alternatives | RAG 服务 + 模型/模板 | `AI_INITIAL` metadata | 请求响应；无 Diagnosis 表 | Diagnosis/Review/Agent | diagnosisId、farmId、fieldId、createdAt、status、版本 |
| Finding | `Claim.id` | id/text/evidence_ids | Grounded generator/模板 | 随响应 | Diagnosis response | Diagnosis Adapter | 独立 findingId、类型、严重度、状态 |
| Evidence | `DiagnosisEvidenceVO.id` | modality/title/summary/score/source/preview/metadata | Retriever | retrieved/selected 只在 metadata 中表达 | Diagnosis/Agent payload | RAG/Agent/UI | farmId、fieldId、observedAt/retrievedAt、availability/provenance 规范 |
| Recommendation | response `Recommendation.id` | title/priority/detail/evidence_ids | RAG generator/模板 | 诊断内嵌 | Diagnosis response | Overview/Diagnosis/Agent | recommendationId 稳定持久化、decisionState、createdAt |
| AgentRun | `runId` | goal/mode/context/status/plan/steps/toolCalls/evidence/output/actions/traces/metadata/timestamps | Agent Runtime | CREATED→…→COMPLETED/FAILED | file 或可选 MySQL payload | Agent API/UI | tenant/user、version、correlation IDs |
| AgentAction | `ProposedAction.id` | type/title/detail/toolName/approvalRequired/approvalState/payload | Planner/AgentRuntime | REQUIRED→APPROVED/REJECTED | AgentRun payload | Approval API/TaskTool | actionId provenance、evidence、result、version、idempotency |
| Approval | 无独立 ID | action approvalState、run approvalState | Human API call | implicit | AgentRun | AgentRuntime | approvalId、actor、decisionAt、reason、unique key、audit |
| OperationsTask | `taskId` UUID | 15 个动态列 | TaskTool | 目前只创建 OPEN | MySQL/fallback | Operations GET/UI | actionId/recommendationId、assignee、deadline、outcome、state history |
| ExpertReview | `diagnosisId` 主键 | status/reviewer/reviewedAt/aiDiagnosis/expertDiagnosis/evidence/recommendations/before/after | Expert Review POST | IN_REVIEW/CONFIRMED/CORRECTED | MySQL/fallback | Diagnosis/Knowledge Center | reviewId、reviewerId、version、history、comment、audit |
| HistoricalCaseCandidate | 无 | ingest map 的 caseId/imageUrl/expertDiagnosis/outcome/expertStatus | 未来由 Review/后台 ingest 组装 | 无 lifecycle | 无 | ingest service | candidateId/reviewId/diagnosisId/quality |
| HistoricalCase | Milvus `request_id` | request_id/vector | confirmed ingest | vector indexed | Milvus | Retriever | caseId/candidateId/metadata/image/outcome/index status |
| KnowledgeSource | 无 | classpath resource 名称隐含 | 配置 | 无 | classpath | Retriever | sourceId/publisher/authority/region |
| KnowledgeDocument | 无 | chunkId/content/hash 隐含 | Retriever load | loaded/not loaded 隐含 | classpath/cache | RAG | documentId/version/category/effectiveDate |

## 3. Commercial Domain Model V1

### 3.1 冻结后的业务链

```text
Farm
  ↓  farmId
Field
  ↓  fieldId
Observation / Context
  ↓  observationId
Diagnosis
  ├─ Finding
  ├─ Evidence
  └─ Recommendation
           ↓ recommendationId
       AgentRun
           ↓ runId
       AgentAction
           ↓ actionId
        Approval
           ↓ approvalId / idempotency key
     OperationsTask
           ↓ taskId
        Outcome
           ↓ outcomeId
      ExpertReview
           ↓ reviewId
HistoricalCaseCandidate
           ↓ candidateId
     HistoricalCase
           ↓ caseId
KnowledgeSource / KnowledgeDocument / KnowledgeVersion
           └─ provides Evidence provenance
```

### 3.2 目标身份

| 对象 | Commercial V1 稳定身份 | 说明 |
| --- | --- | --- |
| Farm | `farmId` | 沿用当前 Integer 语义，API 边界可序列化为 string，但不能混用含义 |
| Field | `fieldId` | 必须是农场内唯一、持久化的地块 ID；当前 `A-12` 只能作为候选值，不能假设已持久化 |
| Observation | `observationId` | 一次图像/天气/土壤/GIS观测或上下文快照的身份 |
| Diagnosis | `diagnosisId` | 一次可追踪诊断事实；不能继续默认等于 `runId` |
| Finding | `findingId` | 诊断中的可独立引用结论，关联 Evidence |
| Evidence | `evidenceId` | 跨 Diagnosis/Agent/Review 的稳定证据身份 |
| Recommendation | `recommendationId` | AI 建议的稳定身份，不等于 Action 或 Task |
| AgentRun | `runId` | 一次 Agent 执行过程身份 |
| AgentAction | `actionId` | Agent 计划执行的一次动作提议 |
| Approval | `approvalId` | 对 action 的一次人类决定；`runId + actionId` 必须唯一 |
| OperationsTask | `taskId` | 进入农事执行系统的任务身份 |
| Outcome | `outcomeId` | 对任务执行结果的身份，未来由现场反馈产生 |
| ExpertReview | `reviewId` | 一次专家审查版本；`diagnosisId` 是被审查对象，不是 review 身份 |
| Candidate | `candidateId` | 专家确认后等待质量检查/入库的案例候选 |
| HistoricalCase | `caseId` | 已完成入库的历史案例，不等于 Milvus vector primary key |
| KnowledgeSource | `sourceId` | 发布机构/内部来源身份 |
| KnowledgeDocument | `documentId` | 文档逻辑身份 |
| KnowledgeVersion | `documentId + version` | 可索引、可撤回的文档版本身份 |

所有跨对象关系必须使用稳定 ID 和外键/唯一约束。禁止通过 title、field name、文本相似度或前端猜测建立业务关联。

## 4. Object Identity / ID Strategy

### 4.1 当前与目标差异

| 当前关系 | 当前可靠性 | Commercial V1 决策 |
| --- | --- | --- |
| Recommendation → Task | 当前不存在直接 ID；只能通过 Agent 写入间接关联 | Task 必须带 `recommendationId?`，没有值就明确为非建议来源 |
| Diagnosis → Task | `diagnosisId` 字段存在但可为空，且当前常由 AgentContext 提供 | 保留并设为可审计 provenance；不允许标题匹配 |
| AgentRun → Task | `agentRunId` 当前存在 | 升级为稳定来源字段，并与 actionId 一起记录 |
| ExpertReview → Diagnosis | `diagnosisId` 当前直接存在 | 保留；增加 reviewId/version |
| ExpertReview → Candidate | 当前不存在 | 新增 candidateId/reviewId 关系 |
| Evidence → Finding | Claim `evidence_ids` 当前存在 | 冻结为 finding.evidenceIds |
| Evidence → Recommendation | Recommendation `evidence_ids` 当前存在 | 冻结为 recommendation.evidenceIds |

### 4.2 ID 规则

- ID 由创建域生成，跨域传递，不由页面重算。
- `runId` 不能在 API contract 中冒充 `diagnosisId`；迁移期可以保留 `sourceRunId`。
- `evidenceId` 不能由前端数组下标生成；当前 Demo ID 只属于演示数据。
- `actionId` 是一次动作提议的身份；重复请求不能创建第二个 action。
- `approvalId` 不应由页面生成；唯一边界是 `(runId, actionId)`。
- `taskId` 只能由 Operations 写边界生成；重复 approve 不能重新生成 taskId。

## 5. Relationship Matrix

| 关系 | 当前状态 | 目标状态 | 证据/缺口 |
| --- | --- | --- | --- |
| Diagnosis → Evidence | CURRENT | TARGET | response.evidence + metadata IDs |
| Diagnosis → Finding | CURRENT | TARGET | claims[] 作为当前实现 |
| Diagnosis → Recommendation | CURRENT | TARGET | recommendations[] 内嵌，无持久化 |
| Diagnosis → AgentRun | PARTIAL | TARGET | 当前只在前端将 run_id 当诊断 ID |
| Evidence → Finding | CURRENT | TARGET | `Claim.evidence_ids` |
| Evidence → Recommendation | CURRENT | TARGET | `Recommendation.evidence_ids` |
| AgentRun → Evidence | CURRENT | TARGET | AgentRun.evidence[] |
| AgentRun → Action | CURRENT | TARGET | AgentRun.actions[] |
| Action → Approval | PARTIAL | TARGET | 只有 approvalState，没有 Approval entity |
| Action → Task | PARTIAL | TARGET | TaskTool 执行后没有 actionId 写入 |
| Evidence → Action | PARTIAL | TARGET | approval execution 临时从 run.evidence 组装 attributes |
| Evidence → OperationsTask | PARTIAL | TARGET | task.evidenceIds 可写入，但未强制非空/可验证 |
| Task → Diagnosis | PARTIAL | TARGET | field exists, nullable |
| Task → AgentRun | CURRENT | TARGET | agentRunId exists |
| ExpertReview → Diagnosis | CURRENT | TARGET | diagnosisId 主键，但 reviewId 缺失 |
| ExpertReview → Evidence | PARTIAL | TARGET | evidence 是自由 JSON/字符串 |
| ExpertReview → Candidate | MISSING | TARGET | ingest map 没有 reviewId |
| Candidate → HistoricalCase | MISSING | TARGET | Milvus ingest 直接写 vector |
| KnowledgeDocument → Evidence | PARTIAL | TARGET | source.document 为文字文件名，无 documentId/version |
| HistoricalCase → Evidence | PARTIAL | TARGET | retriever 返回 evidence，但无 case metadata contract |

## 6. Evidence Contract V1

### 6.1 冻结语义

```json
{
  "evidenceId": "ev-...",
  "modality": "camera|satellite|weather|soil|spatial|knowledge|historical_case",
  "sourceType": "camera|sensor|weather_station|gis|knowledge_document|historical_case",
  "sourceId": "stable source id",
  "farmId": "farm-...",
  "fieldId": "field-...",
  "observedAt": "ISO-8601 or null",
  "retrievedAt": "ISO-8601",
  "contentSummary": "business-readable summary",
  "preview": {},
  "metadata": {},
  "confidence": null,
  "retrievalScore": null,
  "availability": "AVAILABLE|EMPTY|UNAVAILABLE|STALE",
  "mode": "REAL|DEMO|STALE|UNAVAILABLE|EMPTY",
  "provenance": {"sourceMode": "LOCAL|REMOTE|FALLBACK|PROVIDED_CONTEXT"}
}
```

### 6.2 语义约束

- `retrievalScore` 表示 Retriever/融合排序质量；不能显示为 Diagnosis confidence。
- `confidence` 表示对 Evidence 本身的可信程度，缺失时为 null，不填 0。
- Diagnosis `confidence` 是结论置信度，Agent output 的 confidence 是运行结果摘要，三者不能复用字段。
- `observedAt` 是事实发生时间，`retrievedAt` 是系统取得时间，不能混成一个 `updatedAt`。
- Evidence 必须能回答来源、时间、农场、地块和可用性；无法回答时标记缺失或 UNAVAILABLE。

### 6.3 现实映射

当前 `DiagnosisEvidenceVO.score` 映射为 `retrievalScore`，`source.timestamp` 映射为 `observedAt` 候选，`metadata.sourceMode` 映射为 provenance。当前缺少稳定 `sourceId/farmId/observedAt/retrievedAt/availability`，列入 Backend V2。

### 6.4 统一 Provenance

Diagnosis、Recommendation、AgentAction、OperationsTask、ExpertReview、HistoricalCase 和 KnowledgeVersion 都应至少能够回答：

```text
createdByType
createdById?
sourceType
sourceId?
createdAt
correlationId?
```

这是一组跨域审计语义，不要求所有对象共享同一张表，也不允许用一个泛化 `source` 字符串替代具体来源 ID。

## 7. Recommendation / Action / Approval Separation

| 概念 | 固定产品语义 | 当前实现 | 目标 Contract |
| --- | --- | --- | --- |
| Recommendation | AI 对农业处置的建议 | 诊断 response 内嵌项 | `recommendationId/diagnosisId/fieldId/action/evidenceIds/priority/decisionState/createdAt` |
| AgentAction | Agent 计划代表用户提出执行的动作 | `ProposedAction`，带 toolName/payload | `actionId/runId/recommendationId/actionType/payload/evidenceIds/approvalRequired/approvalState` |
| Approval | 人类对 Action 的决定 | 仅 action/run 状态 | `approvalId/runId/actionId/decision/actor/decidedAt/version/idempotencyKey` |
| OperationsTask | 已进入执行系统的任务 | TaskTool 写入 task 表 | `taskId/actionId/recommendationId/diagnosisId/agentRunId/lifecycle/outcome` |

规则：Recommendation 不是 Action；Action 不是 Task；Approval 不能由 Agent 自行产生；Task 只能由经过合法 Approval 的 Action 或明确人工来源创建。

## 8. Operations Task Contract V1

### 8.1 Current V1

当前字段为：`taskId/title/fieldId/diagnosisId/agentRunId/status/priority/source/createdFrom/createdBy/evidenceIds/approvedBy/approvedAt/createdAt/goal`。当前真实状态基本只有 `OPEN`；Operations Controller 仅提供 `GET /api/operations/tasks`，没有状态修改 API。

### 8.2 Commercial Target V1

```text
taskId
recommendationId?
actionId?
diagnosisId?
agentRunId?
farmId
fieldId
title
goal
priority
sourceType
createdByType
status
assignee?
deadline?
createdAt
approvedBy?
approvedAt?
startedAt?
completedAt?
result?
executionEvidenceIds?
exception?
feedback?
version
```

必须把 `actionId` 作为批准动作来源；如果任务为人工/季节性/监测来源，则 `sourceType` 明确表达，不填伪造的 Agent 关系。

## 9. Expert Review Contract V1

### 9.1 Current contract

当前 API 为：

- `GET /api/expert-review`
- `GET /api/expert-review/{diagnosisId}`
- `POST /api/expert-review/{diagnosisId}`

当前字段：`diagnosisId/status/reviewer/reviewedAt/aiDiagnosis/expertDiagnosis/evidence/recommendations/before/after`。MySQL 主键为 diagnosis_id，服务端根据 decision 写入 `IN_REVIEW/CONFIRMED/CORRECTED`。

### 9.2 Target V1

```text
reviewId
diagnosisId
farmId
fieldId
reviewerId
status: PENDING|IN_REVIEW|CONFIRMED|CORRECTED|REJECTED
decision: CONFIRM|CORRECT
aiDiagnosisSnapshot
expertDiagnosis
comment
evidenceIds
recommendationIds
before
after
version
createdAt
reviewedAt
```

`reviewId` 是一次审查版本；同一 diagnosis 可以有多次 review history。提交必须带 version，版本不一致返回 `VERSION_CONFLICT`。当前 `before/after` 是可保留的审计素材，但没有 version/history，需要后端补齐。

## 10. Historical Case Candidate / Case Contract

### 10.1 Candidate V1

```text
candidateId
diagnosisId
reviewId
farmId
fieldId
crop
imageAssetIds
expertDiagnosis
outcome
evidenceIds
qualityStatus: PENDING|READY|REJECTED|INGESTED
createdAt
createdBy
version
```

Candidate 是经专家确认、等待质量检查/索引的中间对象，绝不等同于 HistoricalCase。

### 10.2 HistoricalCase V1

```text
caseId
candidateId
farmId
fieldId
crop
metadata
imageAssetIds
embeddingReference
expertDiagnosis
outcome
indexedAt
indexStatus: PENDING|INDEXED|FAILED|REMOVED
createdAt
```

当前 Milvus 只有 `request_id` 和 vector，不能承担完整 case detail。未来需要 metadata store 与 vector store 之间通过 `caseId`/`embeddingReference` 关联。当前 `READY_EMPTY / rowCount=0` 不得展示为已有案例学习效果。

## 11. Knowledge Contract V1

### 11.1 目标对象

```text
KnowledgeSource
  sourceId
  publisher
  authorityLevel
  region
  cropScope
  sourceType
  status

KnowledgeDocument
  documentId
  sourceId
  title
  category
  crop
  region
  contentLocation
  currentVersion
  status

KnowledgeVersion
  documentId
  version
  contentHash
  effectiveDate
  updatedAt
  indexStatus: PENDING|INDEXING|INDEXED|FAILED|RETIRED
  chunkCount
  embeddingModel
```

Evidence 只引用 `documentId + version + chunkId`，不把文件名 `orchard_management.txt` 当作稳定文档身份。

### 11.2 当前边界

当前只有 classpath 文本、chunkId、contentHash、Retriever score 和本地 embedding cache。没有知识源、文档版本、索引任务或管理 API；本阶段只冻结目标，不实现上传。

## 12. State Machines

### 12.1 AgentAction / Approval

当前：`REQUIRED → APPROVED/REJECTED`，状态存储在 AgentRun payload，重复 APPROVED 仍可能进入 execute。

目标合法转换：

```text
PROPOSED → PENDING_APPROVAL
PENDING_APPROVAL → APPROVED
PENDING_APPROVAL → REJECTED
APPROVED → EXECUTING
EXECUTING → EXECUTED
EXECUTING → FAILED
```

`APPROVED/EXECUTED` 不允许回到 PENDING；重复 approve 必须返回既有结果或 `ALREADY_APPROVED`，不能再次触发 TaskTool。

### 12.2 OperationsTask

当前状态：`OPEN` only。

Target V1：

```text
OPEN → ASSIGNED → IN_PROGRESS → COMPLETED
OPEN → CANCELLED
ASSIGNED → BLOCKED
IN_PROGRESS → BLOCKED
IN_PROGRESS → CANCELLED
BLOCKED → IN_PROGRESS
```

只有角色授权的状态转换 API 可以修改生命周期；前端不能直接改 enum。

### 12.3 ExpertReview

当前：`IN_REVIEW / CONFIRMED / CORRECTED`，无版本历史。

Target：`PENDING → IN_REVIEW → CONFIRMED|CORRECTED|REJECTED`，每次修改生成 review history，必须通过 version compare-and-set。

### 12.4 HistoricalCaseCandidate

TARGET ONLY：`CREATED → QUALITY_REVIEW → READY → INGESTING → INGESTED`；失败进入 `REJECTED/FAILED`，不能从 REJECTED 直接冒充 INDEXED。

### 12.5 KnowledgeDocument Index

TARGET ONLY：`DRAFT → PENDING_INDEX → INDEXING → INDEXED`；异常进入 `FAILED`，旧版本可 `RETIRED`。

## 13. Write Safety / Idempotency

| 操作 | Idempotency key | 唯一边界 | 重复行为 | 当前状态 |
| --- | --- | --- | --- | --- |
| Approve Action | `runId:actionId:approve` | DB `run_id + action_id` | 返回已有 Approval 或 `ALREADY_APPROVED`；绝不再执行写 Tool | P0 缺失 |
| Create Operations Task | `actionId` 或 `approvedActionId` | `operations_tasks.action_id` unique | 返回同一 taskId；payload 不同返回 conflict | 当前无 actionId，随机 UUID |
| Expert Review Submit | `reviewId:version` 或 diagnosisId+version | diagnosis/version unique | 同 payload 幂等成功，不同 payload `VERSION_CONFLICT` | 当前 diagnosisId upsert，无版本 |
| Historical Case Ingest | `candidateId` / caseId+assetHash | candidateId/caseId unique + Milvus PK | 返回已有 case/index result | 当前仅进程 key `caseId:sha(image)` |
| Knowledge Document Index | `documentId:version` | document version + index job unique | 返回已有 job/status | 当前不存在 |

### Approval P0 固定行为

同一 `runId + actionId` 只能产生一个有效批准结果和最多一个 OperationsTask。重复请求可以返回已有结果或明确冲突/幂等成功，但不能再次执行任何写操作。数据库唯一约束和事务状态比较是第一保障；Redis 只能辅助，不能作为唯一一致性保障。

## 14. Concurrency / Transaction Requirements

| 对象 | 要求 |
| --- | --- |
| Approval | `SELECT … FOR UPDATE` 或原子 compare-and-set；唯一约束 `(runId, actionId)`；审批状态与 task 创建必须在可证明的一致性边界内 |
| AgentAction | 版本字段；只允许 PENDING_APPROVAL→APPROVED 一次 |
| OperationsTask | actionId 唯一；task create 与 approved effect 必须可恢复、可重试且不重复 |
| ExpertReview | `version` 乐观锁；两个专家修改同一版本时返回 VERSION_CONFLICT |
| Task State | compare-and-set 合法转换，拒绝过期状态写入 |
| HistoricalCandidate | candidate 状态与 ingest job 状态分离；同一 candidate 只能有一个有效索引结果 |
| KnowledgeVersion | document version immutable；索引状态通过 job/version 原子更新 |

本阶段不实现事务、锁或 schema，只冻结为 Backend V2 要求。

### 14.1 Commercial Error Model

未来 API 错误统一表达为：

```text
code
message
retryable
resourceId?
timestamp
details?
```

至少冻结以下语义：`CONFLICT`、`VERSION_CONFLICT`、`ALREADY_APPROVED`、`ALREADY_EXECUTED`、`RESOURCE_NOT_FOUND`、`SERVICE_UNAVAILABLE`、`VALIDATION_FAILED`。重复请求不能只返回普通 500，也不能用成功空响应掩盖是否已经产生写入。

## 15. RBAC Proposal

| 角色 | 查看 Diagnosis/Evidence | 提议 Action | Approve/Reject | 创建/更新 Task | Expert Review | Knowledge 管理 | Historical Candidate |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Farm Manager | own farm | 可请求 Agent | approve/reject own farm action | view/assign/update | view | view | view |
| Agronomist / Expert | permitted farms | 可提出专业建议 | 按策略可审批 | view/feedback | review/correct | view/contribute（未来） | quality review |
| Admin | all | all | all with audit | all | all | source/document/index | candidate/case admin |
| System / Agent | context scoped | propose only | 禁止自行 approve | create only after approved action | no human judgment | retrieve/index worker only | ingest worker only |
| Viewer | permitted read | no | no | read only | read only | read only | read only |

所有写 API 必须由后端校验角色、资源所属 farm、状态转换和版本，不能只依赖前端隐藏按钮。

## 16. Audit Event Proposal

统一事件结构：

```text
eventId
eventType
actorType: USER|EXPERT|ADMIN|AGENT|SYSTEM
actorId
resourceType
resourceId
farmId?
fieldId?
runId?
actionId?
before
after
reason?
idempotencyKey?
occurredAt
correlationId
```

必须审计：

- `AgentActionProposed`
- `ActionApproved`
- `ActionRejected`
- `OperationsTaskCreated`
- `TaskStateChanged`
- `ExpertReviewSubmitted`
- `ReviewCorrected`
- `HistoricalCandidateCreated`
- `HistoricalCaseIndexed`
- `KnowledgeDocumentUpdated`
- `KnowledgeVersionIndexed`

审计要求回答 who / when / what changed / before / after；本阶段不创建 Audit Service。

## 17. API Gap Analysis

### 17.1 当前 API

| 领域 | 当前 API | 说明 |
| --- | --- | --- |
| Diagnosis | `POST /diagnosis/analyze` | 返回 run_id、diagnosis、claims、evidence、recommendations；不持久化 Diagnosis |
| Agent | `POST /api/agent/runs`、GET run、execute、approve、reject、cancel、events | 运行时完整，但 Approval 写安全不足 |
| Operations | `GET /api/operations/tasks` | 仅列表，无创建/状态转换/详情 |
| Expert Review | GET list/detail、POST save | 以 diagnosisId 主键 upsert，无 review history/version |
| Historical | `POST /api/historical-cases/ingest?execute=` | ingest map，非 Candidate API |
| Knowledge | 无管理 API | Retriever 内部 classpath 能力 |
| Runtime | `GET /api/runtime/health` | 报告 Agent、Operations、Historical、Knowledge 简要状态 |

### 17.2 Target minimal API Proposal

不在本阶段实现，只作为 Backend V2 设计输入：

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

首批只需要先落地 Diagnosis persistence、Approval safety、Operations task detail/transition、Review queue/detail/history/submit 和 Candidate list/detail；Knowledge CRUD 与 Case Search 可后置。

## 18. Contract Gap Matrix

| Object | Current Contract | Target V1 | Gap | Priority | Dependent Product | Backend Change Required |
| --- | --- | --- | --- | --- | --- | --- |
| Diagnosis | run_id response only | persisted diagnosisId + context/status | no durable identity | P0 | Diagnosis/Review/Task | yes |
| Evidence | DiagnosisEvidenceVO with score | provenance/freshness/availability contract | missing stable source/time semantics | P1 | RAG/Diagnosis/Agent | yes |
| Recommendation | nested id/title/detail | independent recommendationId/state | no persistence or task link | P1 | Overview/Agent | yes |
| AgentRun | file/MySQL JSON payload | durable version/correlation | persistence selectable, no domain FK | P1 | Agent | yes |
| AgentAction | ProposedAction in run | stable action + evidence + result | no actionId provenance fields | P0 | Approval/Task | yes |
| Approval | state only | approval record + idempotency | duplicate approve writes risk | P0 | Write safety | yes |
| OperationsTask | 15 fields, OPEN | lifecycle + source IDs + outcome | no actionId/state APIs | P0 | Operations | yes |
| ExpertReview | diagnosisId upsert | reviewId/history/version/audit | concurrent edit unsafe | P1 | Expert Center | yes |
| HistoricalCaseCandidate | ingest map | candidate entity and quality lifecycle | no candidate boundary | P1 | Expert/Case | yes |
| HistoricalCase | Milvus request_id/vector | case metadata + index status | no detail/search contract | P2 | RAG/Case | yes |
| KnowledgeSource | none | source publisher/authority | absent | P2 | Knowledge | yes |
| KnowledgeDocument | classpath file/chunkId | document/version/index status | absent | P2 | Knowledge/RAG | yes |

## 19. Knowledge Graph Future Compatibility

本阶段不创建 Neo4j schema、Cypher 或 Graph extraction pipeline。Domain Model 只要求：

- Crop、Disease、Symptom、Treatment、GrowthStage、WeatherCondition、KnowledgeDocument 未来都能拥有稳定 ID。
- Evidence provenance 使用 source/document/version IDs，而不是把全文复制到关系字段。
- Diagnosis/Recommendation/Case 通过 IDs 和 metadata 关联，避免把业务关系编码在 title 或自然语言中。
- `KnowledgeDocument` 的 category/crop/region/effectiveDate 保持结构化，为未来 Graph RAG 建立节点属性基础。

Graph RAG 未来应服务“作物—症状—病害—处置—证据—结果”的可解释关联查询，不能成为当前知识库管理的替代品。

## 20. Redis / MQ / Spring Cloud Assessment

| 技术 | NOW | NEXT | LATER | 判断 |
| --- | --- | --- | --- | --- |
| Redis | NOT YET | short-lived Agent state/cache、rate limit | distributed lock 辅助 | 不能作为 Approval 一致性唯一保障 |
| MQ | NOT YET | Agent long-running、embedding/indexing、notification 在有真实异步 SLA 后 | 跨模块事件分发 | 不把所有同步流程消息化 |
| Spring Cloud | NOT YET | 只有独立部署/资源隔离/团队边界明确时评估 | 高并发 Agent/RAG worker 拆分 | 当前单体模块边界尚可 |
| Graph DB | NOT YET | 有稳定 Knowledge contract 和查询需求后 | Graph RAG | 当前数据量与契约不足 |

## 21. Modular Monolith vs Microservices Decision

当前选择：**Modular Monolith First**。

依据：

1. 当前领域对象的稳定 ID、事务边界和审计 contract 尚未冻结完成，拆服务会把不清晰的关系扩散为网络协议。
2. Diagnosis、Agent、Approval、Operations、Expert Review 需要强一致的状态和幂等边界，先在单体内建立可测试事务更安全。
3. 当前 persistence 仍有 file/fallback、动态建表和 Milvus sidecar，尚不足以支撑微服务运维复杂度。
4. 只有当 Agent/RAG/Indexing 出现独立扩缩容、故障隔离、资源争用或团队独立部署需求时，才触发 Spring Cloud 评估。

触发微服务的条件：独立部署频率、明确团队边界、可观测性成熟、跨服务数据契约稳定、异步补偿可证明、资源隔离收益大于事务复杂度。

## 22. Backend V2 Priority Roadmap

### P0 — Write Safety Foundation

1. Approval duplicate approve：`runId + actionId` unique、compare-and-set、幂等结果。
2. 禁止重复 TaskTool write effect；Action→Task 写入需 actionId 唯一。
3. 明确 Approval/Task transaction boundary、错误模型和 recovery。
4. 验证 MySQL persistence，取消关键生产写入的静默 fallback。
5. 补充统一错误码：CONFLICT、ALREADY_APPROVED、ALREADY_EXECUTED、VERSION_CONFLICT、SERVICE_UNAVAILABLE、VALIDATION_FAILED。

### P1 — Product Loop Integrity

1. Diagnosis persistence 与独立 diagnosisId。
2. Recommendation→Action→Approval→Task stable relationship。
3. Operations lifecycle、合法 transition、task detail 和 outcome。
4. Expert Review version/history/audit/concurrency。
5. HistoricalCaseCandidate API 与质量状态。

### P2 — Knowledge and Retrieval Productization

1. KnowledgeSource/Document/Version 管理。
2. Knowledge index job/status 和检索 provenance。
3. Historical Case detail/search/index result。
4. Unified Evidence provenance/freshness contract。

后续才评估 Agent V2、RAG V2、Knowledge Graph、Redis/MQ 和基础设施拆分。

## 23. Production Release Gates

生产发布前必须满足：

- **P0：Approval duplicate approve 已修复并有并发测试。** 同一 `runId + actionId` 最多一个有效批准结果、最多一个 OperationsTask。
- **P0：Production Write 从 NO-GO 转为 GO 前，不能依赖前端按钮隐藏。** 后端必须鉴权、校验状态、记录审计和返回明确重复结果。
- AgentRun、Action、Approval、Task 可以通过稳定 ID 完成端到端追踪。
- Task 创建具备 actionId 唯一约束和事务/补偿策略。
- Diagnosis 有持久化 diagnosisId，不再把 runId 冒充业务诊断身份。
- Expert Review 有 reviewerId、version、before/after、history 和 VERSION_CONFLICT。
- Historical Candidate 与 Historical Case 分离，Milvus 写入有 idempotency 和 ingest status。
- Knowledge 文档有 source/document/version/indexStatus，Evidence 可追溯到版本。
- Data Availability State 与 Domain Lifecycle State 分开；`STALE` 不能进入 OperationsTask.status。
- REAL/DEMO/EMPTY/UNAVAILABLE/STALE 语义经过 API 和 UI 联合验收。

当前上述 P0 未完成，因此 Production Write 继续 **NO-GO**。

## 24. Files Created / Changed

本里程碑只创建架构/合同文档：

- `docs/commercial-contract-hardening-result.md`
- `docs/commercial-domain-model-v1.md`
- `docs/backend-contract-gap-analysis.md`
- `docs/knowledge-expert-contract-v1.md`
- `docs/product-write-safety-requirements.md`

未修改运行代码、前端页面、Backend、数据库、Agent Runtime、RAG、Milvus、Redis、MQ 或基础设施。未创建 commit。

## 25. Go / No-Go

### Contract Freeze 判断

**Commercial Product Contract V1：GO，可冻结为设计基线。**

冻结范围包括：核心对象、稳定 ID 原则、Evidence 语义、Recommendation/Action/Approval 分离、Operations 生命周期目标、Expert Review 版本、Historical Candidate/Case 边界、Knowledge contract、审计、RBAC、错误模型、幂等、并发和 Backend V2 优先级。

### 运行实现判断

- Backend V2 Implementation：NO-GO，等待设计确认。
- Production Write：NO-GO，Approval duplicate approve 是未解决 P0。
- Knowledge Graph / Redis / MQ / Spring Cloud：NOT YET。

Commercial UI + Contract Definition 可以整体冻结。下一阶段只建议：

**Milestone 5.2 — Backend / Product Loop V2 Hardening**

本报告完成后停止，不进入 Backend Implementation、Knowledge Graph、RAG V2、Agent V2、Operations lifecycle implementation 或论文实验。
