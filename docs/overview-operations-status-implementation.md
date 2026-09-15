# Milestone 5.1.1 Step 5 Result

## 1 Reality Check

本阶段先检查了 Overview 的 ViewModel、适配层、Operations 页面和后端任务契约，再决定展示边界。

- `OverviewViewModel.operations` 目前只包含 `pendingApproval`、`pendingExecution`、`inProgress`、`overdue` 和 `tasks[]`。
- `OverviewOperationTask` 原有字段为 `id`、`name`、`time`、`status`、`priority`、`fieldId`，没有 `operationId`、负责人、进度、时间线、反馈或推荐关联 ID。
- `src/features/overview/adapter.ts` 使用本地 `demoTasks` 作为 Overview 的运营来源，当前是 4 条演示任务，全部为 `PENDING`。
- `/farmap/operations/tasks` 页面会请求真实 `/api/operations/tasks`，其任务对象包含 `taskId`、`status`、`priority`、`source`、`createdAt`、Evidence ID 和可选 `agentRunId`，但当前没有任务详情路由、负责人、执行进度、时间线或反馈面板。
- 后端 Operations 任务由已批准的 Agent 农事任务创建，和 Overview 的 `demoTasks` 不是同一数据集合。Step 5 没有修改后端或 API。

因此本阶段只能提供“执行态势展示壳”：展示已存在的任务摘要，明确演示数据和未接入字段，不把演示任务包装为真实执行事实。

## 2 Operations Data Audit

| 数据层 | 当前来源 | 可安全展示 | 当前不能展示 |
| --- | --- | --- | --- |
| Overview Demo | `demoTasks` → Overview adapter | 任务名称、优先级、计划时间、地块、面积、演示状态、更新时间 | 真实 operation ID、负责人、进度、执行时间线、完成证明 |
| Operations API | `/api/operations/tasks` | API 返回的任务 ID、状态、来源、创建时间和 Evidence ID（由 Operations 页面消费） | 在 Overview 中直接假定它与 Demo 任务或 AI Recommendation 一一对应 |
| Agent Runtime | 已批准 Agent 任务产生 Operations 任务 | 可在已有 Agent/Operations 页面继续追踪 | 在本阶段从 Overview 发起批准、启动或完成操作 |

适配层为演示任务补充了可选的展示元数据：`source`、`sourceLabel`、`fieldName`、`fieldArea`、`updatedAt`。这些字段用于说明上下文和新鲜度，不代表新增后端事实。

## 3 Recommendation → Operation relationship

当前 Recommendation 只有自身 `id`、风险/信号关联和主操作路径；`demoTasks` 没有 `recommendationId`、`taskId` 或 `operationId` 关联。后端真实任务的 `taskId` 也没有在当前 Overview adapter 中被加载。

因此 Operations Status 对演示任务显示“当前演示任务 · 未关联建议”，并在面板脚注中明确“未与 AI 建议建立可靠 ID 关系”。实现没有通过标题、地块或相似文本猜测关联，也没有创建虚假的 recommendation ID。Recommendation Center 继续表达 FarMap 的提议，Operations Status 只表达当前任务记录，两者保持语义分层。

## 4 Files Changed

- `farmap-frontend-dev/src/types/overview.ts`
  - 增加 `OverviewOperationSource`。
  - 为 `OverviewOperationTask` 增加可选展示元数据字段。
- `farmap-frontend-dev/src/features/overview/adapter.ts`
  - 为演示任务提供地块、面积、来源和更新时间。
  - 将 B-04 演示任务名称修正为“B-04 采收安排”，与已有物候建议保持一致。
- `farmap-frontend-dev/src/features/overview/components/OperationsStatus/index.tsx`
  - 新增运营执行态势面板、聚合指标、空态和数据边界提示。
- `farmap-frontend-dev/src/features/overview/components/OperationsStatus/OperationStatusItem.tsx`
  - 新增紧凑任务行，展示优先级、状态、地块、来源、更新时间和现有任务入口。
- `farmap-frontend-dev/src/views/overview/index.tsx`
  - 在 Recommendation Center 之后接入 Operations Status。
  - “查看任务”复用现有 `/operations/tasks` 路由，并在 A-12 场景保留已有 AI 上下文设置。
- `farmap-frontend-dev/index.css`
  - 增加 Operations Status 的桌面和移动端样式。
- `docs/overview-operations-status-implementation.md`
  - 本实施报告。

没有修改 Backend、Agent Runtime、Planner、Tool Registry、RAG、Milvus、GIS、Knowledge Graph、Redis、MQ、Spring Cloud 或新增路由。

## 5 Data Contract Changes

本次只做前端兼容扩展，原有必需字段和状态模型保持不变：

```ts
type OverviewOperationSource =
  | "AI_RECOMMENDATION"
  | "FIELD_MONITORING"
  | "SEASONAL_GUIDANCE"
  | "UNLINKED";

type OverviewOperationTask = {
  id: string;
  name: string;
  time: string;
  status: "PENDING" | "IN_PROGRESS" | "COMPLETED" | "OVERDUE";
  priority: OverviewPriority;
  fieldId?: string;
  source?: OverviewOperationSource;
  sourceLabel?: string;
  fieldName?: string;
  fieldArea?: number;
  updatedAt?: string;
};
```

没有增加或伪造 `operationId`、`assignee`、`progress`、`timeline`、`approvedAt` 或 `completedAt`。未来接入真实 Operations API 时，可在 adapter/service 层把 API 契约映射到同一展示组件。

## 6 Component Architecture

```text
Overview
 ├─ DecisionQueue
 ├─ FarmHealthCommand
 ├─ RiskIntelligenceBoard
 ├─ RecommendationCenter
 ├─ OperationsStatus
 │   ├─ status metrics
 │   └─ OperationStatusItem × 3
 └─ existing trend / weather / monitor / task cards
```

组件只接收 `OverviewOperations`、`OverviewMode` 和 `OverviewFreshness`，不直接读取 Demo 数据、Store 或 API。`Overview` 负责把任务点击转换为已有 Operations 路由导航，保持页面级上下文行为一致。

## 7 Product Behavior

- 面板标题为“运营执行态势”，位于 AI 建议之后，回答“建议之后发生了什么”。
- 默认按 `CRITICAL → HIGH → MEDIUM → LOW` 排序，仅展示 3 条关键任务，避免重新变成完整 CRUD 列表。
- 每条任务显示优先级、状态、任务名称、地块和面积、来源标签、更新时间，以及“查看任务”入口。
- DEMO 模式下状态明确显示为“演示 · 待处理”，脚注说明未连接实时执行状态和推荐关系。
- “查看任务”只进入既有 `/farmap/operations/tasks` 页面，没有在 Overview 发起批准、开始、完成、修改状态或写入操作。
- 现有 Decision Queue 继续代表“现在需要关注什么”，Recommendation Center 继续代表“FarMap 建议什么”，Operations Status 代表“任务记录处于什么状态”。

## 8 State Handling

| 模式 | 行为 |
| --- | --- |
| `DEMO` | 展示本地演示任务；状态加“演示”前缀；显示演示更新时间和未关联建议提示。 |
| `REAL` | 使用相同组件契约；不对缺失的负责人、进度或时间线做推断；状态按任务原值展示。 |
| `STALE` | 保留可见任务，同时显示“执行记录较旧”和核对更新时间提示。 |
| `UNAVAILABLE` | 不展示任务行，显示“执行数据暂不可用”，引导用户前往现有 Operations 页面。 |
| 空任务 | 显示“当前暂无已记录的农事行动”，说明任务记录产生后会在此汇总。 |

所有状态均为来源数据或明确的展示前缀，没有前端模拟执行进度，也没有状态变更控件。

## 9 Validation

- `pnpm exec tsc -b --pretty false`：通过。
- `pnpm exec eslint src/views/overview/index.tsx src/features/overview/adapter.ts src/features/overview/components/OperationsStatus src/types/overview.ts`：通过。
- `git diff --check`：通过；仅有 Windows 行尾转换提示。
- 1366×768 本地运行检查：Operations Status 可见，展示 3 条关键任务，原有 Decision Queue、Farm Health、Risk Intelligence、Recommendation Center 均保留。
- 交互检查：点击首条“查看任务”进入 `http://127.0.0.1:5173/farmap/operations/tasks`，返回后 Overview 正常恢复。
- 控制台检查：未发现新的应用异常；仍存在既有 `HydrateFallback` 提示以及 Ant Design `Card bordered`、`Drawer width` 弃用警告。
- 全量 `pnpm lint` 仍受仓库既有非 Overview 文件问题影响（此前基线为 47 errors、12 warnings），本阶段定向 lint 未复现这些问题。

## 10 Screenshot

- 默认 Overview：[`farmap-overview-step5-operations-default.png`](C:/Users/Administrator/AppData/Local/Temp/farmap-overview-step5-operations-default.png)
- Recommendation → Operations 上下文：[`farmap-overview-step5-operations-context.png`](C:/Users/Administrator/AppData/Local/Temp/farmap-overview-step5-operations-context.png)

上下文截图显示 Recommendation Center 下方的 Operations Status 面板、3 条任务行、来源未关联标签和数据边界脚注。

## 11 Commercial UX Review

| 维度 | 评价 |
| --- | ---: |
| 执行可见性 | 80/100 |
| 数据可信度表达 | 88/100 |
| Recommendation 与 Operation 分层 | 86/100 |
| 下一步可操作性 | 74/100 |
| 信息密度 | 84/100 |
| 商业产品感 | 82/100 |

本阶段达到“农业 AI Command Center 的基础执行态势层”标准：用户能看到建议后的任务记录，并知道哪些内容仍是演示数据。距离完整商业 Operations 产品仍缺真实状态、执行责任和结果反馈。

## 12 Remaining Issues

- Overview 尚未聚合真实 `/api/operations/tasks`，演示任务仍不能作为现场执行真相。
- Operations 页面尚无任务详情、负责人、进度、时间线、异常处理和专家反馈展示。
- Recommendation 与 Operation 仍缺后端提供的稳定关联字段，当前只能显示“来源建议未关联”。
- Overview 仍保留旧版“今日农事任务”卡片，后续可在商业 UI 收敛阶段移除重复表达，但本阶段保留以确保原有功能不丢失。
- 全量 lint 的既有仓库问题仍需单独治理。

## 13 Go / No-Go

**GO（进入后续产品化评估）**：Step 5 已建立诚实、可复用的 Operations Status 展示边界，适合继续做真实 Operations 数据接入设计。

进入真正的实时执行闭环前，必须先取得后端稳定的任务聚合契约，以及 recommendation → task 的可靠 ID 关系；在此之前不能在 Overview 增加批准、启动、完成或伪造进度操作。

本阶段已按要求停止，没有开始 Copilot V2、Agent V2、Backend、RAG、Knowledge Graph 或 Infrastructure 工作。
