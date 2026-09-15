# Milestone 5.2.2 Diagnosis Persistence & Stable Product Identity Result

本阶段把一次 RAG 运行结果提升为可追踪、可重载、可授权访问的 Diagnosis 业务对象。RAG 的 `run_id` 继续保留为执行追踪字段，但不再承担业务主键职责。

## 1. Reality Audit

审查确认，阶段开始时 `POST /diagnosis/analyze` 只由 `DiagnosisRagService` 生成 `rag-*` `run_id` 并返回内存响应；没有独立 Diagnosis 表、Repository 或详情 GET。前端真实诊断页把 `run_id` 当作诊断 ID，`/diagnosis/:id` 原先是占位页。Expert Review 和 Agent/Operations 链路因此只能保存不稳定的 RAG 执行身份。

## 2. Root Identity Problem

RAG 运行和业务诊断的生命周期不同：一次执行可能失败、重试或被替换，而农业企业需要稳定地查看同一份诊断、复核同一组证据并追踪后续行动。以 `run_id` 作为业务身份会导致详情地址、专家复核和 Operations provenance 随执行变化，也无法保证跨进程重载。

## 3. Architecture Decision

采用“分析 → 生成独立身份 → 持久化完整快照 → 返回”的同步边界。`DiagnosisPersistenceService` 位于 Controller 与 RAG service 之间，Controller 不包含 SQL，RAG 算法和检索器保持不变。读取只从 Repository 加载已保存 payload，绝不重新运行 RAG。

生产 Repository 使用 MySQL `JdbcDiagnosisRepository`；直接单测使用隔离的 `InMemoryDiagnosisRepository`。当生产 DataSource 不可用时使用 fail-closed repository，拒绝返回可被误认为已保存的稳定身份。

## 4. Diagnosis Data Model

`DiagnosisRecord` 包含 `diagnosisId`、`sourceRunId`、`userId`、`farmId`、`fieldId`、`crop`、`status`、`riskLevel`、`confidence`、`summary`、`mode`、创建/更新时间，以及完整的 `DiagnosisAnalysisResponse payload`。

当前领域状态只使用 `COMPLETED`。没有把 DEMO、STALE、UNAVAILABLE 等展示模式伪装成持久化业务状态；这些仍属于页面或数据新鲜度语义。

## 5. ID Strategy

- `diagnosisId`：服务端每次显式分析请求生成新的 `diagnosis-<UUID>`，独立于 RAG。
- `sourceRunId`：保存原始 `rag-<UUID>`，用于追踪 RAG 执行。
- `run_id`：响应字段继续返回，值等于 `sourceRunId`，标记为兼容字段，不再作为新业务关系的首选。
- 当前不实现未获授权的分析幂等或去重；每一次显式 POST 都创建新的 Diagnosis。

## 6. Persistence Design

MySQL 表为 `agent_diagnoses`，主键为 `diagnosis_id`，保存结构化索引列和 `payload_json`。保存使用参数化 SQL；查询同时限定 `diagnosis_id` 与当前用户，避免跨用户读取。动态建表只作为运行兼容保护，部署仍必须执行版本化 SQL。

## 7. API Changes

- `POST /diagnosis/analyze`：先调用现有 RAG，再持久化，成功返回 `diagnosisId`、`sourceRunId`、`status`、时间戳和原完整响应。
- `GET /api/diagnoses/{diagnosisId}`：读取持久化快照。
- `GET /diagnoses/{diagnosisId}`：为开发环境前端 base URL 提供兼容路径。
- 未登录返回 401；不存在或不属于当前用户的诊断返回 404；Repository 不可用返回服务不可用语义。
- GET 不触发模型、检索器或新的 `run_id`。

## 8. Snapshot Semantics

持久化 payload 是分析时的完整结构化快照，包括 diagnosis、claims、evidence、recommendations、retrieval summary 和 metadata。MySQL 以 JSON 序列化保存；查询时反序列化并补回数据库中的稳定身份与时间戳。内存 Repository 也复制 payload，避免调用方修改原对象后改变后续读取结果。

## 9. Evidence / Finding / Recommendation Preservation

Evidence、Claim（finding）和 Recommendation 的原始 ID 与 `evidence_ids` 引用在保存和 GET 之间保持不变。没有重跑召回、重排或生成，也没有在本阶段改变多模态 RAG 算法、Prompt、模型路由或证据融合逻辑。

## 10. Expert Review Compatibility

真实前端诊断结果提交 Expert Review 时优先使用 `diagnosisId`。已有以 `rag-*` 或演示 ID 保存的记录不做破坏性迁移，继续作为只读兼容数据。Expert Review V2、历史候选生成和知识库写入不属于本阶段。

## 11. Agent Context Integration

后端和前端 `AgentContext` 增加 `diagnosisId`。页面上下文适配器从当前选中诊断注入真实 ID；Agent run metadata 和 RAG request context 同步保留该字段。旧的 `diagnosis` map 继续存在，用于兼容 demo/legacy 数据。

## 12. Operations Provenance

`OperationsTaskService` 创建任务时先读取 `AgentContext.diagnosisId`，只有缺少该字段时才回退读取旧 `diagnosis.id`。因此新 Agent 任务保存真实 Diagnosis 身份，`agentRunId` 仍作为执行来源追踪。Recommendation→Action→Approval→Task 的稳定关系留给 Milestone 5.2.3。

## 13. Demo / Legacy Compatibility

`a12-demo` 仍只属于前端 demo，不插入真实 `agent_diagnoses` 表。旧的 `run_id` 字段、Expert Review 行和 diagnosis map 不删除；新代码只在真实持久化结果存在时使用 `diagnosisId`。

## 14. Fail-closed Behavior

RAG 成功但 Diagnosis 保存失败时，Controller 不返回稳定诊断 ID，避免用户拿到无法查询的假记录。读取时数据库不可用不会降级成重新分析或伪造详情。生产写安全仍遵循 5.2.1 的 fail-closed 规则。

## 15. Schema / Migration

新增 `zhgy/zhgy/src/main/resources/db/migration/V5_2_2__diagnosis_persistence.sql`，创建 `agent_diagnoses` 及 source run、field、user 索引。当前工程没有统一 Flyway/Liquibase runner，因此发布门禁必须确认 SQL 已在目标 MySQL 执行；Repository 的动态 `CREATE TABLE IF NOT EXISTS` 只用于兼容启动和测试。5.2.1 的 `agent_approvals` / Operations action safety migration 仍是生产前置条件。

## 16. Files Changed

本阶段新增或改动的核心文件：

- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/diagnosis/DiagnosisPersistenceService.java`
- `DiagnosisRecord.java`、`DiagnosisRepository.java`、`InMemoryDiagnosisRepository.java`、`JdbcDiagnosisRepository.java`、`DiagnosisPersistenceException.java`
- `DiagnosisAnalysisResponse.java`
- `DiagnosisController.java`、`DiagnosisQueryController.java`
- `WebMvcConfiguration.java`
- `AgentContext.java`、`AgentDiagnosisRequestFactory.java`、`AgentRuntime.java`
- `OperationsTaskService.java`
- `V5_2_2__diagnosis_persistence.sql`
- `DiagnosisPersistenceTest.java`、`DiagnosisPersistenceMysqlTest.java`、`DiagnosisAgentLinkTest.java`
- `farmap-frontend-dev/src/features/diagnosis/{contract.ts,service.ts,adapter.ts}`
- `farmap-frontend-dev/src/features/agent/{contract.ts,PageContextAdapter.ts}`
- `farmap-frontend-dev/src/types/diagnosis.ts`
- `farmap-frontend-dev/src/views/diagnosis/Detail.tsx`
- `farmap-frontend-dev/src/routes/routes.tsx`

本列表不包含此前 Milestone 5.0/5.1/5.2.1 已存在的未提交工作；这些改动均被保留。

## 17. Automated Tests

- `DiagnosisPersistenceTest`：独立 ID、`diagnosisId != sourceRunId`、Repository 替换后快照重载、Evidence/Claim/Recommendation ID 稳定、错误 ID/错误用户、数据库不可用 fail-closed、Query 404、Agent→Task 真实 provenance。
- `DiagnosisAgentLinkTest`：AgentContext 同时保留稳定 ID 和 legacy map。
- `ExpertReviewIntegrationTest`：新复核记录保留稳定 `diagnosisId`，不把 source run 重新命名为业务身份。
- `ProductionWriteSafetyTest`：5.2.1 approval/task 并发、重复批准和 fail-closed 回归通过。
- 后端 `-DskipTests compile` 通过。

全量 Maven 测试仍被仓库既有 `LocalVisionServiceTest` 的 fork JVM 崩溃阻断；该失败发生在本阶段代码之外，未观察到本阶段测试的编译或断言失败。

## 18. MySQL Integration Tests

`DiagnosisPersistenceMysqlTest` 使用当前本地 MySQL 配置通过：创建 `agent_diagnoses`、保存结果、使用新的 Repository 实例读取，并验证稳定 ID、source run、Evidence、Claim、Recommendation ID 均保持不变。`ProductionWriteSafetyMysqlTest` 也通过，确认 5.2.1 生产写安全仍可用。测试完成后清理测试行。

## 19. HTTP E2E

在独立端口启动当前编译代码并使用 MySQL 后完成 HTTP smoke/E2E：

1. 带 JWT 的 `POST /diagnosis/analyze` 返回 `diagnosisId=diagnosis-*` 与 `sourceRunId=rag-*`。
2. 带同一用户身份的 `GET /api/diagnoses/{diagnosisId}` 返回 200，并验证 Evidence 数量、Claim ID、Recommendation ID 与 POST 结果一致。
3. 不带 JWT 的 GET 返回 401；带 JWT 查询未知 ID 返回 404。

本机没有 Mongo/Milvus 服务，启动日志显示两者不可用；本次成功路径使用了 RAG 的可用 fallback evidence，未把外部向量服务可用性伪装成通过条件。

## 20. Frontend Compatibility

前端没有重新设计页面，只增加契约字段、GET service、稳定 ID 适配和详情路由读取。`/diagnosis/a12-demo` 仍使用 demo；`/diagnosis/:id` 现在加载持久化快照。真实结果的 Expert Review、Copilot 和 Agent context 优先携带 `diagnosisId`。`npm run build` 通过；本阶段修改文件定向 ESLint 通过。全量 ESLint 的 45 个错误/10 个警告来自既有旧页面与工具文件。

## 21. Remaining Risks

- 当前迁移没有统一 runner，部署必须显式执行 V5.2.1 和 V5.2.2 SQL。
- Repository 仍以 `payload_json` 保存完整快照，后续需要在数据量增长后评估 JSON 查询、归档和保留策略。
- Expert Review 尚未强制校验 Diagnosis 存在性，以保留 legacy run ID 兼容；这应在后续关系收敛阶段处理。
- Agent 内部的 `MultimodalRagTool` 仍直接调用 RAG service，本阶段只保证上下文和 Operations provenance，不把 Agent 内部工具调用误标为独立 Diagnosis 创建。
- 本地 Mongo/Milvus 不可用时，历史/向量证据会进入已有 fallback/UNAVAILABLE 语义；这不影响本阶段稳定身份，但会影响生产 AI 质量。

## 22. Production Release Gates

发布前必须满足：

1. 执行并审计 V5.2.1、V5.2.2 SQL migration。
2. 配置 `FARMAP_AGENT_PERSISTENCE=mysql` 与可用 MySQL，禁止生产写降级到内存。
3. 通过真实用户权限、跨用户 404、数据库不可用 fail-closed 和 POST→GET 快照一致性测试。
4. 验证前端生产 base URL 的 `/api/diagnoses` 代理路由。
5. 准备 Mongo/Milvus/模型网关的运行时健康检查；外部 AI 依赖不可用时必须明确显示证据新鲜度和可用状态。

## 23. Go / No-Go

**Milestone 5.2.2：GO（代码与验证层面）。** 当前已具备独立 `diagnosisId`、MySQL 持久化快照、GET 详情、证据 ID 稳定性、Agent/Operations provenance 和前端兼容路径；HTTP E2E 已在本地完成。

**生产发布：CONDITIONAL GO。** 只有在目标环境执行 V5.2.1/V5.2.2 migration、启用 MySQL 持久化并配置 Mongo/Milvus/模型依赖健康检查后才可发布。下一阶段仅建议进入 Milestone 5.2.3：建立 Recommendation → Action → Approval → Operations Task 的稳定关系，不在本阶段扩展范围。
