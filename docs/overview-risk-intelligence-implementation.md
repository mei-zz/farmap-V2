# Milestone 5.1.1 Step 3 Result

分支：`feature/commercial-product`  
阶段：`Risk Intelligence + Map`  
范围：Overview 商业 UI；没有修改后端、API、数据库、Agent Runtime、Planner、Tool Registry、RAG、Milvus、GIS 引擎、认证、Operations API 或 Copilot 深层逻辑。

## 1. Reality Check

### MapContainer 当前能力

当前地图实现位于 [`farmap-frontend-dev/src/views/dashboard/Map.tsx`](../farmap-frontend-dev/src/views/dashboard/Map.tsx)，底层通过 AMap iframe/地图实例绘制农场位置、地块多边形和监控标记。`MapProps` 支持 `center`、`zoom`、`crops`、`farmLocations`、`infoKey`、`modeKey`、滑块和 `readOnly`，但没有暴露地块点击回调、选中地块回调、风险图层协议或外部选中状态 API。地图标记和多边形由组件内部绘制，Overview 不能安全地从现有 MapContainer 读取“点击了哪个地块”。

Digital Farm 页面有自己的模式、信息和农场位置选择逻辑，但没有可直接复用的 Overview 级 field-selection callback。因此本步骤采用包装现有 `MapContainer` 的方式，避免重写 GIS 引擎。

### 风险数据来源

Overview 通过 [`farmap-frontend-dev/src/features/overview/adapter.ts`](../farmap-frontend-dev/src/features/overview/adapter.ts) 将现有 Demo fixtures 适配为 `OverviewViewModel`。Risk Intelligence 组件只接收 `OverviewViewModel.riskPriorities`、`map`、`mode` 和 `freshness`，没有直接导入 `demoInsights`、`demoFarm` 或内联数据。

### 当前 Risk / Operational Signal 边界

原始洞察中有三条信号：A-12 叶片异常、未来降雨、B-04 采收窗口。前两条是需要监测的风险，B-04 是正常物候/运营机会。因此现在由 `signalType` 明确标记：

| 信号 | `kind` | `signalType` | Risk Intelligence | Decision Queue |
| --- | --- | --- | --- | --- |
| A-12 地块叶片异常 | `RISK` | `RISK` | 展示 | 展示 |
| 未来 24 小时强降雨风险 | `RISK` | `RISK` | 展示 | 展示 |
| B-04 进入采收窗口 | `OPERATION` | `OPERATIONAL` | 不展示 | 展示 |

这样 Decision Queue 继续回答“现在应该做什么”，Risk Intelligence 只回答“风险在哪里、当前是什么状态”。

## 2. Risk Semantic Audit

- `riskFieldCount` 现在只从 `signalType === "RISK"` 的信号聚合。演示数据结果为 **1 个风险地块**，而不是把 B-04 采收窗口计入风险。
- `affectedArea` 现在只累计 Risk Signal 的 `fieldArea`。演示数据结果为 **2.4 ha**，对应 A-12。
- B-04 仍保留在 Decision Queue 和今日任务语境中，未被人为删除；它只是不会污染 Farm Health 的风险覆盖指标。
- `mapReference` 使用 AMap 需要的 `[longitude, latitude]` 顺序，A-12 可聚焦到其地块中心；天气风险没有地块引用，保持全农场视角。

## 3. Files Changed

### 新增

- [`farmap-frontend-dev/src/features/overview/components/RiskIntelligenceBoard/index.tsx`](../farmap-frontend-dev/src/features/overview/components/RiskIntelligenceBoard/index.tsx)
- [`farmap-frontend-dev/src/features/overview/components/RiskIntelligenceBoard/RiskPriorityList.tsx`](../farmap-frontend-dev/src/features/overview/components/RiskIntelligenceBoard/RiskPriorityList.tsx)
- [`farmap-frontend-dev/src/features/overview/components/RiskIntelligenceBoard/RiskPriorityItem.tsx`](../farmap-frontend-dev/src/features/overview/components/RiskIntelligenceBoard/RiskPriorityItem.tsx)
- [`farmap-frontend-dev/src/features/overview/components/RiskIntelligenceBoard/FarmRiskMapPanel.tsx`](../farmap-frontend-dev/src/features/overview/components/RiskIntelligenceBoard/FarmRiskMapPanel.tsx)
- [`farmap-frontend-dev/src/features/overview/components/RiskIntelligenceBoard/SelectedRiskSummary.tsx`](../farmap-frontend-dev/src/features/overview/components/RiskIntelligenceBoard/SelectedRiskSummary.tsx)
- [`docs/overview-risk-intelligence-implementation.md`](./overview-risk-intelligence-implementation.md)

### 修改

- [`farmap-frontend-dev/src/types/overview.ts`](../farmap-frontend-dev/src/types/overview.ts)：增加最小的 `mapReference` 和 `signalType` 契约。
- [`farmap-frontend-dev/src/features/overview/adapter.ts`](../farmap-frontend-dev/src/features/overview/adapter.ts)：补充风险地图引用、Risk/Operational 分类，并修正健康风险聚合。
- [`farmap-frontend-dev/src/views/overview/index.tsx`](../farmap-frontend-dev/src/views/overview/index.tsx)：移除旧的独立地图/Intelligence 区块，接入统一 Risk Intelligence Board 和局部 selected risk state。
- [`farmap-frontend-dev/index.css`](../farmap-frontend-dev/index.css)：增加空间风险工作区、地图叠加、风险列表、选中摘要和响应式样式。

### 明确未修改的边界

没有修改 Backend、任何 API、数据库、Agent、Planner、Tool Registry、RAG、Milvus、GIS engine、Authentication、Recommendation Center、Operations implementation、Knowledge Graph 或 Copilot 深层逻辑。

## 4. Data Contract Changes

数据流现在是：

```text
Demo fixtures / future API
        ↓
Overview adapter
        ↓
OverviewViewModel.riskPriorities + map + health + freshness
        ↓
OverviewPage selectedRisk state
        ↓
RiskIntelligenceBoard
        ├─ RiskPriorityList
        ├─ FarmRiskMapPanel
        └─ SelectedRiskSummary
```

`OverviewDecisionItem` 增加可选 `mapReference`；`OverviewRiskPriority` 增加必需 `signalType`。现有 `riskPriorities` 字段继续复用 `priority`、`field`、`impact`、`evidence`、`status` 和 `freshness`，没有为了本步骤复制一套风险模型。

Farm Health 使用同一份 Risk Signal 语义计算风险覆盖；地图继续使用现有 `OverviewMap` 数据结构，新的地图面板只改变中心点和缩放输入，不改变地图数据来源。

## 5. Component Architecture

```text
OverviewPage
├─ DecisionQueue                 # action-oriented: 下一步做什么
├─ FarmHealthCommand             # aggregate health / risk coverage
├─ RiskIntelligenceBoard         # spatial risk decision workspace
│  ├─ FarmRiskMapPanel            # wraps existing MapContainer
│  ├─ RiskPriorityList             # risk-only monitoring signals
│  │  └─ RiskPriorityItem          # priority / field / evidence / freshness
│  └─ SelectedRiskSummary          # lightweight selected context
└─ existing trends / weather / monitor / operations cards
        ↓
OverviewViewModel
        ↓
Overview adapter
```

页面负责 selected risk state、AI Context 和现有导航；业务组件负责布局、风险列表渲染和风险到地图的选择协调。风险列表没有重复 `recommendedAction`、`查看诊断` 或 `Ask AI`，这些继续由 Decision Queue 或后续 Recommendation Center 承担。

## 6. Map ↔ Risk Interaction

### Risk → Field → Map：已完成

点击风险项会：

1. 更新 Overview 层的 `selectedRiskId`。
2. 将风险项对应的 field 写入现有 `AIContextStore` 的 `currentField`、`currentCrop` 和 `currentLocation`。
3. 通过 `mapReference` 更新地图中心和缩放，A-12 显示选中地块上下文。
4. 在风险列表显示 selected 状态，并在地图叠加层显示 Selected Field。

选择没有地块引用的天气风险时，AI Context 清除具体地块，地图回到全农场视角，并明确显示“全农场风险范围”。

### Map → Field → Risk：当前未完成

原因是现有 `MapContainer` 没有暴露地块点击/选中回调，也没有外部选中地块 API；其 marker、polygon 和 AMap 实例均由组件内部管理。为完成双向联动而重写 GIS 会超出本步骤范围并增加回归风险。因此报告明确保留该限制，Step 3 只交付安全的 Risk → Map 一侧。

## 7. Product Behavior

Risk Intelligence 的用户路径是：

1. 进入 Overview，在 Farm Health 下看到风险空间态势。
2. 地图作为主视觉，右侧按优先级列出两条当前 Risk Signal。
3. 风险项展示优先级、类别、监测状态、地块/作物、摘要、影响面积、AI 信号置信度、证据来源和更新时间。
4. 选择 A-12 后，地图聚焦 A-12，Selected Risk Summary 同步显示地块、面积和 Evidence。
5. 选择降雨风险后，地图回到全农场范围，避免假装它属于某一个地块。

Risk Intelligence 不承担诊断详情、推荐动作、审批或任务创建；这些入口仍位于既有 Decision Queue、Diagnosis 和后续阶段，避免同一风险再次形成第三份重复卡片。

## 8. Evidence Truthfulness

- DEMO 模式下，历史来源统一显示为 **历史案例（演示）**，与 Decision Queue 保持一致。
- REAL/STALE/UNAVAILABLE 模式下，历史来源会被过滤，并在有该来源但当前不可用时显示 **历史案例暂不可用**；不会把 Historical Case 当作真实证据。
- 证据来源使用业务标签：监控图像、气象、空间数据、物候、农业知识。没有来源时显示“证据来源暂不可用”。
- 当前真实 Historical Case Store 为 `READY_EMPTY / rowCount=0`；本步骤没有新增历史案例假数据。

## 9. State Handling

组件遵循现有 `OverviewMode`：

| 状态 | 页面表达 |
| --- | --- |
| `REAL` | 风险地图/风险列表使用正常数据标签；历史来源按可用性过滤 |
| `DEMO` | 显示“演示数据”和时间；历史证据标注“历史案例（演示）” |
| `STALE` | 显示“更新时间较旧”；空列表显示“风险列表为空，且数据更新时间较旧” |
| `UNAVAILABLE` | 显示“风险数据暂不可用”，不误说“农场无风险” |
| loaded empty | 仅在数据加载成功且列表为空时显示“当前没有检测到需要关注的风险信号” |
| selected | 风险项 `aria-pressed=true`、边框高亮、地图显示选中上下文 |

当前演示 ViewModel 为 `DEMO` 且存在两条 Risk Signal；其余状态已在组件文案和结构上预留，但尚未接入真实 Overview API。

## 10. Validation

- TypeScript：`pnpm exec tsc -b --pretty false` **通过**。
- 本步骤 ESLint：`pnpm exec eslint src/views/overview/index.tsx src/features/overview/adapter.ts src/features/overview/components/RiskIntelligenceBoard src/types/overview.ts` **通过**。
- `git diff --check`：**通过**；Git 仅提示工作区文件的 LF/CRLF 转换警告。
- 全量 `pnpm lint`：仍有仓库既有问题（47 errors、12 warnings），主要位于 `src/utils/function.ts`、`src/views/analysis`、`src/views/dashboard/Map.tsx`、`src/views/expert`、`src/views/model`、`src/views/monitor`、`src/views/operations` 和 `src/views/weather`；本步骤涉及文件的定向 lint 没有错误。
- Runtime：在 `http://127.0.0.1:5173/farmap/overview`、1366×768、DEMO MODE 下验证 Overview、Decision Queue、Farm Health、Risk Intelligence、地图和现有诊断入口。
- Risk selection：Risk Priority list 数量为 2；点击第二条天气风险后 `aria-pressed=true`，地图提示“当前为全农场视角”，Selected Risk Summary 同步变为全农场范围。
- Existing navigation：点击既有“查看诊断”入口后进入 `/farmap/diagnosis/a12-demo`，返回 `/farmap/overview` 正常。
- Console：没有发现本步骤新增的应用异常；仍可见仓库已有的 `HydrateFallback` 提示和 Ant Design `Card bordered`、`Drawer width` 弃用提示。

## 11. Screenshot

以下截图来自本地运行页面，视口为 **1366×768**：

- Overview 顶部（Decision Queue + Farm Health）：[farmap-overview-step3-top-header-final.png](C:/Users/Administrator/AppData/Local/Temp/farmap-overview-step3-top-header-final.png)
- A-12 风险聚焦（地图 + 风险列表）：[farmap-overview-step3-risk-focus-final.png](C:/Users/Administrator/AppData/Local/Temp/farmap-overview-step3-risk-focus-final.png)
- 全农场天气风险聚焦：[farmap-overview-step3-weather-focus-final.png](C:/Users/Administrator/AppData/Local/Temp/farmap-overview-step3-weather-focus-final.png)

## 12. Commercial UX Review

| 维度 | 评分 | 评价 |
| --- | ---: | --- |
| Spatial clarity | 86/100 | 地图、风险列表、选中地块和全农场范围的关系清晰 |
| Risk clarity | 84/100 | Risk 与 Operational Signal 分开，优先级/类别/监测状态明确 |
| Map usefulness | 78/100 | Risk → Map 可用；Map → Risk 受现有 MapContainer 边界限制 |
| Information hierarchy | 85/100 | Decision Queue、Farm Health、Spatial Risk 的层级顺序成立 |
| Trust / evidence | 82/100 | Evidence 来源与 Demo 历史案例标识一致，freshness 可见 |
| Interaction quality | 80/100 | 风险项键盘可聚焦并可选择，选择后同步地图和 AI Context |
| Commercial product feel | 83/100 | 从 Dashboard 卡片平铺提升为农业空间决策工作区 |

综合评价：**83/100（Step 3 完成度）**。FarMap Overview 已形成 `Decision + Health + Spatial Risk` 的 Command Center 基础；距离成熟商业产品仍需 Recommendation、Approval、Operations 状态和真实数据新鲜度闭环。

## 13. Remaining Issues

1. 当前 MapContainer 没有地块点击回调，Map → Risk 仍未完成；需要未来以兼容方式增加 field selection contract，再补双向联动。
2. Risk Intelligence 的真实数据源尚未接入，当前运行页仍为 DEMO ViewModel；REAL/STALE/UNAVAILABLE 需要后续 API adapter 驱动。
3. 风险图层目前复用既有地块地图和选中中心，没有新增独立风险 polygon/heatmap；Overview 保持决策级地图，细粒度 GIS 仍在 Digital Farm。
4. 全量 lint 的既有 47 个错误和 12 个警告仍待独立治理，本步骤文件的定向 lint 已通过。

## 14. Go / No-Go

**GO — 可以进入 Milestone 5.1.1 Step 4 — AI Recommendation Center。**

理由：当前已经完成基本空间决策体验：`Risk → Field Context → Map Focus → Evidence/Freshness` 可被用户连续理解；Risk Intelligence 与 Decision Queue 已有清晰语义边界。Map → Risk 的技术限制已明确记录，不阻断 Recommendation Center，但未来接入真实风险图层和地块点击契约时应补齐双向联动。

本步骤到此停止，不进入 Recommendation Center、Operations Status、Copilot 深度重构、Backend、RAG、Agent、Knowledge Graph 或 Infrastructure 改造。
