# FarMap Overview Command Center Design

状态：**待设计确认，尚未进入 UI 实施**  
分支：`feature/commercial-product`  
范围：Overview 页面及其直接展示层；不修改 Backend、Agent Runtime、RAG、Database 或 Operations API。

## 0. 现状检查证据

### 运行页面

已启动本地 Vite 应用，并通过 `/farmap/overview` 检查页面。正式登录会重定向到 `/farmap/login`，本次使用页面已有的“进入演示”入口查看，不提交账号或外部表单。运行页面显示 `DEMO MODE`，地图 iframe 正常加载，Overview 的主要卡片和导航均可见。

当前运行页面的关键可见结构：

- Sidebar：工作台、数字农场、AI 农情诊断、Agent、Operations、Knowledge & Experts。
- Header：农场选择、天气、日期、搜索、通知、FarMap Copilot、Demo 状态和用户菜单。
- Overview：四个指标卡、地图空间态势、FarMap Intelligence 三条风险、NDVI、天气、监控、任务、最近诊断和农场动态。

### 当前实现与依赖

- 页面入口：`farmap-frontend-dev/src/views/overview/index.tsx`。
- 当前 Feature 层：`farmap-frontend-dev/src/features/overview/adapter.ts`，已将 Demo 数据包装为 `OverviewViewModel`；当前没有独立的 Overview 业务模块组件。
- 类型边界：`farmap-frontend-dev/src/types/overview.ts`。
- 视觉组件：Ant Design 的 `Card`、`Button`、`Flex`、`Space`、`Tag`、`Typography`，以及项目自有 `MetricItem`、`StatusBadge` 和 `MapContainer`。
- 样式：HTML 直接加载根目录 `index.css`；该文件引入 Tailwind v4，同时 FarMap 商业界面主要使用全局 `fm-*` 和 `farmap-*` CSS。Overview 没有 CSS Module。
- Layout：`farmap-frontend-dev/src/layout/index.tsx` 提供 Header、Sidebar、农场切换、全局搜索、Demo 状态、Copilot 入口和 `CopilotDrawer`。
- Store：Overview 使用 `useAIContextStore` 初始化页面上下文和 `useCopilotStore` 打开 Copilot；Layout 负责路径上下文、农场选择和用户角色。

### 当前视觉判断

从运行视口看，地图占据主视觉区域，右侧 AI 风险卡有清晰的绿色/红色/紫色层次，整体已有农业 SaaS 的色彩基础。但信息仍按卡片平铺：四个指标与风险、天气、任务具有接近的视觉权重；页面首屏没有“现在先处理什么”的明确答案。风险卡能展示摘要、置信度和 Evidence 数量，却没有直接呈现推荐动作、审批状态、负责人或截止时间。页面右上角有 `DEMO MODE`，但卡片内部没有统一的 freshness 与数据来源说明。

## 1. 当前 Overview UX 评价

### 商业产品感：**62/100**

已经具备农业 SaaS 的基础壳层、空间地图、AI 风险和 Copilot 入口，但还没有达到 Command Center 的“决策优先、证据可信、行动闭环”标准。当前更接近展示 AI 能力的 Dashboard，而不是帮助农场管理者完成决策的工作台。

### P0 — 商业展示阻断

1. **缺少首屏决策优先级**：没有 Today's Decision Queue，用户无法在 10 秒内知道最应该处理的事项。
2. **Demo 真实性边界不完整**：虽然 Header 显示 `DEMO MODE`，风险、任务、趋势、监控和天气卡没有统一标注数据模式、更新时间和过期状态。
3. **洞察没有贯通到行动**：风险卡只提供详情跳转；推荐、审批、任务负责人和截止时间没有在同一条业务路径上出现。

### P1 — 影响体验与转化

1. 四个指标卡占据首屏优先位置，却不能解释风险数量、受影响面积或趋势原因。
2. Risk Intelligence 与地图之间没有稳定的选择同步：风险列表、地图地块和地块上下文不能形成双向空间交互。
3. AI Recommendation、Operations 和 Expert Review 没有 Overview 级摘要，用户看不到建议是否已批准、是否正在执行或需要专家复核。
4. Copilot 入口是全局抽屉式入口，风险项没有携带结构化 risk/recommendation context，也没有在关键决策点提供 Ask AI、Explain、Create Task 等明确动作。
5. 监控卡是占位视觉，NDVI 是固定 sparkline，天气没有绑定受影响地块和建议，导致实时感与业务关联不足。

### P2 — 后续优化

1. 页面仍是单文件 JSX，业务区块不可独立测试和复用。
2. 设计系统同时存在 Ant Design、Tailwind 引入和全局 `fm-*` CSS，需后续统一 token 与状态语义。
3. 缺少无风险、无任务、无设备、接口失败、权限不足、窄屏和键盘替代操作的明确状态。
4. 风险列表、趋势和运营任务暂未支持按影响面积、优先级、时间范围和角色过滤。

## 2. 新 Overview 信息架构

```text
Farm Command Header
        ↓
Today's Decision Queue
        ↓
Farm Health Command
        ↓
Risk Intelligence + Map
        ↓
AI Recommendation Center
        ↓
Operations Status
        ↓
Farm Trends
```

### Farm Command Header

保留现有 Layout Header 的农场切换、日期、天气、搜索、通知和 Copilot。Overview 页面只增加当前农场的上下文摘要：数据模式、最后更新时间、平台状态和当前用户角色可执行范围。全局 Copilot 入口继续保留，页面内提供带上下文的入口。

### Today's Decision Queue

成为首屏第一业务模块，合并：高风险、待审批建议、逾期任务、专家复核和数据质量异常。每条 `DecisionCard` 只给一个首要动作，并显示：优先级、事项、地块、影响摘要、置信度、Evidence 数量、状态和下一动作。

### Farm Health Command

替换普通指标行的“平铺 KPI”语义，保留健康分但增加风险地块数、受影响面积、健康趋势、设备在线率和 freshness。它回答“农场整体是否稳定”，并提供进入风险分布的动作。

### Risk Intelligence + Map

左侧使用可交互农场地图，右侧使用按优先级排序的风险列表。点击风险定位地图并选中地块；点击地图地块更新风险列表与地块上下文。地图只展示决策所需的风险、设备和地块图层，技术图层放入次级操作。

### AI Recommendation Center

每条建议直接展示建议内容、影响地块/面积、关键 Evidence 数量、影响/收益摘要、审批状态和下一动作。默认使用业务语言，不展开模型名称、Prompt、Tool Trace 或调试日志。

### Operations Status

展示建议之后发生的结果：Recommended、Pending Approval、Approved、Executing、Completed、Exception。每个任务携带来源诊断、地块、责任人、截止时间和进入 Operations 的动作。Overview 与 Operations 后续必须使用同一任务事实。

### Farm Trends

以可切换时间范围的趋势支持判断：NDVI、土壤湿度、降雨、设备在线率和风险数量。每个趋势提供异常解释、来源和更新时间，避免只有装饰性的 sparkline。

### 布局理由

顺序按照管理者的决策链排列：先决定“做什么”，再确认“农场整体状态”，接着用地图理解“影响在哪里”，用证据确认“为什么”，用审批和运营完成“怎么执行”，最后用趋势判断“是否改善”。这比当前多卡片平铺更符合 John Deere Operations Center、工业 AI Command Center 和医疗 AI Copilot 的工作流结构。

## 3. 用户任务流程

### 进入后 10 秒

用户看到当前农场、数据模式和更新时间，并在 Today's Decision Queue 看到最多三项优先事项。第一项明确显示“什么问题、影响哪个地块、为什么需要关注、下一步是什么”。

### 进入后 30 秒

用户打开一项风险，地图定位到对应地块；Farm Health 显示受影响面积与风险趋势；用户可以查看 Evidence 摘要，判断这是实时信号、历史案例匹配还是数据异常。

### 进入后 1 分钟

用户完成一个可逆的业务动作：查看诊断、请求 AI 解释、批准建议、创建/分配 Operations 任务，或把问题交给专家复核。完成后，页面显示任务状态和下一次更新时间，形成可追踪闭环。

## 4. AI 能力如何展示

| 能力 | 用户可见表达 | 默认隐藏内容 |
| --- | --- | --- |
| Multimodal RAG | “来自监控图像、天气、土壤与历史案例的综合证据”及 Evidence 数量/来源时间 | Retriever、向量库、Chunk、Embedding |
| Evidence Grounding | 每个风险/建议显示关键证据数、来源模态、观测时间和“查看证据” | 原始检索分数和内部排序 |
| Agent | “让 Agent 分析”“生成处理方案”“准备创建任务”等结果导向动作 | Model Name、Prompt、Tool Trace |
| Human Approval | 显示待审批、已批准、需专家复核和审批人角色 | 内部权限实现细节 |
| Operations | 显示建议到任务的状态流转和异常 | API 请求、队列和运行日志 |

AI 的页面表达必须回答“为什么”和“下一步”，而不是展示技术过程。技术详情只在 Diagnosis 或 Agent Expert Mode 中按需展开。

## 5. Component Architecture

```text
OverviewPage
├─ FarmCommandHeader
├─ DecisionQueue
│  └─ DecisionCard
├─ FarmHealthCommand
├─ RiskIntelligenceBoard
│  ├─ RiskPriorityList
│  ├─ FarmMapPanel
│  └─ FieldIntelligencePanel
├─ RecommendationCenter
│  └─ RecommendationActionCard
├─ OperationsSummary
├─ FarmTrendsPanel
└─ OverviewStateBoundary
        ↓
OverviewViewModel
        ↓
Overview adapter/service
        ↓
REAL / DEMO / STALE / UNAVAILABLE sources
```

### 页面容器职责

`OverviewPage` 负责加载状态、农场/地块选择、刷新、ViewModel 模式和跨模块上下文；不直接导入 Demo 数据，也不直接拼接 API 响应。

### 业务模块职责

业务模块只接收经过类型约束的 ViewModel 子集和事件回调：

- `DecisionQueue`：排序和首要动作。
- `FarmHealthCommand`：健康分、风险分布、面积和 freshness。
- `RiskIntelligenceBoard`：风险与地图双向选择。
- `RecommendationCenter`：证据、影响、审批和下一动作。
- `OperationsSummary`：执行状态与异常。
- `FarmTrendsPanel`：趋势、范围和异常解释。
- `OverviewStateBoundary`：空、错、权限、过期和不可用状态。

### ViewModel 依赖

模块不得读取 `demoFarm`、`demoInsights`、内联任务、内联趋势或 Store 内部字段。所有展示数据必须来自 `OverviewViewModel`；AI Context 和 Copilot 事件通过显式回调传递 risk、field、recommendation context。

## 6. 实施顺序与门禁

设计确认后按以下顺序实施：

1. 将当前 Overview 单文件拆成页面容器和业务模块，先保留现有视觉 token。
2. 实现 Decision Queue 与 `DecisionCard`，复用现有 `OverviewViewModel`。
3. 实现 Farm Health Command 和统一 freshness/status 表达。
4. 实现 Risk Intelligence 与地图/地块选择同步。
5. 实现 Recommendation Center、Approval 状态和 Operations Summary。
6. 在关键决策点接入带 field/risk/recommendation context 的 Copilot 动作。
7. 替换固定趋势和占位监控状态，补齐空态、错误态、权限态和窄屏验证。

每一步都必须保持现有路由和原有入口可用；不新增后端接口，不修改 Agent、RAG 或 Operations API。

## 7. 设计确认项

进入代码实施前需要确认：

1. 是否采用以上信息架构和首屏优先级。
2. 是否接受 `Today's Decision Queue` 作为 Overview 的首要模块。
3. 是否允许在 Overview 内新增业务模块组件并拆分现有单文件 JSX，但保持现有路由不变。
4. 是否接受先以 Demo ViewModel 驱动交互，再在后续阶段接入真实聚合数据。

本文件完成后暂停 UI 实施，等待设计确认。
