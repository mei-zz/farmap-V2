# FarMap V2 — Milestone 5.2.4 Operations Lifecycle & Outcome Foundation

审查与实现日期：2026-09-06  
分支：`feature/commercial-product`  
范围：Operations 生命周期、Outcome、审计、权限、持久化和端到端验证。未进入 Milestone 5.2.5 Expert Review V2。

## 1 Reality Audit

本阶段开始时，工作区已经包含一版 5.2.4 Operations 初始实现，但它仍有产品化缺口：列表和详情读取没有统一 actor/ownership 过滤，生产数据库读取失败可能被错误降级为空列表，MySQL 测试只覆盖基本持久化，完成审计事件的时间精度不足以稳定表达写入顺序。

本阶段以当前代码和实际数据库 schema 为准收紧边界。现有 `agent_operations_tasks` 是旧任务表；`agent_diagnoses`、`agent_runs`、`agent_approvals` 分别承载 Diagnosis、Agent Run 和审批事实。5.2.4 没有改变 Diagnosis/Agent/RAG 的身份不变量。

## 2 Previous Operations Model

此前 Operations 主要是 Agent 产生的一条展示记录：创建后写入 `agent_operations_tasks`，页面读取任务列表，状态字段缺少服务端命令语义，完成结果没有稳定的 Outcome 关系，审计也不能可靠重放完整生命周期。数据库不可用时的内存兼容路径还可能掩盖生产持久化故障。

## 3 Root Problems

1. Task 状态没有形成服务端权威状态机，客户端可以把“完成”理解为一次普通更新。
2. 完成结果没有唯一的业务身份，重复请求可能形成多个结果或无法判断是否已经完成。
3. 状态、Outcome、审计事件不是一个清晰的事务边界。
4. 并发完成与取消缺少明确的赢家规则。
5. 读取接口没有统一按认证 actor 过滤 owner，生产读取故障会产生误导性空结果。
6. 旧任务没有生命周期列时，系统不能猜测历史状态或历史时间。

## 4 Architecture Decision

保留现有 Operations 表作为任务事实来源，在其上增加生命周期字段；新增 `agent_operation_outcomes` 作为一个 Task 至多一个 canonical Outcome 的结果表；新增 `operations_task_events` 作为 append-only 审计表。`OperationsTaskService` 是唯一生命周期写边界，HTTP Controller 只负责解析命令和映射错误。

生产模式使用应用 DataSource 和 JDBC 事务；只有显式无 DataSource 的直接本地构造才允许测试/demo fallback。生产读取或写入数据库失败统一返回 `OPERATIONS_PERSISTENCE_UNAVAILABLE`，不再静默返回空列表。

## 5 Task State Model

任务状态固定为：

`OPEN → ASSIGNED → IN_PROGRESS → COMPLETED`

任一可执行前状态可以在业务允许时进入 `CANCELLED`；`COMPLETED` 和 `CANCELLED` 都是终态。本阶段不引入 `BLOCKED`，避免在没有阻塞原因、恢复策略和产品展示语义时伪造状态。

## 6 Transition Matrix

| 当前状态 | ASSIGNED | IN_PROGRESS | COMPLETED | CANCELLED |
| --- | --- | --- | --- | --- |
| OPEN | 允许 | 允许直接开始 | 禁止 | 允许 |
| ASSIGNED | 禁止重复分配 | 允许 | 禁止跳过执行 | 允许 |
| IN_PROGRESS | 禁止 | 禁止重复开始 | 允许 | 允许 |
| COMPLETED | 禁止 | 禁止 | 仅幂等读取已有 Outcome | 禁止 |
| CANCELLED | 禁止 | 禁止 | 禁止 | 禁止 |

非法转换由服务端返回 `INVALID_TASK_TRANSITION`，HTTP 状态为 409，并带上 `taskId`、`currentStatus` 和 `requestedTransition`。

## 7 Assignment Semantics

`POST /api/operations/tasks/{taskId}/assign` 要求非空 `assignee`。服务端保存 `assignee` 和 `assignedAt`，并写入 `TASK_ASSIGNED`。当前 assignee 是稳定字符串，不等同于 Workforce/RBAC 用户实体；这符合本阶段边界，但正式企业部署仍需接入人员目录和权限域。

## 8 Start Semantics

`POST /api/operations/tasks/{taskId}/start` 允许 `OPEN → IN_PROGRESS` 的直接启动，也允许 `ASSIGNED → IN_PROGRESS`。服务端生成 `startedAt`、更新 `updatedAt` 和 `version`，并写入 `TASK_STARTED`。请求体不能覆盖开始时间或 actor。

## 9 Completion Semantics

`POST /api/operations/tasks/{taskId}/complete` 必须提供非空 `summary`，可附带 `executionEvidence` 和 `notes`。只有 `IN_PROGRESS` 可以首次完成。服务端在同一 JDBC 事务内更新 Task、插入 Outcome、写入 `TASK_COMPLETED` 和 `OUTCOME_CREATED`，事务提交后才向调用方返回成功。

## 10 Cancellation Semantics

`POST /api/operations/tasks/{taskId}/cancel` 必须提供非空 `reason`。取消会记录 `cancelledAt` 和 `cancelledReason`，写入 `TASK_CANCELLED`，不会创建 Outcome。完成后或取消后再次取消都被拒绝。

## 11 Outcome Model

Outcome 字段包括：`outcomeId`、`taskId`、`resultType`、`summary`、`executionEvidence`、`notes`、`actor`、`createdAt`、`updatedAt`。Outcome 的创建入口只有 `completeTask`；没有通过列表更新、Agent trace 或前端 demo 数据旁路创建结果。

## 12 Task → Outcome Relationship

`agent_operation_outcomes.task_id` 具有唯一约束，因此一个 Task 最多一个 canonical Outcome。Outcome 使用独立 `outcome-UUID` 身份，不复用 `runId`、`actionId` 或 `diagnosisId`。取消任务没有 Outcome，仍可通过审计事件追溯取消原因。

## 13 Transaction Boundary

生产完成命令使用 `setAutoCommit(false)`，以 `SELECT ... FOR UPDATE` 锁住 Task 行，然后按顺序执行状态更新、Outcome 插入和两条审计插入；任何异常都会 rollback，连接 finally 恢复 auto-commit。任务创建也把 Task 和 `TASK_CREATED` 放在同一事务中。

## 14 Completion Idempotency

已完成 Task 的重复完成请求只读取既有 Outcome 并返回相同 `outcomeId`，不会替换 summary、不会追加第二个 Outcome、不会增加完成审计。若数据库发现 Task 已完成但缺少 Outcome，服务端 fail closed，返回冲突而不是伪造结果。

## 15 Concurrency Control

生产并发控制采用数据库行锁而不是依赖 JVM 内存锁：每个命令先锁定 Task，再校验状态并提交事务。`version` 同步递增用于诊断和客户端观察；状态正确性由 `FOR UPDATE` 和事务边界保证。无 DataSource 的 fallback 只用于直接单 JVM 测试，并使用 synchronized 保持等价行为。

## 16 Complete/Cancel Race

完成和取消同时竞争同一 `IN_PROGRESS` 行时，先获得行锁并提交的命令成为唯一终态赢家；另一个命令重新读取终态后返回 `INVALID_TASK_TRANSITION`。若完成获胜，存在一个 Outcome；若取消获胜，不存在 Outcome。没有“完成状态但无结果”的成功路径。

## 17 Audit Model

`operations_task_events` 为 append-only 事件表，保存 `eventId`、`taskId`、`eventType`、前后状态、actor、reason、metadata 和 `createdAt`。事件包括 `TASK_CREATED`、`TASK_ASSIGNED`、`TASK_STARTED`、`TASK_COMPLETED`、`TASK_CANCELLED`、`OUTCOME_CREATED`。完成事件和 Outcome 事件都在同一事务中写入；Outcome 事件时间使用完成时间后 1ms，适配 MySQL `TIMESTAMP(3)`，确保查询顺序稳定。

## 18 Data Model Changes

任务表新增或桥接：`updated_at`、`owner_user_id`、`assignee`、`assigned_at`、`started_at`、`completed_at`、`cancelled_at`、`cancelled_reason`、`version`。Outcome 表和事件表按上述模型新增。所有新 Agent 任务继续保留 `action_id`、`agent_run_id`、`diagnosis_id`、可选 `recommendation_id` 和 evidence IDs。

## 19 Schema / Migration

迁移文件为 `zhgy/zhgy/src/main/resources/db/migration/V5_2_4__operations_lifecycle_outcome.sql`。它为旧任务表添加 nullable 生命周期字段，创建状态/assignee 索引，并创建 Outcome 唯一约束和事件索引。代码中的 schema bridge 也能为本地环境补齐缺失列和表。

部署门禁是按顺序应用 V5.2.1、V5.2.2、V5.2.3、V5.2.4；当前迁移文件中的 `ALTER TABLE`/索引语句应由正式 migration runner 只执行一次，不能把启动时 schema bridge 当成生产迁移管理器。

## 20 API Changes

新增或固定以下 HTTP 命令和读取：

| 方法 | 路径 | 语义 |
| --- | --- | --- |
| GET | `/api/operations/tasks` | 当前 actor 可见的任务列表 |
| GET | `/api/operations/tasks/{taskId}` | 任务详情 |
| POST | `/api/operations/tasks/{taskId}/assign` | 分配任务 |
| POST | `/api/operations/tasks/{taskId}/start` | 开始执行 |
| POST | `/api/operations/tasks/{taskId}/complete` | 完成并创建 canonical Outcome |
| POST | `/api/operations/tasks/{taskId}/cancel` | 取消并记录原因 |
| GET | `/api/operations/tasks/{taskId}/outcome` | Outcome 详情 |
| GET | `/api/operations/tasks/{taskId}/events` | 生命周期审计 |

错误响应统一包含稳定 `code`、可选任务和转换字段及时间戳。

## 21 Server-side Validation

服务端校验 summary、reason、assignee、Task 存在性、owner、状态转换和 Outcome 唯一性。生产 Agent 写入仍要求 `actionId`，并继续验证 `diagnosisId` 与 `recommendationId` 的快照来源。客户端传来的 actor、时间和状态不会覆盖服务端事实。

## 22 Security / Ownership

`/api/operations/**` 已纳入 JWT 拦截器；Controller actor 来自 `BaseContext`，不从请求体读取。列表按 `owner_user_id` 过滤，详情、Outcome、事件和所有命令都执行相同 ownership 校验。ownerless 旧行为了兼容仍对已认证用户可读，这一行为在风险章节明确保留，不能被误认为完整租户隔离。

## 23 Legacy Compatibility

旧任务的 nullable 生命周期字段不会被系统猜测填充；状态缺省按现有 OPEN 语义读取。旧的 ownerless 行仍可读取和操作，以避免历史任务消失；新 Agent 写入必须具备 owner 和 actionId。旧列表调用保留无 actor 的 service overload，仅用于本地测试和兼容内部调用，HTTP 不使用该 overload。

## 24 Demo Compatibility

前端 demo 数据仍只属于 DEMO/只读展示边界，没有混入生产 Task 写入。Operations 读模型保留 status、timestamp、outcome 和 traceability 字段的兼容归一化。生产服务数据库故障不会再被伪装成 demo 空列表。

## 25 Stable Product Loop Regression

5.2.4 没有改变以下冻结链路：

`Diagnosis → Agent Run → Approval(runId, actionId) → Operations Task → Outcome`

`diagnosisId` 与 `sourceRunId` 继续分离；recommendationId 可为空且只能来自 Diagnosis snapshot；同一 actionId 最多创建一个 Task；关联全部依赖 ID，不依赖标题、索引或文本匹配。

## 26 Tests

无 DataSource 的 service 回归通过：

- `OperationsLifecycleTest`：9 tests，0 failures，覆盖创建幂等、完整生命周期、非法终态、owner 隔离、完成/取消竞争、并发完成唯一 Outcome、产品链路 provenance 和 ownerless legacy。
- `ProductLoopRelationshipTest`：5 tests，0 failures。
- `DiagnosisPersistenceTest`：5 tests，0 failures。
- `ProductionWriteSafetyTest`：7 tests，0 failures。
- `DiagnosisAgentLinkTest`：1 test，0 failures。
- `ExpertReviewIntegrationTest`：1 test，0 failures。

执行命令使用 `FARMAP_AGENT_PERSISTENCE=file`，并通过 `-DforkCount=0` 避免共享本地状态造成误判。

## 27 MySQL Tests

使用本地 MySQL root 连接（凭据未写入报告）通过：

- `OperationsLifecycleMysqlTest`：1 test，0 failures，验证真实 Task 生命周期、时间字段、Outcome 唯一约束、审计顺序、并发完成和推荐 provenance。
- `ProductLoopRelationshipMysqlTest`：1 test，0 failures。
- `DiagnosisPersistenceMysqlTest`：1 test，0 failures。
- `ProductionWriteSafetyMysqlTest`：1 test，0 failures。

测试使用随机 action/task 身份并在 finally 中清理测试行。

## 28 HTTP E2E

在当前源码、MySQL persistence 和 18081 端口上完成真实 HTTP 链路：

1. `POST /diagnosis/analyze` 返回稳定 `diagnosisId`。
2. `POST /api/agent/runs`，Agent 进入 `WAITING_FOR_APPROVAL` 并产生 operations action。
3. `POST /api/agent/runs/{runId}/actions/{actionId}/approve` 返回 `COMPLETED` 并创建 Task。
4. Task 顺序成功经历 `OPEN → ASSIGNED → IN_PROGRESS → COMPLETED`。
5. 首次完成返回 Outcome；重复完成返回相同 Outcome ID。
6. Outcome 查询成功；事件顺序为 `TASK_CREATED, TASK_ASSIGNED, TASK_STARTED, TASK_COMPLETED, OUTCOME_CREATED`。
7. 完成后再次 start 返回 HTTP 409、`INVALID_TASK_TRANSITION`、当前状态 `COMPLETED`。

HTTP 验证结束后已停止应用进程并清理本次测试生成的 Task、Outcome、Approval、Agent Run 和 Diagnosis 行。

## 29 Frontend

本阶段没有新增前端命令按钮，也没有伪造 mutation 成功状态；前端继续使用 Operations contract/adapter 和只读中心展示生命周期、Outcome 和 traceability。此前 5.2.3 前端验证已通过 `npx tsc -b`、目标文件 ESLint 和 `npm run build`。本阶段后端改动不改变既有路由，下一步可在确认权限和错误交互后接入真实 assign/start/complete/cancel 控件。

## 30 Risks

1. V5.2.4 migration 仍需要正式 runner 管理执行顺序和回滚策略。
2. ownerless legacy rows 的可见范围仍弱于完整租户隔离；需要后续 ownership backfill。
3. assignee 目前是字符串，不是 Workforce/RBAC 身份。
4. 前端当前只读，后端命令虽已可用，但用户操作界面尚未产品化。
5. HTTP E2E 环境中的 Mongo/Milvus 不可解析，Diagnosis 使用了既有 grounded-template/本地知识 fallback；这不阻断 Operations 生命周期验证，但生产部署仍需补齐外部依赖健康门禁。
6. 全量 Maven suite 未作为本阶段唯一门禁；验证集中在 Operations、稳定产品闭环及对应 MySQL tests。

## 31 Release Gates

发布前必须满足：

- V5.2.1–V5.2.4 migration 在目标 MySQL 只执行一次并完成 schema/index 检查。
- `/api/operations/**` 必须经过 JWT，actor 必须来自服务端认证上下文。
- 数据库不可用时写入和读取均 fail closed，不得降级为空生产结果。
- Task 状态、Outcome 唯一约束、完成/取消竞争和事件顺序测试通过。
- ownerless legacy 数据的兼容策略经过产品和安全负责人书面确认。
- Mongo/Milvus/模型依赖健康检查通过，或明确启用受控 fallback 并对用户标注数据新鲜度。
- 前端接入 mutation 前完成权限错误、409 重试和 Outcome 展示设计。

## 32 Go / No-Go

**Milestone 5.2.4 代码验收：GO。** 生命周期状态机、服务端转换校验、数据库行锁、完成事务、Outcome 唯一性、审计、ownership 过滤、legacy 兼容和 HTTP E2E 均有实现与验证证据。

**生产发布：Conditional Go。** 在正式 migration runner、ownerless legacy backfill/隔离决策、Workforce/RBAC 映射和 Mongo/Milvus 依赖健康门禁完成前，不应宣称已达到完全生产就绪。满足第 31 节 gates 后可进入 5.2.5 Expert Review V2 的设计与实现评审。

**当前 No-Go 项：** 没有发现阻断 5.2.4 本地代码验收的失败项；未满足上述生产 gates 时，唯一 No-Go 是面向真实企业租户的无条件发布。

## Files

- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/operations/OperationsTaskService.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/operations/OperationsWriteException.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/controller/OperationsController.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/config/WebMvcConfiguration.java`
- `zhgy/zhgy/src/main/resources/db/migration/V5_2_4__operations_lifecycle_outcome.sql`
- `zhgy/zhgy/src/test/java/com/mei/zhgy/service/operations/OperationsLifecycleTest.java`
- `zhgy/zhgy/src/test/java/com/mei/zhgy/service/operations/OperationsLifecycleMysqlTest.java`

本阶段没有提交 commit。
