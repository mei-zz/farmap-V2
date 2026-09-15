# FarMap Commercial Domain Model V1

本文件是 Milestone 5.1.6 的领域模型冻结附件。完整 Reality Audit、Gap Matrix、状态机和路线图见 [commercial-contract-hardening-result.md](./commercial-contract-hardening-result.md)。

## Canonical Chain

```text
Farm → Field → Observation/Context → Diagnosis
Diagnosis → Finding / Evidence / Recommendation
Recommendation → AgentRun → AgentAction → Approval → OperationsTask → Outcome
OperationsTask → ExpertReview → HistoricalCaseCandidate → HistoricalCase
KnowledgeSource → KnowledgeDocument → KnowledgeVersion → Evidence
```

## Identity Rules

| Object | Canonical ID | Current reality | Freeze decision |
| --- | --- | --- | --- |
| Farm | farmId | MySQL `farm.id: Integer` | 保留 |
| Field | fieldId | 多为 `AgentContext`/Demo 字符串，无独立 Field entity | 必须持久化并受 farmId 约束 |
| Observation | observationId | 不存在 | Backend V2 target |
| Diagnosis | diagnosisId | 当前 response 只有 `run_id` | 新增独立身份；runId 仅 sourceRunId |
| Finding | findingId | Claim.id | 可从 Claim 演进 |
| Evidence | evidenceId | DiagnosisEvidenceVO.id | 跨域冻结 |
| Recommendation | recommendationId | response 内嵌 id | 独立稳定 ID，不等于 Action |
| AgentRun | runId | AgentRun.runId | 保留 |
| AgentAction | actionId | ProposedAction.id | 增加 provenance/evidence/result/version |
| Approval | approvalId | 无实体，仅 approvalState | 新增唯一边界 `(runId, actionId)` |
| OperationsTask | taskId | 动态 MySQL/UUID | 保留，增加 actionId/recommendationId |
| Outcome | outcomeId | 不存在 | Backend V2 target |
| ExpertReview | reviewId | 当前以 diagnosisId 主键 | diagnosisId 作为 subject，reviewId 作为版本身份 |
| Candidate | candidateId | ingest Map 临时字段 | 独立中间对象 |
| HistoricalCase | caseId | Milvus request_id/vector | metadata store + vector reference |
| KnowledgeSource | sourceId | 不存在 | 目标对象 |
| KnowledgeDocument | documentId | classpath 文件名 | 目标对象 |
| KnowledgeVersion | documentId + version | 不存在 | 目标索引和审计单位 |

## Semantic Separation

- Recommendation = AI 建议。
- AgentAction = Agent 提议执行的动作。
- Approval = 人类对 Action 的决定。
- OperationsTask = 进入执行系统的任务。
- ExpertReview = 专家对 Diagnosis 的审查/修正。
- HistoricalCaseCandidate = 满足质量条件但尚未入库的候选。
- HistoricalCase = 已成功索引并可被检索的真实案例。

禁止通过 title、field name、文本相似度、数组下标或前端猜测建立关系。所有关系通过稳定 ID、版本和 provenance 建立。

## State Separation

`REAL/DEMO/STALE/UNAVAILABLE/EMPTY` 是 Presentation/Data Availability State，不是领域生命周期。`OperationsTask.status` 只能使用任务生命周期；`STALE` 只表示数据新鲜度。`Historical READY_EMPTY` 表示存储可用但 rowCount=0，不表示已有案例。

## Freeze Outcome

Commercial Product Contract V1 可冻结为 Backend V2 的设计输入。运行实现仍为 NO-GO，尤其是 Approval duplicate approve 与 Production Write。
