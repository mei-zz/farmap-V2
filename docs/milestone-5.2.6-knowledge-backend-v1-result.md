# FarMap V2 Milestone 5.2.6 — Knowledge Backend V1 Result

本阶段在 `feature/commercial-product` 上完成最小 Knowledge Backend V1。范围停止在可持久化、可版本化、可审计的知识源/文档/版本，以及对现有 Multimodal RAG 的稳定 provenance 接入；没有改造 Agent V2、RAG V2、Graph RAG、Milvus 历史案例或模型训练。

## 1. Reality Audit

原实现只有 `KnowledgeEvidenceRetriever`：从 `classpath:knowledge/orchard_management.txt` 读取段落，使用词法检索和可选本地 BGE 缓存。Evidence ID 是排序序号，Source 只有展示字段，数据库中没有 Knowledge Source、Document、Version 或 current pointer。`KnowledgeTool` 将空结果与不可用混在一起，Runtime Health 固定返回 `knowledge=LOCAL`。

## 2. Previous Knowledge Architecture

旧路径是 `classpath text → paragraph chunk → lexical/BGE rerank → DiagnosisEvidenceVO`。没有版本激活、内容 hash 幂等、所有权隔离、审计事件或数据库故障语义。

## 3. Root Problems

旧 Evidence 不能稳定回放；同一段内容重新排序会改变 ID。历史 Diagnosis 不能证明使用了哪一份知识版本。运营人员无法安全发布新版本，也无法区分知识库为空、知识库不可用和本地 demo 数据。

## 4. Architecture Decision

采用三层模型：`KnowledgeSource → KnowledgeDocument → KnowledgeVersion`。Document 保存显式 `current_version_id`；Version append-only；启用版本是显式 command。现有 curated classpath 内容以只读 `PACKAGED` source 通过幂等 importer 注册到数据库，数据库是 REAL 模式检索的权威来源。

## 5. KnowledgeSource Model

字段为 `sourceId、name、sourceType、description、originUri、ownerUserId、status、createdAt、updatedAt`。Source 类型最小支持 `PACKAGED、USER_MANAGED、EXTERNAL_REFERENCE`，外部连接器不在本阶段实现。

## 6. KnowledgeDocument Model

字段为 `documentId、sourceId、title、externalReference、ownerUserId、status、currentVersionId、version、createdAt、updatedAt`。Document 只能属于一个 Source，current pointer 只指向自己的 Version。

## 7. KnowledgeVersion Model

字段为 `versionId、documentId、versionNumber、contentType、content、contentHash、createdBy、createdAt`。全文内容采用规范化换行后保存，SHA-256 由服务端计算。

## 8. Stable Identity

Source、Document、Version 均由服务端生成 `knowledge-source-*`、`knowledge-document-*`、`knowledge-version-*` UUID 身份；Evidence 使用 `knowledge-{versionId}-{chunkId}`，不再使用返回顺序编号。

## 9. Version Immutability

没有 Version update API。重复提交同一 Document 的相同规范化内容只返回已有 Version；旧内容继续保留，不能被 current switch 覆盖。

## 10. Current Version Semantics

Document 的 `currentVersionId` 是唯一当前指针。没有指针的文档不会进入 REAL 检索；旧 Diagnosis 保存自己的 Evidence snapshot，不会随指针切换而改变。

## 11. Version Activation

`POST /api/knowledge/documents/{documentId}/versions/{versionId}/activate` 在文档行锁事务中执行，服务端校验 Version 所属 Document；跨 Document 激活返回 `KNOWLEDGE_PROVENANCE_CONFLICT`。

## 12. Content Hash / Idempotency

服务端规范化 CRLF/CR 为 LF，并计算 SHA-256。`UNIQUE(document_id, content_hash)` 保证重复导入幂等，`UNIQUE(document_id, version_number)` 防止并发版本号碰撞。

## 13. Source / Document Status

Source 与 Document 支持 `ACTIVE/DISABLED`。只有两者都 ACTIVE 且有 current Version 才可检索；禁用不删除历史版本。系统 PACKAGED 对外只读。

## 14. Knowledge Ownership

USER_MANAGED Source/Document 的 owner 来自已认证 `BaseContext`，请求体不能指定 owner。读取过滤为系统对象或当前 actor 自有对象；跨用户读写返回 `FORBIDDEN`。

## 15. System / Packaged Knowledge

现有 `orchard_management.txt` 作为 `PACKAGED`、owner 为 null 的系统知识。它由内部 importer 注册，客户端不能创建、修改、启用或禁用 PACKAGED 对象。

## 16. Classpath Compatibility

无数据库的旧单元测试仍可使用固定 packaged provenance；生产有 DataSource 时，检索先将现有 curated 文本以 hash 幂等注册到 DB，再只读 DB current Version。数据库连接失败不会静默回退到 classpath。

## 17. Knowledge Retrieval Integration

`KnowledgeEvidenceRetriever` 增加 actor-aware retrieval；`DiagnosisRetriever` 保留旧方法并提供默认 actor overload，`DiagnosisRagService` 将真实 userId 传入。现有词法/BGE 排序未被重写，新增的只是 DB current-version 数据边界和 provenance。

## 18. Retrieval Unit / Chunk Strategy

当前 Version 仍按空行段落切 chunk，chunk ID 为 `{versionId}-chunk-{ordinal}`。这保持既有检索行为，同时使 chunk 能稳定追溯到 Version；chunk 表、向量索引和 reranker 不在本阶段新增。

## 19. Evidence Provenance

`DiagnosisEvidenceVO.Source` 新增 `sourceId、documentId、versionId`；metadata 同步保存这些 ID、chunkId、contentHash、knowledgeStatus。Evidence ID 和 provenance 随 Diagnosis payload 一起持久化。

## 20. Diagnosis Snapshot Compatibility

`DiagnosisPersistenceService` 原有 immutable `payload_json` 直接保存扩展后的 Evidence Source，因此旧 Diagnosis 继续可读，新 Diagnosis 使用创建时 current Version，后续激活新版本不会重写旧快照。

## 21. Recommendation Grounding Compatibility

Recommendation 仍只引用 Evidence ID；因为 Evidence ID 现在由 Version/chunk 组成，既有 Recommendation provenance 关系不变，同时能够追溯当时的知识版本。

## 22. EMPTY / UNAVAILABLE Semantics

Knowledge Tool 现在区分：`READY` 有结果、`READY_EMPTY/NO_KNOWLEDGE_AVAILABLE` 无可用 current 文档、`UNAVAILABLE/KNOWLEDGE_UNAVAILABLE` 数据库或后端不可用。空结果不会伪造知识证据。

## 23. Fail-Closed Behavior

生产构造的 `KnowledgeBackendService` 不允许内存 fallback；DB 连接、DDL、插入或激活失败都返回 `KNOWLEDGE_PERSISTENCE_UNAVAILABLE`。仅无 DataSource 的显式测试构造器允许 fallback。

## 24. Knowledge Health

新增 `GET /api/knowledge/health`，Runtime Health 不再硬编码 LOCAL，返回 `READY、READY_EMPTY、UNAVAILABLE`（测试 fallback 为 DEMO）和数据库模式信息。知识健康不会把 Milvus 历史库状态混为一谈。

## 25. Audit

`knowledge_events` 记录 Source/Document/Version 创建、状态变化和版本激活，包含 actor、entity、event type、时间和 metadata。没有引入 Event Sourcing；事件用于最小审计和排障。

## 26. Data Model Changes

新增 `knowledge_sources、knowledge_documents、knowledge_versions、knowledge_events` 四表；新增 KnowledgeBackendService、KnowledgeBackendException、KnowledgeController；Evidence Source 和 DiagnosisRetriever 增加稳定 provenance/actor 边界。

## 27. Schema / Migration

新增 `zhgy/zhgy/src/main/resources/db/migration/V5_2_6__knowledge_backend_v1.sql`。服务保留 runtime `CREATE TABLE IF NOT EXISTS` bridge 以兼容当前环境；正式发布仍必须由部署 migration runner 执行。

## 28. API Changes

新增 Source 列表/详情/创建、Document 列表/详情/创建、Version 列表/详情/创建、显式 activate、Source/Document enable/disable、Knowledge health API。没有 generic PATCH/delete，也没有 Candidate→HistoricalCase API。

## 29. Server-side Validation

服务端校验 sourceType、必填名称/标题/内容、owner、状态、Document/Version 所属关系、content hash、current pointer 和 packaged 只读策略。客户端不能提交稳定 ID、owner、current pointer 或 eligibility 类字段。

## 30. Security

`WebMvcConfiguration` 已保护 `/api/knowledge/**`。Controller 从 `BaseContext` 取得 actor；跨用户资源拒绝。完整 enterprise Knowledge RBAC 尚未建模，属于后续安全债务。

## 31. Legacy Compatibility

旧 classpath retriever、旧 `DiagnosisRetriever.retrieve(request)`、旧 Diagnosis payload 均保留兼容；旧历史案例和 Expert Review 表没有改写。legacy 运行时无 DB 时只能通过显式测试构造器 fallback。

## 32. Demo Compatibility

无 DataSource 的单元测试仍可运行 demo fallback，但 metadata 明确标为 DEMO/固定 packaged provenance。Demo 不会写 Milvus，也不会把 Candidate 变成 HistoricalCase。

## 33. Tests

新增 `KnowledgeBackendV1Test`（3 tests）和 `KnowledgeEvidenceProvenanceTest`（1 test），覆盖稳定 Source/Document/Version 身份、重复 content 幂等、current pointer、跨 Document 激活拒绝、跨用户拒绝、PACKAGED 注册幂等和 Evidence provenance。`mvn -DskipTests compile` 通过；两组新增测试共 4/4 通过。

## 34. MySQL Tests

新增 `KnowledgeBackendV1MysqlTest`，使用本机 MySQL 8.0.36、`FARMAP_AGENT_PERSISTENCE=mysql` 和真实 JDBC，1/1 通过：Source/Document/Version 重启读取、current activation、跨用户拒绝、版本数量和审计事件均验证。V5_2_6 migration 已实际应用到 `zhgy` 数据库。

## 35. HTTP E2E

已使用重新打包的 Spring Boot jar、真实 MySQL 和 JWT 完成 HTTP smoke/E2E：未认证 `GET /api/knowledge/health` 返回 401；注册/登录后 health 返回 200 `READY`；Source→Document→V1→activate→V2→activate 完整链路返回稳定 ID，随后 GET Document 的 `currentVersionId` 等于 V2。测试数据已清理，仅保留系统 PACKAGED 文档。完整跨用户 403、跨 Document 409 和 DB unavailable 503 仍列为发布门槛。

## 36. 5.2.1–5.2.5 Regression

本阶段没有修改 Diagnosis/AgentAction/Approval/OperationsTask/Outcome/ExpertReview/Candidate 的身份或关系字段。file-mode targeted regression 32/32 通过；MySQL regression（ProductionWriteSafety、DiagnosisPersistence、ProductLoopRelationship、OperationsLifecycle、ExpertReview）5/5 通过；全量 Maven 套件 88 tests、0 failures、9 skipped 通过。

## 37. Frontend

没有重做 Knowledge & Expert 页面，也没有新增管理 CMS。后端新增稳定字段与 `/api/knowledge` 合同，现有前端可继续读取旧结构；本轮工作区的 `npm run build`（`tsc -b && vite build`）通过。下一轮可做最小 Source/Version 状态展示，必须保持 EMPTY 与 UNAVAILABLE 分离。

## 38. Risks

主要风险是正式 migration runner 尚未启用、已有部署可能没有 V5_2_6 schema、Knowledge enterprise RBAC 尚未细化，以及当前 Maven/MySQL 运行器不可用。chunk 仍是段落级，向量 ingestion 属于后续 RAG V2。

## 39. Release Gates

上线前必须执行 V5_2_6 migration；通过 MySQL 单测、HTTP E2E、5.2.1–5.2.5 targeted regression、TypeScript/build（若前端接线）、`git diff --check`，并确认数据库不可用时没有 memory success。生产还需确认 owner policy、DB backup 和健康告警。

## 40. Go / No-Go

**代码范围结论：GO。** 三层模型、stable identity、append-only version、current activation、content idempotency、ownership、audit、Evidence provenance、EMPTY/UNAVAILABLE 和 packaged read-only boundary 已实现；新增测试、MySQL 测试、编译和全量 Maven 套件均通过，没有 Candidate promotion、Milvus 写入、训练或 Agent/RAG V2 越界。

**生产结论：CONDITIONAL GO。** V5_2_6 migration 已在本机 MySQL 验证，但正式 migration runner、真实 HTTP E2E、enterprise RBAC 和部署级 DB/外部依赖健康仍是发布门槛。若任一验证发现跨用户访问、跨文档激活成功、旧 Diagnosis provenance 被改写、DB 故障回退内存或自动写入历史库，必须 NO-GO。
