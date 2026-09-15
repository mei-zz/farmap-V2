# FarMap Overview ViewModel Implementation

## Scope

本次实现对应 Milestone 5.1.1 的 Step 0 — Contract and State Alignment。目标是为 `/overview` 建立稳定的数据边界，让页面消费一个可替换的 `OverviewViewModel`，同时保持现有页面布局、交互入口和演示数据表现不变。

本阶段没有修改后端 API、数据库、Agent Runtime、RAG、Approval 或 Operations 逻辑，也没有创建 `/api/overview`。

## Current dependency audit

在改造前，Overview 页面直接从 `@/demo` 读取以下数据：

| 页面内容 | 原始来源 | 现在的 ViewModel 字段 | 适配方式 |
| --- | --- | --- | --- |
| 农场名称、位置、面积、健康度、设备数、坐标 | `demoFarm` | `farm`、`health` | `getDemoOverviewViewModel()` |
| 主地块、作物、NDVI、土壤湿度、物候 | `demoPrimaryField` | `primaryField` | `getDemoOverviewViewModel()` |
| 地图作物和位置 | `demoMapCrops`、`demoFarmLocations` | `map` | 保留 `MapContainer` 输入结构 |
| AI 风险洞察 | `demoInsights` | `riskPriorities`、`decisionQueue` | 映射优先级、摘要、证据数和下一动作 |
| 诊断和建议 | `demoDiagnosis`、`demoRecommendations` | `diagnosis`、`recommendations` | 保留现有跳转和 AI Context 内容 |
| 天气 | `demoWeather` | `weather` | 复制天气对象并归一化日期数组 |
| 今日任务 | Overview 原内联数组 | `operations.tasks` | 移入适配层并补充任务状态、优先级和地块 ID |
| NDVI sparkline | Overview 原内联高度数组 | `trends` | 移入适配层，转换为带标签的趋势点 |
| 农场动态 | Overview 原内联数组 | `activities` | 移入适配层 |
| 监控卡片 | Overview 原内联占位字段 | `monitor` | 归一化设备、地块、状态和采集时间 |

现有 Store 依赖保持不变：

- `useAIContextStore` 继续接收当前农场、主地块、天气、诊断、设备和页面上下文。
- `useCopilotStore` 继续提供 Copilot 打开入口。
- `useFarmStore` 没有被新增为 Overview 的隐式数据源；未来接入真实数据时由适配器统一协调。

## New contract

新增 [`src/types/overview.ts`](../farmap-frontend-dev/src/types/overview.ts)，定义：

- `OverviewMode`：`REAL`、`DEMO`、`STALE`、`UNAVAILABLE`。
- `OverviewViewModel`：包含 `farm`、`health`、`decisionQueue`、`riskPriorities`、`recommendations`、`operations`、`trends`、`map`、`platform`、`freshness`，并补充当前页面保持视觉不变所需的 `primaryField`、`weather`、`monitor`、`recentDiagnoses`、`activities` 和 `diagnosis`。
- 决策项、风险项、建议项、运营任务、趋势、平台状态和数据新鲜度的结构化类型。

`OverviewMode` 是后续真实接口、缓存过期和不可用状态的共同语义入口；本阶段 Demo 适配结果统一标记为 `DEMO`。

## Adapter boundary

新增 [`src/features/overview/adapter.ts`](../farmap-frontend-dev/src/features/overview/adapter.ts)，提供 `getDemoOverviewViewModel()`。该层负责：

1. 读取现有 Demo 模块。
2. 将风险洞察映射为 `riskPriorities` 和 `decisionQueue`。
3. 将原 Overview 内联任务、趋势点和动态移入统一模型。
4. 生成 `platform` 和 `freshness`，为未来 REAL/STALE/UNAVAILABLE 适配预留位置。
5. 保持地图数据结构与现有 `MapContainer` 兼容。

真实数据接入时，可以新增同一边界下的 API adapter 或 service，替换 ViewModel 构造来源，不需要让页面重新绑定多个后端响应。

## Overview page alignment

[`src/views/overview/index.tsx`](../farmap-frontend-dev/src/views/overview/index.tsx) 现在只读取 `getDemoOverviewViewModel()` 的结果：

- 页面不再直接导入 `demoFarm`、`demoInsights`、`demoFields`、`demoWeather`、地图 Demo 数据或诊断 Demo 数据。
- 页面不再持有内联任务数组、内联农场动态数组或内联 sparkline 高度数组。
- 原有页面分区、卡片层级、CSS class、按钮入口和导航路径保持不变。
- AI Context 的初始化仍然发生在 Overview 进入时，但字段来自 ViewModel，后续可由真实农场/地块选择驱动。

## Data flow after Step 0

```text
Demo sources (temporary)
        ↓
Overview adapter
        ↓
OverviewViewModel { mode, freshness, domain sections }
        ↓
Overview page + AI Context initialization
        ↓
Diagnosis / Agent / Copilot / Operations navigation
```

页面层已经具备替换 Demo 数据源的接口，但本阶段不调用现有后端接口，避免在 ViewModel 契约尚未稳定时引入跨模块状态不一致。

## Validation

- `pnpm exec tsc -b --pretty false`：通过。
- `pnpm exec eslint src/views/overview/index.tsx src/features/overview/adapter.ts src/types/overview.ts`：通过。
- `git diff --check`：通过；仅提示当前工作区文件的换行符转换警告。
- `pnpm exec vite build --minify=false`：未完成。Vite 在解析现有依赖 `react-router` 的 `chunk-KS7C4IRE.mjs` 时报告 invalid JS syntax，失败发生在依赖导入分析阶段，未指向本次新增类型、适配器或 Overview 页面代码。

## Out of scope

本阶段没有：

- 修改后端逻辑或创建 Overview API。
- 修改 Agent、RAG、Approval 或 Operations 实现。
- 删除旧 Overview 或进行页面视觉重构。
- 提交 Git commit。

后续 Milestone 5.1.2 可以在此 ViewModel 边界上接入真实 Farm、Diagnosis、Operations 和平台健康数据，再逐步实现 Decision Queue 与 Command Center UI。
