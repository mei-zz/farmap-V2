# Milestone 5.2.3 Stable Product Loop Relationships Result

本阶段将 Recommendation、AgentRun、AgentAction、Approval 和 OperationsTask 串成可验证的商业产品关系链。实现采用现有 Diagnosis 持久化快照承载 Recommendation 身份，没有新增独立 Recommendation 表，也没有改变 RAG、Agent Planner、Operations 生命周期或 Expert Review V2。

## 1. Reality Audit

阶段开始时 Diagnosis payload 已经包含稳定的 `recommendations[].id`，但 AgentContext 只有 `diagnosisId`，AgentRun 没有 Recommendation 来源字段，AgentAction 只有 actionId，OperationsTask 只有 diagnosisId/agentRunId/actionId。Approval 以 `(runId, actionId)` 持久化，但无法从任务反查具体 Recommendation。

## 2. Current Relationship Graph

```text
Diagnosis diagnosisId
└── persisted snapshot recommendations[].id = recommendationId
    └── AgentRun runId + diagnosisId + optional sourceRecommendationId
        └── AgentAction actionId + diagnosisId + optional recommendationId
            └── Approval (runId, actionId)
                └── OperationsTask taskId + actionId + agentRunId + diagnosisId + nullable recommendationId
```

Diagnosis 级 AgentRun 可以不携带 `sourceRecommendationId`；此时后续 Action/Task 的 Recommendation provenance 保持 null，而不是猜测第一条建议。

## 3. Root Problems

原有关系依赖上下文 map、动作标题或执行顺序，无法证明一个写入任务确实来自某个 Recommendation。标题相同、跨 Diagnosis 使用同一 recommendation ID、客户端覆盖字段和 LLM 返回伪造 action ID 都可能产生错误链路。OperationsTask 也没有 Recommendation 列，重复批准只能靠 actionId 维持幂等，无法完成产品级追踪。

## 4. Architecture Decision

采用**方案 A：snapshot-backed Recommendation identity**。Recommendation 继续作为 Diagnosis 分析快照中的子资源，不新增 Recommendation 表或独立生命周期。创建 Recommendation-specific AgentRun 时，服务端使用 `diagnosisId + persisted snapshot` 精确解析 `recommendationId`；解析不到时 fail closed。这样保留 5.2.2 的不可变证据和 Recommendation 快照，同时避免在本阶段引入新的 CRUD、同步和迁移复杂度。

## 5. Recommendation Identity

- `diagnosisId` 是稳定业务诊断身份，由 5.2.2 服务端生成。
- `recommendationId` 只接受持久化 Diagnosis payload 中的精确 ID。
- 不使用 title、field、detail、数组位置或文本相似度匹配。
- 同一 Recommendation 可以产生多个 AgentAction，因此没有 `recommendation_id` 唯一约束。
- 客户端或 LLM 提交的 Recommendation 字段不能替代服务端已解析的来源。

## 6. Diagnosis→Recommendation

`DiagnosisPersistenceService.findRecommendation(diagnosisId, recommendationId, userId)` 从已持久化快照解析 Recommendation，并同时执行用户权限边界。跨诊断 ID、未知 ID 或错误用户均返回空结果；不会重新执行 RAG，也不会从另一个 Diagnosis 借用同名建议。

## 7. Recommendation→AgentRun

`AgentContext.sourceRecommendationId` 表示显式 Recommendation 来源，null 表示 Diagnosis-level run。`AgentRuntime.createRun(..., userId)` 在真实模式下验证 Diagnosis 和 Recommendation 快照归属，并把 `diagnosisId`、`sourceRecommendationId` 写入 AgentRun context、metadata 和 `run.created` trace。缺少 diagnosisId 的 Recommendation-specific run 直接返回 `VALIDATION_FAILED`。

## 8. AgentRun→AgentAction

服务端为写 Action 生成唯一 `action-<runId>-<uuid>`，并从 AgentRun context 注入 diagnosisId、recommendationId 和当前证据 evidenceIds。只读工具返回的 ProposedAction 经过 `normalizeAction`；其 provenance 与运行上下文不一致时拒绝，空字段只能由服务端补齐，不能由模型覆盖。

## 9. AgentAction→Approval

Approval 继续使用 `(runId, actionId)` 作为持久化和幂等键。批准前再次核对 Action 与 AgentRun provenance；不一致时返回 `PROVENANCE_CONFLICT`。批准、执行和重复批准的 trace 均保留 runId/actionId 以及可用的 diagnosisId/recommendationId。

## 10. Approval→OperationsTask

批准后的 AgentToolRequest 携带服务端生成的 actionId、diagnosisId、recommendationId。OperationsTaskService 将这些字段写入任务；已有 actionId 幂等查询优先返回原任务，因此重复批准不会创建第二个任务。Recommendation provenance 是 nullable，Diagnosis-level 任务可以合法为空。

## 11. Final Provenance Chain

生产链路现在可验证为：

```text
diagnosisId
  → persisted recommendations[recommendationId]
  → AgentRun(runId, diagnosisId, sourceRecommendationId)
  → AgentAction(actionId, diagnosisId, recommendationId, evidenceIds)
  → Approval(runId, actionId)
  → OperationsTask(taskId, actionId, agentRunId, diagnosisId, recommendationId)
```

每条边都有服务端可检查的身份，不依赖标题、字段名或 LLM 文字。

## 12. Data Model Changes

后端新增或补齐：

- `AgentContext.sourceRecommendationId`
- `AgentToolRequest.diagnosisId/recommendationId`
- `ProposedAction.diagnosisId/recommendationId/evidenceIds`
- OperationsTask 结果和持久化行的 `recommendationId`
- ProductLoopRelationException 及 `VALIDATION_FAILED`、`DIAGNOSIS_NOT_FOUND`、`RECOMMENDATION_NOT_FOUND`、`PROVENANCE_CONFLICT` 错误码

前端 AgentAction、AgentContext 和 Operations task adapter 同步声明这些可选字段，兼容旧响应。

## 13. Schema / Migration

新增 `zhgy/zhgy/src/main/resources/db/migration/V5_2_3__product_loop_relationships.sql`：为 `agent_operations_tasks` 增加 nullable `recommendation_id` 和查询索引。没有 Recommendation 表，没有 Recommendation 唯一索引，也没有删除或回填旧任务。运行时兼容桥会在旧动态表缺列时尝试添加列；部署仍需执行版本化 SQL。

## 14. Server-side Validation

服务端验证点包括：

1. Agent API 从认证用户上下文读取 userId，并验证 Diagnosis snapshot 所属用户。
2. Recommendation 必须同时携带 diagnosisId，并且精确存在于该 Diagnosis payload。
3. Action provenance 必须与 AgentRun context 相等；服务端重新生成 actionId。
4. Operations 写边界拒绝 request provenance 与 AgentContext 不一致的请求，包括 context 缺失的客户端 diagnosis/recommendation 覆盖。
5. actionId 继续作为 OperationsTask 唯一幂等键。

## 15. Invalid Handling

无效关系 fail closed，不创建 AgentRun、Action 或 Task：

- 缺少 Diagnosis：`VALIDATION_FAILED`
- Diagnosis 不存在或非当前用户：`DIAGNOSIS_NOT_FOUND`
- Recommendation 不在该 Diagnosis 快照：`RECOMMENDATION_NOT_FOUND`
- Action/Task request provenance 冲突：`PROVENANCE_CONFLICT`

AgentController 将关系错误返回 HTTP 422，并提供 diagnosisId/recommendationId 供客户端定位问题。LLM 的 action ID、title 或 recommendation 字段不会绕过这些检查。

## 16. Legacy

旧 AgentRun、旧 task、demo 数据和没有 Recommendation 的任务继续可读。旧 OperationsTask 的 `recommendation_id` 保持 null；没有按标题或第一条 Recommendation 猜测回填。`a12-demo` 和既有 mock 运行继续走兼容路径，不伪造生产快照关系。

## 17. Audit / Trace

`run.created`、`approval.required`、`approval.approved` 和 `operations.task.created` trace 现在携带可用的关系字段。AgentRun metadata 保存 diagnosisId/sourceRecommendationId，OperationsTask 返回 actionId、agentRunId、diagnosisId、recommendationId，便于前端 Evidence、Approval 和 Operations 页面展示同一条业务链。

## 18. Files

本阶段核心新增或修改文件：

- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/ProductLoopRelationException.java`
- `AgentContext.java`、`AgentToolRequest.java`、`ProposedAction.java`、`AgentRuntime.java`、`AgentDiagnosisRequestFactory.java`
- `DiagnosisPersistenceService.java`
- `OperationsTaskService.java`
- `AgentController.java`、`WebMvcConfiguration.java`
- `V5_2_3__product_loop_relationships.sql`
- `farmap-frontend-dev/src/features/agent/contract.ts`、`PageContextAdapter.ts`、`store/aiContext.ts`
- `farmap-frontend-dev/src/types/operations.ts`、`features/operations/adapter.ts`
- `ProductLoopRelationshipTest.java`、`ProductLoopRelationshipMysqlTest.java`
- 本结果文档

此前 Milestone 5.0–5.2.2 的未提交改动均保留，没有回滚或重写其业务范围。

## 19. Tests

通过以下后端回归：

- `ProductLoopRelationshipTest`：5 tests，快照解析、跨诊断拒绝、Diagnosis-level run、Action/Task provenance、幂等、客户端/LLM 覆盖拒绝、legacy null。
- `DiagnosisPersistenceTest`：5 tests，5.2.2 稳定身份和快照回归。
- `DiagnosisAgentLinkTest`：AgentContext stable diagnosis link。
- `ProductionWriteSafetyTest`：7 tests，5.2.1 Approval/Task 幂等、并发、拒绝和 fail-closed 回归。
- `ExpertReviewIntegrationTest`：5.2.2/Expert link 回归。

后端 `mvn -DskipTests compile` 通过。针对本阶段和前置里程碑的 Maven 测试使用低内存、`-DforkCount=0` 运行通过；仓库全量测试仍受既有大依赖环境和 `LocalVisionServiceTest` fork JVM 资源问题影响，因此不把未运行的全量结果宣称为通过。

## 20. 5.2.1/5.2.2 Regression

5.2.1 的 Durable Approval、重复批准、并发 claim、Task actionId 幂等和无数据库 fail-closed 行为保持通过。5.2.2 的 Diagnosis 独立身份、sourceRunId、持久化 snapshot、Evidence/Claim/Recommendation ID 稳定性和用户边界保持通过。

## 21. MySQL

使用本地 MySQL 和 `FARMAP_AGENT_PERSISTENCE=mysql` 通过：

- `ProductLoopRelationshipMysqlTest`
- `DiagnosisPersistenceMysqlTest`
- `ProductionWriteSafetyMysqlTest`

验证了 Diagnosis snapshot 重载、Recommendation 精确解析、`recommendation_id` 落库、legacy null 可读、同 actionId 重试返回同 task，以及 5.2.1 Approval 状态持久化。测试行已清理；当前表包含 `action_id` 和 `recommendation_id` 列。

## 22. HTTP E2E

在独立端口启动当前源码并使用 MySQL 完成真实 HTTP 链路：

1. 鉴权 `POST /diagnosis/analyze` 返回 `diagnosisId` 和 Recommendation snapshot。
2. 鉴权 `POST /api/agent/runs` 创建 Recommendation-specific run。
3. 轮询 `GET /api/agent/runs/{runId}` 到 `WAITING_FOR_APPROVAL`，确认 Action 携带正确 diagnosisId/recommendationId/evidenceIds。
4. `POST /api/agent/runs/{runId}/actions/{actionId}/approve` 返回 `COMPLETED`，OperationsTask 携带 taskId/actionId/agentRunId/diagnosisId/recommendationId。
5. 重复批准返回同一 taskId；`GET /api/operations/tasks` 读取同一持久化关系。

本机 Milvus/Mongo 不可用时，Agent 保持已有 `UNAVAILABLE`/fallback 语义，未影响本阶段 provenance E2E。

## 23. Frontend

前端契约和 adapter 已支持 `sourceRecommendationId`、Action 的 diagnosis/recommendation/evidence IDs 以及 OperationsTask recommendationId。`npx tsc -b`、本阶段修改文件定向 ESLint 和 `npm run build` 均通过。没有进行 UI redesign，也没有泄漏 model name、tool trace 或其他调试字段到新产品结构之外。

## 24. Risks

- 当前 Recommendation 仍是 Diagnosis JSON snapshot 子资源，后续若需要独立编辑、生命周期或跨诊断复用，必须另立数据模型和迁移。
- SQL migration 目前没有统一自动 runner，发布时必须审计 V5.2.1、V5.2.2、V5.2.3 执行状态。
- 历史 legacy task 没有 Recommendation provenance，产品应明确显示“来源不可用”，不能补猜。
- Mongo/Milvus/模型网关的可用性仍影响 AI 证据质量，但不改变本阶段关系验证结果。

## 25. Release Gates

发布前必须满足：

1. 在目标 MySQL 按顺序执行并审计 `V5_2_1__production_write_safety.sql`、`V5_2_2__diagnosis_persistence.sql`、`V5_2_3__product_loop_relationships.sql`。
2. 设置 `FARMAP_AGENT_PERSISTENCE=mysql`，生产禁止 AgentRun、Approval 或 OperationsTask 静默降级到内存。
3. 通过 Recommendation 跨 Diagnosis 拒绝、跨用户拒绝、Action provenance 冲突、重复批准幂等和 legacy null 读取测试。
4. 前端生产环境验证 Agent/Diagnosis/Operations API 的认证和 base URL 代理。
5. 对 Mongo/Milvus/模型网关配置健康检查，并在外部证据不可用时显示清晰 freshness/status。

## 26. Go / No-Go

**Milestone 5.2.3：GO（代码与验证层面）。** 当前已具备 snapshot-backed Recommendation identity、Diagnosis→AgentRun→AgentAction→Approval→OperationsTask 的稳定关系、服务端 fail-closed 校验、MySQL 落库和 HTTP E2E；5.2.1/5.2.2 回归与前端构建通过。

**生产发布：CONDITIONAL GO。** 只有在目标环境执行三份 migration、确认 `FARMAP_AGENT_PERSISTENCE=mysql`、配置依赖健康检查并完成认证回归后才可发布。下一阶段仅建议进入 **Milestone 5.2.4 — Operations Lifecycle & Outcome Foundation**，建立任务状态、执行结果和异常闭环；本阶段不继续扩展其他 Agent、RAG 或知识库功能。
