# FarMap 农业 Agent 开源复用评估与最小 PoC

日期：2026-09-23  
分支：`codex/poc-open-source-agent-reuse`

## 1. 结论先行

FarMap 不需要再引入一个 Agent 编排框架。现有系统已经有 LangGraph、Spring Boot Agent Runtime、百炼模型池、RAG、SSE、会话持久化和任务审批边界。最合适的增量方案是：

1. 采用方案 A：在现有 LangGraph/Runtime 外围补充“输入理解、澄清和回答整理”的清洁实现。
2. 暂不直接引入 CopilotKit 或 assistant-ui 作为运行时。它们的线程、附件、取消、语音和流式 UI 模式可以作为 React 层的实现参考；如果以后需要协议适配，应只实现一个薄适配层。
3. 农业仓库不能直接整体复制进商用 FarMap：DG farmer-chat 是 GPL-3.0；AgriGPT 仓库声明 academic purposes only 且未发现仓库级 LICENSE；kisaan-ai 虽是 MIT，但其模型、数据、第三方服务和依赖仍需单独核验。
4. 本次 PoC 只增加一个受控农业术语别名：`棚大期` → `膨大期`。它复用现有 `CopilotQueryNormalizer`，因此现有意图路由和参考图检索会共享同一修正结果，不创建平行 Router。

## 2. FarMap 当前实现对应关系

| 能力 | FarMap 现有实现 | 结论 |
|---|---|---|
| 输入规范化 | `zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/CopilotQueryNormalizer.java`：`forMatching`、`compactForMatching` | 已有稳定入口；适合承载少量、可解释、领域确认过的语音/输入法别名 |
| 规则/模型混合意图 | `CopilotIntentRouter.classify`、`classifyHybrid`；`copilot/intent/LlmIntentClassifier` | 规则先行，模型只处理长尾；安全意图和写操作不会被模型越权提升 |
| 上下文路由 | `CopilotRouteService.route`、`CopilotContextResolver.resolve` | 使用当前会话和地块/农场上下文，动作路由仍需解析成功 |
| 模型上下文 | `CopilotContextAssembler.assemble` | 限制最近轮数和字符数，不把完整历史无界发送给模型 |
| 会话历史 | `CopilotConversationService`、`CopilotConversationRepository`、`allTurns` | 展示历史和模型上下文已经分开；历史按 `conversationId + userId` 隔离 |
| 图片需求判断 | `CopilotMediaPolicy.decide` | `NONE/BENEFICIAL/EXPLICIT` 与业务意图分离；普通问答不应无意义查图 |
| 参考图片 | `ReferenceImageAttacher.forChatResult`、`ReferenceImageStore.byStageResult/byDiseaseResult` | 通过真实图谱记录和 URL 白名单返回，不允许模型编造图片 URL |
| 农业 Agent | `AgentPlanner.plan`、`AgentRuntime`、`MultimodalRagTool` | 读工具自动执行，写操作进入现有审批边界 |
| SSE | `CopilotController.chat/streamChat/emitReferenceImages`、`AgentController.events` | 已有文本、元数据、图片和 Agent 事件通道 |
| 前端会话与流式 UI | `farmap-frontend-dev/src/components/copilot/CopilotDrawer.tsx`、`features/copilot/service.ts`、`utils/sse.ts` | 已有消息渲染、附件、历史会话和 Agent 操作反馈；不需要用 OSS UI 替换整页 |

这里的“模型上下文有限”是故意的：前端可以查看完整会话，但路由/生成模型只接收经过裁剪的上下文，避免历史越长越慢、越容易串场。

## 3. 候选仓库源码审计

### 3.1 Digital Green `farmer-chat`

源码中存在一个清晰的农业问答流水线：

- `farmer-chat/intent_classification/intent.py` 的 `classify_intent` 和 `process_user_intent` 对问候、退出、无关问题和农业问题分流，并用 `proceed_to_rag` 控制是否继续检索。
- `farmer-chat/rephrasing/rephrase.py` 的 `rephrase_query` 只有在存在历史时才改写当前问题；失败时回退原问题，并返回时延、token 和异常信息。
- `farmer-chat/retrieval/content_retrieval.py` 调外部内容检索接口，并带重试和检索计时。
- `farmer-chat/reranking/rerank.py` 对候选片段并发调用 LLM 做相关性判断，再限制保留数量。
- `farmer-chat/generation/generate_response.py` 把用户、改写问题和上下文片段整理成最终回答。
- `farmer-chat/api/views.py` 暴露文字和语音 API，并在接口边界校验空问题和用户标识。

FarMap 对应的是 `CopilotQueryNormalizer`、`CopilotIntentRouter`、`CopilotContextAssembler`、现有知识检索/回答生成链以及 `CopilotController`，所以不建议复制它的 Python/Django 代码。可清洁实现以下模式：有历史才改写、检索结果上限、每一步记录脱敏时延、无关问题不进 RAG。

- 额外依赖：若直接复制，需要 Python、Django/DRF、外部检索服务和 GPT-4 配置；FarMap 不应引入这些依赖。
- 许可证：`farmer-chat/LICENSE` 是 GPL-3.0。GPL 修改版和衍生代码有开源与许可证义务，不能直接拷贝到未完成法律评估的闭源商用模块。
- 修改范围：清洁实现约 1 个 Java normalizer、1 个路由测试和现有 response policy 的小改动；直接移植则是高范围重写，不推荐。
- 测试：同一会话首轮问题、代词追问、无关问候、检索失败回退、检索候选数上限和回答来源标记。

源码：[`intent.py`](https://raw.githubusercontent.com/digitalgreenorg/DG_Open/main/farmer-chat/intent_classification/intent.py)、[`rephrase.py`](https://raw.githubusercontent.com/digitalgreenorg/DG_Open/main/farmer-chat/rephrasing/rephrase.py)、[`content_retrieval.py`](https://raw.githubusercontent.com/digitalgreenorg/DG_Open/main/farmer-chat/retrieval/content_retrieval.py)、[`rerank.py`](https://raw.githubusercontent.com/digitalgreenorg/DG_Open/main/farmer-chat/reranking/rerank.py)、[`generate_response.py`](https://raw.githubusercontent.com/digitalgreenorg/DG_Open/main/farmer-chat/generation/generate_response.py)、[`views.py`](https://raw.githubusercontent.com/digitalgreenorg/DG_Open/main/farmer-chat/api/views.py)、[`LICENSE`](https://raw.githubusercontent.com/digitalgreenorg/DG_Open/main/farmer-chat/LICENSE)。

### 3.2 `AgriGPT`

实际代码的可复用模式主要在：

- `backend/agents/master_agent.py` 的 `route_query`：限制输入长度，读取有界历史；文本问题经过带分数的结构化路由，图片存在时强制进入病虫害分支，并行执行主/辅助 Agent，最后交给 `FormatterAgent`。
- `backend/core/router_schema.py`：用 Pydantic 的 `RouterOutput` 和 0–100 分数约束模型路由输出。
- `backend/core/memory_manager.py`：Redis 可用时持久化，不可用时内存降级；历史长度和提示词长度都有上限。
- `backend/core/langchain_prompts.py`：要求理解 it/that 等上下文指代，并限制 Formatter 只整理专家结果、不凭空新增建议。
- `backend/agents/formatter_agent.py`：按主 Agent、辅助 Agent、影响信息排序，模型失败时回退拼接结果。
- `backend/routes/ask_router.py`：对图片类型、大小、临时文件和清理做边界校验。
- `backend/core/guardrails.py`：无检索证据时要求回答明确说明，不把未验证方案说成确定事实。

FarMap 已有更适合生产的 Java/Spring 路由、上下文、RAG、Agent 审批和 SSE。因此可复用的只是设计约束：结构化 Router 输出、主/辅助结果分工、无证据免责声明、输入/图片边界校验。

- 额外依赖：LangChain、Redis/内存记忆、Pinecone/FAISS、Anthropic/Groq 等，和 FarMap 的百炼/Spring/LangGraph 不是同一运行边界。
- 许可证：仓库 README 明确写有 `academic purposes only`；通过仓库级 LICENSE 路径未发现有效 LICENSE。不能把该代码当作商用可直接复用代码。
- 修改范围：若只清洁实现约 1–2 个现有 Java policy/测试；若直接移植需要跨语言和多供应商重构，不推荐。
- 测试：路由分数边界、图片强制诊断的例外（参考图片请求不能误判为现场诊断）、无证据免责声明、格式化失败回退、历史长度和临时图片清理。

源码：[`master_agent.py`](https://raw.githubusercontent.com/isakshay007/AgriGPT/main/backend/agents/master_agent.py)、[`memory_manager.py`](https://raw.githubusercontent.com/isakshay007/AgriGPT/main/backend/core/memory_manager.py)、[`router_schema.py`](https://raw.githubusercontent.com/isakshay007/AgriGPT/main/backend/core/router_schema.py)、[`langchain_prompts.py`](https://raw.githubusercontent.com/isakshay007/AgriGPT/main/backend/core/langchain_prompts.py)、[`formatter_agent.py`](https://raw.githubusercontent.com/isakshay007/AgriGPT/main/backend/agents/formatter_agent.py)、[`ask_router.py`](https://raw.githubusercontent.com/isakshay007/AgriGPT/main/backend/routes/ask_router.py)、[`guardrails.py`](https://raw.githubusercontent.com/isakshay007/AgriGPT/main/backend/core/guardrails.py)、[`README`](https://github.com/isakshay007/AgriGPT)。

### 3.3 `kisaan-ai`

这是五个仓库中最接近农业 Agent 图编排的实现，但不应替换 FarMap：

- `backend/agents/graph.py` 使用 LangGraph `StateGraph`，把 router、疾病、市场、方案、土壤和语音分支连接起来；疾病分支把视觉判断与建议连接。
- `backend/agents/router.py` 先做语言检测/翻译、实体抽取和意图分类，并把置信度、语言、实体和 trace 写入共享状态。
- `backend/agents/state.py` 定义了跨节点共享的 query、image/audio、crop/location、intent、confidence、retrieved_docs、treatment_advice 和 trace。
- `backend/services/zero_shot_classifier.py` 有关键词快速路径和零样本模型回退；低置信度不强行执行。
- `backend/services/classifier.py` 对视觉分类设置信心阈值；不确定时要求补充信息，不越过病害 taxonomy。
- `backend/agents/vision_agent.py` 对低置信度图片请求澄清；`advisory_agent.py` 检索 top-3 治疗片段再整理固定结构答案。
- `backend/rag/vector_store.py` 是疾病/方案的 pgvector 检索，但不是参考图谱检索。

FarMap 应吸收“共享状态 + 置信度 + 澄清 + trace”的思想，放入现有 LangGraph State/Java 运行边界；不要再引入一个 kisaan 图。尤其要保留 FarMap 的图片需求策略：用户说“给我一张膨大期照片”是参考图检索，不是上传现场图诊断。

- 额外依赖：HuggingFace NER/零样本模型、sentence-transformers、pgvector、Claude/视觉服务等；模型权重、数据集和 API 条款不能由 MIT 代码许可证代替。
- 许可证：仓库 LICENSE 是 MIT，代码层面允许商用复用但必须保留版权和许可证声明；第三方模型/数据仍需单独核验。
- 修改范围：只取状态字段、置信度策略和澄清策略，约 2–4 个现有 Java 类的增量；直接接入其 Python 服务不推荐。
- 测试：语言/错别字、实体和地块继承、低置信度澄清、图片诊断阈值、RAG top-k、trace 脱敏和 LangGraph 节点回归。

源码：[`graph.py`](https://raw.githubusercontent.com/SIDDARTHAREDDY8/kisaan-ai/main/backend/agents/graph.py)、[`router.py`](https://raw.githubusercontent.com/SIDDARTHAREDDY8/kisaan-ai/main/backend/agents/router.py)、[`state.py`](https://raw.githubusercontent.com/SIDDARTHAREDDY8/kisaan-ai/main/backend/agents/state.py)、[`zero_shot_classifier.py`](https://raw.githubusercontent.com/SIDDARTHAREDDY8/kisaan-ai/main/backend/services/zero_shot_classifier.py)、[`classifier.py`](https://raw.githubusercontent.com/SIDDARTHAREDDY8/kisaan-ai/main/backend/services/classifier.py)、[`vision_agent.py`](https://raw.githubusercontent.com/SIDDARTHAREDDY8/kisaan-ai/main/backend/agents/vision_agent.py)、[`advisory_agent.py`](https://raw.githubusercontent.com/SIDDARTHAREDDY8/kisaan-ai/main/backend/agents/advisory_agent.py)、[`vector_store.py`](https://raw.githubusercontent.com/SIDDARTHAREDDY8/kisaan-ai/main/backend/rag/vector_store.py)、[`LICENSE`](https://raw.githubusercontent.com/SIDDARTHAREDDY8/kisaan-ai/main/LICENSE)。

### 3.4 CopilotKit

CopilotKit 的价值主要是网页 Agent 交互，不是 FarMap 后端农业逻辑：

- `packages/react-core/src/v2/headless.ts` 把 `useAgent`、`useThreads`、`useHumanInTheLoop` 等能力和 UI 解耦，适合作为 FarMap 自有 React 组件的设计参考。
- `packages/react-core/src/v2/hooks/use-agent.tsx` 以 `agentId/runtimeAgentId/threadId` 建立线程级 Agent，并订阅消息、状态和运行状态。
- `packages/react-core/src/v2/hooks/use-threads.tsx` 提供用户/Agent 范围的列表、分页、重命名、归档、恢复、删除和新建线程。
- `packages/react-ui/src/components/chat/Chat.tsx`、`Input.tsx`、`AttachmentQueue.tsx` 实现了附件队列、粘贴图片、上传状态、删除附件和运行中的 Stop 按钮。
- `packages/react-ui/src/hooks/use-push-to-talk.tsx` 用 `MediaRecorder` 实现按住说话/转写。
- `packages/runtime-client-gql/src/message-conversion/agui-to-gql.ts` 把用户/助手图片、工具调用、工具结果和生成式 UI 转成消息模型。

FarMap 已经有 `CopilotDrawer`、SSE、会话列表、附件和 Agent action 审批 API，所以接入完整 CopilotKit 会重复引入线程/运行时边界。建议只清洁实现这些 UI 模式或做单向协议适配，不安装完整包。

- 额外依赖：CopilotKit React UI/core/runtime client；会把现有 SSE/历史/审批再包一层。
- 许可证：仓库 LICENSE 是 MIT；直接使用仍要保留版权声明，并要单独核验其具体包、平台服务和版本条款。
- 修改范围：如果只复用交互模式，约 1 个 `CopilotDrawer` 和 SSE parser 的小范围修改；如果直接接入包，预计中高范围并可能形成双运行时。
- 测试：切换历史线程、创建新会话、粘贴/删除图片、运行中取消、SSE 断线、Agent 审批按钮和不同用户的线程隔离。

源码：[`headless.ts`](https://raw.githubusercontent.com/CopilotKit/CopilotKit/main/packages/react-core/src/v2/headless.ts)、[`use-agent.tsx`](https://raw.githubusercontent.com/CopilotKit/CopilotKit/main/packages/react-core/src/v2/hooks/use-agent.tsx)、[`use-threads.tsx`](https://raw.githubusercontent.com/CopilotKit/CopilotKit/main/packages/react-core/src/v2/hooks/use-threads.tsx)、[`CopilotThreadsDrawer.tsx`](https://raw.githubusercontent.com/CopilotKit/CopilotKit/main/packages/react-core/src/v2/components/chat/CopilotThreadsDrawer.tsx)、[`Chat.tsx`](https://raw.githubusercontent.com/CopilotKit/CopilotKit/main/packages/react-ui/src/components/chat/Chat.tsx)、[`use-push-to-talk.tsx`](https://raw.githubusercontent.com/CopilotKit/CopilotKit/main/packages/react-ui/src/hooks/use-push-to-talk.tsx)、[`agui-to-gql.ts`](https://raw.githubusercontent.com/CopilotKit/CopilotKit/main/packages/runtime-client-gql/src/message-conversion/agui-to-gql.ts)、[`LICENSE`](https://raw.githubusercontent.com/CopilotKit/CopilotKit/main/LICENSE)。

### 3.5 assistant-ui

assistant-ui 更适合作为现有 LangGraph 的前端交互参考：

- `packages/react/src/primitives/composer/ComposerInput.tsx` 处理自动高度、IME 安全 Enter、粘贴文件和提交。
- `ComposerCancel.ts`、`ComposerDictate.ts`、`ComposerAddAttachment.ts` 分别提供取消运行、语音输入和附件入口。
- `packages/react/src/primitives/thread/ThreadViewport.tsx` 处理自动滚动、回到底部、线程切换和消息视口。
- `packages/react-langgraph/src/useLangGraphRuntime.ts` 是 LangGraph 的 React 适配器，处理流、历史、工具队列、附件、interrupt 和取消；它证明前端可以适配 LangGraph，而不是要求替换 LangGraph。
- `packages/react-ag-ui/src/useAgUiRuntime.ts` 和 `packages/react/src/assistant-transport.ts` 展示了历史适配器、线程列表适配器、工具审批、取消和协议转换的边界。

FarMap 可在自有 `CopilotDrawer` 中采用相同的状态拆分：`idle/running/cancelled/completed/error`、消息附件和线程级消息；不要把 assistant-ui runtime 与现有 SSE/Agent Runtime 同时接入。

- 额外依赖：assistant-ui React 包以及其 LangGraph/AG-UI adapter；直接使用会引入新的前端 transport/runtime 边界。
- 许可证：仓库 LICENSE 是 MIT，保留版权/许可证即可，但 adapter 依赖和版本兼容仍需审计。
- 修改范围：清洁实现约 1–2 个输入/消息组件；直接安装包为中范围，且会增加迁移和样式成本。
- 测试：IME 输入、粘贴图片、语音按钮、取消、线程历史上翻、自动滚动、LangGraph 流事件和工具审批。

源码：[`ComposerInput.tsx`](https://raw.githubusercontent.com/assistant-ui/assistant-ui/main/packages/react/src/primitives/composer/ComposerInput.tsx)、[`ComposerCancel.ts`](https://raw.githubusercontent.com/assistant-ui/assistant-ui/main/packages/react/src/primitives/composer/ComposerCancel.ts)、[`ComposerDictate.ts`](https://raw.githubusercontent.com/assistant-ui/assistant-ui/main/packages/react/src/primitives/composer/ComposerDictate.ts)、[`ThreadViewport.tsx`](https://raw.githubusercontent.com/assistant-ui/assistant-ui/main/packages/react/src/primitives/thread/ThreadViewport.tsx)、[`useLangGraphRuntime.ts`](https://raw.githubusercontent.com/assistant-ui/assistant-ui/main/packages/react-langgraph/src/useLangGraphRuntime.ts)、[`useAgUiRuntime.ts`](https://raw.githubusercontent.com/assistant-ui/assistant-ui/main/packages/react-ag-ui/src/useAgUiRuntime.ts)、[`assistant-transport.ts`](https://raw.githubusercontent.com/assistant-ui/assistant-ui/main/packages/react/src/assistant-transport.ts)、[`LICENSE`](https://raw.githubusercontent.com/assistant-ui/assistant-ui/main/LICENSE)。

## 4. 两类落地方案比较

| 方案 | 做法 | 优点 | 风险/代价 | 判断 |
|---|---|---|---|---|
| A：移植输入理解、澄清、回答整理 | 在现有 `CopilotQueryNormalizer`、`CopilotIntentRouter`、`CopilotMediaPolicy`、`CopilotResponsePolicy` 和 LangGraph State 中吸收源码模式 | 不改变 LangGraph、百炼、RAG、SSE、历史和审批；可逐场景测试 | 需要持续维护农业术语、实体和澄清样例 | **当前采用** |
| B：引入网页 Agent 交互组件/协议适配 | 只借鉴 assistant-ui/CopilotKit 的线程、附件、取消、语音和消息事件模型，必要时实现薄 adapter | UI 行为成熟，能改善取消、粘贴图片、历史线程体验 | 直接安装会引入另一套前端 runtime/transport，和现有 SSE/审批产生边界重复 | **后续可选，不在本 PoC 引入** |

## 5. 最小 PoC 变更

### 5.1 代码变更

本分支只增加一个 alias：

```text
棚大期 -> 膨大期
```

变更位置：

- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/CopilotQueryNormalizer.java`
- `zhgy/zhgy/src/test/java/com/mei/zhgy/service/copilot/CopilotIntentHybridTest.java`
- `zhgy/zhgy/src/test/java/com/mei/zhgy/service/copilot/CopilotMediaPolicyTest.java`
- `zhgy/zhgy/src/test/java/com/mei/zhgy/service/reference/ReferenceImageAttacherTest.java`

没有复制任何上述开源仓库的代码，没有安装新包，没有改生产路由默认模式，没有清空/重建 Milvus，没有改变会话历史和任务审批协议。

### 5.2 证明的场景

| 场景 | 预期 | PoC 覆盖 |
|---|---|---|
| `果实棚大期是什么样的` | 输入先被规范成 `果实膨大期...`，保持 `KNOWLEDGE_QUERY`，不进入现场诊断 | 新增路由回归断言 |
| `给我一张果实棚大期的照片` | 保持知识查询，媒体需求为 `EXPLICIT`，参考图 attacher 查询 `fruit_development` | 新增媒体策略和参考图 attacher 回归断言 |
| `给我一张膨大期照片` | 参考图检索，不要求用户上传现场图片 | 已有路由/媒体测试继续覆盖 |
| `为什么要这样管理` | 走知识解释并继承已有会话稳定上下文 | 现有 `CopilotRouteService`/`CopilotContextAssembler` 逻辑保留，本次不改实现 |
| `创建明天的浇水任务` | 进入 `ACTION_REQUEST`，解析后仍停在 `START_AGENT_REQUIRES_APPROVAL` | 现有 Agent Runtime/Operations 测试和审批 API保留，本次不改实现 |
| `什么是叶面肥` | 普通知识问答，不触发媒体 atlas | 现有 `CopilotMediaPolicyTest` 保留 |

## 6. 测试与边界

已做的检查：

- `git diff --check`：通过（仅有工作区既有的 LF/CRLF 提示）。
- `pnpm exec tsc -b`：通过。
- `pnpm exec vite build`：通过；只有既有大 chunk 警告。
- 本 PoC 没有修改前端，因此前端构建结果用于确认当前工作区前端没有被本次评估破坏。

当前环境限制：PowerShell 中未发现 `mvn`/`mvnw`，本机 Docker 构建此前又被 Docker Hub 授权/网络失败阻断。因此本报告不把新增 Java 测试声称为“已在本轮 Maven 执行通过”；交付前应在已有 Maven/Docker CI 环境执行：

```text
mvn -Dtest=CopilotIntentHybridTest,CopilotMediaPolicyTest,ReferenceImageAttacherTest test
mvn test
```

并额外执行已有浏览器 E2E：图片粘贴/参考图显示、会话历史切换、SSE 断线取消、任务审批。若 Milvus 或 COS 不可用，测试应明确显示 `UNAVAILABLE/NO_MATCH`，不能用其他病害或物候图片冒充结果。

## 7. 增量集成建议

第一阶段先合并本 PoC 的领域别名和测试。第二阶段在现有 `CopilotIntentRouter` 中补充结构化实体槽位（crop、field、stage、date、action），模型只负责候选解析，服务器负责校验和确认。第三阶段把 assistant-ui/CopilotKit 的交互模式按组件级清洁实现补入：运行中 Stop、输入框粘贴附件、线程切换、按用户隔离的历史、断线重连；继续复用 FarMap SSE 和 Agent 审批 API。第四阶段才考虑一个明确版本化的事件协议 adapter，前提是先证明当前 SSE 事件字段不能满足需求。

生产发布前必须分别验证：

1. `RULE_ONLY`、`SHADOW`、`HYBRID` 三种配置下，别名和安全写操作边界不变。
2. 同一会话的上下文继承与不同用户的 `userId` 隔离。
3. 参考图片必须来自真实 `ReferenceImage` 记录，包含 `refId/imageUrl/label/source`，且 URL 通过允许域名校验。
4. Agent 写操作必须继续经过 `approval.required`、批准/拒绝和 Operations 幂等链路。
5. 简单问候和普通知识问答不应触发无意义的多模型、多工具和图片检索。
