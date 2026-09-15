# FarMap V2 — Milestone 5.2 Final Result

审查日期：2026-09-06  
分支：`feature/commercial-product`  
范围：5.2.1 Production Write Safety、5.2.2 Diagnosis Persistence、5.2.3 Product Loop Relationships、5.2.4 Operations Lifecycle & Outcome、5.2.5 Expert Review V2、5.2.6 Knowledge Backend V1。

## 1. Executive Summary

5.2 Final 没有新增业务领域能力，而是完成了关系、数据库边界、失败语义、权限和可重复验证的收口。真实 MySQL 8.0.36 schema 通过 verifier；Knowledge→Diagnosis→Agent→Approval→Task→Outcome→ExpertReview→HistoricalCaseCandidate 的产品链路通过 MySQL 集成和 HTTP smoke。代码结论为 **GO**，Backend Product Loop V2 **FROZEN**；生产结论为 **CONDITIONAL GO**，仍需部署级 migration runner/执行记录、外部依赖 provisioning、ownerless legacy 治理、Expert RBAC 和 datasource-unavailable HTTP 环境门禁。

## 2. Final Reality Audit

| Area | Final state | Evidence |
| --- | --- | --- |
| Diagnosis persistence | CURRENT | `DiagnosisPersistenceService` + `agent_diagnoses` immutable payload |
| AgentRun/Action | CURRENT | `AgentRuntime` persists run and validated action provenance |
| Approval | CURRENT | `JdbcApprovalStore`, composite key and execution claim |
| Operations/Outcome | CURRENT | lifecycle service, unique task outcome, atomic completion/audit |
| ExpertReview V2 | CURRENT | independent review ID, judgments, confidence, CAS and events |
| HistoricalCaseCandidate | CURRENT | server-side eligibility, unique review boundary, no promotion |
| Knowledge Backend | CURRENT | source/document/version/current pointer, hash idempotency, audit |
| Migration runner | MISSING | replaced by non-mutating production schema verifier |
| Expert role RBAC | PARTIAL | authenticated actor + ownership; role directory not modeled |
| Ownerless legacy | PARTIAL | compatibility reads remain and are documented as debt |

全仓搜索确认没有把 Candidate 保存自动转换为 HistoricalCase、Milvus insertion、训练或 prompt update 的产品链路。

## 3. Frozen Domain Graph

```text
KnowledgeSource S1
  ↓ sourceId
KnowledgeDocument D1
  ↓ documentId/currentVersionId
KnowledgeVersion V1
  ↓ versionId/chunkId
Evidence E1
  ↓ evidenceId (Diagnosis snapshot)
Diagnosis DX
  ↓ optional recommendationId
Recommendation R1?
  ↓ validated run context
AgentRun RUN1
  ↓ actionId
AgentAction A1
  ↓ (runId, actionId)
Approval
  ↓ taskId
OperationsTask T1
  ↓ outcomeId
Outcome O1
  ↓ reviewId
ExpertReview ER1
  ↓ candidateId
HistoricalCaseCandidate HC1
```

## 4. Stable Identity Matrix

| Domain | Stable ID | Parent | Nullable? | Ownership | Persistence | Unique boundary |
| --- | --- | --- | --- | --- | --- | --- |
| KnowledgeSource | sourceId | root | owner null only PACKAGED | actor/system | `knowledge_sources` | source type + origin |
| KnowledgeDocument | documentId | sourceId | currentVersionId null until activation | source owner | `knowledge_documents` | PK |
| KnowledgeVersion | versionId | documentId | createdBy null for PACKAGED | document owner | `knowledge_versions` | document + hash/number |
| Evidence | evidenceId | version/chunk | evidence list may be empty | Diagnosis owner | Diagnosis payload | snapshot ID |
| Diagnosis | diagnosisId | sourceRunId | recommendation optional | actor | `agent_diagnoses` | PK |
| Recommendation | recommendationId | diagnosisId | yes | Diagnosis owner | Diagnosis payload | ID validated within snapshot |
| AgentRun | runId/sourceRunId | diagnosis context optional | legacy run may lack DX | actor | `agent_runs` | PK |
| AgentAction | actionId | runId | recommendation optional | run owner | AgentRun payload | action ID in run |
| Approval | runId + actionId | AgentAction | taskId until execution | actor | `agent_approvals` | composite PK |
| OperationsTask | taskId | actionId | legacy action nullable | owner/legacy compatibility | `agent_operations_tasks` | actionId unique for new tasks |
| Outcome | outcomeId | taskId | no | task owner | `agent_operation_outcomes` | task unique |
| ExpertReview | reviewId | diagnosis/task/outcome | task/outcome null for diagnosis-only | reviewer + subject owner | `agent_expert_reviews_v2` | outcome unique; diagnosis-only key |
| HistoricalCaseCandidate | candidateId | reviewId | no for eligible candidate | subject owner | `historical_case_candidates` | reviewId unique |

## 5. Relationship / Provenance Matrix

| Edge | Authoritative source | Constraint/validation |
| --- | --- | --- |
| Source→Document | `knowledge_documents.source_id` | service ownership and source existence |
| Document→Version | `knowledge_versions.document_id` | content hash and number unique; activation locks document |
| Version→Evidence | Diagnosis evidence source metadata | immutable snapshot stores version/chunk ID |
| Evidence→Diagnosis | `agent_diagnoses.payload_json` | persisted once; GET never reruns RAG |
| Diagnosis→Recommendation | Diagnosis payload | recommendationId must belong to same snapshot |
| Diagnosis→Run/Action | AgentRun context/action | server validates diagnosis and recommendation IDs |
| Action→Approval | approval composite PK | one decision and one execution claim |
| Action→Task | task action_id | unique action index, server provenance check |
| Task→Outcome | outcome task_id | unique task index, completion transaction |
| Outcome→Review | review outcome_id | completed Task required; unique canonical review |
| Review→Candidate | candidate review_id | eligibility evaluator + unique candidate index |

## 6. Database Constraint Audit

Direct `information_schema` inspection and `MigrationReadinessVerifierMysqlTest` confirm all required tables, columns and indexes. Verified unique boundaries are approval `(run_id,action_id)`, Task `action_id`, Outcome `task_id`, Outcome-level Review `outcome_id`, Candidate `review_id`, Knowledge Version `(document_id,content_hash)` and `(document_id,version_number)`. Legacy nullable action/owner fields remain readable. No duplicate canonical rows were observed after fixture cleanup.

## 7. Transaction Boundary Audit

Approval decision and execution claim use JDBC row state; Task creation is protected by action uniqueness. Task completion locks the row, writes terminal status, Outcome and task events in one commit. Complete/cancel races have one terminal winner. Review updates use `version` CAS. Candidate creation relies on the database unique review key plus duplicate/retry read. Knowledge activation locks the Document and records the activation event in the same transaction.

## 8. Ownership / Security Audit

JWT interceptor populates `BaseContext`; new Knowledge, Diagnosis, Agent, Task, Review and Candidate writes derive actor/owner server-side. HTTP smoke verified User B cannot read or mutate User A private Knowledge, Task, Review or Candidate (403 in all tested cross-user cases). Provenance mismatch returns a stable 409/422 contract. Expert authentication is implemented as authenticated actor + ownership; Expert role authorization is not fully modeled. Ownerless legacy data remains a documented enterprise release debt.

## 9. Migration Audit

`V5_2_1` through `V5_2_6` are ordered and additive to the stable product loop. No prior migration was edited. The installed MySQL schema has the expected tables/indexes and no duplicate 5.2 columns. `V5_2_1`, `V5_2_3` and `V5_2_4` contain non-idempotent ALTER/CREATE statements, so deployment must apply each version once. Runtime schema bridges are retained for local/test compatibility and are not migration proof.

## 10. Migration Readiness Strategy

采用 **Option B — Production migration verifier**。新增 `MigrationReadinessVerifier` 只读 `information_schema`，不创建、不修改 schema。`GET /api/runtime/migration-readiness` 在本机实际返回 HTTP 200、`status=READY`、空缺表/列/索引均为空。正式部署仍需要 DBA/流水线执行并记录六个 migration 版本，因此生产状态保持 CONDITIONAL GO。

## 11. Fail-Closed Audit

生产 DataSource 存在时，Diagnosis、AgentRun、Approval、Operations、Outcome、ExpertReview V2、Candidate 和 Knowledge 均拒绝内存 fallback。新增 `FailClosedProductionSemanticsTest` 的 2 个测试覆盖关键写/读不可用语义；数据库异常会抛出稳定 persistence exception 或返回 503，不会返回空成功。显式 no-DataSource 构造器仅供测试/demo。

## 12. EMPTY / UNAVAILABLE Semantics

Knowledge DB 健康但没有 eligible current version 时为 `READY_EMPTY`；DB 不可达时为 `UNAVAILABLE`/503。Milvus historical collection 健康且 rowCount=0 时为 `READY_EMPTY`，不可达时为 `UNAVAILABLE`。Candidate 存在不会改变 Milvus 状态。Runtime health 还会单独报告 local AI/Mongo 等外部依赖，不把不可用依赖伪装为 READY。

## 13. Demo / Real Boundary

`FARMAP_AGENT_PERSISTENCE=mysql` 显示 Agent `READY`；file/no-DataSource 路径显示 `DEMO`。`a12-demo` 与 packaged orchard Knowledge 是显式兼容/系统数据，不能伪装成 user-managed REAL 数据。Candidate 只写 MySQL Candidate 表，不改变 HistoricalCase count 或 Milvus collection。

## 14. Knowledge Version Snapshot Validation

HTTP smoke 使用唯一测试 Source/Document：激活 V1 后运行 Diagnosis，断言 Evidence 的 `sourceId/documentId/versionId` 指向 V1；再创建并激活 V2，重新 GET 原 Diagnosis 仍指向 V1。新增 Diagnosis 路径命中当前 V2。该脚本在真实 Spring Boot + MySQL 上通过，测试 fixture 已清理。MySQL `ProductLoopEndToEndMysqlTest` 另行验证 repository reload 后同一快照。

## 15. Diagnosis Persistence Validation

`agent_diagnoses` 保存 stable `diagnosisId`、独立 `sourceRunId` 和 immutable `payload_json`。GET 读取 snapshot，不重新运行 RAG；测试覆盖 repository replacement/reload、跨用户读取拒绝和数据库不可用。HTTP 验证使用真实 `/diagnosis/analyze` 与 `/api/diagnoses/{id}`。

## 16. Recommendation Provenance Validation

Recommendation 不新增表，权威来源仍是 Diagnosis snapshot。若存在 recommendationId，Agent/Task/Review 服务端验证其属于同一 Diagnosis；Diagnosis-level run 可以保持 `recommendationId=null`。同名 Recommendation 不参与关系恢复；测试覆盖 optional recommendation 和 same-title collision 规则。

## 17. Approval / Task Idempotency

HTTP smoke 两次 approve 同一个 `(runId,actionId)` 返回相同 Task；MySQL `ProductionWriteSafetyMysqlTest` 还验证重复 decision、execution claim 以及并发 claim 只有一个 winner，`agent_operations_tasks` action count 为 1。approval composite PK 和 task action unique index 是跨实例边界。

## 18. Operations Lifecycle Validation

允许的状态为 `OPEN → ASSIGNED → IN_PROGRESS → COMPLETED`，并保留定义的 `CANCELLED`。HTTP 验证 assign/start/complete；file 与 MySQL 测试验证非法 transition、duplicate completion 和 terminal state。complete/cancel 并发测试确认只有一个 terminal winner。

## 19. Outcome Atomicity

Task completion 同一事务写 Task terminal 状态、一个 Outcome 及 `TASK_COMPLETED`/`OUTCOME_CREATED` 事件。重复 completion 返回同一 `outcomeId`；MySQL 查询确认每个 task 只有一行 Outcome。取消路径不会产生 Outcome。

## 20. Expert Review Concurrency

ExpertReview V2 独立生成 `review-UUID`，Outcome-level Review 由 `UNIQUE(outcome_id)` canonicalize。judgments、corrections、notes 和 confidence 可编辑；provenance/identity 不可编辑。`version` CAS 让同一旧版本并发更新只有一个成功，另一个返回 `REVIEW_CONFLICT`/409；审计事件保存 actor、version、before/after。

## 21. Candidate Idempotency

Candidate 使用 `candidate-UUID`，server 重新计算 eligibility，客户端不能提交 eligible/status。`UNIQUE(review_id)` 保证重复请求返回同一 Candidate；并发 MySQL 测试确认最终只有一行。Diagnosis-only Review 不产生 Candidate。

## 22. Historical Learning Boundary

Candidate 是 reference-backed 的学习候选，不是 HistoricalCase。创建 Candidate 不复制大 JSON snapshot、不写 `farmap_image_vectors_new`、不触发 embedding、indexing、training、fine-tuning 或 prompt update。HistoricalCase promotion 必须作为后续独立批准流程。

## 23. Restart Persistence

`ProductLoopEndToEndMysqlTest` 创建 Knowledge V1/V2、Diagnosis、Recommendation、AgentRun/Action、Approval、Task、Outcome、Review、Candidate，然后替换 repository/service 并重新读取。Source/Document/Version、Diagnosis/Recommendation、Run/Action、Task/Outcome、Review/Candidate 的 IDs 和关系均保持不变；HTTP 的 Diagnosis V1 snapshot gate 也在应用重启后的读取语义上成立。

## 24. External Dependency Health

本机隔离 HTTP 启动实际报告：MySQL/Knowledge/Operations 为 READY，Historical Milvus collection 为 READY_EMPTY（rowCount=0），local AI 为 UNAVAILABLE；Mongo `mongo:27017` 在本机不可解析，未被伪报为 READY。模型 gateway 仍按环境配置。外部依赖 provisioning 是生产门禁，不在本阶段修复。

## 25. Full Product Loop E2E

真实 HTTP smoke 通过 Source→Document→V1→Diagnosis→Agent Run→Approval→Task assign/start/complete→Outcome→ExpertReview→Candidate；同时执行 duplicate approve/complete/candidate 和跨用户读取。MySQL `ProductLoopEndToEndMysqlTest` 2 个测试覆盖完整推荐链和 recommendation-null 链，并检查 V1→V2 snapshot、reload 与 Candidate boundary。

## 26. Cross-user HTTP E2E

两个不同注册/登录用户在真实 HTTP 上验证了：private Source 读取、Source mutation、Document creation、Task/Review/Candidate read 均拒绝，实际状态为 403。请求体不能伪造 owner、reviewer 或任何 stable ID。

## 27. Failure HTTP E2E

HTTP smoke 的 18 个断言全部通过（401、403、跨文档 409、成功 200、快照和重复语义）。数据库不可用分支已写入可重复脚本，但本次没有把坏 datasource 应用作为 HTTP 运行实例：当前应用在不可达 MySQL 下会在 MyBatis/DataSource 初始化阶段启动失败，因此无法诚实地宣称该 HTTP 503 场景已通过。对应 Service 层 fail-closed 测试已通过；生产发布必须在可启动的隔离故障环境补跑该门禁。

## 28. MySQL Tests

本机 MySQL 8.0.36 真实 JDBC 目标回归：**10 个测试方法、0 failures、0 errors、0 skipped**，覆盖 ProductionWriteSafety、DiagnosisPersistence、ProductLoopRelationship、OperationsLifecycle、ExpertReviewV2、KnowledgeBackend、KnowledgeEvidence provenance、MigrationReadiness 和 ProductLoopEndToEnd；其中包含并发 approval claim、Outcome uniqueness、Review/Candidate persistence 和 reload。

## 29. Backend Tests

file/default Maven targeted 关键回归：**42 tests passed、0 failures、0 errors、0 skipped**（含 5.2.1–5.2.5、Knowledge、migration verifier、fail-closed）。新增/Final-only 单测为 MigrationReadinessVerifier 2 个、FailClosed 2 个；MySQL ProductLoopEndToEnd 2 个和并发安全断言均通过。

## 30. Frontend Regression

未进行 UI redesign。`farmap-frontend-dev` 的 `npx tsc -b`、定向 ESLint（Overview、Diagnosis、Operations、Agent、Knowledge & Expert、Copilot）和 `npm run build` 均通过。现有 REAL/DEMO/EMPTY/UNAVAILABLE/STALE/READY_EMPTY contract 保持兼容。

## 31. Full Maven Suite

最终 `mvn test`：**total 95，failures 0，errors 0，skipped 12**，BUILD SUCCESS。跳过项是环境依赖型 MySQL/Local AI 集成假设，不计为失败；MySQL 专项已在真实配置下单独执行并通过。

## 32. Files Changed

Final-only 硬化文件：

- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/migration/MigrationReadinessVerifier.java`：只读 schema/index readiness verifier。
- `RuntimeHealthController.java`：诚实的 Agent/Knowledge/Operations/Review/Candidate/Migration 状态，修正 MySQL Agent 误报 DEMO。
- `KnowledgeController.java`、`DiagnosisController.java`、`DiagnosisRagService.java`：503/fail-closed 依赖错误合同。
- `ExpertReviewService.java`、`ExpertReviewController.java`、`ExpertReviewV2Service.java`、`HistoricalCaseCandidateService.java`、`OperationsTaskService.java`：生产读取/写入不可用时拒绝伪成功。
- `FailClosedProductionSemanticsTest.java`、`MigrationReadinessVerifierTest.java`、`MigrationReadinessVerifierMysqlTest.java`：失败和 schema readiness 保护。
- `ProductLoopEndToEndMysqlTest.java`、`ProductionWriteSafetyMysqlTest.java`：MySQL 完整链、recommendation-null、reload、并发 approval/task 保护。
- `KnowledgeBackendV1MysqlTest.java`：兼容系统 PACKAGED Knowledge 后对 user-owned evidence 的稳定断言。
- `scripts/milestone-5.2-final-http-smoke.ps1`：可重复 HTTP、跨用户、快照、重复请求 smoke；补充 Windows PowerShell 5 兼容和 fixture cleanup。
- `docs/backend-product-loop-v2-final-architecture.md`、`docs/backend-product-loop-v2-release-checklist.md`、本结果文档：冻结架构、发布门禁和审计证据。

前置 milestone 的其他 dirty 文件均保留，Final 没有删除、reset 或提交它们。

## 33. Remaining Technical Debt

1. 正式 migration runner/版本 checksum/applied_at 仍由部署系统承担。
2. ownerless legacy rows 尚未 backfill 或完成 tenant policy。
3. 已认证 actor 尚未升级为可审计的 Expert role/RBAC；assignee 仍是字符串。
4. datasource 不可用时完整应用会因 MyBatis 初始化而无法启动，需要独立故障注入环境证明 HTTP 503。
5. Mongo、local AI/model gateway 的生产 provisioning 和告警需要部署完成。
6. HistoricalCase promotion/ingestion、Agent V2、Multimodal RAG V2 和 Evaluation Harness 尚未实现，且不属于本 milestone。

## 34. Production Release Gates

上线前必须执行六个 migration 并使 `/api/runtime/migration-readiness` 为 READY；确认 `FARMAP_AGENT_PERSISTENCE=mysql`、JWT/CORS/base URL、MySQL backup、Mongo/Milvus/model gateway health；重跑 MySQL targeted、HTTP smoke（包括可启动故障环境的 503）；审阅 ownerless legacy/Expert RBAC 策略。任何 DB fallback、跨用户访问、provenance mismatch 被接受、重复 canonical 对象、Diagnosis V1 被改写或 Candidate 写入 Milvus 都阻断发布。

## 35. Backend Foundation Freeze Decision

**GO / FROZEN。** Stable IDs、关系约束、snapshot、事务、权限、失败语义、Review/Candidate 监督信号、知识版本 provenance、重启读取和回归测试均满足冻结要求；没有发现 Final NO-GO 条件。

## 36. GO / CONDITIONAL GO / NO-GO

| Gate | Decision |
| --- | --- |
| Milestone 5.2 Final | **GO** |
| Backend Product Loop V2 | **FROZEN** |
| Production | **CONDITIONAL GO** |
| Code/build/tests | **GO** |
| HTTP smoke | **GO for 18 assertions + full chain; datasource-unavailable HTTP pending** |
| Migration readiness | **READY in verified MySQL; deployment execution conditional** |

下一主线只记录为：**Agent V2 + Multimodal RAG V2 + Evaluation Harness**。本阶段不实现下一主线。
