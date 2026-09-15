# Milestone 5.1.4 Operations Center Productization Result

当前分支：`feature/commercial-product`。本阶段只修改 Operations 前端产品层，没有修改 Backend、Database、Agent Runtime、ApprovalManager、Planner、Tool Registry、RAG、Model Router 或新增 Operations API。Milestone 5.1.3 发现的 Approval 幂等 P0 继续作为 Production Release Gate。

结论：只读 Operations Center 产品化完成。原有“操作指导 + Agent 任务 List + 外部专家 iframe”已重组为 Agricultural Execution Workspace，支持 Demo/Real 边界、任务队列、任务详情、来源、地块、状态、优先级、Agent/Diagnosis/Evidence 真实回链和只读 Copilot。后端未启动，因此没有创建真实任务，也没有用 Demo 数据冒充真实 task。

## 1. Reality Audit

- 主路由：`/farmap/operations/tasks`，`/farmap/operations` 同样进入 Operations 页面。
- `/farmap/operations/recommendations` 仍是既有占位入口，不伪造推荐生命周期。
- 正式入口：`src/views/operations/index.tsx`；Demo 入口：`src/views/operations/DemoOperationsCenterPage.tsx`。
- Demo 路由原先是占位页；正式页面原先是 GuidanceBox、Agent 任务 List 和外部专家 iframe，没有运营态势、任务详情、Traceability、任务异常和执行边界。
- 唯一真实 Operations API：`GET /api/operations/tasks`，返回 `Result<{ items, count }>`。
- 真实字段：`taskId/title/fieldId/diagnosisId/agentRunId/status/priority/source/createdFrom/createdBy/evidenceIds/approvedBy/approvedAt/createdAt/goal`。
- `OperationsTaskService` 当前只写入 `status=OPEN`；没有 start、complete、cancel、assign、update API，也没有 assignee、deadline、progress、result、operator note 或 task-level feedback 字段。
- Agent 关系真实存在：批准后的 TaskTool 调用 `OperationsTaskService.createTask`，并可携带 `taskId/agentRunId/diagnosisId/evidenceIds`。
- Expert Review 存在 diagnosis-level `/api/expert-review` API，但没有 Task → Feedback 直接关系。
- 本地 Spring Boot 服务当前未启动，因此没有启动真实 Run、写入任务或现场验证持久化。

## 2. Operations Truth Audit

| 问题 | 真实结论 |
| --- | --- |
| API | 只有 `GET /api/operations/tasks` |
| 状态 | `OPEN`；未知值显示“状态未知” |
| Agent task | `source=agent` 或存在 `agentRunId` 时映射为 AI 分析建议 |
| taskId | 批准后的 TaskTool → OperationsTaskService 生成 |
| diagnosisId / agentRunId | contract 可携带，页面只使用真实 ID |
| evidenceIds | contract 可携带，支持数组或数据库 JSON 字符串；页面只显示数量 |
| Assignee / Deadline / Progress | 不存在，不展示 |
| Execution Result | 不存在，不显示已完成或异常 |
| Complete / Assign / Cancel API | 不存在 |
| Feedback | 只有 diagnosis-level review，没有 task-level link |
| Refresh / persistence | DB 代码存在；本轮后端未启动，未现场验证 |
| no-DB fallback | 进程内 ConcurrentHashMap，不适合多实例生产 |

可安全产品化的字段是标题、地块、优先级、OPEN 状态、来源、创建/批准时间、goal、Evidence 数量和真实回链 ID。负责人、截止时间、进度、执行结果、异常、现场证据和任务反馈均不能前端补造。

## 3. Product Problems Found

### P0

`ApprovalManager` 对重复 approve 缺少明确幂等保护，重复调用可能再次执行 TaskTool 并创建重复 Operations task。本阶段不修改它；生产写操作仍为 **NO-GO**。

### P1

1. 没有 task detail API，详情只能使用列表返回字段。
2. 后端只有 OPEN，无法真实展示执行中、完成、异常或逾期。
3. Agent/Diagnosis/Evidence 回链依赖真实 ID 是否写入。
4. 本地后端未运行，真实 AI task、数据库刷新和任务回链没有现场证据。
5. Diagnosis-level Expert Review 尚未关联到 Operations task。

### P2

1. 后续可增加安全的 task detail、状态转换、负责人、deadline、result 和 task feedback contract。
2. 可增加真实筛选、分页和 Evidence detail route。
3. 可统一 Guidance、Expert 和 task feedback 工作区。

## 4. Final Operations IA

```text
Operations Center
├── Header：农场 / Demo-Real / freshness
├── Operations Overview：待处理 / 执行中 / 逾期
├── Operations Queue：priority / task / field / source / status / time
├── Selected Task Workspace：why created / traceability / execution boundary / Copilot
├── Existing Guidance / Expert support
└── Read-only boundary
```

主路径是“当前运营态势 → 任务队列 → 任务为什么存在 → 真实来源回链”。没有真实生命周期字段的区域不使用假卡片填充。

## 5. Data Contract / ViewModel

新增 `src/types/operations.ts`：

- `OperationsMode`：`REAL | DEMO | STALE | EMPTY | UNAVAILABLE`。
- `OperationsTaskStatus`：`OPEN | UNKNOWN`。
- `OperationsPriority`、`OperationsTaskSource` 和 `OperationsTaskTraceability`。
- `OperationsTaskViewModel`：标题、地块、状态、优先级、来源、时间、goal 和 traceability。
- `OperationsViewModel`：mode、farm、tasks、selectedTask、summary、freshness、read-only capabilities 和 unavailableReason。

新增 `src/features/operations/adapter.ts`：

```text
Operations API / existing demo overview tasks
        ↓
operations adapter
        ↓
OperationsViewModel
        ↓
OperationsCenter
```

Adapter 解析 `evidenceIds`、映射 `agent` 来源、区分 Demo `PENDING` 和 Real `OPEN`，并集中计算只读汇总。

## 6. Files Changed

- `src/types/operations.ts`
- `src/features/operations/adapter.ts`
- `src/features/operations/components/OperationsCenter.tsx`
- `src/views/operations/index.tsx`
- `src/views/operations/DemoOperationsCenterPage.tsx`
- `src/routes/routes.tsx`
- `index.css`
- `docs/operations-center-productization-result.md`

未修改 Backend、Database、ApprovalManager、Agent Runtime、Operations Controller 或现有 API。

## 7. Component Architecture

```text
Operations page
├── operations adapter
├── OperationsHeader
├── OperationsOverview
├── OperationsQueue / TaskQueueItem
├── OperationsTaskDetail
└── Existing GuidanceBox / Expert support
```

子组件只接收 ViewModel 和回调；API 读取、Copilot 上下文和路由跳转集中在页面层。没有新增状态变更 service。

## 8. Operations Overview

Header 展示农场、Demo/Real、更新时间和“只展示已有真实任务状态”的边界。待处理来自 OPEN；执行中、逾期在后端没有字段时显示 `—`；服务不可用时同样显示 `—`，不显示“暂无任务”。

## 9. Task Queue

队列采用 compact master-detail list，显示优先级、任务标题、Field ID、来源、真实状态和创建时间。点击任务只改变 selected task，不发写请求。

来源语义：`agent/task_tool/ai` → AI 分析建议；`expert` → 专家复核；`manual/user` → 人工创建；`seasonal/guidance` → 季节性计划；`monitor/alert` → 监测告警；其他 → 来源未关联。Demo 明确显示“演示任务 · 未关联真实来源”。

## 10. Task Detail

详情展示标题、Demo/Real、优先级、状态、来源、执行地块、为什么创建、来源与追踪、Evidence 数量、批准时间和执行结果边界。缺少 `goal` 时如实说明后端没有返回创建原因；不使用标题、地块名称或文本相似度猜关联。

## 11. AI / Diagnosis / Evidence Traceability

真实 `diagnosisId` 可进入 `/diagnosis/{diagnosisId}`；真实 `agentRunId` 可进入 `/analysis/{agentRunId}`；`evidenceIds` 只显示真实关联数量。当前没有 Evidence detail route，因此不把 ID 猜成图像、天气或知识来源。Demo task 没有真实 Diagnosis、Agent 或 Evidence 关系。

## 12. Available Actions Truth Audit

页面没有“开始执行”“完成任务”“取消任务”“重新分配”“编辑任务”按钮，因为后端没有对应 API。底部明确提示：不会从此页面创建、批准、开始、完成、取消或重新分配任务。Operations 不重新触发 Agent Approval，也不重复执行 Action。

## 13. Contextual Copilot

任务详情的 Ask AI 使用已有 `OPERATION` context，传递 farm、field、task title、status、source、taskId（若存在）和 freshness。Copilot 只解释任务为什么存在、状态和来源如何理解、还缺少什么信息；不会创建、审批、完成或修改任务。Demo 上下文明确标识为演示。

## 14. DEMO / REAL / STALE / EMPTY / UNAVAILABLE

| 状态 | 页面表现 | 数据边界 |
| --- | --- | --- |
| DEMO | `DEMO TASKS` / `DEMO TASK` | 只读，不请求或写入真实任务 |
| REAL | `REAL OPERATIONS` | 只展示 API 返回事实 |
| EMPTY | `EMPTY · 当前没有真实任务` | 成功读取但 items 为空 |
| UNAVAILABLE | `任务服务暂不可用` | 不显示“暂无任务” |
| STALE | 预留 mode/freshness | API 无独立 freshness，未强行制造 |

## 15. Technical Information Downgrade

默认业务界面不显示 raw enum、JSON、Agent Trace 或 ID 列表。真实 taskId、diagnosisId、agentRunId 只出现在来源与追踪区域，用于审计回链；后端没有的字段不展示。

## 16. Interaction Audit

Demo 浏览器已验证：

1. `/farmap/operations/tasks` 不再是占位页，而是农业运营中心。
2. 首屏显示 Demo、当前农场、4 项待处理；执行中和逾期显示为 `—`，因为后端没有这两个字段。
3. A-12、B-03、B-04 和设备任务显示真实存在的演示字段，并标记演示未关联。
4. 点击 B-04，详情标题、Field、来源、状态同步切换。
5. Ask AI 打开 Copilot，显示 Operations、农场、地块、任务状态、来源和演示上下文。
6. 没有调用 approve、TaskTool 或新增写 API。

## 17. Responsive Validation

| Viewport | innerWidth | scrollWidth | 水平溢出 |
| --- | ---: | ---: | --- |
| 1366×768 | 1366 | 1351 | 无 |
| 1024×768 | 1024 | 1009 | 无 |

1366 为 Queue + Detail；1024 切换为上下布局，队列和详情仍可访问。1440×900、1920×1080 使用同一桌面规则，本次没有独立截图，不宣称逐像素审查。

## 18. Technical Validation

已执行并通过：

- `pnpm exec tsc -b --pretty false`
- Operations 相关文件定向 ESLint
- `pnpm build`
- `git diff --check`
- Demo Operations 浏览器运行
- 1366/1024 响应式测量

Vite 构建只有既有的大 chunk warning，没有构建失败。本阶段没有启动后端，也没有执行真实写任务；这符合 P0 安全门禁。

## 19. Screenshots

实际浏览器生成：

1. Operations Center 首屏：`C:/Users/Administrator/AppData/Local/Temp/farmap-operations-center-1366.png`
2. Task selection / detail：`C:/Users/Administrator/AppData/Local/Temp/farmap-operations-task-detail-1366.png`
3. Demo state：以上截图均带 `DEMO TASKS` / `DEMO TASK`。
4. 1024px 首屏：`C:/Users/Administrator/AppData/Local/Temp/farmap-operations-center-1024-top.png`

没有 AI-created task 的真实截图，因为本地后端未启动且本阶段禁止为了测试创建任务；真实 traceability 通过 contract 审查验证。

## 20. Commercial UX Review

| 维度 | 评分 / 10 | 评价 |
| --- | ---: | --- |
| Operations clarity | 9 | 农场与执行边界清楚 |
| Task priority clarity | 8 | 优先级有统一业务标签 |
| Status clarity | 8 | OPEN 映射为待处理，缺失状态不伪造 |
| Source clarity | 9 | 来源语义集中映射 |
| AI traceability | 8 | 真实 ID 可回 Agent/Diagnosis |
| Action integrity | 10 | 无假动作，不绕过 Approval P0 |
| Execution visibility | 6 | 后端没有 lifecycle/result |
| Information hierarchy | 9 | Queue → Detail → Traceability |
| Responsive quality | 8 | 1366/1024 无水平溢出 |
| Commercial product feel | 9 | 从 List 升级为 Execution Workspace |
| **综合** | **84 / 100** | 只读产品化达到商业展示标准，执行闭环受后端 contract 限制 |

## 21. Remaining Limitations

### Frontend

没有独立 task detail endpoint、真实筛选/分页和 Evidence detail route。

### Backend

只有 GET list 和 OPEN 状态；没有生命周期 API。ApprovalManager 重复批准幂等性仍是 P0；无 DB fallback 不适合多实例生产。

### Data

没有负责人、截止时间、进度、完成结果、现场证据和任务级反馈字段。

### Agent

Agent → Task 链路存在，但本轮没有真实 Run 可供现场验证；Operations 不承担 Agent 审批职责。

### Workflow

Diagnosis-level Expert Review 尚未与 Operations task 建立真实反馈回链；OPEN → 执行 → 完成 → 异常 → 反馈尚未成为可操作工作流。

### Infrastructure

本地 Spring Boot 未启动；真实 API、数据库持久化和多实例 fallback 未做本轮现场验证。

再次列出：**P0 Approval idempotency release gate** 仍未修复，生产写操作继续 NO-GO。

## 22. Go / No-Go

### Milestone 5.1.4：DONE（只读 Operations Center 产品化）

已完成 OperationsViewModel/adapter、运营态势、任务队列、详情、真实 traceability、Operations Copilot、Demo/Real/Empty/Unavailable 边界、只读安全门禁和 1366/1024 验证。没有伪造 Assignee、Deadline、Progress、Result、Feedback 或生命周期动作。

### 生产执行：NO-GO

原因：Approval 幂等 P0 未修复；Operations 没有 start/complete/cancel/assign/result API；本轮没有真实 task 写入和 DB 持久化现场证据。

下一项只推荐：**Milestone 5.1.5 — Knowledge & Expert Feedback Productization**。进入真实执行闭环前，应先把 P0 作为生产发布门禁处理。本阶段到此停止，不进入 Backend Hardening、Agent V2、RAG V2 或论文实验。
