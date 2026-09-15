# FarMap V2 Milestone 5.1.5 — Knowledge & Expert Feedback Productization

本阶段将 Knowledge & Experts 从两个占位入口升级为一个以真实契约为边界的 **Agricultural Knowledge & Expert Center**。本阶段只修改前端 Knowledge 入口、前端 ViewModel/适配层和文档，没有修改 Backend、Agent Runtime、RAG、Database 或 Operations API。

## 1. Reality Audit

当前导航入口为 `/knowledge/library`，同一导航下保留 `/knowledge/expert-review` 和 `/knowledge/cases`。此前三个入口分别是知识库占位页、Demo 不可用/正式模式旧 Expert 页面和案例库占位页。

现实页面检查结果：

- 1366×768 Demo 页面已捕获：`C:\Users\Administrator\AppData\Local\Temp\farmap-knowledge-reality-1366.png`。
- `/knowledge/library` 原来只显示“知识库”标题、RAG 预留说明和占位文案。
- Demo `/knowledge/expert-review` 原来明确显示“演示模式不可用”，不允许写入。
- `/knowledge/cases` 原来只显示案例库预留说明，没有查询、筛选或案例详情。
- 正式 Expert 旧页面混合了 `/expert/pending-cases`、`/expert/cases/{requestId}` 与 `/api/expert-review`，没有统一的 Review ViewModel。

已检查的全局设计系统为 Ant Design、FarMap 自有 CSS 变量、`.fm-page`/`.fm-page-header`/`.ant-card` 页面骨架、Sidebar、Header、FarMap Copilot Drawer。新页面复用了这些基础，不改变全局导航和 Layout。

## 2. Knowledge Truth Audit

当前后端没有 `/api/knowledge` 文档管理或知识库查询 Controller。可确认的能力来自：

- `KnowledgeEvidenceRetriever`：读取 classpath 的 `knowledge/orchard_management.txt`，支持 lexical/semantic 检索降级。
- `KnowledgeTool`：Agent 调用农业知识检索并返回 Evidence。
- `DiagnosisRagService`：把知识 Retriever 的结果写入 Diagnosis Evidence 和 retrieval summary。

因此页面只声明“诊断/Agent 可复用的知识检索能力”，不声明文档列表、来源管理、分类、标签、上传、索引完成或“模型已学习”。知识模块状态为 `LIMITED`。

## 3. Expert Review Truth Audit

真实 Expert Review contract：

| 方法 | 路径 | 真实用途 |
| --- | --- | --- |
| GET | `/api/expert-review` | 返回所有已保存复核记录 |
| GET | `/api/expert-review/{diagnosisId}` | 返回单个 diagnosisId 的复核记录 |
| POST | `/api/expert-review/{diagnosisId}` | 保存复核决策 |

服务端字段包括 `diagnosisId`、`status`、`reviewer`、`reviewedAt`、`aiDiagnosis`、`expertDiagnosis`、`evidence`、`recommendations`、`before` 和 `after`。`decision=CONFIRMED` 会归一为 `CONFIRMED`，`decision=CORRECTED` 会归一为 `CORRECTED`，其余保存为 `IN_REVIEW`。

正式页面只对真实返回的 diagnosisId 显示确认/修正动作；Demo 不请求该接口，也不显示写入动作。服务端当前 reviewer 为 `current-user`，数据库失败时存在内存 fallback，生产审计仍需后端鉴权、审计主体和持久化策略继续加固。

## 4. Historical Case Truth Audit

当前历史案例没有页面查询接口。已验证的底层基线为：

- collection：`farmap_image_vectors_new`
- vector dimension：512
- index：IVF_FLAT / L2 / nlist 128
- retriever：`READY_EMPTY`
- rowCount：0

`POST /api/historical-cases/ingest` 只接受 `CONFIRMED`、有 `caseId`、`imageUrl`、`expertDiagnosis` 和 `outcome` 的案例，并且只在执行模式下尝试写入 Milvus。页面没有声称“已有相似案例”，也没有把待确认记录当成历史案例。

## 5. Product Problems Found

### P0 — 商业展示或信任阻断

- 知识库此前是空壳入口，用户无法判断哪些知识能力真实存在。
- Expert Review 旧页面存在两套数据来源，无法形成稳定的 AI Diagnosis → Expert Judgment 证据链。
- 历史案例页面没有真实查询边界，容易被后续 Demo 文案误解为已完成案例学习。
- 已知后端 P0 仍存在：`ApprovalManager` 重复 approve 的幂等与竞态安全需要修复；Production Write 仍为 **NO-GO**。本阶段没有触碰该问题。

### P1 — 影响产品体验

- 当前没有知识文档、来源、分类、标签和索引管理 API，Knowledge Center 只能作为能力说明和入口。
- Expert Review 还缺少正式的 reviewer 身份、权限、审计日志、分页、筛选和队列优先级 contract。
- 历史案例只有 ingest 入口，没有候选查询、质量状态、入库结果和 outcome 反馈页面。
- 旧 `/expert` 与 `/expert-history` 仍保留旧工作流代码，后续需要统一到同一个 Review contract，避免维护两套专家体验。

### P2 — 优化增强

- 需要补充知识来源的更新时间、适用作物、地区、权威等级和版本。
- 需要按农场、地块、作物、风险和状态筛选复核队列。
- 需要为已确认案例提供脱敏、质量评分和历史案例候选预览。
- 需要为 Evidence 建立跨 Diagnosis、Agent Run、Review 和 Operation 的统一 trace ID。

## 6. Final Product IA

页面现在采用以下结构：

1. Header：Knowledge & Expert Center、模式边界、Ask Copilot。
2. Expert Review Queue：真实复核队列和状态。
3. Review Workspace：AI Diagnosis、Expert Judgment、Evidence/Recommendations 和正式写入动作。
4. Agricultural Knowledge：诊断知识检索、知识库浏览、文档上传与索引的真实状态。
5. Historical Cases：`READY_EMPTY`/演示空状态和入库条件。
6. Diagnosis / Agent Traceability：Diagnosis→Review、Review→Candidate、Retrieval→Evidence。
7. Technical Details：折叠显示真实 endpoint 和后端边界。

该 IA 的核心是让“知识能否被检索”“诊断是否被专家复核”“案例是否真的入库”分别可见。

## 7. Data Contract / ViewModel

新增 `src/types/knowledge-expert.ts`，定义：

- `KnowledgeCenterMode`：`DEMO | REAL | EMPTY | UNAVAILABLE | STALE`。
- `ExpertReviewStatus`：`PENDING | IN_REVIEW | CONFIRMED | CORRECTED | UNKNOWN`。
- `ExpertReviewRecord`：以 diagnosisId、status、reviewer、时间、AI 诊断、专家判断和 Evidence 为核心。
- `KnowledgeExpertViewModel`：统一承载 reviewQueue、knowledge、historicalCases、traceability 和 freshness。

UI 不直接读取原始 API response，也不直接读取 Demo diagnosis、Demo evidence 或旧 inline case 数据。

## 8. Files Changed

本阶段修改/新增：

- `farmap-frontend-dev/src/types/knowledge-expert.ts`
- `farmap-frontend-dev/src/features/knowledge-expert/adapter.ts`
- `farmap-frontend-dev/src/features/knowledge-expert/service.ts`
- `farmap-frontend-dev/src/features/knowledge-expert/components/KnowledgeExpertCenter.tsx`
- `farmap-frontend-dev/src/routes/routes.tsx`
- `farmap-frontend-dev/index.css`
- `docs/knowledge-expert-feedback-productization-result.md`

未修改 Backend、Agent Runtime、RAG、Database、Milvus、Operations、Authentication 或旧业务 API。

## 9. Component Architecture

```text
KnowledgeExpertCenter
├── Expert Review Queue
├── Review Workspace
├── Agricultural Knowledge
├── Historical Cases
├── Diagnosis / Agent Traceability
└── Technical Details
        ↓
KnowledgeExpertViewModel
        ↓
Expert Review Service + Adapter
        ↓
/api/expert-review
```

Demo 页面只使用空状态和能力边界；正式页面通过 service 获取真实 Review API。

## 10. Expert Review Queue

队列展示真实 `diagnosisId`、`status`、`reviewer` 和 `reviewedAt`。没有记录时显示“暂无真实复核记录”；接口不可用时显示“真实复核接口不可用”，不生成本地 fallback 队列。

状态颜色与服务端状态保持一致：`IN_REVIEW`、`CONFIRMED`、`CORRECTED`。没有硬编码“待审核 3 条”或虚构优先级。

## 11. Expert Review Workspace

Workspace 明确分成：

- **AI Diagnosis**：服务端保存的 AI 初始诊断。
- **Expert Judgment**：服务端保存的专家诊断/修正。
- **Evidence / Recommendations**：与复核记录关联的证据和建议。

正式模式下，真实记录可提交 `CONFIRMED` 或 `CORRECTED`；Demo 完全只读。修正输入为空时不能提交 `CORRECTED`。页面不会把“AI 诊断”直接标成专家结论。

## 12. Knowledge Library

Knowledge Library 现在把真实能力拆开：

- 诊断知识检索：`AVAILABLE`，来源为 `KnowledgeEvidenceRetriever / KnowledgeTool`。
- 知识库浏览：`UNAVAILABLE`，没有 `/api/knowledge` 查询契约。
- 文档上传与索引：`UNAVAILABLE`，没有安全写入契约。

不显示上传按钮、索引进度、已学习数量或虚构文档卡片。

## 13. Historical Cases / Candidates

真实模式显示“暂无真实已验证历史案例”，并标记 `READY_EMPTY`、`rowCount=0`。Demo 模式显示“历史案例（演示）”，同时强调不代表真实已验证案例。

页面说明只有 CONFIRMED 专家复核且图像、专家诊断、outcome 齐全，才满足 Historical Case Candidate 条件。没有“3 个相似案例”、固定 B-07 案例或假入库状态。

## 14. Diagnosis / Agent Traceability

页面提供三条可理解的追溯关系：

1. Diagnosis 通过 `diagnosisId` 进入 Expert Review。
2. CONFIRMED Review 满足历史案例候选的最小条件，但不等于已经入库。
3. Knowledge/Historical Retriever 结果进入 Diagnosis Evidence；内部 Retriever 不等同于 Knowledge Library 文档清单。

保留一个真实存在的 AI Diagnosis 演示入口；没有生成无效的动态诊断 URL。

## 15. Available Actions Truth Audit

| 动作 | Demo | 正式模式 | 依据 |
| --- | --- | --- | --- |
| 查看真实复核队列 | 禁止请求 | 允许 GET | `/api/expert-review` |
| 查看单条复核 | 无记录可选 | 允许真实记录 | `/api/expert-review/{diagnosisId}` |
| 确认 AI 诊断 | 禁止 | 允许真实记录 | POST decision=CONFIRMED |
| 提交专家修正 | 禁止 | 允许真实记录且文本非空 | POST decision=CORRECTED |
| 上传知识文档 | 不显示 | 不显示 | 无 API |
| 手动索引知识 | 不显示 | 不显示 | 无安全 contract |
| 加入历史案例 | 不显示 | 不显示 | 当前仅有后台 ingest，页面无候选 contract |

## 16. Contextual Copilot

Ask Copilot 会通过现有 Copilot Drawer 打开 `DIAGNOSIS` context，携带当前诊断 ID、复核状态、Evidence 是否存在和历史案例不可用提示。它不会承诺知识库搜索或专家结论，也不会在 Demo 中绕过只读边界。

## 17. DEMO / REAL / EMPTY / UNAVAILABLE / STALE

| 模式 | 页面语义 |
| --- | --- |
| DEMO | `DEMO · READ ONLY`；不请求、不写入真实复核；历史案例标为“历史案例（演示）” |
| REAL | 读取 `/api/expert-review`；只显示服务端返回的真实记录 |
| EMPTY | 接口成功但记录数为 0，显示“暂无真实复核记录” |
| UNAVAILABLE | 接口失败，显示不可用原因，不使用本地替代数据 |
| STALE | ViewModel 预留；当前后端没有更新时间策略，未伪造 stale 数据 |

## 18. Technical Information Downgrade

默认业务视图隐藏数据库表名、SQL、向量维度、模型名和内部 tool trace。Technical Details 折叠区只保留用户理解边界所需的 endpoint、Retriever、READY_EMPTY 和 ingest 条件。页面标题、状态和下一步动作优先于技术实现细节。

## 19. Interaction Audit

- 队列项可选择并高亮，Workspace 只跟随真实记录。
- 确认和修正按钮有真实 POST handler，并在成功/失败后显示反馈。
- Demo 没有写入按钮，避免“看起来可提交但实际失败”。
- Knowledge、Historical、Technical Details 没有死的上传、索引、学习或入库按钮。
- Ask Copilot 有现有 Drawer handler，AI Diagnosis 入口有现有路由 handler。

## 20. Responsive Validation

浏览器实测：

- 1366×768：`scrollWidth=1351`，`clientWidth=1351`，无横向溢出。
- 1024×768：`scrollWidth=1009`，`clientWidth=1009`，无横向溢出。
- 1024 宽度下两列布局仍可读；850 以下自动切换单列；560 以下 Review Workspace 内部切换单列。

截图：

- `C:\Users\Administrator\AppData\Local\Temp\farmap-knowledge-center-1366.png`
- `C:\Users\Administrator\AppData\Local\Temp\farmap-knowledge-center-1024.png`

## 21. Technical Validation

- `pnpm exec tsc -b --pretty false`：通过。
- `pnpm exec eslint src/features/knowledge-expert src/types/knowledge-expert.ts src/routes/routes.tsx`：通过。
- `pnpm build`：通过；只有已有的大 chunk warning，没有新增编译错误。
- 浏览器 Demo 1366/1024：通过，无横向溢出。
- `git diff --check`：应在提交前执行；本阶段没有提交 commit。

## 22. Screenshots

本阶段捕获：

1. 原 Knowledge 占位页：`C:\Users\Administrator\AppData\Local\Temp\farmap-knowledge-reality-1366.png`。
2. 新 Knowledge & Expert Center 1366：`C:\Users\Administrator\AppData\Local\Temp\farmap-knowledge-center-1366.png`。
3. 新 Knowledge & Expert Center 1024：`C:\Users\Administrator\AppData\Local\Temp\farmap-knowledge-center-1024.png`。

新页面首屏能看到产品标题、Demo/Real 边界、专家复核队列、Review Workspace、Knowledge 状态和 Historical 空状态；下方仍可滚动查看追溯与 Technical Details。

## 23. Commercial UX Review

| 维度 | 评价 |
| --- | --- |
| 产品定位 | 8/10：明确农业知识与专家反馈中心，但 Knowledge 管理 API 尚未形成 |
| 信息架构 | 8/10：Review、Knowledge、Historical、Traceability 分区清晰 |
| 首屏价值 | 7/10：真实空状态可信，但真实记录为空时业务密度有限 |
| 决策可追溯 | 8/10：Diagnosis→Review→Candidate→Evidence 关系可读 |
| AI/专家边界 | 9/10：AI Diagnosis 与 Expert Judgment 明确分列 |
| 证据可信度 | 8/10：不伪造文档或历史案例；细粒度 Evidence contract 仍需统一 |
| 行动闭环 | 6/10：复核可写入；案例候选和运营后续仍没有页面闭环 |
| Demo/Real 信任 | 9/10：Demo 只读且明确标识 |
| 响应式体验 | 8/10：1366/1024 实测无横向溢出 |
| 商业可销售性 | 6/10：适合作为可信产品骨架，尚未达到 Knowledge Operations 完整商业交付 |

综合评价：**72/100，Frontend Productization GO / Production Workflow NO-GO**。页面已经从占位入口进入可解释的商业产品骨架，但后端 Knowledge/Review/Historical contract 仍限制可销售功能深度。

## 24. Remaining Limitations

### Frontend

- 旧 `/expert` 与 `/expert-history` 仍存在，尚未完全收敛到新 Center。
- Review Queue 暂无分页、筛选、优先级和批量处理。

### Backend

- Knowledge Library 没有文档、来源、分类、标签、索引和版本 API。
- Expert Review 缺少明确的权限、审计主体、乐观锁/版本和 review history API。
- 历史案例没有查询、候选、质量和入库结果 API。

### Data

- 当前 Historical Store 为 READY_EMPTY / rowCount=0。
- Review 的 Evidence 和 Diagnosis JSON 仍以宽泛字段保存，前端不能对农业语义做可靠筛选。

### Knowledge

- classpath 知识资源可被 RAG 调用，但不能在产品中管理或审计文档版本。

### Expert Workflow

- 复核记录可以保存，但没有分派、锁定、评论、撤回、审核历史和 SLA。

### AI

- Multimodal RAG、Agent Tool Calling 和 Evidence Grounding 已在 Diagnosis/Agent 产品链中体现；Knowledge Center 本身只能诚实展示它们的来源边界。
- Historical Case Learning 在数据为空时只能显示 READY_EMPTY，不能作为商业卖点声称已有学习效果。

### Infrastructure

- Milvus collection 的可用性和 rowCount 需要生产健康检查 API；页面目前使用已验证的项目基线。
- Production Write 仍需在 ApprovalManager 幂等、权限、审计和失败恢复完成后再开放。

重复的 P0：`ApprovalManager` duplicate approve 仍需处理；生产写入链路保持 **NO-GO**。

## 25. Go / No-Go

### 本阶段结论

- **Knowledge & Expert Center 前端展示：GO**。真实边界、空状态、Expert Review 读写入口和 Copilot 上下文已经产品化。
- **Knowledge Library 管理能力：NO-GO**。缺少后端知识文档管理 contract。
- **Historical Case Learning 商业承诺：NO-GO**。当前 `READY_EMPTY / rowCount=0`，且没有查询/候选页面 API。
- **Production Expert Workflow：CONDITIONAL GO**。只有在正式鉴权、审计、幂等和持久化验证完成后，才可作为生产复核流程。
- **Production Write：NO-GO**。沿用 Milestone 5.1.4 的后端 P0 结论。

### 下一阶段建议

如果继续商业产品化，下一阶段只建议做 **Milestone 5.1.6 — Knowledge & Expert Contract Hardening**：先定义知识文档、来源、版本、Review 审计和 Historical Candidate 查询 contract，再据此扩展页面。不要在当前 `READY_EMPTY` 数据基础上加入固定案例、上传假进度或“模型已学习”提示。
