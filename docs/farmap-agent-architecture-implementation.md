# FarMap 农事指导 Agent 架构与源码实现报告

本文是 P0 工具 RPC 改造前的源码快照。改造后的工具和审批接口请参见 [Pi Runtime 实现说明](pi-agent-runtime.md)。下文的旧快照和行号保留用于对照。

核对日期为 2026 年 11 月 26 日（Asia/Shanghai）。基线是本地 Git `fcf5652` 上的当前工作区，包含尚未提交的 Pi Runtime、成熟度和呈现层代码。下文行号以这份工作区为准，首尾均包含。源码链接指向区间起始行，区间旁给出方法名或职责，方便后续重构后重新定位。

## 1. 总体架构

FarMap 农事指导 Agent 由 Java 业务编排层、Node.js Pi Agent 执行层和独立业务数据层组成。Java 负责接收图片及文本、识别意图、解析用户与农场上下文、决定执行分支并组织可信证据。对于需要生成自然语言的分支，Java 将近期消息和本轮资料交给 Node worker，后者使用 `@earendil-works/pi-agent-core` 运行模型和工具循环。对话历史、显式偏好、诊断、视觉报告、农事任务与执行记录分别保存，界面以 SSE 接收结果。

| 执行分支 | 典型请求 | 谁决定与执行 |
| --- | --- | --- |
| Java 业务查询或规则回答 | 当前有哪些农场、当前天气如何、查询本周任务、李树现处于什么物候期 | Java 路由校验意图和作用域，查业务服务或按日历确定结果，再发结论与业务组件。 |
| Pi 生成式对话 | 结合当前地块和天气给出管理建议、解释褐腐病特征、追问历史分析依据 | Java 先准备已授权的事实及 RAG 结果。Pi 管理消息状态、模型调用、工具选择与结果回传，生成基于证据的答复。 |
| Java 专项分析与工作流 | 上传果实图判断成熟度、分析指定摄像头、生成采收草稿、批准农事任务 | Java 调用视觉、诊断或任务服务。诊断及农事工作流有各自的运行状态，持久化和写入由后端业务服务执行。 |

一次登录态对话的主链路如下。

```text
用户文本 / 页面对象 / 已上传的图片引用
  → POST /api/copilot/chat（认证、SSE）
  → CopilotRouteService（去重、意图、上下文校验、上下文版本）
  → 直接回答 / 查询业务数据 / 专项分析 / 生成式对话
  → 生成式分支：事实构建、知识检索 → Java JSONL bridge → Pi Agent Loop
  → SSE 执行状态、正文、业务组件、补充分析和后续操作
  → 对话历史与业务数据分别持久化
```

`/api/copilot/answer` 另提供 JSON 回答兼容入口，启用 Pi 时也可访问生成循环，使用独立的临时 worker 会话键。访客入口是独立的 `GuestCopilotController`，对输入范围和速率做限制，Pi 的访客实例在请求完成时从 worker 回收。登录会话与访客会话的恢复及数据访问能力应分别描述。

## 2. Pi Runtime 的真实接入

### 2.1 依赖与进程边界

FarMap 锁定 `@earendil-works/pi-agent-core` 和 `@earendil-works/pi-ai` 1.5.2，Node 最低版本为 22.19.0。Pi Agent Core 是 Pi 项目中提供有状态 Agent Loop 的库，Pi Coding Agent CLI 有单独的包。FarMap 的使用方式是从 Agent Core 导入 `Agent`，由 Spring 启动应用内的私有 Node worker。上游 API 和循环可按锁定版本查看。[Pi Agent Core v1.5.2 文档](https://github.com/earendil-works/pi/blob/v1.5.2/packages/agent/README.md) · [Pi v1.5.2 Agent Loop 源码](https://github.com/earendil-works/pi/blob/v1.5.2/packages/agent/src/agent-loop.ts)

Java 通过 stdin 写入带请求 ID 的 JSON 行，字段包括会话键、上下文版本、农场／地块／作物／物候范围、用户输入、系统提示词、历史和已授权工具快照。worker 通过 stdout 返回 `delta`、`event`、`done` 或 `error`。Java 按请求 ID 分发，筛选事件字段并转为 SSE。worker 启动采用环境变量白名单，有超时、子进程退出清理和延迟重建。正文和过程事件分流，界面获得工具名、调用状态及简要数量，内部推理正文不随过程事件输出。

### 2.2 消息、工具与控制

- **循环**。worker 为会话建立 `Agent`，注册本轮工具和 `streamFn`，调用 `prompt()`。模型返回工具调用后，由原生 Pi 循环执行工具、形成 `toolResult` 并驱动下一次模型请求。Java 不需要自行解析模型的工具调用再拼接下一轮英文协议。
- **控制**。登录态 `agent-control` 按用户、会话和当前版本校验，向 worker 转发 `abort`、`continue`、`steer` 和 `followUp`。worker 支持先于开始事件到达的中止预留，继续时清理中止或错误的尾消息以及未配对工具调用。页面已连接停止、继续执行和「加入队列」，分别使用 `abort`、`continue`、`followUp`。`steer` 暴露在后台控制接口。排队请求的确认状态记录在会话活动日志中。
- **配额**。worker 最多保留 64 个实例，闲置约 30 分钟回收。单次有 8 个模型轮次、16 次工具调用限额和 120 秒限时，活动执行可接收至多 8 条排队消息。工具顺序执行。Java Bridge 的默认等待时间由 Spring 配置覆盖为 125 秒。
- **重用与恢复**。登录流使用 `userId:conversationId` 作为键。范围一致且已保存历史能对齐上一轮用户与助理消息时，复用 Pi 原生历史和工具结果，同时换入新的只读资料。范围变化、历史无法对齐或 worker 重启时，用 Java 提供的近期可见消息重新初始化。Pi 工具轨迹、队列与活动中止点属于进程内状态，重启后可恢复的是数据库里的可见会话。
- **裁剪**。`transformContext` 保留系统消息与以用户消息开头的完整后缀，目标预算为 40 条非系统消息或约 100,000 个 JSON 字符，优先按用户轮切分。最后一个完整片段存在超过目标预算的可能。`convertToLlm` 只保留 `system`、`user`、`assistant`、`toolResult`，本轮服务端快照在最近用户消息之前注入。当前策略的单位是序列化字符和消息数量，后续可加入 token 预算。

### 2.3 模型配置与边界

项目的本地启动脚本发现 `opencode_apikey` 且未显式设置开关时，启用 OpenCode Go。`farmap-models.yml` 中的默认模型名是 `deepseek-v4.1-flash`。开关开启时，Pi 的 OpenAI-compatible 流、Java `ModelGateway` 调用和意图模型池使用该模型，Java 模型路由不给这条路径配置跨模型回退。Pi 对话侧声明文本输入并禁用 thinking。视觉分析从 Java `IMAGE_UNDERSTANDING` 入口发送图像请求，实际视觉结果取决于模型端是否接受图像请求及能否返回合规结构，配置名和集成测试无法单独证明部署端视觉能力。知识文档向量、CLIP 图片向量与重排是独立的检索模型调用。原来的百炼路径保留为未启用 OpenCode 时的配置分支。

## 3. 意图识别与作用域解析

### 3.1 规则与 LLM 的两级判定

1. `CopilotQueryNormalizer` 统一输入中的换行、标点和识别用文本。`CopilotIntentRouter.classify` 按优先级检查越权／明显提示注入、显式长期记忆操作、农场目录、任务或建议查询、采收计划及成熟度，再比较导航、异常诊断、知识解释、地块状态等信号。强冲突先澄清。典型区分是「褐腐病是什么样」进入知识与参考图链，「分析我拍的叶片」进入现场诊断链。
2. `classifyHybrid` 根据是否有用户上传图片、当前是否指向历史视觉报告等条件修正规则。规则命中的农场目录和专业任务查询直接返回。支持 `RULE_ONLY`、`SHADOW` 和 `HYBRID`。仓库配置默认启用影子模式，空的显式模式继承该开关。影子模式异步采样，实际路由采用规则结果。混合模式允许合格的安全分类覆盖规则的长尾结果。
3. `LlmIntentClassifier` 构造限定意图枚举与 JSON 输出格式的提示词，使用近期摘要、页面上下文及规则判定作为线索，校验置信度及槽位，并在时间预算内处理候选模型。默认接管阈值 0.70，升级阈值 0.55，模型分类总超时 1800 ms。OpenCode 模式中候选池是一个主模型，强模型池为空。失败、超时、输出非法或低置信时保留规则结果。
4. 导航、显式记忆、业务动作及成熟度等受保护意图由规则授予。模型给出的意图和 `IntentSlots` 作为候选意见与诊断元数据记录。授权农场、地块、诊断、任务和运行 ID 仍由 Java 解析与校验。待澄清上下文使「第一条建议」「确认」等简短追问能与先前请求关联，并继续进入权限和业务状态检查。

### 3.2 上下文裁决

`CopilotContextResolver` 合并请求明示对象、页面作用域、会话活动对象并从文本抽取受支持的 ID。合并之后检查用户能否访问农场、地块与农场关系、诊断与地块关系、建议与诊断关系、任务、结果和运行的关联。可能结果包括 `RESOLVED`、`CLARIFICATION_REQUIRED`、`STALE`、`FORBIDDEN`、`NOT_FOUND`、`UNSUPPORTED`。路由分别回传意图、能力名和被允许的下一步。`FarmDirectory` 是账户级查询，其有效范围是用户有权访问的农场集合。普通农场或地块问题仍按当前对象进入数据服务。

## 4. 上下文与记忆

| 数据 | 获取与注入方式 | 生命周期 |
| --- | --- | --- |
| 页面上下文 | 前端提交选中的农场、地块、诊断、任务、日期及摄像头或报告引用。后端复核归属，浏览器传值只作定位线索。 | 随当前页面或用户选择更新。 |
| 短期会话记忆 | 记录近期可见用户和助理消息、活动对象、澄清问题、会话摘要字段及版本。意图模型用最近 4 条可见消息的压缩片段，总计最多 512 字符。会话视图的组装上限为 10 条／4000 字符，Pi 另外管理原生消息与工具状态。 | 数据库会话 TTL 为 30 天，续期和对话恢复走仓储。worker 状态有独立的内存配额。 |
| 用户、农场、地块长期偏好 | 对「记住」「忘掉」「你还记得」等显式请求，解析固定偏好类型和作用域；按用户与当前对象授权查询 ACTIVE 记录，最多返回 20 条。Pi 注入结构化偏好做个性化。 | 持久化至 `copilot_long_term_memories`，同键重复写做语义去重，变更以替代记录保留关系，撤销会更新状态。 |
| 地块事实与农业状态 | 从 `FieldFactsService` 和相关业务服务读取地块登记、作物、物候、气象、分析报告和任务。地块事实与显式地块偏好分别取数。 | 由各业务仓储、报告 ID 或任务 ID 独立管理，可跨会话查询。 |

`CopilotConversationService.begin/commit` 负责读取所属会话、按 `clientMessageId` 识别重复请求并检查上下文版本。JDBC 仓储事务写入会话快照与回合，版本冲突时拒绝旧写入。路由占位与可见答案分开，服务端回答完成后回写文本和组件相关元数据。摘要字段当前主要保存范围和意图等简要状态，将来可以添加事实锚定的长对话语义摘要。

长期记忆的读取依据是 `FIELD`、`FARM`、`USER` 作用域和用户归属。写入路径过滤敏感数据及安全覆盖式指令，结构化范围覆盖服务识别的语言、回答粒度及农事约束等。偏好不改变作物和地块事实。跨用户隔离由仓储查询和作用域校验共同约束。

## 5. 农事知识推理、RAG 与工具

### 5.1 农业事实与物候推理

`FieldFactsService` 从授权地块读取作物和登记阶段。`CropResolver` 将中英文名称、品种或别名归到作物编码。`PhenologyService` 用 Asia/Shanghai 服务端日期对季节区间进行匹配，返回日期推算、登记阶段、实际采用阶段、前后阶段、距下一阶段天数和两种阶段是否冲突。当前有李、柑橘及不同稻作季节的规则入口。无可靠地块时，可用明确作物的通用物候历，并标注依据级别。单纯询问物候阶段时可直接返回已计算结论。涉及物候和操作建议的复合问题将校验过的阶段作为事实输入，以目标阶段检索知识，生成相应农事建议。

回答来源按实际证据状态标记为 `L1_FIELD_PRIVATE`、`L2_PUBLIC_AGRICULTURE`、`L3_MODEL_GENERAL` 或 `BLOCKED_NO_GROUNDING`。通用兜底有配置开关。对缺少私有测量等事实的问题，生成提示词要求明确缺口，使用通用建议时展示其来源范围。

### 5.2 文档入库与在线召回

- **入库**。导入服务对来源和权限做校验，按内容哈希归并重复导入，抽取文本、分块、标注作物及来源。MySQL 保存来源、文档、版本和分块关联，向量侧维护索引。激活后当前可见知识才进入召回。结构化农事知识另有作物、阶段和信号查表，用于可判定的农事规则。
- **查询**。`KnowledgeQueryRewriter` 清理问法、处理指代和目标阶段，根据查询类别调整 Top-K。Copilot 将作物、物候、当前提问、可见近期对话和授权对象送入 `KnowledgeEvidenceRetriever`。无已知作物时使用无作物标签范围。有可判定的结构化规则时可提前返回，否则进入召回。
- **混合检索**。已配置的知识向量库结合 Milvus 密集召回与 MySQL 可见分块构建的 BM25，默认用 RRF 汇总两路排序，可配加权融合。配置基线的 RRF 常数为 60。按作物、当前激活版本与调用者可见性过滤，融合后可选重排。词法和语义兼容路径与生产向量库是不同配置/装配分支。返回状态可包括 `READY`、`READY_EMPTY`、`DEGRADED`、`UNAVAILABLE`。
- **排序公式**。对同一分块，将各路的 `weight / (k + rank)` 相加，未召回该分块的路径贡献为零。配置基线中 `k = 60`，两路权重均为 1，各路候选预算为 30。
- **溯源**。在线结果附来源、文档、版本、分块等信息。Copilot 将内容压入本轮只读知识资料，并产生知识数量事件。参考图由 `CopilotMediaPolicy` 和参考图查找服务决定。参考图用途是说明病害或生育阶段的外观，用户上传现场图用于诊断或视觉分析。

### 5.3 多模态诊断链

`DiagnosisRagService` 根据启用模态运行结构化检索器，收集视觉、气象、历史案例、知识、空间和土壤等数据的状态。视觉检索可尝试模型观测、CLIP 相似图片及上传图索引。跨模态证据由 `EvidenceFusionService` 按可信度和模态权重融合，和文档召回阶段的 RRF 是两次不同的排序。`GroundedDiagnosisGenerator` 用证据 ID 约束结构化结论，校验输出并支持一次修复，必要时由诊断服务返回证据支持的模板结果。若创建后续农事工作流，Java 读取已保存的诊断、建议与证据快照，确保建议与证据 ID 仍有可校验关联。

### 5.4 Pi 实际注册的工具

| 工具 | 数据来自哪里 | 注册条件 |
| --- | --- | --- |
| `read_field_context` | Java 当前轮的地块授权事实、日期及计算物候等 | 生成式对话默认注册。 |
| `read_agricultural_knowledge` | Java 已按当前问题检索的知识条目、状态和来源 | 生成式对话默认注册。 |
| `read_monthly_guidance` | 当前授权农场的登记月度农事指导 | 请求指定月份且有可用指导。 |
| `read_current_weather` | 本轮 Java 气象服务已取回的观测与预报 | 当前生成式上下文中有天气数据。 |
| `read_user_preferences` | 显式登记的用户、农场、地块偏好 | 有符合范围的 ACTIVE 偏好。 |
| `read_workflow_status` | 授权后的 Java 工作流状态与审批状态 | 当前范围有有效业务 run ID。 |
| `read_previous_visual_analysis` | 后端授权读取的历史视觉报告摘要 | 页面提供报告 ID，报告和解析后农场范围相符。 |

这些工具在 Node 侧当前是 **无参数的只读快照读取器**，`execute()` 返回 Java 本轮预取的数据。模型可以选择调用及顺序，无法借工具参数切换对象或发起新查询。Java 同时把这些资料放进模型上下文，所以用户看到的「检索到几条知识」代表后端召回数量，Pi 能否选择调用读取工具由独立的运行事件反映。天气、成熟度和任务列表也会产生业务服务的工具式状态事件，其执行源可以是 Java。前端有通用网页搜索活动的呈现分支，当前 Pi 工具白名单尚未接通网页搜索提供方。

## 6. 专项视觉分析与农事工作流

### 6.1 成熟度、物候与长势

`FARM_MATURITY_SCAN` 路由覆盖四个作用域 `FARM`、`FIELD`、`CAMERA`、`IMAGE`，支持成熟度、物候、长势三类独立提示词。摄像头目标从当前农场内的 ID、名称、地块名称或页面选择中定位，出现重名或多解时澄清。整场视图按业务登记的农场、地块、监测区域和摄像头关联聚合，并在异步采样过程中发送实际采集与识别进度。

摄像头图片可能来自远端抓图、最近的浏览器画面上传或用户上传的图片。分析服务对请求作用域和绑定关系做校验。配额、离线、截图能力缺失、未绑定或画面不清会形成独立状态，分析结果保留 `UNKNOWN`。可用画面由 `IMAGE_UNDERSTANDING` 结构化提示词解读，校验枚举、外观证据与图像质量标志，再生成区域阶段、一致或混合结果和采收复核建议。这里是视觉模型提示词方案。报告的可推广范围是摄像头所见；颜色与空间几何来自独立地图数据，未取得有效画面的区域按无有效数据展示。演示地块拓扑有独立开关与标记。

每次分析报告以 `runId`、`farmId`、`ownerId`、观测时间和报告 JSON 保存在 `maturity_analysis_runs`。全农场模式可以展开地图、区域排序、历史报告和摄像头证据，单摄像头或上传图片优先用紧凑卡片。历史入口读取已保存报告。当前历史／趋势路由会先定位最近可见报告再打开历史视图，连续时间序列的统计比较仍需单独设计和验证。

### 6.2 规划、证据门槛、审批与反馈

1. `AgentRuntime` 是 Java 农事业务执行器，对应独立的 `/api/agent/runs`。它保存可重放的业务运行事件，跟踪计划、工具结果和审批状态。`AgentPlanner` 按请求类型产生带依赖的计划。已有诊断时复用该诊断证据，直接任务走授权地块事实，其他分支可调用视觉、历史、知识和多模态检索。可选的 Planner 模型调用默认关闭，现有代码的实际执行计划仍由 Java 构造。
2. `ToolRegistry` 和 `ToolExecutor` 负责 Java 工具查找、输入执行及结果标准化。现有业务工具名包括 `field-context`、`weather`、`camera`、`gis`、`multimodal-rag`、`historical-case`、`knowledge`、`diagnosis-evidence`、`task`。读取失败可有界重试或切换同能力族替代，重规划受步骤、依赖和证据充分性约束。
3. `EvidenceSufficiencyEvaluator` 校验动作引用的证据是本次可信证据的子集、建议引用有交集，且依赖状态允许往下走。满足门槛后产生待人工审批动作。`ApprovalManager` 与 JDBC 审批仓储确保并发审批、执行认领和已执行动作的确认。任务业务服务用动作标识实现幂等写入，状态、排期和执行反馈落在业务数据层。
4. 视觉报告的采收任务草稿走独立的 `OperationsPlanService`。服务从已保存的分析报告校验可用观测、地块、权限及时间，创建可编辑草稿，用户显式确认后写任务。日历、任务统计、列表和执行记录从实时业务查询服务取得，聊天卡根据业务 ID 读写最新状态。

## 7. SSE、Agent 呈现层与数据出口

`CopilotController` 发出 `run.started`、`route`、`status`、`agent.runtime`、工具与知识汇总事件，并用 `content.delta` 和 `done` 输出正文与结束状态。服务端将结论、补充分析、跟进项表达为 `presentation.summary`、`presentation.analysis`、`presentation.analysis.delta`、`presentation.followups`。业务卡对应 `ui.block.created`／`ui.block.updated` 等结构化事件，视觉工作区由 `analysis.workspace` 引用业务报告。

前端按本轮消息维护流式状态，先显示执行信息，再显示结论和相关业务组件，后续文字出现在卡下方。提前收到的业务结果先缓存，旧式流等待正文结束再呈现卡片。知识库在过程区只呈现数量摘要，病害参考图和现场图片证据有独立媒体入口。报告、任务和执行记录由各自业务服务持久化。对话元数据用于恢复顺序与关联组件，用户跨会话时仍可按业务 ID 读取对应数据。

## 8. 论文可用表述和进一步开发入口

### 论文系统实现段落

FarMap 面向农场中的农事咨询与作业决策，将自然语言交互、农业事实获取与业务执行组织为三个独立层次。首先，应用在服务端依据规则、可选的大语言模型分类结果及用户当前操作对象识别意图，按农场、地块和业务实体检查数据权限。随后，系统在生成式对话分支将结构化农业事实、近期会话和检索证据交给基于 Pi Agent Core 的执行进程，由其完成消息维护、多轮推理及只读工具调用。农业知识按作物及物候阶段约束检索，结合结构化规则、稀疏召回、密集向量召回和证据来源返回可追溯结果。对成熟度分析与农事任务等业务请求，系统通过专用服务产生带业务标识的结构化结果，并以事件流呈现分析进度、结论及后续交互。会话、长期偏好与业务记录独立持久化，具有写入影响的任务由服务端校验和人工确认后执行。

### 下一轮改造建议

| 优先级 | 拟调整的位置 | 可以单独验证的目标 |
| --- | --- | --- |
| P0 | `CopilotController.streamChat/runPiAnswer`、`CopilotRouteService`、Pi worker 工具注册 | 抽出统一的业务路由与注册契约，记录每轮实际使用 Java 直达、Pi 工具还是专项工作流。先保持已有授权行为，减少 Controller 中的重复分支。 |
| P0 | `CopilotContextAssembler`、`CopilotContextResolver`、worker `trimContext` | 为当前可信事实、历史指代和偏好定义明确的 token 预算，保留 tool call/result 配对，做跨农场、跨地块、跨物候与重启恢复回归。 |
| P1 | `CopilotIntentRouter.classifyHybrid`、`LlmIntentClassifier`、影子采样 | 针对本项目任务建立可复用的语料和混淆矩阵，分开记录规则、影子及混合结果，评估长尾意图、槽位准确率、澄清率和延迟。 |
| P1 | `KnowledgeEvidenceRetriever`、`QwenKnowledgeVectorStore`、`GroundedDiagnosisGenerator` | 按作物、物候、文档来源和证据适用范围测量 Recall@K、nDCG、证据支持率，分开比较结构化命中、BM25、密集召回、RRF 与重排。 |
| P1 | `FarmMaturityService`、`CameraSnapshotProvider` | 把图像采集和识别模块继续解耦，可插拔引入检测模型。记录设备、画面质量、有效覆盖和人工复核标签，单独评估视觉识别与地图覆盖。 |
| P2 | `CopilotLongTermMemoryService`、呈现协议 | 扩展偏好冲突与过期处理，评估记忆注入前后的可控性。保持业务卡通过服务端 ID 更新，引入明确的阶段状态和数据来源标记。 |

## 9. 源码精确索引

每个区间以当前文件的物理行计数。较长区间覆盖一个连续实现段，相邻方法或不同层的实现分别列出。

### 9.1 API、Pi 与模型

| 模块或行为 | 实现位置（含首尾行） | 方法或关键对象 |
| --- | --- | --- |
| 登录聊天、路由与会话 API | [CopilotController.java 第 217–247 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java#L217) · [CopilotController.java 第 306–461 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java#L306) | 会话创建、历史、恢复、路由、SSE 认证入口和分支。 |
| 兼容 JSON 回答 | [CopilotController.java 第 2214–2331 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java#L2214) · [CopilotController.java 第 2333–2380 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java#L2333) | answer、answerWithPi。 |
| 访客入口 | [GuestCopilotController.java 第 28–41 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/GuestCopilotController.java#L28) · [GuestCopilotController.java 第 43–89 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/GuestCopilotController.java#L43) | 限制访客输入与频率，建立不带登录会话的生成请求。 |
| Pi 依赖与配置 | [package.json 第 1 行](../zhgy/zhgy/pi-runtime/package.json#L1) · [application.yml 第 91–108 行](../zhgy/zhgy/src/main/resources/application.yml#L91) · [Dockerfile 第 7–19 行](../zhgy/zhgy/Dockerfile#L7) · [run-backend-local.ps1 第 114–123 行](../scripts/run-backend-local.ps1#L114) | 锁定 Pi Core 与 pi-ai 版本，Node worker 本地及容器接入。 |
| Java bridge 正常调用与控制 | [PiAgentRuntime.java 第 18–34 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/pi/PiAgentRuntime.java#L18) · [PiAgentRuntime.java 第 42–106 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/pi/PiAgentRuntime.java#L42) · [PiAgentRuntime.java 第 108–120 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/pi/PiAgentRuntime.java#L108) | 数据结构、请求 ID、事件过滤与终态。 |
| worker 启动与故障回收 | [PiAgentRuntime.java 第 122–191 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/pi/PiAgentRuntime.java#L122) · [worker.mjs 第 162–166 行](../zhgy/zhgy/pi-runtime/worker.mjs#L162) | 子进程白名单、消息通道、超时取消和进程销毁。 |
| Pi 状态、会话对齐和工具注册 | [worker.mjs 第 39–75 行](../zhgy/zhgy/pi-runtime/worker.mjs#L39) · [worker.mjs 第 76–111 行](../zhgy/zhgy/pi-runtime/worker.mjs#L76) | 会话缓存、从历史恢复、快照更新、新建 Agent、轮次／工具限制。 |
| Pi 上下文与投影 | [worker.mjs 第 8–38 行](../zhgy/zhgy/pi-runtime/worker.mjs#L8) · [worker.mjs 第 99–111 行](../zhgy/zhgy/pi-runtime/worker.mjs#L99) · [worker.mjs 第 113–137 行](../zhgy/zhgy/pi-runtime/worker.mjs#L113) · [CopilotController.java 第 1604–1623 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java#L1604) | 裁剪、模型适配、资料注入、文本与工具事件。 |
| Pi 控制端到端 | [CopilotController.java 第 249–297 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java#L249) · [worker.mjs 第 140–158 行](../zhgy/zhgy/pi-runtime/worker.mjs#L140) · [service.ts 第 131–151 行](../farmap-frontend-dev/src/features/copilot/service.ts#L131) · [CopilotDrawer.tsx 第 450–456 行](../farmap-frontend-dev/src/components/copilot/CopilotDrawer.tsx#L450) · [CopilotDrawer.tsx 第 1515–1585 行](../farmap-frontend-dev/src/components/copilot/CopilotDrawer.tsx#L1515) · [CopilotDrawer.tsx 第 2148–2151 行](../farmap-frontend-dev/src/components/copilot/CopilotDrawer.tsx#L2148) · [CopilotDrawer.tsx 第 2238 行](../farmap-frontend-dev/src/components/copilot/CopilotDrawer.tsx#L2238) | abort、continue、steer、followUp 的 API 和 worker 状态条件。 |
| 模型实际调用与策略 | [farmap-models.yml 第 39–57 行](../zhgy/zhgy/src/main/resources/farmap-models.yml#L39) · [run-backend-local.ps1 第 52–54 行](../scripts/run-backend-local.ps1#L52) · [ModelGateway.java 第 39–144 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/ai/ModelGateway.java#L39) · [BailianModelClient.java 第 259–338 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/ai/BailianModelClient.java#L259) · [BailianModelClient.java 第 348–406 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/ai/BailianModelClient.java#L348) · [BailianModelClient.java 第 453–515 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/ai/BailianModelClient.java#L453) | OpenCode 开关、统一路由、SSE 与视觉消息格式。 |

### 9.2 意图判定与作用域

| 模块或行为 | 实现位置（含首尾行） | 方法或关键对象 |
| --- | --- | --- |
| 意图枚举、文本归一 | [CopilotIntent.java 第 1–24 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/CopilotIntent.java#L1) · [CopilotQueryNormalizer.java 第 17–39 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/CopilotQueryNormalizer.java#L17) · [CopilotQueryNormalizer.java 第 42–44 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/CopilotQueryNormalizer.java#L42) | 外部意图类型与规则匹配归一。 |
| 规则识别与优先级 | [CopilotIntentRouter.java 第 17–37 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/CopilotIntentRouter.java#L17) · [CopilotIntentRouter.java 第 39–128 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/CopilotIntentRouter.java#L39) · [CopilotIntentRouter.java 第 219–241 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/CopilotIntentRouter.java#L219) | 固定规则、成熟度识别、诊断与知识区分。 |
| 混合分类、委托及仲裁 | [CopilotIntentRouter.java 第 146–189 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/CopilotIntentRouter.java#L146) · [CopilotIntentRouter.java 第 197–224 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/CopilotIntentRouter.java#L197) · [farmap-models.yml 第 59–99 行](../zhgy/zhgy/src/main/resources/farmap-models.yml#L59) | 上传图片信号、保护规则、候选结果覆盖政策与模式。 |
| 模型提示词、候选与传输 | [IntentPromptBuilder.java 第 11–65 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/intent/IntentPromptBuilder.java#L11) · [IntentModelPool.java 第 31–62 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/intent/IntentModelPool.java#L31) · [BailianIntentClassifierClient.java 第 24–61 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/intent/BailianIntentClassifierClient.java#L24) | JSON 契约、OpenCode 单模型与旧候选池。 |
| 超时、缓存、解析和可观测性 | [LlmIntentClassifier.java 第 110–176 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/intent/LlmIntentClassifier.java#L110) · [LlmIntentClassifier.java 第 178–230 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/intent/LlmIntentClassifier.java#L178) · [LlmIntentClassifier.java 第 232–344 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/intent/LlmIntentClassifier.java#L232) · [LlmIntentClassifier.java 第 364–378 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/intent/LlmIntentClassifier.java#L364) · [LlmIntentClassifier.java 第 416–443 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/intent/LlmIntentClassifier.java#L416) | 判定模式、候选包、槽位、信任范围、失败和指标。 |
| 意图到能力和请求持久化 | [CopilotRouteService.java 第 40–105 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/CopilotRouteService.java#L40) · [CopilotRouteService.java 第 108–157 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/CopilotRouteService.java#L108) · [CopilotRouteService.java 第 159–219 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/CopilotRouteService.java#L159) · [CopilotRouteService.java 第 239–288 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/CopilotRouteService.java#L239) | route、规则/LLM 诊断字段、简短追问、意图和能力映射。 |
| 业务对象的合并和权限 | [CopilotContextResolver.java 第 57–213 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/CopilotContextResolver.java#L57) · [CopilotContextResolver.java 第 215–298 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/CopilotContextResolver.java#L215) · [FarmFieldService.java 第 89–97 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/field/FarmFieldService.java#L89) · [FarmFieldService.java 第 178–196 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/field/FarmFieldService.java#L178) | 请求、页面及短期对象、作用域校验、农场目录权限。 |
| 显式任务和农场目录路线 | [CopilotOperationQuery.java 第 19–109 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/CopilotOperationQuery.java#L19) · [CopilotOperationQuery.java 第 135–139 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/CopilotOperationQuery.java#L135) · [CopilotController.java 第 578–588 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java#L578) · [CopilotController.java 第 636–779 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java#L636) | 任务视图、日期和地块匹配、账号农场列表。 |
| 附件用途识别 | [CopilotMediaPolicy.java 第 13–37 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/CopilotMediaPolicy.java#L13) · [CopilotController.java 第 1060–1096 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java#L1060) · [CopilotController.java 第 2121–2159 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java#L2121) | 参考图与现场证据分流、受控图片来源校验。 |

### 9.3 短期上下文和长期记忆

| 模块或行为 | 实现位置（含首尾行） | 方法或关键对象 |
| --- | --- | --- |
| 客户端作用域与请求 | [service.ts 第 56–84 行](../farmap-frontend-dev/src/features/copilot/service.ts#L56) · [CopilotPageContext.java 第 1–15 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/CopilotPageContext.java#L1) | 页面上下文和业务实体引用。 |
| 短期会话与版本 | [CopilotConversationService.java 第 37–70 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/CopilotConversationService.java#L37) · [CopilotConversationService.java 第 85–179 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/CopilotConversationService.java#L85) · [CopilotConversationService.java 第 187–249 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/CopilotConversationService.java#L187) · [CopilotConversationService.java 第 265–328 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/CopilotConversationService.java#L265) | 去重、恢复、视图、版本提交、答案回写、会话摘要。 |
| 短期上下文预算及会话可见性 | [CopilotContextAssembler.java 第 17–59 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/CopilotContextAssembler.java#L17) · [CopilotConversationTurnPolicy.java 第 22–27 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/CopilotConversationTurnPolicy.java#L22) · [CopilotSessionContextStore.java 第 21–57 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/CopilotSessionContextStore.java#L21) · [CopilotController.java 第 1562–1598 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java#L1562) · [CopilotController.java 第 2381–2425 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java#L2381) | 会话视图预算、兼容会话、Pi 历史和文本压缩。 |
| 会话存储与一致性 | [JdbcCopilotConversationRepository.java 第 38–83 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/JdbcCopilotConversationRepository.java#L38) · [JdbcCopilotConversationRepository.java 第 86–119 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/JdbcCopilotConversationRepository.java#L86) · [JdbcCopilotConversationRepository.java 第 147–155 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/JdbcCopilotConversationRepository.java#L147) | 按账号查询，事务提交，copilot_conversations、copilot_turns。 |
| 长期记忆策略与写入 | [CopilotLongTermMemoryService.java 第 43–108 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/CopilotLongTermMemoryService.java#L43) · [CopilotLongTermMemoryService.java 第 110–173 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/CopilotLongTermMemoryService.java#L110) · [CopilotRouteService.java 第 66–99 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/CopilotRouteService.java#L66) · [CopilotController.java 第 2509–2519 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java#L2509) | 显式记忆、结构化提取、查询与撤销。 |
| 长期记忆仓储和模型注入 | [JdbcCopilotLongTermMemoryRepository.java 第 26–78 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/JdbcCopilotLongTermMemoryRepository.java#L26) · [JdbcCopilotLongTermMemoryRepository.java 第 91–98 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/JdbcCopilotLongTermMemoryRepository.java#L91) · [CopilotController.java 第 1546–1552 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java#L1546) | ACTIVE 范围、替代、撤销，以及只读偏好资料。 |

### 9.4 农业推理和所有生成式只读工具

| 模块或行为 | 实现位置（含首尾行） | 方法或关键对象 |
| --- | --- | --- |
| 地块事实、作物名录与日历 | [FieldFactsService.java 第 28–73 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/agriculture/FieldFactsService.java#L28) · [CropCatalog.java 第 7–17 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/agriculture/CropCatalog.java#L7) · [CropResolver.java 第 17–43 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/agriculture/CropResolver.java#L17) · [PhenologyService.java 第 25–54 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/agriculture/PhenologyService.java#L25) · [PhenologyService.java 第 89–117 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/agriculture/PhenologyService.java#L89) · [PhenologyService.java 第 125–175 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/agriculture/PhenologyService.java#L125) | 作物归一、日期与登记物候、阶段相邻关系。 |
| 事实汇聚、阶段问答与来源分层 | [CopilotController.java 第 1194–1309 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java#L1194) · [CopilotController.java 第 1627–1724 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java#L1627) · [CopilotController.java 第 1757–1828 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java#L1757) · [CopilotController.java 第 1377–1499 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java#L1377) | 把数据库、通用物候和知识证据分开，兼顾模型上下文及直答。 |
| 月度农事指导 | [CopilotController.java 第 819–844 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java#L819) · [AgriculturalContextService.java 第 118–123 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/AgriculturalContextService.java#L118) · [AgriculturalContextService.java 第 174–184 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/AgriculturalContextService.java#L174) | 目标月份和可取得的农事指导。 |
| `read_previous_visual_analysis` | [CopilotController.java 第 1522–1536 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java#L1522) | 按报告 ID 和农场范围读摘要。 |
| `read_field_context` / `read_agricultural_knowledge` | [CopilotController.java 第 1518–1539 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java#L1518) | 地块、物候、知识来源按各自工具注入。 |
| `read_monthly_guidance` / `read_current_weather` | [CopilotController.java 第 1540–1545 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java#L1540) | 有数据时注册。 |
| `read_user_preferences` / `read_workflow_status` | [CopilotController.java 第 1546–1561 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java#L1546) | 显式偏好及授权工作流状态。 |
| 快照注册、事实提示与 Pi 调用 | [worker.mjs 第 94–111 行](../zhgy/zhgy/pi-runtime/worker.mjs#L94) · [CopilotController.java 第 1574–1598 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java#L1574) | 无参数只读工具、角色转换、提示词和作用域。 |
| 天气业务直答及专用数据 | [CopilotController.java 第 1924–1989 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java#L1924) · [WeatherPresentationCard.tsx 第 157–280 行](../farmap-frontend-dev/src/features/copilot/WeatherPresentationCard.tsx#L157) | 天气服务实时结果、结构化天气数据与缺失字段。 |
| 农场天气服务 | [CurrentWeatherService.java 第 21–50 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/CurrentWeatherService.java#L21) | 实时气象查找、授权与缺测状态。 |

### 9.5 知识 RAG 与多模态证据

| 模块或行为 | 实现位置（含首尾行） | 方法或关键对象 |
| --- | --- | --- |
| 文档上传、提取和激活 | [KnowledgeIngestionService.java 第 98–209 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/knowledge/KnowledgeIngestionService.java#L98) · [KnowledgeIngestionService.java 第 264–405 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/knowledge/KnowledgeIngestionService.java#L264) · [KnowledgeDocumentExtractor.java 第 21–50 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/knowledge/KnowledgeDocumentExtractor.java#L21) · [KnowledgeChunker.java 第 13–48 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/knowledge/KnowledgeChunker.java#L13) · [KnowledgeIngestionService.java 第 651–671 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/knowledge/KnowledgeIngestionService.java#L651) | 验权、文件哈希、版本、提取、分块、索引、激活和作物标注。 |
| 知识仓储和当前版本 | [KnowledgeBackendService.java 第 49–99 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/knowledge/KnowledgeBackendService.java#L49) · [KnowledgeBackendService.java 第 109–126 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/knowledge/KnowledgeBackendService.java#L109) · [application.yml 第 118–132 行](../zhgy/zhgy/src/main/resources/application.yml#L118) | 来源、文档、版本、可见范围及检索配置。 |
| 知识查询构造与改写 | [CopilotController.java 第 1311–1370 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java#L1311) · [KnowledgeQueryRewriter.java 第 27–107 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/knowledge/KnowledgeQueryRewriter.java#L27) · [AgriculturalStructuredKnowledgeService.java 第 57–79 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/knowledge/AgriculturalStructuredKnowledgeService.java#L57) · [AgriculturalStructuredKnowledgeService.java 第 104–167 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/knowledge/AgriculturalStructuredKnowledgeService.java#L104) | 作物和物候过滤、指代、动态 Top-K、结构化农事知识。 |
| 召回装配、兼容分支和溯源 | [KnowledgeEvidenceRetriever.java 第 126–253 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/rag/KnowledgeEvidenceRetriever.java#L126) · [KnowledgeEvidenceRetriever.java 第 264–343 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/rag/KnowledgeEvidenceRetriever.java#L264) · [KnowledgeEvidenceRetriever.java 第 358–386 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/rag/KnowledgeEvidenceRetriever.java#L358) | 已配置向量库选择、作物约束、搜索状态、来源映射、可选重排。 |
| Milvus、BM25 和 RRF | [QwenKnowledgeVectorStore.java 第 240–349 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/knowledge/QwenKnowledgeVectorStore.java#L240) · [QwenKnowledgeVectorStore.java 第 351–491 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/knowledge/QwenKnowledgeVectorStore.java#L351) · [QwenKnowledgeVectorStore.java 第 493–568 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/knowledge/QwenKnowledgeVectorStore.java#L493) · [KnowledgeBm25.java 第 1–129 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/knowledge/KnowledgeBm25.java#L1) | 密集和稀疏召回、RRF/加权融合、索引缓存与可见性。 |
| 独立检索模型 | [ConfiguredKnowledgeEmbeddingProvider.java 第 1–41 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/knowledge/ConfiguredKnowledgeEmbeddingProvider.java#L1) · [BailianModelClient.java 第 129–201 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/ai/BailianModelClient.java#L129) | 知识向量与重排 API，区别于生成模型调用。 |
| 知识库管理 API 与向量依赖 | [AdminKnowledgeController.java 第 29–52 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/AdminKnowledgeController.java#L29) · [KnowledgeSearchController.java 第 35–77 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/KnowledgeSearchController.java#L35) · [BailianKnowledgeEmbeddingProvider.java 第 33–56 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/knowledge/BailianKnowledgeEmbeddingProvider.java#L33) · [BailianKnowledgeReranker.java 第 1–29 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/knowledge/BailianKnowledgeReranker.java#L1) | 导入、检索 API 和嵌入/重排适配。 |
| 图像和文本本地 embedding 接口 | [LocalClipEmbeddingProvider.java 第 24–44 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/ai/LocalClipEmbeddingProvider.java#L24) · [LocalBgeEmbeddingProvider.java 第 28–53 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/ai/LocalBgeEmbeddingProvider.java#L28) | CLIP 图像和 BGE 文本向量。 |
| 参考图与知识数量 | [CopilotController.java 第 1012–1024 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java#L1012) · [CopilotController.java 第 1060–1091 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java#L1060) · [ReferenceImageAttacher.java 第 56–95 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/reference/ReferenceImageAttacher.java#L56) | 知识库结果条数、病害或物候图鉴。 |
| 模态收集和融合 | [DiagnosisRagService.java 第 81–266 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/rag/DiagnosisRagService.java#L81) · [DiagnosisRagService.java 第 306–414 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/rag/DiagnosisRagService.java#L306) · [EvidenceFusionService.java 第 29–66 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/rag/EvidenceFusionService.java#L29) | 检索器状态、去重与融合、生成和证据模板。 |
| 视觉与历史案例 | [VisualEvidenceRetriever.java 第 58–223 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/rag/VisualEvidenceRetriever.java#L58) · [VisualEvidenceRetriever.java 第 237–287 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/rag/VisualEvidenceRetriever.java#L237) · [UploadedImageAssetIndexService.java 第 1–214 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/rag/UploadedImageAssetIndexService.java#L1) · [HistoricalCaseEvidenceRetriever.java 第 43–146 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/rag/HistoricalCaseEvidenceRetriever.java#L43) | 图片观测、上传图索引和基于历史案例的证据。 |
| 诊断环境证据适配 | [WeatherEvidenceRetriever.java 第 21–66 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/rag/WeatherEvidenceRetriever.java#L21) · [SoilEvidenceRetriever.java 第 21–49 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/rag/SoilEvidenceRetriever.java#L21) · [SpatialEvidenceRetriever.java 第 21–52 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/rag/SpatialEvidenceRetriever.java#L21) · [SatelliteEvidenceRetriever.java 第 21–48 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/rag/SatelliteEvidenceRetriever.java#L21) · [AccumulatedTemperatureEvidenceRetriever.java 第 19–39 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/rag/AccumulatedTemperatureEvidenceRetriever.java#L19) | 将气象、土壤、空间、卫星与积温数据变为证据对象。 |
| 约束诊断生成 | [GroundedDiagnosisGenerator.java 第 42–188 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/ai/GroundedDiagnosisGenerator.java#L42) · [GroundedDiagnosisGenerator.java 第 201–245 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/ai/GroundedDiagnosisGenerator.java#L201) | 证据提示、结构化输出、验证和一次修复。 |
| 异步诊断与持久化 | [CopilotController.java 第 877–929 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java#L877) · [CopilotController.java 第 1139–1192 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java#L1139) · [DiagnosisJobService.java 第 48–114 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/diagnosis/DiagnosisJobService.java#L48) · [DiagnosisPersistenceService.java 第 37–69 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/diagnosis/DiagnosisPersistenceService.java#L37) · [DiagnosisPersistenceService.java 第 76–202 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/diagnosis/DiagnosisPersistenceService.java#L76) | 诊断作业、SSE、结果入库与已保存证据的复用。 |

### 9.6 专项视觉、工作流与业务持久化

| 模块或行为 | 实现位置（含首尾行） | 方法或关键对象 |
| --- | --- | --- |
| 成熟度和图片分析路由 | [CopilotController.java 第 463–539 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java#L463) · [CopilotController.java 第 541–576 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java#L541) · [CopilotController.java 第 846–870 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java#L846) | 四种范围、历史/排行、真实分析进度、业务报告和采收建议。 |
| 摄像头目录与监测区绑定 | [FarmCameraService.java 第 94–158 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/camera/FarmCameraService.java#L94) · [FarmCameraService.java 第 207–228 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/camera/FarmCameraService.java#L207) · [CameraBindingService.java 第 17–50 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/maturity/CameraBindingService.java#L17) · [MaturityAnalysisController.java 第 24–30 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/MaturityAnalysisController.java#L24) | 设备归属、真实地块与区域、演示绑定和绑定维护 API。 |
| 图片来源、离线与近期帧 | [CameraSnapshotProvider.java 第 50–89 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/camera/CameraSnapshotProvider.java#L50) · [CameraSnapshotProvider.java 第 91–155 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/camera/CameraSnapshotProvider.java#L91) · [CameraSnapshotProvider.java 第 157–175 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/camera/CameraSnapshotProvider.java#L157) · [MonitorController.java 第 197–234 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/MonitorController.java#L197) · [application.yml 第 152–160 行](../zhgy/zhgy/src/main/resources/application.yml#L152) | 近期浏览器帧、受控厂商抓图、存储和图像来源。 |
| 分析、提示词与区间聚合 | [FarmMaturityService.java 第 99–208 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/maturity/FarmMaturityService.java#L99) · [FarmMaturityService.java 第 210–253 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/maturity/FarmMaturityService.java#L210) · [FarmMaturityService.java 第 266–297 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/maturity/FarmMaturityService.java#L266) | 鉴权、采样、并发、可见性/质量、分类标签、区域 UNKNOWN/MIXED。 |
| 分析报告和地图展示 | [FarmMaturityService.java 第 254–264 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/maturity/FarmMaturityService.java#L254) · [MaturityAnalysisController.java 第 17–23 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/MaturityAnalysisController.java#L17) · [MaturityWorkspace.tsx 第 52–149 行](../farmap-frontend-dev/src/features/maturity/MaturityWorkspace.tsx#L52) · [geometry.ts 第 9–32 行](../farmap-frontend-dev/src/features/maturity/geometry.ts#L9) · [geometry.ts 第 175–208 行](../farmap-frontend-dev/src/features/maturity/geometry.ts#L175) | 独立报告、历史数据、地图填色和演示地理边界。 |
| Java 工作流入口和固定计划 | [CopilotController.java 第 931–982 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java#L931) · [AgentController.java 第 56–101 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/AgentController.java#L56) · [AgentController.java 第 136–156 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/AgentController.java#L136) · [AgentPlanner.java 第 38–89 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/AgentPlanner.java#L38) · [AgentRuntime.java 第 123–197 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/AgentRuntime.java#L123) | 诊断建议或直接任务创建运行，规划和执行入口。 |
| Java 工具注册、执行和输入 | [ToolRegistry.java 第 13–30 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/ToolRegistry.java#L13) · [ToolExecutor.java 第 16–73 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/ToolExecutor.java#L16) · [AgentDiagnosisRequestFactory.java 第 12–66 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/AgentDiagnosisRequestFactory.java#L12) · [AgentRuntime.java 第 863–899 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/AgentRuntime.java#L863) | 注册、只读属性、执行包装和可信来源约束。 |
| 地块、天气、摄像头、GIS 工具 | [FieldContextTool.java 第 16–49 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/FieldContextTool.java#L16) · [WeatherTool.java 第 9–18 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/WeatherTool.java#L9) · [CameraTool.java 第 9–19 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/CameraTool.java#L9) · [GisTool.java 第 9–19 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/GisTool.java#L9) | field-context、weather、camera、gis。 |
| 诊断、多模态 RAG、历史、知识工具 | [DiagnosisEvidenceTool.java 第 16–27 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/DiagnosisEvidenceTool.java#L16) · [MultimodalRagTool.java 第 13–35 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/MultimodalRagTool.java#L13) · [HistoricalCaseTool.java 第 13–47 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/HistoricalCaseTool.java#L13) · [KnowledgeTool.java 第 10–25 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/KnowledgeTool.java#L10) | diagnosis-evidence、multimodal-rag、historical-case、knowledge。 |
| 重试、重规划与证据门槛 | [AgentRuntime.java 第 199–343 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/AgentRuntime.java#L199) · [AgentRuntime.java 第 345–427 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/AgentRuntime.java#L345) · [AgentReplanner.java 第 22–111 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/AgentReplanner.java#L22) · [EvidenceSufficiencyEvaluator.java 第 26–83 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/EvidenceSufficiencyEvaluator.java#L26) | 步骤状态、有界重试、替代工具、可执行证据要求。 |
| 人工审批、单次执行与幂等任务 | [ApprovalManager.java 第 24–73 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/ApprovalManager.java#L24) · [JdbcApprovalStore.java 第 22–99 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/JdbcApprovalStore.java#L22) · [AgentRuntime.java 第 497–607 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/AgentRuntime.java#L497) · [TaskTool.java 第 16–24 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/TaskTool.java#L16) · [OperationsTaskService.java 第 101–142 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/operations/OperationsTaskService.java#L101) | 待确认动作、执行认领、证据复验、任务幂等创建。 |
| 农事运行和流式回放 | [JdbcAgentRunRepository.java 第 14–39 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/JdbcAgentRunRepository.java#L14) · [AgentEventBroadcaster.java 第 24–86 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/AgentEventBroadcaster.java#L24) · [AgentRuntime.java 第 907–915 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/AgentRuntime.java#L907) · [application.yml 第 97–112 行](../zhgy/zhgy/src/main/resources/application.yml#L97) | 运行数据与事件回放、终态和资源上限。 |
| 采收草稿到确认 | [OperationsController.java 第 111–135 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/OperationsController.java#L111) · [OperationsPlanService.java 第 91–145 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/operations/OperationsPlanService.java#L91) · [OperationsPlanService.java 第 174–291 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/operations/OperationsPlanService.java#L174) · [OperationsPlanService.java 第 338–376 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/operations/OperationsPlanService.java#L338) · [OperationsPlanService.java 第 543–573 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/operations/OperationsPlanService.java#L543) | 从观测报告创建草稿、修改、权限/版本、确认与去重。 |
| 任务排期、执行和结果 | [OperationsController.java 第 49–109 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/OperationsController.java#L49) · [OperationsTaskViewService.java 第 62–124 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/operations/OperationsTaskViewService.java#L62) · [OperationsTaskViewService.java 第 192–248 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/operations/OperationsTaskViewService.java#L192) · [OperationsTaskService.java 第 204–424 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/operations/OperationsTaskService.java#L204) · [OperationsTaskService.java 第 660–666 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/operations/OperationsTaskService.java#L660) | 列表、统计、日历、排期、状态、反馈、任务表与审计事件。 |

### 9.7 Agent 呈现和恢复协议

| 模块或行为 | 实现位置（含首尾行） | 方法或关键对象 |
| --- | --- | --- |
| 结构化结论、分析、跟进项 | [CopilotController.java 第 590–634 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java#L590) · [CopilotController.java 第 1830–1862 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java#L1830) · [CopilotController.java 第 2012–2040 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java#L2012) · [CopilotController.java 第 2093–2102 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java#L2093) | SSE 事件的产生、正文流、卡片数据、结果元数据。 |
| SSE 活动与恢复 | [CopilotActivityJournal.java 第 18–50 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/CopilotActivityJournal.java#L18) · [CopilotConversationService.java 第 207–263 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/CopilotConversationService.java#L207) · [service.ts 第 103–150 行](../farmap-frontend-dev/src/features/copilot/service.ts#L103) · [CopilotDrawer.tsx 第 340–373 行](../farmap-frontend-dev/src/components/copilot/CopilotDrawer.tsx#L340) | 服务端日志、回放、浏览器 SSE 和持久化数据恢复。 |
| 操作和阶段顺序的合并 | [presentation.ts 第 14–59 行](../farmap-frontend-dev/src/features/copilot/presentation.ts#L14) · [CopilotDrawer.tsx 第 842–977 行](../farmap-frontend-dev/src/components/copilot/CopilotDrawer.tsx#L842) · [CopilotDrawer.tsx 第 2063–2127 行](../farmap-frontend-dev/src/components/copilot/CopilotDrawer.tsx#L2063) | 暂存先到的卡片、按阶段呈现、保持卡片位置和后续按钮。 |
| 可见过程与知识数 | [chatActivity.ts 第 10–69 行](../farmap-frontend-dev/src/features/copilot/chatActivity.ts#L10) · [CopilotTurnActivity.tsx 第 1–31 行](../farmap-frontend-dev/src/components/copilot/CopilotTurnActivity.tsx#L1) · [CopilotController.java 第 1012–1024 行](../zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java#L1012) | 归一化运行与工具事件，最多展示工具状态和知识数量。 |
| 业务组件协议与视图 | [uiBlocks.ts 第 1–67 行](../farmap-frontend-dev/src/features/copilot/uiBlocks.ts#L1) · [uiBlocks.ts 第 228–265 行](../farmap-frontend-dev/src/features/copilot/uiBlocks.ts#L228) · [AgentUiBlocks.tsx 第 107–266 行](../farmap-frontend-dev/src/features/copilot/AgentUiBlocks.tsx#L107) · [AgentUiBlocks.tsx 第 300–429 行](../farmap-frontend-dev/src/features/copilot/AgentUiBlocks.tsx#L300) | 组件白名单、字段校验、进度、分析报告、天气、农场、任务与工作流。 |
| 任务草稿和执行反馈界面 | [OperationsChatArtifacts.tsx 第 185–525 行](../farmap-frontend-dev/src/features/copilot/OperationsChatArtifacts.tsx#L185) · [OperationsChatArtifacts.tsx 第 527–865 行](../farmap-frontend-dev/src/features/copilot/OperationsChatArtifacts.tsx#L527) · [OperationsTaskViews.tsx 第 449–826 行](../farmap-frontend-dev/src/features/copilot/OperationsTaskViews.tsx#L449) | 从业务 ID 加载草稿、提交确认、查看任务和保存执行反馈。 |
| 工作区、滚动与发起追问 | [CopilotDrawer.tsx 第 465–483 行](../farmap-frontend-dev/src/components/copilot/CopilotDrawer.tsx#L465) · [CopilotDrawer.tsx 第 2122–2146 行](../farmap-frontend-dev/src/components/copilot/CopilotDrawer.tsx#L2122) · [CopilotDrawer.tsx 第 2283–2294 行](../farmap-frontend-dev/src/components/copilot/CopilotDrawer.tsx#L2283) · [CopilotWorkspace.css 第 294–299 行](../farmap-frontend-dev/src/components/copilot/CopilotWorkspace.css#L294) | 跟随新消息、跳转最新、报告工作区和依当前对象填充提示词。 |

## 10. 典型可复现调用链

| 场景 | 代码路径追踪 | 应观察到的业务结果 |
| --- | --- | --- |
| 「当前有哪些农场」 | 规则优先识别农场目录 → 目录范围解析 → `accessibleFarms` → `FarmDirectoryCard` | 列表元素为账户可访问的农场，响应中有目录结论和结构化农场信息。 |
| 「李现在是什么物候期，这个阶段要做什么」 | 作物别名解析 → 服务端日历／登记阶段 → 目标阶段知识查询 → Pi 只读资料 → 生成指导 | 保留已核定阶段，农事操作依据来自适用阶段的知识或提示中的明确证据缺口。 |
| 「褐腐病是什么样」与「分析我上传的叶片」 | 前者按知识/图鉴检索，后者先判断现场图是否可信，再进入诊断队列和多模态检索 | 可分别观察知识条数与参考图、诊断证据与结构化诊断结果。 |
| 「分析 CAM-01 成熟度」 | 规则进入视觉分析 → 当前农场找设备及绑定 → 取图 → 结构解析 → 持久化 `runId` | 可用画面生成视觉证据，设备离线或取图缺失有独立状态，结果可通过报告 ID 回查。 |
| 「帮我安排采收」与「本周任务」 | 采收进入报告支持的草稿或待审批流程；任务列表按农场和日期查业务接口 | 确认后生成实际任务，后续新会话可查询和更新任务排期。 |

## 11. 回归核对与论文实验边界

本次在当前工作区运行了 Pi Node 测试和两组 Java 回归。结果为 **Pi 9/9、Java 65/65，失败与跳过均为 0**。测试包括真实 Pi 库配本地 OpenAI-compatible 模拟服务的工具循环与 SSE 桥接，以及使用受控事实的路由、记忆、召回、视觉报告和农事写入门槛。它们证明功能路径和契约。真实相机可用率、现场成熟度准确率、在线模型质量和端到端性能需要另行采集实验数据。

下方索引给出复现实验与可复用的测试入口。Java 第一组 30 项在 `-Dfarmap.pi.integration=true` 下运行，覆盖 `CopilotPiRuntimeIntegrationTest`、`CopilotOperationArtifactsTest`、`FarmMaturityCopilotTest`、`CopilotConversationHistoryTest`、两套短期/长期记忆评估、`HybridKnowledgeRetrieverTest`、`KnowledgeEvidenceRetrieverTest`、`EvidenceSufficiencyEvaluatorTest` 与 `AgentRuntimeTest`。第二组 35 项覆盖规则与混合路由、分类器与模型池、作物物候、复合指导、成熟度服务及知识溯源。

论文评估建议将意图及槽位、知识证据、业务功能、视觉与空间覆盖拆成独立实验。每次记录模型和开关、作物、天气和影像是否可用、请求通过的执行分支、回退状态和证据有效性。对影子模式单独评价模型预测，对混合模式另外评价实际路由，以免把候选准确率当成生效路由准确率。

### 11.1 验证源文件索引

| 模块或行为 | 实现位置（含首尾行） | 方法或关键对象 |
| --- | --- | --- |
| Pi 原生循环和中断 | [worker.test.mjs 第 8–77 行](../zhgy/zhgy/pi-runtime/worker.test.mjs#L8) · [CopilotPiRuntimeIntegrationTest.java 第 29–139 行](../zhgy/zhgy/src/test/java/com/mei/zhgy/controller/CopilotPiRuntimeIntegrationTest.java#L29) | 只读工具调用、结果回传、裁剪、版本、会话隔离、Spring 到实际 Node 进程。 |
| 意图及模式分支 | [CopilotIntentHybridTest.java 第 19–180 行](../zhgy/zhgy/src/test/java/com/mei/zhgy/service/copilot/CopilotIntentHybridTest.java#L19) · [LlmIntentClassifierTest.java 第 1–177 行](../zhgy/zhgy/src/test/java/com/mei/zhgy/service/copilot/intent/LlmIntentClassifierTest.java#L1) | 规则保护、影子/混合模式、上传图转诊断、低置信兜底。 |
| 短期与长期记忆 | [AgentV2Phase15ShortTermMemoryEvaluationTest.java 第 1–226 行](../zhgy/zhgy/src/test/java/com/mei/zhgy/evaluation/AgentV2Phase15ShortTermMemoryEvaluationTest.java#L1) · [AgentV2Phase16LongTermMemoryEvaluationTest.java 第 1–208 行](../zhgy/zhgy/src/test/java/com/mei/zhgy/evaluation/AgentV2Phase16LongTermMemoryEvaluationTest.java#L1) | 跨轮上下文、澄清、记忆隔离和显式偏好生命周期。 |
| 结构化流式呈现 | [agent-presentation.spec.ts 第 49–108 行](../farmap-frontend-dev/e2e/agent-presentation.spec.ts#L49) | 可用现有浏览器测试复现卡片顺序、兼容流和天气字段显示。 |
