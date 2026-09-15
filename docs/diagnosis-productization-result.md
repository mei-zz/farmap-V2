# Milestone 5.1.2 AI Diagnosis Productization Result

本报告记录 FarMap V2 `feature/commercial-product` 分支的 AI Diagnosis 产品化结果。范围限定在前端 Diagnosis 工作区、前端数据适配层和既有 Copilot 上下文接入；没有修改后端、Multimodal RAG、检索、证据融合、Agent Runtime、Planner、Tool Registry、数据库或 Operations API。

## 1. Reality Audit

### 运行入口

- 真实验证路由：`/farmap/diagnosis/a12-demo`
- 路由实现：`farmap-frontend-dev/src/routes/routes.tsx` 中的显式 `a12-demo` 路由映射到 `DiagnosisDetail`。
- 诊断服务：`src/features/diagnosis/service.ts` 根据 `VITE_RAG_MODE` 在 `/diagnosis/analyze` 的真实模式和前端演示模式之间切换。

### 修改前页面结构

运行态页面是一个三列诊断详情页：左侧地图和地块上下文，中间报告/证据/技术详情标签页，右侧专家复核、农事建议和相关操作。页面已经有诊断标题、风险等级、置信度、Evidence 卡片、Agent 入口和 Expert Review 入口，但业务信息与技术信息混在一起。

### 修改前发现的真实性和商业问题

- Header 的“生成报告”和更多菜单没有实际动作。
- Evidence 数量显示为检索结果总数，用户无法区分已引用证据和检索候选。
- Evidence 虽有 `claim` 字段，但没有在产品层形成 Finding → Evidence 的可读链路。
- Demo recommendations 没有 `evidence_ids`，若直接显示“有证据支持”会造成过度承诺。
- Agent Trace 默认暴露工具数、检索数和耗时，容易让 Diagnosis 变成调试控制台。
- 相关操作包含无 handler 的“生成报告”“加入历史案例库”等按钮。
- Expert Review API 存在，但 Diagnosis recommendation 没有明确的 actionId/runId 映射，不能显示虚假的“批准执行”。
- 原始 Demo 页面使用多个 fixture 直接读取，缺少 Diagnosis 专用的数据边界。

修改前运行截图：

![修改前 Diagnosis 运行态](C:/Users/Administrator/AppData/Local/Temp/farmap-diagnosis-step5-1-2-pre-1366.png)

### 已检查的现有契约

`src/features/diagnosis/contract.ts` 已经提供：

- `DiagnosisResult`：`title`、`risk_level`、`confidence`、`summary`、`alternatives`。
- `DiagnosisClaim`：`id`、`text`、`evidence_ids`。
- `DiagnosisEvidence`：多模态、来源、时间、预览、score、metadata。
- `DiagnosisRecommendation`：建议内容、优先级和 `evidence_ids`。
- `retrieval_summary` 与 `metadata`：检索、模型路由、延迟和运行元数据。

现有 Evidence modality 包含 camera、satellite、weather、soil、knowledge、historical_case、spatial。Agent 入口使用既有 `createAgentRun`，Expert Review 使用既有 `/api/expert-review/:id`，Copilot 使用既有全局 Drawer，没有新建平行聊天系统。

## 2. Product Problems Found

### 当前 P0

当前实现没有新增的 P0 商业展示阻断问题。原页面的死按钮、证据计数歧义、技术 Trace 污染和历史案例标签问题已经在本阶段处理。

### P1

1. **演示推荐缺少逐条证据引用。** 现有 Demo fixture 没有 recommendation evidence IDs，因此页面明确显示“逐条证据关联暂不可用（当前数据未提供）”。这保持了真实性，但限制了 Demo 对 Recommendation → Evidence 闭环的展示能力。
2. **Expert Review 仍是外部工作流入口。** 页面可真实调用 GET/POST Expert Review API，但没有在本阶段新增审核历史、专家意见时间线或 recommendation/action 映射。
3. **地图仍是上下文地图。** Diagnosis 会展示 A-12 地块空间上下文，但未新增地图点选、风险图层联动或空间证据的交互式定位。
4. **Diagnosis 与 Operations 的真实关联不足。** 页面可以进入既有 Operations 路由，但没有创建或伪造任务，也没有把 Demo recommendation 直接标记为已执行。

### P2

1. `DiagnosisDetail` 仍然是较大的页面容器，下一阶段可按业务边界拆分 Header、Summary、Evidence、Finding、Recommendation、Actions 和 Technical Details。
2. Evidence 卡片可以继续增加来源新鲜度、来源可用性和冲突证据的更细粒度表达。
3. 移动端可以进一步优化 Evidence 卡片、右侧动作区和 Copilot 的连续操作体验。
4. 可增加真实诊断报告导出、审核记录查看和键盘快捷操作，但必须建立在后端契约存在的前提上。

## 3. Final Diagnosis Information Architecture

最终页面采用以下业务优先层级：

```text
Diagnostic Header
  → Diagnosis Summary
  → Evidence Workspace
  → Finding 与 Evidence 对应关系
  → Recommended Actions
  → Expert Review / Agent / Operations / Copilot
  → Technical Details
```

- **Diagnostic Header**：农场、地块、诊断状态、DEMO/REAL、数据源数量、已引用证据数、检索候选数和专家复核状态。
- **Diagnosis Summary**：主诊断、严重程度、诊断置信度、影响面积、作物/物候阶段和不确定性提醒。
- **Evidence Workspace**：按监控图像、气象、土壤、农业知识、历史案例、空间证据筛选并展开 Evidence。
- **Evidence-backed Explanation**：用 Finding 与 Evidence 的关系解释结论依据，不展示隐藏 Chain-of-Thought。
- **Recommended Actions**：保留现有建议内容、优先级和证据关联可用性。
- **Decision / Workflow Actions**：提交专家复核、让 AI 分析处理方案、查看证据、查看运营任务、Ask AI。
- **Technical Details**：保留模型路由、Evidence ID、retrieval summary、工具和元数据，但置于次级标签页。

## 4. Data Contract / ViewModel

新增 `farmap-frontend-dev/src/types/diagnosis.ts`，定义前端专用 `DiagnosisViewModel`，包含：

- `mode`：`REAL | DEMO | STALE | UNAVAILABLE | EMPTY`。
- `farm`、`field`、`weather`、`map`、`images`、`inputSources`。
- `diagnosis`、`findings`、`evidence`、`recommendations`、`uncertainty`。
- `citedEvidenceCount` 与 `retrievedEvidenceCount`，避免把检索候选误写为已引用证据。
- `freshness` 和 `technical`，把新鲜度和技术元数据放入明确边界。

新增/重写 `src/features/diagnosis/adapter.ts`：

- `getDemoDiagnosisViewModel()` 将现有 Demo fixture 包装成完整 ViewModel。
- `toDiagnosisViewModel(response, fallback)` 将真实 Multimodal RAG response 映射到同一 ViewModel。
- 真实 Evidence 保留 modality、source、timestamp、score、metadata 和 claims 关联。
- 真实 recommendation 保留 `evidence_ids`；没有 IDs 时标记 `UNAVAILABLE`，不生成虚假证据。
- Demo 场景显示 6 条已引用 Evidence，同时保留 31 条检索候选结果。

UI 现在只消费 ViewModel，不再在 Diagnosis 页面直接导入 `demoDiagnosis`、`demoEvidence` 或 `demoRecommendations`。

## 5. Files Changed

本 Milestone 涉及的文件：

- `farmap-frontend-dev/src/types/diagnosis.ts`：Diagnosis ViewModel 和状态契约。
- `farmap-frontend-dev/src/features/diagnosis/adapter.ts`：Demo/REAL 适配层。
- `farmap-frontend-dev/src/views/diagnosis/Detail.tsx`：业务优先的信息层级、证据筛选、动作和状态。
- `farmap-frontend-dev/src/components/commercial/EvidenceCard.tsx`：支持 DEMO 历史案例标签。
- `farmap-frontend-dev/src/features/overview/copilot/context.ts`：增加 Diagnosis Copilot context。
- `farmap-frontend-dev/src/components/copilot/CopilotDrawer.tsx`：显示诊断上下文摘要。
- `farmap-frontend-dev/index.css`：Diagnosis Summary、Evidence、Review 错误状态和响应式样式。

没有修改 Backend、RAG、Retrieval、Evidence Fusion、Model Router、Agent Runtime、Planner、Tool Registry、Database、Milvus、Knowledge Graph、Redis、MQ 或 Spring Cloud。

## 6. Component Architecture

当前架构为：

```text
DiagnosisDetail
  ├─ DiagnosisViewModel (adapter boundary)
  ├─ FieldContextCard
  ├─ Diagnosis Summary
  ├─ EvidenceCard × N
  ├─ Finding ↔ Evidence relations
  ├─ RecommendationCard × N
  ├─ Expert Review actions
  ├─ Agent / Operations actions
  ├─ Copilot context action
  └─ Technical Details
```

已有 `FieldContextCard`、`EvidenceCard`、`RecommendationCard` 被复用。页面容器暂未机械拆成大量文件；ViewModel 边界已先建立，为后续按业务模块拆分提供稳定输入。

## 7. Diagnosis Summary

运行态 Demo Diagnosis 展示：

- 主诊断：疑似根区积水导致的营养吸收障碍。
- 严重程度：高风险。
- 诊断置信度：82%。
- 影响范围：A-12 地块，2.4 ha。
- 作物/阶段：柑橘 · 果实膨大期。
- 状态：DEMO DIAGNOSIS，专家待复核。
- 不确定性：需要农业专家确认；页面保留缺镁、根腐病和其他原因等替代解释。

首屏先回答“发生了什么、影响多大、置信度如何”，不会把模型名或检索参数置于主叙事。

## 8. Multimodal Evidence Experience

Demo 页面实际展示 6 条已引用 Evidence：

| 模态 | 页面表达 | 状态 |
| --- | --- | --- |
| 监控图像 | Camera-03 叶片黄化图像 | 已引用 |
| 气象数据 | 近 14 天累计降雨 58 mm | 已引用 |
| 土壤数据 | Soil Sensor 湿度 84% | 已引用 |
| 农业知识 | 《柑橘栽培技术规程》第 5.3 节 | 已引用 |
| 历史案例 | B-07 历史案例相似 | 历史案例（演示） |
| 空间证据 | A-12 地块 GIS 空间分析 | 已引用 |

Evidence Workspace 支持按模态筛选、展开业务详情和查看“支持结论”。历史案例明确标注“历史案例（演示）”，避免把演示内容误读为真实历史记录。Evidence ID、score 和 metadata 留在 Technical Details/适配层，不污染默认业务视图。

## 9. Evidence → Finding Linkage

真实 response 的 `claims[].evidence_ids` 会被适配为 `findings[].evidenceIds`，Evidence 卡片也会保留反向 claim 关联。Demo 则使用现有 Evidence 的 `claim` 字段生成 Finding，页面显示：

- 叶片症状符合营养吸收障碍的典型表现 → 监控图像。
- 持续降雨可能导致根区缺氧并影响养分吸收 → 气象数据。
- A-12 当前湿度高于相邻地块，支持积水风险判断 → 土壤数据。
- 物候阶段与高湿环境共同放大营养吸收异常风险 → 农业知识。

这使用户可以从结论回看依据，而不是只看到一个不可解释的 Evidence 数量。

## 10. Recommendation → Evidence

真实模式会保留后端推荐中的 `evidence_ids` 并显示已关联数量。当前 Demo recommendations 的 fixture 没有逐条 Evidence IDs，因此每条建议显示“逐条证据关联暂不可用（当前数据未提供）”。该文案是有意的真实性保护：页面不把 claim 的证据自动冒充为 recommendation 的证据，也不创建虚假的关联。

现有建议内容保持不变：排水改善、叶面补肥、持续监测。后续若后端提供 recommendation evidence IDs，适配层可以直接启用 LINKED 状态。

## 11. Approval Truth Audit

- Diagnosis 没有显示“批准执行”按钮，因为当前 contract 没有明确的 recommendation → actionId/runId → real handler 映射。
- “提交专家复核”调用既有 `submitExpertReview(diagnosisId, input)`；只有 API 成功后才改变页面状态。
- API 失败时显示“专家复核服务暂不可用，诊断状态未改变”，不会把本地状态伪装成已提交或已批准。
- 真实结果会用真实 `run_id` 获取 Expert Review 状态；Demo 使用 `demoDiagnosis.id`。

## 12. Agent / Copilot / Expert Workflow

- **Agent**：主入口改为“让 AI 分析处理方案”，复用既有 `/analysis/a12-demo` 或真实 `createAgentRun` 路径；没有修改 Agent Runtime。
- **Expert**：保留真实 Expert Review API，状态默认为待复核，失败不改变状态。
- **Operations**：入口为“查看运营任务”，只进入既有 `/operations/tasks`，没有新建假任务。
- **Copilot**：在既有 Copilot framework 增加 `DIAGNOSIS` context。上下文携带农场、地块、作物、诊断标题、严重程度、置信度、Evidence 模态、Evidence 数量和新鲜度。入口文案为“Ask AI 解释诊断”。

已验证的回链：Overview A-12 → Diagnosis A-12 → Agent / Operations；运行时路径保持一致。

## 13. REAL / DEMO / STALE / UNAVAILABLE / EMPTY

- **DEMO**：Header 显示 `DEMO DIAGNOSIS`，历史案例显示“历史案例（演示）”，Copilot 显示“演示上下文”。
- **REAL**：真实 RAG response 映射为 `REAL`，显示真实 Evidence 和 metadata；不复用 Demo 的证据计数。
- **STALE**：ViewModel 已预留状态和 freshness 字段，后端提供旧数据时可表达过期诊断。
- **UNAVAILABLE**：真实服务失败时展示服务不可用和可操作的重试/上传/演示回退入口。
- **EMPTY**：无诊断结果与服务不可用在类型层分开，避免用旧结果填充空状态。

本次运行验证的是 DEMO；没有把 DEMO 运行态宣称为真实生产诊断。

## 14. Technical Information Downgrade

- 删除 Header 中无 handler 的“生成报告”和更多菜单。
- Agent Trace 改为“运行记录（技术详情）”，默认摘要只说明结果和记录状态，不展示工具数量、检索数量和耗时。
- 模型路由、Evidence ID、metadata、retrieval summary 仍保留在“技术详情”标签页。
- 页面明确说明不展示模型内部推理，使用结构化 Evidence-backed Rationale 替代隐藏 Chain-of-Thought。

## 15. Interaction Audit

运行态验证结果：

- Evidence “气象数据 1”筛选后只显示气象 Evidence，按钮处于 pressed 状态；“已引用 6”可恢复全部。
- “打开证据工作区”切换到 Evidence 标签页。
- “Ask AI 解释诊断”打开既有 Copilot Drawer，并显示 `AI 诊断`、A-12、82% 诊断置信度和 6 条证据上下文。
- “让 AI 分析处理方案”到达 `/farmap/analysis/a12-demo`。
- “查看运营任务”到达 `/farmap/operations/tasks`。
- Overview 第一张 A-12 风险卡片的“查看诊断”回到 `/farmap/diagnosis/a12-demo`。
- 专家复核提交未用测试点击改变外部状态；代码路径只在 API 成功后更新状态。

## 16. Responsive Validation

真实浏览器尺寸验证：

| viewport | inner size | document/body width | 结果 |
| --- | --- | --- | --- |
| 1366 × 768 | 1366 × 768 | 1351 px | 无横向溢出 |
| 1440 × 900 | 1440 × 900 | 1425 px | 无横向溢出 |
| 1920 × 1080 | 1920 × 1080 | 1905 px | 无横向溢出 |
| 1024 × 768 | 1024 × 768 | 1009 px | 单列收敛，无横向溢出 |

1024px 下地图、地块上下文、Evidence 和动作区按纵向排列，页面高度增加但不出现横向滚动。

## 17. Technical Validation

- `pnpm exec tsc -b --pretty false`：通过。
- Diagnosis 相关文件定向 ESLint：通过。
- `git diff --check`：通过；仅有 Git 的 LF/CRLF 转换提示。
- `pnpm build`：通过，Vite 完成 7164 modules transform 和生产构建。
- `pnpm lint`：仓库基线仍失败，报告为 47 errors / 12 warnings，问题位于现有 `utils`、dashboard、expert、model、monitor、operations、weather 文件；本 Milestone 未触碰这些文件，也未把它们伪装成新回归。

## 18. Screenshots

以下截图均来自真实运行页面：

### Diagnosis 首屏

![Diagnosis 首屏](C:/Users/Administrator/AppData/Local/Temp/farmap-diagnosis-step5-1-2-final-1366.png)

### Evidence Workspace

![Evidence Workspace](C:/Users/Administrator/AppData/Local/Temp/farmap-diagnosis-step5-1-2-final-evidence-1366.png)

### Recommendation / Actions

![Recommendation and Actions](C:/Users/Administrator/AppData/Local/Temp/farmap-diagnosis-step5-1-2-final-actions-1366.png)

### Copilot Diagnosis Context

![Copilot Diagnosis Context](C:/Users/Administrator/AppData/Local/Temp/farmap-diagnosis-step5-1-2-final-copilot-1366.png)

### 1024px 响应式状态

![1024px Diagnosis](C:/Users/Administrator/AppData/Local/Temp/farmap-diagnosis-step5-1-2-final-1024.png)

## 19. Commercial UX Review

| 维度 | 评分 / 100 | 评价 |
| --- | ---: | --- |
| Diagnosis clarity | 88 | 主诊断、严重程度、影响范围和置信度在首屏可见 |
| Evidence clarity | 85 | 多模态分组、筛选和 Finding 关系清晰；推荐证据仍受 Demo fixture 限制 |
| Trust | 90 | DEMO、已引用/检索数量、专家复核和不可用状态表达诚实 |
| Multimodal AI expression | 88 | 图像、天气、土壤、知识、历史、空间六类证据被用户直接感知 |
| Recommendation clarity | 83 | 建议保留动作和优先级，但逐条 recommendation evidence 尚未提供 |
| Workflow integration | 78 | Agent、Expert、Operations、Copilot 入口可达，真实审批/执行映射仍待后端 |
| Information hierarchy | 86 | Business View 默认优先，技术详情降权 |
| Visual quality | 87 | 桌面三列和 1024px 单列均保持稳定视觉层次 |
| Commercial product feel | 85 | 已从模型结果页升级为可复核诊断工作区，但生产闭环仍未完全打通 |
| **Overall Diagnosis UX** | **85** | **达到前端产品化验收，尚未等同于全栈商业生产就绪** |

## 20. Remaining Limitations

### Frontend

- 页面容器仍可进一步模块化。
- 地图仍以地块上下文展示为主，未增加可编辑空间交互。
- Recommendation 证据状态依赖后端 IDs。

### Backend

- 未验证生产环境 `/diagnosis/analyze` 的完整 SLA、错误码和真实数据新鲜度。
- Expert Review 虽有 API，但没有在本阶段扩展审核历史和 action 映射。
- Diagnosis recommendation 到 Operations task 的持久化关系仍需既有后端能力支持。

### Data

- Demo 的 31 条检索候选中只有 6 条被页面作为已引用 Evidence 展示。
- Demo recommendations 没有逐条 Evidence IDs。
- 历史案例在 Demo 中明确为演示，不能作为真实农业案例统计。

### AI

- 页面展示的是结构化诊断和证据依据，不代表暴露或验证了模型隐藏推理过程。
- 模型置信度语义仍由后端 contract 定义；前端只显示 `diagnosis.confidence`，没有把 retrieval score 包装成诊断置信度。
- 历史案例学习和多模态 RAG 的生产质量需要单独的离线/在线评估，不属于本 Milestone。

### Workflow

- 当前可以进入 Agent、Expert Review 和 Operations，但尚未在 Diagnosis 中伪造审批、任务创建或执行完成。
- 真正的 Human-in-the-loop 闭环需要 recommendation/action/run 的后端映射和专家反馈写回能力。

## 21. Go / No-Go

**Milestone 5.1.2：DONE（前端 Diagnosis Productization 完成）。**

验收依据：

1. 首屏完成从模型结果到业务诊断摘要的转换。
2. Evidence Workspace 真实展示多模态证据，并支持筛选和 Finding 关联。
3. 诊断置信度、检索候选数、已引用证据数和不确定性被明确区分。
4. Recommendation、Expert Review、Agent、Operations、Copilot 入口保持真实，不新增假审批或假任务。
5. REAL/DEMO/STALE/UNAVAILABLE/EMPTY 状态已建立前端契约。
6. TypeScript、定向 ESLint、生产构建和浏览器交互验证通过。

下一阶段只建议：

**Milestone 5.1.3 — Agricultural AI Analyst / Agent Workspace Productization**

本报告完成后停止，不在本阶段进入 Agent Workspace、Agent V2、RAG V2、Knowledge Graph、Backend V2、Operations V2 或论文实验。
