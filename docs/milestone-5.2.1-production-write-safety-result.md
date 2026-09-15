# Milestone 5.2.1 Production Write Safety Result

## 1. Backend Reality Audit

修复前的实际写路径为：

```text
POST /api/agent/runs/{runId}/actions/{actionId}/approve
  → AgentController.approve
  → AgentRuntime.approve
  → ApprovalManager.approve
  → ProposedAction.approvalState = APPROVED（仅内存）
  → AgentRuntime.executeApprovedAction
  → ToolExecutor
  → TaskTool
  → OperationsTaskService.createTask
  → agent_operations_tasks 或 ConcurrentHashMap fallback
```

`reject` 走同一 Controller/Runtime/ApprovalManager 边界。成功响应直接返回 `AgentRun`，没有独立的审批结果 DTO，也没有 Controller 级审批异常映射。

Run 的持久化有两种模式：默认 `file` 模式使用 `InMemoryAgentRunRepository` 写 JSON；设置 `FARMAP_AGENT_PERSISTENCE=mysql` 后使用 `JdbcAgentRunRepository` 将完整 `AgentRun` JSON 写入 `agent_runs`。原实现没有 Approval 表、Action 版本号、Approval 唯一键或 Operations task 的 `action_id`。

原 `OperationsTaskService` 在数据库连接、建表或插入失败时会静默写入进程内 `ConcurrentHashMap`；这会让 API 看起来成功，但重启后记录消失。原 `TaskTool` 的 `synchronized` 只能保护单个 JVM 实例，不能提供多实例或重启安全。

## 2. Root Cause

`ApprovalManager` 只改变当前 `AgentRun` 对象，没有持久化“谁对哪个 Action 做过什么决定”。重复 HTTP 请求、客户端重试、并发请求或反向代理重放时，每次请求都可以再次进入 `executeApprovedAction`。

`OperationsTaskService` 每次都生成新的随机 `taskId`，没有 Action provenance 或数据库唯一约束。因此 Approval 层的重复副作用会直接转化为多个 OperationsTask；数据库不可用时还会被内存 fallback 掩盖。

## 3. Architecture Decision

本阶段采用最小的模块化单体方案：

- 新增 `agent_approvals` 持久化表，以 `(run_id, action_id)` 主键提供唯一业务决策边界。
- Approval 首次决定使用数据库 insert-if-absent；重复相同决定返回已有记录；相反决定返回 `CONFLICT`。
- Approval 执行阶段使用 `PENDING → EXECUTING` 的数据库 compare-and-set claim，只有拿到 claim 的请求才能调用 TaskTool。
- Operations task 增加 `action_id`，新任务使用数据库 `UNIQUE(action_id)`；唯一冲突时读取已有任务，不生成第二个 UUID 任务。
- Approval 决策、执行 claim、Task insert、Run save 保持清晰的短事务边界；不引入 Redis、MQ、微服务或分布式锁。
- 生产 Spring 路径使用 JDBC store；无 DataSource 时 Approval 写入 fail closed。无参数构造器仅保留给直接单元测试/本地 demo，不是生产 Bean 路径。

这样既保留了现有 AgentRun JSON repository，也把 P0 写入安全移到了数据库最终约束上。

## 4. Data Model Changes

新增 `agent_approvals`：

```text
run_id           VARCHAR(128)  NOT NULL
action_id        VARCHAR(180)  NOT NULL
decision_state   VARCHAR(32)   NOT NULL
execution_state  VARCHAR(32)   NOT NULL
actor_id         VARCHAR(128)
decided_at       TIMESTAMP(3)
task_id          VARCHAR(128)
failure_code     VARCHAR(64)
failure_message  TEXT
PRIMARY KEY (run_id, action_id)
```

`agent_operations_tasks` 新增 nullable `action_id VARCHAR(180)` 和 `uk_agent_operations_action_id` 唯一索引。历史 task 的 `action_id = NULL` 可以保留；新的 Agent-approved task 必须携带非空 Action ID。AgentRuntime 现在生成包含 `runId` 的稳定 Action ID，避免不同 Run 的默认 step ID 碰撞。

正式 SQL 位于 [V5_2_1__production_write_safety.sql](/D:/前后端项目/farmap/zhgy/zhgy/src/main/resources/db/migration/V5_2_1__production_write_safety.sql)。当前项目没有 Flyway/Liquibase 或其他正式 migration runner，仍以动态建表为主，因此 Operations service 保留了一次性的兼容检查（旧表缺列时尝试补列/索引）。这只是兼容桥，生产发布前必须先执行并审查 SQL migration，不能把运行时 `ALTER TABLE` 当作最终发布策略。

`agent_runs` 的 JSON 结构没有被拆表，也没有在本阶段实现 Recommendation persistence。

**Current Migration Limitation：** 当前仓库只有动态建表和手工 SQL 资源，没有已接入应用启动的正式 migration runner；正式发布必须把上述 SQL 纳入部署迁移流程并在启用写入前完成校验。

## 5. Approval State Machine

业务决定只允许：

```text
REQUIRED → APPROVED
REQUIRED → REJECTED
```

同一决定的重复请求是幂等读取；`APPROVED → REJECTED`、`REJECTED → APPROVED` 和并发相反决定都会返回明确冲突。已完成、失败或取消的 Run 不能再批准仍处于 `REQUIRED` 的 Action。已批准的 Action 重复 approve 只返回已有结果，不重新执行。

Approval 记录的执行状态为 `NONE / PENDING / EXECUTING / COMPLETED / FAILED`。`PENDING → EXECUTING` 是唯一 TaskTool 执行 claim；成功后写入 `COMPLETED + taskId`，工具失败写入 `FAILED`。

## 6. Task Idempotency

`AgentToolRequest` 新增 `actionId`，AgentRuntime 在批准路径传给 TaskTool。OperationsTaskService 的顺序是：

1. 检查同一 `action_id` 的已有任务；
2. 没有任务时执行带唯一索引的 INSERT；
3. 并发唯一冲突时读取并返回已有任务；
4. 数据库写失败时抛出 `OPERATIONS_PERSISTENCE_UNAVAILABLE`，不再静默写内存。

因此直接绕过 Approval 调用 `createTask` 也不会为同一 Action 创建第二行。Operations lifecycle 仍保持 `OPEN`，本阶段没有引入后续状态。

## 7. Transaction Boundary

- Approval insert 在 JDBC 短事务中完成；主键保证同一 `(runId, actionId)` 只有一个决定。
- Execution claim 是数据库条件 UPDATE，只有一行可以从 `PENDING` 变为 `EXECUTING`。
- Task insert 是独立的数据库事务，`UNIQUE(action_id)` 是最终防线。
- 首个执行者在 claim 前保存已批准的 Run；只有 claim 成功者继续调用 TaskTool。重复请求不会用旧 Run 对象覆盖最终状态。
- Task 成功后写入 Approval `COMPLETED + taskId`，再完成 Run；若进程在 Task commit 与 Approval completion 或 Run save 之间崩溃，后续 approve 会按 `actionId` 查询既有任务并恢复，不再次调用 TaskTool。

边界不是跨 Approval 表、Task 表和 Run JSON 的单一分布式事务，而是“短事务 + CAS + 数据库唯一约束 + 可恢复读取”。因此：

- Approval 成功、TaskTool 失败：Approval 执行状态变为 `FAILED`，Run 变为 `FAILED`，没有成功任务。
- Task 成功、Run save 失败：Task 和 Approval task link 保留；后续请求通过 `actionId` 恢复 Run，不创建第二个任务。
- DB commit 成功但 HTTP 超时：客户端重试读取已有 Approval/Task，返回相同 taskId。

如果进程在 claim 后、Task insert 前永久崩溃，记录会停在 `EXECUTING`，需要后续运维恢复策略；该情况不会产生第二个任务，但会影响单次动作的可用性，列为剩余风险。

## 8. Duplicate Request Behavior

- 首次 approve：创建一个 Approval 记录，拿到 execution claim，最多创建一个 Task，返回原有 `AgentRun` shape。
- 顺序重复 approve：返回已有 Run/Task，不调用 TaskTool。
- 并发重复 approve：只有一个请求拿到 claim，其余请求返回幂等结果或当前状态。
- approve 后 reject：返回 `ALREADY_APPROVED`，Approval 和 Task 保持不变。
- reject 后 approve：返回 `ALREADY_REJECTED`，不创建 Task。
- 重复 reject：返回已有 REJECTED 决定，不产生写副作用。

## 9. Concurrency Behavior

同一 JVM 的 Run 锁仍作为辅助，数据库 Approval 主键和 execution CAS 才是跨线程/跨实例保证。测试使用 8 个并发 approve，最终只有一个 TaskTool call 和一条任务记录。Operations 直写测试也覆盖了同一 Action 的重复 create。

## 10. Approve / Reject Race

Approval store 对同一 `(runId, actionId)` 的首次 insert 获胜者唯一；另一方向的决定在读取已有记录后返回 `CONFLICT`。测试同时执行 approve/reject，最终恰有一个决定，Action 不会回到 `REQUIRED`，只有 approve 获胜时才允许任务执行。

## 11. Error Contract

Approval Controller 增加了针对性异常映射，成功响应不变；相关错误不再统一落为 generic 500：

| Code | HTTP | 含义 |
|---|---:|---|
| `ALREADY_APPROVED` | 409 | 已批准，不能再次批准或拒绝 |
| `ALREADY_REJECTED` | 409 | 已拒绝，不能再次批准 |
| `CONFLICT` | 409 | 并发或相反决定冲突 |
| `ACTION_NOT_FOUND` | 404 | Run 中不存在该 Action |
| `INVALID_ACTION_STATE` | 409 | 当前状态不允许该转换 |
| `PERSISTENCE_UNAVAILABLE` | 503 | Approval 持久化不可用，写入被拒绝 |

Operations 写失败会通过 ToolResult 的原始错误码进入 Run `failureCode`，而不是伪装成成功任务。

当前 actor 仍为 `current-user`，这是真实现状；本阶段没有虚构 RBAC。生产审计所需的真实用户身份、租户权限和未来 `actorId` 校验仍是后续能力。

## 12. Audit / Logging

沿用现有 TraceRecorder，增加/保留以下关键事件：

- `approval.approved`：runId、actionId、actor、是否幂等；
- `approval.rejected`：runId、actionId、actor、是否幂等；
- `operations.task.created`：runId、actionId、taskId、actor；
- `operations.task.recovered`：崩溃恢复时的 runId、actionId、taskId。

Trace 事件自带时间戳并随 AgentRun 保存；本项目目前没有 correlation/request-id 基础设施，本阶段没有引入完整 Audit Platform。

## 13. Fallback Safety

生产 Spring Bean 的 ApprovalManager 使用 JDBC store；没有 DataSource 时直接返回 `PERSISTENCE_UNAVAILABLE`。生产路径的 OperationsTaskService 也不再在 DB 失败时写 `ConcurrentHashMap`。

无参数构造的 InMemoryApprovalStore/OperationsTaskService fallback 只用于已有直接单元测试和本地 demo 兼容；它们不被 Spring 生产构造器选用。Operations GET 保留原有读取兼容行为，不影响新的写入 fail-closed 门禁。

生产发布还必须显式设置 `FARMAP_AGENT_PERSISTENCE=mysql`，使 AgentRun 也使用 MySQL 而不是默认 file repository。

## 14. Files Changed

本 Milestone 的后端/测试文件：

- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/ApprovalException.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/ApprovalExecutionState.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/ApprovalRecord.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/ApprovalStore.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/InMemoryApprovalStore.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/JdbcApprovalStore.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/ApprovalManager.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/AgentRuntime.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/AgentToolRequest.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/ToolExecutor.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/controller/AgentController.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/operations/OperationsTaskService.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/operations/OperationsWriteException.java`
- `zhgy/zhgy/src/main/resources/db/migration/V5_2_1__production_write_safety.sql`
- `zhgy/zhgy/src/test/java/com/mei/zhgy/service/agent/ApprovalManagerTest.java`
- `zhgy/zhgy/src/test/java/com/mei/zhgy/service/agent/ProductionWriteSafetyTest.java`
- `zhgy/zhgy/src/test/java/com/mei/zhgy/service/agent/ProductionWriteSafetyMysqlTest.java`

本阶段没有修改 Planner、RAG、Diagnosis、Overview、Operations lifecycle、Expert、Knowledge 或前端源文件。工作区中此前已有的 Milestone 5.x 前端和文档改动保持原样。

## 15. Automated Tests

`mvn -q -f zhgy/zhgy/pom.xml test`：通过。全量测试期间只有既有 ONNX/CLIP 本地模型内存告警，未导致测试失败。

`ProductionWriteSafetyTest` 覆盖：

1. First approve：一个批准、一个任务；
2. Sequential duplicate approve：相同 taskId，TaskTool call 数为 1；
3. Parallel duplicate approve：8 个并发请求，一个 claim、一个任务、一个工具调用；
4. Approve after reject：拒绝获胜，无任务；
5. Reject after approve：保持 APPROVED，不能反向拒绝；
6. Approve/reject race：恰有一个决定获胜；
7. HTTP retry 的同等语义由幂等 store 和受控 E2E 覆盖；
8. Direct task duplicate actionId：同一 taskId，count 为 1；
9. Runtime object replacement：共享持久化 store 后重复 approve 不再执行；
10. No datasource：生产构造路径返回 `PERSISTENCE_UNAVAILABLE`，不改 Action 状态。

`ProductionWriteSafetyMysqlTest` 在 `FARMAP_AGENT_PERSISTENCE=mysql` 下通过真实 MySQL，验证了 Approval 主键、跨 store instance 的 claim、Operations `UNIQUE(action_id)`、重复写读回同一 task，并清理了随机测试记录。

## 16. Integration / E2E Verification

使用真实本地 MySQL 启动后端（独立测试端口）完成受控 E2E：

```text
POST /api/agent/runs
→ WAITING_FOR_APPROVAL
→ POST approve
→ POST 同一 approve
→ GET /api/operations/tasks
```

验证结果：任务数量由 0 增加到 1；两次 approve 返回 `COMPLETED`；两次结果中的 `taskId` 相同；按 `actionId` 匹配的 Operations task 数量为 1。测试使用随机 Run/Action/Field 标识，结束后删除了测试行。

后端启动时 Milvus/Mongo 的可选服务名在当前本机不可解析，但应用仍能启动，且本 E2E 只依赖 Agent/Approval/Operations/MySQL 写链路。

## 17. Frontend Compatibility

本阶段没有修改前端源代码。Approve 成功响应仍是 `AgentRun`，因此既有 Agent Workspace approval UI 不需要适配；Operations GET 仍返回 `{items, count}`，任务行只增加了可选 `actionId` 字段。

前端 `npm run build` 通过。`npm run lint` 仍有仓库既有的 45 个 error 和 10 个 warning，集中在旧的 dashboard/expert/model/monitor/weather/util 文件，本阶段没有触碰这些文件，也没有出现由本 Milestone 引入的 API 类型构建错误。

## 18. Remaining Risks

- 当前项目没有正式 migration runner；生产发布必须先执行 SQL migration 并检查旧表是否存在非空重复 `action_id`，再启用写入。
- 必须在生产设置 `FARMAP_AGENT_PERSISTENCE=mysql`；file repository 仍是开发默认，不适合作为生产 AgentRun 真相源。
- Actor 仍是 `current-user`，缺少租户/RBAC/真实身份绑定；这是后续审计与权限工作。
- Claim 后、Task insert 前的永久进程崩溃会留下 `EXECUTING` 记录；当前实现保证不重复写，但需要后续超时/人工恢复机制改善可用性。
- 本阶段没有构建完整 audit platform，也没有实现 Recommendation/Diagnosis persistence 或 Operations lifecycle。

## 19. Production Release Gate

**Approval Idempotency P0：CLOSED（在 MySQL migration 已执行、`FARMAP_AGENT_PERSISTENCE=mysql` 已启用、数据库唯一索引可用的前提下）。**

证据包括：真实 MySQL 条件测试、8 并发测试、approve/reject race、直接 Action task 去重、DB unavailable fail-closed，以及真实 HTTP E2E。重复 approve 不再调用第二次 TaskTool，同一 Action 最多保留一个 OperationsTask。

若未先执行 migration、仍使用 file repository 或数据库权限无法创建唯一约束，发布门禁应保持 NO-GO；代码不能用内存 fallback 替代这些部署条件。

## 20. Go / No-Go

**Milestone 5.2.1 Production Write Safety Foundation：GO。**

本阶段到此停止。下一阶段只建议：**Milestone 5.2.2 — Diagnosis Persistence & Stable Product Identity**。不在本轮开始 Diagnosis persistence、Recommendation persistence、Operations lifecycle、Expert/Historical/Knowledge、Agent V2 或 RAG V2。
