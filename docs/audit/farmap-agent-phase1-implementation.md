# FarMap Agent Phase 1 实施报告

## 1. 本次改造目标

本次在现有 Copilot、SSE、RAG、多模态诊断和 Agent Runtime 之上增加一个最小的响应策略层，使回答形式按问题复杂度和信息完整性动态选择：

- `SIMPLE`：普通对话和短解释。
- `KNOWLEDGE`：专业知识回答，可展示真实知识来源。
- `GUIDED`：结合农情上下文与知识证据的结构化农事指导。
- `AGENT`：已有诊断/Agent Run/审批工作流。
- `CLARIFICATION`：只追问真实缺失的对象、图片或必要条件。

没有重写意图路由、RAG、图片诊断、Agent Runtime、数据库结构或模型供应商。

## 2. 真实修改的文件

本阶段实际新增或修改的文件如下。工作区中其他未提交文件属于此前任务或用户修改，本阶段没有覆盖它们。

### 后端

- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/CopilotResponseMode.java`
  - 新增五种回答模式枚举，与业务意图分离。
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/CopilotResponseMetadata.java`
  - 新增兼容 SSE 的统一响应元数据，包含卡片、来源、追问和操作字段。
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/CopilotResponsePolicy.java`
  - 基于已有路由结果、问题信号、图片是否存在和事实状态确定响应模式；不调用模型、不解析模型 JSON。
- `zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java`
  - 增加 `response.meta` SSE 事件。
  - 增加缺现场图片与缺地块对象的区分。
  - 识别“暂不执行/不要创建任务/只读分析”等约束；只读诊断完成后不创建 Agent Run。
  - 透传检索结果中的 `sourceId/documentId/versionId/chunkId` 等已有 Provenance。
- `zhgy/zhgy/src/test/java/com/mei/zhgy/service/copilot/CopilotResponsePolicyTest.java`
  - 新增五种模式、缺图判定、来源透传和 SSE JSON 契约测试。

### 前端

- `farmap-frontend-dev/src/features/copilot/contract.ts`
  - 新增统一响应元数据、卡片、来源、建议追问和操作入口类型。
- `farmap-frontend-dev/src/store/copilot.ts`
  - 历史兼容地增加可选 `responseMeta`，旧消息仍可直接显示。
- `farmap-frontend-dev/src/components/copilot/CopilotDrawer.tsx`
  - 消费 `response.meta`。
  - 保留流式 Markdown 正文，同时按模式展示知识来源折叠区、结构化证据卡片和动态追问。
  - 保留既有图片预览、粘贴上传、语音输入、Agent Run 状态和审批按钮。

## 3. 五种响应模式的选择规则

响应模式不是业务意图的一一映射：

- 路由未解析、无权限、上下文过期或明确要求补充信息：`CLARIFICATION`。
- `ACTION_REQUEST`：`AGENT`，继续走现有 Evidence Sufficiency、Agent Runtime 和人工审批。
- `DIAGNOSIS_REQUEST`：有图片或不需要视觉证据时进入 `AGENT`；问题明确要求现场图片但没有可信图片时进入 `CLARIFICATION`。
- 管理方案、风险、肥水、下一步建议、未来一周等复杂问题：`GUIDED`。
- 知识库问题或“是什么/为什么/有哪些”等专业解释：`KNOWLEDGE`。
- 你好、感谢、短概念问答等无复杂上下文问题：`SIMPLE`。

知识证据和农情卡片只使用服务端已经取得的数据，不从模型文本中猜来源或构造虚假施肥量、农药剂量和病害结论。

## 4. 新旧响应协议兼容说明

原有路由 JSON、`content.delta`、`status`、`knowledge`、`reference.images`、`answer.source`、`diagnosis.job`、`run.created`、`error` 和 `done` 均保留。

新增的 `CopilotResponseMetadata` 是可选元数据。历史消息没有该字段时，前端继续按原有正文、图片和知识数量显示；旧客户端忽略新增 SSE 事件也不会影响正文流。

`content` 仍由原有文本流提供，元数据中的 `content` 当前保持为空字符串，避免重复显示正文。`messageId` 优先使用已有客户端消息 ID，否则回退到路由 trace ID。

## 5. SSE 事件变化

新增：

```text
event: response.meta
data: {
  schemaVersion,
  messageId,
  responseType,
  content,
  cards,
  sources,
  suggestions,
  actions
}
```

普通问答在知识检索和来源计算完成后发送；澄清路径在正文前发送；诊断/Agent 路径在启动现有能力前发送。原有正文仍按 `content.delta` 增量发送，最终仍由 `done` 表示完成。

没有改变客户端停止请求、异常事件或旧历史消息的处理方式。

## 6. 新增前端组件能力

本阶段采用 `CopilotDrawer.tsx` 内的轻量渲染入口，避免在现有抽屉中引入平行消息体系：

- `MarkdownMessage`：已有安全 Markdown 渲染继续复用。
- `renderResponseMetadata`：根据统一响应元数据显示结构化卡片、真实来源和动态追问。
- 现有 `renderAgentProgress`：继续负责 Agent Run、证据、审批和实际状态。
- 现有 `renderCommandBarResult`：继续负责优先事项、农场/地块状态、已保存诊断和历史病例结果。

动态追问最多三条，并写回现有输入框，不会直接伪造执行成功。元数据的 `actions` 字段保留给已经有真实处理器的操作；本阶段没有展示未接线的假按钮。

## 7. 复用的现有组件和安全边界

- 规则路由仍是主路由来源。
- `shadow-mode=true` 保持不变，未切换百炼模型池接管主流程。
- 知识检索仍使用现有 BM25/RRF/向量和 Provenance 链路。
- 图片仍使用现有上传和可信 URL 校验；未改变图片诊断模型调用链。
- 业务写操作仍由 Agent Runtime、owner 鉴权、Evidence Sufficiency 和人工审批控制。
- 只读诊断可以保存诊断结果，但“创建任务/执行操作”仍不会因为模型生成文本而自动发生。

## 8. 测试命令与结果

### 后端

```powershell
cd D:\前后端项目\farmap\zhgy\zhgy
& D:\apache-maven-3.9.6\bin\mvn.cmd -q `
  '-Dtest=CopilotResponsePolicyTest,CopilotPrivateFactPolicyTest,CopilotIntentHybridTest,LlmIntentClassifierTest,KnowledgeBm25Test,KnowledgeChunkerTest,KnowledgeEvidenceProvenanceTest,AgentEvidenceSufficiencyTest,AgentPlannerTest,AgentRunStateMachineTest,AgentContextTest' `
  test
```

结果：32 个测试运行，0 failures，0 errors，0 skipped。包含新增 6 个 `CopilotResponsePolicyTest`。

### 前端

```powershell
cd D:\前后端项目\farmap\farmap-frontend-dev
pnpm lint
pnpm build
```

结果：lint 退出码 0；Vite production build 成功。构建仍有既有的大 chunk 提示，但不是本阶段错误。

## 9. 尚未完成的功能

- 尚未接入真实浏览器到生产服务的全链路人工验收。
- 尚未让模型直接输出可执行的结构化农事方案；本阶段只对已验证的服务端证据做结构化展示。
- 历史病例向量集合仍需真实数据，不能因本次 UI 协议改造自动补齐。
- 追问按钮只回填输入框，不自动发送；这是为了避免用户误触导致诊断或业务操作。
- 前端项目当前没有独立 React 单元测试运行器；本阶段使用 TypeScript 编译型生产构建、lint 和后端 JSON 契约测试验证协议。没有新增付费测试依赖。

## 10. 真实模型验收限制

本阶段没有调用需要付费的真实模型接口，也没有把 Mock/单测当成真实模型验收。模型实际可用性、首 token 延迟、模型池权限、真实图片诊断耗时和真实知识命中率，需要在开发服务启动后由人工使用真实百炼配置验证。

## 11. 回滚方式

回滚本阶段改造时，删除三个新增后端类和新增测试，并恢复以下三个前端文件及 `CopilotController.java` 到改造前版本即可：

- `farmap-frontend-dev/src/components/copilot/CopilotDrawer.tsx`
- `farmap-frontend-dev/src/features/copilot/contract.ts`
- `farmap-frontend-dev/src/store/copilot.ts`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java`

由于新协议是新增 SSE 事件，没有数据库迁移，也没有向量集合或生产配置变更。

## 12. 下一阶段建议

1. 用真实网页分别验证：简单定义、知识问答、管理建议、缺图诊断、带图诊断、只读分析和需要审批的任务请求。
2. 对每个真实回答记录 `responseType`、知识状态、来源 ID、诊断 ID、Agent Run 状态和用户最终操作。
3. 在确认来源和卡片结构稳定后，再把 GUIDED 的证据卡片扩展为后端明确输出的“核心结论/肥水/病虫害/注意事项”结构，而不是让前端猜测 Markdown。
4. 只有在 shadow 评估达到上线门槛后，才单独评估模型路由是否接管；本阶段不处理该切换。

## 13. 交付结论

- 已修改后端响应策略、SSE 元数据和缺图澄清逻辑。
- 已修改前端消息契约和抽屉动态渲染。
- 已保留原有流式对话、历史消息、图片上传/粘贴、图片诊断、Agent Run 和审批能力。
- 已通过后端相关 32 个测试、前端 lint 和 production build。
- `shadow-mode=true` 保持不变。
- 未影响原有图片诊断上传和可信 URL 处理；只读分析不会创建 Agent Run。
- 未完成真实模型和线上浏览器 E2E 验收，需要人工启动服务后按真实百炼配置测试。
- 当前没有发现必须人工修复的编译、lint 或相关回归测试问题；仍需人工确认真实环境的模型权限、图片地址可访问性和知识库返回内容。
