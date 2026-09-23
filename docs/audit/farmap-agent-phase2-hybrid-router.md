# FarMap Agent Phase 2：真实轻量模型混合路由实施报告

日期：2026-09-20

## 1. 最终技术架构

本阶段在现有 Phase 1 和既有 Agent/RAG 链路上做增量改造，没有另起一套意图服务：

```text
Copilot 请求
  -> CopilotRouteService
  -> CopilotIntentRouter.classify（确定性规则、安全边界）
       | 明确安全/写入/导航/危险请求：规则直接决定
       | 长尾、模糊、软诊断：进入 LlmIntentClassifier
  -> BailianIntentClassifierClient
  -> 现有 BailianModelClient.callOnce（chat JSON）
  -> JSON 解析、枚举/置信度校验、槽位安全缺省、总 deadline
  -> 规则与模型裁决（protected intent 不可被模型提升）
  -> CopilotRouteService 的 context resolver / capability
  -> 现有 RAG、视觉诊断或 Agent Runtime
  -> CopilotResponsePolicy（SIMPLE/KNOWLEDGE/GUIDED/AGENT/CLARIFICATION）
  -> 现有 /api/copilot/chat SSE response.meta + React 动态组件
```

规则仍是安全边界；模型只输出受限的既有 `CopilotIntent`、置信度、槽位和澄清信息，不能返回工具类名、URL、SQL、模型 ID 或直接执行写操作。模型最终意图进入 `CopilotRouteService` 后才映射为已注册的 `CopilotCapability`，因此模型输出确实参与后续执行路径，但不会绕过 owner 鉴权、Agent Run 人工批准或 `FieldAccessException`。

## 2. Router 模型选择报告

### 2.1 已扫描的模型配置

密钥只通过环境变量读取，本文没有记录密钥、指纹、请求正文或响应正文。当前 `farmap-models.yml` 中的模型按用途如下：

| 模型/集合 | Provider | 配置用途 | 输入能力 | 当前可用性 | Router 结论 |
|---|---|---|---|---|---|
| `qwen-flash` | Alibaba Bailian | 意图池中原有 Flash 模型；本阶段设为 intent primary | 文本、JSON | **2026-09-20 真实 chat+JSON：OK，shape=true，1356ms** | **最终选择** |
| `tongyi-xiaomi-analysis-flash`、`qwen-turbo`、`qwen-flash-2025-07-28`、`qwen3.5-flash`、`qwen3.5-flash-2026-02-23`、`qwen3.6-flash`、`qwen3.6-flash-2026-04-16`、`qwen3.7-flash`、`qwen3.7-flash-2026-07-15`、`deepseek-v4-flash`、`deepseek-v4.1-flash` | Alibaba Bailian | intent Flash 故障回退池 | 文本，按同一 JSON Client 调用 | 本次未逐一重验，标记未验证 | 保留为同层回退，不作为默认主模型 |
| `qwen3-8b`、`qwen3-14b`、`qwen3.5-27b`、`qwen3.6-27b`、`qwen3.8-27b`、`qwen3-30b-a3b`、`qwen3-30b-a3b-instruct-2507` | Alibaba Bailian | intent Strong 升级池 | 文本 | 本次未逐一重验，标记未验证 | 仅在一级合法结果低置信/复杂三要素时尝试 |
| `qwen-plus-2025-07-28` | Alibaba Bailian | 默认/最终回答回退模型 | 文本 | 既有全池冒烟 OK；运行时健康状态曾为 unknown | 不作为 intent Router |
| `deepseek-r1-distill-qwen-7b` | Alibaba Bailian | fast-model / 原有轻量文本路径 | 文本 | 本次最小 JSON 探针 `timeout` | 暂不选 |
| `qwen3-vl-32b-thinking` | Alibaba Bailian | 多模态诊断、专家配置 | 文本+视觉 | 已配置；非 Router 小模型 | 不选，交给视觉/诊断链路 |
| `hunyuan-a13b` | Tencent Hunyuan | 独立 Hunyuan 文本配置 | 文本 | 配置存在，本阶段未探测 | 不选；不引入第二 Provider |
| `qwen3.7-text-embedding-flash` | Alibaba Bailian | 知识向量生成 | embedding | 已用于现有知识资产，非 chat Router | 不选 |
| `gte-rerank-v2` | Alibaba Bailian | 知识 rerank | rerank | 已配置，非 chat Router | 不选 |

主回答池的 45 个文本模型也已在此前 `docs/answer-pool-smoke-result.md` 完成全池冒烟并全部通过；它们继续由既有 `AnswerModelPool/ModelRouter` 按 `AiTaskType` 处理最终回答，不被误用为 intent Router。45 个模型为：

```text
glm-5, glm-5.1, glm-5.2, glm-4.7,
qwen-max, qwen3-max-preview, qwen3-max-2025-09-23, qwen3-max,
qwen3-max-2026-01-23, qwen3.6-max-preview, qwen3.7-max,
qwen3.7-max-2026-05-20, qwen3.7-max-2026-06-08, qwen3.8-max-0902,
qwen-plus, qwen-plus-latest, qwen-plus-0112, qwen-plus-1220,
qwen-plus-2025-01-25, qwen-plus-2025-04-28, qwen-plus-2025-07-14,
qwen-plus-2025-07-28, qwen-plus-2025-09-11, qwen-plus-2025-12-01,
qwen3.5-plus, qwen3.5-plus-2026-02-15, qwen3.5-plus-2026-04-20,
qwen3.6-plus, qwen3.6-plus-2026-04-02, qwen3.7-plus-2026-05-26,
qwen3-235b-a22b, qwen3-235b-a22b-instruct-2507, qwen3.5-122b-a10b,
qwen3.5-397b-a17b, qwen3.5-35b-a3b, qwen3.6-35b-a3b,
qwen3-next-80b-a3b-instruct, deepseek-v3, deepseek-v3.1, deepseek-v3.2,
deepseek-v3.2-exp, deepseek-v4-pro-0813, kimi-k2.5, kimi-k2.6,
Moonshot-Kimi-K2-Instruct
```

### 2.2 最终选择

- 模型 ID：`qwen-flash`。
- 配置：`farmap.ai.router.intent.primary-model`，环境覆盖名 `FARMAP_INTENT_PRIMARY_MODEL`。
- 客户端：`BailianIntentClassifierClient -> BailianModelClient.callOnce`，没有新增 SDK 或 Provider。
- 选择依据：它已经在项目原有 intent Flash 池；中文文本 chat 兼容；本次当前环境真实返回符合既有意图 JSON 协议；实测延迟低于当前 1800ms 分类总预算。
- 成本：项目没有锁定、可信且可审计的当前价格数据，本报告不编造单次费用；请求 `max_tokens` 上限为 300，真实探针只执行少量请求。
- 失败回退：同层 Flash 只在连接错误/5xx 等可重试传输失败时有限切换；超时不换模型；分类总 deadline 到期、JSON 非法、枚举非法或池为空时返回规则安全结果。必要时 `FARMAP_INTENT_DECISION_MODE=RULE_ONLY` 立即恢复纯规则。

历史 `docs/intent-hybrid-routing-result.md` 曾记录 `tongyi-intent-detect-v3` 在 2026-09-14 的一次成功探针；本次 2026-09-20 用相同现有客户端重验未成功，因此没有继续把它作为默认 Router。`deepseek-r1-distill-qwen-7b` 本次超时；`qwen-mt-flash` 本次返回角色协议不兼容错误。以上均没有被写入默认主 Router。

## 3. 实际代码修改

| 文件 | 实施内容 |
|---|---|
| `zhgy/zhgy/src/main/resources/farmap-models.yml` | intent primary 改为已有且当前实测成功的 `qwen-flash`；从同层 fallback 去掉重复项；增加 `FARMAP_INTENT_DECISION_MODE` 配置，默认空值兼容旧 `shadow-mode=true`。 |
| `.../intent/IntentModelProperties.java` | 增加 `RULE_ONLY/SHADOW/HYBRID` 有效模式解析；非法值回到旧兼容行为，不改变默认生产行为。 |
| `.../copilot/CopilotIntentRouter.java` | 先规则和安全判定，再依据三态模式选择旁路、接管或纯规则；增加中文“什么是/何谓”、现场图片和视觉异常信号；增加“只分析/不要执行/等我确认”等否定约束，避免误进 ACTION。 |
| `.../intent/IntentPromptBuilder.java` | 在既有 JSON 协议和安全提示中明确否定约束不能输出 `ACTION_REQUEST`。 |
| `.../intent/LlmIntentClassifier.java` | 使用有效决策模式；Shadow 外层由无界公共线程池改为有界队列，队列满只丢弃影子任务；增加 `shadowDropped` 观测，不阻塞交互请求；样本记录 mode 使用有效模式。原有总 deadline、超时不换模型、结构化解析和 fail-open 保留。 |
| `.../controller/IntentObservabilityController.java` | `/ai-model/intent/metrics` 增加有效 `decisionMode`，`shadowMode` 与实际模式一致。既有 JWT 保护未改。 |
| `.../intent/IntentModelConnectivityProbeTest.java` | 现有探针支持 `-Dintent.probe.model`，并增加完整 `LlmIntentClassifier -> CopilotIntentRouter` 受控探针；只打印状态/意图/耗时，不打印密钥、正文或原始问题。 |
| `.../CopilotPhase2HybridRoutingTest.java` | 新增 18 类路由矩阵、非法 JSON fail-open、模型输出进入 `CopilotRouteService` capability 的测试。 |

未重写 Agent Runtime、知识 RAG/BM25/RRF、视觉诊断、Milvus、鉴权、审批和 Phase 1 前端/SSE 协议。

## 4. 路由决策规则

1. 空输入、危险/注入、删除数据库、转账等由规则直接返回澄清或 `UNSUPPORTED`，不调用模型。
2. 明确导航、记忆写入、写操作、记忆读取和其他 protected intent 不交给模型提升。
3. 明确只读意图直接走规则；长尾 `GENERAL_QA`/澄清、软诊断以及低置信规则结果进入 Router。
4. 模型输出只接受既有 `CopilotIntent` 枚举和有限置信度；`slots`、`multiIntent`、`needsClarification` 缺失时安全缺省，`intent/confidence` 非法则整个模型结果失效。
5. 接管必须同时满足模型成功、置信度达到 `takeover-threshold`、不是不安全提升，且通过当前裁决逻辑；模型失败始终回到规则。
6. “只分析，不执行”“不要创建任务”“先给方案，等我确认”会保留为非写约束，不进入 Agent 启动路径。

默认：`SHADOW`。开发隔离接管：`FARMAP_INTENT_DECISION_MODE=HYBRID`。紧急回滚：`FARMAP_INTENT_DECISION_MODE=RULE_ONLY`，或关闭 `FARMAP_INTENT_ROUTER_ENABLED=false`。

## 5. 模型池动态分配

- Intent Router：主模型 `qwen-flash`；同层故障池使用已有 Flash 配置；复杂且一级结果满足升级条件时才使用 Strong 池；总预算默认 1800ms，`max_tokens=300`。
- 普通/知识/管理回答：最终回答仍进入既有 `ModelRouter/AnswerModelPool`，由 `AiTaskType.COPILOT` 等任务类型选择 45 个已冒烟模型之一；Router 不直接生成回答。
- 图片诊断：Router 只识别诊断意图和槽位，不加载原图；现有 Controller/DiagnosisJobService/ModelRouter 继续选择视觉模型。
- 复杂农事指导：路由结果先经过 Field/Farm context resolver，再由 RAG/field facts/Response Policy 决定是否生成 GUIDED；上下文不足则澄清或只读降级。
- 写操作：`ACTION_REQUEST` 仍映射 `AGENT_START`，最终动作必须走既有 Agent Runtime 和人工批准。

不存在模型 ID、任意工具调用、URL 或 SQL 从 Router 输出直接落地的路径。

## 6. Response Policy 集成

Phase 1 的 `CopilotResponsePolicy` 未被重写。真实链路仍由执行结果决定最终展示模式：

- `SIMPLE`：普通闲聊/简单问答；
- `KNOWLEDGE`：知识意图或知识信号，附已有知识证据；
- `GUIDED`：农情、管理、推荐、诊断读取等需要上下文的建议；
- `AGENT`：诊断创建或写操作工作流，但写操作仍需要确认；
- `CLARIFICATION`：上下文不足、权限/状态未解析、缺少现场图片或请求不明确。

模型不会强行制造卡片。比如模型判诊断但没有必要图片/地块事实时，Response Policy 仍可以选择 `CLARIFICATION`；SSE 仍发送既有 `response.meta`、knowledge、reference.images、answer.source 和 done/error 事件，React 继续使用 Phase 1 的动态渲染协议。

## 7. 真实模型调用结果

测试方式：从项目 `.env` 读取已有百炼凭据到 Maven 子进程环境；没有打印密钥；没有启动 Agent Run、写任务、写数据库、调用 Milvus 入库或修改业务数据。每次请求最多 300 output tokens，意图链路总 deadline 1800ms。

### 7.1 底层协议探针

```text
INTENT_MODEL_PROBE model=qwen-flash transport=chat_json status=OK shape=true latencyMs=1356
```

这证明当前 `qwen-flash` 在现有 `BailianModelClient` 下可访问并返回完整意图 JSON 外形。token usage 没有在本探针输出，不能据此编造 token 数。

对照候选：`deepseek-r1-distill-qwen-7b` 返回 `timeout`；`qwen-mt-flash` 返回 `provider_error`（请求角色协议不兼容）；历史主候选 `tongyi-intent-detect-v3` 的 2026-09-20 重验失败。只有 `qwen-flash` 被设为默认主 Router。

### 7.2 完整混合路由探针

```text
INTENT_MODEL_ROUTE_PROBE model=qwen-flash ruleIntent=GENERAL_QA llmIntent=DIAGNOSIS_REQUEST finalIntent=DIAGNOSIS_REQUEST tier=PRIMARY status=MODEL_DECISION fallback=none latencyMs=722
```

输入是固定的软诊断样例，报告只记录脱敏后的路由标签，不记录原文。该结果证明：规则先给 `GENERAL_QA`，真实 qwen-flash 返回合法 `DIAGNOSIS_REQUEST`，通过策略校验后改变最终分类；不是只有 Shadow 日志。对应的现有 `CopilotRouteService` 会把最终意图映射为 `DIAGNOSIS_CREATE` capability，并继续走现有上下文/诊断条件检查。

## 8. 测试报告

### 后端编译与单元/Mock

- `mvn -q -DskipTests compile`：通过。
- `CopilotPhase2HybridRoutingTest`：3/3 通过，覆盖 18 类路由、非法 JSON 回退、模型意图进入 `CopilotRouteService` capability。
- `CopilotIntentHybridTest`：5/5 通过，覆盖三态模式、模型接管、规则安全边界和失败回退。
- `LlmIntentClassifierTest`：7/7 通过，覆盖缓存、超时总 deadline、可选字段缺省、JSON/枚举/provider 故障和 Strong 升级。
- `IntentModelPoolTest`：3/3 通过。
- `LlmIntentClassifierObservabilityTest`：4/4 通过。
- Phase 1 响应策略、上下文、记忆、Agent、证据回归：21 个测试通过（包括 `CopilotResponsePolicyTest` 6、Phase 14 4、Phase 15 1、Phase 16 1、AgentContext 1、AgentPlanner 1、AgentRunStateMachine 1、KnowledgeEvidenceProvenance 1、AgentEvidenceSufficiency 5）。
- `git diff --check`：通过；构建日志中的多个 SLF4J binding 是既有 classpath 警告，不是本阶段失败。

### 真实路由测试

- `IntentModelConnectivityProbeTest`：当前 qwen-flash 真实协议探针通过，完整混合路由探针通过。
- 该探针是开发环境、少量调用、无业务写入；不等同于生产流量 P95 或长期可用性承诺。

### SSE/前端兼容

- Phase 1 已验证现有 `/api/copilot/chat` SSE 与 `response.meta` 协议，Phase 2 没有改 SSE event 名称或 React contract。
- `pnpm lint`：通过。
- `pnpm build`：通过，Vite 转换 7426 modules；仅保留既有大 chunk warning。

## 9. 灰度与回滚

### 安全启用

在隔离开发/测试进程设置：

```text
FARMAP_INTENT_DECISION_MODE=HYBRID
```

观察 `/ai-model/intent/metrics` 中的 `ruleMismatchRate`、`fallbackRate`、`latencyP95Ms`、`shadowDropped` 和 `/ai-model/intent/samples` 后再决定是否扩大流量。当前 YAML 默认空 decision mode + `shadow-mode=true`，因此生产仍为 SHADOW，不会因本次修改静默接管。

### 立即回滚

```text
FARMAP_INTENT_DECISION_MODE=RULE_ONLY
```

或者设置 `FARMAP_INTENT_ROUTER_ENABLED=false` 后重启。两者都保留原确定性规则路由，不需要数据迁移，也不会清空缓存、知识库或 Milvus。任何 Agent Run、任务执行和写操作审批逻辑都不因 HYBRID 开关而绕过。

## 10. 未完成事项

### 已实现且测试通过

- 真实百炼 Router Client 调用、结构化协议解析、总 deadline、有限模型池回退和 fail-open。
- 规则/Shadow/Hybrid 三态配置，默认生产行为未改变。
- 模型输出确实改变了混合分类，并进入现有 RouteService capability 映射。
- 负向执行约束、危险/权限安全边界、既有 Agent 审批、Response Policy、SSE、React 构建保持通过。
- 18 类可重复路由矩阵和 Phase 1 回归测试。

### 已实现但仍需线上观测

- Flash/Strong 备用池中除 qwen-flash 外的每个 intent 模型没有在本次逐一重新探测；它们只保留为已有配置回退。
- `qwen-flash` 的当前单次探针延迟为 1356ms/完整路由 722ms，不代表生产 P95、价格或长期稳定性；需通过已有观测端点采集真实流量。
- Router 输出没有接口级 token usage 记录，因为当前探针和 `LlmIntentResult` 只保留路由耗时，不应推断 token 成本。

### 仅 Mock 验证

- `CopilotPhase2HybridRoutingTest` 中的 capability 进入 RouteService 使用 Mock 的 resolver/model client，验证的是服务组合和安全映射，不冒充真实后端全链路业务数据测试。
- Strong 升级和同层故障接力的行为由离线单元测试验证，尚未逐一真实调用所有候选。

### 当前环境未完成/不宣称

- 没有在生产服务器或真实网页会话中打开 HYBRID，也没有把当前生产部署静默切换到模型接管。
- 没有宣称任何模型的官方费用、长期可用率或线上 P95；当前仅完成受控开发环境真实调用。
- 当前工作区存在前面任务留下的未提交改动，本阶段未 reset、checkout、覆盖或提交这些改动；上线前仍应按团队流程审查完整 diff、打包镜像并在隔离环境回归。

