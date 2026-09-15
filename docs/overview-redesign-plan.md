# FarMap Overview Redesign Plan

## 0. Scope and guardrails

本文件是 Milestone 5.1 Overview Phase 的实施计划，不是代码改动。当前阶段只完成结构审查、产品设计和接口规划，不修改前端页面、后端逻辑、数据库、Agent 或 RAG。

- 分支：`feature/commercial-product`
- 目标页面：`/overview`
- 目标形态：Agricultural AI Command Center
- 当前实现：Commercial Dashboard + Demo 数据
- 目标结果：以农场风险和待决策事项为中心的业务指挥台

## 1. Current Overview structure

当前页面标题为“农场智能总览”，页面结构按视觉区域分成六层：

1. **页面头部**：`SMART FARM / OVERVIEW`、农场智能总览标题和产品描述。
2. **指标行**：农场健康度、地块总数、种植面积、设备在线。
3. **主工作区**：左侧农场空间态势地图，右侧 FarMap Intelligence 风险列表和 Agent 入口。
4. **运营摘要行**：NDVI 长势趋势、天气预报、监控画面、今日农事任务。
5. **近期动态行**：最近 AI 诊断和农场动态。
6. **页脚状态**：农场名称、位置和数据更新时间。

当前页面更像“多个业务卡片组成的 Dashboard”，而不是按优先级组织的指挥台。用户能看到很多信息，但无法在首屏快速回答：

- 今天最应该先处理什么？
- 哪个风险影响最大？
- AI 建议是否需要我批准？
- 哪些任务已经在执行，哪些已经逾期？
- 当前信息是否为实时数据、演示数据，或者数据已经过期？

## 2. Current component tree

当前 `Overview` 组件的实际依赖关系如下：

```text
Overview
├─ page header
├─ MetricItem × 4
├─ main grid
│  ├─ Card: 农场空间态势
│  │  ├─ MapContainer
│  │  ├─ layer / marker / distance buttons
│  │  ├─ map legend
│  │  └─ selected field note + StatusBadge + diagnosis link
│  └─ Card: FarMap Intelligence
│     ├─ risk list × 3
│     ├─ Agent deep analysis CTA
│     └─ Copilot suggested questions
├─ bottom grid
│  ├─ NDVI sparkline card
│  ├─ weather forecast card
│  ├─ monitor preview card
│  └─ today's task list card
├─ lower grid
│  ├─ recent diagnosis list
│  └─ farm activity list
└─ farm name / location / updated-at footer
```

直接复用的共享组件：

- `MetricItem`：指标标签、数值、单位和趋势。
- `StatusBadge`：健康、关注、风险、运行、完成和失败状态。
- `MapContainer`：地图、作物数据、位置数据、指标模式和范围滑块。
- `FieldContextCard`：地块、作物、面积、健康度、NDVI、土壤湿度、降雨和物候。
- `EvidenceCard`：诊断证据卡，当前未被 Overview 直接使用。
- `RecommendationCard`：农事建议卡，当前未被 Overview 直接使用。
- `CopilotDrawer`：全局 Copilot 抽屉，由 Layout 挂载，Overview 只调用 `open()`。

## 3. Current data sources

### 3.1 Overview 当前实际数据

`Overview` 直接从 `@/demo` 引入以下静态数据：

| 数据 | 当前来源 | 当前用途 | 风险 |
|---|---|---|---|
| 农场名称、健康度、面积、设备数、坐标 | `demoFarm` | 顶部指标、地图、页脚 | 无请求、无数据新鲜度和权限来源 |
| 地块、健康度、NDVI、土壤湿度、物候 | `demoFields` / `demoPrimaryField` | 地图选中信息、近期诊断 | 固定 A-12 叙事，不能反映用户所选农场 |
| 地图作物点和边界 | `demoMapCrops` / `demoFarmLocations` | `MapContainer` | 只适合演示，缺少加载、空数据和错误态 |
| AI 风险洞察 | `demoInsights` | FarMap Intelligence 风险列表 | 风险数量、证据数和置信度固定 |
| 天气与降雨 | `demoWeather` | 天气卡片、AI Context | 无天气接口、观测时间和来源状态 |
| 诊断与建议 | `demoDiagnosis` / `demoRecommendations` | 跳转诊断和上下文 | Overview 未直接展示建议对象和审批状态 |
| 任务与动态 | `Overview` 内联数组 | 今日任务、农场动态 | 与 Operations API 没有统一来源 |
| NDVI 趋势 | `Overview` 内联高度数组 | Sparkline | 只有视觉趋势，没有时间序列和查询范围 |
| 监控预览 | `Overview` 内联占位视觉 | 设备在线卡片 | 不是实际视频或图片状态 |

### 3.2 已存在但当前未被 Overview 直接消费的能力

- `/user/get-farm?farmId=`：正式模式可以返回农场、作物点和位置数据，当前 Overview 没有调用。
- `/api/runtime/health`：可以提供 Agent、Operations、模型、Milvus 和历史检索器状态，当前 Overview 没有显示平台状态。
- `/api/operations/tasks`：提供审批后的 Agent 任务，当前 Overview 使用固定任务列表。
- `/diagnosis/analyze`：提供真实诊断、claims、evidence、recommendations 和 metadata，当前 Overview 只跳转到诊断页。
- `/api/agent/runs` 与运行详情接口：可以启动和读取 Agent Run，当前 Overview 只跳转到固定/真实 Agent 页面。
- `/api/expert-review`：提供专家复核状态，当前 Overview 没有显示待复核数量。
- `useAIContextStore`：已能保存当前农场、地块、天气、物候、诊断、设备和页面上下文。

## 4. Store dependencies

| Store / 状态 | 当前依赖 | Overview 的作用 | Redesign 计划 |
|---|---|---|---|
| `useAIContextStore` | `src/store/aiContext.ts` | 初始化 A-12、天气、诊断、设备和当前页面上下文 | 保留为跨页面上下文源；改为由当前真实 Farm/Field 选择驱动，不由 Demo 数据覆盖 |
| `useCopilotStore` | `src/store/copilot.ts` | 打开 Copilot、传递用户问题入口 | 保留；增加从决策队列预填充问题和风险上下文 |
| `useFarmStore` | `src/store/farm.ts` | 当前 Overview 未使用 | 正式模式可作为地图数据缓存；需明确与 `permanence.farm` 和 Header 农场选择的单一数据源关系 |
| `useUserStore` / `permanence.user` | 用户角色、农场列表 | 由 Layout 处理，Overview 间接继承 | Redesign 需要按角色决定决策队列和可执行动作 |
| `useCopilotStore.messages` | 本地会话消息 | Overview 不读取 | 不在 Overview 复制聊天；只提供上下文入口和快捷意图 |

当前最大问题不是缺少 Store，而是 Overview 同时拥有 Demo 数据、Layout 上下文和正式 API 数据的潜在来源，却没有明确优先级。Redesign 应先定义 `OverviewViewModel`，页面只消费一个已经标注 `REAL/DEMO/STALE/UNAVAILABLE` 的聚合模型。

## 5. Existing AI capabilities to reuse

### 5.1 Evidence Grounding

`DiagnosisAnalysisResponse` 已包含 `claims`、`evidence`、`recommendations`、来源时间、模态类别和 score。Overview 可将它们压缩为：

- 风险标题
- 影响地块与面积
- 关键证据数量
- 置信度和数据更新时间
- 进入诊断报告或启动 Agent 的动作

Overview 不应复制完整证据内容，而应显示“为什么需要关注”和证据数量，详情交给 Diagnosis。

### 5.2 Agent Runtime

Agent Runtime 已具备 Run、步骤、Tool Calls、Trace、Evidence、Output、Actions 和审批状态。Overview 可复用其只读摘要：

- 正在运行的分析数量
- 等待审批的动作数量
- 最近一次 Agent 运行状态
- 待批准建议的标题、风险和来源诊断

Overview 不应直接展开 Tool Trace，避免把指挥台变成调试台。

### 5.3 Human Approval and Operations

`/api/operations/tasks` 与 Agent action approval 已经提供了决策转行动的基础。Overview 需要把它们统一成：

- 待审批
- 已批准待执行
- 执行中
- 已完成
- 异常/逾期

每个项目必须带有来源诊断、地块、责任人、截止时间和下一动作。

### 5.4 Farm and Field Context

`FieldContextCard` 已有健康度、NDVI、土壤湿度、降雨、物候、土壤类型、海拔和坡度。它可以成为 Risk Priorities 和 Farm Health 的字段基础，但应从 Demo 类型升级为带数据质量和更新时间的业务 ViewModel。

### 5.5 Copilot

`useCopilotStore` 与 `CopilotDrawer` 已支持当前页面上下文、当前农场/地块/天气和复杂问题进入 Agent。Redesign 只需要从 Overview 传入：

- 当前风险 ID
- 当前决策队列项
- 用户选中的地块
- 推荐问题意图

## 6. Commercial problems in the current Overview

### P0 — 必须在 Overview Redesign 首轮解决

1. **首屏没有决策优先级。** 指标、地图、风险、天气、任务和动态同等占据注意力，用户不知道先处理哪个事项。
2. **核心内容为固定 Demo 数据。** 健康度、风险数、任务、NDVI、天气和监控时间没有清楚的来源与真实性状态，不能直接支撑商业客户判断。
3. **风险与行动断开。** 风险可以跳转诊断或 Operations，但没有在卡片上显示推荐动作、审批状态、负责人和截止时间。
4. **Overview 没有统一实时状态。** Agent、Operations、专家复核、Milvus/历史检索器等系统状态无法从首屏判断，失败或过期时缺少统一解释。

### P1 — 影响体验和转化

1. 地图是静态展示区域，选中地块后没有稳定的 Field Intelligence 面板。
2. AI Insight 风险卡只有固定三条，不能按影响、紧急度、置信度和地块面积排序。
3. 活动和任务列表与 Operations API 分离，用户在 Overview 看到的内容可能与任务中心不一致。
4. 监控卡是视觉占位，没有真实画面、最后采集时间、设备健康或异常状态。
5. NDVI 趋势只能展示固定 sparkline，缺少时间范围、数据来源、同比/环比和异常解释。
6. 天气卡只有预报，没有将天气变化与受影响地块、诊断和建议绑定。
7. AI 能力入口分散为 Agent CTA、Copilot 问题和诊断按钮，没有统一的“下一步”。

### P2 — 后续优化

1. 统一旧 `resp-*` 与新 `fm-*` 设计系统，避免 Overview 与旧页面视觉割裂。
2. 增加角色化视图：农场管理者看风险/任务，专家看复核，管理员看平台健康。
3. 增加农场对比、地块排名、历史趋势和风险关闭率。
4. 提供键盘可操作地图替代路径、图表文本摘要和不依赖颜色的状态表达。

## 7. Target: Agricultural AI Command Center

### 7.1 Design principles

1. **决策优先**：首屏先回答“现在该做什么”，再展示背景指标。
2. **以地块为业务对象**：所有风险、建议、任务和证据都能回到 Farm/Field。
3. **AI 可解释但不技术化**：默认展示依据和影响，模型路由与 Tool Trace 进入 Expert Mode。
4. **状态可信**：每块数据携带 `REAL/DEMO/STALE/UNAVAILABLE`、更新时间和来源。
5. **从洞察到行动**：每个高优先级风险必须有建议、审批状态和下一步。
6. **低认知负担**：管理者无需理解 RAG、Milvus 或 Tool Calling 也能完成决策。

### 7.2 Proposed layout

```text
Command Center header
├─ Farm selector + date + data freshness + platform status
├─ Today’s Decision Queue
│  ├─ critical risk needing approval
│  ├─ overdue / blocked operation
│  └─ expert review or data-quality exception
├─ Farm Health
│  ├─ overall health score
│  ├─ risk/healthy field distribution
│  ├─ trend and affected area
│  └─ selected field shortcut
├─ Risk Priorities + Farm map
│  ├─ ranked risk list
│  ├─ impact / confidence / evidence / next action
│  └─ map selection synchronized with list
├─ AI Recommendations
│  ├─ explanation
│  ├─ evidence count and source time
│  ├─ approval state
│  └─ approve / inspect / ask Copilot
├─ Active Operations
│  ├─ pending / in progress / overdue / completed
│  └─ owner, due time, source diagnosis
└─ Farm Trends
   ├─ NDVI / soil moisture / rainfall
   ├─ device health
   └─ diagnosis and risk trend
```

### 7.3 Proposed command-center modules

| 模块 | 首屏职责 | 必备信息 | 主要动作 |
|---|---|---|---|
| 今日决策队列 | 让用户知道先做什么 | 优先级、事项、影响地块、截止时间、来源、状态 | 查看详情、批准、分配、打开 Copilot |
| Farm Health | 判断农场整体是否稳定 | 健康分、风险地块数、受影响面积、趋势、数据时间 | 查看风险分布、选择地块 |
| Risk Priorities | 把 AI 发现变成排序列表 | 风险、严重度、置信度、证据、影响、建议 | 诊断、解释、生成任务 |
| Farm Map | 提供空间上下文 | 地块边界、风险层、设备、选中地块 | 定位、筛选、打开 Field Panel |
| AI Recommendations | 呈现可执行建议 | 建议、依据、风险、审批状态、可撤销性 | 审批、拒绝、查看证据 |
| Active Operations | 展示建议是否进入执行 | 任务状态、负责人、截止时间、异常、来源 | 打开任务、更新状态 |
| Farm Trends | 支持趋势判断 | NDVI、湿度、降雨、风险、设备在线 | 调整时间范围、比较地块 |

## 8. Component refactor plan

本节只定义未来实施边界，不在本阶段修改代码。

### 8.1 Page shell

将现有 `Overview` 的单文件 JSX 拆成页面级容器和可测试的业务区块：

```text
OverviewPage
├─ OverviewHeader
├─ DecisionQueue
├─ FarmHealthSummary
├─ RiskPriorityBoard
│  ├─ RiskPriorityList
│  └─ FarmMapPanel
├─ RecommendationBoard
├─ ActiveOperationsPanel
├─ FarmTrendsPanel
└─ OverviewEmptyErrorState
```

页面容器负责加载、刷新、Farm/Field 选择和状态汇总；区块组件只接收 ViewModel，不直接读取 Demo 模块或调用 API。

### 8.2 ViewModel boundary

建议新增一个 Overview ViewModel 层，把后端、Demo 和错误态归一化：

```ts
type OverviewMode = "REAL" | "DEMO" | "STALE" | "UNAVAILABLE";

type OverviewViewModel = {
  mode: OverviewMode;
  farm: { id: number; name: string; address: string; updatedAt?: string };
  health: { score: number; trend?: number; affectedArea?: number; totalArea?: number };
  decisionQueue: DecisionItem[];
  riskPriorities: RiskItem[];
  recommendations: RecommendationItem[];
  operations: OperationSummary;
  trends: FarmTrendSeries[];
  map: FarmMapViewModel;
  platform: PlatformStatus;
  freshness: DataFreshness[];
};
```

`Overview` 不应继续直接使用 `demoFarm`、`demoInsights`、内联任务数组和内联 sparkline 高度。

### 8.3 Reuse and replacement map

| 当前实现 | 计划 |
|---|---|
| `MetricItem` | 保留，增加 `status`, `trend`, `freshness` 和可点击语义 |
| `StatusBadge` | 保留，扩展 `REAL/DEMO/STALE/UNAVAILABLE` 和审批/任务状态 |
| `MapContainer` | 保留，外包 `FarmMapPanel` 处理选择、图层、风险筛选和 Field Panel |
| `FieldContextCard` | 保留字段设计，改为接收真实 ViewModel |
| `RecommendationCard` | 复用到 RecommendationBoard，增加 evidence、approval 和 owner |
| `EvidenceCard` | 不直接铺在 Overview，提供证据数量与来源摘要，详情跳 Diagnosis |
| `CopilotDrawer` | 保留全局组件，增加 risk/decision context 预填充 |
| 内联风险按钮 | 替换为 `RiskPriorityList`，支持排序、状态和统一动作 |
| 内联任务数组 | 替换为 `ActiveOperationsPanel` 的 API 数据 |
| 固定 sparkline | 替换为可加载的 `FarmTrendsPanel` |

## 9. Data interface requirements

### 9.1 Recommended aggregate endpoint

为减少 Overview 的并发请求和跨模块状态不一致，建议提供只读聚合接口：

```http
GET /api/overview?farmId={farmId}&range=7d
Authorization: Bearer {token}
```

建议响应：

```json
{
  "code": 0,
  "data": {
    "mode": "REAL",
    "farm": {
      "id": 9001,
      "name": "云阳智慧果园",
      "address": "重庆市云阳县",
      "updatedAt": "2026-09-04T09:32:18+08:00"
    },
    "health": {
      "score": 86,
      "previousScore": 80,
      "affectedArea": 2.4,
      "totalArea": 18.6,
      "riskFieldCount": 2,
      "healthyFieldCount": 10
    },
    "decisionQueue": [],
    "riskPriorities": [],
    "recommendations": [],
    "operations": {
      "pendingApproval": 0,
      "pendingExecution": 0,
      "inProgress": 0,
      "overdue": 0,
      "recent": []
    },
    "trends": [],
    "map": { "center": [108.701, 30.956], "zoom": 13, "fields": [], "locations": [] },
    "platform": { "agent": "READY", "operations": "READY", "expertReview": "READY" },
    "freshness": []
  }
}
```

### 9.2 Domain contracts

#### DecisionItem

```ts
type DecisionItem = {
  id: string;
  kind: "RISK" | "APPROVAL" | "OPERATION" | "REVIEW" | "DATA_QUALITY";
  priority: "CRITICAL" | "HIGH" | "MEDIUM" | "LOW";
  title: string;
  summary: string;
  farmId: number;
  fieldId?: string;
  sourceId?: string;
  evidenceCount?: number;
  confidence?: number;
  owner?: { id: string; name: string };
  dueAt?: string;
  status: string;
  nextAction: { label: string; href?: string; action?: string };
  mode: "REAL" | "DEMO";
  updatedAt: string;
};
```

#### RecommendationItem

每条建议必须包含 `recommendationId`、标题、优先级、影响地块、依据证据 ID、建议动作、审批状态、责任人、截止时间、是否可撤销和来源诊断 ID。

#### OperationSummary

任务摘要必须与 `/api/operations/tasks` 使用同一 `taskId`、`agentRunId`、`diagnosisId`、`fieldId`、`status`、`priority` 和 `evidenceIds`，避免 Overview 与 Operations 产生两套任务事实。

#### FarmTrendSeries

每条趋势包含 `metric`、`unit`、`points[{timestamp,value}]`、`range`、`source`、`updatedAt`、`status` 和可选的 `anomaly`。首期建议支持 `NDVI`、`soilMoisture`、`rainfall`、`deviceOnlineRate` 和 `riskCount`。

### 9.3 Existing endpoint mapping

在聚合接口落地前，可以按以下顺序组合已有能力，但这只是过渡方案：

| Overview 模块 | 可复用现有接口/能力 | 当前不足 |
|---|---|---|
| Farm/map | `/user/get-farm?farmId=`、`MapContainer` | 无统一 freshness、风险摘要和错误态 |
| Operations | `/api/operations/tasks` | 缺少聚合计数、逾期和负责人字段 |
| Platform status | `/api/runtime/health` | 面向系统健康，不是用户决策摘要 |
| Diagnosis | `/diagnosis/analyze` | 以单次分析为中心，不提供风险列表 |
| Agent | `/api/agent/runs/{runId}` | 缺少 Overview 级最近运行/待审批查询 |
| Expert review | `/api/expert-review` | 缺少按农场、优先级和状态筛选 |
| Guidance | `/guidance/get` | 农事指导未绑定风险、地块和任务 |

接口需求只在本计划中定义；本阶段不创建 API、不修改后端。

## 10. Implementation steps

### Step 0 — Contract and state alignment

- 确认 Farm、Field、Diagnosis、Recommendation、Approval、Operation、Review 的 ID 关系。
- 定义 `REAL/DEMO/STALE/UNAVAILABLE` 和任务/审批状态字典。
- 明确角色：农场管理者、专家、管理员分别看到什么决策项。
- 产出：Overview ViewModel、状态字典、接口草案。

### Step 1 — Decision Queue first

- 把最高优先级风险、待审批建议、逾期任务、专家复核和数据异常合并为一条队列。
- 每条队列项只允许一个首要动作，避免用户在卡片上同时面对多个 CTA。
- 产出：可从首屏完成“查看—批准/分配—进入详情”的路径。

### Step 2 — Farm Health and map context

- 保留地图能力，新增风险图层、选中地块和 Field Intelligence Panel。
- 将健康分从单一数字扩展为风险地块数、受影响面积、趋势和数据时间。
- 产出：用户从地图选择地块后，能立即看到风险、证据、建议和相关任务。

### Step 3 — Recommendations and Operations

- 使用 `RecommendationCard` 展示建议依据、优先级、审批状态和责任人。
- 使用真实 Operations 任务摘要替换内联固定任务。
- 增加待审批、执行中、逾期和异常汇总。
- 产出：Overview 上的推荐与 Operations 中的任务使用同一对象和状态。

### Step 4 — Trends and freshness

- 将固定 sparkline 替换为真实时间序列和时间范围选择。
- 统一显示数据更新时间、来源和过期状态。
- 产出：用户能判断健康变化是趋势、异常还是数据延迟。

### Step 5 — AI and Copilot integration

- 风险项和建议项提供“解释原因”“查看证据”“询问 Copilot”“启动 Agent”动作。
- Copilot 读取选中的风险和地块，而不是只读取当前 URL。
- Agent 入口显示预期结果和审批影响，不直接暴露 Tool Trace。
- 产出：AI 能力转化为可理解的业务动作。

### Step 6 — Empty, error, permission and responsive states

- 为无农场、无风险、无任务、无设备、接口失败、权限不足和数据过期提供统一状态。
- 重新验证窄屏下的队列、地图、趋势和任务排列。
- 产出：Overview 在试点数据不完整时仍然可信可用。

### Step 7 — Validation

- 验证管理者、专家、管理员和演示用户的首屏差异。
- 验证 REAL/DEMO 标识与 API 失败场景。
- 验证从 Decision Queue → Diagnosis → Agent → Approval → Operations 的回链。
- 验证键盘导航、图表文本摘要、地图替代操作和状态文本。

## 11. Acceptance criteria for Overview Phase

完成后，Overview 应满足：

1. 首屏 10 秒内能说明当前农场最重要的三件事。
2. 每个 P0/P1 风险都显示地块、影响、证据、状态和下一动作。
3. Overview 与 Operations 使用同一任务事实，不出现固定任务与真实任务冲突。
4. 用户能分辨 REAL、DEMO、STALE 和 UNAVAILABLE 数据。
5. 选中地块后，地图、风险、诊断、建议和 Copilot 上下文同步。
6. 技术信息默认隐藏，但专家可以打开证据和运行详情。
7. 空数据、失败、权限和窄屏状态有明确反馈。
8. 不新增业务逻辑时，现有 Agent、RAG、Approval 和 Operations API 仍可复用。

## Final direction

Overview 的改造重点不是继续增加卡片，而是把现有的 Farm、Field、Diagnosis、Agent、Approval 和 Operations 能力重新组织成一条决策路径。首期应优先解决真实性、优先级、状态和下一动作四个问题；完成后，FarMap 才能从“展示 AI 能力的 Dashboard”变成“帮助农场管理者做决定的 Agricultural AI Command Center”。
