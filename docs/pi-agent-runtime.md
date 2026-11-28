# FarMap 农事助手的 Pi 运行时

Copilot 的生成式 `/api/copilot/chat` 和 `/api/copilot/answer` 默认使用 `@earendil-works/pi-agent-core`（锁定 1.5.2）。Java 后端通过标准输入和输出启动一个私有 Node worker。Pi 管理消息、模型请求、工具调度、上下文裁剪和事件。本地启用 OpenCode 时由 Java 向 worker 传入该模型配置。

登录端由 `CopilotController` 处理 HTTP 鉴权与 SSE 生命周期，`CopilotApplicationService` 接入旧 API 和新 Harness。`CopilotSecurityGuard` 核验农场、地块和图片边界。普通农事问答、农场目录、导航、长期记忆、任务草稿、照片分析和复合问题都由 Pi 编排；Java 只保留空输入、安全硬拦截和明确正式诊断报告的确定性入口。

`AgriculturalAgentHarness` 将固定人格与安全提示、动态环境分别传给 worker。动态环境包含日期、选中对象、作物、登记物候、确认过的自报画像、授权图片引用及本轮技能。Pi 经 `tool_call_request` 和 `tool_call_response` 调用 Java 工具，业务参数在 Java 重新校验。普通工具单次超时 12 秒，多模态工具 35 秒。超时返回可恢复的工具错误，其他命令继续执行。

| 范围 | 工具 |
| --- | --- |
| 农场与物候 | `list_farm_fields`、`read_field_context`、`read_current_weather`、`query_operations_tasks`、`read_monthly_guidance`、`read_orchard_memory` |
| 农场目录、导航与偏好 | `read_farm_directory`、`navigate_workspace`、`manage_user_preference` |
| 多模态检索 | `search_agricultural_knowledge`、`analyze_uploaded_images`、`lookup_reference_atlas`、`search_historical_cases`、`inspect_camera_maturity`、`read_previous_analysis` |
| 呈现与提案 | `render_ui_block`、`propose_task_draft`、`propose_memory_update` |

工具按登录身份、当前农场和请求类型注册。结构化数据卡来自领域查询，声明式风险、对比、清单、图表和澄清表单经过组件及参数校验。图表数值必须能和本轮证据账本中的测量值对上。用户通过 `message.part` 获取工具活动、正文、组件及可点击的证据标识。旧事件仍供兼容客户端使用。

明确的建任务请求可形成带证据的一条或多条待确认草案。Java 核验每项任务的地块、证据归属与日期，确认后才走独立、幂等的业务写入。果园资料提案通过 `POST /api/copilot/memories/proposals/{proposalId}/confirm` 单独确认，确认值保持用户自报来源。

## 路由与工具确认

Pi Harness 的主路由调用 `CopilotIntentRouter.classifyHarness`，只产生四类结果：空输入进入 `CLARIFICATION_REQUIRED`，提示注入或危险系统命令进入 `UNSUPPORTED`，用户明确要求生成正式诊断报告的请求进入 `DIAGNOSIS_REQUEST`，其余请求一律进入 `GENERAL_QA`。这里的“正式诊断”指会进入持久化诊断流程的报告生成，不是普通病害解释或对已有报告的阅读。导航、目录、记忆、任务草稿和图片分析不在 Java 路由中按关键词拆成独立意图；Pi 根据上下文选择已注册工具，Java 在执行每个工具时重新校验身份、农场、地块、图片和业务参数。

`CopilotIntentRouter.isDirectTaskRequest` 是应用层与上下文解析层共享的直接任务判定，只决定任务请求是否可绕过“已有诊断建议”的起点，不创建任务。`CopilotIntentRouter.safeNavigationRoute` 只返回 `/overview`、`/farm/map`、`/operations`、`/diagnosis`、`/analysis`、`/knowledge` 白名单内的页面路径，不接受模型给出的 URL。旧版 `classify` 和 `classifyHybrid` 保留给非 Pi 回退及已有 API；它们的主路径参数和返回记录未改变。

非 Harness 路径仍可使用 `classifyHybrid`。仓库默认 `SHADOW`：规则分类决定实际结果，模型结果只用于异步采样和对照，不会改变导航、记忆、动作或安全边界。显式的 `RULE_ONLY`/`HYBRID` 配置仍受现有保护意图和接管阈值约束。启用 Pi Harness 时，主 `/chat` 路径使用 `classifyHarness`，不会调用这套模型影子分类。默认保持影子的原因是：在真实语料和混淆矩阵证明收益前，不应让模型把含糊表达升级为导航、记忆变更、任务执行或摄像头分析；而任何写入或外部动作仍须经过工具参数校验和用户确认。

工具调用不等于写入授权。`propose_task_draft` 和 `propose_memory_update` 只生成待确认提案；任务草案在 Java 复核证据、地块和日期后仍需人工审批，最终写入使用独立、幂等的业务接口；记忆提案需通过确认接口才会生效，忽略接口不会写入。导航只改变前端页面，照片分析只接受会话中已授权的图片引用。

主对话通过 `read_farm_directory` 按当前登录身份读取授权农场，通过 `navigate_workspace` 生成可点击的站内入口。`manage_user_preference` 在服务端核对本轮用户原话、作用域和安全规则。只有明确授权的保存或撤销才能更新记忆，成功后通过 `memory.changed` 刷新界面。用户对果园资料的独立提案仍使用确认接口。兼容路由已经完成的记忆或导航操作由聊天接口直接确认。

## 会话和执行控制

Java 使用当前用户和会话 ID 定位 Pi，通过上下文版本控制并发。匹配地块等作用域及 `lastCompletedTurnId` 时复用原生状态。首次进入、范围切换或 worker 重启时从 MySQL 还原近轮。上下文预算按模型窗口的 72% 估算，先压缩旧工具结果再整轮裁剪，保留调用与结果配对。SSE 中的用量字段取自 Pi 的实际 `usage`。

## 脆李气象与农艺可信资料

作业气象卡使用 Open-Meteo 的逐小时温度、风速、湿度与降水预报。雨量按开始时间之后六个小时累计，缺测值保留缺失。气象评分与农药标签、认证和安全间隔期分别判定。[Open-Meteo 预报 API 文档](https://open-meteo.com/en/docs)

部署可通过 `farmap.agronomy.evidence-path` 指向由运营者审核的 JSON，结构为 `labels` 与 `orchards`。标签按作物、登记 ID、公开来源、版本和有效期匹配。地块资料按农场、地块和作物匹配，认证、计划采收日、可信物候观测和校准数据均可独立缺省。热量校准仅使用匹配品种、海拔范围、有效日期且明确采用 `FARM_DAILY_TEMP_SUM` 的记录，并检查农场积温时效。该配置缺省时继续提供可信基线和气象信息，缺乏标签及认证证据的施药提案会被拦截。

物候基准历采用中熟青脆李在 500–600 m 的十个连续阶段。基准带内海拔偏移为零，带外按相对 550 m 每 100 m 约偏移 2.67 天取整。已知早熟品种另提前 12 天，晚熟品种按 800–1200 m 的示范区间插值推迟 15–25 天。服务从实际日期减去合计偏移，再匹配基准阶段。卡片展示采用的海拔及品种偏移。审核过的地块资料优先，用户确认的果园画像作为自报补充。缺少可匹配的本地校准时，观测积温仅用于说明现有证据。

会话使用同一个 `traceId` 关联路由、助手回答与执行遥测。完整快照保存在 `copilot_turns.response_metadata` 的 `telemetry` 对象中，记录执行模式、实际模型、首字和总耗时、Token 用量、工具状态及耗时、组件类型和证据层。未测量的数值保留为空。任务审批记录单独保存本轮可用的地块、气象和知识证据，并标明 Agent 草稿说明的来源。回答模型池默认按配置顺序回退。

论文评测使用版本化标签和可重跑的 JSON 报告。评测范围及受控回放的限制见 [脆李 Agent 评测说明](plum-agent-golden-eval.md)。

`POST /api/copilot/conversations/{conversationId}/agent-control` 需要登录，并接收 `operation`、当前 `contextVersion` 和可选的 `message`。版本来自 `/chat` 的 `route` 事件。可用操作为：

- `abort` 中止当前执行。
- `continue` 继续同一版本下尚在 worker 中保留的中止执行。
- `steer` 为进行中的执行排入引导消息。
- `followUp` 为进行中的执行排入在当前工作完成后处理的消息。

控制接口返回 SSE。文本沿用 `content.delta`，结束沿用 `done`，Pi 的模型和工具生命周期通过 `agent.runtime` 输出经过筛选的元数据。页面的停止按钮通过当前上下文版本发送 `abort`。Pi 原生工具记录、正在运行的状态和队列保存在 worker 内存中，跨重启恢复的是 MySQL 中的可见对话。

## 本地和容器运行

后端 Docker 构建包含 Node 及锁定的 worker 依赖。Windows 本地后端脚本会在依赖缺失时准备 worker。若直接启动 Spring 或 JAR，请先在 `zhgy/zhgy/pi-runtime` 执行 `npm ci --ignore-scripts`，再从 `zhgy/zhgy` 启动后端，或用 `FARMAP_PI_WORKER` 指定 worker 的绝对路径。Node 需要 22.19 或更新版本。`FARMAP_PI_ENABLED=false` 可临时切回旧生成路径。

在 `zhgy/zhgy/pi-runtime` 运行 `npm test` 可验证实际 Pi 循环。在后端目录运行 `mvn '-Dfarmap.pi.integration=true' '-Dtest=CopilotPiRuntimeIntegrationTest' test` 可验证 Spring 到 Pi 子进程与 SSE 的链路。两个测试都使用本地 OpenAI 兼容的模拟服务。
