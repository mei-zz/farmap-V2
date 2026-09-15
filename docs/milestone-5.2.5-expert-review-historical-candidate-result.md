# FarMap V2 — Milestone 5.2.5 结果

## 1. Reality Audit

审查了 Expert Review、Diagnosis、Operations、Outcome、Historical Case、Agent ownership、JWT、前端 Knowledge & Expert 页面以及现有 migration/schema。

| 能力 | 当前状态 | 事实 |
| --- | --- | --- |
| ExpertReview identity | PARTIAL（旧模型） | 旧 `agent_expert_reviews` 以 `diagnosis_id` 为主键；没有独立 review identity。 |
| Diagnosis linkage | CURRENT / LEGACY | `agent_diagnoses` 已有稳定 `diagnosisId`；旧 Review 仍兼容 `rag-*`/旧 diagnosis 引用。 |
| Task linkage | MISSING（旧模型）/ CURRENT（V2） | 旧 Review 没有稳定 Task 关系；V2 通过 `taskId` 关联。 |
| Outcome linkage | MISSING（旧模型）/ CURRENT（V2） | 旧 Review 没有 Outcome；V2 要求完整 Outcome Review 使用真实 `outcomeId`。 |
| Expert actor | PLACEHOLDER（旧模型）/ CURRENT（V2） | 旧记录可有字符串 reviewer；V2 使用 JWT 的 `BaseContext` actor。 |
| Review persistence | PARTIAL（旧模型）/ CURRENT（V2） | 旧服务存在数据库失败时的内存 fallback；V2 生产构造拒绝 fallback。 |
| Review update semantics | MISSING（旧模型）/ CURRENT（V2） | V2 支持判断和 notes 更新，identity/provenance 不可变。 |
| Review audit | MISSING（旧模型）/ CURRENT（V2） | V2 写入 `expert_review_events`。 |
| HistoricalCaseCandidate | MISSING（旧模型）/ CURRENT（V2） | 新增 Candidate 服务、API、表和资格评估器。 |
| Candidate persistence | MISSING（旧模型）/ CURRENT（V2） | `historical_case_candidates` 持久化，`review_id` 唯一。 |
| Candidate eligibility rules | MISSING（旧模型）/ CURRENT（V2） | 服务端集中评估完整性、Outcome、判断和置信度。 |
| Candidate → HistoricalCase promotion | MUST NOT IMPLEMENT | 本阶段没有 promotion、embedding、Milvus 写入或训练。 |

旧前端 Knowledge & Expert 页面原来只调用 `/api/expert-review`，使用 diagnosisId 作为选择键，并把 `CONFIRMED` 描述为历史候选；这与新的 Outcome 监督语义不一致。V2 采用增量 API/表，保留旧接口可读写，避免破坏既有记录。

## 2. Previous Expert Review Model

旧模型由 `ExpertReviewService` 管理，表为 `agent_expert_reviews`，身份和读写入口是 `/api/expert-review/{diagnosisId}`。字段主要是 AI diagnosis、expert diagnosis、evidence、recommendations、before/after 和状态。它无法稳定表达一个完成后的 Task/Outcome，也不能区分独立的多条复核 identity；持久化失败时还会回落到内存。旧 `ExpertReviewIntegrationTest` 只覆盖了这一兼容层的保存和读取。

## 3. Root Problems

1. Review 与 Diagnosis 绑定为同一主键，无法表达独立 review identity。
2. 没有服务端验证 `Diagnosis → Task → Outcome` 的完整 provenance。
3. 没有结构化 diagnosis/recommendation/outcome judgment、confidence 和可审计更新。
4. 没有 canonical review 的数据库唯一边界，也没有 Candidate 幂等边界。
5. 没有 HistoricalCaseCandidate 领域对象；旧 UI 容易把 review 误解为已进入历史案例库。
6. 旧服务和旧页面存在 demo/legacy 与 production 状态混用风险。

## 4. Architecture Decision

- ExpertReview 的 canonical identity 是服务端生成的独立 `reviewId`。完整复核保留 `diagnosisId`、`taskId`、`outcomeId` 和可选 `recommendationId`，但不复制整个 Agent provenance。
- 支持 `DIAGNOSIS_ONLY` 和 `OUTCOME_REVIEW` 两个 scope。Diagnosis-only 保留旧的早期人工核验能力，但不能产生 Candidate。
- Outcome-level Review 采用 V1 canonical 1:1：一个完成的 Outcome 至多一个 Review，数据库 `UNIQUE(outcome_id)` 强制执行。
- Diagnosis-only Review 在没有既有多专家语义的前提下采用一个 current canonical review，使用 `diagnosis_only_key` 唯一约束；旧表不改写。
- Review 允许更新 judgments、corrections、notes 和 confidence；`reviewId`、Diagnosis/Task/Outcome/Recommendation identity 和 scope 创建后不可修改。
- Review 更新使用 `version` CAS；过期版本返回 `REVIEW_CONFLICT`，HTTP 409。
- Candidate 以 `reviewId` 为 canonical parent，使用 reference-backed + minimal metadata，不复制大 JSON snapshot。
- Candidate 只表示“具备未来历史案例验证资格”，不等于 HistoricalCase。

## 5. ExpertReview Domain Model

V2 `agent_expert_reviews_v2` 最小字段为：

```text
reviewId
diagnosisId
taskId?
outcomeId?
recommendationId?
scope
diagnosisJudgment
recommendationJudgment?
outcomeJudgment?
diagnosisCorrection?
recommendationCorrection?
expertConfidence?
notes?
reviewerUserId
subjectOwnerUserId
version
createdAt
updatedAt
```

Review 保存专家意见，不覆盖原始 Diagnosis 或 Outcome snapshot。

## 6. Review Scope

`DIAGNOSIS_ONLY` 要求只有 `diagnosisId`；`taskId` 和 `outcomeId` 必须为空。`OUTCOME_REVIEW` 必须同时有 `taskId`、`outcomeId`，Task 必须为 `COMPLETED`，且 Outcome 必须真实存在。

## 7. Diagnosis Judgment

服务端允许：`CORRECT`、`PARTIALLY_CORRECT`、`INCORRECT`、`UNCERTAIN`、`NOT_REVIEWED`。`diagnosisCorrection` 只保存专家修正建议，原始 `agent_diagnoses` immutable snapshot 不会被更新。

## 8. Recommendation Judgment

服务端允许：`APPROPRIATE`、`PARTIALLY_APPROPRIATE`、`INAPPROPRIATE`、`UNCERTAIN`、`NOT_REVIEWED`。当 `recommendationId = null` 时，Recommendation judgment 保持 `null`，不会伪造 recommendation。若有 Recommendation，Candidate 资格要求该 judgment 已明确。

## 9. Outcome Judgment

服务端允许：`SUCCESSFUL`、`PARTIALLY_SUCCESSFUL`、`UNSUCCESSFUL`、`INCONCLUSIVE`。Outcome judgment 必须针对真实 `outcomeId`，不能按 title、field name 或最近记录推断。

## 10. Expert Confidence

使用 numeric `expertConfidence`，服务端验证 `0 <= value <= 1`。Candidate 默认阈值为 `0.7`，集中在 `HistoricalCaseCandidateEligibilityEvaluator`，没有散落在 Controller 或前端。

## 11. Review Provenance

生产 Review 创建/读取会通过 `DiagnosisPersistenceService` 验证 Diagnosis，通过 `OperationsTaskService` 的 actor-aware 方法验证 Task/Outcome 及 ownership，并验证：

```text
Task.status = COMPLETED
Task.diagnosisId = Review.diagnosisId
Outcome.taskId = Review.taskId
Outcome.outcomeId = Review.outcomeId
Recommendation belongs to Review.diagnosisId（存在时）
```

任意不一致都 fail closed，返回 `REVIEW_PROVENANCE_CONFLICT`，不会静默修正或选择“最接近”的对象。

## 12. Review Identity

服务器生成 `review-UUID`。客户端不能提交或覆盖 `reviewId`，也不能把 diagnosisId、taskId 或 outcomeId 当作 review identity。

## 13. Review Ownership

`reviewerUserId` 来自 JWT / `BaseContext`，不信任请求 body。Review 保存 `subjectOwnerUserId`，读取和更新使用同一 ownership boundary。当前系统没有独立 Expert role 模型；认证存在，专家角色授权仍是 enterprise hardening 项。

## 14. Review Mutability

判断、correction、notes、confidence 可更新；identity/provenance 不可更新。原始 AI Diagnosis、Recommendation 和 Outcome 不会被 Review 覆盖。

## 15. Review Concurrency

更新要求请求携带 `version`，数据库使用 `UPDATE ... WHERE review_id=? AND version=?`。同一旧版本的并发更新只允许一个成功，另一请求返回 `REVIEW_CONFLICT`/409。Review canonical uniqueness 使用数据库 unique key，而不是 JVM lock。

## 16. Review Audit

新增 `expert_review_events`，记录：

```text
eventId
reviewId
eventType（EXPERT_REVIEW_CREATED / EXPERT_REVIEW_UPDATED / CANDIDATE_CREATED）
actorId
version
beforeJson
afterJson
createdAt
```

因此创建/更新/候选生成可回答 who、when、which review、what changed；没有记录 secrets 或 raw prompts。

## 17. HistoricalCaseCandidate Domain Model

`historical_case_candidates` 字段为：

```text
candidateId
reviewId
diagnosisId
taskId?
outcomeId?
status
eligibilityReason?
learningValue?
ownerUserId
createdAt
updatedAt
```

Candidate 是独立稳定 identity `candidate-UUID`，不是 reviewId、taskId 或 outcomeId 的别名。

## 18. Candidate Eligibility Policy

`HistoricalCaseCandidateEligibilityEvaluator` 集中执行以下规则：

1. scope 必须是 `OUTCOME_REVIEW`。
2. Task 必须 `COMPLETED`，Outcome 必须存在且 provenance 完整。
3. Diagnosis judgment 不能为 null 或 `NOT_REVIEWED`。
4. Outcome judgment 不能为 null，且不能是 `INCONCLUSIVE`。
5. `expertConfidence >= 0.7`。
6. 存在 `recommendationId` 时，Recommendation judgment 也必须已明确。

不满足资格时返回 `NOT_ELIGIBLE` 和稳定 `eligibilityReason`，不会插入 Candidate。

## 19. Failure Case Learning

Diagnosis `INCORRECT`、Recommendation `INAPPROPRIATE` 或 Outcome `UNSUCCESSFUL` 的完整复核仍可成为 Candidate，`learningValue = CORRECTION_CASE`。这保留失败学习信号，不把“可学习”错误缩减为“AI 成功”。完整但普通的成功闭环标记 `SUCCESS_CASE`。

## 20. Candidate Idempotency

重复执行 `evaluateForCandidate(reviewId)`：

- 已有 Candidate 返回同一个 `candidateId`。
- 不合格 Review 每次返回 `NOT_ELIGIBLE`，不会生成占位 Candidate。
- HTTP `POST /api/expert-reviews/{reviewId}/candidate` 和 `POST /api/historical-case-candidates` 都由服务端重新计算资格，客户端不能提交 `eligible=true`。

## 21. Candidate Concurrency

`historical_case_candidates.review_id` 有 `UNIQUE` 约束。并发首次创建使用数据库唯一边界和 duplicate/retry 处理；InnoDB gap-lock 死锁在验证中发现后已改为无缺失行锁的原子插入、唯一冲突读取和短暂重试。实测两个并发请求最终只有一个 Candidate。

## 22. Candidate vs HistoricalCase Boundary

Candidate 创建不会：

- 创建 HistoricalCase；
- 写入 `farmap_image_vectors_new` 或其他 Milvus collection；
- 改变历史向量库 `READY_EMPTY` 状态；
- 触发 embedding、fine-tuning、prompt update、online learning 或模型训练。

未来必须经过独立验证/promotion 流程后，才可进入 HistoricalCase ingestion。

## 23. Data Model Changes

新增：

- `agent_expert_reviews_v2`：独立 Review identity、判断、confidence、version 和 ownership。
- `expert_review_events`：创建、更新、Candidate 生成审计。
- `historical_case_candidates`：候选 identity、引用关系、资格原因和 learning value。

旧 `agent_expert_reviews` 保留，作为 legacy compatibility boundary。

## 24. Schema / Migration

新增版本化脚本：

```text
zhgy/zhgy/src/main/resources/db/migration/V5_2_5__expert_review_historical_candidate.sql
```

脚本只添加新表和索引，不修改前置 migration。当前仓库仍没有正式 migration runner；运行时的 `CREATE TABLE IF NOT EXISTS` 仅是兼容 bridge。因此代码验证为 GO，生产发布为 CONDITIONAL GO，正式部署必须先执行该版本 schema。

## 25. API Changes

V2 Review：

```text
POST  /api/expert-reviews
GET   /api/expert-reviews?diagnosisId=...
GET   /api/expert-reviews/{reviewId}
PUT   /api/expert-reviews/{reviewId}
PATCH /api/expert-reviews/{reviewId}
POST  /api/expert-reviews/{reviewId}/candidate
```

Candidate：

```text
POST /api/historical-case-candidates                 { reviewId }
GET  /api/historical-case-candidates/{candidateId}
GET  /api/historical-case-candidates?status=...
GET  /api/historical-case-candidates/by-review/{reviewId}
```

旧 `/api/expert-review/**` API 保持不变。

## 26. Server-side Validation

服务器生成 review/candidate identity、actor、timestamps、version、status 和 eligibility。服务器验证判断 enum、confidence、scope、Task 状态、Outcome 关系、Recommendation 所属 Diagnosis、ownership 以及 immutable identity。前端只负责提交表单，不参与资格决定。

## 27. Security

V2 API 已加入 JWT 拦截路径。未认证请求返回 401；跨用户读取/更新返回 403；跨 Diagnosis/Task/Outcome 组合返回 409 `REVIEW_PROVENANCE_CONFLICT`。当前系统对所有已认证用户开放该 API，尚未实现 Expert role/RBAC；ownerless legacy task 的兼容读取仍是安全债务，未在本阶段回填。

## 28. Legacy Compatibility

旧 `ExpertReviewService`、旧表和旧 endpoints 未删除或重解释，旧 `rag-*` diagnosis reference 继续可读写。旧模型仍有 diagnosis-key identity、free-text 语义和 persistence fallback 限制，报告中将其标记为 legacy/production debt；新业务应使用复数 V2 API。

## 29. Demo Compatibility

Demo 页面继续与 REAL 数据边界分离。V2 service 的无参数构造只用于 deterministic tests/local demo；Spring production 构造禁止内存 fallback。Candidate 生成不会把 demo review 写入 HistoricalCase 或 Milvus。Knowledge & Expert 页面保留旧视觉和旧接口兼容，同时增加 V2 列表/版本更新适配，并用 `reviewId` 作为选择键以避免同一 Diagnosis 多条 Review 冲突。

## 30. Stable Product Loop Regression

5.2.1–5.2.4 的关系和生命周期代码未被重写或覆盖。当前验证仍保持：

```text
Diagnosis → Recommendation? → AgentRun → AgentAction → Approval
→ OperationsTask → Outcome
```

Review 只追加在 Outcome 之后，不改变 Task 完成、Outcome 原子创建、Action 幂等和 terminal race 语义。

## 31. Tests

定向单元回归：

```text
ExpertReviewV2Test                         5 passed
ExpertReviewIntegrationTest                1 passed
ProductionWriteSafetyTest                  7 passed
DiagnosisPersistenceTest                   5 passed
DiagnosisAgentLinkTest                     1 passed
ProductLoopRelationshipTest                 5 passed
OperationsLifecycleTest                    9 passed
总计                                      33 passed
```

V2 覆盖 stable identity、完整 Outcome Review、provenance conflict、cross-user、Diagnosis-only、Recommendation optional、immutable identity、optimistic concurrency、failure-case Candidate、inconclusive NOT_ELIGIBLE 和 Candidate idempotency。

## 32. MySQL Tests

在真实本地 MySQL（`FARMAP_AGENT_PERSISTENCE=mysql`）执行：

```text
ExpertReviewV2MysqlTest                    1 passed
ProductionWriteSafetyMysqlTest             1 passed
DiagnosisPersistenceMysqlTest              1 passed
ProductLoopRelationshipMysqlTest           1 passed
OperationsLifecycleMysqlTest               1 passed
总计                                       5 passed
```

V2 MySQL 测试验证了 Review reload、跨用户拒绝、version 冲突、Candidate 并发唯一性、审计事件唯一创建以及失败案例学习标签。测试创建的临时 Diagnosis/Task/Outcome/Review/Candidate 已清理。

## 33. HTTP E2E

真实 Spring Boot + MySQL HTTP 链路通过：

```text
POST /diagnosis/analyze                      200
POST /api/agent/runs                         200
GET  /api/agent/runs/{runId}                 WAITING_FOR_APPROVAL
POST /api/agent/runs/{runId}/actions/.../approve 200
POST /api/operations/tasks/{taskId}/assign   200
POST /api/operations/tasks/{taskId}/start    200
POST /api/operations/tasks/{taskId}/complete 200
GET  /api/operations/tasks/{taskId}/outcome  200
POST /api/expert-reviews                     200
POST /api/expert-reviews/{reviewId}/candidate 200
重复 Candidate 请求                         同一 candidateId
非法 D1/T1/O2 provenance                    409
跨用户 Review 读取                          403
未认证 Review 读取                          401
```

HTTP 运行环境没有可解析的 `milvus`/`mongo` host，诊断按现有 grounded-template/UNAVAILABLE 外部依赖边界完成并持久化；该外部依赖可用性是发布风险，不是本阶段改动范围。

## 34. Frontend

新增 V2 service/type/adapter contract：

- `listExpertReviewsV2`、`updateExpertReviewV2` 和 Candidate evaluate client helper；
- Knowledge & Expert 类型支持 reviewId、scope、judgments、confidence、version、Candidate status；
- V2 Review 使用 stable `reviewId` 作为选择键；旧 diagnosis-keyed records 仍可 fallback 读取；
- Request utility 增加 PUT；未改变路由和旧页面视觉结构。

本阶段没有做 Expert 后台、分配系统、复杂 RBAC 或 Historical Cases redesign。

前端质量门禁：

```text
npx tsc -b                                      PASS
npx eslint（5 个 V2 适配文件）                  PASS
npm run build                                   PASS
```

## 35. Risks

1. 正式 migration runner 未完成，生产需显式执行 V5_2_5 schema。
2. Expert role/RBAC 未建模，当前安全边界是 JWT actor + object ownership。
3. ownerless legacy Operations 数据仍按既有兼容策略读取，需后续 ownership backfill。
4. 旧 `/api/expert-review` 仍保留 free-text/fallback 语义，迁移期应禁止新业务继续依赖它。
5. Milvus historical collection 当前可能是 READY_EMPTY/UNAVAILABLE；Candidate 不改变这一事实。
6. 外部模型、Milvus、Mongo 健康状态会影响实时诊断，但不影响已验证的 Review/Candidate persistence contract。

## 36. Release Gates

发布前必须完成：

- 以正式 migration runner 执行 `V5_2_5__expert_review_historical_candidate.sql`；
- 在目标环境重新运行 Review/Candidate MySQL 与 HTTP E2E；
- 决定 enterprise Expert role/RBAC 和 ownerless legacy 的治理策略；
- 保持历史案例库 `READY_EMPTY` 语义，直到有独立 promotion 审批和 ingestion 设计；
- 不把 runtime schema bridge 当作部署完成证明。

本阶段明确没有进入 HistoricalCase promotion、Milvus ingestion、Agent V2、Multimodal RAG V2 或模型训练。

## 37. Go / No-Go

**Milestone code：GO。**

本阶段的 Review identity、持久化、Outcome 关系、provenance、ownership、结构化 judgments、confidence、audit、CAS 更新、Candidate eligibility、failure-case learning、Candidate unique/idempotent boundary、legacy compatibility 和 5.2.1–5.2.4 targeted regression 均已实现并通过验证。原始 Diagnosis/Outcome 未被覆盖，Candidate 不会自动创建 HistoricalCase 或写入 Milvus。

**Production：CONDITIONAL GO。**

条件是正式执行 migration、补充企业级 Expert authorization/legacy ownership 策略，并在目标环境确认外部 AI/Milvus 依赖健康。当前不满足任何 NO-GO 条件；若部署跳过 schema、允许客户端伪造 actor/provenance、放开重复 canonical review/candidate，或把 Candidate 自动 promotion 为 HistoricalCase，应判定 NO-GO。

## 文件清单

### 新增

- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/expert/ExpertReviewWriteException.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/expert/ExpertReviewV2Service.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/historical/HistoricalCaseCandidateEligibilityEvaluator.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/historical/HistoricalCaseCandidateService.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/controller/ExpertReviewV2Controller.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/controller/HistoricalCaseCandidateController.java`
- `zhgy/zhgy/src/main/resources/db/migration/V5_2_5__expert_review_historical_candidate.sql`
- `zhgy/zhgy/src/test/java/com/mei/zhgy/service/expert/ExpertReviewV2Test.java`
- `zhgy/zhgy/src/test/java/com/mei/zhgy/service/expert/ExpertReviewV2MysqlTest.java`

### 修改

- `zhgy/zhgy/src/main/java/com/mei/zhgy/config/WebMvcConfiguration.java`：V2 Review/Candidate JWT 路径。
- `farmap-frontend-dev/src/utils/reqeust.ts`：PUT request helper。
- `farmap-frontend-dev/src/types/knowledge-expert.ts`：结构化 V2 Review 字段。
- `farmap-frontend-dev/src/features/knowledge-expert/service.ts`：V2 Review/Candidate client contract。
- `farmap-frontend-dev/src/features/knowledge-expert/adapter.ts`：V2 normalization 和资格文案。
- `farmap-frontend-dev/src/features/knowledge-expert/components/KnowledgeExpertCenter.tsx`：V2 列表读取、stable reviewId 选择和更新适配。

### 结果文档

- `docs/milestone-5.2.5-expert-review-historical-candidate-result.md`

未创建新分支，未提交 commit，未执行 reset/restore/clean/覆盖已有工作区修改。
