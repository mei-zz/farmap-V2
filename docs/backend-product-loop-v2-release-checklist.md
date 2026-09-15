# FarMap V2 — Backend Product Loop V2 Release Checklist

适用版本：5.2 Final（5.2.1–5.2.6）  
目标：确认 Backend Product Loop V2 可以冻结，并把未完成的部署/企业治理事项留在发布门禁中。

## A. 版本与工作区

- [ ] 当前分支为 `feature/commercial-product`。
- [ ] `git status --short` 已人工审阅；历史 milestone 的未提交工作保留，没有 reset/restore/clean/checkout 覆盖。
- [ ] `git diff --check` 无 whitespace error。
- [ ] `git diff --stat` 没有意外删除、二进制垃圾或未授权范围外的改动。
- [ ] secret audit 未发现密码、JWT secret、API key、Authorization header 或 `.env` 内容进入代码、脚本和 docs。

## B. Production configuration

- [ ] 使用环境变量/secret manager 提供 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD`；密码不写入仓库。
- [ ] 设置 `FARMAP_AGENT_PERSISTENCE=mysql`，禁止生产使用 file/in-memory Agent repository。
- [ ] `zhgy.jwt.user-secret-key` 使用部署 secret，JWT TTL 和 token header 与网关约定一致。
- [ ] CORS、前端 API base URL、HTTPS 和反向代理 header 已在部署环境验证。
- [ ] Mongo、Milvus 和模型 gateway 的连接参数来自部署配置；未配置的依赖必须显示 `UNAVAILABLE`，不能伪报 READY。

## C. Migration and schema readiness

- [ ] 按顺序执行并记录：`V5_2_1__production_write_safety.sql` → `V5_2_2__diagnosis_persistence.sql` → `V5_2_3__product_loop_relationships.sql` → `V5_2_4__operations_lifecycle_outcome.sql` → `V5_2_5__expert_review_historical_candidate.sql` → `V5_2_6__knowledge_backend_v1.sql`。
- [ ] 部署 migration runner/DBA 记录每个版本只执行一次；当前仓库的 runtime `CREATE TABLE IF NOT EXISTS` bridge 不作为迁移证明。
- [ ] 启动后调用 `GET /api/runtime/migration-readiness`，期望 HTTP 200、`status=READY`、`missingTables=[]`、`missingColumns=[]`、`missingIndexes=[]`。
- [ ] 数据库不可用或 schema 缺失时，发布流程阻断；不要通过切换到内存 fallback 绕过门禁。
- [ ] 备份和恢复演练覆盖 5.2.1–5.2.6 新表及审计事件表。

## D. Database constraint checks

- [ ] `agent_approvals` primary key `(run_id, action_id)`。
- [ ] `agent_operations_tasks.uk_agent_operations_action_id(action_id)`。
- [ ] `agent_operation_outcomes.uk_operation_outcome_task(task_id)`。
- [ ] `agent_expert_reviews_v2.uk_expert_review_v2_outcome(outcome_id)` 与 diagnosis-only unique key。
- [ ] `historical_case_candidates.uk_historical_candidate_review(review_id)`。
- [ ] `knowledge_versions.uk_knowledge_version_hash(document_id, content_hash)` 与 version number unique key。
- [ ] legacy nullable action/owner 字段未被错误回填；新数据 owner 来自服务端 actor。

## E. Health and empty semantics

- [ ] `GET /api/runtime/health` 显示 Agent/Operations/Knowledge/Review/Candidate 的真实数据库状态。
- [ ] Knowledge 有可用 current version 时为 `READY`；无可用版本时为 `READY_EMPTY`；DB 故障为 `UNAVAILABLE`/HTTP 503。
- [ ] Milvus historical collection 健康但 rowCount=0 时为 `READY_EMPTY`；不可达时为 `UNAVAILABLE`。
- [ ] Candidate 数量不会改变 HistoricalCase/Milvus 的状态；`historicalCase.ingestion=disabled-until-candidate-promotion`。
- [ ] Demo/file 模式显示 `DEMO`，不会与 REAL 数据混合。

## F. Critical backend regression commands

在 `zhgy/zhgy` 目录执行：

```powershell
& 'E:\farmap\.tools\apache-maven-3.9.16\bin\mvn.cmd' test
```

MySQL 目标回归（先从 secret manager/环境注入配置）：

```powershell
$env:FARMAP_AGENT_PERSISTENCE='mysql'
# SPRING_DATASOURCE_URL / USERNAME / PASSWORD 由环境提供
& 'E:\farmap\.tools\apache-maven-3.9.16\bin\mvn.cmd' '-Dtest=ProductionWriteSafetyMysqlTest,DiagnosisPersistenceMysqlTest,ProductLoopRelationshipMysqlTest,OperationsLifecycleMysqlTest,ExpertReviewV2MysqlTest,KnowledgeBackendV1MysqlTest,KnowledgeEvidenceProvenanceTest,MigrationReadinessVerifierMysqlTest,ProductLoopEndToEndMysqlTest' test
```

关键 file-mode 回归至少包含：`ProductionWriteSafetyTest`、`DiagnosisPersistenceTest`、`DiagnosisAgentLinkTest`、`ProductLoopRelationshipTest`、`OperationsLifecycleTest`、`ExpertReviewV2Test`、`ExpertReviewIntegrationTest`、`KnowledgeBackendV1Test`、`KnowledgeEvidenceProvenanceTest`、`MigrationReadinessVerifierTest`、`FailClosedProductionSemanticsTest`。

## G. Repeatable HTTP smoke

在两个不同的已认证用户 token 通过环境变量注入后执行：

```powershell
$env:FARMAP_BASE_URL='https://<deployment-host>'
$env:FARMAP_USER_TOKEN='<secret-managed-token-a>'
$env:FARMAP_USER_TOKEN_B='<secret-managed-token-b>'
& powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\milestone-5.2-final-http-smoke.ps1
```

必须看到：

- [ ] 未认证 Knowledge health 为 401。
- [ ] User B 读取/修改 User A private Source、创建 Document 被 403/404 拒绝。
- [ ] 跨 Document 激活 Version 为 409/422，而不是 500。
- [ ] Diagnosis 使用 V1 后，激活 V2 不会改写旧 Diagnosis；新 Diagnosis 使用 V2。
- [ ] Agent Run → Action → Approval → Task assign/start/complete → Outcome → ExpertReview → Candidate 链路完整。
- [ ] duplicate approve、complete、candidate 返回相同 canonical 对象且没有重复行。
- [ ] User B 读取 A 的 Task、Review、Candidate 被拒绝。
- [ ] 在隔离、应用可启动但 datasource 不可用的环境设置 `FARMAP_UNAVAILABLE_BASE_URL`，Knowledge health 必须为 503/UNAVAILABLE；若应用因错误 datasource 无法启动，应在发布记录中标为阻断项，不能把启动失败伪装成通过。

## H. Restart and recovery

- [ ] 创建一条完整 S→D→V→E→DX→R?→RUN→A→Approval→T→O→ER→HC 链路。
- [ ] 重启应用或替换 repository/context 后，所有 stable IDs 和关系保持不变。
- [ ] 重试 approve/complete/candidate 后只得到同一个 canonical Task/Outcome/Candidate。
- [ ] complete/cancel 并发只有一个 terminal winner；`CANCELLED` 不得存在 Outcome。
- [ ] Review stale `version` 更新返回 409 `REVIEW_CONFLICT`。

## I. Frontend contract gate

在 `farmap-frontend-dev` 执行：

```powershell
npx tsc -b
npx eslint src/views/overview/index.tsx src/views/diagnosis/Detail.tsx src/views/operations/index.tsx src/features/overview src/features/agent src/features/diagnosis src/features/operations src/features/knowledge-expert src/components/copilot/CopilotDrawer.tsx
npm run build
```

- [ ] Diagnosis、Agent、Operations、Knowledge & Expert、Overview adapter 的 REAL/DEMO/EMPTY/UNAVAILABLE/STALE/READY_EMPTY contract 未回归。
- [ ] UI 不把 Candidate 显示成已创建 HistoricalCase，不把 API error 显示成空列表。

## J. Rollback and known debts

- [ ] 代码回滚前确认新 schema 仍可被旧版本只读；不要直接删除 5.2 表或索引。
- [ ] 当前没有正式 migration runner；发布必须由 DBA/部署流水线执行并审计六个版本。
- [ ] ownerless legacy 数据、Expert role/RBAC、Workforce directory、外部依赖 provisioning 和 datasource-unavailable HTTP 环境仍是生产门禁。
- [ ] 本阶段不启用 Candidate promotion、HistoricalCase ingestion、Milvus 写入、Agent V2 或 Multimodal RAG V2。

## Release decision

代码和 Backend Product Loop 可以冻结为 **GO / FROZEN**；只有 A–J 的部署门禁全部通过，生产才可从 **CONDITIONAL GO** 提升为 **GO**。任何跨用户访问、ID/provenance 伪造、重复 canonical 对象、旧 Diagnosis 被改写、DB 故障伪装为空成功或 Candidate 自动写入 HistoricalCase 的情况都必须 **NO-GO**。
