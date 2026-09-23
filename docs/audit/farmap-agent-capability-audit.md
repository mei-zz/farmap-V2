# FarMap 农业 Agent 全功能审计与商业化体验差距分析

审计日期：2026-09-20  
审计范围：`D:\前后端项目\farmap` 当前工作区  
审计方式：源码追踪、配置检查、运行时只读健康检查、现有单元测试、前端生产构建。  
约束：本次没有修改业务代码、数据库结构、API、环境变量或密钥；没有调用需要付费的模型接口，没有创建真实农事任务，没有执行写入型验收。报告文件本身是本次唯一新增交付物。

状态含义：

- `VERIFIED`：本次实际运行验证通过。
- `IMPLEMENTED_UNTESTED`：代码链路存在，但本次未执行真实业务/模型验证。
- `PARTIAL`：有真实链路，但条件、数据或页面闭环不完整。
- `UI_ONLY`：目前只能证明前端展示/交互存在。
- `MISSING`：本次扫描没有找到实现。
- `BLOCKED`：因审计约束、外部依赖或缺少浏览器自动化而无法验证。

## 1. 项目实际架构

### 1.1 主链路

```text
React/Vite CopilotDrawer
  ├─ POST /api/copilot/route       意图、上下文、权限、能力路由
  ├─ POST /api/copilot/chat        SSE：路由、事实、RAG、参考图、模型回答
  ├─ POST /api/copilot/answer      结构化命令结果后的短回答
  ├─ /api/copilot/conversations    MySQL 会话列表/恢复/上下文
  ├─ /diagnosis/jobs               异步多模态诊断
  └─ /api/agent/runs               Agent Runtime、工具、审批、任务闭环

CopilotRouteService
  ├─ CopilotIntentRouter：确定性规则安全边界
  ├─ LlmIntentClassifier：百炼意图模型池，当前配置为 shadow mode
  └─ CopilotContextResolver：地块/诊断/任务/Agent ID 所有权和上下文解析

CopilotController
  ├─ FieldFactsService/FarmFieldService：农场、地块、作物、物候、天气
  ├─ KnowledgeEvidenceRetriever
  │    ├─ KnowledgeBackendService：MySQL 管理知识与版本
  │    ├─ QwenKnowledgeVectorStore：Milvus Qwen 向量
  │    ├─ BM25 + RRF + 可选 GTE reranker
  │    └─ KnowledgeQueryRewriter：作物/物候/指代改写和动态 TopK
  └─ ModelGateway：百炼模型池与流式生成

DiagnosisRagService
  ├─ VisualEvidenceRetriever：视觉模型观察 + 图像向量/相似图
  ├─ Weather/Soil/Spatial/Knowledge/Historical retriever
  ├─ EvidenceFusionService：证据融合与 provenance
  └─ DiagnosisPersistenceService：诊断、Evidence、Recommendation 持久化

AgentRuntime
  ├─ AgentPlanner：有界计划
  ├─ ToolRegistry/ToolExecutor：字段、天气、相机、GIS、知识、RAG、历史、诊断、任务
  ├─ EvidenceSufficiencyEvaluator：写操作前证据闸门
  ├─ ApprovalManager：写操作人工审批
  ├─ OperationsTaskService：农事任务实际持久化
  └─ TraceRecorder/AgentEventBroadcaster：运行轨迹与 SSE
```

### 1.2 关键入口

| 层 | 实际入口 |
|---|---|
| 前端 Agent 抽屉 | `farmap-frontend-dev/src/components/copilot/CopilotDrawer.tsx`：`handleSend`、`startLightweightChat`、`startDiagnosis`、`renderAgentProgress` |
| 前端 API 封装 | `farmap-frontend-dev/src/features/copilot/service.ts`：`routeCopilot`、`streamCopilotChat`、`listCopilotConversations`、`resumeCopilotConversation` |
| 后端 Copilot | `zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java`：`route`、`chat`、`streamChat`、`streamModelAnswer`、`answer` |
| 路由编排 | `.../service/copilot/CopilotRouteService.java`：`route` |
| 规则意图 | `.../service/copilot/CopilotIntentRouter.java`：`classify`、`classifyHybrid` |
| 模型意图 | `.../service/copilot/intent/LlmIntentClassifier.java`：`classify`、`attempt`、`parse`、`needsEscalation` |
| 对话持久化 | `.../service/copilot/CopilotConversationService.java`、`JdbcCopilotConversationRepository.java` |
| Agent Runtime | `.../service/agent/AgentRuntime.java`：`createRun`、`execute`、`approve`、`reject`、`resolveEvidence` |
| 诊断 RAG | `.../service/rag/DiagnosisRagService.java`：`analyze`；`VisualEvidenceRetriever.java`：视觉证据 |
| 知识 RAG | `.../service/rag/KnowledgeEvidenceRetriever.java`、`.../service/knowledge/QwenKnowledgeVectorStore.java` |
| 业务数据 | `.../service/agriculture/FieldFactsService.java`、`.../service/field/FarmFieldService.java` |
| 模型配置 | `zhgy/zhgy/src/main/resources/farmap-models.yml`、`application.yml` |

## 2. 运行时与测试证据

### 2.1 本次运行过的安全检查

| 检查 | 命令/前置条件 | 实际结果 |
|---|---|---|
| 后端单元测试 | `mvn -q -Dtest=CopilotPrivateFactPolicyTest,CopilotIntentHybridTest,LlmIntentClassifierTest,KnowledgeBm25Test,KnowledgeChunkerTest,KnowledgeEvidenceProvenanceTest,AgentEvidenceSufficiencyTest,AgentPlannerTest,AgentRunStateMachineTest,AgentContextTest test`；使用现有 Maven 依赖，不调用外部模型 | `VERIFIED`，退出码 0；仅有多个 SLF4J binding 警告，无测试失败输出 |
| 前端生产构建 | `pnpm build`；使用现有依赖，不安装包 | `VERIFIED`，7426 modules transformed，构建成功；存在两个约 1.14MB/1.21MB JS chunk 的性能警告 |
| 前端服务 | `curl -I http://127.0.0.1:5173/farmap/` | `VERIFIED`，HTTP 200 |
| 后端运行时健康 | `curl http://127.0.0.1:8080/api/runtime/health` | `VERIFIED`，返回 REAL/READY；MySQL agent 31 runs、operations 17 tasks、knowledge 29 versions |
| 后端通用 `/health` | `curl --max-time 5 http://127.0.0.1:8080/health` | `BLOCKED`，本次 5 秒内无响应；更细的 `/api/runtime/health` 正常，因此不能把该现象解释成整个后端不可用 |
| 真实 Copilot/诊断模型调用 | 需要百炼模型调用且可能产生费用 | `BLOCKED`，按审计约束没有执行 |
| 浏览器真实点击、视觉布局、权限切换 | 当前会话没有可调用的浏览器截图/自动化工具 | `BLOCKED`；本报告不把用户截图或静态 JSX 当作已通过的交互验收 |

### 2.2 当前运行时快照

来自本次只读 `/api/runtime/health`，不包含密钥：

- 后端：`REAL`，Intelligence Plane：`READY`，模型网关：已配置百炼兼容接口。
- 文本 embedding：`BAAI/bge-small-zh-v1.5`，512 维，`READY`；图像 embedding：`clip-image-encoder.onnx`，512 维，`READY`。
- 知识后端：MySQL，29 个版本；旧知识索引 4 行，Qwen 知识索引 `farmap_knowledge_chunks_qwen3_v1` 为 996 行，RRF、BM25 cache、Qwen embedding、GTE reranker 均报告 `READY`。
- 多模态上传图像索引：13 行，`READY`。
- 历史病例向量集合：集合存在但 0 行，`READY_EMPTY`；因此“历史病例检索”目前不是完整可用的数据能力。
- Agent：MySQL 持久化，31 runs，`READY`；Operations：17 tasks，`READY`；Expert Review：2 条，`READY`。
- 模型可用性不能等同于模型配置：运行时快照中只有 `qwen-max` 返回 `VERIFIED/available`，默认模型及大部分池成员仍是 `unknown/UNVERIFIED`。因此不能仅凭 YAML 中存在 50 个候选模型就称为全部开通并可用。

## 3. Agent 功能审计总表

| 功能 | 状态 | 实现位置 | 验证证据 | 存在问题 | 建议 |
|---|---|---|---|---|---|
| 意图识别 | `PARTIAL` | `CopilotIntentRouter.java:39-123`；`LlmIntentClassifier.java:103-311` | 规则/混合单测通过；运行时配置 `farmap-models.yml:55-60` | 当前 `shadow-mode=true`，`classifyHybrid` 只记录模型影子结果并返回规则结果；百炼模型没有实际接管路由 | 先用观测接口积累真实准确率，再按灰度启用 takeover；保留 protected intent 安全边界 |
| 动态路由 | `PARTIAL` | `CopilotRouteService.java:36-86`；`CopilotController.java:222-290` | 代码可见意图到 capability、读取链和 SSE 分支；未跑付费端到端 | 规则路由有效，但“模型选择/是否 RAG/是否工具”并非完全由模型动态决定；部分重链路需要已解析上下文 | 把 route result、evidence plan、响应模式做成稳定契约并增加 Mock API 契约测试 |
| 简单回答 | `IMPLEMENTED_UNTESTED` | `CopilotController.java:263-290,511-591`；前端 `CopilotDrawer.tsx:622-718` | 前端构建通过；代码有 GENERAL_QA 与 SSE 增量 | 普通问题仍进入知识检索和模型路径，不是纯本地短路；未以真实模型验证“短而简洁” | 对低风险纯知识问题增加明确的快速策略，记录首 token 和完整耗时 |
| 知识回答 | `PARTIAL` | `KnowledgeEvidenceRetriever.java:118-341`；`QwenKnowledgeVectorStore.java:235-329` | runtime 报告知识索引、BM25、RRF、reranker READY；知识相关单测通过 | 本次未付费执行真实 query；前端聊天只展示“找到 N 条”，来源展开能力有限 | 将 source/document/version/chunk/retrieval scores 做成可展开来源卡片 |
| 结构化农事指导 | `PARTIAL` | `responsePresentation` `CopilotDrawer.tsx:251-272`；`renderCommandBarResult` `:1229-1310`；`DiagnosisRagService.java:141-252` | JSX 有 GUIDED/AGENT 模式和证据卡片；构建通过 | 没有原型中的完整“管理要点/肥水管理/病虫害防治/执行计划/知识来源”统一业务卡片 schema；许多内容仍是 Markdown 或命令结果拼接 | 先定义四种 response schema 和前端 renderer，不要让每个意图自行拼卡片 |
| 多模态诊断 | `PARTIAL` | 前端 `CopilotDrawer.tsx:1022-1055,962-1020`；后端 `DiagnosisController.java:64-119`、`VisualEvidenceRetriever.java:59-220` | runtime vision configured、image index 13 rows；多模态 RAG/Agent 单测通过 | 没有执行真实图像模型调用；历史病例索引为空；视觉观察与最终推断的运行结果无法在本次审计中证明 | 在 Mock provider 下增加完整 SSE/持久化契约测试，再做一条人工验收样例 |
| RAG 引用 | `PARTIAL` | `KnowledgeEvidenceRetriever.java:222-308`；`DiagnosisRagService.java:177-252`；`KnowledgeSearchController.java:53-75` | 代码保留 sourceId/documentId/versionId/chunkId/hash/score；provenance 单测通过；runtime Qwen index READY | Copilot 气泡默认只渲染数量备注，不能充分把每个建议关联到具体证据；来源是否真实命中尚未用付费模型回答验证 | UI 展示真实 evidence 列表，禁止模型自由生成“来源”；建议条目携带 evidenceIds |
| 地块感知 | `PARTIAL` | `FieldFactsService.java:28`；`FarmFieldService.java:98-114`；`FieldContextTool.java:19-47`；`CopilotContextResolver.java:60-229` | owner 鉴权、field facts、物候来源和 pageContext 代码；Agent context 单测通过 | `FarmFieldService` 的 soil/satellite 仍以 unavailable 结构返回；跨地块连续对话和浏览器页面联动本次未点击验证 | 用字段切换 Mock 回归测试覆盖 L-01→L-02→“那这个呢”，并在 UI 显示 freshness/status |
| 工具调用 | `IMPLEMENTED_UNTESTED` | `ToolRegistry.java:11-29`；`ToolExecutor.java:16-46`；`AgentPlanner.java:38-79` | Planner、Tool、Agent 状态机单测通过 | 只能证明执行器和计划器，不能证明百炼模型在真实请求中正确选择工具 | 增加 deterministic planner 的 API 契约测试和失败/重试可观测断言 |
| 任务创建 | `PARTIAL` | `AgentRuntime.java:244-255,533-576`；`AgentController.java:56-100`；`OperationsController.java:29-68` | 代码有 approval required、task create、ambiguous write reconcile；没有写入型验收 | 需要已有 Diagnosis/Recommendation provenance；“帮我创建任务”不一定能从自然语言直接得到完整参数；本次不能真实创建任务 | 明确缺参追问 schema；审批卡应展示 field、时间、动作、证据和幂等 actionId |
| 任务闭环 | `PARTIAL` | `AgentRuntime.java:533-606`；`OperationsTaskService`；`OperationsController.java:29-68` | runtime 有 17 个任务，Agent 有审批/结果/恢复代码 | 未验证 Agent 创建后 Operations 页面同步、重复提交、失败重试和真实用户界面联动 | 做无副作用 fake Operations adapter 的全链路测试，再做一次人工审批验收 |
| 会话与用户隔离 | `IMPLEMENTED_UNTESTED` | `CopilotController.java:172-197`；`CopilotConversationService.java:36-239`；`JdbcCopilotConversationRepository.java:38-105` | SQL 查询统一带 `user_id`；会话历史单测通过；前端有列表/恢复 API | 本次没有两个真实账号的浏览器切换验证；会话恢复后的 page context 竞争规则仍应持续回归 | 保留 user-scoped SQL，并补多账号集成测试；UI 加空历史、过期、恢复失败状态 |
| 前端流式/图片/语音 | `PARTIAL` | `CopilotDrawer.tsx:630-718,1022-1117,1528-1691`；`service.ts:98-123` | `pnpm build` 通过；代码具备 SSE、粘贴图片、预览删除、浏览器 SpeechRecognition | 语音依赖浏览器原生识别，不是统一服务；图片/视觉真实调用本次未执行；长回答和停止生成的浏览器体验未实测 | 先补前端 Mock SSE/粘贴/错误测试，再决定是否接统一 STT |

## 4. 五种回答模式

### A. Simple

规则上 `GENERAL_QA` 由 `CopilotIntentRouter.classify` 返回，前端把它映射为 `SIMPLE`，并走 `startLightweightChat`。服务端仍会经过 `resolveCopilotFacts`、`retrieveKnowledge` 和 `streamModelAnswer`，所以“简单”主要是回答呈现模式，不是完全跳过 RAG/上下文的快速执行模式。

结论：`IMPLEMENTED_UNTESTED`。有真实路由和流式实现，但本次没有调用模型确认“你好/什么是叶面肥？”的实际长度与延迟。

### B. Knowledge

`KNOWLEDGE_QUERY` 映射到 `KNOWLEDGE_SEARCH`，`KnowledgeQueryRewriter` 注入作物/物候，`KnowledgeEvidenceRetriever` 优先调用 Qwen 向量集合，并带 BM25/RRF/reranker 分支。Copilot SSE 会发送 `knowledge` 事件，前端保存 `knowledgeCount` 和少量 source 字段。

结论：`PARTIAL`。检索基础设施真实存在且 runtime READY；聊天中完整来源展示和真实模型回答尚未在本次执行。

### C. Guide

`FIELD_STATUS_QUERY`、`FARM_STATUS_QUERY`、`PRIORITIES_QUERY`、`RECOMMENDATION_QUERY` 等映射为前端 `GUIDED`，`commandService.ts:141-313` 读取地块、天气、诊断和 Operations，之后 `/api/copilot/answer` 做短总结。诊断路径还能返回 claims、recommendations、evidence。

结论：`PARTIAL`。能产生若干结构化数据片段，但还不是原型中统一的管理方案卡片，也没有本次真实模型验证。

### D. Action

规则中的“安排/执行/启动 agent/落实”等进入 `ACTION_REQUEST`，后端 capability 是 `AGENT_START`，解析上下文和 provenance 后由 `AgentRuntime` 建立计划。写操作在 `AgentRuntime` 中被标为 `approvalRequired=true`，进入 `WAITING_FOR_APPROVAL` 后才可调用 Operations task。

结论：`PARTIAL`。安全边界和真实 API 入口存在，但只有绑定 Diagnosis/Recommendation、字段事实和合法 owner 时才形成完整闭环；本次禁止执行真实写操作，因此未标记 VERIFIED。

### E. Clarification

低置信度、缺字段、`UNSUPPORTED`、权限/上下文问题会由 `CopilotRouteService` 返回 `CLARIFICATION_REQUIRED` 或 FORBIDDEN，前端 `formatRouteMessage` 展示追问/拒绝。意图分类器对 `intent/confidence` 严格，slots/multiIntent/needsClarification 可缺省为安全默认值。

结论：`IMPLEMENTED_UNTESTED`。规则和单测证明机制存在；“李子叶片发黄应该打什么药”在当前真实上下文下的追问质量尚未跑模型验收。

## 5. “脆李采后应该怎么管理？”完整调用链

1. 前端 `CopilotDrawer.handleSend`（约 `721` 行）把消息、当前 farm/field/pageContext、conversationId 和图片 URL 组成请求。普通问题进入 `startLightweightChat`，调用 `streamCopilotChat`。
2. `features/copilot/service.ts:68-123` POST `/api/copilot/chat`，接收 `text/event-stream`，收到 `route` 事件后保存 conversationId/contextVersion。
3. `CopilotController.chat/streamChat`（`211-290`）发送 status，调用 `CopilotRouteService.route`。
4. `CopilotRouteService.route`（`36-86`）调用 `CopilotIntentRouter.classifyHybrid`，然后 `CopilotContextResolver.resolve` 校验 field/farm/diagnosis/task owner，产生 intent、capability、resolutionStatus。
5. 当前配置 `farmap-models.yml:54-90` 的 `shadow-mode=true` 意味着 `classifyHybrid`（`CopilotIntentRouter.java:110-123`）只异步记录模型影子结果，真实最终路由仍是规则分类；若规则识别为推荐/知识/字段状态，后续 capability 决定读取路径。
6. `resolveCopilotFacts`（`CopilotController.java:390` 附近）通过 `FieldFactsService.forField` 获取脆李、采后恢复期、物候登记/日期推算和来源；缺少私有事实时才进入 public generic 分层。
7. `retrieveKnowledge`（`457-508`）构造 `DiagnosisAnalyzeRequest`，把 query、crop、growthStage、fieldId、farmId、recentConversation 交给 `KnowledgeEvidenceRetriever.retrieveWithStatus`。
8. 知识检索按 `KnowledgeQueryRewriter` 的作物/阶段改写，优先 Qwen Milvus；`QwenKnowledgeVectorStore` 合并 dense/BM25，按 RRF 排序，必要时 GTE rerank，并把 source/document/version/chunk/contentHash 等元数据带回。
9. `streamModelAnswer`（`511-591`）先尝试 deterministic phenology；否则把 facts、knowledge、source layer、warning、evidence 放进模型上下文。`allowUngrounded` 配置默认 true，但 prompt 明确禁止编造地块温度、湿度、历史、诊断和产量，并要求通用兜底声明。
10. 后端发送 `knowledge`、`reference.images`、`answer.source`、`content.delta`、`done` SSE；前端 `startLightweightChat`（`630-719`）更新 Agent 轨迹、来源计数、参考图和 Markdown 消息。

本链路的实际断点/限制：本次没有调用百炼生成模型，不能证明最终自然语言内容、首 token 延迟和引用与句子的绑定；历史病例向量库当前 0 行；前端默认显示知识数量而非完整来源面板。因此应判为 `PARTIAL`，不能称作已完整达到原型效果。

## 6. 多模态图片链路

### 已有真实基础

- `CopilotDrawer.uploadCopilotImages`（`1022-1055`）校验图片类型、单张 10MB、最多 5 张，调用 `uploadDiagnosisImages`，把返回的 URL 放入会话上下文；`onPaste`（`1632-1640`）支持直接粘贴图片。
- 消息中保存 `imageAttachments` 并用 antd `Image.PreviewGroup` 预览（`1528-1561`）；可以删除待发送图片（`1601-1625`）。
- `startDiagnosis`（`962-1020`）将图片 URL 和现场天气送入 `/diagnosis/jobs`，轮询结果，持久化 diagnosisId/recommendationId，然后创建 real Agent Run。
- `DiagnosisController` 对图片引用做上传域/COS 域校验；`DiagnosisJobService` 异步运行并持久化诊断。
- `VisualEvidenceRetriever` 在有 ModelGateway 时把 imageUrls 送入视觉模型，记录 vision model、图片观察和 image embedding；`DiagnosisRagService` 再融合 weather、knowledge、historical、soil、spatial 等证据。

### 当前不能宣称的能力

- 本次没有发送真实图片到视觉模型，不能把 `CONFIGURED` 当作视觉诊断“已通过”。
- `/api/runtime/health` 报告历史病例索引 0 行，历史相似病例不是当前可展示的完整能力。
- “视觉观察”与“农业推断”在代码中有 claim/evidence 区分，但需要真实诊断样例检查最终 UI 是否清楚区分，当前为 `IMPLEMENTED_UNTESTED`。

## 7. RAG 可信度审计

### 真实存在并已得到运行时支持

1. 管理知识通过 `KnowledgeBackendService` 落 MySQL，知识版本/ingestion 有状态；runtime 报告 29 versions。
2. Qwen 知识向量集合为 `farmap_knowledge_chunks_qwen3_v1`，runtime 报告 996 rows、1024 维、RRF、BM25 cache、GTE reranker READY。
3. `KnowledgeEvidenceRetriever` 生成 `sourceId/documentId/versionId/chunkId/contentHash` 和检索分数；`KnowledgeSearchController` 也直接返回这些字段。
4. `DiagnosisRagService` 保存 `retrievedEvidenceIds/selectedEvidenceIds`，并把 evidenceIds 写入 claims/recommendations。
5. 检索失败会返回 `UNAVAILABLE`/`DEGRADED`，Copilot 有 L1 私有事实、L2 公共农业知识、L3 通用模型三层来源策略；`allow-ungrounded` 在 `application.yml:91-94` 可开关。

### 当前风险

- Copilot 对话气泡只显示“根据当前知识库找到 N 条相关知识”（前端 `CopilotDrawer.tsx:1562`），不能让用户核验每一条建议对应哪个 chunk。
- `streamModelAnswer` 的最终文本是普通字符串；没有强制结构化的 claim→evidence schema，所以模型可能正确使用上下文但前端无法精确绑定来源。
- runtime 的 reranker READY 是组件健康状态，不是本次某个 query 的命中证明；本次没有执行付费检索/回答。

结论：检索设施 `VERIFIED`（运行时健康 + 相关单测），面向用户的完整来源溯源 `PARTIAL`。

## 8. 地块上下文、会话与权限

- `FieldFactsService.forField(fieldId,userId)` 通过 `FarmFieldService.context` 读取 MySQL 地块并校验 owner；`FarmFieldService` 可组装 farm/field/crop/location/weather/accumulated temperature/images，但 soil、satellite 当前为 unavailable 结构。
- `CopilotContextResolver` 对 farm、field、diagnosis、recommendation、task、run 做用户范围校验；`FieldAccessException` 不会被通用兜底吞掉。
- 会话由 `JdbcCopilotConversationRepository` 按 `(conversation_id,user_id)` 查询；会话列表按 user_id 过滤；turn 读取最近 10 条，支持幂等 `client_message_id` 和版本更新。
- 前端 `listCopilotConversations`、`resumeCopilotConversation`、`loadCopilotConversation` 已有历史切换 API；界面代码展示“仅显示当前登录账号的会话”。

结论：服务端隔离和会话持久化基础是 `IMPLEMENTED_UNTESTED`；跨两个真实账号、切换地块后继续指代的浏览器验证是 `BLOCKED`。

## 9. 原型功能与现有功能差距

| 原型能力 | 当前状态 | 差距类型 |
|---|---|---|
| 右侧 Agent 抽屉、工作台入口 | 已有抽屉和五个 workbench mode | 已有基础，视觉/点击未验收 |
| Simple/Knowledge/Guide/Action/Clarification 动态响应 | 有 enum/intent 到 UI mode 映射 | 需要统一 response schema；目前部分是标签 + Markdown |
| “脆李采后管理方案”完整卡片 | 有诊断/推荐/evidence/action 数据模型 | 缺少原型式统一 tabs、肥水表、病虫害卡、执行计划卡 |
| 真实 RAG 来源 | 后端 provenance 字段完整 | 前端只显示数量/摘要，缺少逐建议来源关联 |
| 图片粘贴、预览、删除 | 前端已实现，构建通过 | 真实上传→视觉→诊断→Agent 未付费验收 |
| 视觉诊断 | `VisualEvidenceRetriever` + 百炼 vision gateway | 历史病例为空，模型真实可用性多数未验证 |
| 地块/作物/物候/天气上下文 | `FieldFactsService` 等真实链路 | soil/satellite 缺失，freshness 和页面联动缺验证 |
| 农事任务确认 | ApprovalManager + WAITING_FOR_APPROVAL | 依赖 diagnosis/recommendation provenance，缺参追问不完整 |
| 任务实际落库与结果 | Operations API、AgentRuntime reconciliation | 本次未执行写操作，无法标记已闭环 |
| 会话历史/不同用户 | MySQL user-scoped repository + 前端 list/resume | 真实多用户浏览器验收缺失 |
| 停止生成/重新生成/复制/反馈 | 有 AbortController 和错误处理；未找到完整 regenerate/copy/feedback 统一闭环 | 交互缺口 |
| 页面双向联动 | 有 open path、context、command item navigation | “Agent 创建后任务页自动同步”未验证 |

## 10. P0/P1/P2 问题

### P0

1. **当前模型意图没有真正接管路由。** `farmap-models.yml:56` 默认 `shadow-mode=true`，`CopilotIntentRouter.classifyHybrid:116-118` 直接返回规则分类。若产品目标是“规则 + 三级模型池动态意图识别”，当前线上仍是“规则主路由 + 模型观测”，不是完整动态模型路由。方向：先用 shadow 指标校准，再用灰度开关接管非 protected、低风险长尾。
2. **写操作真实闭环尚未验收。** `AgentRuntime` 有审批代码，但 ACTION_REQUEST 要求 diagnosis/recommendation provenance；如果页面只给一句“创建明天浇水任务”，参数缺失时不能假设系统能安全创建。方向：缺参结构化追问、确认卡、幂等 actionId、fake adapter 测试和一次人工审批验收。
3. **视觉/历史病例链路的可用性不能由健康状态替代。** vision 已配置，但历史病例向量为 0 行；真实模型和上传链路本次不能执行。方向：先补非付费 mock diagnosis contract，再补少量经专家确认的历史案例后验收。

### P1

1. **回答模式没有统一 schema。** `responsePresentation` 只决定 label/mode，Guide 主要靠命令结果/Markdown，无法稳定复现原型的结构化管理方案。
2. **来源展示不足。** 后端 provenance 已有，Copilot UI 只显示知识条数，用户无法从“施肥建议”跳到对应 chunk/document/version。
3. **上下文数据缺口。** soil/satellite unavailable，天气依赖 provider；复杂指导的事实完整性需要明确显示，不应只显示“基于当前数据”。
4. **模型池 entitlement 不清晰。** runtime 只验证 `qwen-max`，默认模型和绝大部分池成员是 `UNVERIFIED/unknown`；`answer-pool.enabled=true` 不能证明这些模型都能用。
5. **自然语言任务参数提取不完整。** 现有 Action 路由偏向已绑定 Diagnosis/Recommendation 的 Agent Run，不等同于任意文本任务创建。

### P2

1. 前端构建成功但有两个大 JS chunk 超过 1MB，影响 Agent 首屏和抽屉打开速度。
2. `/health` 通用接口本次 5 秒无响应，虽然 `/api/runtime/health` 正常，仍应修复健康接口的超时/依赖隔离。
3. 浏览器 SpeechRecognition 依赖 Chrome/Edge 和浏览器网络服务，缺少统一 STT、权限引导和企业环境降级。
4. 未发现完整的一键复制、重新生成、对话反馈闭环的统一实现证据；不能把 Markdown 和错误重试当成这些能力已完成。

## 11. 最小改造路线

### 阶段 1：先把当前真实能力变得可验收

1. 不换技术栈，保留 `CopilotRouteService`、`CopilotController`、`DiagnosisRagService`、`AgentRuntime`。
2. 增加纯 Mock 的 Copilot SSE/API 契约测试，覆盖五种 intent、SSE event 顺序、错误/超时、图片 URL 传递和 conversationId。
3. 增加统一 `response_type` 等价 schema：`SIMPLE`、`KNOWLEDGE`、`GUIDED`、`AGENT`、`CLARIFICATION`，每个结构带 `claims`、`evidenceRefs`、`actions`、`approval`、`nextQuestions`。
4. 前端只根据 schema 选择 renderer；Markdown 作为 SIMPLE/KNOWLEDGE 的兜底，不再承担完整农事方案结构。

### 阶段 2：补原型核心展示

1. `GUIDED` renderer 复用现有 diagnosis/recommendation/evidence，渲染“核心结论、肥水、病虫害、执行计划、知识来源”。
2. 每条建议携带 `evidenceIds`，点击后展开真实 source/document/version/chunk，而不是只显示知识数量。
3. 图片诊断结果明确分栏：图像观察、农业知识依据、模型推断、置信度、待补拍信息；历史病例为空时明确显示为空，不显示伪相似案例。

### 阶段 3：打开安全的动态模型路由

1. 保持 `CopilotIntentRouter` 对删除/转账/越权/写操作的 protected boundary。
2. 在 shadow 指标达到阈值后，把 `shadow-mode` 做成环境开关灰度；先允许模型接管 GENERAL_QA/KNOWLEDGE/CLARIFICATION 这类读路径，禁止模型直接升级到 ACTION。
3. 配置池只保留 `VERIFIED` 模型，未知模型不能作为随机候选；给 primary/strong/answer pool 记录 P50/P95、错误类型、实际调用模型。

### 阶段 4：完成 Action/页面闭环

1. 任务卡展示地块、时间、动作、风险、证据、幂等键和“批准执行/驳回”。
2. 只有人工批准后进入 `OperationsTaskService`；失败和超时查询权威 task relation，禁止盲目重放。
3. 创建成功后通过已有 conversation/operation context 触发页面刷新，并增加任务页面同步验收。

## 12. 最终结论

当前 FarMap 已具备一个真实的商业化 Agent 骨架：**规则安全路由 + 可配置百炼模型池 + SSE Copilot + MySQL 会话 + 地块/物候事实 + 多模态诊断 RAG + 有审批闸门的 Agent Runtime**。它不是纯 Mock，也不是只有 UI。

但是，按照“达到原型图的商用体验”标准，目前只能判定为 **PARTIAL**：

- 意图规则路由真实生效；模型意图池当前默认仍是 shadow，不是真正模型接管。
- 简单问答、知识回答和结构化命令结果的代码路径存在，但本次未调用付费模型，不能标记真实回答体验 VERIFIED。
- 图片上传、粘贴、预览和视觉 RAG 接口已接通代码，但真实图片→视觉模型→Evidence→Agent 结果未验收。
- RAG 索引和 provenance 基础设施真实存在且 runtime READY，但前端来源展示还达不到原型中“知识来源”可核验的体验。
- 农事任务存在真实 Operations API、Agent 计划和人工审批机制，但自然语言缺参追问和完整页面闭环尚未证明。

本次审计没有修复以上问题；下一步应按 P0→P1 顺序做 Mock 契约验收和人工灰度验收，而不是继续增加模型数量或重写 Agent。
