# Milestone 5.1.3 Agricultural AI Analyst Productization Result

审查与实现范围：`feature/commercial-product`，仅修改 Agent Workspace 前端产品层和已有 Copilot 上下文适配；没有修改 Agent Runtime、Planner、Tool Registry、Multimodal RAG、Retrieval、Model Router、Prompt、数据库或后端接口。

结论先行：Milestone 5.1.3 的前端产品化交付完成。`/farmap/analysis/a12-demo` 已从开发者式 Agent 控制台转换为以农业问题、业务进度、证据、发现、行动提案和人工确认组成的 Agricultural AI Analyst Workspace。真实 Agent Run 的读取、取消、批准、拒绝和 Operations task 回链均沿用已有 API；由于本地 Spring Boot 服务当前未启动，本次没有创建真实 Run，也没有执行真实写操作。生产开放写操作前，必须先处理后端审批幂等性风险。

## 1. Reality Audit

### 页面与入口

- Demo 路由：`/farmap/analysis/a12-demo`。
- Real 路由：`/farmap/analysis/:runId`，只有 `VITE_AGENT_MODE=real` 且 runId 不是 `a12-demo` 时进入真实运行同步。
- 页面入口：`farmap-frontend-dev/src/views/analysis/index.tsx`。
- Demo 运行截图在浏览器中以 1366×768 实际打开并验证；当前页面纵向可滚动，主内容没有水平溢出。

### 修改前观察到的结构

原页面由技术化 Header、左侧任务上下文、中间任务目标和 8 个技术步骤、右侧分析结论/证据预览/数据视图/运行记录、工具清单和 Demo Run 记录组成。默认可见的内容包括 Tool、Step、工具计数、技术运行记录和任务创建语义，用户需要从 Trace 或工具名称推断农业结论。页面没有完整的 Findings、Proposal ≠ Task、Reject、Cancel、Failure 和 Operations 回链体验。

### 已确认的真实 Agent 能力

后端已有以下接口与状态：

| 能力 | 真实接口/状态 | 本阶段处理 |
| --- | --- | --- |
| 创建 Run | `POST /api/agent/runs` | 保留，Real 模式创建后跳转真实 run 路由 |
| 读取 Run | `GET /api/agent/runs/{runId}` | 保留，前端继续轮询 |
| 事件回放 | `GET /api/agent/runs/{runId}/events`，SSE | 后端保留；本页面继续使用现有 polling service，未伪造 SSE 状态 |
| 执行 | `POST /api/agent/runs/{runId}/execute` | 沿用已有 service，页面不新增执行语义 |
| 批准 | `POST /api/agent/runs/{runId}/actions/{actionId}/approve` | 真实按钮只在 `WAITING_FOR_APPROVAL` 显示 |
| 拒绝 | `POST /api/agent/runs/{runId}/actions/{actionId}/reject` | 真实按钮只在 `WAITING_FOR_APPROVAL` 显示 |
| 取消 | `POST /api/agent/runs/{runId}/cancel` | 真实运行显示取消按钮，结果以 API 返回为准 |
| 状态 | `CREATED / PLANNING / WAITING_FOR_APPROVAL / RUNNING / COMPLETED / FAILED / CANCELLED` | 映射为用户语义，不直接展示枚举 |

### 技术信息污染点

修改前默认展示的 `toolName`、工具数量、运行记录、Step 语义、技术标签和开发者视图会把用户注意力带向 Agent 调试，而不是“AI 正在分析什么、依据是什么、建议做什么”。修改后这些信息保留在折叠的 Technical Details 中。

### 可复用真实工作流

`AgentRun`、`AgentAction`、`AgentEvidence`、`AgentPlan`、`AgentToolCall`、`AgentEvent`、轮询 service、approve/reject/cancel service、Agent→Task Tool→Operations 的已有链路均被保留。Diagnosis 页面提供的 field、diagnosis、image 和业务上下文继续通过 `toAgentContext` 传入 Run。

### 当前无法在本阶段真实验证的工作流

本地 `localhost:8080` 后端连接被拒绝，因此无法安全地启动真实 Run、进入真实 `WAITING_FOR_APPROVAL`、批准写入、创建 Operations task 或在刷新后检查持久化结果。没有用 Demo 状态冒充这些结果，也没有执行会创建任务的脚本。

## 2. Product Problems Found

### P0：商业写操作安全门禁

1. 后端 `ApprovalManager.approve` 只按 `actionId` 设置批准，没有看到对当前 Run 状态、Action 当前审批状态或重复请求的幂等保护。`AgentRuntime.approve` 随后直接执行 `TaskTool`；重复调用可能再次创建 Operations task。这是生产开放真实写操作前的阻断项，本阶段按约束只记录，不修改后端。

### P1：影响商业体验的问题

1. Real 页面仍使用已有 GET polling，后端虽有 SSE replay endpoint，但前端没有在本阶段改成事件流，因此长任务的即时性取决于轮询间隔。
2. 本地后端未启动，Real Run 的端到端批准、拒绝、取消、刷新恢复和 task 回链缺少本机实测证据。
3. Reject API 当前不接收拒绝原因，页面只执行真实拒绝动作，不自行增加未被后端支持的 reason contract。
4. 真实 Run 如果没有返回 structured findings，前端只能把真实 `output.conclusion` 或 Diagnosis summary 映射为 Finding，粒度低于完整的结构化农业结论。
5. `OperationsTaskService` 目前在无数据库时存在进程内 fallback；其跨进程持久性不属于本阶段 UI 可以补偿的能力。

### P2：后续优化问题

1. 可在真实 SSE 可用后补充事件时间线和步骤耗时，但仍应保持业务语言。
2. 可为 Real Evidence 增加图片、地图范围和原始来源的可视化预览，前提是后端返回真实 preview。
3. 可增加“分析任务历史”入口，显示真实 Run 列表和状态；当前页面已删除原来的假历史按钮。
4. 可补充多 Action 审批队列和每个 Action 独立的执行结果。

## 3. Agent / Approval / Operations Truth Audit

### Agent 状态与 Action

- `WAITING_FOR_APPROVAL` 由后端在遇到非只读步骤时真实产生，并附带 `actionId`、`action.type`、`approvalState=REQUIRED`。
- 前端 `adapter.ts` 将 `AgentRun` 映射为 `AgentWorkspaceViewModel`，UI 不自行创建 Action、Evidence 或 taskId。
- Demo Action 明确标记 `UNAVAILABLE`，只展示产品流程示例，不可批准、不创建任务。

### Approval Truth Audit

1. 真实 API：`POST /api/agent/runs/{runId}/actions/{actionId}/approve` 和同路径的 `reject`。
2. 可批准的对象：Run 内 `approvalState=REQUIRED` 的 `operations_task` 等真实 ProposedAction。
3. approve 后端行为：`ApprovalManager` 设置 Action 和 Run 为 APPROVED；`AgentRuntime` 使用 `approvalGranted=true` 调用 `TaskTool`。
4. 是否创建 task：Task Tool 成功时，`OperationsTaskService.createTask` 返回 `taskId`，写入 `run.output.task`；不是每一次批准都能保证成功，失败会返回 Run FAILED。
5. API 返回：完整 `AgentRun`，包含新的 status、actions、output、metadata、toolCalls 和 traces。
6. UI 成功确认：只在 approve API 返回的 Run 中读到 `approvalState`、`output.task.taskId` 后显示“已批准”和“任务已创建”；没有本地先行切换为成功。
7. reject 后端行为：Action 标记 REJECTED；所有 Action 都不再 REQUIRED 时 Run 完成；页面显示已拒绝结果，不继续显示等待审批。
8. 是否支持 reason：当前 Controller 与前端 service 都没有 reason 参数，页面没有伪造原因字段。
9. 是否可能重复 approve：从当前 `ApprovalManager` 和 `AgentRuntime` 实现看，未见幂等门禁；重复 approve 的重复写入风险列为 P0。
10. 刷新后是否保持：Run repository 会保存 Run；但本次后端未运行，未对真实数据库/内存 repository 做刷新复核，不能宣称已完成现场验证。

### Operations Truth Audit

批准后的任务由 `OperationsTaskService` 生成，包含 `taskId`、`diagnosisId`、`agentRunId`、`fieldId`、`evidenceIds`、`approvedBy`、`approvedAt`、status 和 priority。UI 只有收到真实 `output.task.taskId` 时才显示“查看 Operations”链接；Demo 和没有 taskId 的 Real Run 不显示虚假任务入口。

## 4. Final Product IA

最终用户路径为：

```text
Diagnosis
  ↓ 传入 field / diagnosis / images / goal
Agricultural AI Analyst Header
  ↓
Analysis Progress
  ↓
Current Findings
  ↓
Evidence Used
  ↓
Proposed Actions（Proposal ≠ Executed Task）
  ↓
Human Approval（仅真实 REQUIRED Action）
  ↓
Execution Result / Operations（仅真实 taskId）
  ↓
Contextual Copilot / Technical Details
```

桌面布局采用左侧 Context、中间业务分析主列、右侧审批与执行列。用户的主阅读路径是 Goal → Progress → Findings → Evidence → Proposal → Approval；开发调试信息放在底部折叠面板。

## 5. ViewModel / Data Contract

新增 `src/types/agent-workspace.ts`，建立：

- `AgentWorkspaceMode`：`REAL | DEMO | STALE | UNAVAILABLE | EMPTY`。
- `AgentWorkspaceStatus`：`READY | ANALYZING | WAITING_APPROVAL | COMPLETED | FAILED | CANCELLED`。
- `AgentWorkspaceViewModel`：farm、field、map、weather、dataSources、goal、diagnosis、status、steps、findings、evidence、actions、approval、execution、technical、failure、freshness 和交互能力标志。
- `AgentWorkspaceFallback`：仅承接页面启动时已有的 AIContext 和 Demo fallback，不是新的后端 DTO。
- `AgentWorkspaceRuntimeState`：只用于 Demo 展示计时器，不进入 Real API。

新增 `src/features/agent-workspace/adapter.ts`：

```text
Demo fixtures / AgentRun
        ↓
agent-workspace adapter
        ↓
AgentWorkspaceViewModel
        ↓
Business Panels
```

UI 不再直接读取 `demoAgentSteps`、`demoEvidence`、`demoRecommendations` 或 inline task/trend 数据。Demo fixture 只在 adapter 内包装成清晰的 DEMO ViewModel。

## 6. Files Changed

本 Milestone 的相关修改：

- `farmap-frontend-dev/src/views/analysis/index.tsx`：重建 Agent Workspace 页面编排，接入真实 service、ViewModel、Copilot context、取消、批准、拒绝和 Operations 链接。
- `farmap-frontend-dev/src/types/agent-workspace.ts`：新增 Agent Workspace 业务类型边界。
- `farmap-frontend-dev/src/features/agent-workspace/adapter.ts`：新增 Demo/Real 数据适配层和状态语义映射。
- `farmap-frontend-dev/src/features/agent-workspace/components/BusinessPanels.tsx`：新增 Header、Progress、Findings、Evidence、Proposal、Approval、Execution、Technical Details 组件。
- `farmap-frontend-dev/index.css`：新增 Agent Analyst 的业务布局、状态、卡片和响应式样式；保留旧样式以避免其他页面回归。
- `farmap-frontend-dev/src/features/overview/copilot/context.ts`：增加 `AGENT` Copilot 上下文类型和 Agent 分析草稿。
- `farmap-frontend-dev/src/components/copilot/CopilotDrawer.tsx`：显示 AI Analyst 页面标签与 Agent 进度、发现、行动、审批摘要。
- `docs/agent-workspace-productization-result.md`：本结果报告。

没有修改后端 Java、数据库 schema、Agent Runtime、Planner、Tool Registry、RAG 或 Operations API。

## 7. Component Architecture

```text
AgentWorkspace
├── AgentWorkspaceHeader
├── Context Column
│   ├── MapContainer
│   ├── FieldContextCard
│   ├── Diagnosis source
│   └── Agricultural data sources
├── Analysis Column
│   ├── AnalysisProgress
│   ├── FindingsPanel
│   ├── EvidenceUsedPanel
│   └── ActionProposalPanel
└── Decision Column
    ├── ApprovalPanel
    ├── ExecutionResultPanel
    ├── Contextual Copilot entry
    └── TechnicalDetailsPanel
```

子组件只接收 ViewModel 和回调，不直接请求 API。API 调用集中在页面编排层，符合现有 service architecture。

## 8. Analysis Progress UX

技术步骤被映射为业务步骤：理解分析目标、读取地块上下文、检查农情数据、检索农业证据、形成证据判断、准备处理建议、准备任务审批、专家复核入口。

- Demo 运行用计时器展示步骤变化，但第 7、8 阶段在完成时明确标为“未执行”，避免把写入和专家反馈说成已经发生。
- Real 运行由 Run 返回的 Step Status 驱动，不展示百分比，不伪造工具成功。
- Tool 名称只作为 ViewModel 的 technicalTool 留在 Technical Details，业务层展示“检查监控图像”“检索农业证据”等。

## 9. Findings & Evidence

`FindingsPanel` 直接消费 Real Run 的 `output.findings`；如果后端没有结构化 findings，则只映射真实 `output.conclusion` 或 Diagnosis context，不在前端推断新的农业结论。

`EvidenceUsedPanel` 汇总 Agent Run 的真实 Evidence，保留 modality、source、observedAt、summary、score 和 technicalId。Evidence category 使用与 Diagnosis 相同的业务语义：监控图像、气象数据、土壤数据、农业知识、历史案例、空间证据。

Demo 中历史案例明确显示“历史案例（演示）”。Real 中没有历史案例时不会生成成功记录；只展示后端实际返回的 Evidence。检索 score 默认降到 Evidence 展开内容中，不污染首屏业务信息。

## 10. Proposed Actions

`ActionProposalPanel` 明确展示：行动标题、行动说明、地块范围、已关联 Evidence 数量和审批状态，并在标题中声明 `Proposal ≠ Executed Task`。

- Demo Action 只作为流程示例，显示“演示不可执行”。
- Real Action 由 `run.actions` 映射，保留 actionId、type、approvalRequired、approvalState、payload evidenceIds 和 taskId。
- 没有真实 taskId 时不显示已创建任务，也不把“建议”写成“执行结果”。

## 11. Human Approval

真实 Run 只有在 status 为 `WAITING_FOR_APPROVAL` 且存在 `approvalState=REQUIRED` Action 时显示“批准并创建任务”和“拒绝建议”。按钮调用已有 approve/reject service，成功后等待后端返回新的完整 Run 再刷新 ViewModel。

Demo 永远不能批准；页面显示“演示模式不执行写操作”。Real 没有 pending Action 时显示当前无需人工确认，不提供虚假按钮。Approve 文案之所以包含“创建任务”，是因为已检查后端路径确认批准后确实调用 Task Tool；真实失败仍会进入失败状态。

## 12. Execution / Operations Link

`ExecutionResultPanel` 根据真实 Run 输出区分：尚未提出行动、等待批准后创建、已形成行动建议、任务已创建、创建失败和已取消。

只有 `output.task.taskId` 存在时才显示 Task 标识和“查看 Operations”，目标路径为 `/operations/tasks`。这条回链使用真实 `taskId`，没有根据行动标题或前端计时器猜测任务存在。

## 13. Failure / Cancel UX

- Real `FAILED` 映射为“分析失败”，显示后端 `failureCode/failureMessage`，提供重新发起分析入口。
- Real `CANCELLED` 映射为“已取消”，执行结果明确说明没有继续创建任务。
- Real 运行状态允许调用已有 cancel API；页面不会先把状态改成已取消，只有 API 返回后更新。
- Agent Runtime 不可用时显示“Agent Runtime 暂不可用”和重新发起/返回诊断操作。
- Demo 没有伪造失败、取消或真实工具异常；这些状态需要安全的真实 Run 或后端测试数据才能验证。

## 14. Contextual Copilot

新增 `AGENT` 上下文，包含 farm、field、crop、goal、status、current finding、proposed action、approval state、Evidence category 和 freshness。打开 Copilot 后，用户可以询问：

- 为什么 AI 提出这个行动？
- 当前有哪些证据和不确定性？
- 这个建议是否需要人工确认？

Copilot 只解释当前分析，不创建 task、不批准 Action、不绕过页面审批。抽屉中明确显示“AI Analyst”和当前 Agent 状态，Demo 上下文也明确标识。

## 15. Technical Details Downgrade

Technical Details 采用原生折叠面板，默认关闭。展开后仍保留 runId、raw status、Plan、Tool calls、Trace events 和 Demo mode note，满足开发调试和专家审计需要；首屏不再出现 Planner、Tool 计数、JSON、Payload 或 Run record。

## 16. DEMO / REAL State Integrity

| 场景 | 页面表现 | 写操作 |
| --- | --- | --- |
| `/analysis/a12-demo` | `DEMO AGENT`、演示计时步骤、历史案例（演示） | 禁止批准，禁止创建任务 |
| `/analysis/:runId` + `VITE_AGENT_MODE=real` | `REAL AGENT RUN`，状态和步骤来自 API | 只通过真实 approve/reject/cancel API |
| Real API 不可用 | Agent Runtime 暂不可用 | 不创建任务，不显示完成 |
| Demo 完成 | 第 7/8 步显示未执行，执行结果显示不可执行 | 不声称已写入或反馈 |

## 17. Interaction Audit

已在实际浏览器中验证：

1. 进入 `/farmap/analysis/a12-demo`，首屏能看到农场、地块、农业问题、目标、状态和 DEMO 标识。
2. 点击“开始分析”，业务步骤按计时器推进，约 3 秒出现 Findings 和 Evidence，约 8 秒出现 Proposed Actions。
3. Demo 完成后，行动卡显示 Proposal ≠ Executed Task，审批列明确不可执行。
4. 点击“Ask AI 解释当前分析”，Copilot 显示 AI Analyst、A-12、诊断、进度、发现、行动提案和 Evidence 摘要。
5. 展开 Technical Details，能看到 Demo mode note、runId/raw status/tool calls/trace events；默认状态保持折叠。
6. 真实批准、拒绝、取消按钮的渲染条件来自真实 Run status 和 action state，未在 Demo 中触发写 API。

## 18. Responsive Validation

浏览器实测结果：

| Viewport | `innerWidth` | `scrollWidth` | 水平溢出 | 观察 |
| --- | ---: | ---: | --- | --- |
| 1366×768 | 1366 | 1351 | 无 | Context / Analysis / Decision 三列，页面纵向滚动 |
| 1024×768 | 1024 | 1009 | 无 | 响应式布局收窄为可滚动业务列，Evidence、Proposal、Approval 可访问 |

此外 CSS 增加了 1320px、1100px 和 650px 断点；1440×900、1920×1080 使用同一桌面布局规则，未发现会引入水平溢出的固定宽度模块。截图实测聚焦于 1366 和 1024；未声称对 1440、1920 做独立浏览器截图。

## 19. Technical Validation

已执行：

- `pnpm exec tsc --noEmit`：通过。
- Agent Workspace、Agent 页面、Agent 类型和 Copilot 文件定向 ESLint：通过。
- `pnpm build`：通过，Vite 生产构建完成。
- `git diff --check`：通过；仅有 Git 关于 LF/CRLF 的提示，没有 whitespace error。
- Demo Agent 浏览器运行：通过。

未执行真实写入验证：本地 `localhost:8080` 未启动，避免为了测试创建危险或不可逆 Operations task。完整仓库 ESLint 的历史基线仍包含其他页面的既有错误，本 Milestone 没有扩大该范围。

## 20. Screenshots

截图保存在本机临时目录，均由实际浏览器生成：

1. Agent Workspace 首屏：`C:/Users/Administrator/AppData/Local/Temp/farmap-agent-final-idle-1366.png`
2. Analysis Running：`C:/Users/Administrator/AppData/Local/Temp/farmap-agent-progress-1366.png`
3. Proposed Action / Approval：`C:/Users/Administrator/AppData/Local/Temp/farmap-agent-result-1366.png`
4. Completed Demo：同上，页面显示完成但明确未执行写入。
5. Technical Details：`C:/Users/Administrator/AppData/Local/Temp/farmap-agent-technical-1366.png`
6. Copilot Agent Context：`C:/Users/Administrator/AppData/Local/Temp/farmap-agent-copilot-1366.png`
7. 1024px：`C:/Users/Administrator/AppData/Local/Temp/farmap-agent-final-1024.png`

没有生成 Real Approval 或 Real Execution 截图，因为后端服务未启动，不能用 Demo 或伪造数据代替真实审批结果。

## 21. Commercial UX Review

评分以当前 Milestone 的前端业务体验为对象，Real 后端未启动和 P0 幂等问题计入限制：

| 维度 | 评分 / 10 | 评价 |
| --- | ---: | --- |
| Analysis clarity | 9 | Header 直接表达农场、地块、问题和目标 |
| Progress clarity | 9 | 技术步骤已转为农业业务阶段 |
| Evidence trust | 8 | 保留来源、时间和历史案例演示边界；Real Evidence 需实测 |
| Action clarity | 8 | Proposal 与 Task 分离，范围和证据数量可见 |
| Approval clarity | 8 | 真实 pending Action 才显示批准/拒绝，Demo 不可操作 |
| Execution visibility | 7 | taskId 回链清晰，但未能在本地真实运行验证 |
| Failure UX | 7 | 已覆盖 FAILED/CANCELLED/Runtime unavailable，工具级降级需真实数据 |
| Workflow integration | 8 | Diagnosis → Agent → Operations 语义链已经接通 |
| Information hierarchy | 9 | Business View 首屏优先，Technical Details 折叠 |
| Commercial product feel | 9 | 从开发控制台转为农业 AI Analyst 工作区 |
| **综合** | **84 / 100** | 前端产品化达到商业展示和评审标准；生产写操作仍受后端门禁约束 |

## 22. Remaining Limitations

### Frontend

- Real 运行目前复用已有 polling，尚未把后端 SSE replay 接入页面。
- 后端没有 structured findings 时，页面只能展示真实 conclusion/diagnosis 映射。
- 页面没有为 reject 增加原因输入，因为现有 API 不支持 reason。
- 独立的历史 Run 列表、事件耗时、Evidence 图片预览属于后续体验增强。

### Backend

- ApprovalManager 未显示对重复批准、已拒绝 Action、已完成 Run 的幂等校验。
- 本地后端未启动，Real Run、真实 task 创建、刷新恢复和 DB persistence 没有本轮端到端证据。
- Operations 无数据库时的进程内 fallback 不适合作为多实例生产持久化。

### Agent

- 当前 Agent Runtime 的结构化 findings/recommendations 颗粒度仍有限，页面只能忠实呈现已有输出。
- Tool failure 与 evidence empty 的更细粒度业务解释依赖后端返回稳定 error/evidence availability contract。

### Data

- Demo 数据仍用于产品演示，但所有 Demo Evidence、历史案例和行动都在 UI 上标记了演示边界。
- Real 数据源数量由 Run Evidence 返回决定；没有证据时不显示“已使用”的假数据。

### Workflow

- 真实批准后的 Operations 任务仍需要在后端启动并完成安全验证，前端没有通过本地状态替代这一步。
- Agent→Diagnosis 的来源卡沿用真实 context；如果后端没有 diagnosis id，只显示可用的 context，不生成虚假关联。

## 23. Go / No-Go

### Milestone 5.1.3 结论：DONE（前端产品化范围）

以下交付已完成：

- Agent Runtime 已被包装为 Agricultural AI Analyst Workspace。
- Goal、Progress、Findings、Evidence、Proposal、Approval、Execution、Failure/Cancel、Copilot 和 Technical Details 形成完整产品信息架构。
- Demo 与 Real 边界清晰，未伪造真实 Evidence、Approval、Task 或 Progress。
- 真实 approve/reject/cancel API 和 Operations taskId 回链已接入前端业务层。
- TypeScript、定向 ESLint、生产构建、git diff check、Demo 运行和 1366/1024 响应式验证通过。

### 生产写操作发布门禁：NO-GO，直到 P0 解决

后端重复批准幂等性必须先由后端团队修复并验证；这不属于本阶段允许修改的范围。Real Run 端到端验证也需要在安全测试数据上完成。当前页面可以用于商业演示、论文系统展示和无写入 Demo，但不应把本地 Demo 截图解释成真实 Operations 执行证明。

下一阶段只推荐：**Milestone 5.1.4 — Operations Center Productization**。进入下一阶段前应先保留本报告中的 P0 作为发布门禁，并完成安全的真实 Approval/Operations 验证。
