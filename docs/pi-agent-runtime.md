# FarMap 农事助手的 Pi 运行时

Copilot 的生成式 `/api/copilot/chat` 和 `/api/copilot/answer` 默认使用 `@earendil-works/pi-agent-core`（锁定 1.5.2）。Java 后端通过标准输入和输出启动一个私有 Node worker。Pi 管理消息、模型请求、并行只读调用、上下文裁剪和事件。本地启用 OpenCode 时由 Java 向 worker 传入该模型配置。

Java 在发起生成前完成意图路由与范围校验。Pi 使用工具的 JSON Schema 生成查询参数，收到 tool call 后发出含命令 ID、工具集 ID、调用 ID、工具名及参数的 `tool_call_request`。Java 按原会话和上下文版本匹配注册的处理器，重新检查农场权限及参数，执行实际查询并用 `tool_call_response` 传回结果。知识检索支持关键词、地块和物候限定。天气支持指定已提供的预报日期。Java 可将结果投影为用户界面的卡片与知识计数。系统提示只提供日期、选中对象、作物及基本访问范围，查询资料只随工具结果进入模型上下文。纯物候和气象实况等确定性请求可直接查询回答。

用户发出明确的建任务请求时，Pi 的 `propose_task_draft` 只提交标题、详情与可选日期。Java 校验后保存待确认动作，仍由人工审批、证据校验及幂等业务接口创建任务。专题诊断、采收报告和既有 `/api/agent/runs` 接口继续使用 Java 工作流。

## 会话和执行控制

Java 使用当前用户和会话 ID 定位 Pi，并以会话上下文版本限制执行控制。同一范围内继续提问时，Pi 保留受预算约束的近期消息，并为本轮绑定新的工具表。首次进入、范围切换或 worker 重启时，从 MySQL 最近可见消息初始化。

`POST /api/copilot/conversations/{conversationId}/agent-control` 需要登录，并接收 `operation`、当前 `contextVersion` 和可选的 `message`。版本来自 `/chat` 的 `route` 事件。可用操作为：

- `abort` 中止当前执行。
- `continue` 继续同一版本下尚在 worker 中保留的中止执行。
- `steer` 为进行中的执行排入引导消息。
- `followUp` 为进行中的执行排入在当前工作完成后处理的消息。

控制接口返回 SSE。文本沿用 `content.delta`，结束沿用 `done`，Pi 的模型和工具生命周期通过 `agent.runtime` 输出经过筛选的元数据。页面的停止按钮通过当前上下文版本发送 `abort`。Pi 原生工具记录、正在运行的状态和队列保存在 worker 内存中，跨重启恢复的是 MySQL 中的可见对话。

## 本地和容器运行

后端 Docker 构建包含 Node 及锁定的 worker 依赖。Windows 本地后端脚本会在依赖缺失时准备 worker。若直接启动 Spring 或 JAR，请先在 `zhgy/zhgy/pi-runtime` 执行 `npm ci --ignore-scripts`，再从 `zhgy/zhgy` 启动后端，或用 `FARMAP_PI_WORKER` 指定 worker 的绝对路径。Node 需要 22.19 或更新版本。`FARMAP_PI_ENABLED=false` 可临时切回旧生成路径。

在 `zhgy/zhgy/pi-runtime` 运行 `npm test` 可验证实际 Pi 循环。在后端目录运行 `mvn '-Dfarmap.pi.integration=true' '-Dtest=CopilotPiRuntimeIntegrationTest' test` 可验证 Spring 到 Pi 子进程与 SSE 的链路。两个测试都使用本地 OpenAI 兼容的模拟服务。
