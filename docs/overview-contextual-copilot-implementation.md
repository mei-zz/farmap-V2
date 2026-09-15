# Milestone 5.1.1 Step 6 Result

## 1. Reality Check

### AIContextStore

`useAIContextStore` 当前真实结构为：

`currentFarm`、`currentField`、`currentCrop`、`currentLocation`、`currentWeather`、`currentPhenology`、`selectedImages`、`selectedDevice`、`selectedDiagnosis` 和 `currentPage`。

它只提供浅合并的 `setContext(patch)` 与恢复默认演示上下文的 `resetContext()`。没有业务对象上下文字段，且浅合并会保留未显式清除的旧字段。

### CopilotStore

`useCopilotStore` 当前包含 `isOpen`、`draft`、`messages`，以及 `open`、`close`、`toggle`、`setDraft`、`addMessage`、`clearMessages`。它原来只接受文本草稿，没有结构化上下文。

### CopilotDrawer

Drawer 原本展示农场、地块、作物、物候标签和页面路径。短问题使用现有模板回复；复杂问题调用现有 `createAgentRun` 和 `toAgentContext`，没有修改 Agent、RAG 或模型服务。

### Overview 入口

- Decision Queue 的 3 个 Ask AI 入口各自拼接自然语言 prompt。
- Recommendation Center 的 3 个 Ask AI 入口另有一套证据和关系文本拼接逻辑。
- Header FarMap Copilot 只调用 `open()`，原来可能继续显示上一个业务对象的 AIContext。
- Risk Intelligence 没有 Ask AI，本步骤没有新增。
- Operations Status 只有“查看任务”，因其仍是演示任务且没有真实执行上下文，本步骤没有新增 Ask AI。

### Evidence 现状

Decision、Risk、Recommendation 组件分别维护 Evidence 来源显示逻辑，并对 `History`、`Historical Case`、`历史案例` 做局部处理。存在展示规则分散和 REAL 模式语义可能不一致的问题。

## 2. Context Problems Found

1. 不同入口分别拼接 prompt，Decision、Risk、Recommendation 的问题意图不一致。
2. Drawer 不知道用户正在讨论的是 Decision、Risk、Recommendation 还是 Operation，只能看到页面级路径和基础标签。
3. Drawer 不能展示关联风险、推荐动作、Evidence Summary 或数据新鲜度。
4. AIContextStore 的浅合并会产生 stale context：A-12 Recommendation 后打开全农场 Weather，若只更新 `currentPage`，旧的 A-12 field context 会残留。
5. Header Copilot 原来会沿用上一次业务对象上下文，通用入口和对象入口职责不清。
6. Operations Status 缺少可靠 Task/Recommendation 关系、负责人、进度和时间线，因此不能安全扩展为任务解释入口。

## 3. Context Contract

新增 `src/features/overview/copilot/context.ts`，建立 frontend-only `OverviewCopilotContext`：

```ts
type OverviewCopilotContext = {
  contextType: "FARM" | "DECISION" | "RISK" | "RECOMMENDATION" | "OPERATION";
  mode: "REAL" | "DEMO" | "STALE" | "UNAVAILABLE";
  page: string;
  farm: { id: number; name: string };
  field?: { id: string; name: string; crop?: string; area?: number };
  weather?: { summary: string; temperature: string; precipitation?: string; updatedAt: string };
  phenology?: { stage: string; progress: number };
  title: string;
  summary?: string;
  risk?: { id?: string; title: string; priority?: string; confidenceLabel?: string; affectedArea?: string };
  recommendation?: { id?: string; title: string; recommendedAction?: string; impact?: string; decisionState?: string; relatedRiskId?: string };
  operation?: { taskId?: string; title: string; status: string; source?: string };
  evidence: { labels: string[]; count?: number; historyUnavailable?: boolean };
  freshness?: { updatedAt?: string; stale?: boolean };
};
```

该类型只用于 Overview → Copilot 的前端表现层。ID 字段可以用于内部关联，但 Drawer 不渲染内部 ID、枚举、Agent Run 或 Tool Trace。

## 4. Files Changed

- `farmap-frontend-dev/src/features/overview/copilot/context.ts`
  - 新增上下文类型、Evidence 归一化、上下文到 AIContext 的映射和统一草稿构建器。
- `farmap-frontend-dev/src/store/copilot.ts`
  - 为现有 CopilotStore 增加可选结构化 `context` 与 `setContext`，没有新建全局 Store。
- `farmap-frontend-dev/src/views/overview/index.tsx`
  - Decision Queue 和 Recommendation Center 统一使用 `openOverviewCopilot`。
  - 传入 Farm、Field、Risk/Recommendation、Evidence、Freshness 和 OverviewMode。
- `farmap-frontend-dev/src/components/copilot/CopilotDrawer.tsx`
  - 增加“当前讨论”摘要；将页面路径转换为用户可读标签。
  - 显示业务对象、关联风险、建议动作、Evidence 和数据新鲜度。
  - 修复没有天气或物候时显示 `undefined%` 的问题。
- `farmap-frontend-dev/src/layout/index.tsx`
  - Header Copilot 作为通用农场入口，打开前清除业务对象上下文和字段级 AIContext。
- `farmap-frontend-dev/index.css`
  - 增加 Drawer 当前讨论区域样式。
- `docs/overview-contextual-copilot-implementation.md`
  - 本实施报告。

未修改后端、Agent Runtime、Planner、Tool Registry、RAG、Milvus、数据库、Operations API 或新路由。

## 5. Copilot Architecture

```text
Business Object
      ↓
OverviewCopilotContext
      ↓
openOverviewCopilot()
      ├─ AIContextStore（基础农场/地块/天气上下文）
      ├─ CopilotStore.context（Decision/Risk/Recommendation/Operation）
      ├─ CopilotStore.draft（统一问题意图）
      └─ CopilotDrawer（业务对象摘要）
```

页面 handler 负责把业务对象转换为 typed payload；helper 负责证据、时效和问题模板；Drawer 负责展示和沿用现有发送流程。没有第二个全局上下文 Store。

## 6. Context Behavior

### Decision

问题模板变为“为什么今天需要优先处理”，并携带农场、地块（若有）、风险摘要、影响范围、Evidence 和 Freshness。

### Risk

Contract 支持风险对象和“为什么存在风险”的问题模板，但由于当前 Risk Intelligence 没有可靠 Ask AI 入口，本步骤没有新增按钮。

### Recommendation

问题模板围绕“为什么建议这样做”，Drawer 展示 Recommendation、关联 Risk、建议动作、Evidence 和数据时效。

### Operation

Contract 支持 Operation，但当前 Operations Status 仍是 Demo task presentation，没有新增 Ask AI，也没有让 Copilot 声称负责人、进度或执行结果。

### Header Copilot

Header 保留为通用 Farm Context Copilot。打开时清理字段级上下文，Drawer 只展示农场和天气等可用基础信息，不沿用上一个 Recommendation 或 Field。

## 7. Evidence & Truthfulness

新增 `normalizeOverviewEvidenceSources()`，统一来源标签：

- `Camera` → 监控图像
- `Weather` → 气象
- `GIS` → 空间数据
- `Phenology` → 物候
- `Knowledge` → 农业知识
- DEMO 下历史案例显示为 `历史案例（演示）`
- REAL/STALE 下不把历史案例伪装成真实可用证据，并标记 `历史案例暂不可用`
- UNAVAILABLE 下不把不可用 Evidence 或 Weather 写入 Copilot AIContext

草稿会附带“当前为演示上下文”或“部分数据更新时间较旧”等信任提示。没有修改 RAG Evidence Contract。

## 8. Context Switching

已验证以下切换：

1. A-12 Decision → Drawer 显示云阳智慧果园、A-12、柑橘、今日决策、风险和 8 条 Evidence。
2. 关闭后打开 Weather Decision → Drawer 清除 A-12，回到农场级上下文，只显示天气风险和气象/空间数据。
3. 打开 A-12 Recommendation → Drawer 显示 AI 建议“排水改善”、关联风险、建议动作和 Evidence。
4. 打开 B-04 Recommendation → Drawer 显示 B-04、采收建议和物候 Evidence，没有错误标记为 Risk。
5. Header Copilot → 清理业务对象上下文，只显示通用农场上下文。

上下文清理通过每次业务入口显式覆盖字段级 AIContext、Header 入口清除字段上下文，以及 Layout 在路由切换时清除上一页面对象讨论完成。

## 9. Product Behavior

- 用户从 Decision 或 Recommendation 打开 Copilot 后，会看到“当前讨论”业务对象，而不是只看到 `/overview` 路径。
- 不同对象产生不同问题：Decision 询问优先级，Recommendation 询问农业动作依据，Risk/Operation 保留未来扩展契约。
- Copilot 继续沿用现有短问题模板回复和复杂问题 Agent Runtime；没有新增写操作。
- Demo 模式明确显示演示上下文；REAL/STALE/UNAVAILABLE 遵循对应数据边界。
- Drawer 视觉保持原有企业化布局，只增加紧凑的对象摘要区域，没有 ChatGPT Clone、紫色大渐变、模型名称或工具轨迹。

本步骤没有增加 Suggested Questions，因为当前 Drawer 不需要为此扩大结构；现有预填草稿已覆盖对象级问题意图。

## 10. Validation

- `pnpm exec tsc -b --pretty false`：通过。
- Step 6 文件定向 ESLint：通过。
- `git diff --check`：通过；仅有 Windows 行尾转换提示。
- 运行时 1366×768：通过。
- Scenario A A-12 Decision：上下文、风险、Evidence 和演示时效正确。
- Scenario B Weather：A-12 字段被清除，恢复农场级上下文。
- Scenario C A-12 Recommendation：显示 Recommendation、关联风险、建议动作和 8 条 Evidence。
- Scenario D B-04 Recommendation：显示 B-04 采收建议和物候 Evidence，没有错误风险标签。
- Header Copilot：显示通用农场上下文，不保留 B-04 Recommendation。
- Console：未发现新的应用异常；仍有既有 `HydrateFallback` 提示以及 Ant Design `Card bordered`、`Drawer width` 弃用警告。
- 全量 lint 仍存在仓库基线问题（47 errors、12 warnings），未作为本步骤修复范围。

## 11. Screenshot

- A-12 Recommendation Copilot：[`farmap-overview-step6-recommendation-copilot.png`](C:/Users/Administrator/AppData/Local/Temp/farmap-overview-step6-recommendation-copilot.png)
- Weather / Farm-wide Copilot：[`farmap-overview-step6-weather-copilot.png`](C:/Users/Administrator/AppData/Local/Temp/farmap-overview-step6-weather-copilot.png)

两张截图均为 1366×768 运行状态，展示 Drawer 的“当前上下文”和“当前讨论”层级。

## 12. Commercial UX Review

| 维度 | 评分 |
| --- | ---: |
| Context clarity | 88/100 |
| Trust / truthfulness | 90/100 |
| Workflow integration | 84/100 |
| AI usefulness | 82/100 |
| Information density | 86/100 |
| Commercial product feel | 85/100 |

Copilot 已从 Generic Chat Entry 提升为 Context-aware Agricultural Decision Copilot 的 Overview 入口。它能明确表达“正在讨论什么对象”和“依据哪些 Evidence”，但真实模型回答仍依赖现有 Agent Runtime 的后端接入质量。

## 13. Remaining Issues

- AIContextStore 仍是基础字段模型；未来跨页面对象持久化需要谨慎扩展，不能把内部业务对象全部塞入 Store。
- Risk Intelligence 还没有对象级 Ask AI；需要真实产品需求和稳定 Risk context 后再决定。
- Operations Status 暂无可靠 Recommendation → Task 关系、任务详情、负责人、进度、时间线和反馈，因此暂不提供 Operation Ask AI。
- Drawer 仍使用现有 Agent 服务的复杂度判断和模板回复，回答质量与 Evidence 展示尚未做独立评估。
- REAL 模式的 Overview adapter 和真实 Evidence 数据源仍需后续统一接入。
- Step 5 的新版 Operations Status 与旧“今日农事任务”卡片仍然并存，计划在 Step 7 UX Convergence 中收敛。

## 14. Go / No-Go

**GO**：可以进入 Milestone 5.1.1 Step 7 — UX Convergence & Final Validation。

Step 6 已完成上下文产品化、业务对象优先级、Evidence 真实性和 A-12 → Weather → B-04 清理验证。进入实时 Operations Copilot 或跨页面真实对象推理前，仍需后端提供稳定任务关系和数据契约。

本步骤已停止，没有开始 Step 7、Diagnosis 产品化、Agent、Backend、RAG、Knowledge Graph 或 Infrastructure 工作。
