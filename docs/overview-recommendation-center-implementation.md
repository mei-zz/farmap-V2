# Milestone 5.1.1 Step 4 Result

分支：`feature/commercial-product`  
阶段：`AI Recommendation Center`  
目标：在 `Decision Queue → Farm Health → Risk Intelligence` 之后，增加一个 Evidence-backed、可复核、不会伪造审批或任务执行的农业建议层。

## 1. Reality Check

### 当前 recommendation 数据结构

原有 `OverviewViewModel.recommendations` 只有 `id`、`title`、`priority`、`tone`、`detail`、`fieldId`、`evidenceIds` 和 `approvalState`，不足以表达 Overview 级的关联风险、影响范围、证据来源、更新时间和决策状态。它也没有稳定的 approval id、action id 或 review route。

本步骤将其最小扩展为：

- 推荐身份和农业处理方案：`id`、`title`、`detail`、`priority`。
- 关联上下文：`fieldId`、`fieldName`、`crop`、`fieldArea`、`relatedRiskId`、`relatedRiskTitle`、`relatedSignalId`、`relatedSignalTitle`、`relatedSignalType`。
- 可信度与证据：`evidenceIds`、`evidenceCount`、`evidenceSources`、`relatedRiskConfidenceLabel`、`freshness`。
- 业务影响：`impact`。
- 决策层状态和真实下一步：`decisionState`、`primaryAction`、`secondaryActions`。

Recommendation 本身没有被赋予一个不存在的概率值。A-12 的 `82%` 只展示为“关联风险 82% 置信度”，没有被错误地称为“建议置信度”。

### 数据来源与 Diagnosis 关系

适配层仍然使用现有 `demoRecommendations` 和 `demoInsights`，没有创建 `RecommendationDemoData`：

- `排水改善`、`叶面补肥`、`持续监测` 来自 `demoRecommendations`，通过 adapter 明确关联 `a12-leaf-anomaly`。
- `安排 B-04 采收` 使用已有 `harvest-window` Operational Signal 的地块、物候和证据字段派生，未新增虚假业务事实。
- Diagnosis 页仍使用自身 `DemoRecommendation` 结构；Overview 不直接复用其卡片，而是通过 Overview adapter 形成页面级契约。

### 旧 Recommendation UI 与重复程度

Overview 原本没有独立的 AI Recommendation 区块，只有 Decision Queue 中的 `recommendedAction` 和 Diagnosis 页侧栏的“农事建议”。因此本步骤在 Overview 新增统一 Recommendation Center，并把 Decision Queue 的动作摘要压缩为：

- A-12：`24 小时内巡查排水能力`
- B-04：`7 天内安排采收`

Recommendation Center 再展示更完整的处理方案、关联信号、影响范围和证据，避免原句重复。

### Agent、Approval、Copilot 能力

现有 Agent Workspace route 为 `/analysis/:runId`，Diagnosis route 为 `/diagnosis/:id`，Operations route 为 `/operations/tasks`。Agent service 虽然存在真实 `approveAgentAction(runId, actionId)`，但它属于 Agent Run action contract，不是 Overview recommendation approval contract。

当前没有可靠的 Overview recommendation review route、approval id/action id 或 recommendation approval handler。`CopilotStore` 支持 draft 文本，`AIContextStore` 支持 farm/field/crop/diagnosis/currentPage；没有 recommendation/risk 专用全局字段。本步骤使用显式页面 handler 填充已有 Context 与自然语言 Copilot draft，没有扩张 Store。

## 2. Recommendation Semantic Audit

### Risk-driven recommendations

| Recommendation | 关联对象 | 业务含义 |
| --- | --- | --- |
| 排水改善 | `relatedRiskId = a12-leaf-anomaly` | 针对 A-12 叶片异常和积水风险的处理方案 |
| 叶面补肥 | `relatedRiskId = a12-leaf-anomaly` | 针对叶片恢复的后续营养处理方案 |
| 持续监测 | `relatedRiskId = a12-leaf-anomaly` | 针对风险变化的 7 天监测方案 |

### Operational recommendation

`安排 B-04 采收` 使用 `relatedSignalId = harvest-window`、`relatedSignalType = OPERATIONAL`。B-04 不是风险，不计入 Risk Field/Risk Area，但它可以产生一个正常的农事处理建议。

### 无效或重复 signal

本步骤没有把风险信号再次复制成建议标题，也没有把 B-04 当作 Risk。第四条建议会保留在 ViewModel，但默认只展示 3 条高价值建议，其中包含 A-12 风险建议和 B-04 运营建议；页面明确写出“当前展示 3 条高价值建议”。

## 3. Approval Truth Audit

当前 Overview **不存在可靠的 Recommendation Approval capability**。Demo recommendations 的 `decisionState` 统一为 `PROPOSED`，界面显示为 `演示 · AI 提案`，没有“批准建议”“拒绝”“创建任务”或只修改 local state 的假按钮。

可执行主按钮只连接已经存在的真实页面：

- A-12 风险建议：`查看诊断` → `/diagnosis/a12-demo`。
- B-04 运营建议：`查看任务` → `/operations/tasks`。

这些按钮不会在 Overview 创建任务、改变审批状态或声称操作已经执行。未来只有后端提供 recommendation approval contract 后，才应把 `PENDING_APPROVAL` 映射为真实审批动作。

## 4. Files Changed

### 新增

- [`farmap-frontend-dev/src/features/overview/components/RecommendationCenter/index.tsx`](../farmap-frontend-dev/src/features/overview/components/RecommendationCenter/index.tsx)
- [`farmap-frontend-dev/src/features/overview/components/RecommendationCenter/RecommendationCard.tsx`](../farmap-frontend-dev/src/features/overview/components/RecommendationCenter/RecommendationCard.tsx)
- [`farmap-frontend-dev/src/features/overview/components/RecommendationCenter/RecommendationEvidence.tsx`](../farmap-frontend-dev/src/features/overview/components/RecommendationCenter/RecommendationEvidence.tsx)
- [`farmap-frontend-dev/src/features/overview/components/RecommendationCenter/RecommendationStatus.tsx`](../farmap-frontend-dev/src/features/overview/components/RecommendationCenter/RecommendationStatus.tsx)
- [`docs/overview-recommendation-center-implementation.md`](./overview-recommendation-center-implementation.md)

### 修改

- [`farmap-frontend-dev/src/types/overview.ts`](../farmap-frontend-dev/src/types/overview.ts)：最小扩展 `OverviewRecommendation` 契约和 `OverviewRecommendationState`。
- [`farmap-frontend-dev/src/features/overview/adapter.ts`](../farmap-frontend-dev/src/features/overview/adapter.ts)：建立 Risk-driven/Operational recommendation 映射，补充证据、影响、状态、更新时间和真实页面入口；压缩 Decision Queue 动作摘要。
- [`farmap-frontend-dev/src/views/overview/index.tsx`](../farmap-frontend-dev/src/views/overview/index.tsx)：在 Risk Intelligence 后挂载 Recommendation Center，添加真实导航和上下文 Copilot handler。
- [`farmap-frontend-dev/index.css`](../farmap-frontend-dev/index.css)：增加紧凑建议层、证据、状态、影响、按钮和响应式布局样式。

### 明确未修改

没有修改 Backend、API、Database、Agent Runtime、Planner、Tool Registry、RAG、Milvus、GIS Engine、Authentication、Operations 实现、Copilot 深层逻辑、Knowledge Graph 或基础设施，也没有新增 route、假审批、假任务或历史案例。

## 5. Data Contract Changes

数据流保持单一边界：

```text
demoRecommendations + demoInsights + existing field/signal context
        ↓
Overview adapter
        ↓
OverviewViewModel.recommendations
        ↓
OverviewPage handlers
        ↓
RecommendationCenter
```

扩展原因是让 UI 能够表达“建议是什么、关联什么、影响什么、为什么建议、当前处于什么决策状态、下一步去哪里”，而不是建立完整后端 Recommendation Domain Model。

`evidenceIds` 仍保留前端 Demo 证据标识；Overview 只展示来源、数量和 freshness，不声称已经提供真实证据详情。未来 REAL adapter 可以用同一字段接入 Diagnosis/Agent 返回的真实 recommendation。

## 6. Component Architecture

```text
OverviewPage
├─ DecisionQueue                 # action summary
├─ FarmHealthCommand             # farm aggregate state
├─ RiskIntelligenceBoard         # risk / field / map / evidence
├─ RecommendationCenter          # proposal layer
│  ├─ RecommendationCard         # compact business proposal
│  ├─ RecommendationEvidence     # source + count + demo truth
│  └─ RecommendationStatus       # proposed/review/approval label
└─ existing trend / weather / monitor / operations cards
        ↓
OverviewViewModel.recommendations
        ↓
Overview adapter
```

`RecommendationCard` 不读取 Store、Demo fixtures 或 API，不根据标题推测关系，不伪造审批。页面容器负责 navigation、AI Context 和 Copilot draft；组件只负责展示和事件回调。

## 7. Product Behavior

用户经过 Risk Intelligence 后，进入 AI 建议区：

1. 先看到完整农业处理方案，而不是重复风险标题。
2. 看到关联 A-12 风险或 B-04 物候信号、地块、作物和面积。
3. 看到 Supported by 的证据来源、数量、Demo 历史案例标识和数据时间。
4. 看到 `演示 · AI 提案`，知道它是待判断的建议，不是已批准或已执行的任务。
5. 通过一个视觉主操作进入真实 Diagnosis/Operations 页面，或使用 Ask AI 询问农业依据。

Decision Queue 负责“现在做什么”的短动作；Recommendation Center 负责“FarMap 提议的完整处理方案”；Operations 的执行状态仍留给后续 Step 5。

## 8. Evidence & Trust

- DEMO：`History`、`Historical Case` 和 `历史案例` 统一显示为 **历史案例（演示）**。
- REAL/STALE/UNAVAILABLE：历史来源会被过滤；如果原始来源包含历史案例，显示 **历史案例暂不可用**，不伪装成真实检索结果。
- 证据来源使用业务语言：监控图像、气象、空间数据、物候、农业知识。
- 当前建议更新时间来自 Diagnosis/Farm 的已有时间字段，并显示为“建议基于 … 数据生成”；STALE 时追加“更新时间较旧”。
- 关联风险置信度仅在明确标签下展示，不混淆 Recommendation Confidence。

## 9. Actions

### Primary Action

每条建议只有一个视觉主操作：

- Risk-driven A-12 建议：`查看诊断`，进入 `/diagnosis/a12-demo`。
- Operational B-04 建议：`查看任务`，进入 `/operations/tasks`。

两条路径均已在路由中存在，运行验证成功；Overview 不创建新任务。

### Ask AI

Ask AI 使用现有 CopilotStore draft 和 AIContextStore：

```text
为什么建议“检查主排水沟和根区积水情况，清理排水土埂”？
关联风险“A-12 地块叶片异常”，请结合监控图像、气象、历史案例（演示）说明农业依据、适用范围和需要注意的事项。
```

Context 会携带当前农场、A-12、柑橘、诊断和 `/overview` 页面上下文。没有新增 Copilot API 或 Store 字段。

### Approval

本步骤不提供 Approval action。`PROPOSED` 只是 Demo recommendation decision state 的诚实表达；`APPROVED`/`PENDING_APPROVAL` 枚举为未来真实 adapter 预留，不会在当前 Demo 中伪造出现。

## 10. State Handling

| 状态 | Recommendation Center 表达 |
| --- | --- |
| `REAL` | 显示正常建议状态；历史来源按真实可用性处理 |
| `DEMO` | 显示“演示建议”和“演示 · AI 提案”，历史案例带“（演示）” |
| `STALE` | 显示“建议基于较旧数据”，卡片显示较旧更新时间 |
| `UNAVAILABLE` | 显示“AI 建议暂不可用” |
| loaded empty | 显示“当前暂无新的 AI 建议”，不推断没有风险 |
| `PROPOSED` | 显示“AI 提案”状态，不提供审批执行 |
| `NEEDS_REVIEW` / `PENDING_APPROVAL` | 只显示状态标签，实际动作须等待真实 workflow |
| `APPROVED` / `REJECTED` | 仅在未来真实数据提供时显示，不由当前页面本地生成 |

当前 Demo ViewModel 为 `DEMO`，共有 4 条建议，默认展示 3 条高价值建议。

## 11. Validation

- TypeScript：`pnpm exec tsc -b --pretty false` **通过**。
- 本步骤 ESLint：`pnpm exec eslint src/views/overview/index.tsx src/features/overview/adapter.ts src/features/overview/components/RecommendationCenter src/types/overview.ts` **通过**。
- `git diff --check`：**通过**；仅有 Git 的 LF/CRLF 转换提示。
- Runtime：在 `http://127.0.0.1:5173/farmap/overview`、1366×768、DEMO MODE 下验证 Decision Queue、Farm Health、Risk Intelligence 和 Recommendation Center。
- 内容：默认显示 3 张紧凑建议卡，包含 A-12 Risk-driven recommendation 与 B-04 Operational recommendation。
- Primary actions：A-12 进入 `/farmap/diagnosis/a12-demo`；B-04 进入 `/farmap/operations/tasks`。
- Ask AI：打开现有 Copilot Drawer，draft 携带 A-12、关联风险、监控图像/气象/历史案例（演示）上下文。
- Regression：既有 Risk → Map、Decision Queue 和 Farm Health 仍可见；未改变既有路由。
- Console：没有发现本步骤新增应用异常；仍可见仓库既有 `HydrateFallback` 提示以及 Ant Design `Card bordered`、`Drawer width` 弃用提示。
- 全量 `pnpm lint`：仓库既有问题仍为 47 errors、12 warnings，主要位于 utils、analysis、dashboard、expert、model、monitor、operations 和 weather；本步骤定向 lint 已通过。

## 12. Screenshot

以下截图均来自实际本地页面，视口为 **1366×768**：

1. Recommendation Center 默认状态（Risk Intelligence 后的紧凑建议层）：[farmap-overview-step4-recommendation-default.png](C:/Users/Administrator/AppData/Local/Temp/farmap-overview-step4-recommendation-default.png)
2. Recommendation Ask AI 上下文状态（Copilot draft）：[farmap-overview-step4-recommendation-copilot.png](C:/Users/Administrator/AppData/Local/Temp/farmap-overview-step4-recommendation-copilot.png)

## 13. Commercial UX Review

| 维度 | 评分 | 评价 |
| --- | ---: | --- |
| Recommendation clarity | 87/100 | 完整处理方案与风险信号分离，动作语言清楚 |
| Evidence trust | 85/100 | 来源、数量、时间和 Demo 历史案例边界明确 |
| Actionability | 82/100 | 每条建议有一个真实详情/任务入口和 Ask AI 辅助解释 |
| Decision state clarity | 81/100 | Demo 明确显示 AI 提案；真实审批状态预留但不伪造 |
| Information hierarchy | 84/100 | 位于 Risk Intelligence 之后，形成发现→理解→建议顺序 |
| Visual density | 86/100 | 横向紧凑卡片，默认展示 3 条，未增加高告警噪声 |
| Commercial product feel | 84/100 | 从“AI 文案列表”提升为 Evidence-backed proposal layer |

综合 Step 4 评价：**84/100**。Overview 已形成 `Decision + Health + Spatial Risk + Evidence-backed Recommendation` 的农业 AI Command Center 主链路。

## 14. Remaining Issues

1. Recommendation Center 当前仍由 Demo adapter 驱动，REAL/STALE/UNAVAILABLE 需要未来真实 recommendation API 或 Diagnosis/Agent 聚合 adapter。
2. 当前没有推荐列表详情 route；超过 3 条的建议只保留在 ViewModel 中，未来需要真实 Recommendations 页面或分页 contract 后再开放完整列表。
3. Overview 没有 recommendation approval workflow；需要后端提供 approval/action contract 后，才能支持真实审批动作。
4. B-04 的“查看任务”进入真实 Operations 页面，但当前不会自动创建 B-04 任务；任务执行状态属于下一阶段。
5. 全量 lint 的既有 47 errors、12 warnings 仍需单独治理。

## 15. Go / No-Go

**GO — 可以进入 Milestone 5.1.1 Step 5 — Operations Status。**

理由：建议层已经形成可解释的业务链路：

```text
Risk / Operational Signal
        ↓
AI Recommendation
        ↓
Evidence + Freshness
        ↓
Decision State
        ↓
Real Diagnosis / Operations / Copilot Next Step
```

它不是旧 AI 文案的重复卡片：Decision Queue 使用短动作，Recommendation Center 使用完整农业方案，并且没有伪造审批或任务执行。Step 4 到此停止，不进入 Operations Status、Farm Trends 重构、Copilot V2、Agent V2、Backend API 或 Knowledge Graph 改造。
