# FarMap Agent V4 正式集成报告

日期：2026-09-23  
工作分支：`codex/poc-open-source-agent-reuse`

## 1. 交付结论

本阶段没有替换 LangGraph，没有引入第二套 Agent Runtime，没有重写 Spring Boot API、RAG、Milvus 或 Operations。采用的是上一阶段报告推荐的方案 A：把已验证且许可证边界清楚的输入理解、澄清和回答协议模式，增量接入现有 FarMap 路由和 Agent Runtime。

已完成的安全集成：

1. 统一理解结果现在携带 `originalQuery`、`normalizedQuery`、`intentSlots`、`mediaNeed` 和 `requestedAction`。
2. `棚大期` 被加入现有领域规范化层，映射到 `膨大期`；不会对未知相似词做通配替换。
3. 参考图片需求继续与现场图片诊断分离。
4. 任务缺参后的 `对/确认/好的` 会恢复原始 `ACTION_REQUEST`，而不是重新变成普通问答。
5. 恢复后的任务继续经过现有 Agent Runtime、人工审批和 Operations 幂等边界。
6. 增加了 V4 确定性回归矩阵，覆盖术语纠错、知识问答、参考图片、现场诊断、复杂指导、任务请求和否定约束。
7. 没有安装 CopilotKit 或 assistant-ui；React 继续使用 FarMap 自己的 SSE、历史会话和 Agent action UI。

仍未宣称全部完成：真实百炼回答、真实 Milvus/COS 每条数据的浏览器回归、不同账号浏览器隔离、真实任务写入和线上性能压测需要独立验收，不能用单元测试代替。

## 2. 上一阶段证据复盘

来源：

- `docs/audit/farmap-agent-capability-audit.md`
- `docs/audit/farmap-agent-phase1-implementation.md`
- `docs/audit/farmap-agent-phase2-hybrid-router.md`
- `docs/audit/farmap-agent-phase3-experience-fix.md`
- `docs/audit/farmap-agent-responsiveness-fix.md`
- `docs/audit/farmap-agent-streaming-ux-fix.md`
- `docs/audit/farmap-image-retrieval-routing-fix.md`
- `docs/audit/farmap-open-source-agent-reuse.md`

| 能力 | 上一阶段真实证据 | V4 判断 |
|---|---|---|
| 规则意图路由 | 路由矩阵和后端测试通过 | 已存在，V4 只增强输入纠错和结构化输出 |
| qwen-flash Router | Phase 2 报告记录过真实 JSON 探针成功 | 当前配置默认仍由 `FARMAP_INTENT_SHADOW_MODE` 控制，默认值是 shadow；不擅自打开生产 HYBRID |
| 五种响应模式 | Phase 1 单测和前端构建通过 | 已存在，V4 不重复创建 renderer |
| 参考图片检索 | 上一阶段真实巫山接口返回 3 张病害参考图，且前端 E2E 曾验证显示 | 已存在；当前 V4 只保证输入意图不会把参考图请求误导到上传诊断 |
| 用户图片诊断 | 上传、视觉 gateway、Evidence 链路存在 | 仍需真实图片模型验收，不能由配置健康状态代替 |
| 会话/用户隔离 | JDBC 查询带 `user_id`，历史 UI 有恢复接口 | 已存在；V4 增加任务澄清恢复，不改变 owner 条件 |
| Agent/审批/Operations | Runtime 有 approval required、批准/拒绝和任务幂等代码 | V4 修复自然语言确认后的恢复；不执行真实生产写入 |
| 流式/取消 | 前端和 SSE 取消、迟到事件处理已有前一阶段修复 | V4 不引入新 UI Runtime |
| 开源复用 | 源码审计和许可证审计已完成 | 只采用清洁实现模式，不复制受限源码 |

### 2.1 商业化许可结论

| 项目 | 源码实际价值 | 许可/商业化结论 | V4 处理 |
|---|---|---|---|
| Digital Green farmer-chat | 意图分层、历史改写、检索重排、回答整理 | GPL-3.0，不直接复制到闭源商用 FarMap | 只清洁实现边界和失败回退模式 |
| AgriGPT | 结构化路由分数、短期记忆、Formatter、guardrail | README 标注 academic purposes only，且未发现有效仓库 LICENSE | 不复制代码或依赖 |
| kisaan-ai | 共享状态、置信度、澄清、LangGraph 节点组织 | MIT；模型、数据和第三方 API 仍需单独核验 | 只吸收状态/置信度模式，不接入其 Python Runtime |
| CopilotKit | 线程、附件、取消、语音、headless UI | MIT；完整运行时会与 FarMap SSE/会话/审批重叠 | 不安装，保留 FarMap 自有 UI |
| assistant-ui | Composer、ThreadViewport、LangGraph adapter | MIT；直接接入会增加新的前端 transport/runtime 边界 | 不安装，只借鉴交互模式 |

详细源码和许可证链接见上一阶段报告：[farmap-open-source-agent-reuse.md](farmap-open-source-agent-reuse.md)。

## 3. 修改前后架构

### 修改前

```text
用户输入
  -> CopilotIntentRouter / qwen-flash shadow 或 hybrid
  -> CopilotRouteService
  -> ContextResolver
  -> Knowledge / Diagnosis / Agent Runtime
  -> SSE
  -> CopilotDrawer
```

缺口是：规范化结果没有作为统一协议向后续层传递；动作澄清只保存问题文本，确认词无法恢复原动作；前端能看到 route，但无法稳定消费理解结果字段。

### 修改后

```text
原始输入 + pageContext + conversationId + userId
  -> CopilotQueryNormalizer
  -> Router（规则优先，qwen-flash 按 RULE_ONLY/SHADOW/HYBRID 配置）
  -> CopilotRouteResult
       originalQuery
       normalizedQuery
       intent / intentSlots
       mediaNeed
       resolvedContext
       requestedAction
       clarification / allowedNextAction
  -> 既有 ContextResolver
  -> 只读能力 / Reference Image / User Image Diagnosis / Agent Runtime
  -> 既有 SSE route + response.meta + reference.images + run events
  -> 既有 CopilotDrawer / 历史 / 审批 UI
```

## 4. 实际修改文件和函数

### 后端

- `service/copilot/CopilotQueryNormalizer.java`
  - `forMatching` 增加受控别名 `棚大期 → 膨大期`。
- `service/copilot/CopilotIntentRouter.java`
  - 将“管理方案”和“那个地块/刚才那个地块”纳入现有推荐/上下文识别，不新增意图标签。
- `service/copilot/CopilotMediaPolicy.java`
  - 复用统一 normalizer，保证媒体判断和路由使用同一规范化结果。
- `service/copilot/CopilotRouteResult.java`
  - 增加理解协议字段：`originalQuery`、`normalizedQuery`、`mediaNeed`、`requestedAction`。
- `service/copilot/CopilotRouteService.java`
  - `route` 在现有会话状态上恢复 pending action。
  - `isPendingActionConfirmation` 识别有限确认词。
  - `pendingActionMessage` 从最近的用户动作轮次取得原始动作。
  - `copyWithMessage` 只给解析器使用，持久化仍保留用户真实输入。
- `service/copilot/CopilotConversationService.java`
  - 将动作缺参状态持久化为既有字段中的 `ACTION` 类型，不新增数据库列。
- `controller/CopilotController.java`
  - 读取 `requestedAction` 作为恢复后的 Agent goal。
  - 仍然先创建 Agent 草案，再由已有 approval API 决定是否执行。
- `service/copilot/CopilotResponseMetadata.java`、`CopilotResponsePolicy.java`
  - 在已有 `response.meta` 中增加 intent、resolution、normalizedQuery 和 mediaNeed，便于前端和观测读取。

### 测试

- `CopilotTaskClarificationContinuationTest.java`
  - 验证“创建明天浇水任务”缺地块后，“对”可以恢复原动作。
- `CopilotAgentV4RegressionTest.java`
  - 统一验证 14 个确定性输入理解、意图和媒体分离场景。
- 延续上一阶段的 `CopilotIntentHybridTest`、`CopilotMediaPolicyTest`、`ReferenceImageAttacherTest`。

### 前端

- `farmap-frontend-dev/src/features/copilot/contract.ts`
  - 只增加新增 SSE/route 字段的 TypeScript 类型。
- 没有安装或引入 CopilotKit/assistant-ui，也没有替换现有 `CopilotDrawer`、SSE parser、历史会话和审批组件。

## 5. 输入理解与上下文协议

### 5.1 输入字段

| 字段 | 来源 | 权威性 |
|---|---|---|
| `originalQuery` | 本轮用户原文 | 仅用于审计/展示，不能直接当作安全参数 |
| `normalizedQuery` | 领域 normalizer | 只包含已确认的有限别名 |
| `intent` | 规则/Hybrid 最终裁决 | 受 protected intent 安全边界约束 |
| `intentSlots` | qwen-flash 候选槽位 | 非权威，必须再由服务端校验 |
| `resolvedContext` | Field/Diagnosis/Operations owner 校验 | 服务端权威 |
| `mediaNeed` | 独立媒体策略 | 不等于业务 intent |
| `requestedAction` | 直接任务或恢复后的原始动作 | 仅作为 Agent goal 候选，不能绕过审批 |
| `clarificationQuestion` | ContextResolver | 引导补齐缺失条件 |

### 5.2 上下文来源优先级

1. 本轮显式 farm/field ID。
2. 当前页面选中的 field/farm，作为待校验 hint。
3. 当前会话持久化的 stable context。
4. 历史文本只进入有界模型上下文，不直接当成实体事实。
5. 天气、传感器、物候等时间数据必须由服务端重新读取并附 freshness/status。

前端完整历史和模型上下文仍然分离：`historyTurns` 用于用户上翻查看，`recentTurns` 用于有界路由/模型上下文。

## 6. Router 与能力编排

当前保留：

- `RULE_ONLY`：只用规则。
- `SHADOW`：模型只记录脱敏观测，不接管最终路由。
- `HYBRID`：模型只在允许的长尾场景接管，写操作、导航、记忆和安全意图不能被模型越权提升。

能力映射仍由 `CopilotRouteService.capability` 驱动：

- `GENERAL_QA` → 快速上下文回答。
- `KNOWLEDGE_QUERY` → 知识 RAG，可附参考图。
- `DIAGNOSIS_REQUEST` → 必须有用户图片或进入澄清/诊断链。
- `RECOMMENDATION_QUERY` → 结构化农事指导。
- `ACTION_REQUEST` → Agent Runtime，必须保留审批。

“参考图片”由 `mediaNeed=EXPLICIT/BENEFICIAL` 表示；它不会把没有用户图片的知识问题强行改成现场诊断。

## 7. 图文 RAG 集成方式

### 7.1 Reference Image Retrieval

仍由 `ReferenceImageAttacher.forChatResult` 调用 `ReferenceImageStore.byDiseaseResult/byStageResult`。结果必须包含稳定 `refId`、标签、真实 URL、匹配方式和状态；URL 继续经过配置的 COS host 白名单。

适用：

- `褐腐病是什么样的`。
- `给我看脆李穿孔病参考图片`。
- `给我一张果实膨大期的照片`。

### 7.2 User Image Diagnosis

用户上传图片仍由 `CopilotController`、`DiagnosisJobService`、`VisualEvidenceRetriever` 和百炼视觉模型链路处理。用户图片是现场证据，不能与参考图 atlas 混用。

没有匹配图片时必须返回 `NO_MATCH`；Milvus/COS 不可用时返回 `UNAVAILABLE`；禁止用其他病害图片填充。

上一阶段报告已经记录过巫山真实参考图请求返回 3 张病害图并通过线上浏览器测试；本 V4 没有重建或回填图片索引，也没有把该历史结果扩大为所有作物/所有病害均已验证。

## 8. 农事任务执行闭环

```text
创建明天的浇水任务
  -> ACTION_REQUEST
  -> 校验当前/显式地块
  -> 缺地块：保存 pending_type=ACTION，追问地块
  -> 用户“对/确认”
  -> 恢复原始 action goal + 当前页面地块
  -> Agent Runtime 创建草案
  -> approval.required
  -> 用户批准/驳回
  -> OperationsTaskService 幂等写入
  -> SSE run/action/task 状态反馈
```

本次未调用真实生产写操作。模型生成的回答不能被视为任务已创建；只有 Operations API 返回真实任务和状态时才可展示成功。

## 9. React 交互改造边界

本阶段没有引入第三方聊天 Runtime，因为 FarMap 已经有：

- 右侧 Agent Drawer。
- SSE 增量正文和状态。
- 参考图片消息。
- 粘贴/预览/删除附件。
- 历史会话列表与恢复。
- Agent action 批准/驳回。
- 停止生成和错误恢复。

V4 只扩展 TypeScript contract，使前端可以安全读取新的理解字段。后续如需继续优化 UI，应优先在现有组件中实现 assistant-ui/CopilotKit 已验证的局部模式，不同时安装两个 UI Runtime。

## 10. 统一回归矩阵

| 场景 | 正确路径 | 禁止行为 | 本阶段验证层级 |
|---|---|---|---|
| 你好 | GENERAL_QA | 不查图、不读农情卡 | 确定性单测 |
| 什么是叶面肥 | KNOWLEDGE_QUERY | 不创建任务 | 确定性单测 |
| 果实棚大期是什么样 | 规范化为膨大期，知识回答 | 不进入现场诊断 | 确定性单测 |
| 果实膨大期有什么特征 | KNOWLEDGE + BENEFICIAL | 不要求上传图片 | 确定性单测 |
| 给我一张果实膨大期照片 | KNOWLEDGE + EXPLICIT + stage atlas | 不提示上传现场图 | 确定性单测/既有 attacher 测试 |
| 褐腐病是什么样 | KNOWLEDGE + BENEFICIAL + disease atlas | 不返回无关病害图 | 既有真实接口证据，需持续浏览器复测 |
| 给我看脆李穿孔病参考图片 | reference image retrieval | 不走用户图片诊断 | 确定性媒体测试 |
| 我的叶子发黄，帮我诊断 | 诊断澄清或现场诊断 | 无图不直接下药结论 | 规则/Mock |
| 上传图片后问这是病害吗 | User Image Diagnosis | 不只走文本知识 | Hybrid 单测 |
| 结合 L-01 制定管理方案 | RECOMMENDATION/Guided | 不自动写任务 | 确定性单测 |
| 为什么需要这样管理 | 继承稳定上下文做解释 | 不重复要求地块 | 路由与上下文代码，需浏览器复测 |
| 创建明天浇水任务 | ACTION + 草案 + 审批 | 不直接写 Operations | continuation 单测 |
| 不要创建任务，只分析 | 只读路径 | 禁止 ACTION | 确定性单测 |
| 对，就是刚才那个地块 | 恢复上下文/动作 | 不重置成普通聊天 | continuation 单测 |
| 切换地块后继续追问 | 新页面 field hint 经服务端校验 | 不沿用旧地块事实 | 现有 ContextResolver，需浏览器复测 |
| Router 模型失败 | 回退规则 | 不阻塞对话、不越权 | 既有 fail-open 单测 |
| RAG 为空 | 明确无依据/降级 | 不伪造来源 | 既有 guardrail/contract |
| 图片无匹配 | `NO_MATCH` | 不使用其他图片 | 既有 ReferenceImageLookup |
| 任务创建失败 | 明确失败状态 | 不显示成功 | Agent/Operations 单测 |
| SSE 中断 | 保留已收到正文并可重试 | 不重复提交任务 | 既有前端 SSE 回归 |

需要在 Maven/CI 和有服务的浏览器环境重新执行全矩阵；当前本机没有 Maven wrapper，不能把本轮源码新增测试说成已执行通过。

## 11. 自动化与真实验收结果

### 本轮已执行

- `git diff --check`：通过。
- `pnpm exec tsc -b`：通过。
- `pnpm exec vite build`：通过，只有既有大 chunk 警告。
- V4 新增 Java 测试和既有后端测试：未执行，本机未发现 `mvn`/`mvnw`；不虚报结果。

### 上一阶段已有结果（不等于本轮重跑）

- Phase 1/2/3 报告记录过后端单测、Mock 契约和前端构建通过。
- Phase 2 报告记录过 qwen-flash JSON 探针成功，但当前默认配置仍可能是 SHADOW。
- 图片检索报告记录过巫山真实病害参考图片返回和浏览器显示。
- 响应体验报告记录过真实浏览器的回答完成和停止生成回归。
- 线上真实农事写入没有执行，仍需要隔离测试账号和明确批准。

## 12. 性能和成本变化

- `棚大期` 别名和普通规则路由不增加模型调用。
- pending action 恢复不增加模型调用；复用原始动作文本和已有上下文。
- 结构化字段通过已有 route/response.meta 传输，增加的 JSON 很小。
- 没有增加第三方服务、向量索引或图片 embedding 调用。
- 复杂问题仍可能调用 qwen-flash、知识检索、视觉模型或 Agent 工具；真实性能必须通过首 token、完整耗时、检索耗时和 SSE 完成率实测。

## 13. 未完成事项

1. 本机缺少 Maven/Maven Wrapper，后端新增测试需要 CI 或开发机执行。
2. 需要在浏览器复测 20 个场景，尤其是确认词恢复、切换地块、参考图真实显示和 SSE 断开。
3. 需要在隔离测试账号执行一次“创建任务→审批→Operations 状态”闭环；不能使用生产真实业务数据。
4. 需要确认每个目标病害/物候期的 Milvus 图片数据覆盖率；没有数据时只能返回 `NO_MATCH`。
5. qwen-flash 默认是否接管路由必须由观测结果和明确发布决策决定，本阶段不修改生产默认模式。
6. 源码中的 LangGraph Checkpointer 具体部署实现仍需结合实际 LangGraph 服务配置进行运行时核验；本次不新增 Checkpointer。

## 14. 回滚方案

本次新增逻辑均为兼容性增量：

- 删除 `CopilotTaskClarificationContinuationTest`、`CopilotAgentV4RegressionTest` 后即可移除新增测试。
- 回退 `CopilotRouteService` 的 pending action 分支、`CopilotConversationService` 的 `ACTION` 类型、`CopilotController` 的 `requestedAction` 使用，以及 `CopilotRouteResult/ResponseMetadata` 的新增字段即可恢复旧路由协议。
- 不需要数据库回滚；没有新增表、列、索引、Milvus collection 或外部服务。
- 不要使用 `git reset --hard` 或整目录覆盖，以免破坏工作区已有用户修改。

## 15. 生产部署前检查

- 后端 Maven 全量测试和新增 V4 测试通过。
- RULE_ONLY/SHADOW/HYBRID 三种模式各跑一遍安全路由矩阵。
- 两个用户之间无法读取或恢复对方会话。
- 参考图真实 URL 可访问，缺图只返回 `NO_MATCH/UNAVAILABLE`。
- 现场图片诊断和参考图片检索分别验证。
- 任务草案、审批、拒绝、幂等重复提交和失败恢复在隔离账号通过。
- 不修改生产 API Key、默认路由、生产数据库结构或 Milvus 数据。
- 采集脱敏 trace：request/trace/conversation、最终 intent、mediaNeed、检索状态、图片数量、工具状态和 SSE done/error；禁止记录 API Key、完整图片和不必要原始敏感文本。

