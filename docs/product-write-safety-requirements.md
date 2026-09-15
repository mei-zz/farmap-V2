# FarMap Product Write Safety Requirements

本文件是 Milestone 5.1.6 的生产写入门禁。当前只定义要求，不修改 ApprovalManager、Agent Runtime、Database 或 API。

## P0 Approval Idempotency

同一个 `runId + actionId`：

1. 只能产生一个有效 Approval 结果。
2. 最多产生一个对应 OperationsTask。
3. 重复请求必须返回已有结果，或返回 `ALREADY_APPROVED` / `ALREADY_EXECUTED` / 明确 conflict。
4. 绝不能再次调用 TaskTool 或产生第二次写操作。

推荐持久化边界：

```text
agent_approvals
  approval_id
  run_id
  action_id
  decision
  actor_id
  decided_at
  version
  idempotency_key
UNIQUE(run_id, action_id)
```

`ApprovalManager` 当前只修改内存中的 `approvalState`，随后 `AgentRuntime.executeApprovedAction()` 直接执行 Tool，存在重复 approve 风险。因此 Production Write 保持 NO-GO。

## Write Operation Matrix

| Operation | Key | Uniqueness | Duplicate response |
| --- | --- | --- | --- |
| Approve Action | `runId:actionId:approve` | `(runId, actionId)` | existing approval / ALREADY_APPROVED |
| Create Task | approved `actionId` | `operations_tasks.action_id` | existing taskId |
| Expert Review Submit | `reviewId:version` | review/version | idempotent same payload / VERSION_CONFLICT |
| Historical Ingest | candidateId or caseId+assetHash | candidate/case | existing index result |
| Knowledge Index | documentId:version | document version/job | existing job/status |

## Transaction Requirements

- Approval 状态改变必须 compare-and-set。
- Action approved 与 task creation 必须具备一致性、恢复和补偿边界。
- Task lifecycle 必须拒绝过期状态更新。
- Expert Review 必须 optimistic locking。
- Candidate 状态与 Milvus index job 状态必须分离。
- KnowledgeVersion immutable；索引由 versioned job 更新。

## Error Model

```text
code
message
retryable
resourceId
timestamp
details
```

至少支持：`CONFLICT`、`VERSION_CONFLICT`、`ALREADY_APPROVED`、`ALREADY_EXECUTED`、`RESOURCE_NOT_FOUND`、`SERVICE_UNAVAILABLE`、`VALIDATION_FAILED`。

## Audit Event Requirements

所有写动作记录：`eventId/eventType/actorType/actorId/resourceType/resourceId/before/after/reason/idempotencyKey/occurredAt/correlationId`。

必须记录：Action Proposed、Action Approved、Action Rejected、Task Created、Task State Changed、Expert Review Submitted、Review Corrected、Historical Candidate Created、Historical Case Indexed、Knowledge Document Updated。

## Release Gate

Approval duplicate approve 未修复前，任何 Production Write、自动 Task 创建和 Historical/Knowledge 写入都不能宣布 GO。Redis 可以辅助短期锁或 token，但不能替代数据库唯一约束、事务和审计。
